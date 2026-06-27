/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * Simple wrapper around {@link StagedVertexBuffer} to replace Minecraft's
 * {@code MultiBufferSource} which was removed in 26.2-snapshot-5.
 * <p>
 * Safe to continue using after {@link #endBatch()} /
 * {@link #endBatch(RenderType)}
 * &mdash; the internal {@link StagedVertexBuffer} is re-created automatically
 * so that stale {@link BufferBuilder} instances with a null native pointer
 * cannot be returned.
 */
public final class WurstBufferSource
{
	private StagedVertexBuffer stagedBuffer = newStagedBuffer();
	private final List<StagedVertexBuffer.Draw> draws = new ArrayList<>();
	private final List<RenderType> drawTypes = new ArrayList<>();
	private boolean closed;
	
	private static StagedVertexBuffer newStagedBuffer()
	{
		return new StagedVertexBuffer(() -> "WurstBufferSource",
			RenderType.BIG_BUFFER_SIZE);
	}
	
	/**
	 * Makes this instance ready for a fresh batch of draws after a previous
	 * {@link #endBatch()} / {@link #close()} call. Callers that batch-split
	 * (endBatch then getBuffer again on the same instance) do not need to
	 * call this themselves &mdash; {@link #getBuffer(RenderType)} calls it
	 * automatically when needed.
	 */
	private void ensureOpen()
	{
		if(closed)
		{
			stagedBuffer = newStagedBuffer();
			closed = false;
		}
	}
	
	public VertexConsumer getBuffer(RenderType renderType)
	{
		ensureOpen();
		
		if(!drawTypes.isEmpty() && drawTypes.getLast() == renderType
			&& renderType.canConsolidateConsecutiveGeometry())
			return stagedBuffer.getVertexBuilder(draws.getLast());
		
		StagedVertexBuffer.Draw draw =
			stagedBuffer.appendDraw(renderType.format(),
				renderType.primitiveTopology(), renderType.sortOnUpload()
					? RenderSystem.getProjectionType().vertexSorting() : null);
		
		draws.add(draw);
		drawTypes.add(renderType);
		return stagedBuffer.getVertexBuilder(draw);
	}
	
	public void uploadAndDraw()
	{
		try
		{
			if(draws.isEmpty())
				return;
			
			stagedBuffer.upload();
			
			for(int i = 0; i < draws.size(); i++)
				draw(drawTypes.get(i), draws.get(i));
			
			stagedBuffer.endDraw();
			
		}finally
		{
			draws.clear();
			drawTypes.clear();
			stagedBuffer.close();
			closed = true;
		}
	}
	
	public void endBatch(RenderType renderType)
	{
		uploadAndDraw();
	}
	
	public void endBatch()
	{
		uploadAndDraw();
	}
	
	/**
	 * Releases the underlying buffer without uploading or drawing.
	 * Safe to call even after {@link #endBatch()} has already been called.
	 */
	public void close()
	{
		draws.clear();
		drawTypes.clear();
		if(!closed)
		{
			stagedBuffer.close();
			closed = true;
		}
	}
	
	private void draw(RenderType type, StagedVertexBuffer.Draw draw)
	{
		StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(draw);
		
		if(info != null)
			type.prepare().drawFromBuffer(info);
	}
}
