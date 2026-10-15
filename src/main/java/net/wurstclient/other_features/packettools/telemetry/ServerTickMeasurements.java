/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools.telemetry;

import java.util.*;

/**
 * Fixed-size raw tick ring; only timestamp storage is performed at tick
 * boundaries.
 */
public final class ServerTickMeasurements
{
	private final long[][] samples = new long[8192][6];
	private int size, cursor;
	private double rate;
	private boolean frozen, sprinting;
	private long tick;
	
	public synchronized void record(long number, long start, long end,
		long deadline, double configured, boolean frozen, boolean sprinting)
	{
		if(end < start || !Double.isFinite(configured) || configured <= 0)
			return;
		if(rate != configured)
		{
			size = cursor = 0;
		}
		rate = configured;
		this.frozen = frozen;
		this.sprinting = sprinting;
		tick = number;
		long[] sample = samples[cursor];
		sample[0] = start;
		sample[1] = end;
		sample[2] = end - start;
		sample[3] = sprinting ? -1 : Math.max(0, start - deadline);
		sample[4] = number;
		sample[5] = (long)(1e9 / configured);
		cursor = (cursor + 1) % samples.length;
		size = Math.min(size + 1, samples.length);
	}
	
	public Map<String, Object> snapshot(long now)
	{
		List<long[]> copy = new ArrayList<>();
		double configured;
		boolean isFrozen, isSprinting;
		long number;
		synchronized(this)
		{
			configured = rate;
			isFrozen = frozen;
			isSprinting = sprinting;
			number = tick;
			for(int i = 0; i < size; i++)
			{
				long[] sample = samples[(cursor - size + i + samples.length)
					% samples.length];
				if(now - sample[0] <= 5_000_000_000L)
					copy.add(sample.clone());
			}
		}
		if(copy.size() < 2)
			return Map.of();
		long[] first = copy.getFirst(), last = copy.getLast(),
			beforeLast = copy.get(copy.size() - 2);
		long span = last[0] - first[0];
		if(span <= 0)
			return Map.of();
		long[] durations =
			copy.stream().mapToLong(v -> v[2]).sorted().toArray();
		long over = copy.stream().filter(v -> v[2] > v[5]).count();
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("protocolVersion", 1);
		map.put("serverTickNumber", number);
		map.put("configuredTickRate", configured);
		map.put("actualMSPT", last[2] / 1e6);
		map.put("rollingTPS", (copy.size() - 1) * 1e9 / span);
		map.put("tickStartNs", last[0]);
		map.put("tickEndNs", last[1]);
		map.put("tickDurationNs", last[2]);
		map.put("tickIntervalNs", last[0] - beforeLast[0]);
		if(last[3] >= 0)
			map.put("tickSchedulingDelayNs", last[3]);
		map.put("rollingAverageMspt",
			Arrays.stream(durations).average().orElseThrow() / 1e6);
		map.put("medianMspt",
			(durations.length % 2 == 0
				? (durations[durations.length / 2 - 1]
					+ durations[durations.length / 2]) / 2.0
				: durations[durations.length / 2]) / 1e6);
		map.put("p95Mspt",
			durations[(int)Math.ceil(durations.length * 0.95) - 1] / 1e6);
		map.put("p99Mspt",
			durations[(int)Math.ceil(durations.length * 0.99) - 1] / 1e6);
		map.put("maximumMspt", durations[durations.length - 1] / 1e6);
		map.put("ticksOverBudget", over);
		map.put("sampleWindow", Map.of("sampleCount", copy.size(),
			"coveredSeconds", span / 1e9, "maximumSeconds", 5));
		map.put("serverTickFrozen", isFrozen);
		map.put("serverTickSprinting", isSprinting);
		map.put("timestamp", java.time.Instant.now().toString());
		map.put("observationCompleteness",
			"MEASURED_PROCESS_PACKETS_AND_TICK_BOUNDARIES; NO_BOOK_HANDLER_CAUSES");
		map.put("tickSchedulingDelayDefinition",
			"max(0, process-entry minus (Minecraft nextTickTime minus configured tick budget)); omitted while sprinting");
		map.put("clockDomain",
			"SERVER_MINECRAFT_UTIL_GETNANOS; UNALIGNED_WITH_CLIENT");
		return map;
	}
}
