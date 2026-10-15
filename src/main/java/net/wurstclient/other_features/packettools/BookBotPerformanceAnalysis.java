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
 * Worker-confined, bounded observational comparisons. No causal attribution.
 */
public final class BookBotPerformanceAnalysis
{
	private final Map<String, Attempt> attempts = new LinkedHashMap<>();
	private final List<Map<String, Object>> phaseLockEvents = new ArrayList<>();
	private final Map<String, Map<String, Object>> phaseLockModes =
		new LinkedHashMap<>();
	private long evicted, phaseLockEventsOmitted;
	private static final Map<String, List<String>> ENDPOINTS =
		new LinkedHashMap<>();
	static
	{
		ENDPOINTS.put("editToBookResponseMs",
			List.of("BOOK_EDIT_SUBMITTED", "POPULATED_BOOK_PACKET_RECEIVED"));
		ENDPOINTS.put("bookArrivalToClientProcessingMs", List.of(
			"POPULATED_BOOK_PACKET_RECEIVED", "POPULATED_BOOK_HANDLER_START"));
		ENDPOINTS.put("bookArrivalToStateAppliedMs", List.of(
			"POPULATED_BOOK_PACKET_RECEIVED", "POPULATED_BOOK_STATE_APPLIED"));
		ENDPOINTS.put("bookHandlerDurationMs", List
			.of("POPULATED_BOOK_HANDLER_START", "POPULATED_BOOK_HANDLER_END"));
		ENDPOINTS.put("bookAppliedToShelfRequestMs", List
			.of("POPULATED_BOOK_STATE_APPLIED", "SHELF_INTERACTION_REQUESTED"));
		ENDPOINTS.put("shelfRequestToTransportSubmissionMs",
			List.of("SHELF_INTERACTION_REQUESTED",
				"SHELF_INTERACTION_TRANSPORT_SUBMITTED"));
		ENDPOINTS.put("shelfSubmissionToRemovalArrivalMs",
			List.of("SHELF_INTERACTION_TRANSPORT_SUBMITTED",
				"INVENTORY_REMOVAL_PACKET_RECEIVED"));
		ENDPOINTS.put("shelfSubmissionToRemovalApplicationMs",
			List.of("SHELF_INTERACTION_TRANSPORT_SUBMITTED",
				"INVENTORY_REMOVAL_STATE_APPLIED"));
		ENDPOINTS.put("shelfSubmissionToShelfUpdateMs",
			List.of("SHELF_INTERACTION_TRANSPORT_SUBMITTED",
				"SHELF_BLOCK_ENTITY_UPDATE_RECEIVED"));
		ENDPOINTS.put("bookArrivalToRemovalArrivalMs",
			List.of("POPULATED_BOOK_PACKET_RECEIVED",
				"INVENTORY_REMOVAL_PACKET_RECEIVED"));
		ENDPOINTS.put("bookAppliedToRemovalApplicationMs", List.of(
			"POPULATED_BOOK_STATE_APPLIED", "INVENTORY_REMOVAL_STATE_APPLIED"));
		ENDPOINTS.put("editToFinalOutcomeMs",
			List.of("BOOK_EDIT_SUBMITTED", "FINAL_OUTCOME"));
	}
	
	private static final class Attempt
	{
		final String id;
		final Map<String, Map<String, Object>> stages = new LinkedHashMap<>();
		final Map<String, Object> metadata = new LinkedHashMap<>();
		boolean bookBot, matching, dimensionChanged, shelfAttempt;
		String initialDimension;
		String outcome = "INDETERMINATE";
		long lastNs;
		
		Attempt(String id)
		{
			this.id = id;
		}
	}
	
