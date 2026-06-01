/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.chestsearch;

import net.minecraft.core.BlockPos;

public final class SlotHighlighter
{
	public static final SlotHighlighter INSTANCE = new SlotHighlighter();
	
	private int enchantmentHandlerSlot = -1;
	private int chestSearchSlot = -1;
	private String chestSearchDimension = "";
	private BlockPos chestSearchPos;
	private boolean stickyUntilHovered;
	
	private String pendingDimension = "";
	private BlockPos pendingPos;
	private int pendingSlot = -1;
	
	private SlotHighlighter()
	{}
	
	public synchronized void setPending(String dimension, BlockPos pos,
		int slot)
	{
		if(pos == null || slot < 0)
		{
			clearPending();
			return;
		}
		pendingDimension = normalize(dimension);
		pendingPos = pos.immutable();
		pendingSlot = slot;
	}
	
	public synchronized void clearPending()
	{
		pendingDimension = "";
		pendingPos = null;
		pendingSlot = -1;
	}
	
	public synchronized int tryActivate(String dimension, BlockPos pos)
	{
		String dim = normalize(dimension);
		if(pendingPos == null || pendingSlot < 0 || pos == null)
			return -1;
		if(!dim.equals(pendingDimension) || !pos.equals(pendingPos))
			return -1;
		chestSearchSlot = pendingSlot;
		chestSearchDimension = dim;
		chestSearchPos = pos.immutable();
		return chestSearchSlot;
	}
	
	public synchronized int tryActivateForDimension(String dimension)
	{
		String dim = normalize(dimension);
		if(pendingPos == null || pendingSlot < 0)
			return -1;
		if(!dim.equals(pendingDimension))
			return -1;
		chestSearchSlot = pendingSlot;
		chestSearchDimension = dim;
		chestSearchPos = pendingPos.immutable();
		return chestSearchSlot;
	}
	
	public synchronized int getActiveSlot()
	{
		return chestSearchSlot >= 0 ? chestSearchSlot : enchantmentHandlerSlot;
	}
	
	public synchronized int getEnchantmentHandlerSlot()
	{
		return enchantmentHandlerSlot;
	}
	
	public synchronized int getChestSearchSlot()
	{
		return chestSearchSlot;
	}
	
	public synchronized String getChestSearchDimension()
	{
		return chestSearchDimension;
	}
	
	public synchronized BlockPos getChestSearchPos()
	{
		return chestSearchPos;
	}
	
	public synchronized void setActiveSlot(int slot)
	{
		if(slot < 0)
		{
			clearEnchantmentHandlerActive();
			return;
		}
		enchantmentHandlerSlot = slot;
		stickyUntilHovered = false;
	}
	
	public synchronized void setStickySlot(int slot)
	{
		if(slot < 0)
		{
			clearEnchantmentHandlerActive();
			return;
		}
		enchantmentHandlerSlot = slot;
		stickyUntilHovered = true;
	}
	
	public synchronized boolean isStickyActive()
	{
		return enchantmentHandlerSlot >= 0 && stickyUntilHovered;
	}
	
	public synchronized boolean isEnchantmentHandlerActive()
	{
		return enchantmentHandlerSlot >= 0;
	}
	
	public synchronized boolean isChestSearchActive()
	{
		return chestSearchSlot >= 0;
	}
	
	public synchronized void clearEnchantmentHandlerActive()
	{
		enchantmentHandlerSlot = -1;
		stickyUntilHovered = false;
	}
	
	public synchronized void clearChestSearchActive()
	{
		chestSearchSlot = -1;
		chestSearchDimension = "";
		chestSearchPos = null;
	}
	
	public synchronized void forgetChestSearchSelection()
	{
		clearPending();
		clearChestSearchActive();
	}
	
	public synchronized void clearActive()
	{
		clearEnchantmentHandlerActive();
		clearChestSearchActive();
	}
	
	public synchronized void clearAll()
	{
		clearPending();
		clearActive();
	}
	
	private static String normalize(String dimension)
	{
		if(dimension == null)
			return "";
		String dim = dimension.trim().toLowerCase(java.util.Locale.ROOT);
		int colon = dim.indexOf(':');
		if(colon >= 0 && colon < dim.length() - 1)
			return dim.substring(colon + 1);
		return dim;
	}
	
}
