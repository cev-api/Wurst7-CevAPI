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
import org.joml.Vector3f;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;

/** Reuses camera transforms within one GUI callback, never between frames. */
public final class EspScreenProjector
{
	private final Matrix4f matrix = new Matrix4f();
	private final Vector3f projected = new Vector3f();
	private Vec3 origin = Vec3.ZERO;
	private Vec3 forward = Vec3.ZERO;
	
	public void reset(Camera camera)
	{
		camera.getViewRotationProjectionMatrix(matrix);
		setCamera(camera.position(), camera.yRot(), camera.xRot());
	}
	
	public void reset(Matrix4fc projection, Vec3 position, float yaw,
		float pitch)
	{
		matrix.set(projection);
		setCamera(position, yaw, pitch);
	}
	
	private void setCamera(Vec3 position, float yaw, float pitch)
	{
		origin = position;
		double yawRad = Math.toRadians(yaw);
		double pitchRad = Math.toRadians(pitch);
		forward = new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad),
			-Math.sin(pitchRad), Math.cos(yawRad) * Math.cos(pitchRad));
	}
	
	public boolean isBehindCamera(Vec3 position)
	{
		double x = position.x - origin.x;
		double y = position.y - origin.y;
		double z = position.z - origin.z;
		if(x * x + y * y + z * z == 0)
			return false;
		return x * forward.x + y * forward.y + z * forward.z <= 0;
	}
	
	public Vec3 project(Vec3 position)
	{
		// Match GameRenderer.projectPointToScreen(): subtract in double,
		// then cast to float and use the same JOML transformProject operation.
		projected.set((float)(position.x - origin.x),
			(float)(position.y - origin.y), (float)(position.z - origin.z));
		matrix.transformProject(projected);
		return new Vec3(projected);
	}
}
