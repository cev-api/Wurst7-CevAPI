/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adaptive client-observed submission scheduler. A phase here is relative to
 * received time-update packets, not an authoritative server tick phase.
 */
public final class BookBotPhaseLock
{
	public enum State
	{
		CALIBRATING,
		WAITING_FOR_WINDOW,
		PROVISIONAL_LOCK,
		LOCKED,
		RECALIBRATING
	}
	
	public record Decision(boolean submitNow, State state,
		double targetSubmissionPhase, long selectedDeadlineNs,
		double timingUncertaintyMs, String reason)
	{}
	
	private record Sample(double phase, double latencyMs)
	{}
	
	private static final class ModeModel
	{
		final ArrayDeque<Sample> successes = new ArrayDeque<>();
		final ArrayDeque<Double> failures = new ArrayDeque<>();
		final ArrayDeque<Double> latencies = new ArrayDeque<>();
		State state = State.CALIBRATING;
		String reason = "NO_VERIFIED_OUTCOMES";
		int consecutiveSuccesses, consecutiveFailures;
	}
	
	private static final class Attempt
	{
		final String mode;
		final double submittedPhase;
		final long submittedNs;
		final long cycleNs;
		double contentPhase = Double.NaN;
		double latencyMs = Double.NaN;
		boolean contentConfirmed;
		
		Attempt(String mode, double phase, long now, long cycle)
		{
			this.mode = mode;
			submittedPhase = phase;
			submittedNs = now;
			cycleNs = cycle;
		}
	}
	
	private final Map<String, ModeModel> modes = new HashMap<>();
	private final Map<String, Attempt> attempts = new HashMap<>();
	
	public synchronized Decision evaluate(String mode,
		Map<String, Object> phase, long now)
	{
		ModeModel model =
			modes.computeIfAbsent(mode, unused -> new ModeModel());
		if(!Boolean.TRUE.equals(phase.get("phaseAvailable"))
			|| !(phase.get("periodicUpdatePhase") instanceof Number phaseValue)
			|| !(phase
				.get("periodicUpdateCycleNs") instanceof Number cycleValue)
			|| !(phase
				.get("timingUncertaintyMs") instanceof Number uncertainty))
		{
			transition(model,
				model.successes.isEmpty() ? State.CALIBRATING
					: State.RECALIBRATING,
				String.valueOf(
					phase.getOrDefault("phaseReason", "PHASE_UNAVAILABLE")));
			return new Decision(false, model.state, Double.NaN, 0,
				Double.POSITIVE_INFINITY, model.reason);
		}
		double current = wrap(phaseValue.doubleValue());
		long cycle = cycleValue.longValue();
		double periodMs = cycle / 1e6;
		if(cycle <= 0 || uncertainty.doubleValue() >= periodMs * 0.15)
		{
			transition(model, State.RECALIBRATING,
				"TIMING_UNCERTAINTY_EXCEEDS_15_PERCENT_OF_CYCLE");
			return new Decision(false, model.state, Double.NaN, 0,
				uncertainty.doubleValue(), model.reason);
		}
		ModeModel hypothesis =
			model.successes.size() >= 2 ? model : modes.get("PLACE_ON_SHELF");
		boolean borrowed = hypothesis != model && hypothesis != null
			&& hypothesis.successes.size() >= 2;
		if(hypothesis == null || hypothesis.successes.isEmpty())
			hypothesis = model;
		double favourable = hypothesis.successes.isEmpty() ? 0.10
			: circularMeanPhases(hypothesis.successes);
		double latencyMs = mean(hypothesis.latencies);
		double latencyStdMs =
			standardDeviation(hypothesis.latencies, latencyMs);
		double target = wrap(favourable - latencyMs / periodMs);
		double radius = Math.max(0.035,
			(uncertainty.doubleValue() + 2 * latencyStdMs) / periodMs + 0.02);
		if(radius > 0.15)
		{
			transition(model, State.RECALIBRATING,
				"PREDICTION_VARIANCE_EXCEEDS_SAFE_WINDOW");
			return new Decision(false, model.state, target, 0,
				uncertainty.doubleValue() + 2 * latencyStdMs, model.reason);
		}
		State base =
			model.consecutiveSuccesses >= 4 && consistent(model.successes, 0.10)
				&& coefficientOfVariation(model.latencies) < 0.20
					? State.LOCKED
					: model.consecutiveSuccesses >= 2
						&& consistent(model.successes, 0.10)
							? State.PROVISIONAL_LOCK
							: model.state == State.RECALIBRATING
								? State.RECALIBRATING : State.CALIBRATING;
		long nextUpdate =
			((Number)phase.getOrDefault("estimatedNextPeriodicUpdateNs", now))
				.longValue();
		boolean ready = circularDistance(current, target) <= radius;
		long deadline = ready ? now
			: now + (long)(forwardDistance(current, target) * cycle);
		State next = model.consecutiveSuccesses < 2 ? State.CALIBRATING
			: ready ? base : State.WAITING_FOR_WINDOW;
		String reason = borrowed ? "SHELF_SUCCESS_PHASE_HYPOTHESIS"
			: hypothesis.successes.isEmpty() ? "INITIAL_EXPLORATION_WINDOW"
				: "LEARNED_MODE_SPECIFIC_SUCCESS_PHASE";
		transition(model, next, reason);
		return new Decision(ready, model.state, target, deadline,
			uncertainty.doubleValue() + 2 * latencyStdMs, reason);
	}
	
