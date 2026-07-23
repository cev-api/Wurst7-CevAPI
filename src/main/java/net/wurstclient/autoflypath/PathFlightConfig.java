/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.autoflypath;

import java.util.LinkedHashMap;
import java.util.Map;

/** Mutable settings snapshot consumed by the path-flight engine. */
public final class PathFlightConfig
{
	public boolean flightProcess = true;
	public boolean assumeFlightHack = true;
	public double flightHorizontalSpeed = 1.0;
	public double flightVerticalSpeed = 10.0;
	public double flightArrivalRadius = 5.0;
	public boolean flightPredictTerrain;
	public long flightSeed;
	/** Optional per-server seeds used by the imported engine. */
	public final Map<String, Long> flightServerSeeds = new LinkedHashMap<>();
	public boolean flightAntiHunger = true;
	public boolean flightFaceTravel;
	public boolean flightRenderPath = true;
	public boolean flightDebug;
	public int flightCruiseHeight;
}
