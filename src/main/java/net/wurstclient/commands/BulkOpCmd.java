/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.hacks.OfflineSettingsHack;

public final class BulkOpCmd extends Command
{
	public BulkOpCmd()
	{
		super("bulkop",
			"Bulk-ops players with a prefix. Example: .bulkop frog 120",
			".bulkop <prefix> <count>");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length < 2)
			throw new CmdSyntaxError();
		
		String prefix = args[0];
		int count;
		
		try
		{
			count = Integer.parseInt(args[1]);
		}catch(NumberFormatException e)
		{
			throw new CmdSyntaxError("Count must be a number.");
		}
		
		if(count < 1 || count > 1000)
			throw new CmdError("Count must be between 1 and 1000.");
		
		if(!prefix.matches("[A-Za-z0-9_]+"))
			throw new CmdError(
				"Prefix must only contain letters, numbers, and underscores.");
		
		if(prefix.length() > 14)
			throw new CmdError(
				"Prefix too long (max 14 chars, leaving room for numbers).");
		
		OfflineSettingsHack hack = WURST.getHax().offlineSettingsHack;
		hack.startBulkOp(prefix, count);
	}
	
	@Override
	public String getPrimaryAction()
	{
		return "Bulk Op";
	}
	
	@Override
	public void doPrimaryAction()
	{
		WURST.getCmdProcessor().process("bulkop");
	}
}
