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
	private static final long[] counts = new long[10];
	
	private EspCullingStats()
	{}
	
	public static void beginFrame(boolean profile)
	{
		enabled = profile;
		if(profile)
			Arrays.fill(counts, 0);
	}
	
	public static void target(boolean rejected)
	{
		if(!enabled)
			return;
		counts[0]++;
		if(rejected)
			counts[1]++;
	}
	
	public static boolean submitted(boolean allowed)
	{
		if(enabled && allowed)
			counts[2]++;
		return allowed;
	}
	
	static int[] mesh(int[] ranges, boolean fallback)
	{
		if(enabled)
		{
			if(ranges == null)
				counts[4]++;
			else if(ranges.length == 0)
				counts[3]++;
			else
			{
				counts[5]++;
				counts[6] += ranges.length / 2;
			}
			if(fallback)
				counts[7]++;
		}
		return ranges;
	}
	
	public static void appendOverlay(List<String> lines)
	{
		if(!enabled)
			return;
		lines.add("ESP/frame: boxes " + counts[0] + " | rejected " + counts[1]
			+ " | submitted " + counts[2]);
		lines.add("Meshes: rejected " + counts[3] + " | full " + counts[4]
			+ " | partial " + counts[5]);
		lines
			.add("Mesh ranges " + counts[6] + " | full fallbacks " + counts[7]);
		lines.add("World text calls: allowed " + counts[8] + " | skipped "
			+ counts[9]);
	}
	
	static boolean label(boolean rejected)
	{
		if(enabled)
			counts[rejected ? 9 : 8]++;
		return rejected;
	}
}
