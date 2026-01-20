/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.wurstclient.command.Command;
import net.wurstclient.command.CmdException;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.MeasurementEspHack;
import net.wurstclient.util.ChatUtils;

/**
 * Command-only measurement ESP. Usage: .measurementesp <distance> | off
 */
public final class MeasurementEspCmd extends Command
{
	public MeasurementEspCmd()
	{
		super("measurementesp",
			"Create a hovering ESP box at the block you're pointing at.",
			".measurementesp <distance>", ".measurementesp off");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		MeasurementEspHack hack =
			WurstClient.INSTANCE.getHax().measurementEspHack;
		if(args == null || args.length == 0)
		{
			printHelp();
			return;
		}
		
		String a = args[0].toLowerCase();
		
		if("off".equals(a) || "disable".equals(a))
		{
			if(hack.isEnabled())
			{
				hack.setEnabled(false);
				ChatUtils.message("MeasurementESP disabled.");
			}else
				ChatUtils.message("MeasurementESP is not active.");
			return;
		}
		
		if("mark".equals(a))
		{
			if(!hack.markCurrent())
				ChatUtils.message("No target to mark.");
			else
			{
				ChatUtils.message(
					"Marked position: " + hack.getLastPos().toShortString());
			}
			return;
		}
		
		if("clear".equals(a))
		{
			hack.clearMarks();
			ChatUtils.message("Cleared MeasurementESP marks.");
			return;
		}
		
		// distance
		try
		{
			int d = Integer.parseInt(a);
			if(d <= 0)
				throw new NumberFormatException();
			hack.setDistance(d);
		}catch(NumberFormatException e)
		{
			throw new net.wurstclient.command.CmdSyntaxError(
				"Invalid distance: " + a);
		}
		
		if(!hack.isEnabled())
			hack.setEnabled(true);
		
		ChatUtils.message(
			"MeasurementESP active: " + hack.getDistance() + " blocks.");
	}
}
