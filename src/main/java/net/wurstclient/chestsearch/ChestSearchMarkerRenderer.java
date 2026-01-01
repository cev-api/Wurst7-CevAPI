/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.chestsearch;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstRenderLayers;
import net.wurstclient.util.RenderUtils;

public final class ChestSearchMarkerRenderer
{
	private ChestSearchMarkerRenderer()
	{}
	
	public static void drawMarker(PoseStack matrices, AABB box, int color,
		double thickness, boolean depthTest)
	{
		if(matrices == null || box == null)
			return;
		
		MultiBufferSource.BufferSource vcp = RenderUtils.getVCP();
		RenderType layer = WurstRenderLayers.getLines(depthTest, thickness);
		VertexConsumer buffer = vcp.getBuffer(layer);
		drawMarker(matrices.last(), buffer,
			RenderUtils.getCameraPos().reverse(), box, color);
		vcp.endBatch(layer);
	}
	
	public static void drawMarkers(PoseStack matrices, List<AABB> boxes,
		int color, double thickness, boolean depthTest)
	{
		if(matrices == null || boxes == null || boxes.isEmpty())
			return;
		
		MultiBufferSource.BufferSource vcp = RenderUtils.getVCP();
		RenderType layer = WurstRenderLayers.getLines(depthTest, thickness);
		VertexConsumer buffer = vcp.getBuffer(layer);
		PoseStack.Pose entry = matrices.last();
		Vec3 offset = RenderUtils.getCameraPos().reverse();
		for(AABB box : boxes)
		{
			if(box == null)
				continue;
			drawMarker(entry, buffer, offset, box, color);
		}
		vcp.endBatch(layer);
	}
	
	private static void drawMarker(PoseStack.Pose entry, VertexConsumer buffer,
		Vec3 offset, AABB box, int color)
	{
		double minX = box.minX;
		double maxX = box.maxX;
		double minY = box.minY;
		double maxY = box.maxY;
		double minZ = box.minZ;
		double maxZ = box.maxZ;
		double cx = (minX + maxX) / 2.0;
		double cy = (minY + maxY) / 2.0;
		double cz = (minZ + maxZ) / 2.0;
		
		double halfX = (maxX - minX) / 2.0;
		double halfY = (maxY - minY) / 2.0;
		double halfZ = (maxZ - minZ) / 2.0;
		
		Vec3 t1 = new Vec3(cx - halfX, maxY, cz).add(offset);
		Vec3 t2 = new Vec3(cx + halfX, maxY, cz).add(offset);
		Vec3 t3 = new Vec3(cx, maxY, cz - halfZ).add(offset);
		Vec3 t4 = new Vec3(cx, maxY, cz + halfZ).add(offset);
		RenderUtils.drawLine(entry, buffer, (float)t1.x, (float)t1.y,
			(float)t1.z, (float)t2.x, (float)t2.y, (float)t2.z, color);
		RenderUtils.drawLine(entry, buffer, (float)t3.x, (float)t3.y,
			(float)t3.z, (float)t4.x, (float)t4.y, (float)t4.z, color);
		
		Vec3 b1 = new Vec3(cx - halfX, minY, cz).add(offset);
		Vec3 b2 = new Vec3(cx + halfX, minY, cz).add(offset);
		Vec3 b3 = new Vec3(cx, minY, cz - halfZ).add(offset);
		Vec3 b4 = new Vec3(cx, minY, cz + halfZ).add(offset);
		RenderUtils.drawLine(entry, buffer, (float)b1.x, (float)b1.y,
			(float)b1.z, (float)b2.x, (float)b2.y, (float)b2.z, color);
		RenderUtils.drawLine(entry, buffer, (float)b3.x, (float)b3.y,
			(float)b3.z, (float)b4.x, (float)b4.y, (float)b4.z, color);
		
		Vec3 n1 = new Vec3(cx - halfX, cy, minZ).add(offset);
		Vec3 n2 = new Vec3(cx + halfX, cy, minZ).add(offset);
		Vec3 n3 = new Vec3(cx, cy - halfY, minZ).add(offset);
		Vec3 n4 = new Vec3(cx, cy + halfY, minZ).add(offset);
		RenderUtils.drawLine(entry, buffer, (float)n1.x, (float)n1.y,
			(float)n1.z, (float)n2.x, (float)n2.y, (float)n2.z, color);
		RenderUtils.drawLine(entry, buffer, (float)n3.x, (float)n3.y,
			(float)n3.z, (float)n4.x, (float)n4.y, (float)n4.z, color);
		
		Vec3 s1 = new Vec3(cx - halfX, cy, maxZ).add(offset);
		Vec3 s2 = new Vec3(cx + halfX, cy, maxZ).add(offset);
		Vec3 s3 = new Vec3(cx, cy - halfY, maxZ).add(offset);
		Vec3 s4 = new Vec3(cx, cy + halfY, maxZ).add(offset);
		RenderUtils.drawLine(entry, buffer, (float)s1.x, (float)s1.y,
			(float)s1.z, (float)s2.x, (float)s2.y, (float)s2.z, color);
		RenderUtils.drawLine(entry, buffer, (float)s3.x, (float)s3.y,
			(float)s3.z, (float)s4.x, (float)s4.y, (float)s4.z, color);
		
		Vec3 w1 = new Vec3(minX, cy, cz - halfZ).add(offset);
		Vec3 w2 = new Vec3(minX, cy, cz + halfZ).add(offset);
		Vec3 w3 = new Vec3(minX, cy - halfY, cz).add(offset);
		Vec3 w4 = new Vec3(minX, cy + halfY, cz).add(offset);
		RenderUtils.drawLine(entry, buffer, (float)w1.x, (float)w1.y,
			(float)w1.z, (float)w2.x, (float)w2.y, (float)w2.z, color);
		RenderUtils.drawLine(entry, buffer, (float)w3.x, (float)w3.y,
			(float)w3.z, (float)w4.x, (float)w4.y, (float)w4.z, color);
		
		Vec3 e1 = new Vec3(maxX, cy, cz - halfZ).add(offset);
		Vec3 e2 = new Vec3(maxX, cy, cz + halfZ).add(offset);
		Vec3 e3 = new Vec3(maxX, cy - halfY, cz).add(offset);
		Vec3 e4 = new Vec3(maxX, cy + halfY, cz).add(offset);
		RenderUtils.drawLine(entry, buffer, (float)e1.x, (float)e1.y,
			(float)e1.z, (float)e2.x, (float)e2.y, (float)e2.z, color);
		RenderUtils.drawLine(entry, buffer, (float)e3.x, (float)e3.y,
			(float)e3.z, (float)e4.x, (float)e4.y, (float)e4.z, color);
	}
}
