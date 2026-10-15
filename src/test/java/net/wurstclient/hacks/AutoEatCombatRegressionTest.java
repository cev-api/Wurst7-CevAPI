/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

public final class AutoEatCombatRegressionTest
{
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
	
	public static void main(String[] args)
	{
		boolean useStarted = false;
		boolean playerUsingItem = false;
		int useActions = 0;
		for(int tick = 0; tick < 8; tick++)
		{
			if(AutoEatHack.shouldStartFoodUse(useStarted, playerUsingItem))
			{
				useActions++;
				useStarted = true;
				playerUsingItem = true;
			}
		}
		check(useActions == 1,
			"An active food use must not send another start-use action each tick");
		playerUsingItem = false;
		useStarted = false;
		check(AutoEatHack.shouldStartFoodUse(useStarted, playerUsingItem),
			"A completed food use must allow the next required food action");
		check(!AutoEatHack.shouldStartFoodUse(false, true),
			"AutoEat must not take over an item the player is already using");
		check(AutoEatHack.manualAttackHasPriority(true),
			"Physical attack input must interrupt AutoEat's pause and use state");
		check(!AutoEatHack.manualAttackHasPriority(false),
			"AutoEat must not yield combat when attack input is idle");
		System.out.println(
			"AutoEat combat regressions passed: one-shot food use, user item-use ownership and manual attack priority.");
	}
}
