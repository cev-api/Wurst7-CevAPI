/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import org.joml.Matrix4fc;

/**
 * Conservative screen-side tests in homogeneous clip space, before dividing
 * by W. Deliberately has no near/far or terrain-occlusion test.
 */
public final class EspFrustum
{
	private final double[][] planes = new double[4][4];
	
	public EspFrustum(Matrix4fc m)
	{
		this(m, 1.03);
	}
	
	public EspFrustum(Matrix4fc m, double margin)
	{
		this(m, margin, margin);
	}
	
	public EspFrustum(Matrix4fc m, double marginX, double marginY)
	{
		// Extra screen margin preserves wide lines and shader outlines at
		// edges.
		set(0, marginX * m.m03() + m.m00(), marginX * m.m13() + m.m10(),
			marginX * m.m23() + m.m20(), marginX * m.m33() + m.m30());
		set(1, marginX * m.m03() - m.m00(), marginX * m.m13() - m.m10(),
			marginX * m.m23() - m.m20(), marginX * m.m33() - m.m30());
		set(2, marginY * m.m03() + m.m01(), marginY * m.m13() + m.m11(),
			marginY * m.m23() + m.m21(), marginY * m.m33() + m.m31());
		set(3, marginY * m.m03() - m.m01(), marginY * m.m13() - m.m11(),
			marginY * m.m23() - m.m21(), marginY * m.m33() - m.m31());
	}
	
	private void set(int i, double x, double y, double z, double w)
	{
		planes[i][0] = x;
		planes[i][1] = y;
		planes[i][2] = z;
		planes[i][3] = w;
	}
	
	public boolean intersects(double minX, double minY, double minZ,
		double maxX, double maxY, double maxZ)
	{
		for(double[] p : planes)
		{
			double x = p[0] >= 0 ? maxX : minX;
			double y = p[1] >= 0 ? maxY : minY;
			double z = p[2] >= 0 ? maxZ : minZ;
			// Fail open for invalid data. Never lose visible geometry to NaN.
			if(p[0] * x + p[1] * y + p[2] * z + p[3] < -1.0E-5)
				return false;
		}
		return true;
	}
	
	public boolean contains(double minX, double minY, double minZ, double maxX,
		double maxY, double maxZ)
	{
		for(double[] p : planes)
		{
			double x = p[0] >= 0 ? minX : maxX;
			double y = p[1] >= 0 ? minY : maxY;
			double z = p[2] >= 0 ? minZ : maxZ;
			if(!(p[0] * x + p[1] * y + p[2] * z + p[3] >= 0))
				return false;
		}
		return true;
	}
}