	public void accept(Map<String, Object> event)
	{
		if(String.valueOf(event.get("name")).startsWith("BookBot.phase_lock"))
			acceptPhaseLockEvent(event);
		Object tx = event.get("transactionId");
		if(tx == null && event
			.get("relatedEarlierInteraction") instanceof Map<?, ?> related)
			tx = related.get("transactionId");
		if(tx instanceof String id)
			acceptFor(id, event);
		if(event.get("inventoryTimingItems") instanceof List<?> items)
			for(Object value : items)
				if(value instanceof Map<?, ?> raw)
				{
					Object id = raw.get("transactionId");
					if(!(id instanceof String transaction))
						continue;
					Map<String, Object> row = new LinkedHashMap<>(event);
					raw.forEach((k, v) -> row.put(String.valueOf(k), v));
					row.remove("inventoryTimingItems");
					acceptFor(transaction, row);
				}
	}
	
	private void acceptPhaseLockEvent(Map<String, Object> event)
	{
		Map<String, Object> compact = new LinkedHashMap<>();
		for(String key : List.of("name", "transactionId", "mode", "monotonicNs",
			"periodicUpdatePhase", "periodicUpdateCycleNs",
			"estimatedNextPeriodicUpdateNs", "timingUncertaintyMs",
			"selectedSubmissionDeadlineNs", "actualEditSubmissionNs",
			"editSubmissionToContentConfirmationMs", "finalVerifiedResult",
			"verifiedSuccess", "learningState", "lockConfidence",
			"recalibrationReason", "phaseReason", "phaseAvailable"))
			if(event.containsKey(key))
				compact.put(key, event.get(key));
		if(phaseLockEvents.size() < 512)
			phaseLockEvents.add(Collections.unmodifiableMap(compact));
		else
			phaseLockEventsOmitted++;
		if(!(event.get("mode") instanceof String mode))
			return;
		Map<String, Object> summary =
			phaseLockModes.computeIfAbsent(mode,
				unused -> new LinkedHashMap<>(Map.of("verifiedSuccesses", 0L,
					"verifiedFailures", 0L, "indeterminate", 0L, "state",
					"CALIBRATING", "confidence", 0.0, "recalibrationReason",
					"NO_VERIFIED_OUTCOMES", "stateTransitions",
					new ArrayList<String>())));
		Object state = event.get("learningState");
		if(state instanceof String stateName)
		{
			if(!stateName.equals(summary.get("state")))
				((List<String>)summary.get("stateTransitions")).add(stateName);
			summary.put("state", stateName);
		}
		if(event.get("lockConfidence") instanceof Number n)
			summary.put("confidence", n.doubleValue());
		if(event.get("recalibrationReason") instanceof String reason)
			summary.put("recalibrationReason", reason);
		if(!"BookBot.phase_lock_final_outcome".equals(event.get("name")))
			return;
		if(Boolean.TRUE.equals(event.get("verifiedSuccess")))
			summary.merge("verifiedSuccesses", 1L, (a, b) -> (long)a + (long)b);
		else if(event.get("finalVerifiedResult") instanceof String result
			&& List.of("SHELF_SLOT_STILL_EMPTY", "BOOK_REMOVED_BEFORE_TRANSFER",
				"NO_MATCHING_BOOK_RESPONSE", "EMPTY_BOOK_STORED",
				"STORED_WITH_DIFFERENT_CONTENT").contains(result))
			summary.merge("verifiedFailures", 1L, (a, b) -> (long)a + (long)b);
		else
			summary.merge("indeterminate", 1L, (a, b) -> (long)a + (long)b);
	}
	
