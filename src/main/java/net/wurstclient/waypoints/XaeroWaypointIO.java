/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.waypoints;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;

final class XaeroWaypointIO
{
	static final String HEADER =
		"#waypoint:name:initials:x:y:z:color:disabled:type:set:rotate_on_tp:tp_yaw:visibility_type:destination";
	static final String DEFAULT_SET = "gui.xaero_default";
	private static final int DEFAULT_TYPE_IDX = 0;
	// Xaero only keeps waypoints with icon type IDs in [0, 4]. Clamp anything
	// higher so exports persist.
	private static final int MAX_SUPPORTED_TYPE = 4;
	private static final String SAFE_DELIMITER = "§§";
	private static final int[] COLOR_TABLE = new int[]{0xFFFFFFFF, 0xFFFFAA00,
		0xFFFF55FF, 0xFF55FFFF, 0xFFFFFF55, 0xFF55FF55, 0xFFFF55AA, 0xFF555555,
		0xFFAAAAAA, 0xFF00AAAA, 0xFFAA00AA, 0xFF5555FF, 0xFFAA5500, 0xFF00AA00,
		0xFFFF5555, 0xFF000000};
	private static final Map<Integer, String> TYPE_TO_ICON = Map.ofEntries(
		Map.entry(0, "star"), Map.entry(1, "diamond"), Map.entry(2, "triangle"),
		Map.entry(3, "square"), Map.entry(4, "circle"), Map.entry(5, "heart"),
		Map.entry(6, "skull"), Map.entry(7, "check"), Map.entry(8, "x"),
		Map.entry(9, "arrow_down"), Map.entry(10, "sun"),
		Map.entry(11, "snowflake"));
	private static final Map<String, Integer> ICON_TO_TYPE = new HashMap<>();
	static
	{
		for(Map.Entry<Integer, String> e : TYPE_TO_ICON.entrySet())
			ICON_TO_TYPE.put(e.getValue(), e.getKey());
	}
	
	static record FileData(List<String> sets, List<Entry> entries)
	{}
	
	static record Entry(String name, String initials, BlockPos pos,
		boolean yIncluded, int colorIdx, boolean disabled, int type,
		String setId, boolean rotateOnTp, int tpYaw, int visibilityType,
		boolean destination)
	{}
	
	private XaeroWaypointIO()
	{}
	
	static FileData read(Path file) throws IOException
	{
		List<String> sets = new ArrayList<>();
		List<Entry> entries = new ArrayList<>();
		if(!Files.isRegularFile(file))
			return new FileData(List.of(), List.of());
		List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
		for(String rawLine : lines)
		{
			String line = rawLine.trim();
			if(line.isEmpty())
				continue;
			if(line.startsWith("sets:"))
			{
				String[] parts = line.split(":");
				for(int i = 1; i < parts.length; i++)
				{
					String setId = parts[i].trim();
					if(!setId.isEmpty())
						sets.add(setId);
				}
				continue;
			}
			if(!line.startsWith("waypoint:"))
				continue;
			String[] parts = splitLine(line);
			if(parts == null)
				continue;
			try
			{
				String name = decode(parts[1]);
				String initials = decode(parts[2]);
				int x = parseInt(parts[3], 0);
				boolean yIncluded = !"~".equals(parts[4]);
				int y = yIncluded ? parseInt(parts[4], 0) : 0;
				int z = parseInt(parts[5], 0);
				int colorIdx = parseInt(parts[6], 0);
				boolean disabled = parseBoolean(parts[7]);
				int type = parseInt(parts[8], 0);
				String setId = parts[9];
				boolean rotate = parseBoolean(parts[10]);
				int yaw = parseInt(parts[11], 0);
				int visibility = parseInt(parts[12], 0);
				boolean destination = parseBoolean(parts[13]);
				entries.add(new Entry(name, initials, new BlockPos(x, y, z),
					yIncluded, colorIdx, disabled, type, setId, rotate, yaw,
					visibility, destination));
			}catch(Exception ignored)
			{}
		}
		return new FileData(List.copyOf(sets), List.copyOf(entries));
	}
	
	static void write(Path file, List<String> sets, List<Entry> entries)
		throws IOException
	{
		Files.createDirectories(file.getParent());
		List<String> lines = new ArrayList<>();
		List<String> orderedSets =
			new ArrayList<>(sets == null ? List.of() : sets);
		if(orderedSets.isEmpty())
			orderedSets.add(DEFAULT_SET);
		else if(!orderedSets.contains(DEFAULT_SET))
			orderedSets.add(DEFAULT_SET);
		lines.add("sets:" + String.join(":", orderedSets));
		lines.add("#");
		lines.add(HEADER);
		lines.add("#");
		for(Entry entry : entries)
		{
			StringBuilder sb = new StringBuilder("waypoint:");
			sb.append(encode(entry.name())).append(':')
				.append(encode(entry.initials())).append(':');
			sb.append(entry.pos().getX()).append(':');
			sb.append(
				entry.yIncluded() ? Integer.toString(entry.pos().getY()) : "~")
				.append(':');
			sb.append(entry.pos().getZ()).append(':');
			sb.append(entry.colorIdx()).append(':')
				.append(Boolean.toString(entry.disabled())).append(':')
				.append(entry.type()).append(':');
			sb.append(entry.setId()).append(':');
			sb.append(Boolean.toString(entry.rotateOnTp())).append(':')
				.append(entry.tpYaw()).append(':')
				.append(entry.visibilityType()).append(':')
				.append(Boolean.toString(entry.destination()));
			lines.add(sb.toString());
		}
		if(Files.isRegularFile(file))
		{
			Path backup =
				file.resolveSibling(file.getFileName().toString() + ".bak");
			try
			{
				Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
			}catch(IOException ignored)
			{}
		}
		Files.write(file, lines, StandardCharsets.UTF_8);
	}
	
