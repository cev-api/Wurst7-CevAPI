/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.locator;

import java.util.ArrayList;
import java.util.List;

public final class LocatorTriangulator
{
	private LocatorTriangulator()
	{}
	
	public static Result fit(List<LocatorObservation> observations)
	{
		if(observations.size() < 2)
			return null;
		
		double aa = 0, ab = 0, bb = 0, ax = 0, az = 0;
		for(LocatorObservation o : observations)
		{
			double dx = o.directionX(), dz = o.directionZ();
			double nx = dz, nz = -dx;
			aa += nx * nx;
			ab += nx * nz;
			bb += nz * nz;
			ax += nx * (nx * o.x() + nz * o.z());
			az += nz * (nx * o.x() + nz * o.z());
		}
		
		double determinant = aa * bb - ab * ab;
		if(!Double.isFinite(determinant) || determinant < 1e-8)
			return null;
		
		double x = (ax * bb - az * ab) / determinant;
		double z = (az * aa - ax * ab) / determinant;
		if(!Double.isFinite(x) || !Double.isFinite(z))
			return null;
		
		List<LocatorObservation> useful = new ArrayList<>(observations);
		for(int pass = 0; pass < 2 && useful.size() >= 4; pass++)
		{
			double rms = residualRms(x, z, useful);
			if(!Double.isFinite(rms) || rms < 1)
				break;
			final double fitX = x;
			final double fitZ = z;
			final double threshold = Math.max(32, rms * 2.5);
			List<LocatorObservation> filtered = useful.stream()
				.filter(o -> lineResidual(fitX, fitZ, o) <= threshold).toList();
			if(filtered.size() == useful.size() || filtered.size() < 2)
				break;
			useful = filtered;
			Result refit = fitLeastSquares(useful);
			if(refit == null)
				break;
			x = refit.x();
			z = refit.z();
		}
		
		double rms = residualRms(x, z, useful);
		double baseline = baselineSpan(useful);
		double parallax = maxParallax(useful);
		double uncertainty = Math.max(8,
			Math.max(rms * 2.5, baseline / Math.max(0.02, parallax) * 0.08));
		return new Result(x, z, uncertainty, baseline, Math.toDegrees(parallax),
			useful.size(), rms);
	}
	
	private static Result fitLeastSquares(List<LocatorObservation> os)
	{
		double aa = 0, ab = 0, bb = 0, ax = 0, az = 0;
		for(LocatorObservation o : os)
		{
			double dx = o.directionX(), dz = o.directionZ();
			double nx = dz, nz = -dx;
			aa += nx * nx;
			ab += nx * nz;
			bb += nz * nz;
			ax += nx * (nx * o.x() + nz * o.z());
			az += nz * (nx * o.x() + nz * o.z());
		}
		double d = aa * bb - ab * ab;
		if(d < 1e-8)
			return null;
		return new Result((ax * bb - az * ab) / d, (az * aa - ax * ab) / d, 0,
			baselineSpan(os), Math.toDegrees(maxParallax(os)), os.size(), 0);
	}
	
	private static double lineResidual(double x, double z, LocatorObservation o)
	{
		return Math
			.abs((x - o.x()) * o.directionZ() - (z - o.z()) * o.directionX());
	}
	
	private static double residualRms(double x, double z,
		List<LocatorObservation> os)
	{
		return Math.sqrt(os.stream()
			.mapToDouble(o -> lineResidual(x, z, o) * lineResidual(x, z, o))
			.average().orElse(Double.POSITIVE_INFINITY));
	}
	
	private static double baselineSpan(List<LocatorObservation> os)
	{
		LocatorObservation first = os.get(0);
		return os.stream()
			.mapToDouble(o -> Math.hypot(o.x() - first.x(), o.z() - first.z()))
			.max().orElse(0);
	}
	
	private static double maxParallax(List<LocatorObservation> os)
	{
		double max = 0;
		for(int i = 0; i < os.size(); i++)
			for(int j = i + 1; j < os.size(); j++)
				max = Math.max(max,
					Math.abs(angleDifference(os.get(i).yawRadians(),
						os.get(j).yawRadians())));
		return max;
	}
	
	private static double angleDifference(double a, double b)
	{
		return Math.atan2(Math.sin(a - b), Math.cos(a - b));
	}
	
	public record Result(double x, double z, double uncertainty,
		double baseline, double parallaxDegrees, int sampleCount, double rms)
	{
		public String confidence()
		{
			if(uncertainty < 100)
				return "HIGH";
			if(uncertainty < 1000)
				return "MEDIUM";
			return "LOW";
		}
	}
}
