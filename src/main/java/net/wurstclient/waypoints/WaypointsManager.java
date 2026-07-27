/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.waypoints;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.storage.LevelResource;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class WaypointsManager
{
	private static final long DUPLICATE_RADIUS_SQ = 100L * 100L;
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	
	private final Path folder;
	private final ArrayList<Waypoint> waypoints = new ArrayList<>();
	
	public record XaeroSyncStats(int imported, int updated, int skipped,
		int exported, List<Path> filesTouched)
	{}
	
	public WaypointsManager(Path worldFolder)
	{
		this.folder = worldFolder.resolve("waypoints");
		try
		{
			Files.createDirectories(folder);
		}catch(IOException e)
		{}
	}
	
	public List<Waypoint> all()
	{
		return waypoints;
	}
	
	public Waypoint getByName(String name)
	{
		for(Waypoint w : waypoints)
			if(w.getName().equalsIgnoreCase(name))
				return w;
		return null;
	}
	
	public void addOrUpdate(Waypoint w)
	{
		int i = indexOfUuid(w.getUuid());
		if(i >= 0)
			waypoints.set(i, w);
		else
			waypoints.add(w);
	}
	
	public boolean remove(Waypoint w)
	{
		return waypoints.removeIf(x -> x.getUuid().equals(w.getUuid()));
	}
	
	private int indexOfUuid(UUID id)
	{
		for(int i = 0; i < waypoints.size(); i++)
			if(waypoints.get(i).getUuid().equals(id))
				return i;
		return -1;
	}
	
	public void load(String worldId)
	{
		Path file = folder.resolve(worldId + ".json");
		waypoints.clear();
		if(!Files.isRegularFile(file))
			return;
		try
		{
			JsonObject root =
				GSON.fromJson(Files.newBufferedReader(file), JsonObject.class);
			JsonArray arr = root.getAsJsonArray("waypoints");
			if(arr == null)
				return;
			arr.forEach(el -> waypoints.add(fromJson(el.getAsJsonObject())));
		}catch(Exception e)
		{ /* ignore */ }
	}
	
	public void save(String worldId)
	{
		Path file = folder.resolve(worldId + ".json");
		JsonObject root = new JsonObject();
		JsonArray arr = new JsonArray();
		for(Waypoint w : waypoints)
			arr.add(toJson(w));
		root.add("waypoints", arr);
		try
		{
			Files.writeString(file, GSON.toJson(root));
		}catch(IOException e)
		{}
	}
	
	public XaeroSyncStats importFromXaero(String worldId)
	{
		Path worldFolder = findXaeroWorldFolder(worldId);
		if(worldFolder == null)
			return new XaeroSyncStats(0, 0, 0, 0, List.of());
		int imported = 0;
		int skipped = 0;
		Set<Path> touched = new LinkedHashSet<>();
		for(WaypointDimension dimension : WaypointDimension.values())
		{
			Path dimFolder = worldFolder.resolve(xaeroDimFolder(dimension));
			if(!Files.isDirectory(dimFolder))
				continue;
			List<Path> files = new ArrayList<>();
			try(Stream<Path> stream = Files.list(dimFolder))
			{
				stream
					.filter(p -> Files.isRegularFile(p)
						&& isXaeroWaypointName(p.getFileName().toString()))
					.forEach(files::add);
			}catch(IOException e)
			{
				skipped++;
				continue;
			}
			for(Path file : files)
			{
				touched.add(file);
				XaeroWaypointIO.FileData data;
				try
				{
					data = XaeroWaypointIO.read(file);
				}catch(IOException e)
				{
					skipped++;
					continue;
				}
				for(XaeroWaypointIO.Entry entry : data.entries())
				{
					BlockPos pos = entry.pos();
					if(hasNearbyWaypoint(dimension, pos))
					{
						skipped++;
						continue;
					}
					Waypoint w = new Waypoint(UUID.randomUUID(),
						System.currentTimeMillis());
					w.setName(entry.name());
					w.setPos(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
					w.setDimension(dimension);
					w.setColor(
						XaeroWaypointIO.colorFromIndex(entry.colorIdx()));
					w.setVisible(!entry.disabled());
					w.setLines(false);
					w.setBeaconMode(Waypoint.BeaconMode.OFF);
					w.setIcon(XaeroWaypointIO.iconFromType(entry.type()));
					addOrUpdate(w);
					imported++;
				}
			}
		}
		return new XaeroSyncStats(imported, 0, skipped, 0,
			List.copyOf(touched));
	}
	
	public XaeroSyncStats exportToXaero(String worldId)
	{
		Path worldFolder = ensureXaeroWorldFolder(worldId);
		if(worldFolder == null)
			return new XaeroSyncStats(0, 0, 0, 0, List.of());
		int exported = 0;
		int skipped = 0;
		Set<Path> touched = new LinkedHashSet<>();
		for(WaypointDimension dimension : WaypointDimension.values())
		{
			ArrayList<Waypoint> subset = new ArrayList<>();
			for(Waypoint waypoint : waypoints)
				if(waypoint.getDimension() == dimension)
					subset.add(waypoint);
			if(subset.isEmpty())
				continue;
			Path dimFolder = worldFolder.resolve(xaeroDimFolder(dimension));
			Path file = dimFolder.resolve("mw$default_1.txt");
			XaeroWaypointIO.FileData existing;
			try
			{
				existing = XaeroWaypointIO.read(file);
			}catch(IOException e)
			{
				existing = new XaeroWaypointIO.FileData(List.of(), List.of());
			}
			List<XaeroWaypointIO.Entry> combined =
				new ArrayList<>(existing.entries());
			int addedForDim = 0;
			for(Waypoint waypoint : subset)
			{
				BlockPos pos = waypoint.getPos();
				if(hasNearbyXaeroWaypoint(combined, pos))
					continue;
				combined.add(XaeroWaypointIO.fromWaypoint(waypoint));
				addedForDim++;
			}
			if(addedForDim == 0)
				continue;
			List<String> setIds = new ArrayList<>(existing.sets());
			try
			{
				XaeroWaypointIO.write(file, setIds, combined);
				exported += addedForDim;
				touched.add(file);
			}catch(IOException e)
			{
				skipped++;
			}
		}
		return new XaeroSyncStats(0, 0, skipped, exported,
			List.copyOf(touched));
	}
	
	public XaeroSyncStats importFromVoxel(String worldId)
	{
		Path file = findVoxelFile(worldId);
		if(file == null)
			return new XaeroSyncStats(0, 0, 0, 0, List.of());
		int imported = 0;
		int skipped = 0;
		try
		{
			for(String raw : Files.readAllLines(file, StandardCharsets.UTF_8))
			{
				if(!raw.startsWith("name:") || !raw.contains(",x:"))
					continue;
				try
				{
					Map<String, String> values = parseVoxelLine(raw);
					BlockPos pos =
						new BlockPos(Integer.parseInt(values.get("x")),
							Integer.parseInt(values.getOrDefault("y", "0")),
							Integer.parseInt(values.get("z")));
					WaypointDimension dimension =
						voxelDimension(values.get("dimensions"));
					if(hasNearbyWaypoint(dimension, pos))
					{
						skipped++;
						continue;
					}
					Waypoint waypoint = new Waypoint(UUID.randomUUID(),
						System.currentTimeMillis());
					waypoint.setName(descrubVoxel(
						values.getOrDefault("name", "VoxelMap Waypoint")));
					waypoint.setPos(pos);
					waypoint.setDimension(dimension);
					waypoint.setVisible(Boolean
						.parseBoolean(values.getOrDefault("enabled", "true")));
					waypoint.setBeaconMode(Boolean
						.parseBoolean(values.getOrDefault("beacon", "false"))
							? Waypoint.BeaconMode.ESP
							: Waypoint.BeaconMode.OFF);
					waypoint.setColor(voxelColor(values));
					waypoint.setLines(false);
					waypoint.setIcon(voxelIcon(values.get("suffix")));
					addOrUpdate(waypoint);
					imported++;
				}catch(RuntimeException e)
				{
					skipped++;
				}
			}
		}catch(IOException e)
		{
			skipped++;
		}
		return new XaeroSyncStats(imported, 0, skipped, 0, List.of(file));
	}
	
	public XaeroSyncStats exportToVoxel(String worldId)
	{
		Path file = voxelFile(worldId, true);
		if(file == null)
			return new XaeroSyncStats(0, 0, 1, 0, List.of());
		List<String> lines = new ArrayList<>();
		try
		{
			if(Files.isRegularFile(file))
				lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
			Set<String> occupied = new HashSet<>();
			for(String line : lines)
				if(line.startsWith("name:") && line.contains(",x:"))
				{
					Map<String, String> values = parseVoxelLine(line);
					occupied.add(voxelKey(values));
				}
			int exported = 0;
			for(Waypoint waypoint : waypoints)
			{
				if(hasNearbyVoxelWaypoint(lines, waypoint))
					continue;
				String key = voxelKey(waypoint);
				if(occupied.add(key))
				{
					lines.add(toVoxelLine(waypoint));
					exported++;
				}
			}
			if(exported > 0)
			{
				Files.createDirectories(file.getParent());
				Files.write(file, lines, StandardCharsets.UTF_8);
			}
			return new XaeroSyncStats(0, 0, 0, exported,
				exported > 0 ? List.of(file) : List.of());
		}catch(IOException e)
		{
			return new XaeroSyncStats(0, 0, 1, 0, List.of(file));
		}
	}
	
	private Path findVoxelFile(String worldId)
	{
		Path direct = voxelFile(worldId, false);
		if(direct != null)
			return direct;
		return null;
	}
	
	private Path voxelFile(String worldId, boolean create)
	{
		Minecraft client = Minecraft.getInstance();
		Path root = client.gameDirectory.toPath().resolve("voxelmap");
		String name =
			worldId == null || worldId.isBlank() ? "default" : worldId;
		if(client.getCurrentServer() != null
			&& client.getCurrentServer().ip != null
			&& !client.getCurrentServer().ip.isBlank())
		{
			name = client.getCurrentServer().ip;
			if(name.endsWith(":25565"))
				name = name.substring(0, name.length() - 6);
		}
		if("singleplayer".equalsIgnoreCase(name)
			&& client.getSingleplayerServer() != null)
			name = client.getSingleplayerServer()
				.getWorldPath(LevelResource.ROOT).getFileName().toString();
		name = voxelFileName(name);
		Path file = root.resolve(name + ".points");
		if(create)
			return file;
		return Files.isRegularFile(file) ? file : null;
	}
	
	private static String voxelFileName(String value)
	{
		return value.replace("<", "~less~").replace(">", "~greater~")
			.replace(":", "~colon~").replace("\"", "~quote~")
			.replace("/", "~slash~").replace("\\", "~backslash~")
			.replace("|", "~pipe~").replace("?", "~question~")
			.replace("*", "~star~");
	}
	
	private static Map<String, String> parseVoxelLine(String line)
	{
		Map<String, String> values = new java.util.HashMap<>();
		for(String pair : line.split(","))
		{
			int colon = pair.indexOf(':');
			if(colon > 0)
				values.put(
					pair.substring(0, colon).trim().toLowerCase(Locale.ROOT),
					descrubVoxel(pair.substring(colon + 1).trim()));
		}
		return values;
	}
	
	private static String descrubVoxel(String value)
	{
		return value == null ? ""
			: value.replace("~comma~", ",").replace("~colon~", ":");
	}
	
	private static WaypointDimension voxelDimension(String dimensions)
	{
		String value =
			dimensions == null ? "" : dimensions.toLowerCase(Locale.ROOT);
		if(value.contains("nether") || value.contains("the_nether")
			|| value.contains("-1"))
			return WaypointDimension.NETHER;
		if(value.contains("end") || value.contains("the_end")
			|| value.contains("%1"))
			return WaypointDimension.END;
		return WaypointDimension.OVERWORLD;
	}
	
	private static int voxelColor(Map<String, String> values)
	{
		int r = (int)(Float.parseFloat(values.getOrDefault("red", "1")) * 255);
		int g =
			(int)(Float.parseFloat(values.getOrDefault("green", "1")) * 255);
		int b = (int)(Float.parseFloat(values.getOrDefault("blue", "1")) * 255);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}
	
	private static String voxelIcon(String suffix)
	{
		return suffix == null || suffix.isBlank() ? "star" : suffix;
	}
	
	private static String voxelKey(Map<String, String> values)
	{
		return values.getOrDefault("x", "0") + ":"
			+ values.getOrDefault("y", "0") + ":"
			+ values.getOrDefault("z", "0") + ":"
			+ voxelDimension(values.get("dimensions"));
	}
	
	private static String voxelKey(Waypoint waypoint)
	{
		BlockPos p = waypoint.getPos();
		return p.getX() + ":" + p.getY() + ":" + p.getZ() + ":"
			+ waypoint.getDimension();
	}
	
	private boolean hasNearbyWaypoint(WaypointDimension dimension, BlockPos pos)
	{
		for(Waypoint waypoint : waypoints)
			if(waypoint.getDimension() == dimension
				&& waypoint.getPos().distSqr(pos) <= DUPLICATE_RADIUS_SQ)
				return true;
		return false;
	}
	
	private static boolean hasNearbyXaeroWaypoint(
		List<XaeroWaypointIO.Entry> entries, BlockPos pos)
	{
		for(XaeroWaypointIO.Entry entry : entries)
			if(entry.pos().distSqr(pos) <= DUPLICATE_RADIUS_SQ)
				return true;
		return false;
	}
	
	private static boolean hasNearbyVoxelWaypoint(List<String> lines,
		Waypoint waypoint)
	{
		BlockPos pos = waypoint.getPos();
		for(String line : lines)
		{
			if(!line.startsWith("name:") || !line.contains(",x:"))
				continue;
			try
			{
				Map<String, String> values = parseVoxelLine(line);
				if(voxelDimension(values.get("dimensions")) != waypoint
					.getDimension())
					continue;
				BlockPos other = new BlockPos(Integer.parseInt(values.get("x")),
					Integer.parseInt(values.getOrDefault("y", "0")),
					Integer.parseInt(values.get("z")));
				if(other.distSqr(pos) <= DUPLICATE_RADIUS_SQ)
					return true;
			}catch(RuntimeException ignored)
			{}
		}
		return false;
	}
	
	private static String toVoxelLine(Waypoint waypoint)
	{
		BlockPos p = waypoint.getPos();
		int color = waypoint.getColor();
		String dimensions = switch(waypoint.getDimension())
		{
			case OVERWORLD -> "overworld";
			case NETHER -> "the_nether";
			case END -> "the_end";
		};
		return "name:" + voxelEscape(waypoint.getName()) + ",x:" + p.getX()
			+ ",z:" + p.getZ() + ",y:" + p.getY() + ",enabled:"
			+ waypoint.isVisible() + ",beacon:" + waypoint.hasBeacon() + ",red:"
			+ ((color >> 16 & 255) / 255F) + ",green:"
			+ ((color >> 8 & 255) / 255F) + ",blue:" + ((color & 255) / 255F)
			+ ",suffix:" + voxelEscape(waypoint.getIcon())
			+ ",world:,dimensions:" + dimensions + "#,coordDimension:"
			+ dimensions;
	}
	
	private static String voxelEscape(String value)
	{
		return (value == null ? "" : value).replace(",", "~comma~").replace(":",
			"~colon~");
	}
	
	private Set<BlockPos> collectPositions(WaypointDimension dimension)
	{
		HashSet<BlockPos> positions = new HashSet<>();
		for(Waypoint waypoint : waypoints)
			if(waypoint.getDimension() == dimension)
				positions.add(waypoint.getPos());
		return positions;
	}
	
	private static boolean isXaeroWaypointName(String name)
	{
		return name.startsWith("mw$") && name.endsWith(".txt");
	}
	
	private Path findXaeroWorldFolder(String worldId)
	{
		Path root = xaeroRoot();
		if(root == null || !Files.isDirectory(root))
			return null;
		List<Path> dirs = listDirectories(root);
		if(dirs.isEmpty())
			return null;
		List<String> candidates = xaeroFolderCandidates(worldId);
		for(String candidate : candidates)
			for(Path dir : dirs)
			{
				if(dir.getFileName().toString().equalsIgnoreCase(candidate))
					return dir;
			}
		String needle = sanitizeWorldId(worldId).toLowerCase(Locale.ROOT);
		for(Path dir : dirs)
		{
			String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
			if(name.contains(needle))
				return dir;
		}
		return null;
	}
	
	private Path ensureXaeroWorldFolder(String worldId)
	{
		Path root = xaeroRoot();
		if(root == null)
			return null;
		try
		{
			Files.createDirectories(root);
		}catch(IOException e)
		{
			return null;
		}
		Path existing = findXaeroWorldFolder(worldId);
		if(existing != null)
			return existing;
		for(String candidate : xaeroFolderCandidates(worldId))
		{
			Path candidatePath = root.resolve(candidate);
			try
			{
				Files.createDirectories(candidatePath);
				return candidatePath;
			}catch(IOException ignored)
			{}
		}
		return null;
	}
	
	private Path xaeroRoot()
	{
		Minecraft client = Minecraft.getInstance();
		if(client == null)
			return null;
		return client.gameDirectory.toPath().resolve("xaero")
			.resolve("minimap");
	}
	
	private static List<Path> listDirectories(Path root)
	{
		List<Path> dirs = new ArrayList<>();
		try(Stream<Path> stream = Files.list(root))
		{
			stream.filter(Files::isDirectory).forEach(dirs::add);
		}catch(IOException ignored)
		{}
		return dirs;
	}
	
	private static List<String> xaeroFolderCandidates(String worldId)
	{
		LinkedHashSet<String> names = new LinkedHashSet<>();
		String sanitized = sanitizeWorldId(worldId);
		if("singleplayer".equalsIgnoreCase(worldId))
		{
			names.add("Singleplayer_" + sanitized);
			return new ArrayList<>(names);
		}
		int idx = sanitized.lastIndexOf('_');
		if(idx > 0)
			names.add("Multiplayer_" + sanitized);
		names.add("Multiplayer_"
			+ (idx > 0 ? sanitized.substring(0, idx) : sanitized));
		return new ArrayList<>(names);
	}
	
	private static String sanitizeWorldId(String worldId)
	{
		if(worldId == null || worldId.isBlank())
			return "default";
		return worldId.replace(':', '_').replace('/', '_').replace("\\", "_");
	}
	
	private static String xaeroDimFolder(WaypointDimension dimension)
	{
		return switch(dimension)
		{
			case OVERWORLD -> "dim%0";
			case NETHER -> "dim%-1";
			case END -> "dim%1";
		};
	}
	
	private static JsonObject toJson(Waypoint w)
	{
		JsonObject o = new JsonObject();
		o.addProperty("uuid", w.getUuid().toString());
		o.addProperty("createdAt", w.getCreatedAt());
		o.addProperty("name", w.getName());
		o.addProperty("icon", w.getIcon());
		o.addProperty("color", w.getColor());
		o.addProperty("visible", w.isVisible());
		o.addProperty("maxVisible", w.getMaxVisible());
		o.addProperty("scale", w.getScale());
		// new: lines toggle
		o.addProperty("lines", w.isLines());
		o.addProperty("beacon", w.hasBeacon());
		o.addProperty("beaconMode", w.getBeaconMode().name());
		BlockPos p = w.getPos();
		JsonObject pos = new JsonObject();
		pos.addProperty("x", p.getX());
		pos.addProperty("y", p.getY());
		pos.addProperty("z", p.getZ());
		o.add("pos", pos);
		o.addProperty("dimension", w.getDimension().name());
		o.addProperty("opposite", w.isOpposite());
		o.addProperty("actionWhenNear", w.getActionWhenNear().name());
		o.addProperty("actionWhenNearDistance", w.getActionWhenNearDistance());
		return o;
	}
	
	private static Waypoint fromJson(JsonObject o)
	{
		Waypoint w = new Waypoint(UUID.fromString(o.get("uuid").getAsString()),
			o.get("createdAt").getAsLong());
		w.setName(optString(o, "name", "Home"));
		w.setIcon(optString(o, "icon", "star"));
		w.setColor(optInt(o, "color", 0xFFFFFFFF));
		w.setVisible(optBool(o, "visible", true));
		w.setMaxVisible(optInt(o, "maxVisible", 5000));
		w.setScale(optDouble(o, "scale", 1.5));
		// new: lines toggle default true
		w.setLines(optBool(o, "lines", false));
		// beacon toggle default false
		Waypoint.BeaconMode beaconMode = Waypoint.BeaconMode.OFF;
		if(o.has("beaconMode"))
		{
			try
			{
				beaconMode = Waypoint.BeaconMode
					.valueOf(optString(o, "beaconMode", "OFF"));
			}catch(Exception ignored)
			{}
		}else if(optBool(o, "beacon", false))
			beaconMode = Waypoint.BeaconMode.ESP;
		w.setBeaconMode(beaconMode);
		JsonObject pos = o.getAsJsonObject("pos");
		if(pos != null)
		{
			w.setPos(new BlockPos(pos.get("x").getAsInt(),
				pos.get("y").getAsInt(), pos.get("z").getAsInt()));
		}
		w.setDimension(WaypointDimension
			.fromString(optString(o, "dimension", "OVERWORLD")));
		w.setOpposite(optBool(o, "opposite", false));
		try
		{
			w.setActionWhenNear(Waypoint.ActionWhenNear
				.valueOf(optString(o, "actionWhenNear", "DISABLED")));
		}catch(Exception ignored)
		{}
		w.setActionWhenNearDistance(optInt(o, "actionWhenNearDistance", 8));
		return w;
	}
	
	private static String optString(JsonObject o, String k, String d)
	{
		return o.has(k) ? o.get(k).getAsString() : d;
	}
	
	private static int optInt(JsonObject o, String k, int d)
	{
		return o.has(k) ? o.get(k).getAsInt() : d;
	}
	
	private static double optDouble(JsonObject o, String k, double d)
	{
		return o.has(k) ? o.get(k).getAsDouble() : d;
	}
	
	private static boolean optBool(JsonObject o, String k, boolean d)
	{
		return o.has(k) ? o.get(k).getAsBoolean() : d;
	}
}
