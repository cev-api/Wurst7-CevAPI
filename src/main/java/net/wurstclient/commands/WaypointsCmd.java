/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.wurstclient.command.CmdException;
import net.wurstclient.command.Command;

public final class WaypointsCmd extends Command
{
	public WaypointsCmd()
	{
		super("waypoints", "Open the Waypoints manager screen.", ".waypoints");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		WURST.getHax().waypointsHack.openManager();
	}
}
