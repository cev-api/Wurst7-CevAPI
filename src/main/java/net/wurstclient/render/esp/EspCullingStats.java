/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import java.util.Arrays;
import java.util.List;

/**
 * Render-thread counters, collected only while PerformanceOverlay is enabled.
 */
public final class EspCullingStats
{
	private static boolean enabled;
	private static final long[] counts = new long[4];
	
	private EspCullingStats()
	{}
	
	public static void beginFrame(boolean profile)
	{
		enabled = profile;
		if(profile)
			Arrays.fill(counts, 0);
	}
	
	/** Records one tested target and whether the frustum rejected it. */
	public static void target(boolean rejected)
	{
		if(!enabled)
			return;
		counts[0]++;
		if(rejected)
			counts[1]++;
		else
			counts[2]++;
	}
	
	public static void appendOverlay(List<String> lines)
	{
		if(!enabled)
			return;
		lines.add("ESP/frame: tested " + counts[0] + " | culled " + counts[1]
			+ " | drawn " + counts[2]);
	}
}
