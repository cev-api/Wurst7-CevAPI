/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.globalesp;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.WurstRenderLayers;
import net.wurstclient.hack.HackList;
import net.wurstclient.util.EasyVertexBuffer;

public final class GlobalEspManager
{
	private static final GlobalEspManager INSTANCE = new GlobalEspManager();
	
	private final GlobalEspCollector collector = new GlobalEspCollector();
	private final GlobalEspRenderer renderer = new GlobalEspRenderer();
	
	private final ThreadLocal<Boolean> requestedLineDepth =
		ThreadLocal.withInitial(() -> true);
	private final ThreadLocal<Boolean> requestedQuadDepth =
		ThreadLocal.withInitial(() -> true);
	
	private boolean frameOpen;
	
	private GlobalEspManager()
	{}
	
	public static GlobalEspManager getInstance()
	{
		return INSTANCE;
	}
	
	public synchronized void beginFrame()
	{
		collector.clear();
		frameOpen = true;
	}
	
	public synchronized void endFrame(PoseStack matrices)
	{
		if(!frameOpen)
			return;
		
		if(isShaderOutlineMode())
			renderer.render(matrices, collector);
		
		collector.clear();
		frameOpen = false;
	}
	
	public synchronized void cleanup()
	{
		collector.clear();
		renderer.cleanup();
		frameOpen = false;
	}
	
	public synchronized boolean shouldTakeOverRenderCalls()
	{
		return frameOpen && isShaderOutlineMode();
	}
	
	public boolean shouldTakeOverBufferedLineCalls()
	{
		return shouldTakeOverRenderCalls();
	}
	
	public boolean shouldTakeOverBufferedQuadCalls()
	{
		return shouldTakeOverRenderCalls();
	}
	
	public void noteLineLayerRequest(boolean depthTest)
	{
		requestedLineDepth.set(depthTest);
	}
	
	public void noteQuadLayerRequest(boolean depthTest)
	{
		requestedQuadDepth.set(depthTest);
	}
	
	public boolean getRequestedLineDepth()
	{
		return requestedLineDepth.get();
	}
	
	public boolean getRequestedQuadDepth()
	{
		return requestedQuadDepth.get();
	}
	
	public synchronized boolean submitLine(PoseStack matrices, Vec3 start,
		Vec3 end, int color, boolean depthTest, float lineWidth)
	{
		if(!shouldTakeOverRenderCalls())
			return false;
		
		collector.submitLine(matrices.last(), start, end, color, depthTest,
			lineWidth);
		return true;
	}
	
	public synchronized boolean submitLine(PoseStack.Pose entry, float x1,
		float y1, float z1, float x2, float y2, float z2, int color,
		boolean depthTest, float lineWidth)
	{
		if(!shouldTakeOverRenderCalls())
			return false;
		
		collector.submitLine(entry, x1, y1, z1, x2, y2, z2, color, depthTest,
			lineWidth);
		return true;
	}
	
	public synchronized boolean submitCurvedLine(PoseStack matrices,
		List<Vec3> points, int color, boolean depthTest, float lineWidth)
	{
		if(!shouldTakeOverRenderCalls())
			return false;
		
		collector.submitCurvedLine(matrices.last(), points, color, depthTest,
			lineWidth);
		return true;
	}
	
	public synchronized boolean submitSolidBox(PoseStack matrices, AABB box,
		int color, boolean depthTest)
	{
		if(!shouldTakeOverRenderCalls())
			return false;
		
		collector.submitSolidBox(matrices.last(), box, color, depthTest);
		return true;
	}
	
	public synchronized boolean submitSolidBoxes(PoseStack matrices,
		List<AABB> boxes, double offsetX, double offsetY, double offsetZ,
		int color, boolean depthTest)
	{
		if(!shouldTakeOverRenderCalls() || boxes == null || boxes.isEmpty())
			return false;
		
		collector.submitSolidBoxes(matrices.last(), boxes, offsetX, offsetY,
			offsetZ, color, depthTest, 2F);
		return true;
	}
	
	public synchronized boolean submitOutlinedBox(PoseStack matrices, AABB box,
		int color, boolean depthTest, float lineWidth)
	{
		if(!shouldTakeOverRenderCalls())
			return false;
			
		// Shader outline mode draws all box-like ESP as silhouettes, then
		// outlines them in one place.
		collector.submitSolidBox(matrices.last(), box, color, depthTest,
			lineWidth);
		return true;
	}
	
