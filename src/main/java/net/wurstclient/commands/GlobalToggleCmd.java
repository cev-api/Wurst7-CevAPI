/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.Locale;
import net.wurstclient.WurstClient;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.Command;
import net.wurstclient.util.ChatUtils;

public final class GlobalToggleCmd extends Command
{
	public GlobalToggleCmd()
	{
		super("globaltoggle", "GlobalToggle utility commands.",
			".globaltoggle tracers [on|off|toggle]");
	}
	
	@Override
	public void call(String[] args) throws CmdError
	{
		if(args.length == 0 || !args[0].equalsIgnoreCase("tracers"))
			throw new CmdError("Usage: .globaltoggle tracers [on|off|toggle]");
		
		var globalToggle = WurstClient.INSTANCE.getHax().globalToggleHack;
		if(args.length == 1 || args[1].equalsIgnoreCase("toggle"))
		{
			globalToggle.toggleAllTracers();
		}else
		{
			String mode = args[1].toLowerCase(Locale.ROOT);
			switch(mode)
			{
				case "on":
				globalToggle.setAllTracersDisabled(true);
				break;
				
				case "off":
				globalToggle.setAllTracersDisabled(false);
				break;
				
				default:
				throw new CmdError(
					"Usage: .globaltoggle tracers [on|off|toggle]");
			}
		}
		
		ChatUtils.message(globalToggle.areAllTracersDisabled()
			? "All tracers are now disabled." : "All tracers are now enabled.");
	}
}
