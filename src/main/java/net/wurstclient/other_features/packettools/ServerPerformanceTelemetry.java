/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.util.*;

/**
 * Passive estimates and explicitly trusted server measurements retain separate
 * clocks and sources.
 */
public final class ServerPerformanceTelemetry
{
	public final ServerTickEstimator passive = new ServerTickEstimator();
	private Map<String, Object> authoritative = Map.of();
	private long receivedNs, lastTick = -1, rejected;
	private String serverSession;
	
	public synchronized boolean acceptAuthoritative(Map<String, Object> sample,
		long receiveNs)
	{
		if(!(sample.get("protocolVersion") instanceof Number version)
			|| version.doubleValue() != 1
			|| !(sample.get("serverSessionId") instanceof String id)
			|| !(sample.get("serverTickNumber") instanceof Number tick)
			|| !finite(sample, "actualMSPT", 0, 60000)
			|| !finite(sample, "rollingTPS", 0, 10000)
			|| !finite(sample, "configuredTickRate", 0.001, 10000)
			|| !finite(sample, "sampleAgeAtTransmissionNs", 0,
				Double.MAX_VALUE))
		{
			rejected++;
			return false;
		}
		try
		{
			UUID.fromString(id);
		}catch(IllegalArgumentException e)
		{
			rejected++;
			return false;
		}
		if(id.equals(serverSession) && tick.longValue() <= lastTick)
		{
			rejected++;
			return false;
		}
		authoritative =
			Collections.unmodifiableMap(new LinkedHashMap<>(sample));
		receivedNs = receiveNs;
		lastTick = tick.longValue();
		serverSession = id;
		return true;
	}
	
	public synchronized Map<String, Object> snapshot(long now)
	{
		Map<String, Object> result = new LinkedHashMap<>(passive.snapshot(now));
		result.put("serverTelemetrySource", "PASSIVE_ESTIMATE_ONLY");
		if(!authoritative.isEmpty())
		{
			double age = Math.max(0, now - receivedNs) / 1e6
				+ ((Number)authoritative.get("sampleAgeAtTransmissionNs"))
					.doubleValue() / 1e6;
			result.put("serverTelemetryAgeIsLowerBound", true);
			result.put("serverTelemetryAgeBasis",
				"SERVER_MEASUREMENT_TO_SEND_PLUS_CLIENT_RECEIVE_AGE; ONE_WAY_NETWORK_DELAY_UNKNOWN");
			result.put("serverTelemetryAgeMs", age);
			if(age <= 5000)
			{
				result.putIfAbsent("configuredTickRate",
					authoritative.get("configuredTickRate"));
				result.put("actualServerTps", authoritative.get("rollingTPS"));
				result.put("actualServerMspt", authoritative.get("actualMSPT"));
				result.put("serverTelemetrySource",
					"TRUSTED_CONTROLLED_SERVER_COMPONENT_V1");
				result.put("serverSessionId", serverSession);
				result.put("serverTickNumber", lastTick);
				result.put("serverTickNumberIsSampleNotActionAssignment", true);
				result.put("actualServerTpsDefinition",
					"MEASURED_MAIN_TICK_LOOP_START_RATE; GAME_FREEZE_IS_SEPARATE");
				for(String key : List.of("tickStartNs", "tickEndNs",
					"timestamp", "observationCompleteness", "tickDurationNs",
					"tickIntervalNs", "tickSchedulingDelayNs",
					"rollingAverageMspt", "medianMspt", "p95Mspt", "p99Mspt",
					"maximumMspt", "ticksOverBudget", "sampleWindow",
					"serverTickFrozen", "serverTickSprinting"))
					if(authoritative.get(key) != null)
						result.put(key, authoritative.get(key));
				result.put("serverClockDomain",
					"SERVER_LOCAL; NOT_ALIGNED_TO_CLIENT_NANOTIME");
			}else
				result.put("authoritativeTelemetryAvailability", "STALE");
		}else
			result.put("authoritativeTelemetryAvailability",
				"ABSENT_OR_NOT_TRUSTED");
		if(result.get("window5s") instanceof Map<?, ?> stats)
			result.put("receiveIntervalJitterMs",
				stats.get("receiveIntervalStddevMs"));
		return Collections.unmodifiableMap(result);
	}
	
	public synchronized Map<String, Object> summary()
	{
		return Map.of("passive", passive.summary(),
			"authoritativeSamplesRejected", rejected, "snapshot",
			snapshot(System.nanoTime()), "crossMachineClockAlignment",
			"NOT_IMPLEMENTED; RAW_CLOCKS_NEVER_SUBTRACTED");
	}
	
	private static boolean finite(Map<String, Object> map, String key,
		double min, double max)
	{
		return map.get(key) instanceof Number n
			&& Double.isFinite(n.doubleValue()) && n.doubleValue() >= min
			&& n.doubleValue() <= max;
	}
}
