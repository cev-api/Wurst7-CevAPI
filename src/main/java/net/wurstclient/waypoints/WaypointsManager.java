/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
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
import java.util.List;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.util.math.BlockPos;

public final class WaypointsManager
{
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	
	private final Path folder;
	private final ArrayList<Waypoint> waypoints = new ArrayList<>();
	
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
		w.setLines(optBool(o, "lines", true));
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
