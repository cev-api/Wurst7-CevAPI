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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.dimension.DimensionType;
import net.wurstclient.WurstClient;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.settings.ChunkAreaSetting;

public abstract class AbstractChunkCoordinator implements PacketInputListener
{
	protected final HashMap<ChunkPos, ChunkSearcher> searchers =
		new HashMap<>();
	protected final ChunkAreaSetting area;
	private BiPredicate<BlockPos, BlockState> query;
	
	protected final Set<ChunkPos> chunksToUpdate =
		Collections.synchronizedSet(new HashSet<>());
	protected final ConcurrentLinkedQueue<PendingBlockUpdate> pendingBlockUpdates =
		new ConcurrentLinkedQueue<>();
	private final HashSet<ChunkPos> readyChunks = new HashSet<>();
	private final AtomicInteger matchesVersion = new AtomicInteger();
	
	public AbstractChunkCoordinator(BiPredicate<BlockPos, BlockState> query,
		ChunkAreaSetting area)
	{
		this.query = Objects.requireNonNull(query);
		this.area = Objects.requireNonNull(area);
	}
	
	public boolean update()
	{
		DimensionType dimension = WurstClient.MC.world.getDimension();
		HashSet<ChunkPos> chunkUpdates = clearChunksToUpdate();
		HashMap<ChunkPos, ArrayList<ChunkSearcher.BlockUpdate>> blockUpdates =
			clearBlockUpdates();
		boolean searchersChanged = false;
		boolean resultsChanged = false;
		
		// remove outdated ChunkSearchers
		for(ChunkSearcher searcher : new ArrayList<>(searchers.values()))
		{
			boolean remove = false;
			ChunkPos searcherPos = searcher.getPos();
			
			// wrong dimension
			if(dimension != searcher.getDimension())
				remove = true;
			
			// out of range
			else if(!area.isInRange(searcherPos))
				remove = true;
			
			// chunk update
			else if(chunkUpdates.contains(searcherPos))
				remove = true;
			
			if(remove)
			{
				searchers.remove(searcherPos);
				readyChunks.remove(searcherPos);
				searcher.cancel();
				onRemove(searcher);
				searchersChanged = true;
				resultsChanged = true;
			}
		}
		
		// add new ChunkSearchers
		for(Chunk chunk : area.getChunksInRange())
		{
			ChunkPos chunkPos = chunk.getPos();
			if(searchers.containsKey(chunkPos))
				continue;
			
			ChunkSearcher searcher = new ChunkSearcher(query, chunk, dimension);
			searchers.put(chunkPos, searcher);
			readyChunks.remove(chunkPos);
			searcher.start();
			searchersChanged = true;
		}
		
		// apply pending block updates
		for(Entry<ChunkPos, ArrayList<ChunkSearcher.BlockUpdate>> entry : blockUpdates
			.entrySet())
		{
			ChunkSearcher searcher = searchers.get(entry.getKey());
			if(searcher == null)
				continue;
			
			if(searcher.applyBlockUpdates(entry.getValue()))
			{
				readyChunks.add(entry.getKey());
				onMatchesUpdated(searcher);
				resultsChanged = true;
			}
		}
		
		// detect newly completed searchers
		for(ChunkSearcher searcher : searchers.values())
			if(searcher.hasResultsReady() && readyChunks.add(searcher.getPos()))
			{
				onMatchesUpdated(searcher);
				resultsChanged = true;
			}
		
		if(resultsChanged)
			matchesVersion.incrementAndGet();
		
		return searchersChanged;
	}
	
	protected void onRemove(ChunkSearcher searcher)
	{
		// Overridden in ChunkVertexBufferCoordinator
	}
	
	public void reset()
	{
		for(ChunkSearcher searcher : new ArrayList<>(searchers.values()))
		{
			searcher.cancel();
			onRemove(searcher);
		}
		
		searchers.clear();
		chunksToUpdate.clear();
		pendingBlockUpdates.clear();
		readyChunks.clear();
		matchesVersion.incrementAndGet();
	}
	
	public boolean isDone()
	{
		return searchers.values().stream().allMatch(ChunkSearcher::isDone);
	}
	
	public void setQuery(BiPredicate<BlockPos, BlockState> query)
	{
		this.query = Objects.requireNonNull(query);
		reset();
	}
	
	public void setTargetBlock(Block block)
	{
		setQuery((pos, state) -> block == state.getBlock());
	}
	
	public boolean hasReadyMatches()
	{
		return !readyChunks.isEmpty();
	}
	
	public int getMatchesVersion()
	{
		return matchesVersion.get();
	}
	
	protected Stream<ChunkSearcher.Result> streamReadyMatches()
	{
		return searchers.values().stream()
			.flatMap(ChunkSearcher::getReadyMatches);
	}
	
	protected void onMatchesUpdated(ChunkSearcher searcher)
	{
		// Overridden where needed
	}
	
	protected HashSet<ChunkPos> clearChunksToUpdate()
	{
		synchronized(chunksToUpdate)
		{
			HashSet<ChunkPos> chunks = new HashSet<>(chunksToUpdate);
			chunksToUpdate.clear();
			return chunks;
		}
	}
	
	protected HashMap<ChunkPos, ArrayList<ChunkSearcher.BlockUpdate>> clearBlockUpdates()
	{
		HashMap<ChunkPos, ArrayList<ChunkSearcher.BlockUpdate>> updates =
			new HashMap<>();
		PendingBlockUpdate pending;
		
		while((pending = pendingBlockUpdates.poll()) != null)
			updates.computeIfAbsent(pending.chunkPos(), k -> new ArrayList<>())
				.add(pending.update());
		
		return updates;
	}
	
	protected void enqueueBlockUpdate(ChunkPos chunkPos, BlockPos blockPos,
		BlockState state)
	{
		pendingBlockUpdates.add(new PendingBlockUpdate(chunkPos,
			new ChunkSearcher.BlockUpdate(blockPos.toImmutable(), state)));
	}
	
	protected record PendingBlockUpdate(ChunkPos chunkPos,
		ChunkSearcher.BlockUpdate update)
	{}
}
