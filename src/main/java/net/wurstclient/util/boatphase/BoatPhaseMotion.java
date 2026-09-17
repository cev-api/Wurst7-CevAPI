/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.boatphase;

import java.util.function.UnaryOperator;

public final class BoatPhaseMotion
{
	public static final double MAX_STEP = 9.0;
	public static final double MAX_VERTICAL = BoatPhaseVertical.MAX_SPEED;
	public static final double MAX_TRAVEL = 384;
	public static final double MAX_PHASE_HORIZONTAL = 0.249;
	public static final double PROVEN_PHASE_HORIZONTAL = 0.24;
	
	public record Point(double x, double y, double z)
	{
		public static final Point ZERO = new Point(0, 0, 0);
		
		public boolean finite()
		{
			return Double.isFinite(x) && Double.isFinite(y)
				&& Double.isFinite(z);
		}
		
		public double length()
		{
			return Math.sqrt(x * x + y * y + z * z);
		}
	}
	
	private int tick;
	private int resumeAt;
	private int lastSentTick = -1;
	private double diveRemaining;
	private double riseRemaining;
	
	public void reset()
	{
		tick = resumeAt = 0;
		lastSentTick = -1;
		diveRemaining = riseRemaining = 0;
	}
	
	public void nextTick()
	{
		tick++;
	}
	
	public int tick()
	{
		return tick;
	}
	
	public int holdTicks()
	{
		return Math.max(0, resumeAt - tick);
	}
	
	public double diveRemaining()
	{
		return diveRemaining;
	}
	
	public double riseRemaining()
	{
		return riseRemaining;
	}
	
	public void cancelDive()
	{
		diveRemaining = 0;
	}
	
	public void cancelTravel()
	{
		diveRemaining = riseRemaining = 0;
	}
	
	public boolean alreadySent()
	{
		return lastSentTick == tick;
	}
	
	public void acknowledged()
	{
		lastSentTick = tick;
	}
	
	public static Point phaseStep(Point step, double speed)
	{
		if(!Double.isFinite(speed))
			return Point.ZERO;
		return horizontalStep(step, Math.min(MAX_PHASE_HORIZONTAL, speed));
	}
	
	public static Point horizontalStep(Point step, double speed)
	{
		if(step == null || !step.finite() || !Double.isFinite(speed))
			return Point.ZERO;
		double limit = Math.max(0, Math.min(MAX_STEP, speed));
		double horizontal = Math.hypot(step.x, step.z);
		if(horizontal <= limit)
			return step;
		double scale = limit / horizontal;
		return new Point(step.x * scale, step.y, step.z * scale);
	}
	
	public static Point collisionStep(Point desired, double tolerance,
		UnaryOperator<Point> collisionResolver)
	{
		if(desired == null || !desired.finite() || !Double.isFinite(tolerance))
			return Point.ZERO;
		double limit = Math.max(0, Math.min(MAX_PHASE_HORIZONTAL, tolerance));
		Point candidate = desired;
		for(int i = 0; i < 8; i++)
		{
			Point resolved = collisionResolver.apply(candidate);
			if(resolved == null || !resolved.finite())
				return phaseStep(desired, limit);
			double rx = candidate.x - resolved.x, rz = candidate.z - resolved.z;
			double residual = Math.hypot(rx, rz);
			if(residual <= limit + 1e-9)
				return candidate;
			double scale = limit / residual;
			candidate = new Point(resolved.x + rx * scale, desired.y,
				resolved.z + rz * scale);
		}
		return phaseStep(desired, limit);
	}
	
	public static Point airStep(Point desired, Point previous,
		double acceleration, int airTicks, boolean antiKick,
		boolean verticalControl, double remainingBelow)
	{
		if(desired == null || previous == null || !desired.finite()
			|| !previous.finite() || !Double.isFinite(acceleration)
			|| !Double.isFinite(remainingBelow))
			return Point.ZERO;
		double dx = desired.x, dz = desired.z;
		if(Math.hypot(dx, dz) > 1e-9)
		{
			double change = Math.hypot(dx - previous.x, dz - previous.z);
			double allowed = Math.max(0.01, Math.min(1, acceleration));
			if(change > allowed)
			{
				dx = previous.x + (dx - previous.x) * allowed / change;
				dz = previous.z + (dz - previous.z) * allowed / change;
			}
		}
		double dy = desired.y;
		if(antiKick && !verticalControl && airTicks > 0 && airTicks % 10 < 2)
			dy = -Math.min(0.08, Math.max(0, remainingBelow));
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if(Math.abs(dy) <= MAX_STEP && length > MAX_STEP)
		{
			double scale = MAX_STEP / length;
			dx *= scale;
			dy *= scale;
			dz *= scale;
		}
		dy = Math.max(-MAX_VERTICAL, Math.min(MAX_VERTICAL, dy));
		Point result = new Point(dx, dy, dz);
		return Math.abs(dy) > MAX_STEP
			? horizontalStep(result, BoatPhaseVertical.MAX_HORIZONTAL) : result;
	}
	
