/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import org.joml.Matrix4f;

/**
 * Plain-JVM check of the culling math. No Minecraft classes are touched, so
 * this runs without a game instance.
 */
public final class EspFrustumRegressionTest
{
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
	
	public static void main(String[] args)
	{
		// Standard perspective projection, camera looking down -Z in view
		// space, which is the convention Minecraft's view matrix produces.
		Matrix4f projection = new Matrix4f()
			.perspective((float)Math.toRadians(70), 16F / 9F, 0.05F, 1000F);
		EspFrustum frustum = new EspFrustum(projection, 1.03);
		
		// A box straight ahead must never be culled.
		check(!isCulled(frustum, 0, 0, -10, 1),
			"A box directly in front of the camera must be visible");
		check(!isCulled(frustum, 0, 0, -2, 1),
			"A box close in front of the camera must be visible");
		check(!isCulled(frustum, 0, 0, -500, 1),
			"A distant box straight ahead must be visible");
		
		// Geometry behind the camera must be culled.
		check(isCulled(frustum, 0, 0, 10, 1),
			"A box behind the camera must be culled");
		check(isCulled(frustum, 0, 0, 200, 1),
			"A distant box behind the camera must be culled");
		
		// Geometry far outside the horizontal field of view must be culled.
		check(isCulled(frustum, 500, 0, -10, 1),
			"A box far to the right must be culled");
		check(isCulled(frustum, -500, 0, -10, 1),
			"A box far to the left must be culled");
		
		// Geometry far outside the vertical field of view must be culled.
		check(isCulled(frustum, 0, 500, -10, 1),
			"A box far above must be culled");
		check(isCulled(frustum, 0, -500, -10, 1),
			"A box far below must be culled");
		
		// A box straddling an edge must be kept, not culled, because it is
		// still partly on screen.
		check(!isCulled(frustum, 9, 0, -10, 4),
			"A box straddling the right edge must be kept");
		
		// contains() is the fully-inside test used to skip per-primitive work.
		check(frustum.contains(-1, -1, -11, 1, 1, -9),
			"A small box centred on the view axis must be fully contained");
		check(!frustum.contains(-500, -1, -11, 500, 1, -9),
			"An oversized box must not report as fully contained");
		
		// Composition check: the same box must flip visibility when the view
		// matrix turns 180 degrees, which is what positionMatrix does at
		// runtime.
		Matrix4f turned = new Matrix4f(projection)
			.mul(new Matrix4f().rotateY((float)Math.PI));
		EspFrustum turnedFrustum = new EspFrustum(turned, 1.03);
		check(!isCulled(turnedFrustum, 0, 0, 10, 1),
			"After a 180 degree turn a box behind the old view must be visible");
		check(isCulled(turnedFrustum, 0, 0, -10, 1),
			"After a 180 degree turn a box in the old view must be culled");
		
		System.out.println("EspFrustumRegressionTest: all checks passed");
	}
	
	private static boolean isCulled(EspFrustum frustum, double centerX,
		double centerY, double centerZ, double radius)
	{
		return !frustum.intersects(centerX - radius, centerY - radius,
			centerZ - radius, centerX + radius, centerY + radius,
			centerZ + radius);
	}
}
