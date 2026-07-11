/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.wurstclient.Category;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.hacks.PlayerMuteHack;
import net.wurstclient.util.ChatUtils;

public final class PlayerMuteCmd extends Command
{
	public PlayerMuteCmd()
	{
		super("mute", "Mutes or unmutes a player's chat messages.",
			".mute <player>", ".mute unmute <player>", ".mute list");
		setCategory(Category.CHAT);
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length < 1 || args.length > 2)
			throw new CmdSyntaxError();
		
		PlayerMuteHack hack = WURST.getHax().playerMuteHack;
		if(!hack.isEnabled())
			hack.setEnabled(true);
		
		if(args[0].equalsIgnoreCase("list"))
		{
			if(args.length != 1)
				throw new CmdSyntaxError();
			ChatUtils.message(
				"Muted players: " + String.join(", ", hack.getMutedNames()));
			return;
		}
		
		boolean unmute = args[0].equalsIgnoreCase("unmute");
		String name = unmute ? args.length == 2 ? args[1] : null : args[0];
		if(name == null)
			throw new CmdSyntaxError();
		
		var info = hack.findOnlinePlayer(name);
		if(info == null)
		{
			if(unmute)
			{
				hack.unmuteName(name);
				ChatUtils.message("Unmuted " + name + ".");
			}else
			{
				hack.muteName(name);
				ChatUtils.message("Muted " + name + ".");
			}
			return;
		}
		
		if(unmute)
		{
			hack.unmute(info);
			ChatUtils.message("Unmuted " + info.getProfile().name() + ".");
		}else
			ChatUtils.message(hack.toggle(info));
	}
}
