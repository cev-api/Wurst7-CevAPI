/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.hacks.SearchHack;

public final class SearchCmd extends Command
{
	public SearchCmd()
	{
		super("search",
			"Sets Search to query mode and enables/disables it from chat.",
			".search <query>", ".search query <query>", ".search [on|off]");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		SearchHack search = WURST.getHax().searchHack;
		
		if(args.length == 0)
		{
			search.setEnabled(!search.isEnabled());
			return;
		}
		
		String action = args[0].toLowerCase();
		switch(action)
		{
			case "on":
			search.setEnabled(true);
			return;
			
			case "off":
			search.setEnabled(false);
			return;
			
			case "query":
			if(args.length < 2)
				throw new CmdSyntaxError();
			
			search.enableQuerySearch(joinArgs(args, 1));
			return;
			
			default:
			search.enableQuerySearch(joinArgs(args, 0));
			return;
		}
	}
	
	private static String joinArgs(String[] args, int start)
	{
		StringBuilder out = new StringBuilder();
		for(int i = start; i < args.length; i++)
		{
			if(i > start)
				out.append(' ');
			
			out.append(args[i]);
		}
		
		return out.toString().trim();
	}
}
