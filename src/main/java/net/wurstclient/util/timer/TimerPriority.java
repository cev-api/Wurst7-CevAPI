/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.timer;

public enum TimerPriority
{
	NOT_IMPORTANT(-20),
	NORMAL(0),
	IMPORTANT_FOR_USAGE_1(20),
	IMPORTANT_FOR_USAGE_2(30),
	IMPORTANT_FOR_USAGE_3(35),
	IMPORTANT_FOR_PLAYER_LIFE(40),
	IMPORTANT_FOR_USER_SAFETY(60);
	
	private final int priority;
	
	TimerPriority(int priority)
	{
		this.priority = priority;
	}
	
	public int getPriority()
	{
		return priority;
	}
}
