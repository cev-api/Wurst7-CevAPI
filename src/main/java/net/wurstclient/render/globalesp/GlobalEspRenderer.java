/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.globalesp;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.joml.Vector4f;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.rendertype.OutputTarget;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.util.Mth;
import net.wurstclient.WurstClient;
import net.wurstclient.WurstRenderLayers;
import net.wurstclient.util.EasyVertexBuffer;
import net.wurstclient.util.RenderUtils;

public final class GlobalEspRenderer
{
	private static final float MIN_LINE_WIDTH = 0.5F;
	private static final float MAX_LINE_WIDTH = 20F;
	
	private int targetWidth = -1;
	private int targetHeight = -1;
	private boolean resourcesAllocated;
	private final ArrayList<MeshDrawCall> preparedMeshDraws = new ArrayList<>();
	
	public void render(PoseStack matrices, GlobalEspCollector collector)
	{
		if(collector == null || collector.isEmpty())
			return;
		
		ensureTargetsForWindow();
		
		runSilhouettePass(matrices, collector);
		runCompositePass();
	}
	
	public void cleanup()
	{
		freeTargets();
	}
	
	private void ensureTargetsForWindow()
	{
		if(WurstClient.MC == null || WurstClient.MC.getWindow() == null)
			return;
		
		int width = WurstClient.MC.getWindow().getWidth();
		int height = WurstClient.MC.getWindow().getHeight();
		if(width <= 0 || height <= 0)
			return;
		
		if(!resourcesAllocated || width != targetWidth
			|| height != targetHeight)
		{
			freeTargets();
			targetWidth = width;
			targetHeight = height;
			resourcesAllocated = true;
		}
	}
	
	private void freeTargets()
	{
		targetWidth = -1;
		targetHeight = -1;
		resourcesAllocated = false;
	}
	
	private void runSilhouettePass(PoseStack matrices,
		GlobalEspCollector collector)
	{
		// Current implementation draws batched primitives directly. The pass
		// boundaries are kept so a texture-backed silhouette pass can be
		// plugged
		// in without changing submission call-sites.
		drawMeshes(collector, collector.getMeshes());
		drawSolidBoxOutlines(matrices, collector,
			collector.getDepthSolidBoxes(), true);
		drawSolidBoxOutlines(matrices, collector, collector.getXraySolidBoxes(),
			false);
		drawLines(matrices, collector.getDepthLines(), true);
		drawLines(matrices, collector.getXrayLines(), false);
	}
	
	private void runCompositePass()
	{
		// Placeholder for Sobel/edge fullscreen composite.
	}
	
	private void drawLines(PoseStack matrices,
		java.util.List<GlobalEspCollector.LinePrimitive> lines,
		boolean depthTest)
	{
		if(lines.isEmpty())
			return;
		
		MultiBufferSource.BufferSource vcp = RenderUtils.getVCP();
		RenderType layer = WurstRenderLayers.getLines(depthTest);
		VertexConsumer buffer = vcp.getBuffer(layer);
		
		PoseStack scratch = new PoseStack();
		for(GlobalEspCollector.LinePrimitive line : lines)
		{
			PoseStack.Pose entry = scratch.last();
			entry.pose().set(line.pose);
			entry.normal().set(line.normal);
			putLine(entry, buffer, line.x1, line.y1, line.z1, line.x2, line.y2,
				line.z2, line.color,
				Mth.clamp(line.lineWidth, MIN_LINE_WIDTH, MAX_LINE_WIDTH));
		}
		
		vcp.endBatch(layer);
	}
	
