/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.util.*;
import java.util.concurrent.*;
import java.nio.file.*;
import com.google.gson.*;
import net.wurstclient.other_features.packettools.telemetry.*;

public final class ServerTelemetryRegressionTest
{
	static final long BASE = 1_000_000_000L;
	
	static void check(boolean value, String message)
	{
		if(!value)
			throw new AssertionError(message);
	}
	
	static double number(Map<?, ?> values, String key)
	{
		return ((Number)values.get(key)).doubleValue();
	}
	
	static ServerTickEstimator estimator()
	{
		var e = new ServerTickEstimator();
		e.control(20, false, false, BASE);
		return e;
	}
	
	public static void main(String[] args) throws Exception
	{
		net.minecraft.SharedConstants.tryDetectVersion();
		testRates();
		testIrregularities();
		testAuthorityAndServerClock();
		testAttempts();
		testDetachedPublicationAndHealth();
		testProtocol();
		System.out.println(
			"Server telemetry regressions passed: TPS/rate/jitter/bursts/loss/freeze/reset/dimensions, authoritative clock separation, BookBot outcomes/ordering/statistics, detached publication, summary health and protocol round trips.");
	}
	
	static void testRates()
	{
		for(double tps : new double[]{20, 15, 10})
		{
			var e = estimator();
			long now = 0;
			for(int i = 0; i <= 90; i++)
			{
				now = BASE + (long)(i * 20 / tps * 1e9);
				e.sample(i * 20, now, "minecraft:overworld", i, "packet-" + i);
			}
			var snapshot = e.snapshot(now);
			for(int window : new int[]{5, 15, 30, 60})
				check(
					Math.abs(number(snapshot, "estimatedTps" + window + "s")
						- tps) < 0.001,
					"Weighted " + window + "s window at " + tps);
			check(number(snapshot, "configuredTickRate") == 20,
				"Configured rate stays separate from achieved TPS");
		}
		var unknown = new ServerTickEstimator();
		check(!unknown.snapshot(BASE).containsKey("estimatedTps5s"),
			"Missing evidence is unavailable, never a default 20 TPS");
	}
	
