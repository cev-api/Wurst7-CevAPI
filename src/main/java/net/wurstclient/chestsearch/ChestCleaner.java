/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.chestsearch;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.screens.ChestSearchScreen;

import java.util.Iterator;

public class ChestCleaner
{
	private final ChestManager manager;
	private int tickCounter = 0;
	// ticks since we last observed a world/server change; used for grace
	// period after joining/reloading
	private int ticksSinceWorldObserved = 0;
	private String lastServer = null;
	private String lastDimension = null;
	// defaults (will be read from config at runtime)
	
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
				
				Minecraft mc = WurstClient.MC;
				if(mc == null || mc.level == null)
					return;
					
				// update world/server observation ticks and detect join/world
				// changes
				String curServer = null;
				try
				{
					if(mc.getCurrentServer() != null)
						curServer = mc.getCurrentServer().ip;
				}catch(Throwable ignored)
				{}
				String curDimension = null;
				try
				{
					curDimension = mc.level.dimension().identifier().toString();
				}catch(Throwable ignored)
				{}
				// reset grace timer on server/dimension change
				if(lastServer == null || !lastServer.equals(curServer)
					|| lastDimension == null
					|| !lastDimension.equals(curDimension))
				{
					ticksSinceWorldObserved = 0;
					lastServer = curServer;
					lastDimension = curDimension;
				}else
				{
					// increase observed ticks
					ticksSinceWorldObserved += 100; // we check every 100 ticks
													// interval
				}
				
				String serverIp = null;
				try
				{
					if(mc.getCurrentServer() != null)
						serverIp = mc.getCurrentServer().ip;
				}catch(Throwable ignored)
				{}
				String dimension = null;
				try
				{
					dimension = mc.level.dimension().identifier().toString();
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
						// read configured values
						int configuredGrace = manager.getConfig() == null ? 200
							: manager.getConfig().graceTicks;
						int configuredRadius = manager.getConfig() == null ? 64
							: manager.getConfig().scanRadius;
						// Only check entries when player is within scan radius
						// or when within the grace period after joining.
						if(ticksSinceWorldObserved < configuredGrace)
						{
							// during grace period avoid deletions
							continue;
						}
						
						// Determine canonical bounds for the recorded chest
						int minX = Math.min(e.x, e.maxX);
						int minY = Math.min(e.y, e.maxY);
						int minZ = Math.min(e.z, e.maxZ);
						int maxX = Math.max(e.x, e.maxX);
						int maxY = Math.max(e.y, e.maxY);
						int maxZ = Math.max(e.z, e.maxZ);
						
						// compute distance from player to nearest point in
						// bounds
						BlockPos playerPos = mc.player.blockPosition();
						int px = playerPos.getX();
						int py = playerPos.getY();
						int pz = playerPos.getZ();
						int closestX = Math.max(minX, Math.min(px, maxX));
						int closestY = Math.max(minY, Math.min(py, maxY));
						int closestZ = Math.max(minZ, Math.min(pz, maxZ));
						long dx = px - closestX;
						long dy = py - closestY;
						long dz = pz - closestZ;
						long distSq = dx * dx + dy * dy + dz * dz;
						if(distSq > (long)configuredRadius * configuredRadius)
						{
							// player too far to reliably observe this chest
							continue;
						}
						
						boolean anyChunkNotLoaded = false;
						boolean anyContainerPresent = false;
						
						// Iterate all block positions inside the recorded
						// bounds.
						// If any overlapping chunk is not loaded, skip
						// deletion.
						// If any block in the bounds is a container and has a
						// block entity,
						// skip deletion. Only delete when all chunks are loaded
						// and
						// NO matching container+block-entity exists in the
						// bounds.
						outer: for(int bx = minX; bx <= maxX; bx++)
						{
							for(int by = minY; by <= maxY; by++)
							{
								for(int bz = minZ; bz <= maxZ; bz++)
								{
									BlockPos pos = new BlockPos(bx, by, bz);
									boolean chunkLoaded = false;
									try
									{
										@SuppressWarnings("deprecation")
										boolean tmp = mc.level.hasChunkAt(pos);
										chunkLoaded = tmp;
									}catch(Throwable ignored)
									{
										try
										{
											Object cm =
												mc.level.getChunkSource();
											java.lang.reflect.Method m =
												cm.getClass().getMethod(
													"isChunkLoaded", int.class,
													int.class);
											chunkLoaded = Boolean.TRUE.equals(
												m.invoke(cm, pos.getX() >> 4,
													pos.getZ() >> 4));
										}catch(Throwable ignored2)
										{}
									}
									if(!chunkLoaded)
									{
										anyChunkNotLoaded = true;
										break outer; // abort: wait until all
														// chunks loaded
									}
									
									var state = mc.level.getBlockState(pos);
									boolean containerBlock =
										state != null && (state
											.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
											|| state
												.getBlock() instanceof net.minecraft.world.level.block.BarrelBlock
											|| state
												.getBlock() instanceof net.minecraft.world.level.block.ShulkerBoxBlock
											|| state
												.getBlock() instanceof net.minecraft.world.level.block.DecoratedPotBlock);
									boolean hasBe =
										mc.level.getBlockEntity(pos) != null;
									if(containerBlock && hasBe)
									{
										anyContainerPresent = true;
										break outer; // container still present,
														// do not delete
									}
								}
							}
						}
						
						if(anyChunkNotLoaded)
							continue; // wait until all relevant chunks are
										// loaded
							
						if(!anyContainerPresent)
						{
							// No container found in the recorded bounds and all
							// chunks covering the bounds are loaded -> safe to
							// delete
							ChestSearchScreen.clearDecorations(e.dimension,
								e.getMinPos());
							manager.removeChest(e.serverIp, e.dimension, minX,
								minY, minZ);
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
