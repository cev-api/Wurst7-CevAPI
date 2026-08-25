/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IMinecraftClient;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.BlockPlacer;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.InteractionSimulator;

@SearchTags({"quick shulker", "quickshulker", "auto shulker"})
public final class QuickShulkerHack extends Hack
{
	private final EnumSetting<TransferMode> modeSetting = new EnumSetting<>(
		"Items to move", "description.wurst.setting.quickshulker.mode",
		TransferMode.values(), TransferMode.ALL);
	
	private final CheckboxSetting useBlacklist =
		new CheckboxSetting("Use blacklist",
			"description.wurst.setting.quickshulker.use_blacklist", true);
	
	private final CheckboxSetting useWhitelist =
		new CheckboxSetting("Use whitelist",
			"description.wurst.setting.quickshulker.use_whitelist", true);
	
	private final ItemListSetting blacklist = new ItemListSetting("Blacklist",
		"description.wurst.setting.quickshulker.blacklist");
	private final ItemListSetting whitelist = new ItemListSetting("Whitelist",
		"description.wurst.setting.quickshulker.whitelist");
	
	private final CheckboxSetting continueToNextShulker =
		new CheckboxSetting("Continue to next shulker",
			"description.wurst.setting.quickshulker.continue_to_next_shulker",
			false);
	
	private final CheckboxSetting skipHotbar =
		new CheckboxSetting("Skip hotbar",
			"description.wurst.setting.quickshulker.skip_hotbar", false);
	
	private final CheckboxSetting fastBreak = new CheckboxSetting("Fast break",
		"description.wurst.setting.quickshulker.fast_break", true);
	
	private final CheckboxSetting airplace = new CheckboxSetting("Airplace",
		"description.wurst.setting.quickshulker.airplace", false);
	
	private final CheckboxSetting shulkerInventory =
		new CheckboxSetting("Shulker Inventory",
			"description.wurst.setting.quickshulker.shulker_inventory", false);
	
	private final Object workerLock = new Object();
	private Thread worker;
	private PreviewPlan previewPlan;
	
	public QuickShulkerHack()
	{
		super("QuickShulker");
		setCategory(Category.ITEMS);
		addSetting(modeSetting);
		addSetting(useBlacklist);
		addSetting(useWhitelist);
		addSetting(blacklist);
		addSetting(whitelist);
		addSetting(continueToNextShulker);
		addSetting(skipHotbar);
		addSetting(fastBreak);
		addSetting(shulkerInventory);
		addSetting(airplace);
	}
	
	@Override
	protected void onDisable()
	{
		cancelWorker();
	}
	
	public boolean isShulkerInventoryEnabled()
	{
		return isEnabled() && shulkerInventory.isChecked();
	}
	
	public boolean openShulkerInventory(Slot slot)
	{
		if(!isShulkerInventoryEnabled() || MC.player == null || slot == null
			|| slot.container != MC.player.getInventory())
			return false;
		
		ItemStack shulker = slot.getItem();
		if(!isShulkerItem(shulker))
			return false;
		
		Inventory inv = MC.player.getInventory();
		Screen previous = MC.gui.screen();
		MC.gui.setScreen(ShulkerInventoryScreen.open(this, previous, inv,
			slot.getContainerSlot(), shulker.copy()));
		return true;
	}
	
	public void queueShulkerInventoryEdit(int shulkerSlot,
		List<ItemStack> desiredShulker, List<ItemStack> desiredPlayer,
		List<ItemStack> droppedItems)
	{
		if(!isShulkerInventoryEnabled() || MC.player == null)
			return;
		synchronized(workerLock)
		{
			if(isBusy())
				return;
			previewPlan = new PreviewPlan(shulkerSlot,
				desiredShulker.stream().map(ItemStack::copy).toList(),
				desiredPlayer.stream().map(ItemStack::copy).toList(),
				droppedItems.stream().map(ItemStack::copy).toList());
		}
		startWorker();
	}
	
	public boolean isBusy()
	{
		Thread current = worker;
		return current != null && current.isAlive();
	}
	
	public void triggerFromGui()
	{
		if(!isEnabled())
		{
			ChatUtils.warning("QuickShulker needs to be enabled.");
			return;
		}
		
		if(isBusy())
		{
			ChatUtils.warning("QuickShulker is already running.");
			return;
		}
		
		startWorker();
	}
	
	public boolean hasUsableShulker()
	{
		LocalPlayer player = MC.player;
		if(player == null)
			return false;
		
		return findShulkerSlot(player.getInventory()) != -1;
	}
	
