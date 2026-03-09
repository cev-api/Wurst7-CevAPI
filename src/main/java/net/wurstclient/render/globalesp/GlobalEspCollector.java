/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.globalesp;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.util.EasyVertexBuffer;

public final class GlobalEspCollector
{
	private final ArrayList<LinePrimitive> depthLines = new ArrayList<>();
	private final ArrayList<LinePrimitive> xrayLines = new ArrayList<>();
	private final ArrayList<SolidBoxPrimitive> depthSolidBoxes =
		new ArrayList<>();
	private final ArrayList<SolidBoxPrimitive> xraySolidBoxes =
		new ArrayList<>();
	private final ArrayList<MeshPrimitive> meshes = new ArrayList<>();
	private final ArrayList<TransformState> transforms = new ArrayList<>();
	
	private final ArrayDeque<LinePrimitive> linePool = new ArrayDeque<>();
	private final ArrayDeque<SolidBoxPrimitive> solidBoxPool =
		new ArrayDeque<>();
	private final ArrayDeque<MeshPrimitive> meshPool = new ArrayDeque<>();
	private final ArrayDeque<TransformState> transformPool = new ArrayDeque<>();
	
	public void clear()
	{
		recycleLines(depthLines);
		recycleLines(xrayLines);
		recycleSolidBoxes(depthSolidBoxes);
		recycleSolidBoxes(xraySolidBoxes);
		recycleMeshes();
		recycleTransforms();
	}
	
	public boolean isEmpty()
	{
		return depthLines.isEmpty() && xrayLines.isEmpty()
			&& depthSolidBoxes.isEmpty() && xraySolidBoxes.isEmpty()
			&& meshes.isEmpty();
	}
	
	public void submitLine(PoseStack.Pose entry, Vec3 start, Vec3 end,
		int color, boolean depthTest, float lineWidth)
	{
		submitLine(entry, (float)start.x, (float)start.y, (float)start.z,
			(float)end.x, (float)end.y, (float)end.z, color, depthTest,
			lineWidth);
	}
	
	public void submitLine(PoseStack.Pose entry, float x1, float y1, float z1,
		float x2, float y2, float z2, int color, boolean depthTest,
		float lineWidth)
	{
		LinePrimitive line = takeLine(depthTest);
		line.pose.set(entry.pose());
		line.normal.set(entry.normal());
		line.x1 = x1;
		line.y1 = y1;
		line.z1 = z1;
		line.x2 = x2;
		line.y2 = y2;
		line.z2 = z2;
		line.color = color;
		line.lineWidth = lineWidth;
	}
	
	public void submitCurvedLine(PoseStack.Pose entry, List<Vec3> points,
		int color, boolean depthTest, float lineWidth)
	{
		if(points == null || points.size() < 2)
			return;
		
		for(int i = 1; i < points.size(); i++)
			submitLine(entry, points.get(i - 1), points.get(i), color,
				depthTest, lineWidth);
	}
	
	public void submitSolidBox(PoseStack.Pose entry, AABB box, int color,
		boolean depthTest)
	{
		submitSolidBox(entry, box, color, depthTest, 2F);
	}
	
	public void submitSolidBox(PoseStack.Pose entry, AABB box, int color,
		boolean depthTest, float lineWidth)
	{
		int transformIndex = storeTransform(entry);
		submitSolidBox(transformIndex, (float)box.minX, (float)box.minY,
			(float)box.minZ, (float)box.maxX, (float)box.maxY, (float)box.maxZ,
			color, depthTest, lineWidth);
	}
	
	public void submitSolidBoxes(PoseStack.Pose entry, List<AABB> boxes,
		double offsetX, double offsetY, double offsetZ, int color,
		boolean depthTest, float lineWidth)
	{
		if(boxes == null || boxes.isEmpty())
			return;
		
		int transformIndex = storeTransform(entry);
		float ox = (float)offsetX;
		float oy = (float)offsetY;
		float oz = (float)offsetZ;
		for(AABB box : boxes)
			submitSolidBox(transformIndex, (float)box.minX + ox,
				(float)box.minY + oy, (float)box.minZ + oz,
				(float)box.maxX + ox, (float)box.maxY + oy,
				(float)box.maxZ + oz, color, depthTest, lineWidth);
	}
	
	public void submitOutlinedBox(PoseStack.Pose entry, AABB box, int color,
		boolean depthTest, float lineWidth)
	{
		float x1 = (float)box.minX;
		float y1 = (float)box.minY;
		float z1 = (float)box.minZ;
		float x2 = (float)box.maxX;
		float y2 = (float)box.maxY;
		float z2 = (float)box.maxZ;
		
		// bottom
		submitLine(entry, x1, y1, z1, x2, y1, z1, color, depthTest, lineWidth);
		submitLine(entry, x2, y1, z1, x2, y1, z2, color, depthTest, lineWidth);
		submitLine(entry, x2, y1, z2, x1, y1, z2, color, depthTest, lineWidth);
		submitLine(entry, x1, y1, z2, x1, y1, z1, color, depthTest, lineWidth);
		
		// top
		submitLine(entry, x1, y2, z1, x2, y2, z1, color, depthTest, lineWidth);
		submitLine(entry, x2, y2, z1, x2, y2, z2, color, depthTest, lineWidth);
		submitLine(entry, x2, y2, z2, x1, y2, z2, color, depthTest, lineWidth);
		submitLine(entry, x1, y2, z2, x1, y2, z1, color, depthTest, lineWidth);
		
		// vertical
		submitLine(entry, x1, y1, z1, x1, y2, z1, color, depthTest, lineWidth);
		submitLine(entry, x2, y1, z1, x2, y2, z1, color, depthTest, lineWidth);
		submitLine(entry, x1, y1, z2, x1, y2, z2, color, depthTest, lineWidth);
		submitLine(entry, x2, y1, z2, x2, y2, z2, color, depthTest, lineWidth);
	}
	
