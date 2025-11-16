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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.UpdateListener;
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

public class ItemHandlerHack extends Hack implements UpdateListener
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
		"Reject rule expiry (s)", 15, 5, 3600, 1, ValueDisplay.INTEGER);
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
		"Popup font scale", 0.75, 0.5, 1.5, 0.05, ValueDisplay.DECIMAL);
	
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
		trackedItems.clear();
		pickupWhitelist.clear();
		pickupQueue.clear();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		trackedItems.clear();
		pickupWhitelist.clear();
		pickupQueue.clear();
		stopAutoWalk();
		rejectedRules.clear();
		
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
		processRejectedPickup();
		processPickupQueue();
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
				if(esp != null && esp.shouldUseIgnoredItems())
				{
					String id =
						net.minecraft.core.registries.BuiltInRegistries.ITEM
							.getKey(stack.getItem()).toString();
					if(esp.isIgnoredId(id))
						continue;
				}
			}
			trackedItems.add(new GroundItem(entity.getId(), entity.getUUID(),
				stack, distance, entity.position()));
		}
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
