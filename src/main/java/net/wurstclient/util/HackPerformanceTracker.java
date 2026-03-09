/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Map;
import net.wurstclient.WurstClient;
import net.wurstclient.event.Listener;
import net.wurstclient.hack.Hack;
import net.wurstclient.other_features.PerformanceOverlayOtf.SortMode;

public enum HackPerformanceTracker
{
	;
	
	private static final long WINDOW_NS = 1_000_000_000L;
	private static final long STALE_NS = 30_000_000_000L;
	
	private static final Object LOCK = new Object();
	private static final IdentityHashMap<Hack, Stats> STATS =
		new IdentityHashMap<>();
	
	private static long windowStartNs = System.nanoTime();
	private static boolean hasCompletedWindow;
	
	public enum Phase
	{
		UPDATE,
		RENDER,
		GUI
	}
	
	public static boolean shouldProfile()
	{
		WurstClient wurst = WurstClient.INSTANCE;
		if(wurst == null || !wurst.isEnabled() || wurst.getOtfs() == null
			|| wurst.getOtfs().performanceOverlayOtf == null)
			return false;
		
		return wurst.getOtfs().performanceOverlayOtf.isEnabled();
	}
	
	public static void record(Listener listener, Phase phase, long durationNs)
	{
		if(!(listener instanceof Hack hack) || durationNs <= 0
			|| !hack.isEnabled())
			return;
		
		long nowNs = System.nanoTime();
		synchronized(LOCK)
		{
			rollWindow(nowNs);
			Stats stats = STATS.computeIfAbsent(hack, h -> new Stats());
			stats.record(phase, durationNs, nowNs);
		}
	}
	
	public static ListSnapshot getTopRows(int maxRows, SortMode sortMode)
	{
		long nowNs = System.nanoTime();
		synchronized(LOCK)
		{
			rollWindow(nowNs);
			
			Collection<Map.Entry<Hack, Stats>> entries = STATS.entrySet();
			ArrayList<Row> rows = new ArrayList<>(entries.size());
			for(Map.Entry<Hack, Stats> e : entries)
			{
				Hack hack = e.getKey();
				Stats stats = e.getValue();
				if(hack == null || stats == null)
					continue;
				if(!hack.isEnabled() && nowNs - stats.lastSeenNs > STALE_NS)
					continue;
				
				rows.add(stats.toRow(hack.getName(), hasCompletedWindow));
			}
			
			if(sortMode == SortMode.PEAK_TIME)
				rows.sort(Comparator.comparingDouble(Row::peakMs).reversed());
			else
				rows.sort(Comparator.comparingDouble(Row::totalMs).reversed());
			
			int limit = Math.max(1, maxRows);
			if(rows.size() > limit)
				rows = new ArrayList<>(rows.subList(0, limit));
			
			return new ListSnapshot(rows, hasCompletedWindow);
		}
	}
	
	private static void rollWindow(long nowNs)
	{
		if(nowNs - windowStartNs < WINDOW_NS)
			return;
		
		long windowsElapsed = Math.max(1L, (nowNs - windowStartNs) / WINDOW_NS);
		windowStartNs += windowsElapsed * WINDOW_NS;
		hasCompletedWindow = true;
		
		ArrayList<Hack> stale = new ArrayList<>();
		for(Map.Entry<Hack, Stats> e : STATS.entrySet())
		{
			Stats stats = e.getValue();
			stats.rollWindow();
			
			Hack hack = e.getKey();
			if((hack == null || !hack.isEnabled())
				&& nowNs - stats.lastSeenNs > STALE_NS)
				stale.add(hack);
		}
		
		for(Hack hack : stale)
			STATS.remove(hack);
	}
	
	private static final class Stats
	{
		private final long[] currentTotalNs = new long[Phase.values().length];
		private final int[] currentCalls = new int[Phase.values().length];
		private final long[] currentMaxNs = new long[Phase.values().length];
		
		private final long[] lastTotalNs = new long[Phase.values().length];
		private final int[] lastCalls = new int[Phase.values().length];
		private final long[] lastMaxNs = new long[Phase.values().length];
		
		private final long[] peakNs = new long[Phase.values().length];
		private long lastSeenNs = System.nanoTime();
		
		private void record(Phase phase, long durationNs, long nowNs)
		{
			int idx = phase.ordinal();
			currentTotalNs[idx] += durationNs;
			currentCalls[idx]++;
			if(durationNs > currentMaxNs[idx])
				currentMaxNs[idx] = durationNs;
			if(durationNs > peakNs[idx])
				peakNs[idx] = durationNs;
			lastSeenNs = nowNs;
		}
		
		private void rollWindow()
		{
			for(int i = 0; i < currentTotalNs.length; i++)
			{
				lastTotalNs[i] = currentTotalNs[i];
				lastCalls[i] = currentCalls[i];
				lastMaxNs[i] = currentMaxNs[i];
				currentTotalNs[i] = 0;
				currentCalls[i] = 0;
				currentMaxNs[i] = 0;
			}
		}
		
		private Row toRow(String name, boolean useLastWindow)
		{
			long[] totals = useLastWindow ? lastTotalNs : currentTotalNs;
			long[] maxes = useLastWindow ? lastMaxNs : currentMaxNs;
			
			double updateMs = nanosToMillis(totals[Phase.UPDATE.ordinal()]);
			double renderMs = nanosToMillis(totals[Phase.RENDER.ordinal()]);
			double guiMs = nanosToMillis(totals[Phase.GUI.ordinal()]);
			double totalMs = updateMs + renderMs + guiMs;
			
			double windowPeakMs =
				nanosToMillis(Math.max(maxes[Phase.UPDATE.ordinal()],
					Math.max(maxes[Phase.RENDER.ordinal()],
						maxes[Phase.GUI.ordinal()])));
			double lifetimePeakMs =
				nanosToMillis(Math.max(peakNs[Phase.UPDATE.ordinal()],
					Math.max(peakNs[Phase.RENDER.ordinal()],
						peakNs[Phase.GUI.ordinal()])));
			
			return new Row(name, updateMs, renderMs, guiMs, totalMs,
				Math.max(windowPeakMs, lifetimePeakMs));
		}
		
		private double nanosToMillis(long ns)
		{
			return ns / 1_000_000D;
		}
	}
	
	public record Row(String name, double updateMs, double renderMs,
		double guiMs, double totalMs, double peakMs)
	{}
	
	public record ListSnapshot(ArrayList<Row> rows, boolean usingWindowData)
	{}
}
