/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.File;
import java.nio.file.Path;
import net.minecraft.network.chat.Component;
import net.wurstclient.Category;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;
import net.wurstclient.lootsearch.LootChestManager;
import net.wurstclient.lootsearch.LootSearchUtil;
import net.wurstclient.settings.FileSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public final class LootSearchHack extends Hack
{
	private static final int WAYPOINT_TIME_MINUTES_INFINITE = 241;
	
	private final FileSetting lootJsonPicker =
		new FileSetting("Loot JSON", "", "lootprobe", folder -> {
			try
			{
				java.nio.file.Files.createDirectories(folder);
				Path placeholder = folder.resolve("lootprobe-placeholder.json");
				if(!java.nio.file.Files.exists(placeholder))
					java.nio.file.Files.writeString(placeholder, "{}\n");
			}catch(java.io.IOException e)
			{
				throw new RuntimeException(e);
			}
		});
	
	private final TextFieldSetting literalJsonPath = new TextFieldSetting(
		"JSON Path",
		"Literal path to a loot export JSON file. If set, this path is used instead of auto-detection.",
		"");
	
	private final SliderSetting waypointTimeMinutes = new SliderSetting(
		"Waypoint time (min)", 1, 1, WAYPOINT_TIME_MINUTES_INFINITE, 1,
		ValueDisplay.INTEGER.withSuffix(" min")
			.withLabel(WAYPOINT_TIME_MINUTES_INFINITE, "Infinite"));
	
	public LootSearchHack()
	{
		super("LootSearch");
		setCategory(Category.ITEMS);
		addSetting(lootJsonPicker);
		addSetting(literalJsonPath);
		addSetting(waypointTimeMinutes);
	}
	
	public int getWaypointTimeMs()
	{
		int minutes = waypointTimeMinutes.getValueI();
		if(minutes >= WAYPOINT_TIME_MINUTES_INFINITE)
			return -1;
		return minutes * 60 * 1000;
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
			
			File f = resolveLootFile(serverIp);
			if(f == null)
			{
				File dir = LootSearchUtil.getSeedmapperLootDir();
				if(WurstClient.MC != null && WurstClient.MC.player != null)
				{
					WurstClient.MC.player
						.displayClientMessage(
							Component.literal(
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
	
	private File resolveLootFile(String serverIp)
	{
		// Highest priority: literal path entered by user.
		File explicit = resolveLiteralJsonPath();
		if(explicit != null)
			return explicit;
		
		// Then use selected file from wurst/lootprobe.
		File selected = LootSearchUtil
			.normalizeJsonFile(lootJsonPicker.getSelectedFile().toFile());
		if(selected != null && !selected.getName()
			.equalsIgnoreCase("lootprobe-placeholder.json"))
			return selected;
		
		// Fallback to existing server-based SeedMapper detection.
		return LootSearchUtil.findFileForServer(serverIp);
	}
	
	private File resolveLiteralJsonPath()
	{
		String raw = literalJsonPath.getValue();
		if(raw == null || raw.isBlank())
			return null;
		
		File file = new File(raw.trim());
		if(!file.isAbsolute() && WurstClient.MC != null
			&& WurstClient.MC.gameDirectory != null)
		{
			file = new File(WurstClient.MC.gameDirectory, raw.trim());
		}
		
		File normalized = LootSearchUtil.normalizeJsonFile(file);
		if(normalized == null)
		{
			if(WurstClient.MC != null && WurstClient.MC.player != null)
				WurstClient.MC.player.displayClientMessage(Component.literal(
					"LootSearch: JSON Path does not point to an existing .json file."),
					false);
		}
		return normalized;
	}
	
	private void sendLootSearchDebug(String serverIp, File dir)
	{
		if(WurstClient.MC == null || WurstClient.MC.player == null)
			return;
		
		String ip = serverIp == null ? "<null>" : serverIp;
		String dirPath = dir == null ? "<null>" : dir.getAbsolutePath();
		WurstClient.MC.player.displayClientMessage(
			Component.literal("LootSearch debug: serverIp=" + ip), false);
		WurstClient.MC.player.displayClientMessage(
			Component.literal("LootSearch debug: lootDir=" + dirPath), false);
		
		if(dir == null || !dir.exists() || !dir.isDirectory())
			return;
		
		File[] files =
			dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));
		if(files == null || files.length == 0)
		{
			WurstClient.MC.player.displayClientMessage(Component
				.literal("LootSearch debug: no .json files in lootDir"), false);
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
		
		WurstClient.MC.player.displayClientMessage(
			Component.literal("LootSearch debug: files=" + names), false);
	}
}
