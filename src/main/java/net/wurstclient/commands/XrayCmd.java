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
import net.wurstclient.hacks.XRayHack;

public final class XrayCmd extends Command
{
	public XrayCmd()
	{
		super("xray", "Controls X-Ray list operations and query mode.",
			".xray <query>", ".xray query <query>", ".xray [on|off]",
			".xray add <block>", ".xray remove <block>", ".xray list [<page>]",
			".xray reset", "Example: .xray add gravel");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		XRayHack xray = WURST.getHax().xRayHack;
		if(args.length == 0)
		{
			xray.setEnabled(!xray.isEnabled());
			return;
		}
		
		String action = args[0].toLowerCase();
		switch(action)
		{
			case "on":
			xray.setEnabled(true);
			return;
			
			case "off":
			xray.setEnabled(false);
			return;
			
			case "query":
			if(args.length < 2)
				throw new CmdSyntaxError();
			
			xray.enableQuerySearch(joinArgs(args, 1));
			return;
			
			case "add":
			case "remove":
			case "list":
			case "reset":
			WURST.getCmdProcessor()
				.process("blocklist X-Ray Ores " + String.join(" ", args));
			return;
			
			default:
			xray.enableQuerySearch(joinArgs(args, 0));
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
