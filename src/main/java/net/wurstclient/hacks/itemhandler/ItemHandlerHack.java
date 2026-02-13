/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
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
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
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
import it.unimi.dsi.fastutil.objects.Object2IntMap;
// no screen import needed; we embed ItemESP's editor component directly
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.chunk.ChunkUtils;

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
	private static final Set<String> DEFAULT_MOB_EQUIPMENT_IDS =
		Set.of("minecraft:bow", "minecraft:crossbow", "minecraft:trident",
			"minecraft:wooden_sword", "minecraft:stone_sword",
			"minecraft:iron_sword", "minecraft:golden_sword",
			"minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:iron_axe",
			"minecraft:golden_axe", "minecraft:wooden_pickaxe",
			"minecraft:stone_pickaxe", "minecraft:iron_pickaxe",
			"minecraft:golden_pickaxe", "minecraft:wooden_shovel",
			"minecraft:stone_shovel", "minecraft:iron_shovel",
			"minecraft:golden_shovel", "minecraft:wooden_hoe",
			"minecraft:stone_hoe", "minecraft:iron_hoe", "minecraft:golden_hoe",
			"minecraft:leather_helmet", "minecraft:leather_chestplate",
			"minecraft:leather_leggings", "minecraft:leather_boots",
			"minecraft:iron_helmet", "minecraft:iron_chestplate",
			"minecraft:iron_leggings", "minecraft:iron_boots",
			"minecraft:golden_helmet", "minecraft:golden_chestplate",
			"minecraft:golden_leggings", "minecraft:golden_boots");
	private static final String[] DEFAULT_MOB_EQUIPMENT_MATERIALS =
		{"copper", "steel", "gold", "golden"};
	private static final String[] DEFAULT_MOB_EQUIPMENT_SUFFIXES =
		{"_helmet", "_chestplate", "_leggings", "_boots", "_sword", "_axe",
			"_pickaxe", "_shovel", "_hoe", "_spear"};
	
	private final List<GroundItem> trackedItems = new ArrayList<>();
	private final List<NearbySign> trackedSigns = new ArrayList<>();
	private int signScanCooldown;
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
	
	private final CheckboxSetting showEnchantmentsInNames =
		new CheckboxSetting("Show enchantments in names",
			"Shows enchantments on the second line in ItemHandler HUD and GUI.",
			false);
	
	// How many items to show in the popup HUD
	private final SliderSetting popupMaxItems = new SliderSetting(
		"Popup HUD max items", 8, 1, 10, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting pinSpecialItemsTop = new CheckboxSetting(
		"Pin ItemESP special to top",
		"In popup HUD sorting, show items marked as special by ItemESP first.",
		false);
	
	private final CheckboxSetting showSignsInHud =
		new CheckboxSetting("Show nearby signs",
			"Adds nearby sign text to the ItemHandler popup HUD.", false);
	
	private final SliderSetting signRange = new SliderSetting("Sign range",
		"How far to scan for signs when 'Show nearby signs' is enabled.\n"
			+ "∞ = all loaded chunks around you (limited by render distance).",
		INFINITE_SCAN_RADIUS, 2.0, INFINITE_SCAN_RADIUS, 1.0,
		ValueDisplay.DECIMAL.withLabel(INFINITE_SCAN_RADIUS, "∞")
			.withSuffix(" blocks"));
	
	private final SliderSetting signMax = new SliderSetting("Max signs",
		"Maximum number of signs to show in the popup HUD.", 4, 1, 10, 1,
		ValueDisplay.INTEGER);
	
	private final SliderSetting popupRange = new SliderSetting(
		"Item detection range", 6.0, 1.0, INFINITE_SCAN_RADIUS, 0.5,
		ValueDisplay.DECIMAL.withLabel(INFINITE_SCAN_RADIUS, "∞"));
	
	// Adjust popup/UI scale (affects text size and icon size heuristically)
	private final SliderSetting popupScale = new SliderSetting(
		"Popup HUD font scale", 0.75, 0.5, 1.5, 0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting tracerThickness = new SliderSetting(
		"Tracer thickness", 2, 0.5, 8, 0.1, ValueDisplay.DECIMAL);
	
	// Respect ItemESP's ignored items list
	private final CheckboxSetting respectItemEspIgnores =
		new CheckboxSetting("Respect ItemESP ignored items", true);
	
	// Include items held or worn by mobs
	private final CheckboxSetting includeMobEquipment =
		new CheckboxSetting("Detect held/worn mob items",
			"Also detect items held or worn by mobs.", false);
	
	private final CheckboxSetting filterDefaultMobEquipment =
		new CheckboxSetting("Filter default mob items",
			"Hide common mob gear (gold/iron/stone/chain/etc).", false);
	
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
		addSetting(hudEnabled);
		addSetting(showRegistryName);
		addSetting(showEnchantmentsInNames);
		addSetting(includeMobEquipment);
		addSetting(filterDefaultMobEquipment);
		addSetting(respectItemEspIgnores);
		addSetting(itemEspIgnoredListSetting);
		addSetting(rejectRadius);
		addSetting(rejectExpiry);
		addSetting(popupRange);
		addSetting(popupScale);
		addSetting(tracerThickness);
		addSetting(popupMaxItems);
		addSetting(pinSpecialItemsTop);
		addSetting(showSignsInHud);
		addSetting(signRange);
		addSetting(signMax);
		addSetting(hudOffsetX);
		addSetting(hudOffsetY);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		trackedItems.clear();
		trackedSigns.clear();
		pickupWhitelist.clear();
		pickupQueue.clear();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		trackedItems.clear();
		trackedSigns.clear();
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
			trackedSigns.clear();
			pickupWhitelist.clear();
			pickupQueue.clear();
			stopAutoWalk();
			return;
		}
		
		updateWhitelist();
		scanNearbyItems();
		scanNearbySigns();
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
		
		List<ItemFrame> foundFrames = MC.level.getEntitiesOfClass(
			ItemFrame.class, player.getBoundingBox().inflate((float)scanRadius),
			this::shouldTrackItemFrame);
		
		trackedItems.clear();
		for(ItemEntity entity : found)
		{
			ItemStack stack = entity.getItem().copy();
			double distance = entity.distanceTo(player);
			// optional filter: respect ItemESP ignored items
			if(isIgnoredByItemEsp(stack))
				continue;
			trackedItems.add(new GroundItem(entity.getId(), entity.getUUID(),
				stack, distance, entity.position(), SourceType.GROUND, null));
		}
		
		for(ExperienceOrb orb : foundOrbs)
		{
			double distance = orb.distanceTo(player);
			// proxy stack for UI only (contains synthetic id + xp metadata)
			ItemStack stack =
				net.wurstclient.util.ItemUtils.createSyntheticXpStack(orb);
			trackedItems.add(new GroundItem(orb.getId(), orb.getUUID(), stack,
				distance, orb.position(), SourceType.XP_ORB, null));
		}
		
		for(ItemFrame frame : foundFrames)
		{
			ItemStack stack = frame.getItem().copy();
			double distance = frame.distanceTo(player);
			trackedItems
				.add(new GroundItem(frame.getId(), frame.getUUID(), stack,
					distance, frame.position(), SourceType.ITEM_FRAME, null));
		}
		
		if(includeMobEquipment.isChecked())
			addMobEquipmentItems(player, scanRadius);
			
		// Traced items intentionally persist even if they are temporarily out
		// of
		// range or not currently tracked. (Only explicit pickup detection or
		// manual toggling should untrace.)
	}
	
	private void scanNearbySigns()
	{
		boolean guiOpen = MC.screen instanceof ItemHandlerScreen;
		if(!showSignsInHud.isChecked() && !guiOpen)
		{
			trackedSigns.clear();
			return;
		}
		
		if(MC.level == null || MC.player == null)
		{
			trackedSigns.clear();
			return;
		}
		
		// Don't scan every tick; this can get expensive in sign-heavy areas.
		if(signScanCooldown-- > 0)
			return;
		signScanCooldown = 10;
		
		trackedSigns.clear();
		
		double range = signRange.getValue();
		boolean infinite = range >= INFINITE_SCAN_RADIUS;
		double rangeSq = range * range;
		Vec3 centerVec = MC.player.position();
		
		ChunkUtils.getLoadedBlockEntities().forEach(be -> {
			if(!(be instanceof SignBlockEntity sign))
				return;
			
			BlockPos pos = sign.getBlockPos();
			if(pos == null)
				return;
			
			Vec3 p = Vec3.atCenterOf(pos);
			double distSq = p.distanceToSqr(centerVec);
			if(!infinite && distSq > rangeSq)
				return;
			
			String text = readSignText(sign);
			if(text.isEmpty())
				return;
			
			ItemStack icon =
				new ItemStack(MC.level.getBlockState(pos).getBlock().asItem());
			if(icon.isEmpty())
				icon = new ItemStack(Items.OAK_SIGN);
			
			trackedSigns
				.add(new NearbySign(pos, icon, text, Math.sqrt(distSq)));
		});
		
		trackedSigns
			.sort(java.util.Comparator.comparingDouble(NearbySign::distance));
		
		int max = signMax.getValueI();
		if(trackedSigns.size() > max)
			trackedSigns.subList(max, trackedSigns.size()).clear();
	}
	
	private static String readSignText(SignBlockEntity sign)
	{
		if(sign == null)
			return "";
		
		try
		{
			SignText signText = sign.getFrontText();
			if(signText == null)
				return "";
			
			java.util.StringJoiner joiner = new java.util.StringJoiner(" | ");
			for(int i = 0; i < 4; i++)
			{
				net.minecraft.network.chat.Component c =
					signText.getMessage(i, false);
				if(c == null)
					continue;
				String s = c.getString();
				if(s == null)
					continue;
				String trimmed = s.trim();
				if(trimmed.isEmpty())
					continue;
				joiner.add(trimmed);
			}
			
			String out = joiner.toString();
			if(out.length() > 80)
				out = out.substring(0, 77) + "...";
			return out;
			
		}catch(Throwable t)
		{
			return "";
		}
	}
	
	private boolean shouldTrack(ItemEntity entity)
	{
		return entity != null && entity.isAlive() && !entity.isRemoved()
			&& !entity.getItem().isEmpty();
	}
	
	private boolean shouldTrackItemFrame(ItemFrame frame)
	{
		if(frame == null || !frame.isAlive() || frame.isRemoved())
			return false;
		ItemStack stack = frame.getItem();
		if(stack == null || stack.isEmpty())
			return false;
		return !isIgnoredByItemEsp(stack);
	}
	
	private boolean shouldTrackMobEquipment(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		if(isIgnoredByItemEsp(stack))
			return false;
		return !filterDefaultMobEquipment.isChecked()
			|| !isDefaultMobEquipment(stack);
	}
	
	private boolean isDefaultMobEquipment(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
			.getKey(stack.getItem()).toString();
		if(DEFAULT_MOB_EQUIPMENT_IDS.contains(id))
			return true;
		String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
			.getKey(stack.getItem()).getPath();
		for(String material : DEFAULT_MOB_EQUIPMENT_MATERIALS)
		{
			if(!path.contains(material))
				continue;
			for(String suffix : DEFAULT_MOB_EQUIPMENT_SUFFIXES)
			{
				if(path.endsWith(suffix))
					return true;
			}
		}
		return false;
	}
	
	private void addMobEquipmentItems(LocalPlayer player, double scanRadius)
	{
		if(player == null || MC.level == null)
			return;
		
		List<LivingEntity> living =
			MC.level.getEntitiesOfClass(LivingEntity.class,
				player.getBoundingBox().inflate((float)scanRadius),
				e -> e != null && e.isAlive() && !e.isRemoved() && e != player);
		
		for(LivingEntity le : living)
		{
			if(le instanceof Player)
				continue;
			if(le instanceof ArmorStand)
				continue;
			
			String mobName = le.getName().getString();
			
			ItemStack main = le.getMainHandItem();
			if(shouldTrackMobEquipment(main))
			{
				Vec3 pos = getHeldItemPos(le, InteractionHand.MAIN_HAND);
				addMobTrackedItem(le, main, pos, SourceType.MOB_HELD, mobName);
			}
			
			ItemStack off = le.getOffhandItem();
			if(shouldTrackMobEquipment(off))
			{
				Vec3 pos = getHeldItemPos(le, InteractionHand.OFF_HAND);
				addMobTrackedItem(le, off, pos, SourceType.MOB_HELD, mobName);
			}
			
			for(EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD,
				EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
			{
				ItemStack armor = le.getItemBySlot(slot);
				if(!shouldTrackMobEquipment(armor))
					continue;
				Vec3 pos = getArmorPos(le, slot);
				addMobTrackedItem(le, armor, pos, SourceType.MOB_WORN, mobName);
			}
		}
	}
	
	private void addMobTrackedItem(LivingEntity le, ItemStack stack, Vec3 pos,
		SourceType sourceType, String mobName)
	{
		if(le == null || stack == null || stack.isEmpty())
			return;
		Vec3 position = pos != null ? pos : le.position();
		double distance =
			MC.player != null ? MC.player.position().distanceTo(position) : 0.0;
		trackedItems.add(new GroundItem(le.getId(), le.getUUID(), stack.copy(),
			distance, position, sourceType, mobName));
	}
	
	private Vec3 getHeldItemPos(LivingEntity e, InteractionHand hand)
	{
		if(e == null || hand == null)
			return null;
		Vec3 base = e.position();
		double yawRad = Math.toRadians(e.getYRot());
		HumanoidArm mainArm = HumanoidArm.RIGHT;
		if(e instanceof Player pe)
			mainArm = pe.getMainArm();
		boolean rightSide =
			(mainArm == HumanoidArm.RIGHT && hand == InteractionHand.MAIN_HAND)
				|| (mainArm == HumanoidArm.LEFT
					&& hand == InteractionHand.OFF_HAND);
		double side = rightSide ? -1 : 1;
		double eyeH = e.getEyeHeight(e.getPose());
		double offX = Math.cos(yawRad) * 0.16 * side;
		double offY = eyeH - 0.1;
		double offZ = Math.sin(yawRad) * 0.16 * side;
		return base.add(offX, offY, offZ);
	}
	
	private Vec3 getArmorPos(LivingEntity e, EquipmentSlot slot)
	{
		if(e == null)
			return null;
		Vec3 base = e.position();
		double height = e.getBbHeight();
		double y;
		switch(slot)
		{
			case HEAD -> y = e.getEyeHeight(e.getPose()) + 0.05;
			case CHEST -> y = height * 0.75;
			case LEGS -> y = height * 0.5;
			case FEET -> y = height * 0.25;
			default -> y = height * 0.5;
		}
		return base.add(0, y, 0);
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
			if(gi.sourceType() != SourceType.GROUND
				&& gi.sourceType() != SourceType.XP_ORB)
				continue;
			String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getKey(gi.stack().getItem()).toString();
			int amt = gi.stack().getCount();
			rejectedRules.add(new RejectedRule(itemId, gi.position(), radius,
				expiryMillis, amt));
		}
	}
	
	public void addIgnoredItemsFromItems(Collection<GroundItem> items)
	{
		if(items == null || items.isEmpty())
			return;
		
		net.wurstclient.hacks.ItemEspHack esp =
			net.wurstclient.WurstClient.INSTANCE.getHax().itemEspHack;
		if(esp == null)
			return;
		
		net.wurstclient.settings.ItemListSetting list =
			esp.getIgnoredListSetting();
		java.util.Set<String> added = new java.util.HashSet<>();
		for(GroundItem gi : items)
		{
			if(gi.sourceType() != SourceType.GROUND
				&& gi.sourceType() != SourceType.XP_ORB)
				continue;
			String id = gi.traceId();
			if(id == null)
				continue;
			if(added.contains(id))
				continue;
			added.add(id);
			list.addRawName(id);
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
	
	public List<NearbySign> getTrackedSigns()
	{
		return List.copyOf(trackedSigns);
	}
	
	public static String getSignTraceId(BlockPos pos)
	{
		if(pos == null)
			return null;
		return "sign:" + pos.asLong();
	}
	
	public boolean isShowSignsInHud()
	{
		return showSignsInHud.isChecked();
	}
	
	public record NearbySign(BlockPos pos, ItemStack icon, String text,
		double distance)
	{
		public String traceId()
		{
			return getSignTraceId(pos);
		}
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
	
	public boolean isPinSpecialItemsTop()
	{
		return pinSpecialItemsTop.isChecked();
	}
	
	public int getHudOffsetX()
	{
		return hudOffsetX.getValueI();
	}
	
	public int getHudOffsetY()
	{
		return hudOffsetY.getValueI();
	}
	
	public int getHudOffsetMinX()
	{
		return (int)hudOffsetX.getMinimum();
	}
	
	public int getHudOffsetMaxX()
	{
		return (int)hudOffsetX.getMaximum();
	}
	
	public int getHudOffsetMinY()
	{
		return (int)hudOffsetY.getMinimum();
	}
	
	public int getHudOffsetMaxY()
	{
		return (int)hudOffsetY.getMaximum();
	}
	
	public void setHudOffsets(int x, int y)
	{
		hudOffsetX.setValue(x);
		hudOffsetY.setValue(y);
	}
	
	public boolean isShowRegistryName()
	{
		return showRegistryName.isChecked();
	}
	
	public boolean isShowEnchantmentsInNames()
	{
		return showEnchantmentsInNames.isChecked();
	}
	
	public record GroundItem(int entityId, UUID uuid, ItemStack stack,
		double distance, Vec3 position, SourceType sourceType,
		String sourceName)
	{
		public String baseId()
		{
			String id = net.wurstclient.util.ItemUtils.getStackId(stack);
			if(id != null)
				return id;
			return net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getKey(stack.getItem()).toString();
		}
		
		public String traceId()
		{
			String baseId = baseId();
			if(net.wurstclient.util.ItemUtils.isSyntheticXp(stack))
			{
				int xp = net.wurstclient.util.ItemUtils.getXpAmount(stack);
				return baseId + ":xp:" + xp;
			}
			if(sourceType == SourceType.ITEM_FRAME)
				return baseId + ":item_frame";
			return baseId;
		}
		
		public String sourceLabel()
		{
			if(sourceType == SourceType.MOB_HELD)
				return "held by " + (sourceName != null ? sourceName : "mob");
			if(sourceType == SourceType.MOB_WORN)
				return "worn by " + (sourceName != null ? sourceName : "mob");
			if(sourceType == SourceType.ITEM_FRAME)
				return "in item frame";
			return "";
		}
		
		public String displayName()
		{
			String name = stack.getHoverName().getString();
			if(name == null || name.isBlank())
				name = net.minecraft.core.registries.BuiltInRegistries.ITEM
					.getKey(stack.getItem()).getPath();
			
			String label = sourceLabel();
			if(label == null || label.isBlank())
				return name;
			return name + " (" + label + ")";
		}
		
		public String key()
		{
			return uuid.toString();
		}
		
		public ComponentSummary summary()
		{
			return new ComponentSummary(displayName(), stack.getCount(),
				distance);
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
		java.util.ArrayList<RenderUtils.ColoredPoint> ends =
			shouldDrawTracers ? new java.util.ArrayList<>() : null;
		for(GroundItem gi : trackedItems)
		{
			String traceId = gi.traceId();
			boolean traced = traceId != null && isTraced(traceId);
			if(!traced)
				continue;
			Vec3 p = gi.position();
			if(shouldDrawBoxes)
				boxes.add(new AABB(p.x - 0.18, p.y - 0.18, p.z - 0.18,
					p.x + 0.18, p.y + 0.18, p.z + 0.18));
			if(shouldDrawTracers)
				ends.add(new RenderUtils.ColoredPoint(p, 0));
		}
		
		for(NearbySign sign : trackedSigns)
		{
			if(sign == null || sign.pos() == null)
				continue;
			String traceId = sign.traceId();
			boolean traced = traceId != null && isTraced(traceId);
			if(!traced)
				continue;
			BlockPos pos = sign.pos();
			Vec3 p = Vec3.atCenterOf(pos);
			if(shouldDrawBoxes)
				boxes.add(new AABB(pos));
			if(shouldDrawTracers)
				ends.add(new RenderUtils.ColoredPoint(p, 0));
		}
		boolean hasBoxes = shouldDrawBoxes && boxes != null && !boxes.isEmpty();
		boolean hasTracers =
			shouldDrawTracers && ends != null && !ends.isEmpty();
		if(!hasBoxes && !hasTracers)
			return;
		float[] rf = RenderUtils.getRainbowColor();
		int traceLines = RenderUtils.toIntColor(rf, 0.5f);
		int traceQuads = RenderUtils.toIntColor(rf, 0.35f);
		if(hasTracers)
			for(int i = 0; i < ends.size(); i++)
			{
				Vec3 p = ends.get(i).point();
				ends.set(i, new RenderUtils.ColoredPoint(p, traceLines));
			}
		if(hasBoxes)
		{
			RenderUtils.drawSolidBoxes(matrixStack, boxes, traceQuads, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, traceLines,
				false);
		}
		if(hasTracers)
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, false,
				tracerThickness.getValue());
	}
	
	private boolean isIgnoredByItemEsp(ItemStack stack)
	{
		if(!respectItemEspIgnores.isChecked())
			return false;
		net.wurstclient.hacks.ItemEspHack esp =
			net.wurstclient.WurstClient.INSTANCE.getHax().itemEspHack;
		if(esp == null)
			return false;
		String id = net.wurstclient.util.ItemUtils.getStackId(stack);
		return esp.isIgnoredId(id);
	}
	
	public boolean isSpecialByItemEsp(ItemStack stack)
	{
		net.wurstclient.hacks.ItemEspHack esp =
			net.wurstclient.WurstClient.INSTANCE.getHax().itemEspHack;
		if(esp == null)
			return false;
		return esp.isSpecialStack(stack);
	}
	
	public enum SourceType
	{
		GROUND,
		XP_ORB,
		ITEM_FRAME,
		MOB_HELD,
		MOB_WORN
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
	
	public String getEnchantmentSummary(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return "";
		
		ArrayList<String> parts = new ArrayList<>();
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		appendEnchantmentParts(parts, seen, stack
			.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
		appendEnchantmentParts(parts, seen, stack.getOrDefault(
			DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
		if(parts.isEmpty())
			return "";
		return String.join(", ", parts);
	}
	
	private static void appendEnchantmentParts(List<String> out,
		Set<String> seen, ItemEnchantments enchantments)
	{
		for(Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments
			.entrySet())
		{
			Holder<Enchantment> holder = entry.getKey();
			int level = entry.getIntValue();
			String key = holder.getRegisteredName() + "#" + level;
			if(!seen.add(key))
				continue;
			out.add(Enchantment.getFullname(holder, level).getString());
		}
	}
}
