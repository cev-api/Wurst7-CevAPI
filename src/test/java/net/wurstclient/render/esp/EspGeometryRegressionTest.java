/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.state.gui.ColoredRectangleRenderState;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.world.phys.AABB;
import net.wurstclient.util.HackPerformanceTracker;

/** Exact vertex and ordering checks, without a Minecraft window or GPU. */
public final class EspGeometryRegressionTest
{
	private static int checks;
	private static volatile double sink;
	
	public static void main(String[] args)
	{
		geometry();
		screenBatches();
		rectangleVertices();
		check(HackPerformanceTracker.averageFrameMillis(162_000_000, 29) == 162D
			/ 29, "window totals divided by matching frame count");
		check(HackPerformanceTracker.averageFrameMillis(400_000_000, 20) == 20,
			"update costs amortized across rendered frames");
		check(HackPerformanceTracker.averageFrameMillis(400_000_000, 0) == 0,
			"empty window");
		System.out.println("ESP geometry regression checks passed: " + checks);
		if(Arrays.asList(args).contains("--benchmark"))
			benchmark();
	}
	
	private static void geometry()
	{
		Random random = new Random(260310);
		EspFrustum frustum =
			new EspFrustum(new Matrix4f().perspective(1.4F, 1.8F, .05F, 1024));
		for(int sample = 0; sample < 1500; sample++)
		{
			PoseStack pose = new PoseStack();
			pose.translate(random.nextFloat() * 40 - 20,
				random.nextFloat() * 20 - 10, random.nextFloat() * 50 - 25);
			pose.rotate(new Quaternionf().rotationXYZ(random.nextFloat() * 6,
				random.nextFloat() * 6, random.nextFloat() * 6));
			pose.scale(random.nextFloat() * 2 + .01F,
				random.nextFloat() * 2 + .01F, random.nextFloat() * 2 + .01F);
			AABB box = new AABB(random.nextDouble() * 40 - 20,
				random.nextDouble() * 20 - 10, random.nextDouble() * 80 - 40,
				random.nextDouble() * 40 - 20, random.nextDouble() * 20 - 10,
				random.nextDouble() * 80 - 40);
			for(boolean lines : new boolean[]{false, true})
				for(boolean cull : new boolean[]{false, true})
				{
					Capture expected = new Capture(), actual = new Capture();
					VertexConsumer oldTarget =
						cull ? new CullingVertexConsumer(expected, frustum,
							lines ? 2 : 4) : expected;
					VertexConsumer newTarget =
						cull ? new CullingVertexConsumer(actual, frustum,
							lines ? 2 : 4) : actual;
					int color = random.nextInt();
					emit(false, lines, pose, oldTarget, box, color);
					emit(true, lines, pose, newTarget, box, color);
					finish(oldTarget);
					finish(newTarget);
					check(expected.values.equals(actual.values),
						"all original vertex bits, attributes and order, including partial visibility");
				}
		}
		Capture output = new Capture();
		CullingVertexConsumer consumer =
			new CullingVertexConsumer(output, frustum, 2);
		consumer.addVertex(0, 0, -10).setColor(1);
		consumer.addVertex(1, 0, -10).setColor(2);
		check(consumer.forBounds(-1, -1, -12, 1, 1, -10) == output,
			"fully contained boxes bypass primitive checks");
		check(output.values.size() == 8,
			"preceding complete primitive submitted before bypass");
		check(consumer.forBounds(8, -1, -12, 20, 1, -10) == consumer,
			"screen boundary keeps primitive checks");
		check(consumer.forBounds(Double.NaN, 0, 0, 1, 1, 1) == consumer,
			"unknown bounds keep conservative fallback");
	}
	
	private static void emit(boolean fast, boolean lines, PoseStack pose,
		VertexConsumer target, AABB box, int color)
	{
		if(fast)
		{
			if(lines)
				EspBoxGeometry.outline(pose.last(), target, box, color);
			else
				EspBoxGeometry.solid(pose.last(), target, box, color);
		}else if(lines)
			legacyOutline(pose, target, box, color);
		else
			legacySolid(pose, target, box, color);
	}
	
