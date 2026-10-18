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

import net.minecraft.client.Camera;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.HackPerformanceTracker;

/**
 * Render-thread scope for ESP view culling. The frustum is rebuilt once per
 * frame from the projection and view matrices, and source identity is
 * established once per render listener so per-hack exceptions are cheap.
 */
public final class EspViewCulling
{
	private static final Matrix4f WORLD_VIEW = new Matrix4f();
	private static EspFrustum worldFrustum;
	private static Vec3 camera = Vec3.ZERO;
	private static String source;
	private static boolean active;
	private static boolean cullSource;
	
	private EspViewCulling()
	{}
	
	public static void beginFrame(Matrix4fc projectionMatrix,
		Matrix4fc positionMatrix, Camera camera)
	{
		WORLD_VIEW.set(projectionMatrix).mul(positionMatrix);
		EspViewCulling.camera = camera.position();
		worldFrustum = new EspFrustum(WORLD_VIEW, screenMargin());
		active = true;
		EspCullingStats.beginFrame(HackPerformanceTracker.shouldProfile());
	}
	
	public static void endFrame()
	{
		active = false;
		worldFrustum = null;
		endSource();
	}
	
	/**
	 * Wide lines and shader outlines reach past the geometric edge of their
	 * primitive, so the test is widened by a few dozen screen pixels.
	 */
	private static double screenMargin()
	{
		int pixels = Math.max(1, Math.min(WurstClient.MC.getWindow().getWidth(),
			WurstClient.MC.getWindow().getHeight()));
		return 1 + 64.0 / pixels;
	}
	
	public static void beginSource(Object listener)
	{
		source = listener instanceof Hack hack ? hack.getName() : null;
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
	
	/**
	 * True when the box is entirely outside the camera view and its geometry
	 * can therefore be skipped. Input is in world coordinates.
	 */
	public static boolean shouldCull(AABB box)
	{
		if(box == null)
			return false;
		return shouldCull(box.minX, box.minY, box.minZ, box.maxX, box.maxY,
			box.maxZ);
	}
	
	public static boolean shouldCull(double minX, double minY, double minZ,
		double maxX, double maxY, double maxZ)
	{
		if(!isActive())
			return false;
		
		boolean culled = !worldFrustum.intersects(minX - camera.x,
			minY - camera.y, minZ - camera.z, maxX - camera.x, maxY - camera.y,
			maxZ - camera.z);
		EspCullingStats.target(culled);
		return culled;
	}
	
	public static boolean shouldCullTracer(Vec3 point)
	{
		if(!isActive() || WurstClient.INSTANCE.getHax().globalToggleHack
			.allowOffscreenTracers())
			return false;
		
		return shouldCull(point.x, point.y, point.z, point.x, point.y, point.z);
	}
}
