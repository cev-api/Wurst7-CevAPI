/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import org.joml.Vector3f;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.AABB;

/** The original box vertices, with each corner transformed only once. */
public final class EspBoxGeometry
{
	private static final int[] FACES = {0, 1, 5, 4, 2, 6, 7, 3, 0, 2, 3, 1, 1,
		3, 7, 5, 4, 5, 7, 6, 0, 4, 6, 2};
	private static final int[] EDGES = {0, 1, 0, 4, 1, 5, 4, 5, 2, 3, 2, 6, 3,
		7, 6, 7, 0, 2, 1, 3, 4, 6, 5, 7};
	private static final int[] AXES = {0, 2, 2, 0, 0, 2, 2, 0, 1, 1, 1, 1};
	private static final ThreadLocal<EspBoxGeometry> SCRATCH =
		ThreadLocal.withInitial(EspBoxGeometry::new);
	private final Vector3f[] corners = vectors(8);
	private final Vector3f[] normals = vectors(3);
	
	private static Vector3f[] vectors(int count)
	{
		Vector3f[] result = new Vector3f[count];
		for(int i = 0; i < count; i++)
			result[i] = new Vector3f();
		return result;
	}
	
	public static void solid(Pose pose, VertexConsumer target, AABB box,
		int color)
	{
		EspBoxGeometry scratch = SCRATCH.get();
		target = scratch.prepare(pose, target, box);
		for(int index : FACES)
		{
			Vector3f p = scratch.corners[index];
			target.addVertex(p.x, p.y, p.z).setColor(color);
		}
	}
	
	public static void outline(Pose pose, VertexConsumer target, AABB box,
		int color)
	{
		EspBoxGeometry scratch = SCRATCH.get();
		target = scratch.prepare(pose, target, box);
		pose.transformNormal(1, 0, 0, scratch.normals[0]);
		pose.transformNormal(0, 1, 0, scratch.normals[1]);
		pose.transformNormal(0, 0, 1, scratch.normals[2]);
		for(int i = 0; i < EDGES.length; i++)
		{
			Vector3f p = scratch.corners[EDGES[i]];
			Vector3f n = scratch.normals[AXES[i / 2]];
			target.addVertex(p.x, p.y, p.z).setColor(color)
				.setNormal(n.x, n.y, n.z).setLineWidth(2);
		}
	}
	
	private VertexConsumer prepare(Pose pose, VertexConsumer target, AABB box)
	{
		float x1 = (float)box.minX, y1 = (float)box.minY, z1 = (float)box.minZ;
		float x2 = (float)box.maxX, y2 = (float)box.maxY, z2 = (float)box.maxZ;
		for(int i = 0; i < corners.length; i++)
			pose.pose().transformPosition((i & 1) == 0 ? x1 : x2,
				(i & 2) == 0 ? y1 : y2, (i & 4) == 0 ? z1 : z2, corners[i]);
		if(!(target instanceof CullingVertexConsumer culling))
			return target;
		float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
		float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
		for(Vector3f p : corners)
		{
			minX = Math.min(minX, p.x);
			minY = Math.min(minY, p.y);
			minZ = Math.min(minZ, p.z);
			maxX = Math.max(maxX, p.x);
			maxY = Math.max(maxY, p.y);
			maxZ = Math.max(maxZ, p.z);
		}
		return culling.forBounds(minX, minY, minZ, maxX, maxY, maxZ);
	}
}
