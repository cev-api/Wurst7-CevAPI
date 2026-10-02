/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.joml.Matrix4f;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.render.esp.ItemTagClusterer.Cluster;

/**
 * CPU-only checks for grouping, visible tag budgets and camera-relative ranges.
 */
public final class EspPerformanceRegressionTest
{
	private static int checks;
	
	public static void main(String[] args)
	{
		screenTags();
		labelRange();
		clusters();
		meshBuildScope();
		groupCache();
		framePool();
		projection();
		check(
			DiscardingVertexConsumer.INSTANCE.addVertex(1, 2, 3)
				.setColor(0xFFFFFFFF).setUv(0, 1).setUv1(2, 3).setUv2(4, 5)
				.setUv3(6, 7).setNormal(0, 1, 0)
				.setLineWidth(2) == DiscardingVertexConsumer.INSTANCE,
			"suppressed vertices stay chainable");
		System.out
			.println("ESP performance regression checks passed: " + checks);
	}
	
	private static void screenTags()
	{
		ScreenTagLayout layout = new ScreenTagLayout();
		layout.reset(2, true);
		check(layout.reserve(0, 0, 20, 20), "first visible tag");
		check(!layout.reserve(10, 10, 30, 30), "overlapping tag suppressed");
		check(!layout.isFull(), "rejection does not consume tag budget");
		check(layout.reserve(20, 0, 40, 20), "touching edges do not overlap");
		check(layout.isFull(), "visible tags consume budget");
		check(!layout.reserve(100, 100, 120, 120), "budget stops further tags");
		layout.reset(0, false);
		for(int i = 0; i < 1500; i++)
			check(layout.reserve(0, 0, 20, 20),
				"disabled filters preserve all tags");
		layout.reset(0, true);
		check(layout.reserve(-20, -20, 20, 20),
			"frame reset clears old bounds");
		check(!layout.reserve(-5, -5, 5, 5), "fully enclosed tag overlaps");
		check(!layout.reserve(-30, -30, 30, 30), "enclosing tag overlaps");
		check(layout.reserve(200, 200, 400, 250), "large text tag accepted");
		check(!layout.reserve(390, 240, 410, 260),
			"full tag width and height used");
	}
	
