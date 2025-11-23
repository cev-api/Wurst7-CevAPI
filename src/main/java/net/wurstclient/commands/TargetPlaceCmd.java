/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.wurstclient.Category;
import net.wurstclient.WurstClient;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.hacks.TargetPlaceHack;
import net.wurstclient.util.ChatUtils;

public final class TargetPlaceCmd extends Command
{
	public TargetPlaceCmd()
	{
		super("targetplace", "Selects a block for TargetPlace or unselects it.",
			".targetplace");
		setCategory(Category.BLOCKS);
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length > 0)
			throw new CmdSyntaxError();
		
		TargetPlaceHack hack = WurstClient.INSTANCE.getHax().targetPlaceHack;
		if(!hack.isEnabled())
		{
			ChatUtils.error("TargetPlace is disabled.");
			return;
		}
		
		if(!hack.handleActivation())
			ChatUtils.error("TargetPlace did nothing.");
	}
}
