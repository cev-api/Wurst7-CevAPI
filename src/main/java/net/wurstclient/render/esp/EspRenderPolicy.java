/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import org.joml.Matrix4fc;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.wurstclient.WurstClient;
import net.wurstclient.WurstRenderLayers;

/** Runtime filters never affect construction of reusable cached meshes. */
public final class EspRenderPolicy
{
	private static final ThreadLocal<Integer> meshBuildDepth =
		ThreadLocal.withInitial(() -> 0);
	
	private EspRenderPolicy()
	{}
	
	public static boolean skipFill()
	{
		return !isBuildingMesh() && EspViewCulling.getSource() != null
			&& WurstClient.INSTANCE.getHax().globalToggleHack
				.shouldDisableEspFills();
	}
	
	public static void beginMeshBuild()
	{
		meshBuildDepth.set(meshBuildDepth.get() + 1);
	}
	
	public static void endMeshBuild()
	{
		int depth = meshBuildDepth.get() - 1;
		if(depth <= 0)
			meshBuildDepth.remove();
		else
			meshBuildDepth.set(depth);
	}
	
	public static boolean isBuildingMesh()
	{
		return meshBuildDepth.get() > 0;
	}
	
	public static boolean skipFill(RenderType layer)
	{
		return skipFill() && (layer == WurstRenderLayers.QUADS
			|| layer == WurstRenderLayers.ESP_QUADS
			|| layer == WurstRenderLayers.QUADS_NO_CULLING
			|| layer == WurstRenderLayers.ESP_QUADS_NO_CULLING);
	}
	
	public static boolean skipLabel(Matrix4fc cameraRelativePose)
	{
		var settings = WurstClient.INSTANCE.getHax().globalToggleHack;
		if(settings.shouldDisableEspLabels())
			return EspCullingStats.label(true);
		// World-label poses already contain their camera-relative translation.
		// Rotation and glyph scale do not change the distance of this origin.
		double range = settings.getEffectiveEspLabelRange();
		return EspCullingStats
			.label(outsideLabelRange(cameraRelativePose, range));
	}
	
	static boolean outsideLabelRange(Matrix4fc pose, double range)
	{
		double x = pose.m30(), y = pose.m31(), z = pose.m32();
		return range > 0 && x * x + y * y + z * z > range * range;
	}
}