	private void startWorker()
	{
		synchronized(workerLock)
		{
			if(isBusy())
				return;
			
			worker = Thread.ofPlatform().name("QuickShulker")
				.uncaughtExceptionHandler((t, e) -> e.printStackTrace())
				.start(this::runTaskSafe);
		}
	}
	
	private void cancelWorker()
	{
		Thread current;
		synchronized(workerLock)
		{
			current = worker;
			worker = null;
		}
		
		if(current != null)
			current.interrupt();
	}
	
	private void runTaskSafe()
	{
		try
		{
			runTask();
			
		}catch(InterruptedException ignored)
		{
			Thread.currentThread().interrupt();
			
		}catch(Exception e)
		{
			ChatUtils.error("QuickShulker failed: " + e.getMessage());
			e.printStackTrace();
			
		}finally
		{
			synchronized(workerLock)
			{
				worker = null;
			}
		}
	}
	
	private void runPreviewTask(PreviewPlan plan) throws InterruptedException
	{
		LocalPlayer player = MC.player;
		if(player == null || MC.level == null)
			return;
		Inventory inv = player.getInventory();
		if(plan.shulkerSlot() < 0
			|| plan.shulkerSlot() >= inv.getContainerSize()
			|| !isShulkerItem(inv.getItem(plan.shulkerSlot())))
		{
			ChatUtils.error("The preview shulker is no longer available.");
			return;
		}
		
		List<ItemStack> originalShulker =
			getShulkerContents(inv.getItem(plan.shulkerSlot()));
		
		BlockPos placePos = findPlacementPos(player);
		if(placePos == null)
		{
			ChatUtils.error("No position available for the preview shulker.");
			return;
		}
		
		int originalSlot = inv.getSelectedSlot();
		ItemSwap shulkerSwap =
			bringSlotToHand(inv, plan.shulkerSlot(), originalSlot);
		if(!shulkerSwap.success())
			return;
		if(!placeShulker(placePos))
		{
			restoreSwap(inv, shulkerSwap);
			
			return;
		}
		
		WurstClient.INSTANCE.getRotationFaker()
			.faceVectorClient(Vec3.atCenterOf(placePos));
		safeSleep(80);
		if(!openShulker(placePos))
		{
			restoreSwap(inv, shulkerSwap);
			return;
		}
		
		AbstractContainerMenu handler = waitForShulkerHandler(player, 3000);
		if(handler == null)
		{
			closeCurrentScreen(player);
			restoreSwap(inv, shulkerSwap);
			return;
		}
		
		for(int i = 0; i < Math.min(27, handler.slots.size()); i++)
		{
			if(Thread.interrupted())
				throw new InterruptedException();
			ItemStack original = originalShulker.get(i);
			ItemStack desired = plan.desiredShulker().get(i);
			if(ItemStack.isSameItemSameComponents(original, desired)
				&& original.getCount() == desired.getCount())
				continue;
			if(original.isEmpty())
				continue;
			MC.gameMode.handleContainerInput(handler.containerId, i, 0,
				ContainerInput.QUICK_MOVE, player);
			safeSleep(45);
		}
		
		for(int target = 0; target < plan.desiredShulker().size()
			&& target < 27; target++)
		{
			ItemStack wanted = plan.desiredShulker().get(target);
			ItemStack original = originalShulker.get(target);
			if(ItemStack.isSameItemSameComponents(original, wanted)
				&& original.getCount() == wanted.getCount())
				continue;
			if(wanted.isEmpty())
				continue;
			int source = findMatchingInventoryStack(inv, wanted);
			if(source < 0)
				continue;
			int handlerSource = toHandlerSlot(handler, inv, source);
			if(handlerSource < 0)
				continue;
			ItemStack sourceStack = inv.getItem(source);
			if(sourceStack.getCount() < wanted.getCount())
				continue;
			
			MC.gameMode.handleContainerInput(handler.containerId, handlerSource,
				0, ContainerInput.PICKUP, player);
			safeSleep(35);
			if(sourceStack.getCount() == wanted.getCount())
			{
				MC.gameMode.handleContainerInput(handler.containerId, target, 0,
					ContainerInput.PICKUP, player);
				safeSleep(50);
				continue;
			}
			
			for(int count = 0; count < wanted.getCount(); count++)
			{
				MC.gameMode.handleContainerInput(handler.containerId, target, 1,
					ContainerInput.PICKUP, player);
				safeSleep(25);
			}
			MC.gameMode.handleContainerInput(handler.containerId, handlerSource,
				0, ContainerInput.PICKUP, player);
			safeSleep(50);
		}
		
		closeCurrentScreen(player);
		safeSleep(80);
		breakPlacedShulker(placePos);
		safeSleep(160);
		restoreSwap(inv, shulkerSwap);
		dropCapturedItems(inv, plan.droppedItems());
		ChatUtils.message("Shulker Inventory applied.");
	}
	
