/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Scanner dashboard preferences, persisted with the same UI-Utils JSON format.
 */
public final class UiUtilsSettings
{
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH =
		FabricLoader.getInstance().getConfigDir().resolve("ui-utils.json");
	private static Data data = new Data();
	
	private UiUtilsSettings()
	{}
	
	public static Data get()
	{
		return data;
	}
	
	public static void load()
	{
		if(!Files.exists(PATH))
		{
			save();
			return;
		}
		try(Reader reader = Files.newBufferedReader(PATH))
		{
			Data loaded = GSON.fromJson(reader, Data.class);
			data = loaded == null ? new Data() : loaded;
		}catch(Exception e)
		{
			UiUtils.LOGGER.warn("Failed to load ServerIntel UI settings", e);
			data = new Data();
		}
	}
	
	public static void save()
	{
		try
		{
			Files.createDirectories(PATH.getParent());
			try(Writer writer = Files.newBufferedWriter(PATH))
			{
				GSON.toJson(data, writer);
			}
		}catch(Exception e)
		{
			UiUtils.LOGGER.warn("Failed to save ServerIntel UI settings", e);
		}
	}
	
	public static final class Data
	{
		public int uiButtonColor = 0x4A90E2;
		public int uiButtonTextColor = 0xFFFFFF;
		public String commandScannerMode = "PACKET_PROBING";
		public boolean commandScannerDebugProbe = false;
		public boolean commandScannerRunFoundCommands = false;
		public String commandScannerDontSendFilter = "";
		public String commandScannerPacketCommands = "";
	}
}
