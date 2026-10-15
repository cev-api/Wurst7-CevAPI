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
 * Receive-clock estimates: never server tick duration or an exact server phase.
 */
public final class ServerTickEstimator
{
	private record Point(long ticks, long ns)
	{}
	
	private final ArrayDeque<Point> points = new ArrayDeque<>();
	private final Map<String, Long> irregularities = new LinkedHashMap<>();
	private final ServerUpdatePhaseEstimator periodicPhase =
		new ServerUpdatePhaseEstimator();
	private Double configured;
	private boolean frozen, stepping;
	private int stepsRemaining;
	private long stepStartGameTime = -1;
	private long lastObservedGameTime = -1;
	private String dimension;
	private long samples, invalid, invalidWindows, lastValidNs, lastReceiveNs;
	private boolean lowActive;
	private String reason = "NO_TIME_PACKETS";
	private Map<String, Object> estimates = Map.of();
	private boolean staleReported;
	
	public synchronized List<Map<String, Object>> control(double rate,
		boolean freeze, boolean step, long now)
	{
		List<Map<String, Object>> events = new ArrayList<>();
		if(!Double.isFinite(rate) || rate <= 0 || rate > 10000)
		{
			events.add(unavailable("INVALID_CONFIGURED_RATE", now));
			return events;
		}
		boolean changed =
			configured == null || Double.compare(configured, rate) != 0
				|| frozen != freeze || stepping != step;
		if(changed)
		{
			Map<String, Object> e = event("server_tick_rate_changed", now);
			if(configured != null)
				e.put("previousConfiguredTickRate", configured);
			e.put("configuredTickRate", rate);
			e.put("frozen", freeze);
			e.put("stepping", step);
			events.add(e);
			points.clear();
			estimates = Map.of();
			reason = freeze || step ? "TICK_FREEZE_OR_STEP"
				: "CONTROL_CHANGED_WARMUP";
		}
		configured = rate;
		frozen = freeze;
		stepping = step;
		stepsRemaining = step ? Math.max(1, stepsRemaining) : 0;
		stepStartGameTime = -1;
		return events;
	}
	
	public synchronized List<Map<String, Object>> dimensionChanged(String next,
		long now)
	{
		if(Objects.equals(dimension, next))
			return List.of();
		dimension = next;
		points.clear();
		estimates = Map.of();
		frozen = false;
		stepping = false;
		stepsRemaining = 0;
		stepStartGameTime = -1;
		lastObservedGameTime = -1;
		periodicPhase.reset();
		reason = "DIMENSION_CONTINUITY_UNKNOWN";
		return List.of(irregular(reason, now), unavailable(reason, now));
	}
	
	public synchronized List<Map<String, Object>> step(long now,
		int requestedSteps)
	{
		stepsRemaining = Math.max(0, requestedSteps);
		stepping = stepsRemaining > 0;
		stepStartGameTime = stepping ? lastObservedGameTime : -1;
		points.clear();
		estimates = Map.of();
		reason = stepping ? "TICK_FREEZE_OR_STEP" : "STEP_COUNT_ZERO";
		return stepping
			? List.of(irregular("TICK_STEPPING", now), unavailable(reason, now))
			: List.of(event("server_tick_step_cleared", now));
	}
	