	private void acceptFor(String id, Map<String, Object> e)
	{
		if(!id.startsWith("BookBot.edit-")
			&& !String.valueOf(e.get("name")).startsWith("BookBot."))
			return;
		Attempt a = attempts.computeIfAbsent(id, Attempt::new);
		while(attempts.size() > 256)
		{
			attempts.remove(attempts.keySet().iterator().next());
			evicted++;
		}
		a.bookBot = true;
		if(e.get("dimension") instanceof String dim && dim.contains(":"))
		{
			if(a.initialDimension == null)
				a.initialDimension = dim;
			else if(!a.initialDimension.equals(dim))
				a.dimensionChanged = true;
		}
		String name = String.valueOf(e.get("name")),
			phase = String.valueOf(e.get("captureStage")),
			packet = String.valueOf(e.get("packetClass"));
		if(Boolean.TRUE.equals(e.get("shelfTransferRequested"))
			|| packet.contains("UseItemOn") || name.startsWith("BookBot.shelf_")
			|| name.equals("BookBot.book_removed_before_transfer")
			|| name.equals("BookBot.no_matching_book_response"))
			a.shelfAttempt = true;
		long now = number(e, "monotonicNs", 0);
		a.lastNs = Math.max(a.lastNs, now);
		for(String key : List.of("dimension", "playerPosition", "shelfPosition",
			"payloadBytes", "pageCount", "contentSha256", "strategy",
			"inventorySlot"))
			if(e.get(key) != null)
				a.metadata.put(key, e.get(key));
		if(phase.equals("TRANSPORT_SUBMITTED") && packet.contains("EditBook"))
			stage(a, "BOOK_EDIT_SUBMITTED", e,
				number(e, "transportSubmittedNs", now));
		if(name.equals("BookBot.book_edit_submitted")
			&& !a.stages.containsKey("BOOK_EDIT_SUBMITTED"))
			stage(a, "BOOK_EDIT_REQUESTED_SEND_NOT_OBSERVED", e,
				number(e, "editSubmittedNs", now));
		boolean populated = number(e, "suppliedBookPageCount", 0) > 0
			|| number(e, "observedBookPageCount", 0) > 0;
		boolean removed = Boolean.TRUE.equals(e.get("suppliedItemEmpty"))
			|| Boolean.TRUE.equals(e.get("observedItemEmpty"));
		if(e.get("itemBookContentAfter") instanceof Map<?, ?> book
			&& book.get("pageCount") instanceof Number n)
			populated |= n.intValue() > 0;
		removed |= "item_removed".equals(e.get("change"));
		if(populated)
		{
			if(e.get("packetArrivalNs") instanceof Number n)
				stage(a, "POPULATED_BOOK_PACKET_RECEIVED", e, n.longValue());
			if(e.get("handlerStartNs") instanceof Number n)
				stage(a, "POPULATED_BOOK_HANDLER_START", e, n.longValue());
			if(e.get("handlerEndNs") instanceof Number end)
				stage(a, "POPULATED_BOOK_HANDLER_END", e, end.longValue());
			if(phase.equals("STATE_APPLIED")
				&& (number(e, "observedBookPageCount", 0) > 0
					|| e.containsKey("itemBookContentAfter"))
				&& e.get("stateAppliedNs") instanceof Number n)
				stage(a, "POPULATED_BOOK_STATE_APPLIED", e, n.longValue());
		}
		if(removed)
		{
			if(e.get("packetArrivalNs") instanceof Number n)
				stage(a, "INVENTORY_REMOVAL_PACKET_RECEIVED", e, n.longValue());
			if(phase.equals("STATE_APPLIED")
				&& (Boolean.TRUE.equals(e.get("observedItemEmpty"))
					|| "item_removed".equals(e.get("change")))
				&& e.get("stateAppliedNs") instanceof Number n)
				stage(a, "INVENTORY_REMOVAL_STATE_APPLIED", e, n.longValue());
		}
		if(name.equals("BookBot.book_content_confirmed"))
			stage(a, "BOOK_CONTENT_VERIFIED", e,
				number(e, "contentConfirmedNs", now));
		if(name.equals("BookBot.shelf_interaction_requested"))
			stage(a, "SHELF_INTERACTION_REQUESTED", e,
				number(e, "interactionRequestedNs", now));
		if(phase.equals("TRANSPORT_SUBMITTED") && packet.contains("UseItemOn"))
			stage(a, "SHELF_INTERACTION_TRANSPORT_SUBMITTED", e,
				number(e, "transportSubmittedNs", now));
		if(packet.contains("BlockEntityData")
			&& e.get("packetArrivalNs") instanceof Number n)
			stage(a, "SHELF_BLOCK_ENTITY_UPDATE_RECEIVED", e, n.longValue());
		if(name.equals("BookBot.shelf_server_update"))
		{
			stage(a, "SHELF_CONTENT_VERIFIED", e, now);
			String result = String.valueOf(e.get("outcome"));
			if(result.equals("SERVER_CONFIRMED_MATCHING_CONTENT"))
			{
				a.matching = true;
				a.outcome = a.dimensionChanged ? "INDETERMINATE"
					: "CONFIRMED_SUCCESSFUL";
			}else if(!a.matching)
				a.outcome = result.equals("SHELF_SLOT_STILL_EMPTY")
					? "SHELF_REMAINED_EMPTY" : "INDETERMINATE";
			stage(a, "FINAL_OUTCOME", e, now);
		}
		if(!a.matching && name.equals("BookBot.no_matching_book_response"))
		{
			a.outcome = "NO_MATCHING_BOOK_RESPONSE";
			stage(a, "FINAL_OUTCOME", e, now);
		}
		if(!a.matching && name.equals("BookBot.book_removed_before_transfer"))
		{
			a.outcome = "BOOK_REMOVED_BEFORE_TRANSFER";
			stage(a, "FINAL_OUTCOME", e, now);
		}
		if(!a.matching && (name.equals("BookBot.shelf_transfer_unverified")
			|| name.equals("BookBot.shelf_transfer_failed")))
		{
			if(!a.outcome.equals("SHELF_REMAINED_EMPTY")
				&& !a.outcome.equals("BOOK_REMOVED_BEFORE_TRANSFER")
				&& !a.outcome.equals("NO_MATCHING_BOOK_RESPONSE"))
				a.outcome = "INDETERMINATE";
			stage(a, "FINAL_OUTCOME", e, now);
		}
	}
	