	private void dropCapturedItems(Inventory inv, List<ItemStack> dropped)
		throws InterruptedException
	{
		for(ItemStack wanted : dropped)
		{
			int remaining = wanted.getCount();
			while(remaining > 0)
			{
				if(Thread.interrupted())
					throw new InterruptedException();
				int slot = findMatchingInventoryStack(inv, wanted);
				if(slot < 0)
					break;
				ItemStack current = inv.getItem(slot);
				if(current.getCount() <= remaining)
				{
					IMC.getInteractionManager()
						.windowClick_THROW(InventoryUtils.toNetworkSlot(slot));
					remaining -= current.getCount();
				}else
				{
					int networkSlot = InventoryUtils.toNetworkSlot(slot);
					MC.gameMode.handleContainerInput(
						inv.player.inventoryMenu.containerId, networkSlot, 0,
						ContainerInput.THROW, inv.player);
					remaining--;
				}
				safeSleep(60);
			}
		}
	}
	
	private int findMatchingInventoryStack(Inventory inv, ItemStack wanted)
	{
		for(int i = 0; i < 36; i++)
		{
			ItemStack current = inv.getItem(i);
			if(!current.isEmpty()
				&& ItemStack.isSameItemSameComponents(current, wanted))
				return i;
		}
		return -1;
	}
	
