/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.addons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;

public final class AddonManager
{
	private static final ArrayList<WurstAddon> addons = new ArrayList<>();
	private static boolean initialized;
	
	private AddonManager()
	{}
	
	public static void init()
	{
		if(initialized)
			return;
		
		initialized = true;
		
		loadAddons();
		initializeAddons();
		
		if(!addons.isEmpty())
			System.out.println("Loaded Wurst addons: " + addons.stream()
				.map(a -> a.name).collect(Collectors.joining(", ")));
	}
	
	private static void loadAddons()
	{
		for(EntrypointContainer<WurstAddon> entrypoint : FabricLoader
			.getInstance().getEntrypointContainers("wurst", WurstAddon.class))
		{
			ModMetadata metadata = entrypoint.getProvider().getMetadata();
			WurstAddon addon;
			try
			{
				addon = entrypoint.getEntrypoint();
				
			}catch(Throwable t)
			{
				throw new RuntimeException(
					"Exception while preparing Wurst addon \""
						+ metadata.getName() + "\".",
					t);
			}
			
			addon.id = metadata.getId();
			addon.name = metadata.getName();
			addon.version = metadata.getVersion().getFriendlyString();
			
			if(metadata.getAuthors().isEmpty())
				throw new RuntimeException("Wurst addon \"" + addon.name
					+ "\" requires at least one author in fabric.mod.json.");
			
			addon.authors = new String[metadata.getAuthors().size()];
			int i = 0;
			for(Person author : metadata.getAuthors())
				addon.authors[i++] = author.getName();
			
			addons.add(addon);
		}
	}
	
	private static void initializeAddons()
	{
		for(WurstAddon addon : addons)
			try
			{
				addon.onInitialize();
				
			}catch(Throwable t)
			{
				throw new RuntimeException(
					"Exception during Wurst addon init \"" + addon.name + "\".",
					t);
			}
	}
	
	public static List<WurstAddon> getAddons()
	{
		return Collections.unmodifiableList(addons);
	}
}
