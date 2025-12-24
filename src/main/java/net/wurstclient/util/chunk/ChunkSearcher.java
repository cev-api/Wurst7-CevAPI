/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.chunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.MissingPaletteEntryException;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.dimension.DimensionType;
import net.wurstclient.WurstClient;
import net.wurstclient.util.MinPriorityThreadFactory;

/**
 * Searches the given {@link ChunkAccess} for blocks matching the given query.
 */
public final class ChunkSearcher
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final java.util.concurrent.ExecutorService BACKGROUND_THREAD_POOL =
		MinPriorityThreadFactory.newFixedThreadPool();
	
	private final BiPredicate<BlockPos, BlockState> query;
	private final ChunkAccess chunk;
	private final DimensionType dimension;
	
	private CompletableFuture<ArrayList<Result>> future;
	private boolean interrupted;
	private volatile ArrayList<Result> results;
	private ArrayList<BlockUpdate> pendingUpdates;
	
	public ChunkSearcher(BiPredicate<BlockPos, BlockState> query,
		ChunkAccess chunk, DimensionType dimension)
	{
		this.query = query;
		this.chunk = chunk;
		this.dimension = dimension;
	}
	
	public void start()
	{
		if(future != null || interrupted)
			throw new IllegalStateException();
		
		ChunkSnapshot snapshot = ChunkSnapshot.capture(chunk);
		if(snapshot == null)
		{
			future = CompletableFuture.completedFuture(new ArrayList<>());
			return;
		}
		
		future = CompletableFuture.supplyAsync(() -> searchNow(snapshot),
			BACKGROUND_THREAD_POOL);
	}
	
	private ArrayList<Result> searchNow(ChunkSnapshot snapshot)
	{
		ArrayList<Result> results = new ArrayList<>();
		boolean reportedMissingEntry = false;
		ChunkPos chunkPos = snapshot.chunkPos();
		
		int minX = snapshot.minX();
		int minY = snapshot.minY();
		int minZ = snapshot.minZ();
		int maxX = snapshot.maxX();
		int maxY = snapshot.maxY();
		int maxZ = snapshot.maxZ();
		
		BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
		
		for(int x = minX; x <= maxX; x++)
			for(int y = minY; y <= maxY; y++)
				for(int z = minZ; z <= maxZ; z++)
				{
					if(interrupted)
						return results;
					
					mutablePos.set(x, y, z);
					BlockState state;
					try
					{
						state = snapshot.getBlockState(mutablePos);
						
					}catch(MissingPaletteEntryException e)
					{
						if(!reportedMissingEntry)
						{
							reportedMissingEntry = true;
							LOGGER.warn(
								"ChunkSearcher skipped palette gap in chunk {}: {}",
								chunkPos, e.getMessage());
						}
						continue;
					}
					if(!query.test(mutablePos, state))
						continue;
					
					results.add(new Result(mutablePos.immutable(), state));
				}
			
		return results;
	}
	
	public void cancel()
	{
		if(future == null || future.isDone())
			return;
		
		interrupted = true;
		future.cancel(false);
	}
	
	public boolean isInterrupted()
	{
		return interrupted;
	}
	
	public ChunkPos getPos()
	{
		return chunk.getPos();
	}
	
	public DimensionType getDimension()
	{
		return dimension;
	}
	
	public Stream<Result> getMatches()
	{
		if(future == null || future.isCancelled())
			return Stream.empty();
		
		ensureResultsLoaded();
		if(results == null)
			return Stream.empty();
		
		ArrayList<Result> snapshot;
		synchronized(this)
		{
			snapshot = new ArrayList<>(results);
		}
		return snapshot.stream();
	}
	
	public List<Result> getMatchesList()
	{
		if(future == null || future.isCancelled())
			return List.of();
		
		ensureResultsLoaded();
		if(results == null)
			return List.of();
		
		synchronized(this)
		{
			return Collections.unmodifiableList(new ArrayList<>(results));
		}
	}
	
	public Stream<Result> getReadyMatches()
	{
		if(!hasResultsReady())
			return Stream.empty();
		
		ArrayList<Result> snapshot;
		synchronized(this)
		{
			snapshot = new ArrayList<>(results);
		}
		return snapshot.stream();
	}
	
	public List<Result> getReadyMatchesList()
	{
		if(!hasResultsReady())
			return List.of();
		
		synchronized(this)
		{
			return Collections.unmodifiableList(new ArrayList<>(results));
		}
	}
	
	public boolean isDone()
	{
		return future != null && future.isDone();
	}
	
	public boolean hasResultsReady()
	{
		if(future == null || future.isCancelled())
			return false;
		if(results != null)
			return true;
		if(!future.isDone())
			return false;
		
		ensureResultsLoaded();
		return results != null;
	}
	
	public boolean applyBlockUpdates(List<BlockUpdate> updates)
	{
		if(updates == null || updates.isEmpty())
			return false;
		
		synchronized(this)
		{
			if(future == null || future.isCancelled())
				return false;
			
			if(!future.isDone())
			{
				if(pendingUpdates == null)
					pendingUpdates = new ArrayList<>();
				pendingUpdates.addAll(updates);
				return false;
			}
		}
		
		ensureResultsLoaded();
		synchronized(this)
		{
			return applyUpdates(results, updates);
		}
	}
	
	private void ensureResultsLoaded()
	{
		if(results != null || future == null || future.isCancelled())
			return;
		
		if(!future.isDone())
			return;
		
		ArrayList<Result> computed = future.join();
		
		synchronized(this)
		{
			if(results == null)
			{
				results = computed;
				if(pendingUpdates != null && !pendingUpdates.isEmpty())
				{
					applyUpdates(results, pendingUpdates);
					pendingUpdates.clear();
					pendingUpdates = null;
				}else
				{
					pendingUpdates = null;
				}
			}
		}
	}
	
	private boolean applyUpdates(ArrayList<Result> target,
		List<BlockUpdate> updates)
	{
		boolean changed = false;
		
		for(BlockUpdate update : updates)
		{
			BlockPos pos = update.pos();
			BlockState state = update.state();
			boolean matches = query.test(pos, state);
			int index = indexOf(target, pos);
			
			if(matches)
			{
				Result newResult = new Result(pos, state);
				if(index < 0)
				{
					target.add(newResult);
					changed = true;
				}else if(target.get(index).state() != state)
				{
					target.set(index, newResult);
					changed = true;
				}
			}else if(index >= 0)
			{
				target.remove(index);
				changed = true;
			}
		}
		
		return changed;
	}
	
	private int indexOf(ArrayList<Result> list, BlockPos pos)
	{
		for(int i = 0; i < list.size(); i++)
			if(list.get(i).pos().equals(pos))
				return i;
			
		return -1;
	}
	
	public record Result(BlockPos pos, BlockState state)
	{}
	
	public record BlockUpdate(BlockPos pos, BlockState state)
	{}
	
	private record ChunkSnapshot(ChunkPos chunkPos, int minX, int minY,
		int minZ, int maxX, int maxY, int maxZ, int minSectionCoord,
		PalettedContainer<BlockState>[] sections)
	{
		static ChunkSnapshot capture(ChunkAccess chunk)
		{
			if(WurstClient.MC == null || WurstClient.MC.level == null)
				return null;
			
			ChunkPos chunkPos = chunk.getPos();
			if(!WurstClient.MC.level.hasChunk(chunkPos.x, chunkPos.z))
				return null;
			
			LevelChunkSection[] chunkSections = chunk.getSections();
			@SuppressWarnings("unchecked")
			PalettedContainer<BlockState>[] copies =
				new PalettedContainer[chunkSections.length];
			
			for(int i = 0; i < chunkSections.length; i++)
			{
				LevelChunkSection section = chunkSections[i];
				if(section == null || section.hasOnlyAir())
					continue;
				
				copies[i] = section.getStates().copy();
			}
			
			int minX = chunkPos.getMinBlockX();
			int minY = chunk.getMinBuildHeight();
			int minZ = chunkPos.getMinBlockZ();
			int maxX = chunkPos.getMaxBlockX();
			int maxY = ChunkUtils.getHighestNonEmptySectionYOffset(chunk) + 16;
			int maxZ = chunkPos.getMaxBlockZ();
			int minSectionCoord = SectionPos.blockToSectionCoord(minY);
			
			return new ChunkSnapshot(chunkPos, minX, minY, minZ, maxX, maxY,
				maxZ, minSectionCoord, copies);
		}
		
		BlockState getBlockState(BlockPos pos)
		{
			int ySection = SectionPos.blockToSectionCoord(pos.getY());
			int sectionIndex = ySection - minSectionCoord;
			
			PalettedContainer<BlockState> container = null;
			
			if(sectionIndex >= 0 && sectionIndex < sections.length)
				container = sections[sectionIndex];
			
			if(container == null)
				return Blocks.AIR.defaultBlockState();
			
			return container.get(pos.getX() & 15, pos.getY() & 15,
				pos.getZ() & 15);
		}
	}
}
