/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

public final class TitleBackgroundAnimationRegressionTest
{
	public static void main(String[] args)
	{
		TitleBackgroundAnimation animation = new TitleBackgroundAnimation();
		long start = 1_000_000_000L;
		check(animation.sample(start, true) == 0, "first frame starts at zero");
		float firstStep = animation.sample(start + 16_666_667L, true);
		check(firstStep > 0.016F && firstStep < 0.017F,
			"movement must advance on the very next frame, before a 50ms tick");
		float paused = animation.sample(start + 33_333_334L, false);
		check(paused == firstStep,
			"pausing must preserve the current position");
		check(animation.sample(start + 120_000_000_000L, false) == paused,
			"long pauses must not move the camera");
		check(animation.sample(start + 121_000_000_000L, true) == paused,
			"resuming must not jump over the paused interval");
		check(animation.sample(start + 121_016_666_667L, true) > paused,
			"resumed movement must advance on the next frame");
		System.out
			.println("Immediate animation and pause/resume checks passed.");
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
