/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.*;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.item.*;

/**
 * Real queue, file lifecycle, raw stack and monotonic timing regression checks.
 */
public final class ForensicReliabilityRegressionTest
{
	static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
	
	static JsonObject json(String line)
	{
		return JsonParser.parseString(line).getAsJsonObject();
	}
	
	public static void main(String[] args) throws Exception
	{
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		var lookup = VanillaRegistries.createWorldLookup();
		net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
			.build(lookup).forEach(pending -> pending.apply());
		var model = new ItemInventoryForensics();
		List<JsonObject> events = new ArrayList<>();
		model.setEventSink(line -> events.add(json(line)));
		ItemStack stack = new ItemStack(Items.STONE, 3);
		for(int tick = 0; tick < 5000; tick++)
			model.reconcileCaptured(state(stack.copy(), tick, lookup), true,
				false, 1024, Map.of());
		check(
			events.stream().noneMatch(
				e -> e.get("eventType").getAsString().equals("item_mutation")),
			"5000 unchanged ticks must not produce mutations");
		stack.setCount(2);
		model.reconcileCaptured(state(stack.copy(), 5000, lookup), false, false,
			1024, Map.of("captureStage", "STATE_APPLIED", "logicalPacketId",
				"packet-1", "packetArrivalNs", 1000000L));
		model.reconcileCaptured(state(ItemStack.EMPTY, 5000, lookup), false,
			false, 1024, Map.of("captureStage", "STATE_APPLIED",
				"logicalPacketId", "packet-2", "packetArrivalNs", 1002500L));
		var changes = events.stream()
			.filter(
				e -> e.get("eventType").getAsString().equals("item_mutation"))
			.toList();
		check(changes.size() == 2,
			"Both changes in the same tick must be captured");
		check(changes.get(1).get("sincePreviousSlotPacketArrivalNs")
			.getAsLong() == 2500, "Exact same-slot arrival delta");
		check(changes.get(1).get("logicalPacketId").getAsString()
			.equals("packet-2"), "Removal belongs to actual affecting packet");
		ItemStack live = new ItemStack(Items.DIRT, 4);
		var frozen = state(live.copy(), 5001, lookup);
		live.setCount(1);
		model.reconcileCaptured(frozen, false, false, 1024, Map.of());
		check(
			events.get(events.size() - 1).getAsJsonObject("itemAfter")
				.get("count").getAsInt() == 4,
			"Copied stack cannot follow later live mutation");
		for(boolean main : new boolean[]{false, true})
			for(boolean forensic : new boolean[]{false, true})
				check(
					ForensicSessionWriter.effectiveEnabled(main,
						forensic) == (main && forensic),
					"Effective logging matrix");
		testTransactionTimeline();
		var pages = java.util.stream.IntStream.range(0, 100)
			.mapToObj(i -> net.minecraft.server.network.Filterable
				.passThrough("x".repeat(3072)))
			.toList();
		ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
		book.set(
			net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,
			new net.minecraft.world.item.component.WritableBookContent(pages));
		model.reconcileCaptured(state(book.copy(), 6000, lookup), true, true,
			1048576, Map.of());
		int beforeRepeatedBooks = events.size();
		for(int i = 0; i < 1000; i++)
			model.reconcileCaptured(state(book.copy(), 6001 + i, lookup), true,
				true, 1048576, Map.of());
		check(events.size() == beforeRepeatedBooks,
			"Unchanged 300 KiB books are neither rehashed into new events nor re-exported");
		testTiming();
		testFiles();
		System.out.println(
			"Forensic reliability: unchanged 5000 ticks, same-tick update/removal, immutable stacks, timing, overflow/recovery, rotation, failures and session isolation passed.");
	}
	
	static ForensicSnapshots.State state(ItemStack stack, long tick,
		net.minecraft.core.HolderLookup.Provider lookup)
	{
		return new ForensicSnapshots.State(
			Map.of("player_inventory:0:slot:37", stack),
			Map.of("player_inventory:0:slot:37",
				Map.of("containerId", 0, "rawSlot", 37, "logicalSlot",
					"hotbar[1]")),
			lookup, "minecraft:overworld", tick, System.nanoTime(),
			java.time.Instant.now().toString(),
			Thread.currentThread().getName());
	}
	