	private static void finish(VertexConsumer target)
	{
		if(target instanceof CullingVertexConsumer culling)
			culling.finish();
	}
	
	private static void screenBatches()
	{
		Random random = new Random(991);
		for(int capacity : new int[]{1, 2, 16, 4096})
		{
			ArrayList<ScreenRectangle> bounds = new ArrayList<>();
			ArrayList<List<Integer>> submitted = new ArrayList<>();
			NonOverlappingScreenBatch<Integer> batch =
				new NonOverlappingScreenBatch<>(capacity,
					group -> submitted.add(List.copyOf(group)));
			batch.add(-1, null);
			for(int i = 0; i < 1000; i++)
			{
				ScreenRectangle area = i % 71 == 0
					? new ScreenRectangle(-10000, -10000, 20000, 20000)
					: new ScreenRectangle(random.nextInt(2000) - 1000,
						random.nextInt(1200) - 600, random.nextInt(100) + 1,
						random.nextInt(40) + 1);
				bounds.add(area);
				batch.add(i, area);
			}
			batch.flush();
			int expected = 0;
			for(List<Integer> group : submitted)
			{
				check(group.size() <= capacity, "bounded batch");
				for(int i : group)
					check(i == expected++,
						"every tag retained in original group order");
				for(int a = 0; a < group.size(); a++)
					for(int b = a + 1; b < group.size(); b++)
						check(
							!bounds.get(group.get(a))
								.intersects(bounds.get(group.get(b))),
							"only disjoint tags reordered, including negative cells and large areas");
			}
			check(expected == bounds.size(),
				"no operations lost or duplicated");
			batch.add(1, new ScreenRectangle(0, 0, 16, 16));
			batch.clear();
			batch.flush();
			check(submitted.stream().mapToInt(List::size).sum() == 1000,
				"clear discards pending work");
		}
		NonOverlappingScreenBatch<Integer> failing =
			new NonOverlappingScreenBatch<>(4, group -> {
				throw new IllegalStateException("test");
			});
		failing.add(1, new ScreenRectangle(0, 0, 4, 4));
		try
		{
			failing.flush();
			throw new AssertionError("expected submit failure");
		}catch(IllegalStateException expected)
		{}
		failing.flush();
		check(true, "failed flush releases pending state");
	}
	
	private static void rectangleVertices()
	{
		Random random = new Random(18);
		for(int frame = 0; frame < 100; frame++)
		{
			List<ColoredRectangleRenderState> rectangles = new ArrayList<>();
			Capture expected = new Capture(), actual = new Capture();
			ScreenRectangle area = null;
			for(int i = 0; i < 20; i++)
			{
				Matrix3x2f pose = new Matrix3x2f()
					.translate(random.nextFloat() * 300,
						random.nextFloat() * 300)
					.scale(.1F + random.nextFloat() * 3);
				var rectangle = new ColoredRectangleRenderState(null,
					TextureSetup.noTexture(), pose, 30, 20, -2, -2,
					random.nextInt(), random.nextInt(), null);
				rectangles.add(rectangle);
				rectangle.buildVertices(expected);
				area = EspItemTagBatcher.union(area, rectangle.bounds());
			}
			GuiRectangleBatch batch = new GuiRectangleBatch(rectangles, area);
			rectangles.clear();
			batch.buildVertices(actual);
			check(expected.values.equals(actual.values),
				"deferred background batch keeps original vertex order, poses, colors and stable membership");
		}
	}
	
