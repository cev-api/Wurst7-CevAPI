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

public final class ItemForensicsRegressionTest
{
	public static void main(String[] args)
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
