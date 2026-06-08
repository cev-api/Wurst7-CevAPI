/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.protocol.Packet;

/**
 * Tracks decode coverage for every packet class seen during verbose logging.
 * <ul>
 * <li>{@code FULLY_DECODED} – a type-specific dumper was used</li>
 * <li>{@code PARTIALLY_DECODED} – some fields decoded via reflection</li>
 * <li>{@code FALLBACK_ONLY} – only reflection, no specific dumper</li>
 * </ul>
 */
public final class PacketDecodeCoverage
{
	public enum DecodeLevel
	{
		FULLY_DECODED,
		PARTIALLY_DECODED,
		FALLBACK_ONLY
	}
	
	private final Map<String, DecodeLevel> coverage = new LinkedHashMap<>();
	private final Map<String, Integer> counts = new LinkedHashMap<>();
	
	public synchronized void record(Packet<?> packet, DecodeLevel level)
	{
		String key = packet.getClass().getSimpleName();
		DecodeLevel existing = coverage.get(key);
		// Upgrade: PARTIAL -> FULL if we later see a specific dumper
		if(existing == null || level.ordinal() < existing.ordinal())
			coverage.put(key, level);
		counts.merge(key, 1, Integer::sum);
	}
	
	public synchronized void clear()
	{
		coverage.clear();
		counts.clear();
	}
	
	/**
	 * @return an unmodifiable snapshot of the current coverage map.
	 */
	public synchronized Map<String, DecodeLevel> getCoverage()
	{
		return Collections.unmodifiableMap(new LinkedHashMap<>(coverage));
	}
	
	/**
	 * @return an unmodifiable snapshot of packet occurrence counts.
	 */
	public synchronized Map<String, Integer> getCounts()
	{
		return Collections.unmodifiableMap(new LinkedHashMap<>(counts));
	}
	
	/**
	 * Build a human-readable multi-line coverage report.
	 */
	public synchronized String buildReport()
	{
		if(coverage.isEmpty())
			return "[PacketDecodeCoverage] No packets recorded.";
		
		int total = counts.values().stream().mapToInt(Integer::intValue).sum();
		long full = coverage.values().stream()
			.filter(l -> l == DecodeLevel.FULLY_DECODED).count();
		long partial = coverage.values().stream()
			.filter(l -> l == DecodeLevel.PARTIALLY_DECODED).count();
		long fallback = coverage.values().stream()
			.filter(l -> l == DecodeLevel.FALLBACK_ONLY).count();
		
		StringBuilder sb = new StringBuilder();
		sb.append("\n\u00a7b=== Packet Decode Coverage Report ===\u00a7r\n");
		sb.append(String.format("Total packets: %d  Unique classes: %d\n",
			total, coverage.size()));
		sb.append(String.format("FULLY: %d  PARTIALLY: %d  FALLBACK: %d\n",
			full, partial, fallback));
		
		// List by decode level
		appendSection(sb, "FULLY DECODED", DecodeLevel.FULLY_DECODED);
		appendSection(sb, "PARTIALLY DECODED", DecodeLevel.PARTIALLY_DECODED);
		appendSection(sb, "FALLBACK ONLY", DecodeLevel.FALLBACK_ONLY);
		
		return sb.toString();
	}
	
	private void appendSection(StringBuilder sb, String title,
		DecodeLevel level)
	{
		List<String> names = new ArrayList<>();
		for(Map.Entry<String, DecodeLevel> e : coverage.entrySet())
			if(e.getValue() == level)
				names.add(e.getKey() + " (" + counts.getOrDefault(e.getKey(), 0)
					+ ")");
			
		if(names.isEmpty())
			return;
		
		sb.append("\n\u00a7e").append(title).append(":\u00a7r\n");
		Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
		for(String name : names)
			sb.append("  ").append(name).append("\n");
	}
}
