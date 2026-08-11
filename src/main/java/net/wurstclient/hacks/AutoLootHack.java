/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.ItemUtils;
import net.wurstclient.mixinterface.IKeyBinding;

@SearchTags({"auto loot", "loot upgrade", "lootupgrade", "loot upgrader"})
public final class AutoLootHack extends Hack
	implements UpdateListener, PlayerAttacksEntityListener
{
	private static final long KILL_WINDOW_MS = 2 * 60 * 1000L;
	private static final long MIN_ACTION_DELAY_MS = 150L;
	
	private enum Mode
	{
		ALWAYS("Always"),
		AFTER_PLAYER_KILL("After player kill");
		
		private final String label;
		
		Mode(String label)
		{
			this.label = label;
		}
		
		@Override
		public String toString()
		{
			return label;
		}
	}
	
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"Always scan for loot, or scan for two minutes after a player you attacked dies.",
		Mode.values(), Mode.ALWAYS);
	private final CheckboxSetting containers = new CheckboxSetting(
		"Loot containers",
		"Also upgrade from opened chests and shulker boxes, returning the replaced item to the container.",
		false);
	private final CheckboxSetting protectNamed = new CheckboxSetting(
		"Protect named items",
		"Never swap or drop items in your inventory that have a custom name.",
		true);
	private final CheckboxSetting moveToLoot = new CheckboxSetting(
		"Move to loot",
		"Automatically walk toward valuable dropped items that are within range.",
		true);
	private final SliderSetting range = new SliderSetting("Range",
		"Maximum distance to collect valuable dropped items.", 6, 1, 32, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting delay = new SliderSetting("Action delay",
		"Delay between loot actions to avoid sending a burst of clicks.", 250,
		50, 1000, 50, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private Entity pendingKill;
	private ItemEntity movingTo;
	private Mode lastMode;
	private long activeUntil;
	private long nextActionAt;
	
	public AutoLootHack()
	{
		super("AutoLoot");
		setCategory(Category.ITEMS);
		addSetting(mode);
		addSetting(containers);
		addSetting(protectNamed);
		addSetting(moveToLoot);
		addSetting(range);
		addSetting(delay);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PlayerAttacksEntityListener.class, this);
		pendingKill = null;
		movingTo = null;
		activeUntil = mode.getSelected() == Mode.ALWAYS ? Long.MAX_VALUE : 0;
		lastMode = mode.getSelected();
		nextActionAt = 0;
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		pendingKill = null;
		stopMoving();
		activeUntil = 0;
	}
	
	@Override
	public void onPlayerAttacksEntity(Entity target)
	{
		if(target instanceof Player && target != MC.player
			&& mode.getSelected() == Mode.AFTER_PLAYER_KILL)
			pendingKill = target;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
			return;
		if(mode.getSelected() != lastMode)
		{
			lastMode = mode.getSelected();
			pendingKill = null;
			activeUntil = lastMode == Mode.ALWAYS ? Long.MAX_VALUE : 0;
		}
		if(movingTo != null && moveToLoot.isChecked())
		{
			if(!movingTo.isAlive() || movingTo.distanceTo(MC.player) <= 1.5)
				stopMoving();
			else
				driveToward(movingTo);
		}else if(movingTo != null)
			stopMoving();
		
		if(mode.getSelected() == Mode.ALWAYS)
			activeUntil = Long.MAX_VALUE;
		else
		{
			if(pendingKill != null && !pendingKill.isAlive())
			{
				activeUntil = System.currentTimeMillis() + KILL_WINDOW_MS;
				pendingKill = null;
			}
			if(System.currentTimeMillis() >= activeUntil)
				return;
		}
		
		long now = System.currentTimeMillis();
		if(now < nextActionAt)
			return;
		if(equipBetterArmor())
		{
			nextActionAt =
				now + Math.max(MIN_ACTION_DELAY_MS, delay.getValueI());
			return;
		}
		
		if(containers.isChecked()
			&& MC.screen instanceof AbstractContainerScreen<?> screen
			&& !(screen instanceof InventoryScreen)
			&& containerSlotCount(screen) > 0)
		{
			if(processContainer(screen))
				nextActionAt =
					now + Math.max(MIN_ACTION_DELAY_MS, delay.getValueI());
			return;
		}
		
		if(MC.screen instanceof AbstractContainerScreen<?>
			|| !processFloorLoot())
			return;
		nextActionAt = now + Math.max(MIN_ACTION_DELAY_MS, delay.getValueI());
	}
	
	private boolean processFloorLoot()
	{
		double r = range.getValue();
		List<ItemEntity> items = MC.level.getEntitiesOfClass(ItemEntity.class,
			MC.player.getBoundingBox().inflate(r),
			e -> e.isAlive() && isSpecial(e.getItem()));
		return items.stream()
			.sorted(Comparator.comparingDouble(e -> e.distanceToSqr(MC.player)))
			.map(this::tryFloorItem).filter(Boolean::booleanValue).findFirst()
			.orElse(false);
	}
	
	private boolean tryFloorItem(ItemEntity entity)
	{
		ItemStack candidate = entity.getItem();
		int comparable = findBestComparableSlot(candidate);
		if(comparable >= 0)
		{
			ItemStack current = MC.player.getInventory().getItem(comparable);
			if(!isBetter(candidate, current))
				return false;
			if(isProtected(current))
				return false;
			throwInventorySlot(comparable);
		}else if(MC.player.getInventory().getFreeSlot() < 0)
		{
			int junk = findJunkSlot();
			if(junk < 0)
				return false;
			throwInventorySlot(junk);
		}
		
		if(entity.distanceTo(MC.player) > 1.5)
		{
			if(!moveToLoot.isChecked())
				return false;
			movingTo = entity;
			driveToward(entity);
			return true;
		}
		return true;
	}
	
	private void driveToward(ItemEntity entity)
	{
		WURST.getRotationFaker().faceVectorClientIgnorePitch(entity.position());
		IKeyBinding.get(MC.options.keyUp).simulatePress(true);
	}
	
	private void stopMoving()
	{
		IKeyBinding.get(MC.options.keyUp).resetPressedState();
		movingTo = null;
	}
	
	private boolean processContainer(AbstractContainerScreen<?> screen)
	{
		int containerSlots = containerSlotCount(screen);
		for(int i = 0; i < containerSlots; i++)
		{
			Slot slot = screen.getMenu().slots.get(i);
			ItemStack candidate = slot.getItem();
			if(candidate.isEmpty() || !isSpecial(candidate))
				continue;
			
			int inventorySlot = findBestComparableInventorySlot(candidate);
			if(inventorySlot >= 0)
			{
				ItemStack current =
					MC.player.getInventory().getItem(inventorySlot);
				if(!isBetter(candidate, current) || isProtected(current))
					continue;
				return swapWithContainer(screen, slot, inventorySlot,
					containerSlots);
			}
			
			if(MC.player.getInventory().getFreeSlot() >= 0)
			{
				screen.slotClicked(slot, slot.index, 0, ClickType.QUICK_MOVE);
				return true;
			}
			
			int junk = findJunkSlot();
			if(junk < 0)
				continue;
			int junkMenuSlot = inventoryMenuSlot(junk, containerSlots);
			Slot junkSlot = screen.getMenu().slots.get(junkMenuSlot);
			if(findEmptyContainerSlot(screen, containerSlots) >= 0)
				screen.slotClicked(junkSlot, junkSlot.index, 0,
					ClickType.QUICK_MOVE);
			else
				screen.slotClicked(junkSlot, junkSlot.index, 1,
					ClickType.THROW);
			screen.slotClicked(slot, slot.index, 0, ClickType.QUICK_MOVE);
			return true;
		}
		return false;
	}
	
	private boolean swapWithContainer(AbstractContainerScreen<?> screen,
		Slot containerSlot, int inventorySlot, int containerSlots)
	{
		Slot playerSlot = screen.getMenu().slots
			.get(inventoryMenuSlot(inventorySlot, containerSlots));
		screen.slotClicked(containerSlot, containerSlot.index, 0,
			ClickType.PICKUP);
		screen.slotClicked(playerSlot, playerSlot.index, 0, ClickType.PICKUP);
		screen.slotClicked(containerSlot, containerSlot.index, 0,
			ClickType.PICKUP);
		return true;
	}
	
	private int findBestComparableSlot(ItemStack candidate)
	{
		int best = -1;
		for(int i = 0; i < 41; i++)
			if(isComparable(candidate, MC.player.getInventory().getItem(i))
				&& (best < 0 || isBetter(MC.player.getInventory().getItem(i),
					MC.player.getInventory().getItem(best))))
				best = i;
		return best;
	}
	
	private boolean equipBetterArmor()
	{
		for(int i = 0; i < 36; i++)
		{
			ItemStack candidate = MC.player.getInventory().getItem(i);
			if(candidate.isEmpty() || !isSpecial(candidate))
				continue;
			EquipmentSlot armorSlot =
				ItemUtils.getArmorSlot(candidate.getItem());
			if(armorSlot == null)
				continue;
			ItemStack current = MC.player.getItemBySlot(armorSlot);
			if(!isBetter(candidate, current) || isProtected(current))
				continue;
			
			if(!current.isEmpty())
			{
				if(MC.player.getInventory().getFreeSlot() < 0)
					return false;
				IMC.getInteractionManager()
					.windowClick_QUICK_MOVE(8 - armorSlot.getIndex());
			}
			IMC.getInteractionManager()
				.windowClick_QUICK_MOVE(InventoryUtils.toNetworkSlot(i));
			return true;
		}
		return false;
	}
	
	private int findBestComparableInventorySlot(ItemStack candidate)
	{
		int best = -1;
		for(int i = 0; i < 36; i++)
			if(isComparable(candidate, MC.player.getInventory().getItem(i))
				&& (best < 0 || isBetter(MC.player.getInventory().getItem(i),
					MC.player.getInventory().getItem(best))))
				best = i;
		return best;
	}
	
	private boolean isComparable(ItemStack a, ItemStack b)
	{
		if(a == null || a.isEmpty() || b == null || b.isEmpty())
			return false;
		EquipmentSlot aArmor = ItemUtils.getArmorSlot(a.getItem());
		EquipmentSlot bArmor = ItemUtils.getArmorSlot(b.getItem());
		if(aArmor != null || bArmor != null)
			return aArmor != null && aArmor == bArmor;
		return family(a.getItem()).equals(family(b.getItem()));
	}
	
	private String family(Item item)
	{
		String name = item.getClass().getSimpleName().toLowerCase(Locale.ROOT);
		if(name.contains("pickaxe") || name.contains("axe")
			|| name.contains("shovel") || name.contains("hoe")
			|| name.contains("sword") || name.contains("mace")
			|| name.contains("bow") || name.contains("trident")
			|| name.contains("shield") || name.contains("fishingrod"))
			return name;
		return "item:" + BuiltInRegistries.ITEM.getKey(item);
	}
	
	private boolean isBetter(ItemStack candidate, ItemStack current)
	{
		return lootScore(candidate) > lootScore(current) + 1;
	}
	
	private int lootScore(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return Integer.MIN_VALUE;
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		int material =
			id.contains("netherite") ? 1000
				: id.contains("diamond") ? 800 : id.contains("gold") ? 600
					: id.contains("iron") ? 500 : id.contains("chainmail") ? 400
						: id.contains("stone") ? 300
							: id.contains("copper") ? 250
								: id.contains("wood") || id.contains("leather")
									? 100 : 0;
		int enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack)
			.entrySet().stream().mapToInt(e -> e.getIntValue()).sum();
		int durability = stack.isDamageableItem()
			? (int)(100.0 * (stack.getMaxDamage() - stack.getDamageValue())
				/ Math.max(1, stack.getMaxDamage()))
			: 0;
		return material + enchantments * 10 + durability;
	}
	
	private boolean isSpecial(ItemStack stack)
	{
		return WURST.getHax().itemEspHack.isSpecialStack(stack);
	}
	
	private boolean isProtected(ItemStack stack)
	{
		return protectNamed.isChecked() && stack != null
			&& stack.has(DataComponents.CUSTOM_NAME);
	}
	
	private int findJunkSlot()
	{
		int best = -1;
		int bestScore = Integer.MAX_VALUE;
		for(int i = 0; i < 36; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(stack.isEmpty() || isProtected(stack) || isSpecial(stack)
				|| !isJunk(stack))
				continue;
			int score = lootScore(stack);
			if(score < bestScore)
			{
				best = i;
				bestScore = score;
			}
		}
		return best;
	}
	
	private boolean isJunk(ItemStack stack)
	{
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		return id.contains("dirt") || id.contains("cobblestone")
			|| id.contains("netherrack") || id.contains("gravel")
			|| id.contains("sand") || id.contains("stone")
			|| id.contains("andesite") || id.contains("diorite")
			|| id.contains("granite") || id.contains("dirt")
			|| id.contains("rotten_flesh") || id.contains("seeds")
			|| id.contains("stick") || id.contains("flint");
	}
	
	private void throwInventorySlot(int inventorySlot)
	{
		IMC.getInteractionManager()
			.windowClick_THROW(InventoryUtils.toNetworkSlot(inventorySlot));
	}
	
	private int containerSlotCount(AbstractContainerScreen<?> screen)
	{
		if(screen.getMenu() instanceof ChestMenu chest)
			return chest.getRowCount() * 9;
		if(screen.getMenu() instanceof ShulkerBoxMenu)
			return 27;
		return 0;
	}
	
	private int inventoryMenuSlot(int inventorySlot, int containerSlots)
	{
		return containerSlots
			+ (inventorySlot < 9 ? 27 + inventorySlot : inventorySlot - 9);
	}
	
	private int findEmptyContainerSlot(AbstractContainerScreen<?> screen,
		int containerSlots)
	{
		for(int i = 0; i < containerSlots; i++)
			if(screen.getMenu().slots.get(i).getItem().isEmpty())
				return i;
		return -1;
	}
}
