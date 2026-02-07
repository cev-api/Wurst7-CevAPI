/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.minecraft.core.BlockPos;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.util.MathUtils;

public final class AutoFlyCmd extends Command
{
	public AutoFlyCmd()
	{
		super("autofly", "Fly to a waypoint using AutoFly.",
			".autofly <x> <y> <z> [height] [speed]",
			".autofly <x> <z> [height] [speed]");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(MC.player == null)
			throw new CmdError("Join a world before using .autofly.");
		String[] normalized = normalizeArgs(args);
		if(normalized.length == 1)
		{
			String cmd = normalized[0];
			if(cmd.equalsIgnoreCase("next"))
			{
				ensureAutoFlyEnabled();
				WURST.getHax().autoFlyHack.cycleNextWaypoint();
				return;
			}
			
			if(cmd.equalsIgnoreCase("previous") || cmd.equalsIgnoreCase("prev"))
			{
				ensureAutoFlyEnabled();
				WURST.getHax().autoFlyHack.cyclePreviousWaypoint();
				return;
			}
			
			if(cmd.equalsIgnoreCase("stop") || cmd.equalsIgnoreCase("off")
				|| cmd.equalsIgnoreCase("disable"))
			{
				if(WURST.getHax().autoFlyHack.isEnabled())
					WURST.getHax().autoFlyHack.setEnabled(false);
				return;
			}
		}
		
		if(normalized.length < 2)
			throw new CmdSyntaxError("Invalid coordinates.");
		
		boolean hasY = normalized.length == 3 || normalized.length == 4
			|| normalized.length == 5;
		int idxHeight = hasY ? 3 : 2;
		
		if(normalized.length > idxHeight + 2)
			throw new CmdSyntaxError("Too many arguments.");
		
		BlockPos pos = hasY ? parseXyz(normalized) : parseXz(normalized);
		
		Double height = null;
		Double speed = null;
		if(normalized.length > idxHeight)
			height = parseDouble(normalized[idxHeight], "height");
		if(normalized.length > idxHeight + 1)
			speed = parseDouble(normalized[idxHeight + 1], "speed");
		
		WURST.getHax().autoFlyHack.setTargetFromCommand(pos, hasY, height,
			speed);
	}
	
	private BlockPos parseXyz(String[] args) throws CmdSyntaxError
	{
		int x = parseCoord(args[0], MC.player.blockPosition().getX());
		int y = parseCoord(args[1], MC.player.blockPosition().getY());
		int z = parseCoord(args[2], MC.player.blockPosition().getZ());
		return new BlockPos(x, y, z);
	}
	
	private BlockPos parseXz(String[] args) throws CmdSyntaxError
	{
		int x = parseCoord(args[0], MC.player.blockPosition().getX());
		int z = parseCoord(args[1], MC.player.blockPosition().getZ());
		int y = MC.player.blockPosition().getY();
		return new BlockPos(x, y, z);
	}
	
	private int parseCoord(String raw, int base) throws CmdSyntaxError
	{
		if(MathUtils.isInteger(raw))
			return Integer.parseInt(raw);
		if(raw.equals("~"))
			return base;
		if(raw.startsWith("~") && MathUtils.isInteger(raw.substring(1)))
			return base + Integer.parseInt(raw.substring(1));
		throw new CmdSyntaxError("Invalid coordinates.");
	}
	
	private Double parseDouble(String raw, String label) throws CmdSyntaxError
	{
		if(!MathUtils.isDouble(raw))
			throw new CmdSyntaxError("Invalid " + label + ".");
		return Double.parseDouble(raw);
	}
	
	private String[] normalizeArgs(String[] args)
	{
		if(args == null || args.length == 0)
			return new String[0];
		java.util.List<String> out = new java.util.ArrayList<>();
		for(String arg : args)
		{
			if(arg == null)
				continue;
			String[] parts = arg.split(",");
			for(String p : parts)
			{
				String trimmed = p.trim();
				if(!trimmed.isEmpty())
					out.add(trimmed);
			}
		}
		return out.toArray(new String[0]);
	}
	
	private void ensureAutoFlyEnabled() throws CmdError
	{
		if(!WURST.getHax().autoFlyHack.isEnabled())
			throw new CmdError("AutoFly must be enabled to cycle waypoints.");
	}
}
