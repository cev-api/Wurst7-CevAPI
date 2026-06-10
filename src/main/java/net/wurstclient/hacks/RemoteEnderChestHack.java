/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"remote echest", "remote ender chest", "ender chest",
	"portable echest", "portable ender chest"})
public final class RemoteEnderChestHack extends Hack implements UpdateListener
{
	private static RemoteEnderChestHack instance;
	
	private boolean guiHidden;
	private boolean guiWasOpen;
	private boolean lastKeyState;
	private AbstractContainerScreen<?> savedScreen;
	private int savedSyncId = -1;
	private Level lastWorld;
	private BlockPos potentialEChestPos;
	
	private final CheckboxSetting swapInventoryKey =
		new CheckboxSetting("Swap inventory key",
			"When active, pressing the inventory key (E) will open the linked\n"
				+ "ender chest instead of the regular inventory.",
			false);
	
	public RemoteEnderChestHack()
	{
		super("RemoteEChest");
		instance = this;
		setCategory(Category.OTHER);
		addSetting(swapInventoryKey);
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
			self.MC.setScreen(null);
			self.guiHidden = true;
		}
	}
	
	public static void toggleLinkedGui()
	{
		RemoteEnderChestHack self = instance;
		if(self == null || !self.isEnabled() || self.savedScreen == null)
			return;
		
		// Prevent double-toggle when both a mixin and onUpdate() see Left Alt.
		self.lastKeyState = true;
		
		if(!self.isSavedContainerMenuStillActive())
		{
			self.breakLink("Ender chest handler invalid. EChest link broken.");
			return;
		}
		
		if(self.guiHidden)
		{
			self.MC.setScreen(self.savedScreen);
			self.guiHidden = false;
		}else
		{
			self.MC.setScreen(null);
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
		MC.setScreen(savedScreen);
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
			self.MC.setScreen(new InventoryScreen(self.MC.player));
		}
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
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
		resetStuff();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
		{
			resetStuff(false);
			return;
		}
		
		// Swap inventory key: when the player opens their inventory (E key),
		// immediately replace it with the linked ender chest GUI.
		if(swapInventoryKey.isChecked() && savedScreen != null
			&& isSavedContainerMenuStillActive() && guiHidden
			&& MC.screen instanceof InventoryScreen invScreen
			&& invScreen != savedScreen)
		{
			MC.setScreen(savedScreen);
			guiHidden = false;
			return;
		}
		
		if(MC.hitResult instanceof BlockHitResult bhr)
		{
			potentialEChestPos = bhr.getBlockPos();
			
			if(MC.screen == null
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
		// the saved link. Clear it immediately so Alt cannot reopen a stale
		// ghost GUI later.
		if(savedScreen != null
			&& MC.screen instanceof AbstractContainerScreen<?> screen
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
		
		if(isEnderChestScreen(potentialEChestPos) && savedScreen == null
			&& !guiWasOpen && !guiHidden)
		{
			savedScreen = (AbstractContainerScreen<?>)MC.screen;
			
			savedSyncId = savedScreen.getMenu().containerId;
			
			MC.setScreen(null);
			guiHidden = true;
			guiWasOpen = true;
			lastWorld = MC.level;
			ChatUtils.message(
				"EChest link created! Press Left Alt to toggle the GUI.");
		}
		
		long window = MC.getWindow().handle();
		boolean keyDown = org.lwjgl.glfw.GLFW.glfwGetKey(window,
			org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT) == 1;
		boolean keyJustPressed = keyDown && !lastKeyState;
		lastKeyState = keyDown;
		
		if(keyJustPressed && savedScreen != null)
		{
			if(!isSavedContainerMenuStillActive())
			{
				breakLink("Ender chest handler invalid. EChest link broken.");
				return;
			}
			
			if(guiHidden)
			{
				MC.setScreen(savedScreen);
				guiHidden = false;
			}else
			{
				MC.setScreen(null);
				guiHidden = true;
			}
		}
		
		if(savedScreen != null && MC.screen == null && !guiHidden && guiWasOpen)
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
	
	private boolean isEnderChestScreen(BlockPos echest)
	{
		if(!(MC.screen instanceof AbstractContainerScreen<?> screen))
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
		boolean oldScreenVisible = MC.screen == oldScreen;
		
		guiHidden = false;
		guiWasOpen = false;
		lastKeyState = false;
		potentialEChestPos = null;
		savedSyncId = -1;
		savedScreen = null;
		
		if(oldScreenVisible)
			MC.setScreen(null);
		
		if(sendClosePacket && !oldScreenVisible && syncId != -1
			&& MC.getConnection() != null)
		{
			MC.getConnection()
				.send(new ServerboundContainerClosePacket(syncId));
		}
	}
}
