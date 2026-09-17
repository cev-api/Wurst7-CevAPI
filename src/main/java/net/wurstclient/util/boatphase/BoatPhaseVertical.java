/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.boatphase;

public final class BoatPhaseVertical
{
	public static final double STEP = 9;
	public static final int PACKETS = 5;
	public static final double PROVEN_SPEED = STEP * PACKETS;
	public static final double FAST_STEP = 19.8;
	public static final double MAX_SPEED = FAST_STEP * PACKETS;
	public static final double MAX_HORIZONTAL =
		BoatPhaseAirSteps.STEP * PACKETS;
	
	public static int count(double vertical)
	{
		if(!Double.isFinite(vertical) || Math.abs(vertical) > MAX_SPEED + 1e-6)
			return 0;
		double step =
			Math.abs(vertical) <= PROVEN_SPEED + 1e-6 ? STEP : FAST_STEP;
		return Math.min(PACKETS,
			Math.max(1, (int)Math.ceil(Math.abs(vertical) / step)));
	}
}
