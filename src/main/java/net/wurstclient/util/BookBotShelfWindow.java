/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayDeque;

/** Monotonic cadence and bounded response history, independent of outcomes. */
public final class BookBotShelfWindow
{
	private final ArrayDeque<Long> latencies = new ArrayDeque<>();
	private long lastUpdate;
	private long period = 1_000_000_000L;
	private int intervals;
	private boolean reliable = true;
	
	public void update(long now)
	{
		if(lastUpdate != 0)
		{
			long interval = now - lastUpdate;
			reliable = interval >= 800_000_000L && interval <= 1_200_000_000L;
			if(reliable)
			{
				period = (period * 3 + interval) / 4;
				intervals++;
			}else
				intervals = 0;
		}
		lastUpdate = now;
	}
	
	public boolean usable(long now)
	{
		return reliable && intervals >= 2 && now >= lastUpdate
			&& now - lastUpdate <= 2_500_000_000L;
	}
	
	public long phase(long now)
	{
		return lastUpdate == 0 ? -1 : Math.floorMod(now - lastUpdate, period);
	}
	
	public long latency()
	{
		return latencies.isEmpty() ? 500_000_000L : (long)latencies.stream()
			.mapToLong(Long::longValue).average().orElseThrow();
	}
	
	public long uncertainty()
	{
		if(latencies.size() < 3)
			return 350_000_000L;
		long mean = latency();
		return (long)Math.sqrt(
			latencies.stream().mapToDouble(v -> (double)(v - mean) * (v - mean))
				.average().orElseThrow());
	}
	
	public void observe(long nanos)
	{
		latencies.addLast(Math.clamp(nanos, 1_000_000L, 10_000_000_000L));
		if(latencies.size() > 12)
			latencies.removeFirst();
	}
	
	public boolean shouldSubmit(boolean enabled, long now)
	{
		return !enabled || usable(now) && inWindow(phase(now + latency()));
	}
	
	public static boolean inWindow(long phase)
	{
		return phase >= 250_000_000L && phase <= 700_000_000L;
	}
	
	public long period()
	{
		return period;
	}
	
	public long lastUpdate()
	{
		return lastUpdate;
	}
}
