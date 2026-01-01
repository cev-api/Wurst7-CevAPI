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
import net.wurstclient.hacks.PanicHack;

public final class PanicCmd extends Command
{
	public PanicCmd()
	{
		super("panic",
			"Quickly disables every enabled hack and allows restoring them.",
			".panic", ".panic restore");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length > 1)
			throw new CmdSyntaxError();
		
		PanicHack panic = WURST.getHax().panicHack;
		
		if(args.length == 0)
		{
			panic.setEnabled(true);
			return;
		}
		
		if("restore".equalsIgnoreCase(args[0]))
		{
			panic.restoreSavedHacks();
			return;
		}
		
		throw new CmdSyntaxError();
	}
}
