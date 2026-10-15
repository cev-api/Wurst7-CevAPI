/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Passive, identity-based packet lifecycle timestamps. No decoding or I/O. */
public final class PacketTimingTracker
{
	private final String sessionId;
	private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
	private final Map<IdentityReference, Trace> packets = new HashMap<>();
	private long packetSequence;
	private long observationSequence;
	private long duplicateStages;
	private long metadataEvictions;
	private final Map<String, long[]> durations = new LinkedHashMap<>();
	
	public PacketTimingTracker(String sessionId)
	{
		this.sessionId = sessionId;
	}
	
	public synchronized Map<String, Object> observe(Object packet,
		String direction, String stage, long now, long tick,
		Map<String, Object> context)
	{
		IdentityReference dead;
		while((dead = (IdentityReference)collected.poll()) != null)
			packets.remove(dead);
		IdentityReference key = new IdentityReference(packet, collected);
		Trace trace = packets.get(key);
		boolean reused = trace != null && direction.equals("C2S")
			&& (((stage.equals("SEND_REQUESTED")
				|| stage.equals("PACKETTOOLS_CLIENT_SEND"))
				&& trace.times.containsKey("transportSubmittedNs"))
				|| (stage.equals("ACTION_REQUESTED")
					&& trace.times.containsKey("actionRequestedNs")));
		if(trace == null || reused)
		{
			// References are weak; this is an engineering bound on metadata
			// only.
			if(packets.size() >= 16384)
			{
				packets.remove(packets.keySet().iterator().next());
				metadataEvictions++;
			}
			trace = new Trace("packet-" + (++packetSequence));
			packets.put(key, trace);
		}
		Map<String, Object> event = new LinkedHashMap<>(context);
		event.put("schemaVersion", 2);
		if(reused)
			event.put("reusedPacketObjectForNewTransmission", true);
		event.put("sessionId", sessionId);
		event.put("eventType", "packet_timing");
		event.put("logicalPacketId", trace.id);
		event.put("packetObservationId",
			trace.id + "-" + (++observationSequence));
		event.put("observationSequence", observationSequence);
		event.put("packetClass", packet.getClass().getName());
		event.put("packetObjectCorrelationId",
			Integer.toHexString(System.identityHashCode(packet)));
		event.put("packetDirection", direction);
		event.put("captureStage", stage);
		event.put("monotonicNs", now);
		event.put("timestamp", Instant.now().toString());
		event.put("thread", Thread.currentThread().getName());
		event.put("clientTick", tick);
		String field = switch(stage)
		{
			case "DECODED", "INBOUND_CONNECTION" -> "packetArrivalNs";
			case "CLIENT_DISPATCH", "CLIENT_SCHEDULE_REQUESTED" -> "packetDispatchedNs";
			case "HANDLER_START" -> "handlerStartNs";
			case "HANDLER_END" -> "handlerEndNs";
			case "STATE_APPLIED" -> "stateAppliedNs";
			case "NEXT_CLIENT_TICK" -> "nextClientTickNs";
			case "ACTION_REQUESTED" -> "actionRequestedNs";
			case "SEND_REQUESTED" -> "sendRequestedNs";
			case "TRANSPORT_SUBMITTED" -> "transportSubmittedNs";
			case "WRITE_COMPLETED", "WRITE_FAILED" -> "transportWriteCompletedNs";
			default -> null;
		};
		if(field != null)
		{
			trace.times.putIfAbsent(field, now);
			boolean duplicate = !trace.seenStages.add(stage);
			if(duplicate && !stage.equals("INBOUND_CONNECTION"))
			{
				duplicateStages++;
				event.put("duplicateCapturePoint", true);
			}
		}
		if(context.get("transactionId") instanceof String transaction)
			trace.transactionId = transaction;
		if(trace.transactionId != null)
			event.put("transactionId", trace.transactionId);
		event.putAll(trace.times);
		interval(event, trace, "packetDispatchDelayNs", "packetArrivalNs",
			"handlerStartNs");
		interval(event, trace, "handlerDurationNs", "handlerStartNs",
			"handlerEndNs");
		interval(event, trace, "stateApplicationDelayNs", "packetArrivalNs",
			"stateAppliedNs");
		interval(event, trace, "nextTickDelayNs", "stateAppliedNs",
			"nextClientTickNs");
		interval(event, trace, "actionToSendNs", "actionRequestedNs",
			"sendRequestedNs");
		interval(event, trace, "sendPipelineNs", "sendRequestedNs",
			"transportSubmittedNs");
		interval(event, trace, "transportWriteNs", "transportSubmittedNs",
			"transportWriteCompletedNs");
		event.put("observationCompleteness",
			"ONLY_INDEPENDENTLY_OBSERVED_STAGES");
		return event;
	}
	