	private void runTask() throws InterruptedException
	{
		PreviewPlan plan;
		synchronized(workerLock)
		{
			plan = previewPlan;
			previewPlan = null;
		}
		if(plan != null)
		{
			runPreviewTask(plan);
			return;
		}
		LocalPlayer player = MC.player;
		if(player == null || MC.level == null)
		{
			ChatUtils.error("Player not available.");
			return;
		}
		
		Inventory inv = player.getInventory();
		int originalSlot = inv.getSelectedSlot();
		int shulkerSlot = findShulkerSlot(inv);
		if(shulkerSlot == -1)
		{
			ChatUtils.error("No non-full shulker box found.");
			return;
		}
		
		// If the player currently has a chest-like UI open, first
		// quick-move the selected chest's contents into the player's
		// inventory and remember which inventory slots increased. This
		// lets us later only move those items into the placed shulker,
		// leaving the rest of the player's inventory untouched.
		AbstractContainerMenu openHandler = player.containerMenu;
		int[] chestOriginSnapshot = null;
		int[] chestAfterSnapshot = null;
		Set<Integer> slotsFromChest = null;
		boolean hadOpenContainer =
			openHandler != null && openHandler != player.inventoryMenu
				&& hasExternalSlots(openHandler, inv);
		if(hadOpenContainer)
		{
			// snapshot before
			chestOriginSnapshot = snapshotInventory(inv);
			// move eligible items from the open container into the player's
			// inventory using QUICK_MOVE
			moveItemsFromOpenContainerToPlayer(player, openHandler,
				modeSetting.getSelected(), blacklist.getItemNames(),
				whitelist.getItemNames(), useBlacklist.isChecked(),
				useWhitelist.isChecked());
			// snapshot after
			chestAfterSnapshot = snapshotInventory(inv);
			slotsFromChest =
				computeIncreasedSlots(chestOriginSnapshot, chestAfterSnapshot);
			// close container UI before placing shulker (wait until closed,
			// then a short pause to allow inventory updates to settle)
			closeCurrentScreen(player);
			safeSleep(50);
		}
		List<ItemStack> originalShulker =
			getShulkerContents(inv.getItem(plan.shulkerSlot()));
		
		BlockPos placePos = findPlacementPos(player);
		if(placePos == null)
		{
			ChatUtils.error("No safe space to place a shulker nearby.");
			return;
		}
		
		closeCurrentScreen(player);
		safeSleep(50);
		
		// Loop placing shulkers and transferring items until there are no
		// remaining items from the chest or the user disabled continuing.
		Set<String> blacklistSet = new HashSet<>(blacklist.getItemNames());
		Set<String> whitelistSet = new HashSet<>(whitelist.getItemNames());
		TransferMode mode = modeSetting.getSelected();
		boolean keepGoing = continueToNextShulker.isChecked();
		Set<Integer> remainingSlots =
			slotsFromChest == null ? null : new HashSet<>(slotsFromChest);
		
		boolean avoidHotbar = skipHotbar.isChecked();
		while(true)
		{
			ItemSwap shulkerSwap =
				bringSlotToHand(inv, shulkerSlot, originalSlot);
			if(!shulkerSwap.success())
				return;
			
			if(!placeShulker(placePos))
			{
				ChatUtils.error("Failed to place shulker.");
				restoreSwap(inv, shulkerSwap);
				return;
			}
			
			// Ensure the player is facing the placed shulker so the open
			// interaction targets it and not any nearby container.
			WurstClient.MC.execute(() -> {
				try
				{
					WurstClient.INSTANCE.getRotationFaker()
						.faceVectorClient(Vec3.atCenterOf(placePos));
				}catch(Throwable ignored)
				{}
			});
			safeSleep(80);
			if(!openShulker(placePos))
			{
				ChatUtils.error("Failed to open shulker.");
				restoreSwap(inv, shulkerSwap);
				return;
			}
			
			AbstractContainerMenu handler = waitForShulkerHandler(player, 3000);
			if(handler == null)
			{
				ChatUtils.error("Timed out waiting for shulker UI.");
				closeCurrentScreen(player);
				restoreSwap(inv, shulkerSwap);
				return;
			}
			
			int pickaxeSlot = findPickaxeSlot(inv);
			Set<Integer> protectedSlots = new HashSet<>();
			if(shulkerSwap.storageSlot >= 0 && shulkerSwap.storageSlot < 36)
				protectedSlots.add(shulkerSwap.storageSlot);
			if(pickaxeSlot >= 0 && pickaxeSlot < 36)
				protectedSlots.add(pickaxeSlot);
			
			if(remainingSlots != null && !remainingSlots.isEmpty())
			{
				transferItemsFromSlots(player, handler, blacklistSet,
					whitelistSet, mode, protectedSlots, remainingSlots);
			}else
			{
				transferItems(player, handler, blacklistSet, whitelistSet, mode,
					protectedSlots);
			}
			safeSleep(120);
			closeCurrentScreen(player);
			
			// Check if there are any remaining items that came from the chest.
			boolean anyLeft = false;
			if(remainingSlots != null && !remainingSlots.isEmpty())
			{
				for(int s : remainingSlots)
				{
					if(s < 0 || s >= 36)
						continue;
					if(avoidHotbar && s < 9)
						continue;
					if(!inv.getItem(s).isEmpty())
					{
						anyLeft = true;
						break;
					}
				}
			}
			
			// Prepare to break the placed shulker (bring pickaxe if needed).
			ItemSwap pickaxeSwap =
				pickaxeSlot == -1 ? ItemSwap.keepSelection(originalSlot)
					: bringSlotToHand(inv, pickaxeSlot, originalSlot);
			
			breakPlacedShulker(placePos);
			safeSleep(160);
			
			// Restore inventory state for this iteration.
			restoreSwap(inv, pickaxeSwap);
			restoreSwap(inv, shulkerSwap);
			
			if(!anyLeft)
				break;
			if(!keepGoing)
				break;
			
			// find another available shulker and continue the loop
			shulkerSlot = findShulkerSlot(inv);
			if(shulkerSlot == -1)
			{
				ChatUtils.error("No non-full shulker box found to continue.");
				break;
			}
		}
		
		ChatUtils.message("QuickShulker finished.");
	}
	
	private void transferItems(LocalPlayer player,
		AbstractContainerMenu handler, Set<String> blacklistSet,
		Set<String> whitelistSet, TransferMode mode,
		Set<Integer> protectedSlots) throws InterruptedException
	{
		Inventory inv = player.getInventory();
		boolean useBl = useBlacklist.isChecked();
		boolean useWl = useWhitelist.isChecked();
		boolean avoidHotbar = skipHotbar.isChecked();
		for(int slot = 0; slot < 36; slot++)
		{
			if(Thread.interrupted())
				throw new InterruptedException();
			
			if(protectedSlots.contains(slot))
				continue;
			if(avoidHotbar && slot < 9)
				continue;
			
			ItemStack stack = inv.getItem(slot);
			if(stack.isEmpty())
				continue;
			
			if(isShulkerItem(stack))
				continue;
			
			if(mode == TransferMode.STACKABLE && !stack.isStackable())
				continue;
			if(mode == TransferMode.NON_STACKABLE && stack.isStackable())
				continue;
			
			String id =
				BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			if(useBl && blacklistSet.contains(id))
				continue;
			if(useWl && !whitelistSet.isEmpty() && !whitelistSet.contains(id))
				continue;
			
			int handlerSlot = toHandlerSlot(handler, inv, slot);
			if(handlerSlot == -1)
				continue;
			
			int before = stack.getCount();
			MC.gameMode.handleContainerInput(handler.containerId, handlerSlot,
				0, ContainerInput.QUICK_MOVE, player);
			safeSleep(70);
			
			ItemStack after = inv.getItem(slot);
			if(after.getCount() == before)
				continue;
			
			if(!hasContainerSpace(handler, inv))
				return;
		}
	}
	