	private static void stage(Attempt a, String name, Map<String, Object> event,
		long ns)
	{
		if(ns <= 0)
			return;
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("stage", name);
		row.put("monotonicNs", ns);
		for(String key : List.of("logicalPacketId", "packetObservationId",
			"clientTick", "clientTickPhaseMs", "clientTickPhaseObservationNs",
			"clientTickBoundaryNs", "relationshipConfidence",
			"correlationConfidence", "fps", "pingMs",
			"recentClientTickDurationNs", "contextObservedNs", "dimension",
			"timeUpdateAgeMs", "serverTickPhase"))
			if(event.get(key) != null)
				row.put(key, event.get(key));
		Map<String, Object> previous = a.stages.get(name);
		if(previous == null
			|| ns < number(previous, "monotonicNs", Long.MAX_VALUE)
			|| name.equals("FINAL_OUTCOME"))
			a.stages.put(name, Collections.unmodifiableMap(row));
	}
	
	public void clear()
	{
		attempts.clear();
	}
	
	public Map<String, Object> summary()
	{
		Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
		for(String name : List.of("CONFIRMED_SUCCESSFUL",
			"SHELF_REMAINED_EMPTY", "BOOK_REMOVED_BEFORE_TRANSFER",
			"NO_MATCHING_BOOK_RESPONSE", "INDETERMINATE"))
			groups.put(name, new ArrayList<>());
		List<Map<String, Object>> rows = new ArrayList<>();
		for(Attempt a : attempts.values())
		{
			if(!a.bookBot || !a.shelfAttempt)
				continue;
			Map<String, Object> row = new LinkedHashMap<>(a.metadata);
			row.put("transactionId", a.id);
			row.put("outcome",
				a.dimensionChanged ? "INDETERMINATE" : a.outcome);
			row.put("dimensionContinuityUnknown", a.dimensionChanged);
			var timeline = new ArrayList<>(a.stages.values());
			timeline.sort(
				Comparator.comparingLong(e -> number(e, "monotonicNs", 0)));
			row.put("timeline", timeline);
			Map<String, Object> durations = new LinkedHashMap<>();
			ENDPOINTS.forEach((name, ends) -> {
				var from = a.stages.get(ends.get(0));
				var to = a.stages.get(ends.get(1));
				if(from != null && to != null)
					durations.put(name, (number(to, "monotonicNs", 0)
						- number(from, "monotonicNs", 0)) / 1e6);
			});
			row.put("timingsMs", durations);
			Map<String, Object> measurement = new LinkedHashMap<>(durations);
			Map<String, Object> edit = a.stages.get("BOOK_EDIT_SUBMITTED");
			if(edit == null && !timeline.isEmpty())
				edit = timeline.getFirst();
			if(edit != null)
			{
				copyNumber(edit, measurement, "pingMs",
					"estimatedNetworkPingMs");
				copyNumber(edit, measurement, "fps", "clientFps");
				if(edit.get("recentClientTickDurationNs") instanceof Number n)
					measurement.put("clientTickDurationMs",
						n.doubleValue() / 1e6);
			}
			row.put("comparisonMeasurements", measurement);
			for(String field : List.of("estimatedNetworkPingMs", "clientFps",
				"clientTickDurationMs"))
				if(measurement.get(field) != null)
					row.put(field, measurement.get(field));
			rows.add(row);
			groups.get(String.valueOf(row.get("outcome"))).add(row);
		}
		Map<String, Object> statistics = new LinkedHashMap<>();
		groups.forEach((name, group) -> {
			Map<String, List<Double>> values = new TreeMap<>();
			for(var attempt : group)
				((Map<?, ?>)attempt.get("comparisonMeasurements"))
					.forEach((key, value) -> {
						if(value instanceof Number n
							&& Double.isFinite(n.doubleValue()))
							values
								.computeIfAbsent(String.valueOf(key),
									unused -> new ArrayList<>())
								.add(n.doubleValue());
					});
			Map<String, Object> metrics = new LinkedHashMap<>();
			values.forEach(
				(name2, list) -> metrics.put(name2, distribution(list)));
			statistics.put(name,
				Map.of("attempts", group.size(), "metrics", metrics));
		});
		List<String> findings = new ArrayList<>();
		int successes = groups.get("CONFIRMED_SUCCESSFUL").size(),
			failures = groups.get("SHELF_REMAINED_EMPTY").size()
				+ groups.get("BOOK_REMOVED_BEFORE_TRANSFER").size()
				+ groups.get("NO_MATCHING_BOOK_RESPONSE").size();
		if(successes < 3 || failures < 3)
			findings.add(
				"Insufficient successful/failed attempts for a stable comparison; correlations do not establish causation.");
		else
		{
			Map<?, ?> success =
				(Map<?, ?>)((Map<?, ?>)statistics.get("CONFIRMED_SUCCESSFUL"))
					.get("metrics");
			for(String metric : List.of("shelfSubmissionToRemovalArrivalMs",
				"bookArrivalToClientProcessingMs"))
			{
				List<Double> failed = new ArrayList<>();
				for(var row : rows)
					if(!row.get("outcome").equals("CONFIRMED_SUCCESSFUL")
						&& !row.get("outcome").equals("INDETERMINATE")
						&& ((Map<?, ?>)row.get("comparisonMeasurements"))
							.get(metric) instanceof Number n)
						failed.add(n.doubleValue());
				if(success.get(metric) instanceof Map<?, ?> distribution
					&& number(distribution, "samples", 0) >= 3
					&& failed.size() >= 3)
				{
					double a =
						((Number)distribution.get("median")).doubleValue(),
						b = ((Number)distribution(failed).get("median"))
							.doubleValue();
					findings.add(metric + ": observed success median=" + a
						+ ", other-outcome median=" + b
						+ "; descriptive comparison only, no causal claim.");
				}
			}
		}
		return Map.of("attempts", rows, "groups", statistics,
			"intervalEndpoints", ENDPOINTS, "findings", findings, "phaseLock",
			Map.of("modes", phaseLockModes, "events", phaseLockEvents,
				"eventsOmittedByMemoryBound", phaseLockEventsOmitted),
			"attemptsOmittedByMemoryBound", evicted, "serverBookHandlerPhases",
			"UNOBSERVABLE_WITHOUT_TARGETED_SERVER_INSTRUMENTATION");
	}
	