	static void testIrregularities()
	{
		var e = estimator();
		long now = BASE;
		for(int i = 0; i <= 80; i++)
		{
			now = BASE + i * 1_000_000_000L + (i >= 40 ? 2_000_000_000L : 0);
			e.sample(i * 20, now, "minecraft:overworld", i, "p" + i);
		}
		check(number(e.snapshot(now), "estimatedTps5s") == 20,
			"A short lag spike does not become sustained TPS loss");
		check(
			((Map<?, ?>)e.summary().get("irregularities"))
				.containsKey("LONG_RECEIVE_INTERVAL_SERVER_OR_NETWORK"),
			"Lag indicator records uncertain cause");
		e = estimator();
		for(int i = 0; i <= 90; i++)
		{
			long jitter = i % 2 == 0 ? 250_000_000L : -250_000_000L;
			now = BASE + i * 1_000_000_000L + jitter;
			e.sample(i * 20, now, "minecraft:overworld", i, "j" + i);
		}
		check(Math.abs(number(e.snapshot(now), "estimatedTps60s") - 20) < 0.4,
			"Jitter-resistant longer weighted estimate");
		check(e.snapshot(now).get("sampleConfidence").equals("LOW"),
			"Jitter reduces confidence");
		e = estimator();
		for(int i = 0; i <= 81; i++)
		{
			now = BASE + (i / 2) * 2_000_000_000L + (i % 2) * 1_000_000L;
			e.sample(i * 20, now, "minecraft:overworld", i, "b" + i);
		}
		check(Math.abs(number(e.snapshot(now), "estimatedTps60s") - 20) < 0.5,
			"Burst arrivals retain endpoint progression");
		check(e.snapshot(now).get("sampleConfidence").equals("LOW"),
			"Burst buffering is not confident measured TPS");
		e = estimator();
		for(int i = 0; i <= 90; i++)
		{
			if(i > 0 && i % 7 == 0)
				continue;
			now = BASE + i * 1_000_000_000L;
			e.sample(i * 20, now, "minecraft:overworld", i, "l" + i);
		}
		check(Math.abs(number(e.snapshot(now), "estimatedTps30s") - 20) < 0.001,
			"Missing packets use game-time delta, not packet count");
		check(!e.snapshot(now + 10_000_000_000L).containsKey("estimatedTps5s"),
			"Stale time packets do not remain contemporaneous");
		check(
			!e.checkStale(now + 10_000_000_000L).isEmpty()
				&& e.checkStale(now + 11_000_000_000L).isEmpty(),
			"Missing-update warning is transition-based");
		e.dimensionChanged("minecraft:the_nether", now + 1);
		check(!e.snapshot(now + 1).containsKey("estimatedTps5s"),
			"Dimension continuity resets immediately");
		check(
			number(e.periodicPhaseSnapshot(now + 1),
				"periodicUpdateSampleCount") == 0,
			"Dimension transitions clear the old periodic phase anchor");
		e.control(20, true, false, now + 2);
		var frozen = e.sample(2000, now + 1_000_000_000L,
			"minecraft:the_nether", 1, "freeze");
		check(
			frozen.stream().anyMatch(
				row -> "TICK_FREEZE_OR_STEP".equals(row.get("invalidReason"))),
			"Frozen sample rejected");
		e.step(now + 2_000_000_000L, 5);
		check(!e.snapshot(now + 2_000_000_000L).containsKey("estimatedTps5s"),
			"Stepping never looks like sustained tick progression");
		check(
			Boolean.TRUE
				.equals(e.snapshot(now + 2_000_000_000L).get("tickStepping")),
			"Positive step count is reported as active stepping");
		e.step(now + 2_000_000_001L, 0);
		check(
			Boolean.FALSE
				.equals(e.snapshot(now + 2_000_000_001L).get("tickStepping")),
			"Zero remaining steps clear stepping state");
		var stepped = estimator();
		stepped.sample(100, BASE, "minecraft:overworld", 0, "step-base");
		stepped.step(BASE + 1, 2);
		stepped.sample(101, BASE + 1_000_000_000L, "minecraft:overworld", 1,
			"step-one");
		check(
			Boolean.TRUE.equals(
				stepped.snapshot(BASE + 1_000_000_000L).get("tickStepping")),
			"Requested steps remain active until game time advances enough");
		stepped.sample(102, BASE + 2_000_000_000L, "minecraft:overworld", 2,
			"step-two");
		check(
			Boolean.FALSE.equals(
				stepped.snapshot(BASE + 2_000_000_000L).get("tickStepping")),
			"Completed step count automatically clears stepping state");
		e.control(10, false, false, now + 3_000_000_000L);
		long start = now + 4_000_000_000L;
		for(int i = 0; i < 40; i++)
			e.sample(2000 + i * 20, start + i * 2_000_000_000L,
				"minecraft:the_nether", i, "rate" + i);
		now = start + 39 * 2_000_000_000L;
		check(
			Math.abs(number(e.snapshot(now), "estimatedTps15s") - 10) < 0.001
				&& number(e.snapshot(now), "configuredTickRate") == 10,
			"Rate changes start fresh windows and use correct semantics");
		e.sample(0, now + 1_000_000_000L, "minecraft:the_nether", 1, "reset");
		check(!e.snapshot(now + 1_000_000_000L).containsKey("estimatedTps5s"),
			"Counter reset invalidates historical estimates");
		e = estimator();
		for(int i = 0; i < 80; i++)
			e.sample(i * 200, BASE + i * 1_000_000_000L, "minecraft:overworld",
				i, "manipulated" + i);
		check(!e.snapshot(BASE + 79_000_000_000L).containsKey("estimatedTps5s"),
			"Nonstandard counter rates rejected, not clamped to 20");
		check(number(e.summary(), "invalidWindowEstimates") > 0,
			"Invalid counter windows counted");
		var cadenceOnly = estimator();
		for(int i = 0; i < 6; i++)
			cadenceOnly.sample(500, BASE + i * 1_000_000_000L,
				"minecraft:overworld", i, "cadence-only" + i);
		check(
			Boolean.TRUE
				.equals(cadenceOnly.periodicPhaseSnapshot(BASE + 5_000_000_000L)
					.get("phaseAvailable")),
			"Periodic phase remains available independently of a TPS estimate");
	}
	