	public synchronized List<Map<String, Object>> sample(long gameTime,
		long now, String dim, long clientTick, String packetId)
	{
		List<Map<String, Object>> out = new ArrayList<>();
		samples++;
		if(!Objects.equals(dimension, dim))
			periodicPhase.reset();
		periodicPhase.observe(now);
		Map<String, Object> e = event("server_tick_sample", now);
		e.putAll(periodicPhase.snapshot(now));
		e.put("localReceiveMonotonicNs", now);
		e.put("serverGameTime", gameTime);
		e.put("dimension", dim);
		e.put("clientTick", clientTick);
		e.put("logicalPacketId", packetId);
		if(configured != null)
			e.put("configuredTickRate", configured);
		if(!Objects.equals(dimension, dim))
		{
			if(dimension != null)
				out.add(irregular("DIMENSION_CONTINUITY_UNKNOWN", now));
			points.clear();
			estimates = Map.of();
			lastObservedGameTime = -1;
			dimension = dim;
			reason = "DIMENSION_BASELINE";
		}
		lastReceiveNs = now;
		staleReported = false;
		Point previous = points.peekLast();
		if(stepping && stepStartGameTime < 0 && gameTime >= 0)
			stepStartGameTime = gameTime;
		if(stepping && stepStartGameTime >= 0 && gameTime > stepStartGameTime)
		{
			long advanced = gameTime - stepStartGameTime;
			stepsRemaining = (int)Math.max(0, stepsRemaining - advanced);
			stepStartGameTime = gameTime;
			if(stepsRemaining == 0)
			{
				stepping = false;
				stepStartGameTime = -1;
				points.clear();
				previous = null;
				reason = "STEP_COMPLETED";
			}
		}
		String invalidReason = null;
		if(gameTime < 0)
			invalidReason = "NEGATIVE_NONSTANDARD_GAME_TIME";
		else if(frozen || stepping)
			invalidReason = "TICK_FREEZE_OR_STEP";
		else if(previous != null && now <= previous.ns)
			invalidReason = "NON_INCREASING_RECEIVE_CLOCK";
		else if(previous != null && gameTime <= previous.ticks)
			invalidReason = gameTime < previous.ticks
				? "COUNTER_RESET_OR_MANIPULATED" : "NO_TICK_PROGRESSION";
		if(invalidReason != null)
		{
			invalid++;
			e.put("valid", false);
			e.put("invalidReason", invalidReason);
			out.add(e);
			out.add(irregular(invalidReason, now));
			points.clear();
			estimates = Map.of();
			reason = invalidReason;
			if(!frozen && !stepping)
				points.add(new Point(gameTime, now));
			if(gameTime >= 0)
				lastObservedGameTime = gameTime;
			out.add(unavailable(reason, now));
			return out;
		}
		e.put("valid", previous != null);
		e.put("sampleConfidence",
			previous == null ? "BASELINE" : "CLIENT_OBSERVED_ESTIMATE");
		if(previous != null)
		{
			long ticks = gameTime - previous.ticks, ns = now - previous.ns;
			double seconds = ns / 1e9, rate = ticks / seconds;
			e.put("elapsedServerTicks", ticks);
			e.put("elapsedReceiveNs", ns);
			e.put("intervalEstimatedTps", rate);
			if(configured != null)
			{
				double expected = ticks / configured;
				if(seconds > Math.max(2, expected * 1.8))
					out.add(irregular("LONG_RECEIVE_INTERVAL_SERVER_OR_NETWORK",
						now));
				if(seconds < expected * 0.25)
					out.add(irregular("BURST_OR_NONSTANDARD_COUNTER", now));
			}
			lastValidNs = now;
		}
		points.addLast(new Point(gameTime, now));
		lastObservedGameTime = gameTime;
		while(points.size() > 256 || (!points.isEmpty()
			&& now - points.peekFirst().ns > 65_000_000_000L))
			points.removeFirst();
		out.add(e);
		long rejectedBefore = invalidWindows;
		estimates = calculate(now);
		if(estimates.isEmpty())
		{
			reason = invalidWindows > rejectedBefore
				? "COUNTER_RATE_OUTSIDE_CONFIGURED_ENVELOPE"
				: "INSUFFICIENT_PROGRESS_WINDOW";
			if(invalidWindows > rejectedBefore)
			{
				invalid++;
				e.put("valid", false);
				e.put("invalidReason", reason);
			}
			out.add(unavailable(reason, now));
		}else
		{
			reason = "AVAILABLE";
			Map<String, Object> estimate = event("server_tps_estimate", now);
			estimate.putAll(estimates);
			estimate.put("dimension", dim);
			out.add(estimate);
			Object shortRate = estimates.get("estimatedTps5s");
			boolean low = shortRate instanceof Number n && configured != null
				&& n.doubleValue() < configured * 0.8 && points.size() >= 6;
			if(low && !lowActive)
				out.add(irregular(
					"SUSTAINED_LOW_ESTIMATE_NOT_PROOF_OF_SERVER_OVERRUN", now));
			lowActive = low;
		}
		return out;
	}
	
