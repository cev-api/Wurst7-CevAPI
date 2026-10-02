/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Suppressed geometry needs neither native vertex storage nor an upload. */
public enum DiscardingVertexConsumer implements VertexConsumer
{
	INSTANCE;
	
	@Override
	public VertexConsumer addVertex(float x, float y, float z)
	{
		return this;
	}
	
	@Override
	public VertexConsumer setColor(int red, int green, int blue, int alpha)
	{
		return this;
	}
	
	@Override
	public VertexConsumer setColor(int color)
	{
		return this;
	}
	
	@Override
	public VertexConsumer setUv(float u, float v)
	{
		return this;
	}
	
	@Override
	public VertexConsumer setUv1(int u, int v)
	{
		return this;
	}
	
	@Override
	public VertexConsumer setUv2(int u, int v)
	{
		return this;
	}
	
	@Override
	public VertexConsumer setUv3(float u, float v)
	{
		return this;
	}
	
	@Override
	public VertexConsumer setNormal(float x, float y, float z)
	{
		return this;
	}
	
	@Override
	public VertexConsumer setLineWidth(float width)
	{
		return this;
	}
}
