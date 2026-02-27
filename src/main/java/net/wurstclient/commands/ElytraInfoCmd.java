/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.wurstclient.WurstClient;
import net.wurstclient.command.Command;
import net.wurstclient.command.CmdError;

public final class ElytraInfoCmd extends Command
{
	public ElytraInfoCmd()
	{
		super("elytrainfo", "ElytraInfo utility commands.", ".elytrainfo swap");
	}
	
	@Override
	public void call(String[] args) throws CmdError
	{
		if(args.length == 0 || !args[0].equalsIgnoreCase("swap"))
			throw new CmdError("Usage: .elytrainfo swap");
		
		WurstClient.INSTANCE.getHax().elytraInfoHack.swapChestItemFromKeybind();
	}
}