	public synchronized Map<String, Object> submitted(String transactionId,
		String mode, double phase, long now, long cycleNs, long deadlineNs)
	{
		attempts.put(transactionId,
			new Attempt(mode, wrap(phase), now, cycleNs));
		Map<String, Object> event = event("PHASELOCK_EDIT_SUBMITTED", mode, now,
			Map.of("submissionPhase", wrap(phase), "selectedDeadlineNs",
				deadlineNs, "cycleNs", cycleNs));
		event.put("transactionId", transactionId);
		return event;
	}
	
	public synchronized Map<String, Object> contentConfirmed(
		String transactionId, double phase, long now)
	{
		Attempt attempt = attempts.get(transactionId);
		if(attempt == null)
			return Map.of();
		attempt.contentConfirmed = true;
		attempt.contentPhase = wrap(phase);
		attempt.latencyMs = Math.max(0, now - attempt.submittedNs) / 1e6;
		Map<String, Object> event =
			event("PHASELOCK_CONTENT_CONFIRMED", attempt.mode, now,
				Map.of("contentConfirmationPhase", attempt.contentPhase,
					"editSubmissionToContentConfirmationMs", attempt.latencyMs,
					"submissionPhase", attempt.submittedPhase));
		event.put("transactionId", transactionId);
		return event;
	}
	
	public synchronized Map<String, Object> outcome(String transactionId,
		String outcome, double phase, long now)
	{
		Attempt attempt = attempts.remove(transactionId);
		if(attempt == null)
			return Map.of();
		ModeModel model =
			modes.computeIfAbsent(attempt.mode, unused -> new ModeModel());
		boolean success = switch(attempt.mode)
		{
			case "PLACE_ON_SHELF" -> outcome
				.equals("SERVER_CONFIRMED_MATCHING_CONTENT");
			case "KEEP" -> outcome.equals("KEEP_CONTENT_STABLE");
			case "DROP" -> outcome.equals("DROP_ENTITY_MATCHING_CONTENT");
			default -> false;
		};
		boolean failure = outcome.equals("SHELF_SLOT_STILL_EMPTY")
			|| outcome.equals("BOOK_REMOVED_BEFORE_TRANSFER")
			|| outcome.equals("NO_MATCHING_BOOK_RESPONSE")
			|| outcome.equals("EMPTY_BOOK_STORED")
			|| outcome.equals("STORED_WITH_DIFFERENT_CONTENT");
		if(success && (attempt.contentConfirmed
			|| attempt.mode.equals("PLACE_ON_SHELF")))
		{
			if(!attempt.contentConfirmed)
			{
				attempt.contentConfirmed = true;
				attempt.contentPhase = wrap(phase);
				attempt.latencyMs =
					Math.max(0, now - attempt.submittedNs) / 1e6;
			}
			model.successes
				.addLast(new Sample(attempt.contentPhase, attempt.latencyMs));
			model.latencies.addLast(attempt.latencyMs);
			trim(model.successes, 12);
			trim(model.latencies, 12);
			model.consecutiveSuccesses++;
			model.consecutiveFailures = 0;
			if(model.successes.size() >= 2 && consistent(model.successes, 0.10))
				transition(model,
					model.successes.size() >= 4
						&& coefficientOfVariation(model.latencies) < 0.20
							? State.LOCKED : State.PROVISIONAL_LOCK,
					"VERIFIED_SUCCESS_PHASE_LEARNED");
			else if(model.successes.size() >= 2)
				transition(model, State.RECALIBRATING,
					"SUCCESS_PHASES_INCONSISTENT");
		}else if(failure)
		{
			model.failures.addLast(wrap(phase));
			trim(model.failures, 12);
			model.consecutiveFailures++;
			model.consecutiveSuccesses = 0;
			if(model.consecutiveFailures >= 2)
			{
				transition(model, State.RECALIBRATING,
					"TWO_CONSECUTIVE_VERIFIED_FAILURES");
				model.successes.clear();
				model.latencies.clear();
			}
		}
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("transactionId", transactionId);
		fields.put("finalVerifiedResult", outcome);
		fields.put("verifiedSuccess", success && attempt.contentConfirmed);
		fields.put("contentConfirmationSource",
			attempt.mode.equals("PLACE_ON_SHELF")
				&& outcome.equals("SERVER_CONFIRMED_MATCHING_CONTENT")
					? "SERVER_CONFIRMED_SHELF_CONTENT"
					: "SERVER_CONFIRMED_ITEM_CONTENT");
		fields.put("submissionPhase", attempt.submittedPhase);
		fields.put("finalOutcomePhase", wrap(phase));
		fields.put("editSubmissionToContentConfirmationMs",
			Double.isFinite(attempt.latencyMs) ? attempt.latencyMs : null);
		fields.put("modeSuccessCount", model.successes.size());
		fields.put("modeFailureCount", model.failures.size());
		fields.put("lockConfidence", confidence(model));
		fields.put("learningState", model.state.name());
		fields.put("recalibrationReason", model.reason);
		return event("PHASELOCK_FINAL_OUTCOME", attempt.mode, now, fields);
	}
	
