/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Buffers one complete independent primitive, retaining every vertex attribute.
 * Off-screen primitives are discarded before they enter the upload buffer.
 */
public final class CullingVertexConsumer implements VertexConsumer
{
	private final VertexConsumer target;
	private final EspFrustum frustum;
	private final Vertex[] vertices;
	private int count;
	
	public CullingVertexConsumer(VertexConsumer target, EspFrustum frustum,
		int primitiveLength)
	{
		this.target = target;
		this.frustum = frustum;
		vertices = new Vertex[primitiveLength];
		for(int i = 0; i < vertices.length; i++)
			vertices[i] = new Vertex();
	}
	
	@Override
	public VertexConsumer addVertex(float x, float y, float z)
	{
		if(count == vertices.length)
			finish();
		Vertex v = vertices[count++];
		v.x = x;
		v.y = y;
		v.z = z;
		v.flags = 0;
		return this;
	}
	
	public void finish()
	{
		if(count == 0)
			return;
		double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
		double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
		for(int i = 0; i < count; i++)
		{
			Vertex v = vertices[i];
			minX = Math.min(minX, v.x);
			minY = Math.min(minY, v.y);
			minZ = Math.min(minZ, v.z);
			maxX = Math.max(maxX, v.x);
			maxY = Math.max(maxY, v.y);
			maxZ = Math.max(maxZ, v.z);
		}
		if(count != vertices.length
			|| frustum.intersects(minX, minY, minZ, maxX, maxY, maxZ))
			for(int i = 0; i < count; i++)
				vertices[i].emit(target);
		count = 0;
	}
	
	private Vertex current()
	{
		return vertices[count - 1];
	}
	
	/** Fully contained geometry cannot benefit from another primitive test. */
	public VertexConsumer forBounds(double minX, double minY, double minZ,
		double maxX, double maxY, double maxZ)
	{
		// Submit any preceding primitive before bypassing the wrapper, so
		// translucent geometry keeps its original order.
		finish();
		return frustum.contains(minX, minY, minZ, maxX, maxY, maxZ) ? target
			: this;
	}
	
	@Override
	public VertexConsumer setColor(int r, int g, int b, int a)
	{
		return setColor(a << 24 | r << 16 | g << 8 | b);
	}
	
	@Override
	public VertexConsumer setColor(int color)
	{
		Vertex v = current();
		v.color = color;
		v.flags |= 1;
		return this;
	}
	
	@Override
	public VertexConsumer setUv(float u, float v)
	{
		Vertex p = current();
		p.u = u;
		p.v = v;
		p.flags |= 2;
		return this;
	}
	
	@Override
	public VertexConsumer setUv1(int u, int v)
	{
		Vertex p = current();
		p.u1 = u;
		p.v1 = v;
		p.flags |= 4;
		return this;
	}
	
	@Override
	public VertexConsumer setUv2(int u, int v)
	{
		Vertex p = current();
		p.u2 = u;
		p.v2 = v;
		p.flags |= 8;
		return this;
	}
	
	@Override
	public VertexConsumer setUv3(float u, float v)
	{
		Vertex p = current();
		p.u3 = u;
		p.v3 = v;
		p.flags |= 16;
		return this;
	}
	
	@Override
	public VertexConsumer setNormal(float x, float y, float z)
	{
		Vertex p = current();
		p.nx = x;
		p.ny = y;
		p.nz = z;
		p.flags |= 32;
		return this;
	}
	
	@Override
	public VertexConsumer setLineWidth(float width)
	{
		Vertex p = current();
		p.width = width;
		p.flags |= 64;
		return this;
	}
	
	private static final class Vertex
	{
		float x, y, z, u, v, u3, v3, nx, ny, nz, width;
		int color, u1, v1, u2, v2, flags;
		
		void emit(VertexConsumer out)
		{
			out.addVertex(x, y, z);
			if((flags & 1) != 0)
				out.setColor(color);
			if((flags & 2) != 0)
				out.setUv(u, v);
			if((flags & 4) != 0)
				out.setUv1(u1, v1);
			if((flags & 8) != 0)
				out.setUv2(u2, v2);
			if((flags & 16) != 0)
				out.setUv3(u3, v3);
			if((flags & 32) != 0)
				out.setNormal(nx, ny, nz);
			if((flags & 64) != 0)
				out.setLineWidth(width);
		}
	}
}
