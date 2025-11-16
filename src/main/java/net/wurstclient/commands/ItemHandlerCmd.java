/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.wurstclient.command.Command;
import net.wurstclient.command.CmdException;
import net.wurstclient.WurstClient;

public final class ItemHandlerCmd extends Command
{
	public ItemHandlerCmd()
	{
		super("itemhandler", "Opens the item handler UI.",
			".itemhandler [gui]");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		// Support optional "gui" argument for clarity
		WurstClient.INSTANCE.getHax().itemHandlerHack.openScreen();
	}
}
