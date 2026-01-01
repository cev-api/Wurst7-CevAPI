/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient;

import net.fabricmc.api.ModInitializer;
import net.wurstclient.chestsearch.ChestCleaner;
import net.wurstclient.chestsearch.TargetHighlighter;
import net.wurstclient.events.RenderListener;

public final class WurstInitializer implements ModInitializer
{
	private static boolean initialized;
	
	@Override
	public void onInitialize()
	{
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.
		
		if(initialized)
			throw new RuntimeException(
				"WurstInitializer.onInitialize() ran twice!");
		
		WurstClient.INSTANCE.initialize();
		// Register chest cleaner
		try
		{
			new ChestCleaner().register();
		}catch(Throwable ignored)
		{}
		// Register targeted highlighter
		try
		{
			WurstClient.INSTANCE.getEventManager().add(RenderListener.class,
				TargetHighlighter.INSTANCE);
		}catch(Throwable ignored)
		{}
		initialized = true;
	}
}
