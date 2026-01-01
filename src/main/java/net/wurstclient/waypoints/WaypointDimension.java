/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.waypoints;

public enum WaypointDimension
{
	OVERWORLD,
	NETHER,
	END;
	
	public static WaypointDimension fromString(String s)
	{
		if(s == null)
			return OVERWORLD;
		s = s.toLowerCase();
		return switch(s)
		{
			case "overworld", "over" -> OVERWORLD;
			case "nether" -> NETHER;
			case "end" -> END;
			default -> OVERWORLD;
		};
	}
}
