/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.seedmapper;

import java.util.Optional;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * Detects whether the SeedMapper mod is present at runtime and exposes the
 * vendor-provided option data when possible.
 */
public enum VendorSeedMapperLoader
{
	;
	
	private static final String MOD_ID = "seedmapper";
	
	private static boolean checked;
	private static boolean available;
	private static String version;
	private static SeedMapperData data = SeedMapperData.createFallback();
	
	private static void ensureChecked()
	{
		if(checked)
			return;
		
		checked = true;
		FabricLoader loader = FabricLoader.getInstance();
		if(loader.isModLoaded(MOD_ID))
		{
			// Do not load vendor classes during early initialization. Loading
			// can trigger static initializers in the vendor mod which may fail
			// when Wurst initializes very early and cause hard crashes.
			// Detect presence and report fallback data instead. A later
			// call to forceReload() can be used to attempt a full load.
			available = true;
			version =
				loader.getModContainer(MOD_ID).map(ModContainer::getMetadata)
					.map(meta -> meta.getVersion().getFriendlyString())
					.orElse("unknown");
			data = SeedMapperData.createFallback();
		}else
		{
			available = false;
			version = null;
			data = SeedMapperData.createFallback();
		}
	}
	
	public static boolean isSeedMapperPresent()
	{
		ensureChecked();
		return available;
	}
	
	public static SeedMapperData getData()
	{
		ensureChecked();
		return data;
	}
	
	public static Optional<String> getDetectedVersion()
	{
		ensureChecked();
		return Optional.ofNullable(version);
	}
	
	public static void forceReload()
	{
		checked = false;
		ensureChecked();
	}
}
