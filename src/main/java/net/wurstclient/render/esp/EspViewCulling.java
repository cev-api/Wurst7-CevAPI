/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;

/** Render-thread scope: source identity is established once per listener. */
public final class EspViewCulling
{
	private static final Matrix4f PROJECTION = new Matrix4f();
	private static EspFrustum worldFrustum;
	private static Vec3 camera = Vec3.ZERO;
	private static String source;
	private static boolean active;
	private static boolean cullSource;
	private static final Matrix4f LAST_POSE = new Matrix4f();
	private static final Matrix4f LAST_MODEL_VIEW = new Matrix4f();
	private static final Matrix4f IDENTITY = new Matrix4f();
	private static EspFrustum cachedLocal;
	private static double screenMarginX, screenMarginY;
	
	private EspViewCulling()
	{}
	
	public static void beginFrame(CameraRenderState state)
	{
		PROJECTION.set(state.projectionMatrix);
		screenMarginX = pixelMargin(WurstClient.MC.getWindow().getWidth());
		screenMarginY = pixelMargin(WurstClient.MC.getWindow().getHeight());
		EspCullingStats.beginFrame(
			net.wurstclient.util.HackPerformanceTracker.shouldProfile());
		worldFrustum = new EspFrustum(
			new Matrix4f(PROJECTION).mul(state.viewRotationMatrix),
			screenMarginX, screenMarginY);
		camera = state.pos;
		active = true;
		cachedLocal = null;
	}
	
	// NDC spans two units across the viewport: 64 pixels need 128 / size.
	static double pixelMargin(int size)
	{
		return 1 + 128.0 / Math.max(1, size);
	}
	
	static Matrix4f composeTransform(Matrix4fc projection, Matrix4fc modelView,
		Matrix4fc pose)
	{
		return new Matrix4f(projection).mul(modelView).mul(pose);
	}
	
	static boolean intersectsWorldBox(EspFrustum frustum, AABB box,
		Vec3 cameraPosition)
	{
		return frustum.intersects(box.minX - cameraPosition.x,
			box.minY - cameraPosition.y, box.minZ - cameraPosition.z,
			box.maxX - cameraPosition.x, box.maxY - cameraPosition.y,
			box.maxZ - cameraPosition.z);
	}
	
	public static void endFrame()
	{
		active = false;
		endSource();
	}
	
	public static void beginSource(Object listener)
	{
		source = listener instanceof Hack hack ? hack.getName()
			: listener instanceof net.wurstclient.events.RenderListener render
				? render.espCullingSource() : null;
		cullSource = active && source != null
			&& WurstClient.INSTANCE.getHax().globalToggleHack
				.shouldCullEspSource(source);
	}
	
	public static void endSource()
	{
		source = null;
		cullSource = false;
	}
	
	public static boolean isActive()
	{
		return active && cullSource;
	}
	
	public static String getSource()
	{
		return source;
	}
	
	public static boolean shouldCull(AABB box)
	{
		return isActive() && !worldFrustum.intersects(box.minX - camera.x,
			box.minY - camera.y, box.minZ - camera.z, box.maxX - camera.x,
			box.maxY - camera.y, box.maxZ - camera.z);
	}
	
	public static boolean shouldCullBox(Matrix4fc pose, AABB box)
	{
		boolean culled =
			isActive() && !intersectsWorldBox(localFrustum(pose), box, camera);
		EspCullingStats.target(culled);
		return culled;
	}
	
	public static boolean shouldCullTracer(Vec3 point)
	{
		if(!isActive() || WurstClient.INSTANCE.getHax().globalToggleHack
			.allowOffscreenTracers())
			return false;
		double x = point.x - camera.x;
		double y = point.y - camera.y;
		double z = point.z - camera.z;
		return !worldFrustum.intersects(x, y, z, x, y, z);
	}
	
	/** Input coordinates are those passed to the GPU, after the PoseStack. */
	public static EspFrustum bufferFrustum()
	{
		return localFrustum(IDENTITY);
	}
	
	public static EspFrustum localFrustum(Matrix4fc pose)
	{
		Matrix4fc modelView = RenderSystem.getModelViewStack();
		if(cachedLocal == null || !LAST_POSE.equals(pose)
			|| !LAST_MODEL_VIEW.equals(modelView))
		{
			LAST_POSE.set(pose);
			LAST_MODEL_VIEW.set(modelView);
			cachedLocal =
				new EspFrustum(composeTransform(PROJECTION, modelView, pose),
					screenMarginX, screenMarginY);
		}
		return cachedLocal;
	}
}
