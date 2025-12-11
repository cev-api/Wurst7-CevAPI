/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.waypoints.Waypoint;
import net.wurstclient.waypoints.WaypointDimension;

public final class WaypointCmd extends Command
{
	private static final Set<String> VALID_ICONS =
		Set.of("square", "circle", "triangle", "star", "diamond", "skull",
			"heart", "check", "x", "arrow_down", "sun", "snowflake");
	
	public WaypointCmd()
	{
		super("waypoint", "Create waypoints via chat.",
			".waypoint add <name> [x=<int>] [y=<int>] [z=<int>] [dim=<overworld|nether|end>]"
				+ " [color=<#RRGGBB|#AARRGGBB>] [icon=<"
				+ String.join("|", VALID_ICONS)
				+ ">] [visible=<true|false>] [lines=<true|false>]"
				+ " [opposite=<true|false>] [beacon=<off|solid|esp>]"
				+ " [action=<disabled|hide|delete>] [actiondist=<int>]"
				+ " [maxvisible=<int>] [scale=<decimal>]");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length == 0)
			throw new CmdSyntaxError("Missing subcommand.");
		
		String sub = args[0].toLowerCase(Locale.ROOT);
		switch(sub)
		{
			case "add":
			handleAdd(Arrays.copyOfRange(args, 1, args.length));
			break;
			
			default:
			throw new CmdSyntaxError("Unknown subcommand: " + sub);
		}
	}
	
	private void handleAdd(String[] args) throws CmdException
	{
		if(args.length == 0)
			throw new CmdSyntaxError("Missing waypoint name.");
		
		Map<String, String> options = new LinkedHashMap<>();
		String name = null;
		for(String arg : args)
		{
			int eq = arg.indexOf('=');
			if(eq < 0)
			{
				if(name != null)
					throw new CmdError("Unexpected argument \"" + arg + "\".");
				name = arg;
				continue;
			}
			
			String key = arg.substring(0, eq).toLowerCase(Locale.ROOT);
			String value = arg.substring(eq + 1);
			options.put(key, value);
		}
		
		if(options.containsKey("name"))
			name = options.remove("name");
		
		if(name == null || name.isEmpty())
			throw new CmdError("Waypoint name is required.");
		name = name.replace('_', ' ');
		
		BlockPos playerPos = MC.player == null ? null
			: BlockPos.containing(MC.player.position());
		Integer px = playerPos == null ? null : playerPos.getX();
		Integer py = playerPos == null ? null : playerPos.getY();
		Integer pz = playerPos == null ? null : playerPos.getZ();
		
		int x = parseInt(options.remove("x"), "x", px);
		int y = parseInt(options.remove("y"), "y", py);
		int z = parseInt(options.remove("z"), "z", pz);
		
		WaypointDimension dim = parseDimension(options.remove("dim"));
		int color = parseColor(options.remove("color"));
		String icon = parseIcon(options.remove("icon"));
		boolean visible =
			parseBoolean(options.remove("visible"), true, "visible");
		boolean lines = parseBoolean(options.remove("lines"), false, "lines");
		boolean opposite =
			parseBoolean(options.remove("opposite"), false, "opposite");
		Waypoint.BeaconMode beacon = parseBeaconMode(options.remove("beacon"));
		Waypoint.ActionWhenNear action = parseAction(options.remove("action"));
		int actionDistance =
			parsePositiveInt(options.remove("actiondist"), "actiondist", 8, 1);
		int maxVisible = parsePositiveInt(options.remove("maxvisible"),
			"maxvisible", 5000, 0);
		double scale = parseScale(options.remove("scale"));
		
		if(!options.isEmpty())
		{
			String unknown = options.keySet().iterator().next();
			throw new CmdError("Unknown option \"" + unknown + "\".");
		}
		
		Waypoint waypoint =
			new Waypoint(UUID.randomUUID(), System.currentTimeMillis());
		waypoint.setName(name);
		waypoint.setPos(new BlockPos(x, y, z));
		waypoint.setDimension(dim);
		waypoint.setColor(color);
		waypoint.setIcon(icon);
		waypoint.setVisible(visible);
		waypoint.setLines(lines);
		waypoint.setOpposite(opposite);
		waypoint.setBeaconMode(beacon);
		waypoint.setActionWhenNear(action);
		waypoint.setActionWhenNearDistance(actionDistance);
		waypoint.setMaxVisible(maxVisible);
		waypoint.setScale(scale);
		
		WURST.getHax().waypointsHack.addWaypointFromCommand(waypoint);
		ChatUtils
			.message(String.format("Added waypoint \"%s\" at %d, %d, %d in %s.",
				name, x, y, z, dim.name()));
	}
	
	private int parseInt(String raw, String key, Integer fallback)
		throws CmdException
	{
		if(raw != null)
			return parseMandatoryInt(raw, key);
		if(fallback != null)
			return fallback;
		throw new CmdError("Missing value for " + key + ".");
	}
	
	private int parseMandatoryInt(String raw, String key) throws CmdException
	{
		try
		{
			return Integer.parseInt(raw);
		}catch(NumberFormatException e)
		{
			throw new CmdError("Invalid integer for " + key + ": " + raw);
		}
	}
	
	private int parsePositiveInt(String raw, String key, int fallback, int min)
		throws CmdException
	{
		if(raw == null)
			return Math.max(min, fallback);
		int value = parseMandatoryInt(raw, key);
		if(value < min)
			throw new CmdError(key + " must be >= " + min + ".");
		return value;
	}
	
	private double parseScale(String raw) throws CmdException
	{
		if(raw == null)
			return 1.5;
		try
		{
			double value = Double.parseDouble(raw);
			return Math.max(0.1, Math.min(10.0, value));
		}catch(NumberFormatException e)
		{
			throw new CmdError("Invalid scale: " + raw);
		}
	}
	
	private boolean parseBoolean(String raw, boolean fallback, String key)
		throws CmdException
	{
		if(raw == null)
			return fallback;
		String value = raw.toLowerCase(Locale.ROOT);
		switch(value)
		{
			case "true":
			case "1":
			case "yes":
			case "on":
			return true;
			
			case "false":
			case "0":
			case "no":
			case "off":
			return false;
			
			default:
			throw new CmdError("Invalid boolean for " + key + ": " + raw);
		}
	}
	
	private Waypoint.BeaconMode parseBeaconMode(String raw) throws CmdException
	{
		if(raw == null)
			return Waypoint.BeaconMode.OFF;
		String value = raw.toLowerCase(Locale.ROOT);
		return switch(value)
		{
			case "off", "none", "false", "disabled" -> Waypoint.BeaconMode.OFF;
			case "solid", "on" -> Waypoint.BeaconMode.SOLID;
			case "esp", "beam", "true" -> Waypoint.BeaconMode.ESP;
			default -> throw new CmdError("Unknown beacon mode: " + raw);
		};
	}
	
	private Waypoint.ActionWhenNear parseAction(String raw) throws CmdException
	{
		if(raw == null || raw.isEmpty())
			return Waypoint.ActionWhenNear.DISABLED;
		String v = raw.toUpperCase(Locale.ROOT);
		try
		{
			return Waypoint.ActionWhenNear.valueOf(v);
		}catch(IllegalArgumentException e)
		{
			throw new CmdError("Unknown action: " + raw);
		}
	}
	
	private String parseIcon(String raw) throws CmdException
	{
		if(raw == null || raw.isEmpty())
			return "star";
		String value = raw.toLowerCase(Locale.ROOT);
		if(!VALID_ICONS.contains(value))
			throw new CmdError("Unknown icon: " + raw);
		return value;
	}
	
	private int parseColor(String raw) throws CmdException
	{
		if(raw == null || raw.isEmpty())
			return 0xFFFFFFFF;
		String value = raw.trim();
		if(value.startsWith("#"))
			value = value.substring(1);
		if(value.startsWith("0x") || value.startsWith("0X"))
			value = value.substring(2);
		if(value.length() != 6 && value.length() != 8)
			throw new CmdError("Color must be RRGGBB or AARRGGBB.");
		try
		{
			int color = (int)Long.parseLong(value, 16);
			if(value.length() == 6)
				color |= 0xFF000000;
			return color;
		}catch(NumberFormatException e)
		{
			throw new CmdError("Invalid color: " + raw);
		}
	}
	
	private WaypointDimension parseDimension(String raw) throws CmdException
	{
		if(raw == null || raw.isEmpty())
			return currentDimension();
		String v = raw.toLowerCase(Locale.ROOT);
		return switch(v)
		{
			case "overworld", "over", "ow" -> WaypointDimension.OVERWORLD;
			case "nether", "hell" -> WaypointDimension.NETHER;
			case "end", "the_end" -> WaypointDimension.END;
			default -> throw new CmdError("Unknown dimension: " + raw);
		};
	}
	
	private WaypointDimension currentDimension()
	{
		if(MC.level == null)
			return WaypointDimension.OVERWORLD;
		String key = MC.level.dimension().identifier().getPath();
		return switch(key)
		{
			case "the_nether" -> WaypointDimension.NETHER;
			case "the_end" -> WaypointDimension.END;
			default -> WaypointDimension.OVERWORLD;
		};
	}
}