	private static void benchmark()
	{
		PoseStack pose = new PoseStack();
		pose.rotate(new Quaternionf().rotationXYZ(.2F, .8F, -.1F));
		pose.scale(.7F, 1.6F, 1.2F);
		AABB[] boxes = new AABB[5000];
		Random random = new Random(90);
		for(int i = 0; i < boxes.length; i++)
			boxes[i] = new AABB(random.nextDouble() * 10 - 5,
				random.nextDouble() * 4 - 2, -50, random.nextDouble() * 10 - 5,
				random.nextDouble() * 4 - 2, -49);
		EspFrustum frustum =
			new EspFrustum(new Matrix4f().perspective(1.4F, 1.8F, .05F, 1024));
		for(boolean cull : new boolean[]{false, true})
		{
			Runnable old =
				() -> geometryWork(false, pose, boxes, cull ? frustum : null);
			Runnable fast =
				() -> geometryWork(true, pose, boxes, cull ? frustum : null);
			compare(
				"5000 filled/outlined boxes" + (cull ? " with culling" : ""),
				old, fast);
		}
		for(boolean dense : new boolean[]{false, true})
		{
			FakeTag[] tags = new FakeTag[1000];
			for(int i = 0; i < tags.length; i++)
			{
				int x = dense ? random.nextInt(1000) : i % 40 * 50;
				int y = dense ? random.nextInt(600) : i / 40 * 30;
				tags[i] =
					new FakeTag(new Element(new ScreenRectangle(x, y, 44, 24)),
						new Element(new ScreenRectangle(x + 2, y + 2, 16, 16)),
						new Element(new ScreenRectangle(x + 23, y + 7, 18, 9)));
			}
			compare(
				"native GUI hierarchy, 1000 "
					+ (dense ? "overlapping" : "disjoint") + " tags",
				() -> guiWork(tags, false), () -> guiWork(tags, true));
		}
	}
	
	private static void geometryWork(boolean fast, PoseStack pose, AABB[] boxes,
		EspFrustum frustum)
	{
		SummingConsumer output = new SummingConsumer();
		VertexConsumer solid = frustum == null ? output
			: new CullingVertexConsumer(output, frustum, 4);
		VertexConsumer outline = frustum == null ? output
			: new CullingVertexConsumer(output, frustum, 2);
		for(AABB box : boxes)
		{
			emit(fast, false, pose, solid, box, -1);
			emit(fast, true, pose, outline, box, -1);
		}
		finish(solid);
		finish(outline);
		sink = output.sum;
	}
	
	private record FakeTag(Element background, Element icon, Element text)
	{}
	
	private record Element(ScreenRectangle bounds)
		implements GuiElementRenderState
	{
		public void buildVertices(VertexConsumer target)
		{}
		
		public RenderPipeline pipeline()
		{
			return null;
		}
		
		public TextureSetup textureSetup()
		{
			return null;
		}
		
		public ScreenRectangle scissorArea()
		{
			return null;
		}
	}
	
	private static void guiWork(FakeTag[] tags, boolean fast)
	{
		GuiRenderState state = new GuiRenderState();
		if(!fast)
			for(FakeTag tag : tags)
			{
				state.addGuiElement(tag.background);
				state.addGuiElement(tag.icon);
				state.addGuiElement(tag.text);
			}
		else
		{
			NonOverlappingScreenBatch<FakeTag> batch =
				new NonOverlappingScreenBatch<>(4096, group -> {
					if(group.size() == 1)
					{
						FakeTag tag = group.getFirst();
						state.addGuiElement(tag.background);
						state.addGuiElement(tag.icon);
						state.addGuiElement(tag.text);
						return;
					}
					ScreenRectangle area = null;
					for(FakeTag tag : group)
						area = EspItemTagBatcher.union(area,
							tag.background.bounds);
					state.addGuiElement(new Element(area));
					state.up();
					for(FakeTag tag : group)
					{
						state.current.addGuiElement(tag.icon);
						state.current.addGuiElement(tag.text);
					}
				});
			for(FakeTag tag : tags)
				batch.add(tag, tag.background.bounds);
			batch.flush();
		}
		GuiRenderState.Node root = state.current;
		while(root.parent != null)
			root = root.parent;
		int count = 0;
		for(var node = root; node != null; node = node.up)
			if(node.elementStates != null)
				count += node.elementStates.size();
		sink = count;
	}
	
	private static void compare(String label, Runnable old, Runnable fast)
	{
		for(int i = 0; i < 8; i++)
		{
			old.run();
			fast.run();
		}
		double before = median(old), after = median(fast);
		System.out.printf(java.util.Locale.ROOT,
			"%s: original %.3f ms, optimized %.3f ms, %.2fx (CPU microbenchmark)%n",
			label, before, after, before / after);
	}
	
