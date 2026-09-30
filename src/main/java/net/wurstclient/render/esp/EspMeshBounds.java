/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

/**
 * Immutable CPU bounds for cached geometry. Visibility is evaluated at draw
 * time, never baked into a buffer that must survive camera turns.
 */
public final class EspMeshBounds
{
	private final float[] bounds;
	private final int indicesPerPrimitive;
	private final float[] total =
		{Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
			Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
			Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
	
	public EspMeshBounds(MeshData mesh)
	{
		this(mesh.vertexBuffer(), mesh.drawState());
	}
	
	EspMeshBounds(ByteBuffer vertexData, MeshData.DrawState state)
	{
		var mode = state.primitiveTopology();
		var position = state.format().getElement("Position");
		if(position == null || position.format() != GpuFormat.RGB32_FLOAT)
		{
			bounds = null;
			indicesPerPrimitive = 0;
			return;
		}
		int vertices =
			mode == PrimitiveTopology.LINES ? 4 : mode.primitiveLength;
		// Connected strips/fans cannot be split without reconstructing indices.
		// Keep a single conservative bound for those rare meshes.
		if(mode.connectedPrimitives)
			vertices = state.vertexCount();
		indicesPerPrimitive = mode.indexCount(vertices);
		int count = vertices == 0 ? 0 : state.vertexCount() / vertices;
		bounds = new float[count * 6];
		ByteBuffer data = vertexData.duplicate().order(ByteOrder.nativeOrder());
		int stride = state.format().getVertexSize();
		for(int i = 0; i < count; i++)
		{
			int base = i * 6;
			Arrays.fill(bounds, base, base + 3, Float.POSITIVE_INFINITY);
			Arrays.fill(bounds, base + 3, base + 6, Float.NEGATIVE_INFINITY);
			for(int v = 0; v < vertices; v++)
				for(int axis = 0; axis < 3; axis++)
				{
					float value = data.getFloat((i * vertices + v) * stride
						+ position.offset() + axis * 4);
					bounds[base + axis] = Math.min(bounds[base + axis], value);
					bounds[base + 3 + axis] =
						Math.max(bounds[base + 3 + axis], value);
					total[axis] = Math.min(total[axis], value);
					total[3 + axis] = Math.max(total[3 + axis], value);
				}
		}
	}
	
	/** Null means the complete mesh; otherwise pairs of first-index/count. */
	public int[] visibleRanges(EspFrustum frustum)
	{
		if(bounds == null)
			return null;
		if(!frustum.intersects(total[0], total[1], total[2], total[3], total[4],
			total[5]))
			return new int[0];
		if(frustum.contains(total[0], total[1], total[2], total[3], total[4],
			total[5]))
			return null;
		int[] ranges = new int[16];
		int used = 0, runStart = -1;
		int count = bounds.length / 6;
		for(int i = 0; i <= count; i++)
		{
			int b = i * 6;
			boolean visible =
				i < count && frustum.intersects(bounds[b], bounds[b + 1],
					bounds[b + 2], bounds[b + 3], bounds[b + 4], bounds[b + 5]);
			if(visible && runStart < 0)
				runStart = i;
			if(!visible && runStart >= 0)
			{
				if(runStart == 0 && i == count)
					return null;
				if(used + 2 > ranges.length)
					ranges = Arrays.copyOf(ranges, ranges.length * 2);
				ranges[used++] = runStart * indicesPerPrimitive;
				ranges[used++] = (i - runStart) * indicesPerPrimitive;
				runStart = -1;
			}
		}
		return Arrays.copyOf(ranges, used);
	}
}
