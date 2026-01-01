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
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.hacks.SeedMapperHelperHack;

public final class SeedMapperHelperCmd extends Command
{
	public SeedMapperHelperCmd()
	{
		super("seedmapperhelper",
			"Quick shortcuts for SeedMapperHelper actions.",
			".seedmapperhelper map", ".seedmapperhelper highlight",
			".seedmapperhelper clearhighlight");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length < 1)
			throw new CmdSyntaxError();
		
		SeedMapperHelperHack hack =
			WurstClient.INSTANCE.getHax().seedMapperHelperHack;
		if(hack == null)
			throw new CmdSyntaxError();
		
		String action = args[0].toLowerCase(Locale.ROOT);
		switch(action)
		{
			case "map":
			case "seedmap":
			case "open":
			hack.openSeedMapUi();
			break;
			
			case "highlight":
			hack.runConfiguredHighlight();
			break;
			
			case "clearhighlight":
			case "highlightclear":
			case "clearhl":
			case "clear":
			hack.clearHighlight();
			break;
			
			default:
			throw new CmdSyntaxError();
		}
	}
}