	public void dive(double distance)
	{
		if(Double.isFinite(distance) && distance > 0 && holdTicks() == 0)
		{
			riseRemaining = 0;
			diveRemaining = Math.min(MAX_TRAVEL, distance);
		}
	}
	
	public void rise(double distance)
	{
		if(Double.isFinite(distance) && distance > 0 && holdTicks() == 0)
		{
			diveRemaining = 0;
			riseRemaining = Math.min(MAX_TRAVEL, distance);
		}
	}
	
	public void corrected(int waitTicks)
	{
		cancelTravel();
		resumeAt = tick + Math.max(1, Math.min(100, waitTicks));
		lastSentTick = -1;
	}
	
	public boolean sent(Point from, Point to)
	{
		if(from == null || to == null || !from.finite() || !to.finite()
			|| alreadySent())
			return false;
		lastSentTick = tick;
		boolean traveling = diveRemaining > 1e-6 || riseRemaining > 1e-6;
		if(diveRemaining > 0)
			diveRemaining =
				Math.max(0, diveRemaining - Math.max(0, from.y - to.y));
		if(riseRemaining > 0)
			riseRemaining =
				Math.max(0, riseRemaining - Math.max(0, to.y - from.y));
		return traveling && diveRemaining <= 1e-6 && riseRemaining <= 1e-6;
	}
	
	public Point plan(Point origin, double forward, double sideways, boolean up,
		boolean down, double yawDegrees, double horizontalSpeed,
		double verticalSpeed, double floor, double ceiling, boolean antiKick)
	{
		if(origin == null || !origin.finite() || !Double.isFinite(forward)
			|| !Double.isFinite(sideways) || !Double.isFinite(yawDegrees)
			|| !Double.isFinite(horizontalSpeed)
			|| !Double.isFinite(verticalSpeed) || !Double.isFinite(floor)
			|| !Double.isFinite(ceiling) || floor > ceiling || origin.y < floor
			|| origin.y > ceiling || holdTicks() > 0)
			return Point.ZERO;
		
		if(up)
			cancelTravel();
		else if(down)
			riseRemaining = 0;
		double h = Math.max(0, Math.min(MAX_STEP, horizontalSpeed));
		double v = Math.max(0, Math.min(MAX_VERTICAL, verticalSpeed));
		double inputLength = Math.hypot(forward, sideways);
		if(inputLength > 1)
		{
			forward /= inputLength;
			sideways /= inputLength;
		}
		double yaw = Math.toRadians(yawDegrees % 360);
		double dx = (-Math.sin(yaw) * forward + Math.cos(yaw) * sideways) * h;
		double dz = (Math.cos(yaw) * forward + Math.sin(yaw) * sideways) * h;
		double dy = (up ? v : 0) - (down ? v : 0);
		if(diveRemaining > 1e-6)
			dy = -Math.min(v, diveRemaining);
		else if(riseRemaining > 1e-6)
			dy = Math.min(v, riseRemaining);
		else if(!up && !down && antiKick && tick > 0 && tick % 40 == 0)
			dy = -0.04;
		
		dy = Math.max(floor - origin.y, Math.min(ceiling - origin.y, dy));
		double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
		
		if(Math.abs(dy) <= MAX_STEP && length > MAX_STEP)
		{
			double factor = MAX_STEP / length;
			dx *= factor;
			dy *= factor;
			dz *= factor;
		}
		Point result = new Point(dx, dy, dz);
		return Math.abs(dy) > MAX_STEP
			? horizontalStep(result, BoatPhaseVertical.MAX_HORIZONTAL) : result;
	}
}