	static Map<String, Object> authoritative(long tick, double mspt, double tps)
	{
		return new LinkedHashMap<>(Map.of("protocolVersion", 1,
			"serverSessionId", "5e973cea-936d-4d1a-9f59-30041f7d585f",
			"serverTickNumber", tick, "actualMSPT", mspt, "rollingTPS", tps,
			"configuredTickRate", 20, "tickStartNs", Long.MAX_VALUE - 1000,
			"sampleAgeAtTransmissionNs", 0L, "sampleWindow",
			Map.of("sampleCount", 100, "coveredSeconds", 5)));
	}
	
	static void testAuthorityAndServerClock()
	{
		var telemetry = new ServerPerformanceTelemetry();
		check(!telemetry.snapshot(BASE).containsKey("actualServerMspt"),
			"Absent component never produces authoritative MSPT");
		check(telemetry.acceptAuthoritative(authoritative(100, 80, 12), BASE),
			"Valid controlled-server measurement accepted");
		check(
			number(telemetry.snapshot(BASE + 2_000_000L),
				"serverTelemetryAgeMs") == 2,
			"Age uses local receive clock, not the unrelated server monotonic origin");
		check(number(telemetry.snapshot(BASE), "actualServerMspt") == 80,
			"Authoritative MSPT remains actual, separate from estimated TPS");
		check(!telemetry.acceptAuthoritative(authoritative(100, 80, 12),
			BASE + 1), "Repeated/out-of-order server tick sample rejected");
		var invalid = authoritative(101, 10, 20);
		invalid.put("protocolVersion", 2);
		check(!telemetry.acceptAuthoritative(invalid, BASE + 2),
			"Unknown protocol rejected");
		var aged = authoritative(102, 10, 20);
		aged.put("sampleAgeAtTransmissionNs", 6_000_000_000L);
		check(telemetry.acceptAuthoritative(aged, BASE + 3),
			"Delayed sample remains observable");
		check(!telemetry.snapshot(BASE + 4).containsKey("actualServerMspt"),
			"Server queue delay is included in measurement age, not hidden by recent delivery");
		
		check(
			!telemetry.snapshot(BASE + 6_000_000_000L)
				.containsKey("actualServerMspt"),
			"Stale authority is not attached as current MSPT");
		var server = new ServerTickMeasurements();
		for(int i = 0; i < 5; i++)
		{
			long start = BASE + i * 50_000_000L;
			server.record(i, start, start + 1_000_000L, start - 2_000_000L, 20,
				false, false);
		}
		var sample = server.snapshot(BASE + 201_000_000L);
		check(
			number(sample, "rollingTPS") == 20
				&& number(sample, "actualMSPT") == 1,
			"Real tick interval and execution duration remain separate");
		check(number(sample, "tickSchedulingDelayNs") == 2_000_000,
			"Scheduling lateness is measured against deadline, not inter-tick idle time");
		server.record(6, BASE + 250_000_000L, BASE + 251_000_000L, 0, 20, false,
			true);
		check(
			!server.snapshot(BASE + 251_000_000L)
				.containsKey("tickSchedulingDelayNs"),
			"Sprint has no invented fixed deadline");
		server.record(7, BASE + 300_000_000L, BASE + 301_000_000L,
			BASE + 300_000_000L, 40, false, false);
		check(server.snapshot(BASE + 301_000_000L).isEmpty(),
			"Authoritative windows reset at tick-rate change");
		server.record(8, BASE + 325_000_000L, BASE + 326_000_000L,
			BASE + 325_000_000L, 40, false, false);
		check(number(server.snapshot(BASE + 326_000_000L), "rollingTPS") == 40,
			"Configured rates above 20 are supported without a clamp");
	}
	