	static void testTransactionTimeline()
	{
		var report = ItemInventoryForensics.transactionSummary(List.of(
			Map.of("captureStage", "ACTION_REQUESTED", "packetClass",
				"ServerboundEditBookPacket", "actionRequestedNs", 1000L,
				"monotonicNs", 1000L),
			Map.of("captureStage", "TRANSPORT_SUBMITTED", "packetClass",
				"ServerboundEditBookPacket", "transportSubmittedNs", 1200L,
				"monotonicNs", 1200L),
			Map.of("itemBookContentAfter", Map.of("pageCount", 100),
				"packetArrivalNs", 2000L, "monotonicNs", 2100L),
			Map.of("change", "item_removed", "packetArrivalNs", 2400L,
				"monotonicNs", 2600L),
			Map.of("name", "BookBot.shelf_interaction_requested",
				"interactionRequestedNs", 2500L, "monotonicNs", 2800L),
			Map.of("name", "BookBot.book_content_confirmed",
				"contentConfirmedNs", 2200L, "monotonicNs", 2900L),
			Map.of("name", "BookBot.shelf_server_update", "outcome",
				"EMPTY_BOOK_STORED", "monotonicNs", 3000L)));
		var intervals = (Map<?, ?>)report.get("measuredIntervalsNs");
		check(intervals.get("editRequestToSubmissionNs").equals(200L),
			"Only observed stages form intervals");
		check(
			intervals.get("removalArrivalRelativeToShelfRequestNs")
				.equals(-100L),
			"Arrival before shelf request remains negative and explicit");
		check(!intervals.containsKey("shelfRequestToSubmissionNs"),
			"Missing transport observation must not be invented");
		check(report.get("outcome").equals("EMPTY_BOOK_STORED"),
			"Empty storage never classified as successful matching content");
		var rows = (List<Map<String, Object>>)report.get("events");
		check(rows.get(3).get("contentConfirmedNs").equals(2200L),
			"Late diagnostic report sorted by actual confirmation timestamp");
	}
	
	static void testTiming() throws Exception
	{
		var tracker = new PacketTimingTracker("test");
		Object packet = new Object();
		var first =
			tracker.observe(packet, "S2C", "DECODED", 1000000, 1, Map.of());
		final Map<String, Object>[] fromThread = new Map[1];
		var thread = new Thread(() -> fromThread[0] = tracker.observe(packet,
			"S2C", "HANDLER_START", 1250000, 2, Map.of()), "simulated-client");
		thread.start();
		thread.join();
		var end =
			tracker.observe(packet, "S2C", "HANDLER_END", 1250750, 2, Map.of());
		check(end.get("handlerDurationNs").equals(750L),
			"Sub-millisecond duration preserved numerically");
		check(end.get("packetDispatchDelayNs").equals(250000L),
			"Scheduling delay independent of encoding");
		check(fromThread[0].get("thread").equals("simulated-client"),
			"Actual observation thread");
		check(first.get("logicalPacketId").equals(end.get("logicalPacketId")),
			"Multiple hooks share identity");
		var distinct = tracker.observe(new Object(), "S2C", "DECODED", 1300000,
			2, Map.of());
		check(
			!distinct.get("logicalPacketId")
				.equals(first.get("logicalPacketId")),
			"Identical distinct packets retain distinct IDs");
		tracker.observe(packet, "S2C", "HANDLER_END", 1250800, 2, Map.of());
		check(
			((Number)tracker.summary().get("duplicateCapturePointObservations"))
				.longValue() == 1,
			"Duplicate hooks counted");
		Object outgoing = new Object();
		tracker.observe(outgoing, "C2S", "ACTION_REQUESTED", 2000000, 3,
			Map.of("transactionId", "action-1"));
		tracker.observe(outgoing, "C2S", "SEND_REQUESTED", 2000500, 3,
			Map.of());
		var submitted = tracker.observe(outgoing, "C2S", "TRANSPORT_SUBMITTED",
			2000900, 3, Map.of());
		check(submitted.get("actionToSendNs").equals(500L),
			"Real action-to-send interval");
		check(submitted.get("sendPipelineNs").equals(400L),
			"Real send pipeline interval");
		check(
			!submitted.containsKey("transportWriteCompletedNs")
				&& !submitted.containsKey("transportWriteNs"),
			"Submission never fabricates completion");
		var resent = tracker.observe(outgoing, "C2S", "SEND_REQUESTED", 2001000,
			3, Map.of());
		check(
			!resent.get("logicalPacketId")
				.equals(submitted.get("logicalPacketId")),
			"Reused object still represents a distinct actual transmission");
		var completion =
			tracker.transportCompleted(submitted, 2001100, 3, true, null);
		check(
			completion.get("logicalPacketId")
				.equals(submitted.get("logicalPacketId")),
			"Late write completion belongs to original submission");
		check(completion.get("transportWriteNs").equals(200L),
			"Actual callback interval uses original transport timestamp");
		
	}
	
