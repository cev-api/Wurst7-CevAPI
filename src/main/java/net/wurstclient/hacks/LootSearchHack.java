/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

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
		// No category -> Navigator-only
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
				{
					WurstClient.MC.player
						.displayClientMessage(
							net.minecraft.network.chat.Component.literal(
								"No loot export found for this server."),
							false);
					sendLootSearchDebug(serverIp, dir);
				}
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
	
	private void sendLootSearchDebug(String serverIp, File dir)
	{
		if(WurstClient.MC == null || WurstClient.MC.player == null)
			return;
		
		String ip = serverIp == null ? "<null>" : serverIp;
		String dirPath = dir == null ? "<null>" : dir.getAbsolutePath();
		WurstClient.MC.player
			.displayClientMessage(net.minecraft.network.chat.Component
				.literal("LootSearch debug: serverIp=" + ip), false);
		WurstClient.MC.player
			.displayClientMessage(net.minecraft.network.chat.Component
				.literal("LootSearch debug: lootDir=" + dirPath), false);
		
		if(dir == null || !dir.exists() || !dir.isDirectory())
			return;
		
		File[] files =
			dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));
		if(files == null || files.length == 0)
		{
			WurstClient.MC.player.displayClientMessage(
				net.minecraft.network.chat.Component.literal(
					"LootSearch debug: no .json files in lootDir"),
				false);
			return;
		}
		
		StringBuilder names = new StringBuilder();
		for(int i = 0; i < files.length && i < 10; i++)
		{
			if(i > 0)
				names.append(", ");
			names.append(files[i].getName());
		}
		if(files.length > 10)
			names.append(" ... (").append(files.length).append(" total)");
		
		WurstClient.MC.player
			.displayClientMessage(net.minecraft.network.chat.Component
				.literal("LootSearch debug: files=" + names), false);
	}
}
