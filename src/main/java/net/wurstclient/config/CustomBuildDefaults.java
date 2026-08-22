/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Installs profile-supplied defaults only when a config file does not exist.
 */
public final class CustomBuildDefaults
{
	private static final List<String> FILES = List.of("settings.json",
		"enabled-hacks.json", "favourites.json", "keybinds.json",
		"windows.json", "preferences.json", "toomanyhax.json");
	
	private CustomBuildDefaults()
	{}
	
	public static void install(Path configFolder)
	{
		if(!BuildConfig.CUSTOM_BUILD)
			return;
		
		for(String fileName : FILES)
		{
			Path target = configFolder.resolve(fileName);
			if(Files.exists(target))
				continue;
			
			String resource = "/wurst/custom-defaults/" + fileName;
			try(InputStream input =
				CustomBuildDefaults.class.getResourceAsStream(resource))
			{
				if(input != null)
					Files.copy(input, target);
			}catch(IOException e)
			{
				System.err
					.println("Couldn't install custom default " + fileName);
				e.printStackTrace();
			}
		}
	}
}
