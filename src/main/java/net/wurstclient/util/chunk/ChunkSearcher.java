/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.chunk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
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
	private static volatile java.util.concurrent.ExecutorService backgroundThreadPool =
		MinPriorityThreadFactory.newFixedThreadPool();
	private static volatile int backgroundThreadPriority =
		MinPriorityThreadFactory.getConfiguredThreadPriority();
	// Index setup pays off for large batches; delay it to skip early removals.
	private static final int MIN_INDEXED_UPDATES = 64;
	private static final int MIN_INDEXED_RESULTS = 256;
	private static final int INDEX_WARMUP_UPDATES = 16;
	private static final int MIN_REMAINING_INDEXED_UPDATES = 32;
	private static final int INDEX_SCAN_WORK_FACTOR = 8;
	
	private final BiPredicate<BlockPos, BlockState> query;
	private final ChunkAccess chunk;
	private final DimensionType dimension;
	
	private CompletableFuture<ArrayList<Result>> future;
	private volatile boolean interrupted;
	private volatile ArrayList<Result> results;
	// Guarded by this; old snapshots remain immutable after updates.
	private List<Result> resultsSnapshot;
	// Guarded by this; detects changes made by reentrant query callbacks.
	private long resultsRevision;
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
			backgroundThreadPool);
	}
	
	public static int getBackgroundThreadPriority()
	{
		return backgroundThreadPriority;
	}
	
	public static synchronized void setBackgroundThreadPriority(int priority)
	{
		int clamped = MinPriorityThreadFactory.clampThreadPriority(priority);
		if(clamped == backgroundThreadPriority)
			return;
		
		java.util.concurrent.ExecutorService oldPool = backgroundThreadPool;
		backgroundThreadPool =
			MinPriorityThreadFactory.newFixedThreadPool(clamped);
		backgroundThreadPriority = clamped;
		oldPool.shutdownNow();
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
		{
			int localX = x & 15;
			for(int y = minY; y <= maxY; y++)
			{
				PalettedContainer<BlockState> section = snapshot.getSection(y);
				int localY = y & 15;
				for(int z = minZ; z <= maxZ; z++)
				{
					if(interrupted)
						return results;
					
					mutablePos.set(x, y, z);
					BlockState state;
					try
					{
						state = section == null ? Blocks.AIR.defaultBlockState()
							: section.get(localX, localY, z & 15);
						
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
			}
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
		
		return getResultsSnapshot().stream();
	}
	
	public List<Result> getMatchesList()
	{
		if(future == null || future.isCancelled())
			return List.of();
		
		ensureResultsLoaded();
		if(results == null)
			return List.of();
		
		return getResultsSnapshot();
	}
	
	public Stream<Result> getReadyMatches()
	{
		if(!hasResultsReady())
			return Stream.empty();
		
		return getResultsSnapshot().stream();
	}
	
	public List<Result> getReadyMatchesList()
	{
		if(!hasResultsReady())
			return List.of();
		
		return getResultsSnapshot();
	}
	
	private synchronized List<Result> getResultsSnapshot()
	{
		if(resultsSnapshot == null)
			resultsSnapshot =
				Collections.unmodifiableList(new ArrayList<>(results));
		return resultsSnapshot;
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
		if(updates.size() < MIN_INDEXED_UPDATES
			|| target.size() < MIN_INDEXED_RESULTS
				&& updates.size() < MIN_INDEXED_RESULTS)
			return applyUpdatesLinear(target, updates.iterator());
		
		boolean changed = false;
		boolean canIndex = true;
		int processed = 0;
		long linearComparisons = 0;
		Object2IntOpenHashMap<BlockPos> indices = null;
		
		Iterator<BlockUpdate> iterator = updates.iterator();
		while(iterator.hasNext())
		{
			// A cheap initial probe does not justify further index bookkeeping.
			if(!canIndex || indices == null && processed >= INDEX_WARMUP_UPDATES
				&& linearComparisons < target.size())
				return changed | applyUpdatesLinear(target, iterator);
			
			BlockUpdate update = iterator.next();
			BlockPos pos = update.pos();
			BlockState state = update.state();
			long revisionBeforeQuery = resultsRevision;
			boolean matches = query.test(pos, state);
			if(canIndex && (resultsRevision != revisionBeforeQuery
				|| pos == null || pos.getClass() != BlockPos.class))
			{
				indices = null;
				canIndex = false;
			}
			// Near-head lookups and early removals should not pay for an index.
			if(canIndex && indices == null && matches
				&& linearComparisons >= (long)target.size()
					* INDEX_SCAN_WORK_FACTOR
				&& target.size() >= MIN_INDEXED_RESULTS
				&& processed >= INDEX_WARMUP_UPDATES
				&& updates.size() - processed >= MIN_REMAINING_INDEXED_UPDATES)
			{
				indices = createIndex(target);
				canIndex = indices != null;
			}
			int index =
				indices == null ? indexOf(target, pos) : indices.getInt(pos);
			if(canIndex && indices == null)
			{
				processed++;
				linearComparisons += index < 0 ? target.size() : index + 1;
			}
			
			if(!applyUpdate(target, pos, state, matches, index))
				continue;
			changed = true;
			if(matches)
			{
				if(index < 0 && indices != null)
					indices.put(pos, target.size() - 1);
			}else
			{
				// Removal shifts list indices; finish this batch with linear
				// lookup.
				indices = null;
				canIndex = false;
			}
		}
		
		return changed;
	}
	
	private boolean applyUpdatesLinear(ArrayList<Result> target,
		Iterator<BlockUpdate> updates)
	{
		boolean changed = false;
		while(updates.hasNext())
		{
			BlockUpdate update = updates.next();
			BlockPos pos = update.pos();
			BlockState state = update.state();
			boolean matches = query.test(pos, state);
			int index = indexOf(target, pos);
			changed |= applyUpdate(target, pos, state, matches, index);
		}
		return changed;
	}
	
	private boolean applyUpdate(ArrayList<Result> target, BlockPos pos,
		BlockState state, boolean matches, int index)
	{
		if(matches)
		{
			if(index < 0)
				target.add(new Result(pos, state));
			else if(target.get(index).state() != state)
				target.set(index, new Result(pos, state));
			else
				return false;
		}else if(index >= 0)
			target.remove(index);
		else
			return false;
		
		resultsSnapshot = null;
		resultsRevision++;
		return true;
	}
	
	private Object2IntOpenHashMap<BlockPos> createIndex(ArrayList<Result> list)
	{
		Object2IntOpenHashMap<BlockPos> indices =
			new Object2IntOpenHashMap<>(list.size());
		indices.defaultReturnValue(-1);
		for(int i = 0; i < list.size(); i++)
		{
			BlockPos pos = list.get(i).pos();
			// Mutable or custom positions require the original equality scan.
			if(pos == null || pos.getClass() != BlockPos.class)
				return null;
			indices.putIfAbsent(pos, i);
		}
		return indices;
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
			if(!WurstClient.MC.level.hasChunk(chunkPos.x(), chunkPos.z()))
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
			int minY = chunk.getMinY();
			int minZ = chunkPos.getMinBlockZ();
			int maxX = chunkPos.getMaxBlockX();
			int maxY = ChunkUtils.getHighestNonEmptySectionYOffset(chunk) + 16;
			int maxZ = chunkPos.getMaxBlockZ();
			int minSectionCoord = SectionPos.blockToSectionCoord(minY);
			
			return new ChunkSnapshot(chunkPos, minX, minY, minZ, maxX, maxY,
				maxZ, minSectionCoord, copies);
		}
		
		PalettedContainer<BlockState> getSection(int y)
		{
			int ySection = SectionPos.blockToSectionCoord(y);
			int sectionIndex = ySection - minSectionCoord;
			return sectionIndex >= 0 && sectionIndex < sections.length
				? sections[sectionIndex] : null;
		}
	}
}
