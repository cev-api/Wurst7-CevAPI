/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.chestsearch;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.screens.ChestSearchScreen;

import java.util.Iterator;

public class ChestCleaner
{
	private final ChestManager manager;
	private int tickCounter = 0;
	
	public ChestCleaner()
	{
		// use default manager which reads config for db path
		this.manager = new ChestManager();
	}
	
	public void register()
	{
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try
			{
				tickCounter++;
				if(tickCounter < 100)
					return; // check every ~5s (20 ticks/s)
				tickCounter = 0;
				
				MinecraftClient mc = WurstClient.MC;
				if(mc == null || mc.world == null)
					return;
				
				String serverIp = null;
				try
				{
					if(mc.getCurrentServerEntry() != null)
						serverIp = mc.getCurrentServerEntry().address;
				}catch(Throwable ignored)
				{}
				String dimension = null;
				try
				{
					dimension = mc.world.getRegistryKey().getValue().toString();
				}catch(Throwable ignored)
				{}
				
				for(Iterator<net.wurstclient.chestsearch.ChestEntry> it =
					manager.all().iterator(); it.hasNext();)
				{
					net.wurstclient.chestsearch.ChestEntry e = it.next();
					if(e.serverIp != null && serverIp != null
						&& !e.serverIp.equals(serverIp))
						continue;
					if(e.dimension != null && dimension != null
						&& !e.dimension.equals(dimension))
						continue;
					try
					{
						BlockPos pos = e.getMinPos();
						boolean chunkLoaded = false;
						try
						{
							@SuppressWarnings("deprecation")
							boolean tmp = mc.world.isChunkLoaded(pos);
							chunkLoaded = tmp;
						}catch(Throwable ignored)
						{
							try
							{
								Object cm = mc.world.getChunkManager();
								java.lang.reflect.Method m =
									cm.getClass().getMethod("isChunkLoaded",
										int.class, int.class);
								chunkLoaded = Boolean.TRUE.equals(m.invoke(cm,
									pos.getX() >> 4, pos.getZ() >> 4));
							}catch(Throwable ignored2)
							{}
						}
						if(!chunkLoaded)
							continue; // don't delete when chunk is unloaded
						var state = mc.world.getBlockState(pos);
						boolean containerBlock = state != null && (state
							.getBlock() instanceof net.minecraft.block.ChestBlock
							|| state
								.getBlock() instanceof net.minecraft.block.BarrelBlock
							|| state
								.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock
							|| state
								.getBlock() instanceof net.minecraft.block.DecoratedPotBlock);
						boolean hasBe = mc.world.getBlockEntity(pos) != null;
						if(!hasBe || !containerBlock)
						{
							ChestSearchScreen.clearDecorations(e.dimension,
								pos);
							manager.removeChest(e.serverIp, e.dimension,
								pos.getX(), pos.getY(), pos.getZ());
						}
					}catch(Throwable ignored)
					{}
				}
			}catch(Throwable t)
			{
				t.printStackTrace();
			}
		});
	}
}