	static Entry fromWaypoint(Waypoint waypoint)
	{
		String name = sanitizeName(waypoint.getName());
		String initials = initialsFor(name);
		BlockPos pos = waypoint.getPos();
		int colorIdx = colorIndexFor(waypoint.getColor());
		boolean disabled = !waypoint.isVisible();
		int type = typeFromIcon(waypoint.getIcon());
		return new Entry(name, initials,
			new BlockPos(pos.getX(), pos.getY(), pos.getZ()), true, colorIdx,
			disabled, type, DEFAULT_SET, false, 0, 0, false);
	}
	
	static int colorFromIndex(int idx)
	{
		if(idx < 0 || idx >= COLOR_TABLE.length)
			return COLOR_TABLE[0];
		return COLOR_TABLE[idx];
	}
	
	static int colorIndexFor(int argb)
	{
		int targetR = (argb >> 16) & 0xFF;
		int targetG = (argb >> 8) & 0xFF;
		int targetB = argb & 0xFF;
		int bestIdx = 0;
		long bestDist = Long.MAX_VALUE;
		for(int i = 0; i < COLOR_TABLE.length; i++)
		{
			int rgb = COLOR_TABLE[i];
			int r = (rgb >> 16) & 0xFF;
			int g = (rgb >> 8) & 0xFF;
			int b = rgb & 0xFF;
			long dr = targetR - r;
			long dg = targetG - g;
			long db = targetB - b;
			long dist = dr * dr + dg * dg + db * db;
			if(dist < bestDist)
			{
				bestDist = dist;
				bestIdx = i;
			}
		}
		return bestIdx;
	}
	
	static String iconFromType(int type)
	{
		return TYPE_TO_ICON.getOrDefault(type, "star");
	}
	
	static int typeFromIcon(String icon)
	{
		if(icon == null)
			return DEFAULT_TYPE_IDX;
		String key = icon.toLowerCase(Locale.ROOT);
		int type = ICON_TO_TYPE.getOrDefault(key, DEFAULT_TYPE_IDX);
		return type <= MAX_SUPPORTED_TYPE ? type : DEFAULT_TYPE_IDX;
	}
	
	private static String encode(String value)
	{
		if(value == null || value.isEmpty())
			return "";
		return value.replace(":", SAFE_DELIMITER);
	}
	
	private static String decode(String value)
	{
		if(value == null || value.isEmpty())
			return "";
		return value.replace(SAFE_DELIMITER, ":");
	}
	
	private static String[] splitLine(String line)
	{
		String[] raw = line.split(":", -1);
		if(raw.length < 14)
			return null;
		if(raw.length == 14)
			return raw;
		int extra = raw.length - 14;
		StringBuilder name = new StringBuilder(raw[1]);
		for(int i = 0; i < extra; i++)
			name.append(':').append(raw[2 + i]);
		String[] fixed = new String[14];
		fixed[0] = raw[0];
		fixed[1] = name.toString();
		System.arraycopy(raw, 2 + extra, fixed, 2, 12);
		return fixed;
	}
	
	private static int parseInt(String value, int fallback)
	{
		if(value == null)
			return fallback;
		try
		{
			return Integer.parseInt(value.trim());
		}catch(NumberFormatException e)
		{
			return fallback;
		}
	}
	
	private static boolean parseBoolean(String value)
	{
		return value != null && value.equalsIgnoreCase("true");
	}
	
	private static String sanitizeName(String name)
	{
		if(name == null)
			return "Waypoint";
		String trimmed = name.replace("\r", " ").replace("\n", " ").trim();
		if(trimmed.isEmpty())
			return "Waypoint";
		return trimmed;
	}
	
	private static String initialsFor(String name)
	{
		String trimmed = name.trim();
		if(trimmed.isEmpty())
			return "WP";
		StringBuilder sb = new StringBuilder();
		for(String part : trimmed.split("\s+"))
		{
			if(part.isEmpty())
				continue;
			char c = part.charAt(0);
			if(Character.isLetterOrDigit(c))
				sb.append(Character.toUpperCase(c));
			if(sb.length() >= 3)
				break;
		}
		if(sb.length() == 0)
		{
			for(char c : trimmed.toCharArray())
				if(Character.isLetterOrDigit(c))
				{
					sb.append(Character.toUpperCase(c));
					if(sb.length() >= 2)
						break;
				}
		}
		if(sb.length() == 0)
			return "WP";
		return sb.toString();
	}
}