	private static double median(Runnable work)
	{
		long[] samples = new long[9];
		for(int i = 0; i < samples.length; i++)
		{
			long start = System.nanoTime();
			work.run();
			samples[i] = System.nanoTime() - start;
		}
		Arrays.sort(samples);
		return samples[samples.length / 2] / 1e6;
	}
	
	private static void check(boolean condition, String message)
	{
		checks++;
		if(!condition)
			throw new AssertionError(message);
	}
	
	private static class SummingConsumer implements VertexConsumer
	{
		double sum;
		
		public VertexConsumer addVertex(float x, float y, float z)
		{
			sum += x + y + z;
			return this;
		}
		
		public VertexConsumer setColor(int r, int g, int b, int a)
		{
			return setColor(a << 24 | r << 16 | g << 8 | b);
		}
		
		public VertexConsumer setColor(int color)
		{
			sum += color;
			return this;
		}
		
		public VertexConsumer setUv(float u, float v)
		{
			return this;
		}
		
		public VertexConsumer setUv1(int u, int v)
		{
			return this;
		}
		
		public VertexConsumer setUv2(int u, int v)
		{
			return this;
		}
		
		public VertexConsumer setUv3(float u, float v)
		{
			return this;
		}
		
		public VertexConsumer setNormal(float x, float y, float z)
		{
			sum += x + y + z;
			return this;
		}
		
		public VertexConsumer setLineWidth(float width)
		{
			sum += width;
			return this;
		}
	}
	
	private static final class Capture extends SummingConsumer
	{
		final List<Integer> values = new ArrayList<>();
		
		private void floats(float... fields)
		{
			for(float value : fields)
				values.add(Float.floatToRawIntBits(value));
		}
		
		@Override
		public VertexConsumer addVertex(float x, float y, float z)
		{
			floats(x, y, z);
			return this;
		}
		
		@Override
		public VertexConsumer setColor(int color)
		{
			values.add(color);
			return this;
		}
		
		@Override
		public VertexConsumer setNormal(float x, float y, float z)
		{
			floats(x, y, z);
			return this;
		}
		
		@Override
		public VertexConsumer setLineWidth(float width)
		{
			floats(width);
			return this;
		}
	}
	
	private static void legacySolid(PoseStack matrices, VertexConsumer buffer,
		AABB box, int color)
	{
		PoseStack.Pose entry = matrices.last();
		float x1 = (float)box.minX;
		float y1 = (float)box.minY;
		float z1 = (float)box.minZ;
		float x2 = (float)box.maxX;
		float y2 = (float)box.maxY;
		float z2 = (float)box.maxZ;
		
		buffer.addVertex(entry, x1, y1, z1).setColor(color);
		buffer.addVertex(entry, x2, y1, z1).setColor(color);
		buffer.addVertex(entry, x2, y1, z2).setColor(color);
		buffer.addVertex(entry, x1, y1, z2).setColor(color);
		
		buffer.addVertex(entry, x1, y2, z1).setColor(color);
		buffer.addVertex(entry, x1, y2, z2).setColor(color);
		buffer.addVertex(entry, x2, y2, z2).setColor(color);
		buffer.addVertex(entry, x2, y2, z1).setColor(color);
		
		buffer.addVertex(entry, x1, y1, z1).setColor(color);
		buffer.addVertex(entry, x1, y2, z1).setColor(color);
		buffer.addVertex(entry, x2, y2, z1).setColor(color);
		buffer.addVertex(entry, x2, y1, z1).setColor(color);
		
		buffer.addVertex(entry, x2, y1, z1).setColor(color);
		buffer.addVertex(entry, x2, y2, z1).setColor(color);
		buffer.addVertex(entry, x2, y2, z2).setColor(color);
		buffer.addVertex(entry, x2, y1, z2).setColor(color);
		
		buffer.addVertex(entry, x1, y1, z2).setColor(color);
		buffer.addVertex(entry, x2, y1, z2).setColor(color);
		buffer.addVertex(entry, x2, y2, z2).setColor(color);
		buffer.addVertex(entry, x1, y2, z2).setColor(color);
		
		buffer.addVertex(entry, x1, y1, z1).setColor(color);
		buffer.addVertex(entry, x1, y1, z2).setColor(color);
		buffer.addVertex(entry, x1, y2, z2).setColor(color);
		buffer.addVertex(entry, x1, y2, z1).setColor(color);
	}
	
