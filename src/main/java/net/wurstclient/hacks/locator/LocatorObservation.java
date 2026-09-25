/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.locator;

import java.util.UUID;

public record LocatorObservation(UUID target, double x, double z, long time,
	double yawRadians, String dimension)
{
	public double directionX()
	{
		return -Math.sin(yawRadians);
	}
	
	public double directionZ()
	{
		return Math.cos(yawRadians);
	}
}
