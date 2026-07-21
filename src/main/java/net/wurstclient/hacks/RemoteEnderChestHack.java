/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BeaconBlock;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CartographyTableBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.SmithingTableBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.events.RightClickListener.RightClickEvent;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.InventoryUtils;

@SearchTags({"remote echest", "remote ender chest", "ender chest",
	"portable echest", "portable ender chest"})
public final class RemoteEnderChestHack extends Hack
	implements UpdateListener, RightClickListener
{
	private static RemoteEnderChestHack instance;
	
	private boolean guiHidden;
	private boolean guiWasOpen;
	private boolean lastToggleKeyState;
	private AbstractContainerScreen<?> savedScreen;
	private int savedSyncId = -1;
	private Level lastWorld;
	private BlockPos potentialEChestPos;
	private boolean blockedContainerWarningShown;
	private int autoTotemReserve = -1;
	private boolean autoTotemRefillRequested;
	
	private final TextFieldSetting toggleGuiKey =
		new TextFieldSetting("Toggle GUI key", "key.keyboard.left.alt",
			RemoteEnderChestHack::isValidKey);
	
	private final CheckboxSetting swapInventoryKey =
		new CheckboxSetting("Swap inventory key",
			"When active, pressing the inventory key (E) will open the linked\n"
				+ "ender chest instead of the regular inventory.",
			false);
	
	private final CheckboxSetting autoTotem = new CheckboxSetting("Auto Totem",
		"When AutoTotem is enabled and your inventory has no totems,"
			+ " automatically move one from the linked ender chest without"
			+ " opening its GUI.",
		false);
	
	private final CheckboxSetting disableContainers = new CheckboxSetting(
		"Disable containers",
		"For forgetful players who keep breaking the link to their e-chest.",
		false);
	
	public RemoteEnderChestHack()
	{
		super("RemoteEChest");
		instance = this;
		setCategory(Category.OTHER);
		addSetting(toggleGuiKey);
		addSetting(swapInventoryKey);
		addSetting(autoTotem);
		addSetting(disableContainers);
	}
	
	// ---- Mixin API (X button, E/ESC key interception) ----
	
	public static boolean isLinkedScreen(AbstractContainerScreen<?> screen)
	{
		RemoteEnderChestHack self = instance;
		if(self == null || !self.isEnabled())
			return false;
		
		return self.savedScreen == screen
			&& self.isSavedContainerMenuStillActive();
	}
	
	public static void hideLinkedGui()
	{
		RemoteEnderChestHack self = instance;
		if(self == null || !self.isEnabled() || self.savedScreen == null)
			return;
		
		if(!self.isSavedContainerMenuStillActive())
		{
			self.breakLink("Ender chest handler invalid. EChest link broken.");
			return;
		}
		
		if(!self.guiHidden)
		{
			self.MC.gui.setScreen(null);
			self.guiHidden = true;
		}
	}
	
	public static void toggleLinkedGui()
	{
		RemoteEnderChestHack self = instance;
		if(self == null || !self.isEnabled() || self.savedScreen == null)
			return;
		
		if(!self.isSavedContainerMenuStillActive())
		{
			self.breakLink("Ender chest handler invalid. EChest link broken.");
			return;
		}
		
		if(self.guiHidden)
		{
			self.MC.gui.setScreen(self.savedScreen);
			self.guiHidden = false;
		}else
		{
			self.MC.gui.setScreen(null);
			self.guiHidden = true;
		}
	}
	
	// Use this from packet/screen mixins to detect the saved container.
	public static boolean shouldKeepContainerOpen(int syncId)
	{
		RemoteEnderChestHack self = instance;
		if(self == null || !self.isEnabled())
			return false;
		
		return self.savedScreen != null && self.savedSyncId == syncId
			&& self.isSavedContainerMenuStillActive();
	}
	
	/**
	 * Called when the server confirms that this player has popped a totem.
	 * The active chest menu does not always receive an offhand slot update, so
	 * this must not rely on the client-side offhand item being empty.
	 */
	public static void requestAutoTotemRefill()
	{
		RemoteEnderChestHack self = instance;
		if(self != null && self.isEnabled() && self.autoTotem.isChecked())
			self.autoTotemRefillRequested = true;
	}
	
	// ---------------------------------------------------------------
	
	@Override
	public String getRenderName()
	{
		return super.getRenderName();
	}
	
	@Override
	public String getStatusText()
	{
		if(isEnabled() && savedScreen != null
			&& isSavedContainerMenuStillActive())
			return " [Active]";
		return null;
	}
	
	/**
	 * Called from mixin to check if the inventory key should open the
	 * linked ender chest instead.
	 */
	public boolean shouldSwapInventoryKey()
	{
		return isEnabled() && swapInventoryKey.isChecked()
			&& savedScreen != null && isSavedContainerMenuStillActive()
			&& guiHidden;
	}
	
	/**
	 * Opens the linked ender chest GUI when the inventory key is pressed.
	 */
	public void openLinkedGui()
	{
		if(savedScreen == null || !isSavedContainerMenuStillActive())
			return;
		MC.gui.setScreen(savedScreen);
		guiHidden = false;
	}
	
	/**
	 * Returns true if the X button should open the player inventory instead
	 * of just hiding the linked GUI. This checks the swap inventory key
	 * setting without requiring the GUI to be hidden.
	 */
	public boolean shouldCancelWithInventory()
	{
		return isEnabled() && swapInventoryKey.isChecked()
			&& savedScreen != null && isSavedContainerMenuStillActive();
	}
	
	/**
	 * Breaks the ender chest link and opens the player's own inventory,
	 * effectively cancelling the remote ender chest hack when the X button
	 * is pressed in swap-inventory-key mode.
	 */
	public static void cancelWithPlayerInventory()
	{
		RemoteEnderChestHack self = instance;
		if(self == null || !self.isEnabled() || self.savedScreen == null)
			return;
		
		// Break the link (closes the current screen and cleans up state)
		self.resetStuff(false);
		ChatUtils.message("EChest link cancelled. Opened player inventory.");
		
		// Open the player's actual inventory
		if(self.MC.player != null)
		{
			self.MC.gui.setScreen(new InventoryScreen(self.MC.player));
		}
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RightClickListener.class, this);
		resetStuff();
		
		if(MC.player == null || MC.level == null)
		{
			setEnabled(false);
			return;
		}
		
		lastWorld = MC.level;
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RightClickListener.class, this);
		resetStuff();
	}
	
	@Override
	public void onRightClick(RightClickEvent event)
	{
		if(!disableContainers.isChecked() || savedScreen == null
			|| !isSavedContainerMenuStillActive() || MC.level == null
			|| !(MC.hitResult instanceof BlockHitResult hit)
			|| !isContainerBlock(hit.getBlockPos()))
			return;
		
		event.cancel();
		if(MC.options.keyUse.isDown())
		{
			if(blockedContainerWarningShown)
				return;
			blockedContainerWarningShown = true;
		}
		ChatUtils.message(
			"Containers are disabled while the EChest link is active.");
	}
	
	private boolean isContainerBlock(BlockPos pos)
	{
		BlockState state = MC.level.getBlockState(pos);
		if(MC.level.getBlockEntity(pos) instanceof BaseContainerBlockEntity)
			return true;
		
		var block = state.getBlock();
		return block instanceof ChestBlock || block instanceof BarrelBlock
			|| block instanceof ShulkerBoxBlock || block instanceof HopperBlock
			|| block instanceof DispenserBlock || block instanceof BeaconBlock
			|| block instanceof BrewingStandBlock || block instanceof AnvilBlock
			|| block instanceof CartographyTableBlock
			|| block instanceof CrafterBlock
			|| block instanceof EnchantingTableBlock
			|| block instanceof GrindstoneBlock || block instanceof LecternBlock
			|| block instanceof LoomBlock || block instanceof SmithingTableBlock
			|| block instanceof StonecutterBlock || block == Blocks.DROPPER
			|| block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE
			|| block == Blocks.SMOKER || block == Blocks.ENDER_CHEST
			|| block == Blocks.CRAFTER;
	}
	
	@Override
	public void onUpdate()
	{
		if(!MC.options.keyUse.isDown())
			blockedContainerWarningShown = false;
		
		if(MC.player == null || MC.level == null)
		{
			resetStuff(false);
			return;
		}
		
		// Swap inventory key: when the player opens their inventory (E key),
		// immediately replace it with the linked ender chest GUI.
		if(swapInventoryKey.isChecked() && savedScreen != null
			&& isSavedContainerMenuStillActive() && guiHidden
			&& MC.gui.screen() instanceof InventoryScreen invScreen
			&& invScreen != savedScreen)
		{
			MC.gui.setScreen(savedScreen);
			guiHidden = false;
			return;
		}
		
		if(MC.gui.screen() == null && savedScreen != null
			&& isSavedContainerMenuStillActive())
		{
			InputConstants.Key key = getToggleGuiKey();
			if(key != null)
			{
				boolean keyDown =
					InputConstants.isKeyDown(MC.getWindow(), key.getValue());
				boolean keyJustPressed = keyDown && !lastToggleKeyState;
				lastToggleKeyState = keyDown;
				if(keyJustPressed)
				{
					toggleLinkedGui();
					return;
				}
			}
		}else
			lastToggleKeyState = false;
		
		if(MC.hitResult instanceof BlockHitResult bhr)
		{
			potentialEChestPos = bhr.getBlockPos();
			
			if(MC.gui.screen() == null
				&& MC.level.getBlockState(bhr.getBlockPos())
					.getBlock() == Blocks.ENDER_CHEST
				&& MC.options.keyUse.isDown()
				&& !isEnderChestScreen(potentialEChestPos) && !guiHidden)
			{
				MC.startUseItem();
				MC.startUseItem();
			}
		}
		
		// Opening another container, chest, player inventory, etc. invalidates
		// the saved link. Clear it immediately so the toggle key cannot
		// reopen a stale ghost GUI later.
		if(savedScreen != null
			&& MC.gui.screen() instanceof AbstractContainerScreen<?> screen
			&& screen != savedScreen)
		{
			breakLink(
				"Opened another inventory/container. EChest link broken.");
		}
		
		// If the server/client has already returned to the player inventory
		// menu,
		// the saved screen is stale. This prevents ghost clicks like:
		// "Ignoring click in mismatching container. Click in 4, player has 0."
		if(savedScreen != null && !isSavedContainerMenuStillActive())
		{
			breakLink("Ender chest handler invalid. EChest link broken.");
			return;
		}
		
		tryAutoTotemTransfer();
		
		if(isEnderChestScreen(potentialEChestPos) && savedScreen == null
			&& !guiWasOpen && !guiHidden)
		{
			savedScreen = (AbstractContainerScreen<?>)MC.gui.screen();
			
			savedSyncId = savedScreen.getMenu().containerId;
			
			MC.gui.setScreen(null);
			guiHidden = true;
			guiWasOpen = true;
			lastWorld = MC.level;
			ChatUtils.message(
				"EChest link created! Use the configured toggle key to show or"
					+ " hide the GUI.");
		}
		
		if(savedScreen != null && MC.gui.screen() == null && !guiHidden
			&& guiWasOpen)
		{
			
			// ESC/X will hide the GUI.
			guiHidden = true;
			return;
		}
		
		if(savedScreen != null && MC.level != lastWorld)
		{
			breakLink("World changed. EChest link broken.");
			lastWorld = MC.level;
			return;
		}
		
		lastWorld = MC.level;
	}
	
	private void tryAutoTotemTransfer()
	{
		if(!autoTotem.isChecked() || savedScreen == null
			|| !isSavedContainerMenuStillActive()
			|| !WURST.getHax().autoTotemHack.isEnabled())
			return;
		
		boolean forceRefill = autoTotemRefillRequested;
		int localTotems = InventoryUtils
			.count(stack -> stack.is(Items.TOTEM_OF_UNDYING), 40, true);
		if(autoTotemReserve < 0)
			autoTotemReserve = Math.max(1, localTotems);
		if(!forceRefill && localTotems >= autoTotemReserve)
			return;
			
		// A generic 9x3 chest has 27 chest slots before the player's slots.
		// slotClicked sends the normal server-validated click even when the
		// saved screen is not currently displayed.
		for(int i = 0; i < Math.min(27,
			savedScreen.getMenu().slots.size()); i++)
		{
			Slot slot = savedScreen.getMenu().getSlot(i);
			if(!slot.getItem().is(Items.TOTEM_OF_UNDYING))
				continue;
				
			// SWAP button 40 is the player's offhand. When it is empty,
			// move the chest item directly into it so the active remote menu
			// never needs to expose the player's inventory slot mapping.
			if(forceRefill || MC.player.getOffhandItem().isEmpty())
			{
				savedScreen.slotClicked(slot, slot.index, 40,
					ContainerInput.SWAP);
				autoTotemRefillRequested = false;
				return;
			}
			
			savedScreen.slotClicked(slot, slot.index, 0,
				ContainerInput.QUICK_MOVE);
			return;
		}
	}
	
	public static int getLinkedEnderChestTotemCount()
	{
		RemoteEnderChestHack self = instance;
		if(self == null || !self.isEnabled() || !self.autoTotem.isChecked()
			|| self.savedScreen == null
			|| !self.isSavedContainerMenuStillActive())
			return 0;
		
		int count = 0;
		for(int i = 0; i < Math.min(27,
			self.savedScreen.getMenu().slots.size()); i++)
			if(self.savedScreen.getMenu().getSlot(i).getItem()
				.is(Items.TOTEM_OF_UNDYING))
				count +=
					self.savedScreen.getMenu().getSlot(i).getItem().getCount();
		return count;
	}
	
	private boolean isEnderChestScreen(BlockPos echest)
	{
		if(!(MC.gui.screen() instanceof AbstractContainerScreen<?> screen))
			return false;
		if(echest == null || MC.level == null)
			return false;
		if(MC.level.getBlockState(echest).getBlock() != Blocks.ENDER_CHEST)
			return false;
		try
		{
			return screen.getMenu().getType() == MenuType.GENERIC_9x3;
		}catch(UnsupportedOperationException e)
		{
			return false;
		}
	}
	
	private boolean isSavedContainerMenuStillActive()
	{
		if(MC.player == null || MC.player.containerMenu == null)
			return false;
		if(savedScreen == null || savedSyncId == -1)
			return false;
		
		return MC.player.containerMenu.containerId == savedSyncId;
	}
	
	private void breakLink(String message)
	{
		resetStuff(false);
		ChatUtils.error(message);
	}
	
	private void resetStuff()
	{
		resetStuff(true);
	}
	
	private void resetStuff(boolean sendClosePacket)
	{
		int syncId = savedSyncId;
		AbstractContainerScreen<?> oldScreen = savedScreen;
		boolean oldScreenVisible = MC.gui.screen() == oldScreen;
		
		guiHidden = false;
		guiWasOpen = false;
		lastToggleKeyState = false;
		potentialEChestPos = null;
		blockedContainerWarningShown = false;
		autoTotemReserve = -1;
		autoTotemRefillRequested = false;
		savedSyncId = -1;
		savedScreen = null;
		
		if(oldScreenVisible)
			MC.gui.setScreen(null);
		
		if(sendClosePacket && !oldScreenVisible && syncId != -1
			&& MC.getConnection() != null)
		{
			MC.getConnection()
				.send(new ServerboundContainerClosePacket(syncId));
		}
	}
	
	public String getToggleGuiKeyName()
	{
		return toggleGuiKey.getValue();
	}
	
	private InputConstants.Key getToggleGuiKey()
	{
		try
		{
			return InputConstants.getKey(toggleGuiKey.getValue());
		}catch(IllegalArgumentException e)
		{
			return null;
		}
	}
	
	private static boolean isValidKey(String keyName)
	{
		try
		{
			return InputConstants.getKey(keyName) != null;
		}catch(IllegalArgumentException e)
		{
			return false;
		}
	}
}
