/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.util.LootItemPolicy;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.ItemUtils;
import net.wurstclient.util.ChatUtils;
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
		"Collect whitelisted valuables and upgrades from opened chests and shulker boxes.",
		false);
	private final CheckboxSetting protectNamed = new CheckboxSetting(
		"Protect named items",
		"Never swap or drop items in your inventory that have a custom name.",
		true);
	private final CheckboxSetting moveToLoot = new CheckboxSetting(
		"Move to loot",
		"Automatically walk toward valuable dropped items that are within range.",
		true);
	private final CheckboxSetting onlyAfterInventoryChange =
		new CheckboxSetting("Only after inventory change",
			"Only scan after detecting a pickup, drop, or other inventory change.",
			false);
	private final CheckboxSetting inventoryTransfers =
		new CheckboxSetting("Inventory transfers",
			"Allow inventory clicks, junk disposal, and container transfers.",
			true);
	private final CheckboxSetting equipElytra = new CheckboxSetting(
		"Equip Elytra",
		"Allow AutoLoot to automatically equip Elytra as chest armor.", false);
	private final CheckboxSetting useAutoDrop = new CheckboxSetting(
		"Use AutoDrop rules",
		"Use AutoDrop's junk rules instead of dropping items outside the keep whitelist.",
		false);
	private final ItemListSetting keepItems = new ItemListSetting(
		"Keep whitelist",
		"Items to keep and collect. Inferior duplicate gear is still discarded. Named items remain protected.",
		LootItemPolicy.defaultWhitelist());
	private final CheckboxSetting autoEquip = new CheckboxSetting("Auto equip",
		"Equip armor from your inventory after closing containers and inventory screens.",
		true);
	private long nextWarningAt;
	private java.util.function.BooleanSupplier pendingActionCheck;
	private long actionCheckAt;
	private String pendingActionDescription;
	private long pickupStartedAt;
	
	private final SliderSetting range = new SliderSetting("Range",
		"Maximum distance to collect valuable dropped items.", 6, 1, 32, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting delay = new SliderSetting("Action delay",
		"Delay between loot actions to avoid sending a burst of clicks.", 250,
		50, 1000, 50, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private Entity pendingKill;
	private ItemEntity movingTo;
	private ItemEntity pickupAttempted;
	private ItemStack pendingPickupStack;
	private int pendingPickupCount;
	private List<ItemStack> inventorySnapshot;
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
		addSetting(onlyAfterInventoryChange);
		addSetting(inventoryTransfers);
		addSetting(equipElytra);
		addSetting(useAutoDrop);
		addSetting(keepItems);
		addSetting(autoEquip);
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
		pickupAttempted = null;
		pendingPickupStack = null;
		activeUntil = mode.getSelected() == Mode.ALWAYS ? Long.MAX_VALUE : 0;
		lastMode = mode.getSelected();
		nextActionAt = 0;
		inventorySnapshot = MC.player == null ? null : snapshotInventory();
		pendingActionCheck = null;
		nextWarningAt = 0;
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		pendingKill = null;
		pendingActionCheck = null;
		stopMoving();
		pickupAttempted = null;
		pendingPickupStack = null;
		inventorySnapshot = null;
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
		{
			pendingActionCheck = null;
			movingTo = null;
			pickupAttempted = null;
			inventorySnapshot = null;
			return;
		}
		if(WURST.getHax().itemHandlerHack.isPickFilterActive())
		{
			stopMoving();
			return;
		}
		checkPickupConfirmation();
		boolean inventoryChanged = inventoryChanged();
		// Continue pending cleanup and equipment actions even without a new
		// pickup.
		if(mode.getSelected() != lastMode)
		{
			lastMode = mode.getSelected();
			pendingKill = null;
			activeUntil = lastMode == Mode.ALWAYS ? Long.MAX_VALUE : 0;
		}
		if(movingTo != null && moveToLoot.isChecked())
		{
			if(!movingTo.isAlive() || movingTo.distanceTo(MC.player) <= 1.0)
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
		if(pendingActionCheck != null)
		{
			if(now < actionCheckAt)
				return;
			boolean succeeded = pendingActionCheck.getAsBoolean();
			pendingActionCheck = null;
			if(!succeeded)
			{
				warn("could not " + pendingActionDescription
					+ "; the server or another inventory feature may be blocking it.");
				nextActionAt = now + 2000;
			}
		}
		if(now < nextActionAt)
			return;
		boolean playerMenu = MC.player.containerMenu == MC.player.inventoryMenu;
		if(inventoryTransfers.isChecked() && playerMenu
			&& MC.gui.screen() == null && autoEquip.isChecked()
			&& equipBetterArmor())
		{
			nextActionAt =
				now + Math.max(MIN_ACTION_DELAY_MS, delay.getValueI());
			return;
		}
		
		if(inventoryTransfers.isChecked() && cleanupInventory())
		{
			nextActionAt =
				now + Math.max(MIN_ACTION_DELAY_MS, delay.getValueI());
			return;
		}
		
		if(inventoryTransfers.isChecked() && containers.isChecked()
			&& MC.gui.screen() instanceof AbstractContainerScreen<?> screen
			&& !(screen instanceof InventoryScreen) && !isEnderChest(screen)
			&& containerSlotCount(screen) > 0)
		{
			if(processContainer(screen))
				nextActionAt =
					now + Math.max(MIN_ACTION_DELAY_MS, delay.getValueI());
			return;
		}
		
		if(onlyAfterInventoryChange.isChecked() && !inventoryChanged
			&& movingTo == null && pickupAttempted == null)
			return;
		if(MC.player.containerMenu != MC.player.inventoryMenu
			|| MC.gui.screen() instanceof AbstractContainerScreen<?>
			|| !processFloorLoot())
			return;
		nextActionAt = now + Math.max(MIN_ACTION_DELAY_MS, delay.getValueI());
	}
	
	private boolean processFloorLoot()
	{
		double r = range.getValue();
		List<ItemEntity> items = MC.level.getEntitiesOfClass(ItemEntity.class,
			MC.player.getBoundingBox().inflate(r),
			e -> e.isAlive() && isLootable(e.getItem()));
		boolean found = items.stream()
			.sorted(Comparator.comparingDouble(e -> e.distanceToSqr(MC.player)))
			.map(this::tryFloorItem).filter(Boolean::booleanValue).findFirst()
			.orElse(false);
		if(!found)
			stopMoving();
		return found;
	}
	
	private boolean tryFloorItem(ItemEntity entity)
	{
		ItemStack candidate = entity.getItem();
		if(!wantsUpgrade(candidate))
			return false;
		if(!hasRoom(candidate))
		{
			warn(
				"inventory is full; no disposable items remain or inventory transfers are disabled.");
			stopMoving();
			return false;
		}
		if(entity.distanceTo(MC.player) > 1.0)
		{
			if(!moveToLoot.isChecked())
				return false;
			movingTo = entity;
			driveToward(entity);
			return true;
		}
		stopMoving();
		if(pickupAttempted != entity)
		{
			pickupAttempted = entity;
			pendingPickupStack = candidate.copy();
			pickupStartedAt = System.currentTimeMillis();
			pendingPickupCount = countMatchingItems(candidate);
			ChatUtils.message("AutoLoot: picking up "
				+ candidate.getHoverName().getString() + ".");
		}
		return true;
	}
	
	private List<ItemStack> snapshotInventory()
	{
		List<ItemStack> snapshot = new ArrayList<>();
		for(int i = 0; i < 41; i++)
			snapshot.add(MC.player.getInventory().getItem(i).copy());
		return snapshot;
	}
	
	private boolean inventoryChanged()
	{
		List<ItemStack> current = snapshotInventory();
		if(inventorySnapshot == null)
		{
			inventorySnapshot = current;
			return false;
		}
		boolean changed = false;
		for(int i = 0; i < current.size(); i++)
		{
			ItemStack oldStack = inventorySnapshot.get(i);
			ItemStack newStack = current.get(i);
			if(oldStack.getCount() != newStack.getCount()
				|| !ItemStack.isSameItemSameComponents(oldStack, newStack))
			{
				changed = true;
				break;
			}
		}
		inventorySnapshot = current;
		return changed;
	}
	
	private void checkPickupConfirmation()
	{
		if(pickupAttempted == null)
			return;
		if(pickupAttempted.isAlive())
		{
			if(System.currentTimeMillis() - pickupStartedAt > 5000)
			{
				warn(
					"pickup is stalled; check space, reachability, pickup restrictions, or ItemHandler.");
				pickupStartedAt = System.currentTimeMillis();
			}
			return;
		}
		if(pendingPickupStack != null
			&& countMatchingItems(pendingPickupStack) > pendingPickupCount)
			ChatUtils.message("AutoLoot: collected "
				+ pendingPickupStack.getHoverName().getString() + ".");
		pickupAttempted = null;
		pendingPickupStack = null;
	}
	
	private int countMatchingItems(ItemStack wanted)
	{
		int count = 0;
		for(int i = 0; i < 36; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(ItemStack.isSameItemSameComponents(stack, wanted))
				count += stack.getCount();
		}
		return count;
	}
	
	private void driveToward(ItemEntity entity)
	{
		WURST.getRotationFaker().faceVectorClientIgnorePitch(entity.position());
		IKeyBinding.get(MC.options.keyUp).simulatePress(true);
	}
	
	private void stopMoving()
	{
		if(movingTo != null)
			IKeyBinding.get(MC.options.keyUp).resetDownState();
		movingTo = null;
	}
	
	private boolean processContainer(AbstractContainerScreen<?> screen)
	{
		if(!screen.getMenu().getCarried().isEmpty())
		{
			warn("put down the cursor stack before looting.");
			return false;
		}
		for(int i = 0; i < containerSlotCount(screen); i++)
		{
			Slot slot = screen.getMenu().slots.get(i);
			ItemStack candidate = slot.getItem();
			if(candidate.isEmpty() || !isLootable(candidate)
				|| !wantsUpgrade(candidate))
				continue;
			if(!hasRoom(candidate))
			{
				warn(
					"inventory is full; cannot transfer valuables from the container.");
				continue;
			}
			// QUICK_MOVE transfers into the player's inventory. Equipment is
			// handled only after the player menu becomes active again.
			ItemStack expected = candidate.copy();
			int before = countMatchingItems(expected);
			screen.slotClicked(slot, slot.index, 0, ContainerInput.QUICK_MOVE);
			verifyAction(() -> countMatchingItems(expected) > before,
				"transfer " + expected.getHoverName().getString());
			return true;
		}
		return false;
	}
	
	private int findBestComparableSlot(ItemStack candidate)
	{
		int best = -1;
		for(int n = 0; n < 41; n++)
		{
			int i = n < 5 ? 36 + n : n - 5;
			if(isComparable(candidate, MC.player.getInventory().getItem(i))
				&& (best < 0 || isBetter(MC.player.getInventory().getItem(i),
					MC.player.getInventory().getItem(best))))
				best = i;
		}
		return best;
	}
	
	private boolean equipBetterArmor()
	{
		if(!MC.player.inventoryMenu.getCarried().isEmpty())
		{
			warn("clear the cursor before equipping armor.");
			return false;
		}
		for(int i = 0; i < 36; i++)
		{
			ItemStack candidate = MC.player.getInventory().getItem(i);
			if(candidate.isEmpty() || !isLootable(candidate))
				continue;
			EquipmentSlot armorSlot = getAutoArmorSlot(candidate);
			if(armorSlot == null)
				continue;
			ItemStack current = MC.player.getItemBySlot(armorSlot);
			if(!isBetter(candidate, current) || isProtected(current))
				continue;
			
			if(current
				.getOrDefault(DataComponents.ENCHANTMENTS,
					net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY)
				.entrySet().stream().anyMatch(e -> e.getKey()
					.getRegisteredName().endsWith(":binding_curse")))
			{
				warn("cannot replace armor with Curse of Binding.");
				continue;
			}
			ItemStack expected = candidate.copy();
			int source = InventoryUtils.toNetworkSlot(i);
			int target = 8 - armorSlot.getIndex();
			IMC.getGameMode().windowClick_PICKUP(source);
			IMC.getGameMode().windowClick_PICKUP(target);
			IMC.getGameMode().windowClick_PICKUP(source);
			verifyAction(
				() -> ItemStack.isSameItemSameComponents(
					MC.player.getItemBySlot(armorSlot), expected),
				"equip " + expected.getHoverName().getString());
			return true;
		}
		return false;
	}
	
	private boolean isComparable(ItemStack a, ItemStack b)
	{
		return a != null && b != null && LootItemPolicy.isGear(a)
			&& LootItemPolicy.isGear(b)
			&& LootItemPolicy.type(a).equals(LootItemPolicy.type(b));
	}
	
	private boolean isBetter(ItemStack candidate, ItemStack current)
	{
		return lootScore(candidate) > lootScore(current);
	}
	
	private int lootScore(ItemStack stack)
	{
		return LootItemPolicy.score(stack);
	}
	
	private boolean isLootable(ItemStack stack)
	{
		return !stack.isEmpty() && keepItems.contains(stack.getItem())
			&& (!useAutoDrop.isChecked() || !isJunk(stack));
	}
	
	private EquipmentSlot getAutoArmorSlot(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return null;
		if(stack.is(Items.PLAYER_HEAD))
			return EquipmentSlot.HEAD;
		if(stack.is(Items.ELYTRA) && !equipElytra.isChecked())
			return null;
		EquipmentSlot slot = ItemUtils.getArmorSlot(stack.getItem());
		if(slot != EquipmentSlot.HEAD && slot != EquipmentSlot.CHEST
			&& slot != EquipmentSlot.LEGS && slot != EquipmentSlot.FEET)
			return null;
		// EQUIPPABLE is also used by non-armor items such as carpets. Require
		// actual armor attributes, while keeping Elytra as a valid chest item.
		if(!stack.is(Items.ELYTRA)
			&& ItemUtils.getArmorPoints(stack.getItem()) <= 0
			&& ItemUtils.getToughness(stack.getItem()) <= 0)
			return null;
		return slot;
	}
	
	private boolean isProtected(ItemStack stack)
	{
		return protectNamed.isChecked() && stack != null
			&& !stack.is(Items.PLAYER_HEAD)
			&& stack.has(DataComponents.CUSTOM_NAME);
	}
	
	private int findJunkSlot()
	{
		for(int i = 0; i < 36; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(stack.isEmpty() || isProtected(stack))
				continue;
			if(isJunk(stack))
				return i;
			if(!LootItemPolicy.isGear(stack))
				continue;
			int best = findBestComparableSlot(stack);
			// Keep exactly one best stack; on ties prefer equipped gear.
			if(best >= 0 && best != i
				&& (isBetter(MC.player.getInventory().getItem(best), stack)
					|| lootScore(MC.player.getInventory()
						.getItem(best)) == lootScore(stack)))
				return i;
		}
		return -1;
	}
	
	private boolean isJunk(ItemStack stack)
	{
		return useAutoDrop.isChecked()
			? WURST.getHax().autoDropHack.isConfiguredJunk(stack)
			: !keepItems.contains(stack.getItem());
	}
	
	private void throwInventorySlot(int inventorySlot)
	{
		ItemStack stack = MC.player.getInventory().getItem(inventorySlot);
		if(stack.isEmpty() || isProtected(stack))
			return;
		ItemStack before = stack.copy();
		IMC.getGameMode()
			.windowClick_THROW(InventoryUtils.toNetworkSlot(inventorySlot));
		verifyDrop(inventorySlot, before);
	}
	
	private void verifyDrop(int slot, ItemStack before)
	{
		verifyAction(() -> {
			ItemStack after = MC.player.getInventory().getItem(slot);
			return !ItemStack.isSameItemSameComponents(before, after)
				|| after.getCount() < before.getCount();
		}, "discard " + before.getHoverName().getString());
	}
	
	private void verifyAction(java.util.function.BooleanSupplier check,
		String description)
	{
		pendingActionCheck = check;
		pendingActionDescription = description;
		actionCheckAt = System.currentTimeMillis() + 500;
	}
	
	public boolean shouldAllowPickup(ItemEntity entity)
	{
		if(!isEnabled() || MC.player == null)
			return true;
		return isLootable(entity.getItem()) && wantsUpgrade(entity.getItem());
	}
	
	private boolean wantsUpgrade(ItemStack candidate)
	{
		if(!LootItemPolicy.isGear(candidate))
			return true;
		int best = findBestComparableSlot(candidate);
		return best < 0
			|| isBetter(candidate, MC.player.getInventory().getItem(best));
	}
	
	private boolean hasRoom(ItemStack candidate)
	{
		if(MC.player.getInventory().getFreeSlot() >= 0)
			return true;
		for(int i = 0; i < 36; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(ItemStack.isSameItemSameComponents(stack, candidate)
				&& stack.getCount() < stack.getMaxStackSize())
				return true;
		}
		return false;
	}
	
	private boolean cleanupInventory()
	{
		if(!MC.player.containerMenu.getCarried().isEmpty())
		{
			warn(
				"put down the cursor stack before dropping or equipping items.");
			return false;
		}
		int junk = findJunkSlot();
		if(junk < 0)
			return false;
		if(MC.player.containerMenu == MC.player.inventoryMenu)
			throwInventorySlot(junk);
		else if(MC.gui.screen() instanceof AbstractContainerScreen<?> screen
			&& containerSlotCount(screen) > 0)
		{
			Slot slot = screen.getMenu().slots
				.get(inventoryMenuSlot(junk, containerSlotCount(screen)));
			ItemStack before = slot.getItem().copy();
			screen.slotClicked(slot, slot.index, 1, ContainerInput.THROW);
			verifyDrop(junk, before);
		}else
		{
			warn("close this menu to discard unwanted items.");
			return false;
		}
		return true;
	}
	
	private void warn(String text)
	{
		long now = System.currentTimeMillis();
		if(now < nextWarningAt)
			return;
		nextWarningAt = now + 5000;
		ChatUtils.message("AutoLoot: " + text);
	}
	
	private int containerSlotCount(AbstractContainerScreen<?> screen)
	{
		if(screen.getMenu() instanceof ChestMenu chest)
		{
			if(chest.getContainer() instanceof PlayerEnderChestContainer)
				return 0;
			return chest.getRowCount() * 9;
		}
		if(screen.getMenu() instanceof ShulkerBoxMenu)
			return 27;
		return 0;
	}
	
	private boolean isEnderChest(AbstractContainerScreen<?> screen)
	{
		return screen.getMenu() instanceof ChestMenu chest
			&& chest.getContainer() instanceof PlayerEnderChestContainer;
	}
	
	private int inventoryMenuSlot(int inventorySlot, int containerSlots)
	{
		return containerSlots
			+ (inventorySlot < 9 ? 27 + inventorySlot : inventorySlot - 9);
	}
	
}