	static Map<String, Object> distribution(List<Double> input)
	{
		var values = new ArrayList<>(input);
		values.sort(Double::compare);
		int n = values.size();
		return Map.of(
			"samples", n, "average", values.stream()
				.mapToDouble(Double::doubleValue).average().orElseThrow(),
			"median",
			n % 2 == 0 ? (values.get(n / 2 - 1) + values.get(n / 2)) / 2
				: values.get(n / 2),
			"minimum", values.getFirst(), "maximum", values.getLast(), "p95",
			values.get((int)Math.ceil(n * 0.95) - 1));
	}
	
	public String markdown()
	{
		Map<String, Object> summary = summary();
		Map<?, ?> groups = (Map<?, ?>)summary.get("groups");
		Set<String> metrics = new TreeSet<>();
		groups.values().forEach(value -> metrics.addAll(
			((Map<String, Object>)((Map<?, ?>)value).get("metrics")).keySet()));
		StringBuilder text = new StringBuilder(
			"\n## BookBot outcome comparison\n\nValues are median (available samples); missing data is unavailable. Performance is sampled at edit transport submission.\n\n| Metric |");
		for(Object group : groups.keySet())
			text.append(' ').append(group).append(" |");
		text.append("\n|---|");
		for(int i = 0; i < groups.size(); i++)
			text.append("---:|");
		text.append("\n| Attempts |");
		for(Object value : groups.values())
			text.append(' ').append(((Map<?, ?>)value).get("attempts"))
				.append(" |");
		for(String metric : metrics)
		{
			text.append("\n| ").append(metric).append(" |");
			for(Object group : groups.values())
			{
				Object stat =
					((Map<?, ?>)((Map<?, ?>)group).get("metrics")).get(metric);
				if(stat instanceof Map<?, ?> m)
					text.append(' ').append(String.format(java.util.Locale.ROOT,
						"%.3f (%s)", m.get("median"), m.get("samples")));
				else
					text.append(" unavailable");
				text.append(" |");
			}
		}
		text.append("\n\n");
		for(Object finding : (List<?>)summary.get("findings"))
			text.append("- ").append(finding).append('\n');
		Map<?, ?> phaseLock = (Map<?, ?>)summary.get("phaseLock");
		Map<?, ?> modes = (Map<?, ?>)phaseLock.get("modes");
		text.append(
			"\n## PhaseLock by output mode\n\n| Mode | State | Verified successes | Verified failures | Indeterminate | Confidence |\n|---|---|---:|---:|---:|---:|\n");
		for(Object entry : modes.entrySet())
		{
			Map.Entry<?, ?> mode = (Map.Entry<?, ?>)entry;
			Map<?, ?> values = (Map<?, ?>)mode.getValue();
			text.append("| ").append(mode.getKey()).append(" | ")
				.append(values.get("state")).append(" | ")
				.append(values.get("verifiedSuccesses")).append(" | ")
				.append(values.get("verifiedFailures")).append(" | ")
				.append(values.get("indeterminate")).append(" | ")
				.append(String.format(java.util.Locale.ROOT, "%.2f",
					values.get("confidence")))
				.append(" |\n");
		}
		text.append(
			"\nPhaseLock event details and observed deadlines are in the JSONL and JSON summary. Phases refer to client receipt cadence, not an authoritative server tick phase.\n");
		text.append(
			"\nRaw endpoint definitions, actual ordering, stage performance snapshots and per-attempt measurements are in the JSON summary. Server processing order and server tick phase remain unassigned without direct server evidence.\n");
		return text.toString();
	}
	
	private static void copyNumber(Map<String, Object> from,
		Map<String, Object> to, String key, String target)
	{
		if(from.get(key) instanceof Number n)
			to.put(target, n);
	}
	
	private static long number(Map<?, ?> map, String key, long fallback)
	{
		return map.get(key) instanceof Number n ? n.longValue() : fallback;
	}
}