	/**
	 * Transfer only the specified player inventory slots into the open
	 * shulker handler.
	 */
	private void transferItemsFromSlots(LocalPlayer player,
		AbstractContainerMenu handler, Set<String> blacklistSet,
		Set<String> whitelistSet, TransferMode mode,
		Set<Integer> protectedSlots, Set<Integer> slotsToTransfer)
		throws InterruptedException
	{
		Inventory inv = player.getInventory();
		boolean useBl = useBlacklist.isChecked();
		boolean useWl = useWhitelist.isChecked();
		boolean avoidHotbar = skipHotbar.isChecked();
		for(int slot : slotsToTransfer)
		{
			if(Thread.interrupted())
				throw new InterruptedException();
			
			if(protectedSlots.contains(slot))
				continue;
			if(avoidHotbar && slot < 9)
				continue;
			
			if(slot < 0 || slot >= 36)
				continue;
			
			ItemStack stack = inv.getItem(slot);
			if(stack.isEmpty())
				continue;
			
			if(isShulkerItem(stack))
				continue;
			
			if(mode == TransferMode.STACKABLE && !stack.isStackable())
				continue;
			if(mode == TransferMode.NON_STACKABLE && stack.isStackable())
				continue;
			
			String id =
				BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			if(useBl && blacklistSet.contains(id))
				continue;
			if(useWl && !whitelistSet.isEmpty() && !whitelistSet.contains(id))
				continue;
			
			int handlerSlot = toHandlerSlot(handler, inv, slot);
			if(handlerSlot == -1)
				continue;
			
			int before = stack.getCount();
			MC.gameMode.handleContainerInput(handler.containerId, handlerSlot,
				0, ContainerInput.QUICK_MOVE, player);
			safeSleep(70);
			
			ItemStack after = inv.getItem(slot);
			if(after.getCount() == before)
				continue;
			
			if(!hasContainerSpace(handler, inv))
				return;
		}
	}
	
	private boolean hasExternalSlots(AbstractContainerMenu handler,
		Inventory inv)
	{
		for(Slot slot : handler.slots)
		{
			if(slot.container != inv)
				return true;
		}
		return false;
	}
	
	private int[] snapshotInventory(Inventory inv)
	{
		int[] arr = new int[36];
		for(int i = 0; i < 36; i++)
		{
			ItemStack s = inv.getItem(i);
			arr[i] = s == null ? 0 : s.getCount();
		}
		return arr;
	}
	
	private Set<Integer> computeIncreasedSlots(int[] before, int[] after)
	{
		Set<Integer> set = new HashSet<>();
		if(before == null || after == null)
			return set;
		int len = Math.min(before.length, after.length);
		for(int i = 0; i < len; i++)
		{
			if(after[i] > before[i])
				set.add(i);
		}
		return set;
	}
	
	private void moveItemsFromOpenContainerToPlayer(LocalPlayer player,
		AbstractContainerMenu handler, TransferMode mode,
		java.util.List<String> bl, java.util.List<String> wl, boolean useBl,
		boolean useWl) throws InterruptedException
	{
		Inventory inv = player.getInventory();
		Set<String> blacklistSet = new HashSet<>(bl);
		Set<String> whitelistSet = new HashSet<>(wl);
		for(int i = 0; i < handler.slots.size(); i++)
		{
			if(Thread.interrupted())
				throw new InterruptedException();
			
			Slot slot = handler.slots.get(i);
			if(slot.container == inv)
				continue;
			ItemStack stack = slot.getItem();
			if(stack.isEmpty())
				continue;
			
			if(isShulkerItem(stack))
				continue;
			
			if(mode == TransferMode.STACKABLE && !stack.isStackable())
				continue;
			if(mode == TransferMode.NON_STACKABLE && stack.isStackable())
				continue;
			
			String id =
				BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			if(useBl && blacklistSet.contains(id))
				continue;
			if(useWl && !whitelistSet.isEmpty() && !whitelistSet.contains(id))
				continue;
			
			MC.gameMode.handleContainerInput(handler.containerId, i, 0,
				ContainerInput.QUICK_MOVE, player);
			safeSleep(70);
		}
	}
	