	private void drawMeshes(GlobalEspCollector collector,
		java.util.List<GlobalEspCollector.MeshPrimitive> meshes)
	{
		if(meshes.isEmpty())
			return;
		
		preparedMeshDraws.clear();
		
		Matrix4fStack modelViewStack = RenderSystem.getModelViewStack();
		for(GlobalEspCollector.MeshPrimitive mesh : meshes)
		{
			EasyVertexBuffer buffer = mesh.buffer;
			RenderType layer = mesh.layer;
			if(buffer == null || layer == null)
				continue;
			
			GpuBuffer vertexBuffer = buffer.getVertexBufferForGlobalEsp();
			RenderSystem.AutoStorageIndexBuffer indexAccessor =
				buffer.getIndexBufferForGlobalEsp();
			int indexCount = buffer.getIndexCountForGlobalEsp();
			if(vertexBuffer == null || indexAccessor == null || indexCount < 1)
				continue;
			
			RenderPipeline pipeline = layer.pipeline();
			GlobalEspCollector.TransformState transform =
				collector.getTransform(mesh.transformIndex);
			
			modelViewStack.pushMatrix();
			modelViewStack.mul(transform.pose);
			GpuBufferSlice transformSlice =
				RenderSystem.getDynamicUniforms()
					.writeTransform(RenderSystem.getModelViewMatrix(),
						new Vector4f(mesh.red, mesh.green, mesh.blue,
							mesh.alpha),
						new Vector3f(),
						TextureTransform.DEFAULT_TEXTURING.getMatrix());
			modelViewStack.popMatrix();
			
			GpuBuffer indexBuffer = indexAccessor.getBuffer(indexCount);
			
			preparedMeshDraws.add(new MeshDrawCall(pipeline, transformSlice,
				vertexBuffer, indexBuffer, indexAccessor, indexCount));
		}
		
		if(preparedMeshDraws.isEmpty())
			return;
		
		RenderTarget framebuffer =
			OutputTarget.ITEM_ENTITY_TARGET.getRenderTarget();
		try(RenderPass renderPass =
			RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> "wurst_global_esp_meshes",
				framebuffer.getColorTextureView(), OptionalInt.empty(),
				framebuffer.getDepthTextureView(), OptionalDouble.empty()))
		{
			RenderSystem.bindDefaultUniforms(renderPass);
			for(MeshDrawCall drawCall : preparedMeshDraws)
			{
				renderPass.setPipeline(drawCall.pipeline);
				renderPass.setUniform("DynamicTransforms", drawCall.transform);
				renderPass.setVertexBuffer(0, drawCall.vertexBuffer);
				renderPass.setIndexBuffer(drawCall.indexBuffer,
					drawCall.indexAccessor.type());
				renderPass.drawIndexed(0, 0, drawCall.indexCount, 1);
			}
		}
	}
	
	private static final class MeshDrawCall
	{
		private final RenderPipeline pipeline;
		private final GpuBufferSlice transform;
		private final GpuBuffer vertexBuffer;
		private final GpuBuffer indexBuffer;
		private final RenderSystem.AutoStorageIndexBuffer indexAccessor;
		private final int indexCount;
		
		private MeshDrawCall(RenderPipeline pipeline, GpuBufferSlice transform,
			GpuBuffer vertexBuffer, GpuBuffer indexBuffer,
			RenderSystem.AutoStorageIndexBuffer indexAccessor, int indexCount)
		{
			this.pipeline = pipeline;
			this.transform = transform;
			this.vertexBuffer = vertexBuffer;
			this.indexBuffer = indexBuffer;
			this.indexAccessor = indexAccessor;
			this.indexCount = indexCount;
		}
	}
	
	private void drawSolidBoxOutlines(PoseStack matrices,
		GlobalEspCollector collector,
		java.util.List<GlobalEspCollector.SolidBoxPrimitive> boxes,
		boolean depthTest)
	{
		if(boxes.isEmpty())
			return;
		
		MultiBufferSource.BufferSource vcp = RenderUtils.getVCP();
		RenderType layer = WurstRenderLayers.getLines(depthTest);
		VertexConsumer buffer = vcp.getBuffer(layer);
		
		PoseStack scratch = new PoseStack();
		for(GlobalEspCollector.SolidBoxPrimitive box : boxes)
		{
			GlobalEspCollector.TransformState transform =
				collector.getTransform(box.transformIndex);
			PoseStack.Pose entry = scratch.last();
			entry.pose().set(transform.pose);
			entry.normal().set(transform.normal);
			putBoxOutline(entry, buffer, box.minX, box.minY, box.minZ, box.maxX,
				box.maxY, box.maxZ, box.color,
				Mth.clamp(box.lineWidth, MIN_LINE_WIDTH, MAX_LINE_WIDTH));
		}
		
		vcp.endBatch(layer);
	}
	
	private void putLine(PoseStack.Pose entry, VertexConsumer buffer, float x1,
		float y1, float z1, float x2, float y2, float z2, int color,
		float lineWidth)
	{
		Vector3f normal = new Vector3f(x2, y2, z2).sub(x1, y1, z1).normalize();
		buffer.addVertex(entry, x1, y1, z1).setColor(color)
			.setNormal(entry, normal).setLineWidth(lineWidth);
		
		// Keep workaround for Minecraft's line shader near-camera clipping
		// issue.
		float t = new Vector3f(x1, y1, z1).negate().dot(normal);
		float length = new Vector3f(x2, y2, z2).sub(x1, y1, z1).length();
		if(t > 0 && t < length)
		{
			Vector3f closeToCam = new Vector3f(normal).mul(t).add(x1, y1, z1);
			buffer.addVertex(entry, closeToCam).setColor(color)
				.setNormal(entry, normal).setLineWidth(lineWidth);
			buffer.addVertex(entry, closeToCam).setColor(color)
				.setNormal(entry, normal).setLineWidth(lineWidth);
		}
		
		buffer.addVertex(entry, x2, y2, z2).setColor(color)
			.setNormal(entry, normal).setLineWidth(lineWidth);
	}
	
	private void putBoxOutline(PoseStack.Pose entry, VertexConsumer buffer,
		float x1, float y1, float z1, float x2, float y2, float z2, int color,
		float lineWidth)
	{
		// bottom
		putLine(entry, buffer, x1, y1, z1, x2, y1, z1, color, lineWidth);
		putLine(entry, buffer, x2, y1, z1, x2, y1, z2, color, lineWidth);
		putLine(entry, buffer, x2, y1, z2, x1, y1, z2, color, lineWidth);
		putLine(entry, buffer, x1, y1, z2, x1, y1, z1, color, lineWidth);
		
		// top
		putLine(entry, buffer, x1, y2, z1, x2, y2, z1, color, lineWidth);
		putLine(entry, buffer, x2, y2, z1, x2, y2, z2, color, lineWidth);
		putLine(entry, buffer, x2, y2, z2, x1, y2, z2, color, lineWidth);
		putLine(entry, buffer, x1, y2, z2, x1, y2, z1, color, lineWidth);
		
		// vertical
		putLine(entry, buffer, x1, y1, z1, x1, y2, z1, color, lineWidth);
		putLine(entry, buffer, x2, y1, z1, x2, y2, z1, color, lineWidth);
		putLine(entry, buffer, x1, y1, z2, x1, y2, z2, color, lineWidth);
		putLine(entry, buffer, x2, y1, z2, x2, y2, z2, color, lineWidth);
	}
}
