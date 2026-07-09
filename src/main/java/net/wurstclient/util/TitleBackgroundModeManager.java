/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.resources.Identifier;
import net.wurstclient.WurstClient;

public enum TitleBackgroundModeManager
{
	;
	
	private static final Path MODE_FILE = WurstClient.INSTANCE.getWurstFolder()
		.resolve("title-background-mode.txt");
	private static Mode currentMode = loadModeFromDisk();
	
	public static void advanceForStartup()
	{
		currentMode = currentMode.next();
		saveModeToDisk();
	}
	
	public static void advanceForEnableToggle()
	{
		currentMode = currentMode.next();
		saveModeToDisk();
	}
	
	public static Mode getCurrentMode()
	{
		return currentMode;
	}
	
	private static Mode loadModeFromDisk()
	{
		if(!Files.isRegularFile(MODE_FILE))
			return Mode.END;
		
		try
		{
			String value =
				Files.readString(MODE_FILE, StandardCharsets.UTF_8).trim();
			return Mode.valueOf(value);
		}catch(Exception e)
		{
			return Mode.END;
		}
	}
	
	private static void saveModeToDisk()
	{
		try
		{
			Files.writeString(MODE_FILE, currentMode.name(),
				StandardCharsets.UTF_8);
		}catch(IOException e)
		{
			throw new RuntimeException("Failed to save title background mode.",
				e);
		}
	}
	
	public enum Mode
	{
		OVERWORLD("wurst:textures/shader_blocks_overworld.png", 0),
		NETHER("wurst:textures/shader_blocks_nether.png", 1),
		END("wurst:textures/shader_blocks_end.png", 2);
		
		private final Identifier atlasId;
		private final int shaderIndex;
		
		private Mode(String atlasId, int shaderIndex)
		{
			this.atlasId = Identifier.parse(atlasId);
			this.shaderIndex = shaderIndex;
		}
		
		public Identifier getAtlasId()
		{
			return atlasId;
		}
		
		public int getShaderIndex()
		{
			return shaderIndex;
		}
		
		public Mode next()
		{
			return values()[(ordinal() + 1) % values().length];
		}
	}
}
