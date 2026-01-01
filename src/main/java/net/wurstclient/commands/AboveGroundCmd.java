/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.wurstclient.WurstClient;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.MathUtils;

public final class AboveGroundCmd extends Command
{
	public AboveGroundCmd()
	{
		super("aboveground",
			"Global above-ground ESP filter for supported hacks.",
			".aboveground (on|off|toggle)", ".aboveground y <value>",
			".aboveground <value>");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length == 0)
			throw new CmdSyntaxError();
		String a = args[0].toLowerCase();
		// Single-word actions
		if(args.length == 1)
		{
			if(a.equals("on"))
			{
				WurstClient.INSTANCE.getHax().setAboveGroundFilterEnabled(true);
				ChatUtils.message(
					"Above-ground filter enabled for supported hacks.");
				return;
			}
			if(a.equals("off"))
			{
				WurstClient.INSTANCE.getHax()
					.setAboveGroundFilterEnabled(false);
				ChatUtils.message(
					"Above-ground filter disabled for supported hacks.");
				return;
			}
			if(a.equals("toggle"))
			{
				// Toggle: invert by enabling when currently all disabled is
				// unknown.
				// We'll simply enable if arg toggled on; user can set
				// explicitly.
				// For simplicity, toggle will enable when called.
				WurstClient.INSTANCE.getHax().setAboveGroundFilterEnabled(true);
				ChatUtils.message("Above-ground filter toggled (enabled).");
				return;
			}
			// numeric provided -> set Y
			if(MathUtils.isDouble(a))
			{
				int y = (int)Double.parseDouble(a);
				WurstClient.INSTANCE.getHax().setAboveGroundFilterY(y);
				ChatUtils.message(
					"Above-ground Y set to " + y + " for supported hacks.");
				return;
			}
			throw new CmdSyntaxError();
		}
		
		// Two-argument forms: "y <value>" or "on/off <value>" to enable and set
		// Y
		if(args.length == 2)
		{
			if(a.equals("y") || a.equals("set"))
			{
				if(!MathUtils.isDouble(args[1]))
					throw new CmdSyntaxError("Y must be a number.");
				int y = (int)Double.parseDouble(args[1]);
				WurstClient.INSTANCE.getHax().setAboveGroundFilterY(y);
				ChatUtils.message(
					"Above-ground Y set to " + y + " for supported hacks.");
				return;
			}
			if(a.equals("on") || a.equals("off"))
			{
				boolean enabled = a.equals("on");
				WurstClient.INSTANCE.getHax()
					.setAboveGroundFilterEnabled(enabled);
				if(!MathUtils.isDouble(args[1]))
				{
					ChatUtils.message("Above-ground filter "
						+ (enabled ? "enabled" : "disabled") + ".");
					return;
				}
				int y = (int)Double.parseDouble(args[1]);
				WurstClient.INSTANCE.getHax().setAboveGroundFilterY(y);
				ChatUtils.message(
					"Above-ground filter " + (enabled ? "enabled" : "disabled")
						+ " and Y set to " + y + ".");
				return;
			}
			throw new CmdSyntaxError();
		}
		throw new CmdSyntaxError();
	}
}