	private static void legacyOutline(PoseStack matrices, VertexConsumer buffer,
		AABB box, int color)
	{
		PoseStack.Pose entry = matrices.last();
		float x1 = (float)box.minX;
		float y1 = (float)box.minY;
		float z1 = (float)box.minZ;
		float x2 = (float)box.maxX;
		float y2 = (float)box.maxY;
		float z2 = (float)box.maxZ;
		
		// bottom lines
		buffer.addVertex(entry, x1, y1, z1).setColor(color)
			.setNormal(entry, 1, 0, 0).setLineWidth(2);
		buffer.addVertex(entry, x2, y1, z1).setColor(color)
			.setNormal(entry, 1, 0, 0).setLineWidth(2);
		buffer.addVertex(entry, x1, y1, z1).setColor(color)
			.setNormal(entry, 0, 0, 1).setLineWidth(2);
		buffer.addVertex(entry, x1, y1, z2).setColor(color)
			.setNormal(entry, 0, 0, 1).setLineWidth(2);
		buffer.addVertex(entry, x2, y1, z1).setColor(color)
			.setNormal(entry, 0, 0, 1).setLineWidth(2);
		buffer.addVertex(entry, x2, y1, z2).setColor(color)
			.setNormal(entry, 0, 0, 1).setLineWidth(2);
		buffer.addVertex(entry, x1, y1, z2).setColor(color)
			.setNormal(entry, 1, 0, 0).setLineWidth(2);
		buffer.addVertex(entry, x2, y1, z2).setColor(color)
			.setNormal(entry, 1, 0, 0).setLineWidth(2);
		
		// top lines
		buffer.addVertex(entry, x1, y2, z1).setColor(color)
			.setNormal(entry, 1, 0, 0).setLineWidth(2);
		buffer.addVertex(entry, x2, y2, z1).setColor(color)
			.setNormal(entry, 1, 0, 0).setLineWidth(2);
		buffer.addVertex(entry, x1, y2, z1).setColor(color)
			.setNormal(entry, 0, 0, 1).setLineWidth(2);
		buffer.addVertex(entry, x1, y2, z2).setColor(color)
			.setNormal(entry, 0, 0, 1).setLineWidth(2);
		buffer.addVertex(entry, x2, y2, z1).setColor(color)
			.setNormal(entry, 0, 0, 1).setLineWidth(2);
		buffer.addVertex(entry, x2, y2, z2).setColor(color)
			.setNormal(entry, 0, 0, 1).setLineWidth(2);
		buffer.addVertex(entry, x1, y2, z2).setColor(color)
			.setNormal(entry, 1, 0, 0).setLineWidth(2);
		buffer.addVertex(entry, x2, y2, z2).setColor(color)
			.setNormal(entry, 1, 0, 0).setLineWidth(2);
		
		// side lines
		buffer.addVertex(entry, x1, y1, z1).setColor(color)
			.setNormal(entry, 0, 1, 0).setLineWidth(2);
		buffer.addVertex(entry, x1, y2, z1).setColor(color)
			.setNormal(entry, 0, 1, 0).setLineWidth(2);
		buffer.addVertex(entry, x2, y1, z1).setColor(color)
			.setNormal(entry, 0, 1, 0).setLineWidth(2);
		buffer.addVertex(entry, x2, y2, z1).setColor(color)
			.setNormal(entry, 0, 1, 0).setLineWidth(2);
		buffer.addVertex(entry, x1, y1, z2).setColor(color)
			.setNormal(entry, 0, 1, 0).setLineWidth(2);
		buffer.addVertex(entry, x1, y2, z2).setColor(color)
			.setNormal(entry, 0, 1, 0).setLineWidth(2);
		buffer.addVertex(entry, x2, y1, z2).setColor(color)
			.setNormal(entry, 0, 1, 0).setLineWidth(2);
		buffer.addVertex(entry, x2, y2, z2).setColor(color)
			.setNormal(entry, 0, 1, 0).setLineWidth(2);
	}
	
}
