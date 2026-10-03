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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.util.EasyVertexBuffer;
import net.wurstclient.util.chunk.ChunkSearcher.Result;

public final class ChunkVertexBufferCoordinator extends AbstractChunkCoordinator
{
	private final HashMap<ChunkPos, EasyVertexBuffer> buffers = new HashMap<>();
	private final Set<Entry<ChunkPos, EasyVertexBuffer>> buffersView =
		Collections.unmodifiableSet(buffers.entrySet());
	private final HashSet<ChunkPos> chunksToBuild = new HashSet<>();
	private final ArrayList<ChunkSearcher> pendingBuffers = new ArrayList<>();
	private boolean pendingOrderChanged;
	private final Renderer renderer;
	private final PrimitiveTopology drawMode;
	private final VertexFormat format;
	
	public ChunkVertexBufferCoordinator(BiPredicate<BlockPos, BlockState> query,
		PrimitiveTopology drawMode, VertexFormat format, Renderer renderer,
		ChunkAreaSetting area)
	{
		super(query, area);
		this.renderer = Objects.requireNonNull(renderer);
		this.drawMode = drawMode;
		this.format = format;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		ChunkPos center = ChunkUtils.getAffectedChunk(event.getPacket());
		if(center == null)
			return;
		
		for(int x = center.x() - 1; x <= center.x() + 1; x++)
			for(int z = center.z() - 1; z <= center.z() + 1; z++)
				chunksToUpdate.add(new ChunkPos(x, z));
	}
	
	@Override
	protected void onAdd(ChunkSearcher searcher)
	{
		super.onAdd(searcher);
		chunksToBuild.add(searcher.getPos());
		pendingOrderChanged = true;
	}
	
	@Override
	protected void onRemove(ChunkSearcher searcher)
	{
		super.onRemove(searcher);
		chunksToBuild.remove(searcher.getPos());
		pendingOrderChanged = true;
		@SuppressWarnings("resource")
		EasyVertexBuffer buffer = buffers.remove(searcher.getPos());
		if(buffer != null)
			buffer.close();
	}
	
	@Override
	protected void onMatchesUpdated(ChunkSearcher searcher)
	{
		super.onMatchesUpdated(searcher);
		if(chunksToBuild.add(searcher.getPos()))
			pendingOrderChanged = true;
		@SuppressWarnings("resource")
		EasyVertexBuffer buffer = buffers.remove(searcher.getPos());
		if(buffer != null)
			buffer.close();
	}
	
	@Override
	public void reset()
	{
		super.reset();
		buffers.values().forEach(EasyVertexBuffer::close);
		buffers.clear();
		chunksToBuild.clear();
		pendingBuffers.clear();
		pendingOrderChanged = false;
	}
	
	public Set<Entry<ChunkPos, EasyVertexBuffer>> getBuffers()
	{
		if(pendingOrderChanged)
		{
			pendingBuffers.clear();
			// Rebuild only after membership changes, preserving the renderer's
			// existing callback order without scanning built chunks every
			// frame.
			if(!chunksToBuild.isEmpty())
				for(ChunkSearcher searcher : searchers.values())
					if(chunksToBuild.contains(searcher.getPos()))
						pendingBuffers.add(searcher);
			pendingOrderChanged = false;
		}
		
		if(pendingBuffers.isEmpty())
			return buffersView;
		
		try
		{
			for(int i = 0; i < pendingBuffers.size(); i++)
			{
				ChunkSearcher searcher = pendingBuffers.get(i);
				if(!searcher.hasResultsReady())
					continue;
				
				buildBuffer(searcher);
				chunksToBuild.remove(searcher.getPos());
				pendingBuffers.set(i, null);
			}
		}finally
		{
			// Compact in one pass instead of shifting the list after every
			// upload. A failed upload stays pending so the next call retries
			// it.
			pendingBuffers.removeIf(Objects::isNull);
		}
		
		return buffersView;
	}
	
	private void buildBuffer(ChunkSearcher searcher)
	{
		ChunkPos chunkPos = searcher.getPos();
		// A pending search would only build an empty buffer and rebuild it
		// when results arrive. Capture ready results once before uploading.
		List<Result> results = searcher.getReadyMatchesList();
		EasyVertexBuffer vertexBuffer =
			EasyVertexBuffer.createAndUpload(drawMode, format,
				buffer -> renderer.buildBuffer(buffer, searcher, results));
		
		buffers.put(chunkPos, vertexBuffer);
	}
	
	public static interface Renderer
	{
		public void buildBuffer(VertexConsumer buffer, ChunkSearcher searcher,
			List<Result> results);
	}
}