	static Map<String, Object> event(String id, String phase, long ns,
		String packet, Map<String, Object> fields)
	{
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("transactionId", id);
		row.put("captureStage", phase);
		row.put("monotonicNs", ns);
		row.put("packetClass", packet);
		row.put("dimension", "minecraft:overworld");
		row.put("clientTick", 42);
		row.putAll(fields);
		return row;
	}
	
	static void attempt(BookBotPerformanceAnalysis analysis, String id,
		String outcome, boolean earlyRemoval, boolean authority,
		boolean dimensionChange)
	{
		Map<String, Object> performance = new LinkedHashMap<>(
			Map.of("estimatedTps5s", 15.0, "configuredTickRate", 20.0,
				"serverTelemetrySource", "CLIENT_ESTIMATE"));
		if(authority)
		{
			performance.put("serverTelemetrySource",
				"TRUSTED_CONTROLLED_SERVER_COMPONENT_V1");
			performance.put("actualServerMspt",
				outcome.equals("SERVER_CONFIRMED_MATCHING_CONTENT") ? 80.0
					: 5.0);
			performance.put("actualServerTps", 15.0);
			performance.put("serverTelemetryAgeMs", 10.0);
		}
		analysis.accept(
			event(id, "TRANSPORT_SUBMITTED", BASE, "ServerboundEditBookPacket",
				Map.of("transportSubmittedNs", BASE, "serverPerformance",
					performance, "fps", 60, "pingMs", 100,
					"recentClientTickDurationNs", 5_000_000L)));
		analysis.accept(event(id, "DECODED", BASE + 100_000_000L,
			"ClientboundContainerSetSlotPacket", Map.of("packetArrivalNs",
				BASE + 100_000_000L, "suppliedBookPageCount", 100)));
		analysis.accept(event(id, "HANDLER_START", BASE + 102_000_000L,
			"ClientboundContainerSetSlotPacket",
			Map.of("packetArrivalNs", BASE + 100_000_000L, "handlerStartNs",
				BASE + 102_000_000L, "suppliedBookPageCount", 100)));
		analysis.accept(event(id, "STATE_APPLIED", BASE + 103_000_000L,
			"ClientboundContainerSetSlotPacket",
			Map.of("packetArrivalNs", BASE + 100_000_000L, "stateAppliedNs",
				BASE + 103_000_000L, "observedBookPageCount", 100)));
		analysis.accept(event(id, "external", BASE + 104_000_000L, "",
			Map.of("name", "BookBot.shelf_interaction_requested",
				"interactionRequestedNs", BASE + 104_000_000L)));
		analysis.accept(event(id, "TRANSPORT_SUBMITTED", BASE + 105_000_000L,
			"ServerboundUseItemOnPacket",
			Map.of("transportSubmittedNs", BASE + 105_000_000L)));
		long removed = BASE + (earlyRemoval ? 101_000_000L : 110_000_000L);
		analysis.accept(
			event(id, "DECODED", removed, "ClientboundContainerSetSlotPacket",
				Map.of("packetArrivalNs", removed, "suppliedItemEmpty", true)));
		analysis.accept(event(id, "STATE_APPLIED", removed + 4_000_000L,
			"ClientboundContainerSetSlotPacket",
			Map.of("packetArrivalNs", removed, "stateAppliedNs",
				removed + 4_000_000L, "observedItemEmpty", true)));
		analysis.accept(event(id, "DECODED", BASE + 120_000_000L,
			"ClientboundBlockEntityDataPacket",
			Map.of("packetArrivalNs", BASE + 120_000_000L)));
		Map<String, Object> result = new LinkedHashMap<>(
			Map.of("name", "BookBot.shelf_server_update", "outcome", outcome));
		if(dimensionChange)
			result.put("dimension", "minecraft:the_nether");
		analysis.accept(event(id, "external", BASE + 121_000_000L, "", result));
	}
	
