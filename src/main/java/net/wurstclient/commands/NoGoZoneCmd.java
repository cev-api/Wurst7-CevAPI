/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.wurstclient.DontBlock;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.hacks.NoGoZoneHack;
import net.wurstclient.hacks.NoGoZoneHack.NoGoZone;
import net.wurstclient.util.ChatUtils;

@DontBlock
public final class NoGoZoneCmd extends Command
{
	public NoGoZoneCmd()
	{
		super("nogozone",
			"Manages NoGoZones - areas you cannot re-enter after leaving.\n"
				+ "Use when NoGoZone hack is enabled.",
			".nogozone add [x z [radius]]", ".nogozone list",
			".nogozone remove <id>", ".nogozone clear");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length < 1)
			throw new CmdSyntaxError();
		
		switch(args[0].toLowerCase())
		{
			case "add":
			addZone(args);
			break;
			
			case "list":
			listZones();
			break;
			
			case "remove":
			if(args.length < 2)
				throw new CmdSyntaxError("No zone ID specified.");
			removeZone(args[1]);
			break;
			
			case "clear":
			clearZones();
			break;
			
			default:
			throw new CmdSyntaxError("Unknown subcommand: " + args[0]);
		}
	}
	
	private void addZone(String[] args) throws CmdError
	{
		if(MC.player == null)
		{
			ChatUtils.error("You must be in a world to add a NoGoZone.");
			return;
		}
		if(args.length != 1 && args.length != 3 && args.length != 4)
			throw new CmdError("Usage: .nogozone add [x z [radius]]");
		
		// Read the effective render distance (capped by server view distance)
		int renderDistance = MC.options.getEffectiveRenderDistance();
		// Add +3 as specified
		int blockRadius = (renderDistance + 3) * 16;
		
		BlockPos zonePos = args.length == 3
			? parseXZ(args[1], args[2], MC.player.blockPosition().getY())
			: MC.player.blockPosition();
		if(args.length == 4)
		{
			zonePos =
				parseXZ(args[1], args[2], MC.player.blockPosition().getY());
			blockRadius = parseRadius(args[3]);
		}
		
		int id = NoGoZoneHack.addZone(zonePos, blockRadius);
		WURST.getHax().noGoZoneHack.setEnabled(true);
		
		ChatUtils.message("NoGoZone #" + id + " created at " + zonePos.getX()
			+ " " + zonePos.getY() + " " + zonePos.getZ() + " with radius "
			+ blockRadius + " blocks.");
	}
	
	private BlockPos parseXZ(String xStr, String zStr, int y) throws CmdError
	{
		try
		{
			return new BlockPos(Integer.parseInt(xStr), y,
				Integer.parseInt(zStr));
			
		}catch(NumberFormatException e)
		{
			throw new CmdError("Invalid coordinates: " + xStr + " " + zStr);
		}
	}
	
	private int parseRadius(String radiusStr) throws CmdError
	{
		try
		{
			int radius = Integer.parseInt(radiusStr);
			if(radius < 1)
				throw new CmdError("Radius must be at least 1 block.");
			return radius;
			
		}catch(NumberFormatException e)
		{
			throw new CmdError("Invalid radius: " + radiusStr);
		}
	}
	
	private void listZones()
	{
		List<NoGoZone> zones = NoGoZoneHack.getZones();
		
		if(zones.isEmpty())
		{
			ChatUtils.message("No NoGoZones active.");
			return;
		}
		
		ChatUtils.message("=== NoGoZones (" + zones.size() + " total) ===");
		for(NoGoZone zone : zones)
		{
			ChatUtils.message("  #" + zone.id + " | XYZ: " + zone.center.getX()
				+ " " + zone.center.getY() + " " + zone.center.getZ()
				+ " | Radius: " + zone.getBlockRadius() + " blocks");
		}
	}
	
	private void removeZone(String idStr) throws CmdError
	{
		int id;
		try
		{
			id = Integer.parseInt(idStr);
		}catch(NumberFormatException e)
		{
			throw new CmdError("Invalid zone ID: " + idStr);
		}
		
		boolean removed = NoGoZoneHack.removeZone(id);
		if(removed)
			ChatUtils.message("NoGoZone #" + id + " removed.");
		else
			throw new CmdError("NoGoZone #" + id + " not found.");
	}
	
	private void clearZones()
	{
		int count = NoGoZoneHack.getZones().size();
		NoGoZoneHack.clearAllZones();
		ChatUtils.message("Cleared " + count + " NoGoZone(s).");
	}
}