	private static void projection()
	{
		EspScreenProjector projector = new EspScreenProjector();
		Random random = new Random(2603);
		for(int frame = 0; frame < 100; frame++)
		{
			Vec3 camera = new Vec3(random.nextDouble() * 6e7 - 3e7,
				random.nextDouble() * 512 - 128,
				random.nextDouble() * 6e7 - 3e7);
			float yaw = random.nextFloat() * 720 - 360;
			float pitch = random.nextFloat() * 180 - 90;
			Matrix4f matrix = new Matrix4f()
				.perspective(.2F + random.nextFloat() * 2.5F, 1.9F, .05F, 1024)
				.rotateX((float)Math.toRadians(pitch))
				.rotateY((float)Math.toRadians(yaw));
			projector.reset(matrix, camera, yaw, pitch);
			check(!projector.isBehindCamera(camera),
				"tag exactly at camera retains original visibility");
			double yawRad = Math.toRadians(yaw);
			double pitchRad = Math.toRadians(pitch);
			Vec3 forward = new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad),
				-Math.sin(pitchRad), Math.cos(yawRad) * Math.cos(pitchRad));
			for(int tag = 0; tag < 100; tag++)
			{
				Vec3 position = camera.add(random.nextDouble() * 256 - 128,
					random.nextDouble() * 256 - 128,
					random.nextDouble() * 256 - 128);
				Vec3 relative = position.subtract(camera);
				Vec3 expected = new Vec3(new Matrix4f(matrix)
					.transformProject(relative.toVector3f()));
				check(projector.project(position).equals(expected),
					"bit-identical vanilla screen projection");
				check(
					projector.isBehindCamera(
						position) == (relative.dot(forward) <= 0),
					"same behind-camera result as original tag rendering");
			}
		}
	}
	
	private static void labelRange()
	{
		Matrix4f pose = new Matrix4f().rotateY(1).translate(0, 0, -32)
			.rotateX(.7F).scale(.025F, -.025F, .025F);
		check(!EspRenderPolicy.outsideLabelRange(pose, 33),
			"near label retained");
		check(EspRenderPolicy.outsideLabelRange(pose, 31),
			"distant label suppressed");
		check(!EspRenderPolicy.outsideLabelRange(pose, 0), "range disabled");
		check(
			!EspRenderPolicy
				.outsideLabelRange(new Matrix4f().translate(0, 0, 32), 32),
			"exact range boundary retained");
		check(
			EspRenderPolicy
				.outsideLabelRange(new Matrix4f().translate(-40, 0, 0), 32),
			"range applies in every camera direction");
	}
	
	private static void meshBuildScope()
	{
		check(!EspRenderPolicy.isBuildingMesh(), "ordinary draw scope");
		EspRenderPolicy.beginMeshBuild();
		try
		{
			check(EspRenderPolicy.isBuildingMesh(),
				"cached mesh construction scope");
			check(!EspRenderPolicy.skipFill(),
				"cached geometry is never suppressed");
			EspRenderPolicy.beginMeshBuild();
			try
			{
				throw new IllegalStateException("simulated mesh build failure");
			}finally
			{
				EspRenderPolicy.endMeshBuild();
			}
		}catch(IllegalStateException expected)
		{
			check(EspRenderPolicy.isBuildingMesh(),
				"nested build restores outer scope");
		}finally
		{
			EspRenderPolicy.endMeshBuild();
		}
		check(!EspRenderPolicy.isBuildingMesh(),
			"failed builds restore runtime filtering");
	}
	
	private static final class MovingItem
	{
		int kind, count;
		Vec3 position, label;
		
		MovingItem(int kind, int count, Vec3 position)
		{
			this.kind = kind;
			this.count = count;
			this.position = position;
			label = position;
		}
	}
	
	private static void groupCache()
	{
		var cache = new ItemTagClusterer.Cache<MovingItem, Integer>();
		MovingItem a = new MovingItem(1, 2, new Vec3(0, 0, 0));
		MovingItem b = new MovingItem(1, 3, new Vec3(1, 0, 0));
		List<MovingItem> items = List.of(a, b);
		var first = cache.groups(items, i -> i.kind, i -> i.position, 2.5);
		check(
			first == cache.groups(new ArrayList<>(items), i -> i.kind,
				i -> i.position, 2.5),
			"unchanged membership reused across frames");
		a.count = 8;
		b.label = new Vec3(2, 0, 0);
		var live = ItemTagClusterer.evaluate(first, i -> i.label, i -> i.count);
		check(live.getFirst().count() == 11,
			"cached groups use live stack counts");
		check(live.getFirst().position().equals(new Vec3(1, 0, 0)),
			"cached groups preserve per-frame interpolation");
		b.position = new Vec3(10, 0, 0);
		var moved = cache.groups(items, i -> i.kind, i -> i.position, 2.5);
		check(moved != first && moved.size() == 2,
			"movement invalidates membership");
		b.position = a.position;
		b.kind = 2;
		check(
			cache.groups(items, i -> i.kind, i -> i.position, 2.5).size() == 2,
			"item-type change invalidates membership");
		b.kind = 1;
		check(
			cache.groups(items, i -> i.kind, i -> i.position, 2.5).size() == 1,
			"item-type change can rejoin groups");
		b.position = new Vec3(1, 0, 0);
		check(cache.groups(items, i -> i.kind, i -> i.position, .5).size() == 2,
			"radius change invalidates membership");
		var reordered =
			cache.groups(List.of(b, a), i -> i.kind, i -> i.position, 2.5);
		check(reordered.getFirst().seed() == b,
			"input order preserves original seed priority");
		check(
			cache.groups(List.of(a), i -> i.kind, i -> i.position, 2.5)
				.getFirst().members().size() == 1,
			"removed item leaves cached groups");
		cache.clear();
		check(cache.groups(List.of(), i -> i.kind, i -> i.position, 2.5)
			.isEmpty(), "cache clear releases old items");
	}
	
	private static final class Resource
	{
		final boolean retained;
		int frames, closes;
		
		Resource(boolean retained)
		{
			this.retained = retained;
		}
	}
	
	private static void framePool()
	{
		var pool = new FrameResourcePool<Resource>(2, Resource::new,
			r -> r.frames++, r -> r.closes++);
		Resource a = pool.acquire(), b = pool.acquire(),
			overflow = pool.acquire();
		check(a != b && b != overflow && a != overflow,
			"nested callers never alias a live vertex buffer");
		check(a.retained && b.retained && !overflow.retained,
			"native resource retention is bounded");
		pool.endFrame();
		check(a.frames == 1 && b.frames == 1 && overflow.frames == 0,
			"only retained resources receive frame fence recycling");
		check(pool.acquire() == a && pool.acquire() == b,
			"next frame reuses buffers instead of reallocating");
		pool.endFrame();
		pool.clear();
		check(a.closes == 1 && b.closes == 1,
			"disabling or disconnecting releases retained resources");
		check(pool.acquire() != a, "cleared pools start with a fresh buffer");
		pool.endFrame();
		pool.clear();
	}
	
	private record Item(int kind, int count, Vec3 position)
	{}
	
	private static void clusters()
	{
		compare(List.of(), 2.5);
		compare(List.of(new Item(0, 2, new Vec3(-.1, 0, 0)),
			new Item(0, 3, new Vec3(.1, 0, 0)),
			new Item(1, 4, new Vec3(.1, 0, 0))), 2.5);
		// Radius grouping is seed-based, not a transitive connected component.
		compare(List.of(new Item(0, 2, new Vec3(0, 0, 0)),
			new Item(0, 3, new Vec3(2, 0, 0)),
			new Item(0, 4, new Vec3(4, 0, 0))), 2.5);
		compare(List.of(new Item(0, 2, new Vec3(0, 0, 0)),
			new Item(0, 3, new Vec3(0, 3, 4))), 5);
		Random random = new Random(74126);
		for(int trial = 0; trial < 100; trial++)
		{
			ArrayList<Item> items = new ArrayList<>();
			for(int i = 0; i < 300; i++)
				items.add(new Item(random.nextInt(5), 1 + random.nextInt(64),
					new Vec3(random.nextDouble() * 20 - 10,
						random.nextDouble() * 20 - 10,
						random.nextDouble() * 20 - 10)));
			compare(items, .5 + random.nextDouble() * 12);
		}
		ArrayList<Item> pile = new ArrayList<>();
		for(int i = 0; i < 10000; i++)
			pile.add(new Item(i % 5, 1, new Vec3(i % 4 * .1, 0, 0)));
		compare(pile, 2.5);
		try
		{
			group(pile, 0);
			throw new AssertionError("invalid radius accepted");
		}catch(IllegalArgumentException expected)
		{
			checks++;
		}
	}
	
	private static Vec3 labelPosition(Item item)
	{
		return item.position.add(0, .7, 0);
	}
	
	private static List<Cluster<Item>> group(List<Item> items, double radius)
	{
		return ItemTagClusterer.cluster(items, Item::kind, Item::position,
			EspPerformanceRegressionTest::labelPosition, Item::count, radius);
	}
	
	private static void compare(List<Item> items, double radius)
	{
		List<Cluster<Item>> actual = group(items, radius);
		ArrayList<Cluster<Item>> expected = new ArrayList<>();
		boolean[] used = new boolean[items.size()];
		for(int i = 0; i < items.size(); i++)
		{
			if(used[i])
				continue;
			used[i] = true;
			Item seed = items.get(i);
			int count = seed.count, size = 1;
			Vec3 center = labelPosition(seed);
			for(int j = 0; j < items.size(); j++)
			{
				Item other = items.get(j);
				if(used[j] || other.kind != seed.kind || seed.position
					.distanceToSqr(other.position) > radius * radius)
					continue;
				used[j] = true;
				count += other.count;
				size++;
				center = center.add(labelPosition(other));
			}
			expected.add(new Cluster<>(seed, count, center.scale(1.0 / size)));
		}
		check(actual.size() == expected.size(),
			"same cluster count as original algorithm");
		for(int i = 0; i < actual.size(); i++)
		{
			Cluster<Item> a = actual.get(i), e = expected.get(i);
			check(a.seed() == e.seed(), "same seed and cluster order");
			check(a.count() == e.count(), "same stack count");
			check(a.position().equals(e.position()),
				"bit-identical interpolated label center");
		}
	}
	
	private static void check(boolean value, String message)
	{
		checks++;
		if(!value)
			throw new AssertionError(message);
	}
}