	public synchronized boolean submitOutlinedBoxes(PoseStack matrices,
		List<AABB> boxes, double offsetX, double offsetY, double offsetZ,
		int color, boolean depthTest, float lineWidth)
	{
		if(!shouldTakeOverRenderCalls() || boxes == null || boxes.isEmpty())
			return false;
			
		// Shader outline mode draws all box-like ESP as silhouettes, then
		// outlines them in one place.
		collector.submitSolidBoxes(matrices.last(), boxes, offsetX, offsetY,
			offsetZ, color, depthTest, lineWidth);
		return true;
	}
	
	public synchronized boolean submitCrossBox(PoseStack matrices, AABB box,
		int color, boolean depthTest, float lineWidth)
	{
		if(!shouldTakeOverRenderCalls())
			return false;
		
		collector.submitCrossBox(matrices.last(), box, color, depthTest,
			lineWidth);
		return true;
	}
	
	public synchronized boolean submitNode(PoseStack matrices, AABB box,
		int color, boolean depthTest, float lineWidth)
	{
		if(!shouldTakeOverRenderCalls())
			return false;
		
		collector.submitNode(matrices.last(), box, color, depthTest, lineWidth);
		return true;
	}
	
	public synchronized boolean submitArrow(PoseStack matrices, Vec3 from,
		Vec3 to, int color, boolean depthTest, float lineWidth, float headSize)
	{
		if(!shouldTakeOverRenderCalls())
			return false;
		
		collector.submitArrow(matrices.last(), from, to, color, depthTest,
			lineWidth, headSize);
		return true;
	}
	
	public synchronized boolean submitBufferedSolidBox(PoseStack matrices,
		AABB box, int color)
	{
		return submitSolidBox(matrices, box, color, requestedQuadDepth.get());
	}
	
	public synchronized boolean submitBufferedOutlinedBox(PoseStack matrices,
		AABB box, int color, float lineWidth)
	{
		return submitOutlinedBox(matrices, box, color, requestedLineDepth.get(),
			lineWidth);
	}
	
	public synchronized boolean submitBufferedCrossBox(PoseStack matrices,
		AABB box, int color, float lineWidth)
	{
		return submitCrossBox(matrices, box, color, requestedLineDepth.get(),
			lineWidth);
	}
	
	public synchronized boolean submitBufferedNode(PoseStack matrices, AABB box,
		int color, float lineWidth)
	{
		return submitNode(matrices, box, color, requestedLineDepth.get(),
			lineWidth);
	}
	
	public synchronized boolean submitBufferedArrow(PoseStack matrices,
		Vec3 from, Vec3 to, int color, float lineWidth, float headSize)
	{
		return submitArrow(matrices, from, to, color, requestedLineDepth.get(),
			lineWidth, headSize);
	}
	
	public synchronized boolean submitBufferedLine(PoseStack.Pose entry,
		float x1, float y1, float z1, float x2, float y2, float z2, int color,
		float lineWidth)
	{
		return submitLine(entry, x1, y1, z1, x2, y2, z2, color,
			requestedLineDepth.get(), lineWidth);
	}
	
	public synchronized boolean submitMeshDraw(PoseStack matrices,
		EasyVertexBuffer buffer, RenderType layer, float red, float green,
		float blue, float alpha)
	{
		if(!shouldTakeOverRenderCalls() || !isEspLayer(layer))
			return false;
		
		collector.submitMeshDraw(matrices.last(), buffer, layer, red, green,
			blue, alpha);
		return true;
	}
	
	public synchronized GlobalEspRenderMode getRenderMode()
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null || hax.globalToggleHack == null)
			return GlobalEspRenderMode.LEGACY;
		
		GlobalEspRenderMode mode =
			hax.globalToggleHack.getGlobalEspRenderMode();
		return Objects.requireNonNullElse(mode, GlobalEspRenderMode.LEGACY);
	}
	
	public synchronized boolean isShaderOutlineMode()
	{
		return getRenderMode() == GlobalEspRenderMode.SHADER_OUTLINE;
	}
	
	private boolean isEspLayer(RenderType layer)
	{
		return layer == WurstRenderLayers.LINES
			|| layer == WurstRenderLayers.ESP_LINES
			|| layer == WurstRenderLayers.QUADS
			|| layer == WurstRenderLayers.ESP_QUADS
			|| layer == WurstRenderLayers.QUADS_NO_CULLING
			|| layer == WurstRenderLayers.ESP_QUADS_NO_CULLING;
	}
}
