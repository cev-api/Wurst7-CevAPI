/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * All normal-screen interaction is concentrated here; it never sends packets
 * directly and never creates/drops items.
 */
public final class ContainerInteractionController
{
	private final Minecraft mc;
	
	public ContainerInteractionController(Minecraft mc)
	{
		this.mc = mc;
	}
	
	public AbstractContainerScreen<?> getSupportedScreen()
	{
		AbstractContainerScreen<?> screen = getContainerScreen();
		if(screen == null || !(screen.getMenu() instanceof ChestMenu
			|| screen.getMenu() instanceof ShulkerBoxMenu))
			return null;
		return screen;
	}
	
	public boolean hasCarriedStack()
	{
		AbstractContainerScreen<?> screen = getContainerScreen();
		return screen != null && !screen.getMenu().getCarried().isEmpty();
	}
	
	public void quickMove(Slot slot)
	{
		AbstractContainerScreen<?> screen = getSupportedScreen();
		if(screen != null && slot != null)
			screen.slotClicked(slot, slot.index, 0, ClickType.QUICK_MOVE);
	}
	
	/**
	 * Moves through the normal container quick-move action after the caller has
	 * verified that a compatible slot exists on the other side. Sending a chain
	 * of PICKUP actions in one client tick reuses the same menu revision, which
	 * modern servers reject after the first click. QUICK_MOVE is one normal,
	 * server-confirmed menu action and is unambiguous for chest and shulker
	 * menus: container-to-player when the source is a container slot and
	 * player-to-container in the reverse direction.
	 */
	public boolean moveAsMuchAsPossible(Slot from, Slot to)
	{
		AbstractContainerScreen<?> screen = getSupportedScreen();
		if(screen == null || from == null || to == null
			|| from.getItem().isEmpty() || !to.mayPlace(from.getItem())
			|| to.getMaxStackSize(from.getItem()) <= to.getItem().getCount())
			return false;
		screen.slotClicked(from, from.index, 0, ClickType.QUICK_MOVE);
		return true;
	}
	
	/**
	 * Performs one ordinary left-click for a controller-managed safe transfer.
	 */
	public boolean pickup(Slot slot)
	{
		AbstractContainerScreen<?> screen = getSupportedScreen();
		if(screen == null || slot == null)
			return false;
		screen.slotClicked(slot, slot.index, 0, ClickType.PICKUP);
		return true;
	}
	
	public boolean isPlayerSlot(Slot slot)
	{
		return slot != null && mc.player != null
			&& slot.container == mc.player.getInventory();
	}
	
	public void close()
	{
		if(mc.player != null && getContainerScreen() != null)
		{
			mc.player.closeContainer();
			// closeContainer() sends the normal close packet. Clearing the
			// local
			// screen as well prevents a server that delays its close response
			// from
			// trapping the sorter in the close-wait state.
			mc.gui.setScreen(null);
		}
	}
	
	public ItemStack getCarried()
	{
		AbstractContainerScreen<?> screen = getContainerScreen();
		return screen == null ? ItemStack.EMPTY : screen.getMenu().getCarried();
	}
	
	private AbstractContainerScreen<?> getContainerScreen()
	{
		return mc.gui.screen() instanceof AbstractContainerScreen<?> screen
			? screen : null;
	}
	
	public int getRevision()
	{
		AbstractContainerScreen<?> screen = getSupportedScreen();
		return screen == null ? -1 : screen.getMenu().getStateId();
	}
}
