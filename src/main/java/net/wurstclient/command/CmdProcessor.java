/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.command;

import java.util.Arrays;
import java.util.Locale;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.wurstclient.WurstClient;
import net.wurstclient.events.ChatOutputListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.TooManyHaxHack;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.util.ChatUtils;

public final class CmdProcessor implements ChatOutputListener
{
	private final CmdList cmds;
	
	public CmdProcessor(CmdList cmds)
	{
		this.cmds = cmds;
	}
	
	@Override
	public void onSentMessage(ChatOutputEvent event)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		String message = event.getOriginalMessage().trim();
		String prefix = getPrefix();
		
		if(!message.startsWith(prefix))
			return;
		
		event.cancel();
		process(message.substring(prefix.length()));
	}
	
	public void process(String input)
	{
		if(runHackShortcut(input))
			return;
		
		if(runOtherFeatureShortcut(input))
			return;
		
		try
		{
			Command cmd = parseCmd(input);
			
			TooManyHaxHack tooManyHax =
				WurstClient.INSTANCE.getHax().tooManyHaxHack;
			if(tooManyHax.shouldBlockStarting(cmd))
			{
				ChatUtils.error(cmd.getName() + " is blocked by TooManyHax.");
				return;
			}
			
			runCmd(cmd, input);
			
		}catch(CmdNotFoundException e)
		{
			e.printToChat();
		}
	}
	
	private Command parseCmd(String input) throws CmdNotFoundException
	{
		String cmdName = input.split(" ")[0];
		Command cmd = cmds.getCmdByName(cmdName);
		
		if(cmd == null)
			throw new CmdNotFoundException(input);
		
		return cmd;
	}
	
	private void runCmd(Command cmd, String input)
	{
		String[] args = input.split(" ");
		args = Arrays.copyOfRange(args, 1, args.length);
		
		try
		{
			cmd.call(args);
			
		}catch(CmdException e)
		{
			e.printToChat(cmd);
			
		}catch(Throwable e)
		{
			CrashReport report =
				CrashReport.forThrowable(e, "Running Wurst command");
			CrashReportCategory section =
				report.addCategory("Affected command");
			section.setDetail("Command input", () -> input);
			throw new ReportedException(report);
		}
	}
	
	private boolean runHackShortcut(String input)
	{
		String[] args = input.trim().split("\\s+");
		if(args.length == 0 || args[0].isEmpty())
			return false;
			
		// If a real command shares this name, let the command parser handle it.
		// This keeps legacy commands like .autofly working even when the hack
		// name is also runnable as a shortcut.
		if(cmds.getCmdByName(args[0]) != null)
			return false;
		
		Hack hack = WurstClient.INSTANCE.getHax().getHackByName(args[0]);
		if(hack == null)
			return false;
		
		if(args.length == 1)
		{
			setHackEnabled(hack, !hack.isEnabled());
			return true;
		}
		
		if(args.length == 2)
		{
			switch(args[1].toLowerCase(Locale.ROOT))
			{
				case "on":
				setHackEnabled(hack, true);
				return true;
				
				case "off":
				setHackEnabled(hack, false);
				return true;
				
				case "toggle":
				setHackEnabled(hack, !hack.isEnabled());
				return true;
				
				default:
				return false;
			}
		}
		
		ChatUtils
			.error("Syntax: " + getPrefix() + args[0] + " [on|off|toggle]");
		return true;
	}
	
	private boolean runOtherFeatureShortcut(String input)
	{
		String[] args = input.trim().split("\\s+");
		if(args.length != 1 || args[0].isEmpty())
			return false;
		
		// If a real command shares this name, let the command parser handle it.
		if(cmds.getCmdByName(args[0]) != null)
			return false;
		
		OtherFeature otf = WurstClient.INSTANCE.getOtfs().getOtfByName(args[0]);
		if(otf == null)
			return false;
		
		otf.doPrimaryAction();
		return true;
	}
	
	private void setHackEnabled(Hack hack, boolean enabled)
	{
		TooManyHaxHack tooManyHax =
			WurstClient.INSTANCE.getHax().tooManyHaxHack;
		if(enabled && tooManyHax.shouldBlockStarting(hack))
		{
			ChatUtils.error(hack.getName() + " is blocked by TooManyHax.");
			return;
		}
		
		hack.setEnabled(enabled);
	}
	
	private static String getPrefix()
	{
		String prefix = ".";
		try
		{
			prefix = WurstClient.INSTANCE.getOtfs().commandPrefixOtf
				.getPrefixSetting().getSelected().toString();
		}catch(Throwable ignored)
		{}
		
		return prefix;
	}
	
	private static class CmdNotFoundException extends Exception
	{
		private final String input;
		
		public CmdNotFoundException(String input)
		{
			this.input = input;
		}
		
		public void printToChat()
		{
			String cmdName = input.split(" ")[0];
			String prefix = getPrefix();
			
			ChatUtils.error("Unknown command: " + prefix + cmdName);
			
			StringBuilder helpMsg = new StringBuilder();
			
			if(input.startsWith("/"))
			{
				helpMsg.append("Use \"" + prefix + "say " + input + "\"");
				helpMsg.append(" to send it as a chat command.");
				
			}else
			{
				helpMsg.append(
					"Type \"" + prefix + "help\" for a list of commands or ");
				helpMsg.append("\"" + prefix + "say " + prefix + input + "\"");
				helpMsg.append(" to send it as a chat message.");
			}
			
			ChatUtils.message(helpMsg.toString());
		}
	}
}
