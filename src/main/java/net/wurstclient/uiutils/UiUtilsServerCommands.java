/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;

/**
 * A separately decoded server packet tree, never the dispatcher merged with
 * local commands.
 */
public interface UiUtilsServerCommands
{
	RootCommandNode<ClientSuggestionProvider> wurst$getServerCommandRoot();
	
	static CommandDispatcher<ClientSuggestionProvider> dispatcher(
		ClientPacketListener connection)
	{
		if(!(connection instanceof UiUtilsServerCommands commands))
			return null;
		var root = commands.wurst$getServerCommandRoot();
		return root == null ? null : new CommandDispatcher<>(root);
	}
}
