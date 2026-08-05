/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import net.wurstclient.WurstClient;
import net.wurstclient.altbot.AltBotManager;
import net.wurstclient.altbot.AltBotState;
import net.wurstclient.altmanager.TokenAlt;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.util.ChatUtils;

public final class AltBotCmd extends Command
{
	public AltBotCmd()
	{
		super("altbot", "Manages protocol-bot connections for your saved alts.",
			".altbot connect <alt>", ".altbot disconnect <alt>", ".altbot list",
			".altbot say <alt> <message>", ".altbot command <alt> <command>");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length < 1)
			throw new CmdSyntaxError();
		
		switch(args[0].toLowerCase())
		{
			case "connect":
			connect(args);
			break;
			
			case "disconnect":
			disconnect(args);
			break;
			
			case "list":
			list();
			break;
			
			case "say":
			say(args);
			break;
			
			case "command":
			command(args);
			break;
			
			default:
			throw new CmdSyntaxError();
		}
	}
	
	private void connect(String[] args) throws CmdException
	{
		if(args.length != 2)
			throw new CmdSyntaxError();
		
		TokenAlt alt = resolveAlt(args[1]);
		if(alt == null)
			throw new CmdError("Unknown or unsupported alt: " + args[1]);
		
		AltBotManager manager = WurstClient.INSTANCE.getAltBotManager();
		if(manager.isActiveClientAlt(alt))
			throw new CmdError("\"" + alt.getDisplayName()
				+ "\" is the rendered client and cannot be connected as a bot.");
		
		manager.connectBotToCurrentServer(alt);
	}
	
	private void disconnect(String[] args) throws CmdException
	{
		if(args.length != 2)
			throw new CmdSyntaxError();
		
		TokenAlt alt = resolveAlt(args[1]);
		if(alt == null)
			throw new CmdError("Unknown or unsupported alt: " + args[1]);
		
		WurstClient.INSTANCE.getAltBotManager().disconnectBot(alt);
	}
	
	private void list()
	{
		AltBotManager manager = WurstClient.INSTANCE.getAltBotManager();
		ChatUtils.message("AltBots:");
		for(AltBotState state : manager.getAllStates())
			ChatUtils
				.message(state.getDisplayName() + ": " + statusText(state));
	}
	
	private static String statusText(AltBotState state)
	{
		return switch(state.getState())
		{
			case DISCONNECTED -> "Offline";
			case AUTHENTICATING -> "Authenticating";
			case CONNECTING -> "Connecting";
			case LOGIN -> "Login";
			case CONFIGURING -> "Configuring";
			case PLAY -> "Connected to " + state.getServer();
			case DISCONNECTING -> "Disconnecting";
			case FAILED -> "Failed: " + state.getLastError();
		};
	}
	
	private void say(String[] args) throws CmdException
	{
		if(args.length < 3)
			throw new CmdSyntaxError();
		
		TokenAlt alt = resolveAlt(args[1]);
		if(alt == null)
			throw new CmdError("Unknown or unsupported alt: " + args[1]);
		
		String message = joinArgs(args, 2);
		sendAsBot(alt, message);
	}
	
	private void command(String[] args) throws CmdException
	{
		if(args.length < 3)
			throw new CmdSyntaxError();
		
		TokenAlt alt = resolveAlt(args[1]);
		if(alt == null)
			throw new CmdError("Unknown or unsupported alt: " + args[1]);
		
		String command = joinArgs(args, 2);
		sendAsBot(alt, command);
	}
	
	private void sendAsBot(TokenAlt alt, String text) throws CmdException
	{
		AltBotManager manager = WurstClient.INSTANCE.getAltBotManager();
		if(!manager.isBotReady(alt))
			throw new CmdError("Bot \"" + alt.getDisplayName()
				+ "\" is not in the play state yet.");
		
		boolean sent = manager.sendChat(alt, text);
		if(!sent)
			throw new CmdError(
				"Failed to send through bot \"" + alt.getDisplayName() + "\".");
	}
	
	private TokenAlt resolveAlt(String name)
	{
		return WurstClient.INSTANCE.getAltBotManager().findAltByName(name);
	}
	
	private static String joinArgs(String[] args, int start)
	{
		StringBuilder sb = new StringBuilder();
		for(int i = start; i < args.length; i++)
		{
			if(i > start)
				sb.append(' ');
			sb.append(args[i]);
		}
		return sb.toString();
	}
}
