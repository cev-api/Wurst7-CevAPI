/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Map;
import net.wurstclient.other_features.packettools.ServerUpdatePhaseEstimator;

public final class BookBotPhaseLockRegressionTest
{
	private static final long SECOND = 1_000_000_000L;
	
	private static void check(boolean value, String message)
	{
		if(!value)
			throw new AssertionError(message);
	}
	
	private static Map<String, Object> phase(double value)
	{
		return Map.of("phaseAvailable", true, "periodicUpdatePhase", value,
			"periodicUpdateCycleNs", SECOND, "timingUncertaintyMs", 15.0,
			"estimatedNextPeriodicUpdateNs", 10 * SECOND);
	}
	
	public static void main(String[] args)
	{
		testPeriodicCadenceAndJitter();
		testSchedulingAndWraparound();
		testOutcomeLearningAndModes();
		testFailureAndCancellation();
		System.out.println(
			"BookBot PhaseLock regressions passed: periodic cadence, stale/jitter rejection, phase wraparound, latency compensation, late responses, cancellation and verified mode-specific outcomes.");
	}
	
	private static void testPeriodicCadenceAndJitter()
	{
		ServerUpdatePhaseEstimator estimator = new ServerUpdatePhaseEstimator();
		for(int i = 0; i < 6; i++)
			estimator.observe(i * SECOND);
		Map<String, Object> valid =
			estimator.snapshot(5 * SECOND + 250_000_000L);
		check(Boolean.TRUE.equals(valid.get("phaseAvailable")),
			"one-second update cadence should become available");
		check(Math.abs(((Number)valid.get("periodicUpdatePhase")).doubleValue()
			- 0.25) < 0.001, "phase estimate is wrong");
		check(
			!Boolean.TRUE
				.equals(estimator.snapshot(9 * SECOND).get("phaseAvailable")),
			"stale cadence must be rejected");
		
		ServerUpdatePhaseEstimator jitter = new ServerUpdatePhaseEstimator();
		long[] arrivals = {0, SECOND, 2 * SECOND + 180_000_000L,
			3 * SECOND - 100_000_000L, 4 * SECOND + 150_000_000L, 5 * SECOND};
		for(long arrival : arrivals)
			jitter.observe(arrival);
		check(
			!Boolean.TRUE.equals(jitter.snapshot(5 * SECOND + 10_000_000L)
				.get("phaseAvailable")),
			"high cadence jitter must be rejected");
	}
	
	private static void testSchedulingAndWraparound()
	{
		BookBotPhaseLock lock = new BookBotPhaseLock();
		var initial = lock.evaluate("PLACE_ON_SHELF", phase(0.50), 5 * SECOND);
		check(!initial.submitNow(), "unsuitable phase must queue the edit");
		check(initial.state() == BookBotPhaseLock.State.CALIBRATING,
			"first attempt should be calibrating");
		var window = lock.evaluate("PLACE_ON_SHELF", phase(0.10), 6 * SECOND);
		check(window.submitNow(), "initial exploration phase should open");
		check(window.selectedDeadlineNs() == 6 * SECOND,
			"selected deadline should be now when already in window");
		lock.submitted("s1", "PLACE_ON_SHELF", 0.10, 6 * SECOND, SECOND,
			6 * SECOND);
		lock.contentConfirmed("s1", 0.98, 6 * SECOND + 50_000_000L);
		lock.outcome("s1", "SERVER_CONFIRMED_MATCHING_CONTENT", 0.99,
			6 * SECOND + 200_000_000L);
		lock.submitted("s2", "PLACE_ON_SHELF", 0.10, 7 * SECOND, SECOND,
			7 * SECOND);
		lock.contentConfirmed("s2", 0.02, 7 * SECOND + 60_000_000L);
		lock.outcome("s2", "SERVER_CONFIRMED_MATCHING_CONTENT", 0.03,
			7 * SECOND + 200_000_000L);
		check(
			lock.stateSnapshot("PLACE_ON_SHELF").get("state")
				.equals("PROVISIONAL_LOCK"),
			"two similar wraparound phases should be provisional");
		var compensated =
			lock.evaluate("PLACE_ON_SHELF", phase(0.93), 8 * SECOND);
		check(compensated.submitNow(),
			"content latency should move the submission window earlier");
	}
	
	private static void testOutcomeLearningAndModes()
	{
		BookBotPhaseLock lock = new BookBotPhaseLock();
		lock.submitted("keep", "KEEP", 0.1, 1_000_000_000L, SECOND,
			1_000_000_000L);
		lock.contentConfirmed("keep", 0.12, 1_100_000_000L);
		var unverified = lock.outcome("keep", "BOOK_REMOVED_BEFORE_TRANSFER",
			0.13, 1_200_000_000L);
		check(Boolean.FALSE.equals(unverified.get("verifiedSuccess")),
			"removed book must not train as durable success");
		lock.submitted("keep2", "KEEP", 0.1, 2_000_000_000L, SECOND,
			2_000_000_000L);
		lock.contentConfirmed("keep2", 0.12, 2_100_000_000L);
		lock.outcome("keep2", "KEEP_CONTENT_STABLE", 0.12, 3_200_000_000L);
		check(((Number)lock.stateSnapshot("KEEP").get("verifiedSuccesses"))
			.intValue() == 1, "stable keep content should train Keep mode");
		check(
			((Number)lock.stateSnapshot("PLACE_ON_SHELF")
				.get("verifiedSuccesses")).intValue() == 0,
			"mode success counts must remain separate");
		
		lock.submitted("drop", "DROP", 0.1, 4_000_000_000L, SECOND,
			4_000_000_000L);
		lock.contentConfirmed("drop", 0.11, 4_100_000_000L);
		lock.outcome("drop", "DROP_OUTPUT_UNVERIFIED", 0.12, 4_200_000_000L);
		check(((Number)lock.stateSnapshot("DROP").get("verifiedSuccesses"))
			.intValue() == 0, "unverified drop must not train as success");
	}
	
	private static void testFailureAndCancellation()
	{
		BookBotPhaseLock lock = new BookBotPhaseLock();
		lock.submitted("late", "PLACE_ON_SHELF", 0.1, SECOND, SECOND, SECOND);
		lock.contentConfirmed("late", 0.2, 900_000_000L);
		check(
			((Number)lock.stateSnapshot("PLACE_ON_SHELF")
				.get("meanContentLatencyMs")).doubleValue() == 0,
			"out-of-order local timestamps must not create negative latency");
		check(
			lock.cancel("late", "DIMENSION_CHANGED", SECOND)
				.get("cancellationReason").equals("DIMENSION_CHANGED"),
			"cancellation reason should be retained");
		lock.submitted("f1", "PLACE_ON_SHELF", 0.1, 2 * SECOND, SECOND,
			2 * SECOND);
		lock.outcome("f1", "SHELF_SLOT_STILL_EMPTY", 0.1, 2 * SECOND + 10);
		lock.submitted("f2", "PLACE_ON_SHELF", 0.1, 3 * SECOND, SECOND,
			3 * SECOND);
		Map<String, Object> failed =
			lock.outcome("f2", "SHELF_SLOT_STILL_EMPTY", 0.1, 3 * SECOND + 10);
		check(failed.get("learningState").equals("RECALIBRATING"),
			"repeated verified failures should trigger recalibration");
	}
}