	private Map<String, Object> calculate(long now)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		List<Point> all = new ArrayList<>(points);
		for(int window : new int[]{5, 15, 30, 60})
		{
			long cutoff = now - window * 1_000_000_000L;
			int start = 0;
			while(start + 1 < all.size() && all.get(start + 1).ns <= cutoff)
				start++;
			List<Point> selected = all.subList(start, all.size());
			if(selected.size() < 3)
				continue;
			Point first = selected.getFirst(), last = selected.getLast();
			double seconds = (last.ns - first.ns) / 1e9;
			if(seconds < window * 0.8)
				continue;
			double rate = (last.ticks - first.ticks) / seconds;
			if(!Double.isFinite(rate) || rate <= 0
				|| configured != null && rate > configured * 1.25 + 0.5)
			{
				invalidWindows++;
				continue;
			}
			List<Double> intervals = new ArrayList<>(),
				receiveIntervals = new ArrayList<>();
			for(int i = 1; i < selected.size(); i++)
			{
				Point a = selected.get(i - 1), b = selected.get(i);
				double dt = (b.ns - a.ns) / 1e9;
				if(dt > 0)
				{
					intervals.add((b.ticks - a.ticks) / dt);
					receiveIntervals.add(dt * 1000);
				}
			}
			intervals.sort(Double::compare);
			receiveIntervals.sort(Double::compare);
			double mean =
				receiveIntervals.stream().mapToDouble(Double::doubleValue)
					.average().orElse(0),
				variance = receiveIntervals.stream()
					.mapToDouble(v -> (v - mean) * (v - mean)).average()
					.orElse(0);
			int trim = intervals.size() / 10;
			double trimmed =
				intervals.subList(trim, intervals.size() - trim).stream()
					.mapToDouble(Double::doubleValue).average().orElse(rate);
			double cv = mean == 0 ? 0 : Math.sqrt(variance) / mean;
			String confidence = configured == null || cv > 0.25 ? "LOW"
				: seconds >= window * 0.95 && selected.size() >= 5 ? "HIGH"
					: "MEDIUM";
			result.put("estimatedTps" + window + "s", rate);
			result.put("window" + window + "s",
				Map.of("sampleCount", selected.size() - 1, "coveredSeconds",
					seconds, "medianIntervalTps", median(intervals),
					"trimmedMeanIntervalTps", trimmed,
					"receiveIntervalStddevMs", Math.sqrt(variance),
					"receiveIntervalCoefficientOfVariation", cv,
					"sampleConfidence", confidence));
		}
		if(!result.isEmpty())
		{
			result.put("sampleCount", points.size());
			result.put("sampleConfidence",
				((Map<?, ?>)result.getOrDefault("window5s",
					result.entrySet().stream()
						.filter(entry -> entry.getKey().startsWith("window"))
						.findFirst().orElseThrow().getValue()))
							.get("sampleConfidence"));
			result.put("estimator",
				"GAME_TIME_DELTA_OVER_LOCAL_RECEIVE_INTERVAL; NETWORK_BUFFERING_CAN_DISTORT");
		}
		return Collections.unmodifiableMap(result);
	}
	
	public synchronized Map<String, Object> snapshot(long now)
	{
		Map<String, Object> result = new LinkedHashMap<>();
		if(dimension != null)
			result.put("dimension", dimension);
		if(configured != null)
			result.put("configuredTickRate", configured);
		if(configured != null || stepping)
		{
			result.put("tickFrozen", frozen);
			result.put("tickStepping", stepping);
		}
		result.put("invalidTickSamples", invalid);
		result.put("passiveTelemetrySource", "CLIENT_OBSERVED_GAME_TIME");
		if(lastReceiveNs != 0)
		{
			result.put("timeUpdateAgeMs",
				Math.max(0, now - lastReceiveNs) / 1e6);
			result.put("lastTimeUpdateReceiveNs", lastReceiveNs);
		}
		if(lastValidNs != 0)
			result.put("timeSinceLastValidSampleMs",
				Math.max(0, now - lastValidNs) / 1e6);
		boolean stale =
			lastReceiveNs == 0 || now - lastReceiveNs > staleAfterNs();
		result.put("serverTickEstimateAvailability",
			stale ? "STALE_OR_ABSENT" : reason);
		if(!stale && !frozen && !stepping)
			result.putAll(estimates);
		result.put("serverTickPhase",
			"UNOBSERVABLE_FROM_PERIODIC_TIME_UPDATES");
		return Collections.unmodifiableMap(result);
	}
	
	public synchronized List<Map<String, Object>> checkStale(long now)
	{
		if(lastReceiveNs != 0 && now - lastReceiveNs > staleAfterNs()
			&& !staleReported)
		{
			staleReported = true;
			return List.of(
				irregular("TIME_UPDATES_STALE_SERVER_OR_NETWORK", now),
				unavailable("STALE_TIME_UPDATES", now));
		}
		return List.of();
	}
	
	private long staleAfterNs()
	{
		return (long)(Math.max(3, configured == null ? 5 : 60 / configured)
			* 1e9);
	}
	
	public Map<String, Object> periodicPhaseSnapshot(long now)
	{
		return periodicPhase.snapshot(now);
	}
	
	public synchronized Map<String, Object> summary()
	{
		return Map.of("timePacketsObserved", samples, "invalidSamples", invalid,
			"invalidWindowEstimates", invalidWindows, "irregularities",
			Map.copyOf(irregularities), "lastSnapshot",
			snapshot(System.nanoTime()));
	}
	
	private Map<String, Object> irregular(String reason, long now)
	{
		irregularities.merge(reason, 1L, Long::sum);
		Map<String, Object> event = event("server_tick_irregularity", now);
		event.put("indicator", reason);
		event.put("interpretation",
			"CLIENT_RECEIVE_OBSERVATION; NOT_PROOF_OF_SERVER_CAUSE");
		return event;
	}
	
	private Map<String, Object> unavailable(String reason, long now)
	{
		Map<String, Object> event =
			event("server_tick_estimate_unavailable", now);
		event.put("reason", reason);
		return event;
	}
	
	private static Map<String, Object> event(String type, long now)
	{
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("eventType", type);
		event.put("monotonicNs", now);
		event.put("timestamp", java.time.Instant.now().toString());
		return event;
	}
	
	private static double median(List<Double> values)
	{
		int n = values.size();
		return n % 2 == 0 ? (values.get(n / 2 - 1) + values.get(n / 2)) / 2
			: values.get(n / 2);
	}
}