	public void submitCrossBox(PoseStack.Pose entry, AABB box, int color,
		boolean depthTest, float lineWidth)
	{
		float x1 = (float)box.minX;
		float y1 = (float)box.minY;
		float z1 = (float)box.minZ;
		float x2 = (float)box.maxX;
		float y2 = (float)box.maxY;
		float z2 = (float)box.maxZ;
		
		// back, left, front, right, top, bottom X
		submitLine(entry, x1, y1, z1, x2, y2, z1, color, depthTest, lineWidth);
		submitLine(entry, x2, y1, z1, x1, y2, z1, color, depthTest, lineWidth);
		submitLine(entry, x2, y1, z1, x2, y2, z2, color, depthTest, lineWidth);
		submitLine(entry, x2, y1, z2, x2, y2, z1, color, depthTest, lineWidth);
		submitLine(entry, x2, y1, z2, x1, y2, z2, color, depthTest, lineWidth);
		submitLine(entry, x1, y1, z2, x2, y2, z2, color, depthTest, lineWidth);
		submitLine(entry, x1, y1, z2, x1, y2, z1, color, depthTest, lineWidth);
		submitLine(entry, x1, y1, z1, x1, y2, z2, color, depthTest, lineWidth);
		submitLine(entry, x1, y2, z2, x2, y2, z1, color, depthTest, lineWidth);
		submitLine(entry, x1, y2, z1, x2, y2, z2, color, depthTest, lineWidth);
		submitLine(entry, x2, y1, z1, x1, y1, z2, color, depthTest, lineWidth);
		submitLine(entry, x1, y1, z1, x2, y1, z2, color, depthTest, lineWidth);
	}
	
	public void submitNode(PoseStack.Pose entry, AABB box, int color,
		boolean depthTest, float lineWidth)
	{
		float x1 = (float)box.minX;
		float y1 = (float)box.minY;
		float z1 = (float)box.minZ;
		float x2 = (float)box.maxX;
		float y2 = (float)box.maxY;
		float z2 = (float)box.maxZ;
		float x3 = (x1 + x2) / 2F;
		float y3 = (y1 + y2) / 2F;
		float z3 = (z1 + z2) / 2F;
		
		// middle
		submitLine(entry, x3, y3, z2, x1, y3, z3, color, depthTest, lineWidth);
		submitLine(entry, x1, y3, z3, x3, y3, z1, color, depthTest, lineWidth);
		submitLine(entry, x3, y3, z1, x2, y3, z3, color, depthTest, lineWidth);
		submitLine(entry, x2, y3, z3, x3, y3, z2, color, depthTest, lineWidth);
		
		// top
		submitLine(entry, x3, y2, z3, x2, y3, z3, color, depthTest, lineWidth);
		submitLine(entry, x3, y2, z3, x1, y3, z3, color, depthTest, lineWidth);
		submitLine(entry, x3, y2, z3, x3, y3, z1, color, depthTest, lineWidth);
		submitLine(entry, x3, y2, z3, x3, y3, z2, color, depthTest, lineWidth);
		
		// bottom
		submitLine(entry, x3, y1, z3, x2, y3, z3, color, depthTest, lineWidth);
		submitLine(entry, x3, y1, z3, x1, y3, z3, color, depthTest, lineWidth);
		submitLine(entry, x3, y1, z3, x3, y3, z1, color, depthTest, lineWidth);
		submitLine(entry, x3, y1, z3, x3, y3, z2, color, depthTest, lineWidth);
	}
	
	public void submitArrow(PoseStack.Pose entry, Vec3 from, Vec3 to, int color,
		boolean depthTest, float lineWidth, float headSize)
	{
		Vec3 dir = to.subtract(from);
		double lenSq = dir.lengthSqr();
		if(lenSq < 1e-10)
			return;
		
		Vec3 n = dir.scale(1.0 / Math.sqrt(lenSq));
		Vec3 up = Math.abs(n.y) > 0.99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
		Vec3 right = n.cross(up);
		if(right.lengthSqr() < 1e-10)
			right = new Vec3(1, 0, 0);
		right = right.normalize();
		Vec3 orthoUp = right.cross(n).normalize();
		
		double headLength = Math.max(headSize * 2.0, 0.05);
		double headWidth = Math.max(headSize, 0.025);
		Vec3 headBase = to.subtract(n.scale(headLength));
		Vec3 p1 = headBase.add(right.scale(headWidth));
		Vec3 p2 = headBase.subtract(right.scale(headWidth));
		Vec3 p3 = headBase.add(orthoUp.scale(headWidth));
		Vec3 p4 = headBase.subtract(orthoUp.scale(headWidth));
		
		submitLine(entry, from, to, color, depthTest, lineWidth);
		submitLine(entry, to, p1, color, depthTest, lineWidth);
		submitLine(entry, to, p2, color, depthTest, lineWidth);
		submitLine(entry, to, p3, color, depthTest, lineWidth);
		submitLine(entry, to, p4, color, depthTest, lineWidth);
	}
	