	private boolean hasContainerSpace(AbstractContainerMenu handler,
		Inventory playerInventory)
	{
		for(Slot slot : handler.slots)
		{
			if(slot.container == playerInventory)
				continue;
			if(!slot.hasItem())
				return true;
		}
		return false;
	}
	
	private int toHandlerSlot(AbstractContainerMenu handler, Inventory inv,
		int playerSlot)
	{
		for(int i = 0; i < handler.slots.size(); i++)
		{
			Slot slot = handler.slots.get(i);
			if(slot.container == inv && slot.getContainerSlot() == playerSlot)
				return i;
		}
		return -1;
	}
	
	private AbstractContainerMenu waitForShulkerHandler(LocalPlayer player,
		long timeoutMs) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + timeoutMs;
		while(System.currentTimeMillis() < deadline)
		{
			if(Thread.interrupted())
				throw new InterruptedException();
			
			AbstractContainerMenu current = player.containerMenu;
			if(current instanceof ShulkerBoxMenu handler
				&& current != player.inventoryMenu)
				return handler;
			
			safeSleep(50);
		}
		return null;
	}
	
	private boolean openShulker(BlockPos pos) throws InterruptedException
	{
		for(int attempt = 0; attempt < 3; attempt++)
		{
			final boolean[] accepted = new boolean[1];
			final BlockPos fpos = pos;
			// run the actual interaction on the client thread
			WurstClient.MC.execute(() -> {
				try
				{
					BlockHitResult hit = new BlockHitResult(
						Vec3.atCenterOf(fpos), Direction.UP, fpos, false);
					InteractionResult result =
						WurstClient.MC.gameMode.useItemOn(WurstClient.MC.player,
							InteractionHand.MAIN_HAND, hit);
					accepted[0] = result.consumesAction();
				}catch(Throwable ignored)
				{
					accepted[0] = false;
				}
			});
			
			// allow the client thread some time to perform the interaction
			safeSleep(100);
			if(accepted[0])
				return true;
		}
		return false;
	}
	
	private boolean breakPlacedShulker(BlockPos pos) throws InterruptedException
	{
		// Try the instant-break approach first if Fast break is enabled.
		if(fastBreak.isChecked() && breakPlacedShulkerFast(pos))
			return true;
		
		for(int attempt = 0; attempt < 40; attempt++)
		{
			if(BlockUtils.getState(pos).isAir())
				return true;
			
			final BlockPos fpos = pos;
			final boolean[] started = new boolean[1];
			WurstClient.MC.execute(() -> {
				try
				{
					started[0] = BlockBreaker.breakOneBlock(fpos);
				}catch(Throwable ignored)
				{
					started[0] = false;
				}
			});
			
			if(!started[0])
				safeSleep(50);
			
			safeSleep(60);
		}
		return BlockUtils.getState(pos).isAir();
	}
	
	private boolean breakPlacedShulkerFast(BlockPos pos)
		throws InterruptedException
	{
		for(int attempt = 0; attempt < 10; attempt++)
		{
			if(BlockUtils.getState(pos).isAir())
				return true;
			
			final BlockPos fpos = pos;
			WurstClient.MC.execute(() -> {
				try
				{
					fastBreakBlock(fpos);
				}catch(Throwable ignored)
				{}
			});
			
			safeSleep(40);
		}
		return BlockUtils.getState(pos).isAir();
	}
	
	private void fastBreakBlock(BlockPos pos)
	{
		if(MC.getConnection() == null || MC.player == null || MC.level == null)
			return;
		
		Direction face = getBreakDirection(pos);
		
		// Face the block so the server accepts the break.
		WurstClient.INSTANCE.getRotationFaker()
			.faceVectorClient(Vec3.atCenterOf(pos));
		
		// Swing the arm for visual feedback.
		MC.player.swing(InteractionHand.MAIN_HAND);
		
		// Instant-break: start then immediately stop destroying the block.
		// Same technique used by SuperInstaMine / Nuker packet-spam mode.
		MC.getConnection()
			.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos,
				face));
		MC.getConnection()
			.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos,
				face));
	}
	
	private Direction getBreakDirection(BlockPos pos)
	{
		Vec3 delta = Vec3.atCenterOf(pos).subtract(MC.player.getEyePosition());
		if(delta.lengthSqr() < 1.0E-6)
			return Direction.UP;
		
		double ax = Math.abs(delta.x);
		double ay = Math.abs(delta.y);
		double az = Math.abs(delta.z);
		
		if(ay >= ax && ay >= az)
			return delta.y > 0 ? Direction.DOWN : Direction.UP;
		if(ax >= az)
			return delta.x > 0 ? Direction.WEST : Direction.EAST;
		return delta.z > 0 ? Direction.NORTH : Direction.SOUTH;
	}
	
	private boolean placeShulker(BlockPos pos) throws InterruptedException
	{
		for(int attempt = 0; attempt < 3; attempt++)
		{
			final BlockPos fpos = pos;
			final boolean[] placed = new boolean[1];
			WurstClient.MC.execute(() -> {
				try
				{
					BlockPlacer.BlockPlacingParams params =
						BlockPlacer.getBlockPlacingParams(fpos);
					if(params != null && !params.requiresSneaking())
					{
						placed[0] = BlockPlacer.placeOneBlock(fpos);
						return;
					}
					if(!airplace.isChecked())
						return;
					Vec3 hitVec = Vec3.atCenterOf(fpos);
					WurstClient.INSTANCE.getRotationFaker()
						.faceVectorPacket(hitVec);
					InteractionSimulator.rightClickBlock(
						new BlockHitResult(hitVec, Direction.UP, fpos, false),
						InteractionHand.MAIN_HAND);
					placed[0] = true;
				}catch(Throwable ignored)
				{}
			});
			safeSleep(80);
			if(placed[0] || BlockUtils.getBlock(pos) instanceof ShulkerBoxBlock)
				return true;
		}
		return BlockUtils.getBlock(pos) instanceof ShulkerBoxBlock;
	}
	
	private void closeCurrentScreen(LocalPlayer player)
	{
		// Close current screen on the client thread and wait for it to be
		// actually closed. This prevents the mouse/input from staying
		// locked after QuickShulker finishes.
		if(WurstClient.MC == null)
			return;
		
		if(WurstClient.MC.gui.screen() == null)
			return;
		
		WurstClient.MC.execute(() -> {
			try
			{
				if(WurstClient.MC.player != null)
					WurstClient.MC.player.closeContainer();
			}catch(Throwable ignored)
			{}
		});
		
		long deadline = System.currentTimeMillis() + 2000L;
		while(System.currentTimeMillis() < deadline)
		{
			if(Thread.interrupted())
			{
				Thread.currentThread().interrupt();
				return;
			}
			if(WurstClient.MC.gui.screen() == null)
				return;
			try
			{
				Thread.sleep(50);
			}catch(InterruptedException ie)
			{
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
	
	private ItemSwap bringSlotToHand(Inventory inv, int sourceSlot,
		int targetHotbarSlot)
	{
		if(sourceSlot == -1)
			return ItemSwap.failure(targetHotbarSlot);
		
		if(sourceSlot == targetHotbarSlot)
		{
			inv.setSelectedSlot(targetHotbarSlot);
			return ItemSwap.keepSelection(targetHotbarSlot);
		}
		
		IMinecraftClient imc = WurstClient.IMC;
		imc.getInteractionManager().windowClick_SWAP(
			InventoryUtils.toNetworkSlot(sourceSlot), targetHotbarSlot);
		inv.setSelectedSlot(targetHotbarSlot);
		return new ItemSwap(targetHotbarSlot, sourceSlot, true);
	}
	
	private void restoreSwap(Inventory inv, ItemSwap swap)
	{
		if(!swap.success())
			return;
		
		if(swap.storageSlot >= 0)
		{
			WurstClient.IMC.getInteractionManager().windowClick_SWAP(
				InventoryUtils.toNetworkSlot(swap.storageSlot),
				swap.restoreSlot);
		}
		
		inv.setSelectedSlot(swap.restoreSlot);
	}
	
	private BlockPos findPlacementPos(LocalPlayer player)
	{
		BlockPos base = player.blockPosition();
		Direction facing = player.getDirection();
		BlockPos[] candidates = new BlockPos[]{base.relative(facing),
			base.relative(facing.getClockWise()),
			base.relative(facing.getCounterClockWise()),
			base.relative(facing.getOpposite()), base.above(2), base.above(),
			base.below()};
		
		AABB playerBox = player.getBoundingBox();
		for(BlockPos pos : candidates)
		{
			BlockState state = BlockUtils.getState(pos);
			if(!state.canBeReplaced())
				continue;
			
			BlockState below = BlockUtils.getState(pos.below());
			if(below.isAir() && !airplace.isChecked())
				continue;
			
			AABB blockBox = new AABB(pos);
			if(blockBox.intersects(playerBox))
				continue;
			
			return pos;
		}
		
		return null;
	}
	
	private List<ItemStack> getShulkerContents(ItemStack shulker)
	{
		ItemContainerContents component = shulker.getOrDefault(
			DataComponents.CONTAINER, ItemContainerContents.EMPTY);
		net.minecraft.core.NonNullList<ItemStack> contents =
			net.minecraft.core.NonNullList.withSize(27, ItemStack.EMPTY);
		component.copyInto(contents);
		return contents.stream().map(ItemStack::copy).toList();
	}
	
	private int findShulkerSlot(Inventory inv)
	{
		for(int slot = 0; slot < inv.getContainerSize(); slot++)
		{
			ItemStack stack = inv.getItem(slot);
			if(stack.isEmpty())
				continue;
			if(!isShulkerItem(stack))
				continue;
			if(!isShulkerFull(stack))
				return slot;
		}
		return -1;
	}
	
	private int findPickaxeSlot(Inventory inv)
	{
		int bestSlot = -1;
		int bestPriority = -1;
		for(int slot = 0; slot < inv.getContainerSize(); slot++)
		{
			ItemStack stack = inv.getItem(slot);
			if(stack.isEmpty() || !stack.is(ItemTags.PICKAXES))
				continue;
			
			int priority = getPickaxePriority(stack.getItem());
			if(priority > bestPriority)
			{
				bestPriority = priority;
				bestSlot = slot;
			}
		}
		return bestSlot;
	}
	
	private int getPickaxePriority(Item item)
	{
		if(item == Items.NETHERITE_PICKAXE)
			return 4;
		if(item == Items.DIAMOND_PICKAXE)
			return 3;
		if(item == Items.IRON_PICKAXE)
			return 2;
		if(item == Items.STONE_PICKAXE)
			return 1;
		return 0;
	}
	
	public boolean isShulkerItem(ItemStack stack)
	{
		Item item = stack.getItem();
		if(!(item instanceof BlockItem blockItem))
			return false;
		return blockItem.getBlock() instanceof ShulkerBoxBlock;
	}
	
	private boolean isShulkerFull(ItemStack shulker)
	{
		ItemContainerContents container = shulker.get(DataComponents.CONTAINER);
		if(container == null)
			return false;
		
		int occupied = 0;
		for(ItemStack stack : container.nonEmptyItemCopyStream().toList())
		{
			if(stack.isEmpty())
				continue;
			occupied++;
			if(occupied >= 27)
				return true;
		}
		
		return false;
	}
	
	private void sleep(long ms)
	{
		try
		{
			Thread.sleep(ms);
		}catch(InterruptedException ie)
		{
			Thread.currentThread().interrupt();
		}
	}
	
	/**
	 * Non-throwing wrapper for sleeping used to avoid compile-time
	 * InterruptedException handling in callers.
	 */
	private void safeSleep(long ms)
	{
		try
		{
			Thread.sleep(ms);
		}catch(InterruptedException ie)
		{
			Thread.currentThread().interrupt();
		}
	}
	
	private enum TransferMode
	{
		ALL("All items"),
		STACKABLE("Stackable"),
		NON_STACKABLE("Non-stackable");
		
		private final String label;
		
		TransferMode(String label)
		{
			this.label = Objects.requireNonNull(label);
		}
		
		@Override
		public String toString()
		{
			return label;
		}
	}
	
	private record PreviewPlan(int shulkerSlot, List<ItemStack> desiredShulker,
		List<ItemStack> desiredPlayer, List<ItemStack> droppedItems)
	{}
	
	private static final class ItemSwap
	{
		private final int restoreSlot;
		private final int storageSlot;
		private final boolean success;
		
		private ItemSwap(int restoreSlot, int storageSlot, boolean success)
		{
			this.restoreSlot = restoreSlot;
			this.storageSlot = storageSlot;
			this.success = success;
		}
		
		private static ItemSwap failure(int restoreSlot)
		{
			return new ItemSwap(restoreSlot, -1, false);
		}
		
		private static ItemSwap keepSelection(int slot)
		{
			return new ItemSwap(slot, -1, true);
		}
		
		private boolean success()
		{
			return success;
		}
	}
}
