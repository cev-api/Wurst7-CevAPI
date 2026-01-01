/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.WurstClient;
import net.wurstclient.lootsearch.LootChestManager;
import net.wurstclient.lootsearch.LootSearchUtil;

import java.io.File;

public final class LootSearchHack extends Hack
{
	public LootSearchHack()
	{
		super("LootSearch");
		setCategory(Category.ITEMS);
	}
	
	@Override
	protected void onEnable()
	{
		try
		{
			String serverIp = null;
			try
			{
				if(WurstClient.MC != null
					&& WurstClient.MC.getCurrentServer() != null)
					serverIp = WurstClient.MC.getCurrentServer().ip;
			}catch(Throwable ignored)
			{}
			
			File dir = LootSearchUtil.getSeedmapperLootDir();
			if(dir == null || !dir.exists() || !dir.isDirectory())
			{
				if(WurstClient.MC != null && WurstClient.MC.player != null)
					WurstClient.MC.player.displayClientMessage(
						net.minecraft.network.chat.Component.literal(
							"SeedMapper loot folder not found. Run SeedMapper and export loot first."),
						false);
				setEnabled(false);
				return;
			}
			
			File f = LootSearchUtil.findFileForServer(serverIp);
			if(f == null)
			{
				if(WurstClient.MC != null && WurstClient.MC.player != null)
					WurstClient.MC.player
						.displayClientMessage(
							net.minecraft.network.chat.Component.literal(
								"No loot export found for this server."),
							false);
				setEnabled(false);
				return;
			}
			
			LootChestManager mgr = new LootChestManager(f, serverIp);
			WurstClient.MC.setScreen(
				new net.wurstclient.clickgui.screens.ChestSearchScreen(
					WurstClient.MC.screen, mgr, Boolean.TRUE));
		}catch(Throwable t)
		{
			t.printStackTrace();
		}
		setEnabled(false);
	}
}