	public void submitMeshDraw(PoseStack.Pose entry, EasyVertexBuffer buffer,
		RenderType layer, float red, float green, float blue, float alpha)
	{
		if(buffer == null || layer == null)
			return;
		
		MeshPrimitive mesh = takeMesh();
		mesh.transformIndex = storeTransform(entry);
		mesh.buffer = buffer;
		mesh.layer = layer;
		mesh.red = red;
		mesh.green = green;
		mesh.blue = blue;
		mesh.alpha = alpha;
	}
	
	List<LinePrimitive> getDepthLines()
	{
		return depthLines;
	}
	
	List<LinePrimitive> getXrayLines()
	{
		return xrayLines;
	}
	
	List<SolidBoxPrimitive> getDepthSolidBoxes()
	{
		return depthSolidBoxes;
	}
	
	List<SolidBoxPrimitive> getXraySolidBoxes()
	{
		return xraySolidBoxes;
	}
	
	List<MeshPrimitive> getMeshes()
	{
		return meshes;
	}
	
	TransformState getTransform(int index)
	{
		return transforms.get(index);
	}
	
	private LinePrimitive takeLine(boolean depthTest)
	{
		LinePrimitive line = linePool.pollFirst();
		if(line == null)
			line = new LinePrimitive();
		
		if(depthTest)
			depthLines.add(line);
		else
			xrayLines.add(line);
		
		return line;
	}
	
	private SolidBoxPrimitive takeSolidBox(boolean depthTest)
	{
		SolidBoxPrimitive box = solidBoxPool.pollFirst();
		if(box == null)
			box = new SolidBoxPrimitive();
		
		if(depthTest)
			depthSolidBoxes.add(box);
		else
			xraySolidBoxes.add(box);
		
		return box;
	}
	
	private MeshPrimitive takeMesh()
	{
		MeshPrimitive mesh = meshPool.pollFirst();
		if(mesh == null)
			mesh = new MeshPrimitive();
		
		meshes.add(mesh);
		return mesh;
	}
	
	private int storeTransform(PoseStack.Pose entry)
	{
		TransformState transform = transformPool.pollFirst();
		if(transform == null)
			transform = new TransformState();
		
		transform.pose.set(entry.pose());
		transform.normal.set(entry.normal());
		transforms.add(transform);
		return transforms.size() - 1;
	}
	
	private void submitSolidBox(int transformIndex, float minX, float minY,
		float minZ, float maxX, float maxY, float maxZ, int color,
		boolean depthTest, float lineWidth)
	{
		SolidBoxPrimitive solidBox = takeSolidBox(depthTest);
		solidBox.transformIndex = transformIndex;
		solidBox.minX = minX;
		solidBox.minY = minY;
		solidBox.minZ = minZ;
		solidBox.maxX = maxX;
		solidBox.maxY = maxY;
		solidBox.maxZ = maxZ;
		solidBox.color = color;
		solidBox.lineWidth = lineWidth;
	}
	
	private void recycleLines(ArrayList<LinePrimitive> lines)
	{
		linePool.addAll(lines);
		lines.clear();
	}
	
	private void recycleSolidBoxes(ArrayList<SolidBoxPrimitive> boxes)
	{
		solidBoxPool.addAll(boxes);
		boxes.clear();
	}
	
	private void recycleMeshes()
	{
		for(MeshPrimitive mesh : meshes)
		{
			mesh.buffer = null;
			mesh.layer = null;
			meshPool.addLast(mesh);
		}
		
		meshes.clear();
	}
	
	private void recycleTransforms()
	{
		transformPool.addAll(transforms);
		transforms.clear();
	}
	
	static final class LinePrimitive
	{
		final Matrix4f pose = new Matrix4f();
		final Matrix3f normal = new Matrix3f();
		float x1;
		float y1;
		float z1;
		float x2;
		float y2;
		float z2;
		int color;
		float lineWidth;
	}
	
	static final class SolidBoxPrimitive
	{
		int transformIndex;
		float minX;
		float minY;
		float minZ;
		float maxX;
		float maxY;
		float maxZ;
		int color;
		float lineWidth;
	}
	
	static final class MeshPrimitive
	{
		int transformIndex;
		EasyVertexBuffer buffer;
		RenderType layer;
		float red;
		float green;
		float blue;
		float alpha;
	}
	
	static final class TransformState
	{
		final Matrix4f pose = new Matrix4f();
		final Matrix3f normal = new Matrix3f();
	}
}
