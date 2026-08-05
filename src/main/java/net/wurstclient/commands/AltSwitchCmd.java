/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.List;

import net.wurstclient.WurstClient;
import net.wurstclient.altbot.AccountSwitchController;
import net.wurstclient.altbot.SwitchState;
import net.wurstclient.altmanager.TokenAlt;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.util.ChatUtils;

public final class AltSwitchCmd extends Command
{
	public AltSwitchCmd()
	{
		super("altswitch",
			"Switches the rendered Minecraft client to another saved account,"
				+ " parking the previous account as a protocol bot.",
			".altswitch <alt>", ".altswitch status", ".altswitch cancel");
	}
	
	@Override
	public List<String> getArgumentSuggestions(String[] args, int argIndex,
		String prefix)
	{
		// The first argument can be either an alt name (handled separately)
		// or one of these keywords.
		return List.of("status", "cancel");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length < 1)
			throw new CmdSyntaxError();
		
		switch(args[0].toLowerCase())
		{
			case "status":
			status();
			break;
			
			case "cancel":
			cancel();
			break;
			
			default:
			switchTo(args);
			break;
		}
	}
	
	private void switchTo(String[] args) throws CmdException
	{
		if(args.length != 1)
			throw new CmdSyntaxError();
		
		TokenAlt alt =
			WurstClient.INSTANCE.getAltBotManager().findAltByName(args[0]);
		if(alt == null)
			throw new CmdError("Unknown or unsupported alt: " + args[0]);
		
		AccountSwitchController controller =
			WurstClient.INSTANCE.getAltSwitchController();
		boolean accepted = controller.startSwitch(alt);
		if(accepted)
			ChatUtils.message("Switching to " + alt.getDisplayName() + "...");
	}
	
	private void status()
	{
		AccountSwitchController controller =
			WurstClient.INSTANCE.getAltSwitchController();
		SwitchState state = controller.getState();
		ChatUtils.message("Account switch: " + controller.getStatus());
		if(state == SwitchState.FAILED)
			ChatUtils.message("Last switch failed.");
	}
	
	private void cancel()
	{
		WurstClient.INSTANCE.getAltSwitchController().cancelSwitch();
	}
}