	static void testAttempts()
	{
		var analysis = new BookBotPerformanceAnalysis();
		for(int i = 0; i < 3; i++)
		{
			attempt(analysis, "BookBot.edit-success" + i,
				"SERVER_CONFIRMED_MATCHING_CONTENT", false, true, false);
			attempt(analysis, "BookBot.edit-empty" + i,
				"SHELF_SLOT_STILL_EMPTY", true, true, false);
		}
		attempt(analysis, "BookBot.edit-no-authority",
			"SERVER_CONFIRMED_MATCHING_CONTENT", false, false, false);
		attempt(analysis, "BookBot.edit-dimension",
			"SERVER_CONFIRMED_MATCHING_CONTENT", false, true, true);
		analysis.accept(event("BookBot.edit-removed", "external", BASE, "",
			Map.of("name", "BookBot.book_removed_before_transfer")));
		analysis.accept(event("BookBot.edit-timeout", "external", BASE, "",
			Map.of("name", "BookBot.no_matching_book_response")));
		analysis.accept(event("BookBot.edit-only-send", "TRANSPORT_SUBMITTED",
			BASE, "ServerboundUseItemOnPacket",
			Map.of("transportSubmittedNs", BASE)));
		Map<String, Object> summary = analysis.summary();
		Map<?, ?> groups = (Map<?, ?>)summary.get("groups");
		check(
			number((Map<?, ?>)groups.get("CONFIRMED_SUCCESSFUL"),
				"attempts") == 4,
			"Success depends on matching shelf result, including elevated MSPT and absent telemetry");
		check(
			number((Map<?, ?>)groups.get("SHELF_REMAINED_EMPTY"),
				"attempts") == 3,
			"Normal MSPT never turns empty-shelf result into success");
		check(number((Map<?, ?>)groups.get("BOOK_REMOVED_BEFORE_TRANSFER"),
			"attempts") == 1
			&& number((Map<?, ?>)groups.get("NO_MATCHING_BOOK_RESPONSE"),
				"attempts") == 1,
			"Explicit failure categories retained");
		var attempts = (List<Map<String, Object>>)summary.get("attempts");
		var success = attempts.stream()
			.filter(
				row -> row.get("transactionId").equals("BookBot.edit-success0"))
			.findFirst().orElseThrow();
		var times = (Map<?, ?>)success.get("timingsMs");
		check(
			number(times, "editToBookResponseMs") == 100
				&& number(times, "bookArrivalToClientProcessingMs") == 2
				&& number(times, "bookAppliedToShelfRequestMs") == 1,
			"Arrival, processing and application use independent endpoints");
		check(number(times, "shelfSubmissionToRemovalApplicationMs") == 9,
			"Removal application differs from packet arrival");
		var failure = attempts.stream()
			.filter(
				row -> row.get("transactionId").equals("BookBot.edit-empty0"))
			.findFirst().orElseThrow();
		check(
			number((Map<?, ?>)failure.get("timingsMs"),
				"shelfSubmissionToRemovalArrivalMs") == -4,
			"Earlier removal stays negative, with no forced expected sequence");
		var dimension = attempts.stream().filter(
			row -> row.get("transactionId").equals("BookBot.edit-dimension"))
			.findFirst().orElseThrow();
		check(dimension.get("outcome").equals("INDETERMINATE"),
			"Cross-dimension storage cannot be attributed to the original target");
		var noAuthority = attempts.stream().filter(
			row -> row.get("transactionId").equals("BookBot.edit-no-authority"))
			.findFirst().orElseThrow();
		check(!noAuthority.containsKey("actualServerMspt"),
			"Missing authoritative metrics are never manufactured");
		check(
			analysis.markdown().contains("| Attempts |")
				&& analysis.markdown().contains("unavailable"),
			"Markdown includes automatic outcome comparison with missing-data markers");
		var ignored = new BookBotPerformanceAnalysis();
		ignored.accept(event("BookBot.edit-ignored", "STATE_APPLIED", BASE,
			"ClientboundContainerSetSlotPacket",
			Map.of("packetArrivalNs", BASE - 10, "stateAppliedNs", BASE,
				"suppliedBookPageCount", 100, "observedBookPageCount", 0,
				"shelfTransferRequested", true)));
		var ignoredRow =
			((List<Map<String, Object>>)ignored.summary().get("attempts"))
				.getFirst();
		check(
			((List<Map<String, Object>>)ignoredRow.get("timeline")).stream()
				.noneMatch(row -> row.get("stage")
					.equals("POPULATED_BOOK_STATE_APPLIED")),
			"A proposed populated stack is not proof it was applied");
	}
	