	public synchronized Map<String, Object> cancel(String transactionId,
		String reason, long now)
	{
		Attempt attempt = attempts.remove(transactionId);
		if(attempt == null)
			return Map.of();
		Map<String, Object> event = event("PHASELOCK_CANCELLED", attempt.mode,
			now, Map.of("cancellationReason", reason, "submissionPhase",
				attempt.submittedPhase));
		event.put("transactionId", transactionId);
		return event;
	}
	
	public synchronized Map<String, Object> stateSnapshot(String mode)
	{
		ModeModel model =
			modes.computeIfAbsent(mode, unused -> new ModeModel());
		return Map.of("state", model.state.name(), "reason", model.reason,
			"verifiedSuccesses", model.successes.size(), "verifiedFailures",
			model.failures.size(), "confidence", confidence(model),
			"meanContentLatencyMs", mean(model.latencies),
			"contentLatencyStdDevMs",
			standardDeviation(model.latencies, mean(model.latencies)));
	}
	
	private static Map<String, Object> event(String name, String mode, long now,
		Map<String, Object> fields)
	{
		Map<String, Object> event = new LinkedHashMap<>(fields);
		event.put("eventType", name);
		event.put("monotonicNs", now);
		event.put("mode", mode);
		return event;
	}
	
	private static void transition(ModeModel model, State next, String reason)
	{
		model.state = next;
		model.reason = reason;
	}
	
	private static double confidence(ModeModel model)
	{
		if(model.successes.size() < 2 || !consistent(model.successes, 0.10))
			return 0;
		return Math.min(0.95, 0.5 + 0.12 * (model.successes.size() - 2))
			* (coefficientOfVariation(model.latencies) < 0.20 ? 1 : 0.75);
	}
	
	private static boolean consistent(ArrayDeque<Sample> values,
		double maximumDistance)
	{
		if(values.size() < 2)
			return false;
		double center = circularMeanPhases(values);
		for(Sample sample : values)
			if(circularDistance(center, sample.phase) > maximumDistance)
				return false;
		return true;
	}
	
	private static double circularMeanPhases(ArrayDeque<Sample> values)
	{
		double x = 0, y = 0;
		for(Sample sample : values)
		{
			x += Math.cos(sample.phase * Math.PI * 2);
			y += Math.sin(sample.phase * Math.PI * 2);
		}
		return wrap(Math.atan2(y, x) / (Math.PI * 2));
	}
	
	private static double mean(ArrayDeque<Double> values)
	{
		return values.stream().mapToDouble(Double::doubleValue).average()
			.orElse(0);
	}
	
	private static double standardDeviation(ArrayDeque<Double> values,
		double average)
	{
		return Math.sqrt(values.stream()
			.mapToDouble(value -> (value - average) * (value - average))
			.average().orElse(0));
	}
	
	private static double coefficientOfVariation(ArrayDeque<Double> values)
	{
		double average = mean(values);
		return average <= 0 ? Double.POSITIVE_INFINITY
			: standardDeviation(values, average) / average;
	}
	
	private static void trim(ArrayDeque<?> values, int max)
	{
		while(values.size() > max)
			values.removeFirst();
	}
	
	private static double wrap(double value)
	{
		return value - Math.floor(value);
	}
	
	private static double circularDistance(double a, double b)
	{
		double distance = Math.abs(wrap(a) - wrap(b));
		return Math.min(distance, 1 - distance);
	}
	
	private static double forwardDistance(double from, double to)
	{
		return wrap(to - from);
	}
}