	private void interval(Map<String, Object> event, Trace trace, String name,
		String start, String end)
	{
		Long a = trace.times.get(start), b = trace.times.get(end);
		if(a == null || b == null || b < a)
			return;
		long duration = b - a;
		event.put(name, duration);
		if(trace.measured.putIfAbsent(name, true) == null)
		{
			long[] stats =
				durations.computeIfAbsent(name, unused -> new long[4]);
			stats[0]++;
			stats[1] += duration;
			stats[2] = Math.max(stats[2], duration);
			if(duration > 50_000_000L)
				stats[3]++;
		}
	}
	
	/**
	 * Bound to the original submission, even if the packet object is sent again
	 * before this callback.
	 */
	public synchronized Map<String, Object> transportCompleted(
		Map<String, Object> submission, long now, long tick, boolean success,
		String error)
	{
		Map<String, Object> event = new LinkedHashMap<>(submission);
		event.put("captureStage", success ? "WRITE_COMPLETED" : "WRITE_FAILED");
		event.put("packetObservationId",
			event.get("logicalPacketId") + "-" + (++observationSequence));
		event.put("observationSequence", observationSequence);
		event.put("monotonicNs", now);
		event.put("timestamp", Instant.now().toString());
		event.put("thread", Thread.currentThread().getName());
		event.put("clientTick", tick);
		event.put("transportWriteCompletedNs", now);
		event.put("localWriteSucceeded", success);
		event.put("remoteDeliveryConfirmed", false);
		if(error != null)
			event.put("writeError", error);
		if(submission.get("transportSubmittedNs") instanceof Number start
			&& now >= start.longValue())
		{
			long duration = now - start.longValue();
			event.put("transportWriteNs", duration);
			long[] stats = durations.computeIfAbsent("transportWriteNs",
				unused -> new long[4]);
			stats[0]++;
			stats[1] += duration;
			stats[2] = Math.max(stats[2], duration);
			if(duration > 50_000_000L)
				stats[3]++;
		}
		return event;
	}
	
	public synchronized boolean contains(Object packet)
	{
		return packets.containsKey(new IdentityReference(packet, null));
	}
	
	public synchronized boolean hasStage(Object packet, String field)
	{
		Trace trace = packets.get(new IdentityReference(packet, null));
		return trace != null && trace.times.containsKey(field);
	}
	
	public synchronized Map<String, Object> summary()
	{
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("distinctLogicalPackets", packetSequence);
		summary.put("metadataEvictions", metadataEvictions);
		summary.put("capturePointObservations", observationSequence);
		summary.put("duplicateCapturePointObservations", duplicateStages);
		durations.forEach((name, stats) -> summary.put(name,
			Map.of("samples", stats[0], "averageNs",
				stats[1] / (double)stats[0], "maximumNs", stats[2],
				"over50msSamples", stats[3])));
		return summary;
	}
	
	private static final class Trace
	{
		final String id;
		String transactionId;
		final java.util.Set<String> seenStages = new java.util.HashSet<>();
		final Map<String, Long> times = new LinkedHashMap<>();
		final Map<String, Boolean> measured = new HashMap<>();
		
		Trace(String id)
		{
			this.id = id;
		}
	}
	
	private static final class IdentityReference extends WeakReference<Object>
	{
		final int hash;
		
		IdentityReference(Object packet, ReferenceQueue<Object> queue)
		{
			super(packet, queue);
			hash = System.identityHashCode(packet);
		}
		
		@Override
		public int hashCode()
		{
			return hash;
		}
		
		@Override
		public boolean equals(Object other)
		{
			return this == other || other instanceof IdentityReference reference
				&& get() != null && get() == reference.get();
		}
	}
}