	static void testDetachedPublicationAndHealth() throws Exception
	{
		Map<String, Object> child = new LinkedHashMap<>(Map.of("x", 1));
		Map<String, Object> mutable =
			new LinkedHashMap<>(Map.of("eventType", "test", "nested", child));
		var detached = ForensicSnapshots.freezeFields(mutable);
		CountDownLatch entered = new CountDownLatch(1),
			released = new CountDownLatch(1);
		java.util.concurrent.atomic.AtomicReference<Throwable> failure =
			new java.util.concurrent.atomic.AtomicReference<>();
		Thread reader = new Thread(() -> {
			try
			{
				entered.countDown();
				released.await();
				for(int i = 0; i < 1000; i++)
					new Gson().toJson(detached);
			}catch(Throwable e)
			{
				failure.set(e);
			}
		});
		reader.start();
		entered.await();
		for(int i = 0; i < 1000; i++)
		{
			mutable.put("slot" + i, i);
			child.put("nested" + i, i);
		}
		released.countDown();
		reader.join();
		check(
			failure.get() == null && detached.size() == 2
				&& ((Map<?, ?>)detached.get("nested")).size() == 1,
			"Queued metadata is detached before producer mutation, including nested fields");
		Path directory = Files.createTempDirectory("telemetry-health-");
		var model = new ItemInventoryForensics();
		var writer = new ForensicSessionWriter(directory, model, Map.of(),
			new PacketTimingTracker(model.getSessionId()));
		writer.submit("diagnostic", System.nanoTime(), 1, () -> {
			throw new ConcurrentModificationException(
				"deliberate diagnostic failure");
		});
		writer.finish("test");
		check(writer.await(10000), "Writer drains after a diagnostic failure");
		JsonObject health = JsonParser
			.parseString(Files.readString(
				directory.resolve("session-" + model.getSessionId() + ".json")))
			.getAsJsonObject().getAsJsonObject("captureHealth");
		check(
			health.get("diagnosticErrors").getAsLong() == 1
				&& health.get("droppedObservationJobs").getAsLong() == 0
				&& health.get("writerCompletedSuccessfully").getAsBoolean(),
			"Writer completion, diagnostic errors and capture loss are distinct");
		check(!health.get("captureComplete").getAsBoolean(),
			"JSON summary reports incomplete diagnostics");
		try(var files = Files.list(directory))
		{
			check(files.count() == 2,
				"A completed capture writes one event stream and one summary");
		}
	}
	
	static void testProtocol()
	{
		var buffer = new net.minecraft.network.RegistryFriendlyByteBuf(
			io.netty.buffer.Unpooled.buffer(),
			net.minecraft.core.RegistryAccess.EMPTY);
		try
		{
			var subscription = new TelemetryPayloads.Subscription(1, true,
				"5e973cea-936d-4d1a-9f59-30041f7d585f");
			TelemetryPayloads.Subscription.CODEC.encode(buffer, subscription);
			check(TelemetryPayloads.Subscription.CODEC.decode(buffer)
				.equals(subscription), "Negotiation payload round trip");
			var sample = new TelemetryPayloads.Sample(subscription.captureId(),
				new Gson().toJson(authoritative(1, 10, 20)));
			TelemetryPayloads.Sample.CODEC.encode(buffer, sample);
			check(TelemetryPayloads.Sample.CODEC.decode(buffer).equals(sample),
				"Bounded authoritative payload round trip");
		}finally
		{
			buffer.release();
		}
	}
}
