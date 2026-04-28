/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixinterface;

import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ServerData;

public interface IMultiplayerMultiSelect
{
	public boolean wurst$handleServerClick(
		ServerSelectionList.OnlineServerEntry entry, MouseButtonEvent event,
		boolean doubleClick);
	
	public boolean wurst$isMultiSelected(ServerData serverData);
	
	public int wurst$getMultiSelectedCount();
	
	public void wurst$clearMultiSelection();
	
	public boolean wurst$bulkDeleteSelected();
}