	static void testFiles() throws Exception
	{
		Path directory = Files.createTempDirectory("forensic-regression-");
		var a = new ItemInventoryForensics();
		var b = new ItemInventoryForensics();
		var wa = new ForensicSessionWriter(directory, a, Map.of(),
			new PacketTimingTracker(a.getSessionId()), 4, 65536, 10);
		var wb = new ForensicSessionWriter(directory, b, Map.of(),
			new PacketTimingTracker(b.getSessionId()), 100, 65536, 10);
		CountDownLatch entered = new CountDownLatch(1),
			release = new CountDownLatch(1);
		wa.submit("blocked", System.nanoTime(), 1, () -> {
			entered.countDown();
			try
			{
				release.await();
			}catch(InterruptedException e)
			{
				throw new RuntimeException(e);
			}
		});
		check(entered.await(5, TimeUnit.SECONDS),
			"Writer begins delayed old-session job");
		for(int i = 0; i < 40; i++)
			wa.submit("rapid_update", System.nanoTime(), 1, () -> a
				.recordObservation(Map.of("eventType", "probe", "owner", "A")));
		for(int i = 0; i < 50; i++)
			check(
				wb.submit("load", System.nanoTime(), 1,
					() -> b.recordObservation(
						Map.of("eventType", "probe", "owner", "B"))),
				"Sustained rotated writer keeps accepting records");
		wa.finish("logging_disabled");
		wb.finish("disconnect");
		release.countDown();
		check(wa.await(10000) && wb.await(10000),
			"Session close flushes accepted jobs");
		check(!wa.getFile().equals(wb.getFile()),
			"Rapid activation creates unique paths");
		long overflow = 0, bEvents = 0;
		try(var files = Files.list(directory))
		{
			for(Path file : files.filter(p -> p.toString().endsWith(".jsonl"))
				.toList())
			{
				for(String line : Files.readAllLines(file))
				{
					var event = json(line);
					String id = event.get("sessionId").getAsString();
					check(file.getFileName().toString().contains(id),
						"Delayed session A writes cannot enter session B files");
					if(event.get("eventType").getAsString()
						.equals("diagnostic_overflow"))
						overflow++;
					if(event.has("owner")
						&& event.get("owner").getAsString().equals("B"))
						bEvents++;
				}
			}
		}
		check(overflow == 1,
			"Overflow loss is aggregated outside bounded queue");
		check(
			!JsonParser
				.parseString(Files.readString(
					directory.resolve("session-" + a.getSessionId() + ".json")))
				.getAsJsonObject().getAsJsonObject("forensics")
				.get("captureComplete").getAsBoolean(),
			"Automatic model summary cannot call a lossy capture complete");
		check(bEvents == 50, "Rotation never becomes a lifetime capture quota");
		check(((Number)wa.health().get("droppedObservationJobs"))
			.longValue() == 36, "All dropped jobs reported");
		check(Boolean.FALSE.equals(wa.health().get("captureComplete")),
			"Loss cannot be called complete");
		var c = new ItemInventoryForensics();
		var wc = new ForensicSessionWriter(directory, c, Map.of(),
			new PacketTimingTracker(c.getSessionId()), 2, 65536, 100);
		CountDownLatch started = new CountDownLatch(1),
			unblock = new CountDownLatch(1);
		wc.submit("blocking", System.nanoTime(), 1, () -> {
			started.countDown();
			try
			{
				unblock.await();
			}catch(Exception e)
			{
				throw new RuntimeException(e);
			}
		});
		started.await();
		for(int i = 0; i < 10; i++)
			wc.submit("burst", System.nanoTime(), 1, () -> {});
		unblock.countDown();
		boolean recovered = false;
		for(int i = 0; i < 100 && !recovered; i++)
		{
			recovered = wc.submit("recovered", System.nanoTime(), 1,
				() -> c.recordObservation(Map.of("eventType", "recovered")));
			if(!recovered)
				Thread.sleep(10);
		}
		check(recovered, "Ordinary capture resumes after overflow");
		wc.finish("shutdown");
		check(wc.await(10000), "Shutdown drains");
		check(Files.readAllLines(wc.getFile()).stream().anyMatch(
			l -> json(l).get("eventType").getAsString().equals("recovered")),
			"Recovered event persisted");
		Path bad = Files.createTempFile("forensic-bad-output-", ".file");
		var d = new ItemInventoryForensics();
		var wd = new ForensicSessionWriter(bad, d, Map.of(),
			new PacketTimingTracker(d.getSessionId()));
		wd.finish("disk_failure_test");
		check(wd.await(10000), "Failed disk close terminates");
		check(
			((Number)wd.health().get("ioErrors")).longValue() > 0
				&& !Boolean.TRUE.equals(wd.health().get("captureComplete")),
			"Disk errors reported as incomplete");
	}
}
