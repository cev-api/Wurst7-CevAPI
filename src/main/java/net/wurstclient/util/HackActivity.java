/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.world.level.Level;
import net.wurstclient.WurstClient;

/** Cheap context checks shared by hacks that pause automatically. */
public enum HackActivity
{
	;
	
	public static boolean isOverworld()
	{
		return WurstClient.MC.level != null
			&& WurstClient.MC.level.dimension() == Level.OVERWORLD;
	}
	
	public static boolean isNether()
	{
		return WurstClient.MC.level != null
			&& WurstClient.MC.level.dimension() == Level.NETHER;
	}
	
	public static boolean isEnd()
	{
		return WurstClient.MC.level != null
			&& WurstClient.MC.level.dimension() == Level.END;
	}
	
	public static boolean canUseWater()
	{
		return WurstClient.MC.level != null && !isNether();
	}
}
