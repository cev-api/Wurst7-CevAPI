/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.itemhandler;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ButtonSetting;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.events.RenderListener;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.phys.AABB;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyBinding;
import net.wurstclient.settings.Setting;
import net.wurstclient.clickgui.components.ItemListEditButton;
import net.wurstclient.clickgui.Component;
import net.wurstclient.util.text.WText;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
// no screen import needed; we embed ItemESP's editor component directly
import net.wurstclient.util.InventoryUtils;

public class ItemHandlerHack extends Hack
	implements UpdateListener, RenderListener
{
	private static final double SCAN_RADIUS = 2.5;
	// When the popup is set to "infinite", use this large but finite
	// radius to avoid passing Infinity into AABB.inflate which breaks
	// entity queries. 1024m should be more than enough for a "global" popup
	// and avoids pathological behavior.
	private static final double INFINITE_SCAN_RADIUS = 1024.0;
	private static final int WHITELIST_TICKS = 3;
	
	private final List<GroundItem> trackedItems = new ArrayList<>();
	private final Int2IntOpenHashMap pickupWhitelist = new Int2IntOpenHashMap();
	private final Deque<Integer> pickupQueue = new ArrayDeque<>();
	private boolean autoWalking;
	// Rejection rules: match by item id, apply within radius, expire after
	// seconds
	private final List<RejectedRule> rejectedRules = new ArrayList<>();
	private final java.util.Map<String, Integer> prevInventoryCounts =
		new java.util.HashMap<>();
	// Items explicitly traced/highlighted via ItemHandler GUI
	private final java.util.Set<String> tracedItems =
		new java.util.LinkedHashSet<>();
	
	private final SliderSetting rejectRadius = new SliderSetting(
		"Reject radius", 3.0, 0.5, 8.0, 0.25, ValueDisplay.DECIMAL);
	
	private final SliderSetting rejectExpiry = new SliderSetting(
		"Pickup/Drop timeout (s)", 15, 5, 120, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting hudEnabled =
		new CheckboxSetting("Show item popup HUD", true);
	
	private final CheckboxSetting showRegistryName =
		new CheckboxSetting("Show registry names", false);
	
	// How many items to show in the popup HUD
	private final SliderSetting popupMaxItems = new SliderSetting(
		"Popup HUD max items", 8, 1, 10, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting popupRange = new SliderSetting(
		"Item detection range", 6.0, 1.0, INFINITE_SCAN_RADIUS, 0.5,
		ValueDisplay.DECIMAL.withLabel(INFINITE_SCAN_RADIUS, "∞"));
	
	// Adjust popup/UI scale (affects text size and icon size heuristically)
	private final SliderSetting popupScale = new SliderSetting(
		"Popup HUD font scale", 0.75, 0.5, 1.5, 0.05, ValueDisplay.DECIMAL);
	
	// Respect ItemESP's ignored items list
	private final CheckboxSetting respectItemEspIgnores =
		new CheckboxSetting("Respect ItemESP ignored items", true);
	
	// Reduce extremes so offsets cannot go off-screen entirely.
	private final SliderSetting hudOffsetX = new SliderSetting(
		"Popup HUD offset X", -105, -300, 300, 1, ValueDisplay.INTEGER);
	private final SliderSetting hudOffsetY = new SliderSetting(
		"Popup HUD offset Y", -15, -200, 200, 1, ValueDisplay.INTEGER);
	
	public ItemHandlerHack()
	{
		super("ItemHandler");
		setCategory(Category.ITEMS);
		addPossibleKeybind("itemhandler gui",
			"ItemHandler GUI (open manual pickup screen)");
		// Top-level button to open the ItemHandler GUI from settings
		addSetting(new ButtonSetting("Open ItemHandler GUI", this::openScreen));
		addSetting(rejectRadius);
		addSetting(rejectExpiry);
		addSetting(hudEnabled);
		addSetting(showRegistryName);
		addSetting(popupRange);
		addSetting(popupScale);
		addSetting(popupMaxItems);
		addSetting(respectItemEspIgnores);
		addSetting(itemEspIgnoredListSetting);
		addSetting(hudOffsetX);
		addSetting(hudOffsetY);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		trackedItems.clear();
		pickupWhitelist.clear();
		pickupQueue.clear();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		trackedItems.clear();
		pickupWhitelist.clear();
		pickupQueue.clear();
		stopAutoWalk();
		rejectedRules.clear();
		endPickFilterSession();
		
		if(MC.screen instanceof ItemHandlerScreen)
			MC.setScreen(null);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null || MC.player == null)
		{
			trackedItems.clear();
			pickupWhitelist.clear();
			pickupQueue.clear();
			stopAutoWalk();
			return;
		}
		
		updateWhitelist();
		scanNearbyItems();
		updateRejectedRules();
		updatePickFilterTimeout();
		processRejectedPickup();
		processPickupQueue();
	}
	
	private void updatePickFilterTimeout()
	{
		if(!pickFilterActive)
			return;
		if(pickFilterTimeoutMs <= 0)
			return;
		long now = System.currentTimeMillis();
		if(now - pickFilterStartMs > pickFilterTimeoutMs)
		{
			endPickFilterSession();
			ChatUtils.message("Pick filter: timeout reached, stopping.");
		}
	}
	
	private void updateRejectedRules()
	{
		if(rejectedRules.isEmpty())
			return;
		
		long now = System.currentTimeMillis();
		rejectedRules.removeIf(
			r -> r.expiryMillis > 0 && now > r.createdAt + r.expiryMillis);
	}
	
	private void processRejectedPickup()
	{
		if(MC.player == null)
			return;
		
		// Build current counts by item id
		java.util.Map<String, Integer> cur = new java.util.HashMap<>();
		net.minecraft.world.entity.player.Inventory inventory =
			MC.player.getInventory();
		int maxSlots = 45;
		for(int slot = 0; slot < maxSlots; slot++)
		{
			var stack = inventory.getItem(slot);
			if(stack == null || stack.isEmpty())
				continue;
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getKey(stack.getItem()).toString();
			cur.merge(id, stack.getCount(), Integer::sum);
		}
		
		// Compare to previous counts to detect pickups
		if(prevInventoryCounts.isEmpty())
		{
			prevInventoryCounts.putAll(cur);
			return;
		}
		
		for(java.util.Map.Entry<String, Integer> e : cur.entrySet())
		{
			String id = e.getKey();
			int count = e.getValue();
			int prev = prevInventoryCounts.getOrDefault(id, 0);
			if(count <= prev)
				continue;
			
			int gained = count - prev;
			// if player gained items of this id, unmark tracing for that id
			if(gained > 0 && tracedItems.contains(id))
			{
				tracedItems.remove(id);
				ChatUtils.message("Untraced " + id + " after pickup.");
			}
			
			// Pick filter: drop any non-target pickups until a desired item
			// is picked up, then stop the session.
			if(pickFilterActive)
			{
				if(pickFilterIds.contains(id))
				{
					endPickFilterSession();
					continue;
				}
				int remainingToDrop = gained;
				while(remainingToDrop > 0)
				{
					int foundSlot = -1;
					for(int s = 0; s < 45; s++)
					{
						var st = inventory.getItem(s);
						if(st == null || st.isEmpty())
							continue;
						String sid =
							net.minecraft.core.registries.BuiltInRegistries.ITEM
								.getKey(st.getItem()).toString();
						if(sid.equals(id))
						{
							foundSlot = s;
							break;
						}
					}
					if(foundSlot < 0)
						break;
					int networkSlot = InventoryUtils.toNetworkSlot(foundSlot);
					IMC.getInteractionManager().windowClick_THROW(networkSlot);
					remainingToDrop--;
				}
				// skip rejected-rules logic for this id
				continue;
			}
			// Total rejected amount for this id (sum across rules that match
			// player's position)
			int totalRejected = 0;
			for(RejectedRule r : rejectedRules)
			{
				if(r.itemId.equals(id)
					&& r.matchesPosition(MC.player.position()))
					totalRejected += r.amountRemaining;
			}
			
			int toDrop = Math.min(gained, totalRejected);
			if(toDrop <= 0)
				continue;
				
			// Drop 'toDrop' items by repeatedly throwing single items from
			// matching slots
			int remainingToDrop = toDrop;
			while(remainingToDrop > 0)
			{
				int foundSlot = -1;
				for(int s = 0; s < maxSlots; s++)
				{
					var st = inventory.getItem(s);
					if(st == null || st.isEmpty())
						continue;
					String sid =
						net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getKey(st.getItem()).toString();
					if(sid.equals(id))
					{
						foundSlot = s;
						break;
					}
				}
				if(foundSlot < 0)
					break;
				
				int networkSlot = InventoryUtils.toNetworkSlot(foundSlot);
				IMC.getInteractionManager().windowClick_THROW(networkSlot);
				remainingToDrop--;
				
				// update rejected rules amounts (consume rules in insertion
				// order)
				int dropLeft = 1; // we dropped one item
				for(java.util.Iterator<RejectedRule> it =
					rejectedRules.iterator(); it.hasNext() && dropLeft > 0;)
				{
					RejectedRule r = it.next();
					if(!r.itemId.equals(id)
						|| !r.matchesPosition(MC.player.position()))
						continue;
					int dec = Math.min(r.amountRemaining, dropLeft);
					r.amountRemaining -= dec;
					dropLeft -= dec;
					if(r.amountRemaining <= 0)
						it.remove();
				}
			}
		}
		
		// store current counts for next tick
		prevInventoryCounts.clear();
		prevInventoryCounts.putAll(cur);
	}
	
	// Pick filter session: drop non-target pickups until target is picked.
	private boolean pickFilterActive;
	private final java.util.Set<String> pickFilterIds =
		new java.util.HashSet<>();
	
	public void beginPickFilterSession(java.util.Set<String> desiredIds)
	{
		pickFilterIds.clear();
		if(desiredIds != null)
			pickFilterIds.addAll(desiredIds);
		pickFilterActive = !pickFilterIds.isEmpty();
		if(pickFilterActive)
		{
			pickFilterStartMs = System.currentTimeMillis();
			pickFilterTimeoutMs = (long)(rejectExpiry.getValueI() * 1000L);
			ChatUtils.message(
				"Pick filter: dropping non-target pickups until target or timeout.");
		}
	}
	
	public void endPickFilterSession()
	{
		pickFilterActive = false;
		pickFilterIds.clear();
		pickFilterStartMs = 0L;
		pickFilterTimeoutMs = 0L;
	}
	
	// Timeout tracking for pick filter session
	private long pickFilterStartMs;
	private long pickFilterTimeoutMs;
	
	private static final class RejectedRule
	{
		final String itemId;
		final Vec3 center;
		final double radius;
		final long createdAt;
		final long expiryMillis;
		int amountRemaining;
		
		RejectedRule(String itemId, Vec3 center, double radius,
			long expiryMillis, int amount)
		{
			this.itemId = itemId;
			this.center = center;
			this.radius = radius;
			this.createdAt = System.currentTimeMillis();
			this.expiryMillis = expiryMillis;
			this.amountRemaining = amount;
		}
		
		boolean matchesPosition(Vec3 playerPos)
		{
			double dist = playerPos.distanceTo(center);
			return dist <= radius;
		}
	}
	
	private void updateWhitelist()
	{
		if(pickupWhitelist.isEmpty())
			return;
		
		List<Integer> toRemove = new ArrayList<>();
		for(Int2IntMap.Entry entry : pickupWhitelist.int2IntEntrySet())
		{
			int ticks = entry.getIntValue() - 1;
			if(ticks <= 0)
				toRemove.add(entry.getIntKey());
			else
				entry.setValue(ticks);
		}
		
		// Use primitive remove(int) to avoid deprecated remove(Object)
		for(int k : toRemove)
			pickupWhitelist.remove(k);
	}
	
	private void scanNearbyItems()
	{
		LocalPlayer player = MC.player;
		double sliderRange = getPopupRange();
		double scanRadius =
			Math.max(SCAN_RADIUS, sliderRange >= INFINITE_SCAN_RADIUS
				? INFINITE_SCAN_RADIUS : sliderRange);
		
		List<ItemEntity> found = MC.level.getEntitiesOfClass(ItemEntity.class,
			player.getBoundingBox().inflate((float)scanRadius),
			this::shouldTrack);
		
		// Also include XP orbs in the scan
		List<ExperienceOrb> foundOrbs =
			MC.level.getEntitiesOfClass(ExperienceOrb.class,
				player.getBoundingBox().inflate((float)scanRadius),
				o -> o != null && o.isAlive() && !o.isRemoved());
		
		trackedItems.clear();
		for(ItemEntity entity : found)
		{
			ItemStack stack = entity.getItem().copy();
			double distance = entity.distanceTo(player);
			// optional filter: respect ItemESP ignored items
			if(respectItemEspIgnores.isChecked())
			{
				net.wurstclient.hacks.ItemEspHack esp =
					net.wurstclient.WurstClient.INSTANCE.getHax().itemEspHack;
				if(esp != null)
				{
					String id =
						net.wurstclient.util.ItemUtils.getStackId(stack);
					if(esp.isIgnoredId(id))
						continue;
				}
			}
			trackedItems.add(new GroundItem(entity.getId(), entity.getUUID(),
				stack, distance, entity.position()));
		}
		
		for(ExperienceOrb orb : foundOrbs)
		{
			double distance = orb.distanceTo(player);
			// proxy stack for UI only (contains synthetic id + xp metadata)
			ItemStack stack =
				net.wurstclient.util.ItemUtils.createSyntheticXpStack(orb);
			trackedItems.add(new GroundItem(orb.getId(), orb.getUUID(), stack,
				distance, orb.position()));
		}
		
		// Auto-untrace: remove traced ids that no longer have tracked items
		try
		{
			java.util.Set<String> present = new java.util.HashSet<>();
			for(GroundItem gi : trackedItems)
			{
				String baseId =
					net.wurstclient.util.ItemUtils.getStackId(gi.stack());
				if(baseId == null)
					baseId =
						net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getKey(gi.stack().getItem()).toString();
				if(net.wurstclient.util.ItemUtils.isSyntheticXp(gi.stack()))
				{
					int xp =
						net.wurstclient.util.ItemUtils.getXpAmount(gi.stack());
					present.add(baseId + ":xp:" + xp);
				}else
				{
					present.add(baseId);
				}
			}
			for(java.util.Iterator<String> it = tracedItems.iterator(); it
				.hasNext();)
			{
				String t = it.next();
				if(!present.contains(t))
				{
					it.remove();
					ChatUtils.message("Untraced " + t + " after collection.");
				}
			}
		}catch(Throwable ignored)
		{}
	}
	
	private boolean shouldTrack(ItemEntity entity)
	{
		return entity != null && entity.isAlive() && !entity.isRemoved()
			&& !entity.getItem().isEmpty();
	}
	
	private void processPickupQueue()
	{
		while(!pickupQueue.isEmpty())
		{
			if(MC.player == null || MC.level == null)
			{
				pickupQueue.clear();
				break;
			}
			
			int targetId = pickupQueue.peek();
			ItemEntity target = getItemEntity(targetId);
			if(target == null || target.isRemoved())
			{
				pickupQueue.poll();
				continue;
			}
			
			double distance = target.distanceTo(MC.player);
			if(distance <= SCAN_RADIUS + 0.2)
			{
				whitelist(targetId);
				pickupQueue.poll();
				continue;
			}
			
			driveToward(target);
			return;
		}
		
		stopAutoWalk();
	}
	
	private ItemEntity getItemEntity(int id)
	{
		Entity entity = MC.level.getEntity(id);
		return entity instanceof ItemEntity item ? item : null;
	}
	
	private void driveToward(ItemEntity target)
	{
		if(!(MC.player instanceof LocalPlayer))
			return;
		
		Vec3 targetPos = target.position();
		WURST.getRotationFaker().faceVectorClientIgnorePitch(targetPos);
		IKeyBinding.get(MC.options.keyUp).simulatePress(true);
		autoWalking = true;
	}
	
	private void stopAutoWalk()
	{
		if(!autoWalking)
			return;
		
		IKeyBinding.get(MC.options.keyUp).resetPressedState();
		autoWalking = false;
	}
	
	private void whitelist(int entityId)
	{
		pickupWhitelist.put(entityId, WHITELIST_TICKS);
	}
	
	public boolean shouldAllowPickup(ItemEntity entity)
	{
		return entity != null && pickupWhitelist.containsKey(entity.getId());
	}
	
	public List<GroundItem> getTrackedItems()
	{
		return List.copyOf(trackedItems);
	}
	
	public void openScreen()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		Screen prev = MC.screen;
		MC.setScreen(new ItemHandlerScreen(prev, this));
	}
	
	public void requestPickup(Collection<Integer> entityIds)
	{
		if(entityIds == null || entityIds.isEmpty())
			return;
		
		for(int entityId : entityIds)
		{
			if(entityId == 0 || pickupQueue.contains(entityId))
			{
				whitelist(entityId);
				continue;
			}
			pickupQueue.add(entityId);
		}
	}
	
	public void addRejectedRulesFromItems(Collection<GroundItem> items)
	{
		if(items == null || items.isEmpty())
			return;
		
		double radius = rejectRadius.getValueF();
		long expiryMillis = (long)(rejectExpiry.getValueI() * 1000L);
		
		for(GroundItem gi : items)
		{
			String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getKey(gi.stack().getItem()).toString();
			int amt = gi.stack().getCount();
			rejectedRules.add(new RejectedRule(itemId, gi.position(), radius,
				expiryMillis, amt));
		}
	}
	
	public void toggleTracedItem(String itemId)
	{
		if(itemId == null)
			return;
		if(tracedItems.contains(itemId))
			tracedItems.remove(itemId);
		else
			tracedItems.add(itemId);
	}
	
	public void setTraced(String itemId, boolean traced)
	{
		if(itemId == null)
			return;
		if(traced)
			tracedItems.add(itemId);
		else
			tracedItems.remove(itemId);
	}
	
	public boolean isTraced(String itemId)
	{
		return itemId != null && tracedItems.contains(itemId);
	}
	
	public java.util.Set<String> getTracedItems()
	{
		return java.util.Set.copyOf(tracedItems);
	}
	
	public boolean isHudEnabled()
	{
		return hudEnabled.isChecked();
	}
	
	public double getPopupRange()
	{
		// Always return the numeric slider value here. The special "infinite"
		// behavior is handled in scanning logic to avoid passing Infinity into
		// math that doesn't support it.
		return popupRange.getValueF();
	}
	
	public double getPopupScale()
	{
		return popupScale.getValueF();
	}
	
	public int getPopupMaxItems()
	{
		return popupMaxItems.getValueI();
	}
	
	public int getHudOffsetX()
	{
		return hudOffsetX.getValueI();
	}
	
	public int getHudOffsetY()
	{
		return hudOffsetY.getValueI();
	}
	
	public boolean isShowRegistryName()
	{
		return showRegistryName.isChecked();
	}
	
	public record GroundItem(int entityId, UUID uuid, ItemStack stack,
		double distance, Vec3 position)
	{
		public String key()
		{
			return uuid.toString();
		}
		
		public ComponentSummary summary()
		{
			return new ComponentSummary(stack.getHoverName().getString(),
				stack.getCount(), distance);
		}
	}
	
	public record ComponentSummary(String displayName, int count,
		double distance)
	{
		public String distanceText()
		{
			return String.format(Locale.ROOT, "%.2f m", distance);
		}
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(tracedItems.isEmpty())
			return;
		
		net.wurstclient.hacks.ItemEspHack esp =
			net.wurstclient.WurstClient.INSTANCE.getHax().itemEspHack;
		boolean espEnabled = esp != null && esp.isEnabled();
		boolean espHasTracerLines = espEnabled && esp.rendersTracerLines();
		boolean shouldDrawBoxes = !espEnabled;
		boolean shouldDrawTracers = !espHasTracerLines;
		if(!shouldDrawBoxes && !shouldDrawTracers)
			return;
		
		java.util.ArrayList<AABB> boxes =
			shouldDrawBoxes ? new java.util.ArrayList<>() : null;
		java.util.ArrayList<Vec3> ends =
			shouldDrawTracers ? new java.util.ArrayList<>() : null;
		for(GroundItem gi : trackedItems)
		{
			String baseId =
				net.wurstclient.util.ItemUtils.getStackId(gi.stack());
			if(baseId == null)
				baseId = net.minecraft.core.registries.BuiltInRegistries.ITEM
					.getKey(gi.stack().getItem()).toString();
			boolean traced = isTraced(baseId);
			if(!traced
				&& net.wurstclient.util.ItemUtils.isSyntheticXp(gi.stack()))
			{
				int xp = net.wurstclient.util.ItemUtils.getXpAmount(gi.stack());
				traced = isTraced(baseId + ":xp:" + xp);
			}
			if(!traced)
				continue;
			Vec3 p = gi.position();
			if(shouldDrawBoxes)
				boxes.add(new AABB(p.x - 0.18, p.y - 0.18, p.z - 0.18,
					p.x + 0.18, p.y + 0.18, p.z + 0.18));
			if(shouldDrawTracers)
				ends.add(p);
		}
		boolean hasBoxes = shouldDrawBoxes && boxes != null && !boxes.isEmpty();
		boolean hasTracers =
			shouldDrawTracers && ends != null && !ends.isEmpty();
		if(!hasBoxes && !hasTracers)
			return;
		float[] rf = RenderUtils.getRainbowColor();
		int traceLines = RenderUtils.toIntColor(rf, 0.5f);
		int traceQuads = RenderUtils.toIntColor(rf, 0.35f);
		if(hasBoxes)
		{
			RenderUtils.drawSolidBoxes(matrixStack, boxes, traceQuads, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, traceLines,
				false);
		}
		if(hasTracers)
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, traceLines,
				false);
	}
	
	// Inline ItemESP ignored-items editor in ItemHandler settings
	private final Setting itemEspIgnoredListSetting =
		new Setting("Ignored items", WText.empty())
		{
			@Override
			public Component getComponent()
			{
				net.wurstclient.hacks.ItemEspHack esp =
					net.wurstclient.WurstClient.INSTANCE.getHax().itemEspHack;
				if(esp == null)
					return new ItemListEditButton(
						new net.wurstclient.settings.ItemListSetting(
							"Ignored items", WText.empty()));
				return new ItemListEditButton(esp.getIgnoredListSetting());
			}
			
			@Override
			public void fromJson(JsonElement json)
			{
				// no-op
			}
			
			@Override
			public JsonElement toJson()
			{
				return JsonNull.INSTANCE;
			}
			
			@Override
			public JsonObject exportWikiData()
			{
				JsonObject json = new JsonObject();
				json.addProperty("name", getName());
				json.addProperty("description", getDescription());
				json.addProperty("type", "ItemList");
				return json;
			}
			
			@Override
			public java.util.Set<net.wurstclient.keybinds.PossibleKeybind> getPossibleKeybinds(
				String featureName)
			{
				return java.util.Collections.emptySet();
			}
		};
}
