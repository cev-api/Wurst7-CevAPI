/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Method;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class ItemForensicsRegressionTest
{
	public static void main(String[] args) throws Exception
	{
		Map<String, Object> first = new LinkedHashMap<>();
		first.put("count", 1);
		first.put("item", "minecraft:written_book");
		Map<String, Object> second = new LinkedHashMap<>();
		second.put("item", "minecraft:written_book");
		second.put("count", 1);
		check(
			ItemStackForensics.fingerprintCanonical(first)
				.equals(ItemStackForensics.fingerprintCanonical(second)),
			"Canonical fingerprints ignore map insertion order");
		
		ItemStackForensics.Snapshot absent = snapshot(Map.of());
		ItemStackForensics.Snapshot present = snapshot(Map.of("minecraft:lore",
			Map.of("state", "PRESENT", "valueSha256", "abc")));
		List<Map<String, Object>> diff =
			ItemInventoryForensics.componentDiff(absent, present);
		check(diff.size() == 1, "Component addition appears in diff");
		check(diff.get(0).get("beforeState").equals("ABSENT"),
			"Absent component is distinct from present component");
		check(diff.get(0).get("afterState").equals("PRESENT"),
			"Present component state is captured");
		ItemStackForensics.Snapshot sameSemanticA =
			snapshot(Map.of("minecraft:custom_data",
				Map.of("state", "PRESENT", "valueSha256", "same",
					"serializedChars", 10, "value",
					Map.of("raw", "preview-a"))));
		ItemStackForensics.Snapshot sameSemanticB =
			snapshot(Map.of("minecraft:custom_data",
				Map.of("state", "PRESENT", "valueSha256", "same",
					"serializedChars", 900, "value",
					Map.of("raw", "preview-b"))));
		check(
			ItemInventoryForensics.componentDiff(sameSemanticA, sameSemanticB)
				.isEmpty(),
			"Component representation changes do not create semantic diffs");
		ItemStackForensics.Snapshot changedSemantic =
			snapshot(Map.of("minecraft:custom_data",
				Map.of("state", "PRESENT", "valueSha256", "changed")));
		check(ItemInventoryForensics
			.componentDiff(sameSemanticA, changedSemantic).size() == 1,
			"A changed component value hash is reported");
		check(
			Boolean.TRUE.equals(ItemInventoryForensics
				.componentDiff(sameSemanticA, changedSemantic).get(0)
				.get("comparisonComplete")),
			"Hash-backed component comparisons are complete");
		
		var bookSummary = BookContentSummaries.fromJson(
			com.google.gson.JsonParser.parseString(
				"{\"title\":\"Test\",\"author\":\"Player\",\"pages\":[{\"raw\":\"one\"},\"two\"]}"),
			"SIGNED_COMPONENT");
		check(bookSummary.get("pageCount").equals(2),
			"Book summaries count all pages");
		check(bookSummary.get("totalUtf8ByteCount").equals(6L),
			"Book summaries count UTF-8 page bytes");
		check(!bookSummary.containsKey("pageContents"),
			"Book summaries never emit full page contents");
		List<String> largePages =
			java.util.Collections.nCopies(100, "a".repeat(3072));
		var largeBookSummary = BookContentSummaries.fromStrings(largePages,
			"Large", "Player", "SIGNED", true);
		check(largeBookSummary.get("pageCount").equals(100),
			"Large book summary includes all 100 pages");
		check(largeBookSummary.get("totalUtf8ByteCount").equals(307200L),
			"Large book summary reports complete 300 KiB UTF-8 content");
		check(((List<?>)largeBookSummary.get("pages")).size() == 100,
			"Large book summary retains metadata for every page");
		testPayloadDeduplication();
		
		String bookPayload = "x".repeat(300 * 1024);
		Object complete =
			PacketDumper.summarizeLargeValues(bookPayload, 1024 * 1024);
		check(complete.equals(bookPayload),
			"A 300 KiB book payload fits the default full-content limit");
		Object omitted = PacketDumper.summarizeLargeValues(bookPayload, 4096);
		check(
			omitted instanceof Map<?, ?> map
				&& Boolean.TRUE.equals(map.get("_contentOmitted"))
				&& ((Number)map.get("serializedBytes")).intValue() == 300
					* 1024,
			"Oversized content is explicitly summarized with its byte count");
		System.out.println(
			"Item forensics deterministic fingerprint and component-diff checks passed.");
	}
	
	private static void testPayloadDeduplication() throws Exception
	{
		ItemInventoryForensics forensics = new ItemInventoryForensics();
		Method append =
			ItemInventoryForensics.class.getDeclaredMethod("append", Map.class);
		append.setAccessible(true);
		for(int i = 0; i < 2; i++)
		{
			Map<String, Object> component = new LinkedHashMap<>();
			component.put("state", "PRESENT");
			component.put("valueSha256", "payload-hash");
			component.put("value", "x".repeat(4096));
			Map<String, Object> event = new LinkedHashMap<>();
			event.put("eventType", "item_mutation");
			event.put("component", component);
			append.invoke(forensics, event);
		}
		List<String> events = forensics.drain(10);
		long payloadCount = events.stream().map(JsonParser::parseString)
			.map(JsonObject.class::cast).filter(event -> "content_payload"
				.equals(event.get("eventType").getAsString()))
			.count();
		check(payloadCount == 1,
			"Identical component payloads are stored once per session");
		long references = events.stream().map(JsonParser::parseString)
			.map(JsonObject.class::cast)
			.filter(event -> event.has("component")
				&& event.getAsJsonObject("component").has("payloadReference"))
			.count();
		check(references == 2,
			"Every repeated component retains a payload reference");
	}
	
	private static ItemStackForensics.Snapshot snapshot(
		Map<String, Object> components)
	{
		return new ItemStackForensics.Snapshot("minecraft:air", 0, Map.of(),
			components, "identity", "fingerprint", "content", "{}");
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
