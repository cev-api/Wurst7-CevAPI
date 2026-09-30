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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

/** Standalone regression checks: no Minecraft window or GPU is required. */
public final class EspCullingRegressionTest
{
	private static int checks;
	
	public static void main(String[] args)
	{
		Matrix4f projection =
			new Matrix4f().perspective((float)Math.toRadians(90), 1, .05F, 128);
		EspFrustum view = new EspFrustum(projection);
		check(view.intersects(-1, -1, -11, 1, 1, -9), "visible box");
		check(!view.intersects(-1, -1, 9, 1, 1, 11), "behind camera");
		check(!view.intersects(30, -1, -11, 32, 1, -9), "off-screen side");
		check(view.intersects(9, -1, -11, 20, 1, -9),
			"center outside, edge visible");
		check(view.intersects(-100, -100, -11, 100, 100, -9),
			"screen enclosed, no corner visible");
		check(view.intersects(-1, -1, -1, 1, 1, 1), "camera inside box");
		check(view.intersects(-1, -1, -10001, 1, 1, -10000),
			"far plane must not hide ESP");
		check(view.intersects(0, 0, -.001, .0001, .0001, -.001),
			"near plane must not hide ESP");
		EspFrustum turned =
			new EspFrustum(new Matrix4f(projection).rotateY((float)Math.PI));
		check(turned.intersects(-1, -1, 9, 1, 1, 11),
			"camera turn updates visibility");
		check(!turned.intersects(-1, -1, -11, 1, 1, -9), "old view now behind");
		check(view.intersects(-100, 0, -10, 100, 0, -10),
			"line crosses screen with both ends outside");
		check(view.intersects(-16, -1, -16, 16, 0, 0),
			"NewerNewChunks rectangle crosses camera plane");
		Random random = new Random(9321);
		for(int i = 0; i < 10000; i++)
		{
			Matrix4f transform = new Matrix4f()
				.perspective(.5F + random.nextFloat() * 1.7F,
					.5F + random.nextFloat() * 3, .05F, 256)
				.rotateX(random.nextFloat() * 6)
				.rotateY(random.nextFloat() * 6);
			float x = random.nextFloat() * 200 - 100;
			float y = random.nextFloat() * 200 - 100;
			float z = random.nextFloat() * 200 - 100;
			Vector4f clip = transform.transform(new Vector4f(x, y, z, 1));
			if(clip.w > 0 && Math.abs(clip.x) < clip.w
				&& Math.abs(clip.y) < clip.w)
				check(
					new EspFrustum(transform).intersects(x - 1, y - 1, z - 1,
						x + 1, y + 1, z + 1),
					"random on-screen sample retained");
		}
		Capture target = new Capture();
		CullingVertexConsumer consumer =
			new CullingVertexConsumer(target, view, 4);
		quad(consumer, 0, 0xff112233);
		quad(consumer, 100, 0xff445566);
		quad(consumer, 2, 0xff778899);
		consumer.finish();
		check(target.colors.size() == 8,
			"off-screen middle primitive must not terminate batch");
		check(
			target.colors.get(0) == 0xff112233
				&& target.colors.get(7) == 0xff778899,
			"visible vertex colors unchanged");
		check(
			target.uvCalls == 8 && target.normalCalls == 8
				&& target.widthCalls == 8,
			"visible vertex attributes unchanged");
		cachedMesh(view, turned, PrimitiveTopology.QUADS, 4);
		cachedMesh(view, turned, PrimitiveTopology.LINES, 4);
		cachedMesh(view, turned, PrimitiveTopology.DEBUG_LINES, 2);
		System.out.println("ESP culling regression checks passed: " + checks);
	}
	
	private static void cachedMesh(EspFrustum view, EspFrustum turned,
		PrimitiveTopology mode, int vertices)
	{
		VertexFormat format = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT).build();
		ByteBuffer data = ByteBuffer.allocate(3 * vertices * 12)
			.order(ByteOrder.nativeOrder());
		for(int i = 0; i < 3; i++)
			for(int j = 0; j < vertices; j++)
				data.putFloat(i == 1 ? 100 : i).putFloat(j % 2).putFloat(-10);
		MeshData.DrawState state = new MeshData.DrawState(format, 3 * vertices,
			mode.indexCount(3 * vertices), mode, IndexType.INT);
		EspMeshBounds mesh = new EspMeshBounds(data, state);
		int[] ranges = mesh.visibleRanges(view);
		int step = mode.indexCount(vertices);
		check(
			ranges.length == 4 && ranges[0] == 0 && ranges[1] == step
				&& ranges[2] == step * 2 && ranges[3] == step,
			"cached mesh retains visible runs: " + mode);
		check(mesh.visibleRanges(turned).length == 0,
			"cached mesh reevaluates camera turns: " + mode);
	}
	
	private static void quad(VertexConsumer out, float x, int color)
	{
		for(int i = 0; i < 4; i++)
			out.addVertex(x + (i & 1), (i >> 1), -10).setColor(color)
				.setUv(.25F, .75F).setNormal(0, 1, 0).setLineWidth(2);
	}
	
	private static void check(boolean value, String name)
	{
		checks++;
		if(!value)
			throw new AssertionError(name);
	}
	
	private static final class Capture implements VertexConsumer
	{
		final List<Integer> colors = new ArrayList<>();
		int uvCalls, normalCalls, widthCalls;
		
		@Override
		public VertexConsumer addVertex(float x, float y, float z)
		{
			return this;
		}
		
		@Override
		public VertexConsumer setColor(int c)
		{
			colors.add(c);
			return this;
		}
		
		@Override
		public VertexConsumer setColor(int r, int g, int b, int a)
		{
			return setColor(a << 24 | r << 16 | g << 8 | b);
		}
		
		@Override
		public VertexConsumer setUv(float u, float v)
		{
			check(u == .25F && v == .75F, "UV preserved");
			uvCalls++;
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
			check(x == 0 && y == 1 && z == 0, "normal preserved");
			normalCalls++;
			return this;
		}
		
		@Override
		public VertexConsumer setLineWidth(float width)
		{
			check(width == 2, "width preserved");
			widthCalls++;
			return this;
		}
	}
}
