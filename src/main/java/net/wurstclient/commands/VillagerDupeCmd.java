/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.wurstclient.command.Command;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.CmdError;
import net.wurstclient.hacks.VillagerDupeHack;
import net.wurstclient.hacks.villagerdupe.VillagerDupeEngine.DupeFilter;
import net.wurstclient.hacks.villagerdupe.VillagerDupeEngine.Opts;
import net.wurstclient.util.ChatUtils;

public final class VillagerDupeCmd extends Command
{
	public VillagerDupeCmd()
	{
		super("villagerdupe",
			"Runs the villager dupe or waits for a shearable trade item.",
			".villagerdupe (run|wait|preset|stop|status) [key=value ...]",
			"run: filter=all|saddles|horse_armor|carpets targets=all|nearest count=1 interval=1 stopAfter=0",
			"run toggles: refresh traders autoEmerald autoShears keepTotem stopWhenMissing=true|false",
			"wait: filter=all traders=true holdEmerald=true timeoutTicks=0",
			"preset: infinite run with current VillagerDupe settings");
	}
	
	@Override
	public List<String> getArgumentSuggestions(String[] args, int argIndex,
		String prefix)
	{
		if(argIndex == 0)
			return List.of("run", "wait", "preset", "stop", "status");
		if(args.length > 0 && args[0].equalsIgnoreCase("wait"))
			return List.of("filter=all", "traders=true", "holdEmerald=true",
				"timeoutTicks=0");
		return List.of("filter=all", "targets=nearest", "count=0", "interval=1",
			"stopAfter=0", "refresh=true", "traders=true", "autoEmerald=true",
			"autoShears=true", "keepTotem=true", "stopWhenMissing=true");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length == 0)
			throw new CmdSyntaxError();
		VillagerDupeHack hack = WURST.getHax().villagerDupeHack;
		String action = args[0].toLowerCase(Locale.ROOT);
		if(action.equals("stop") || action.equals("status"))
		{
			if(args.length != 1)
				throw new CmdSyntaxError();
			if(action.equals("stop"))
				hack.setEnabled(false);
			ChatUtils.message(hack.resultLine());
			return;
		}
		boolean wait = action.equals("wait");
		if(!wait && !action.equals("run") && !action.equals("preset"))
			throw new CmdSyntaxError();
		Map<String, String> values = new HashMap<>();
		Set<String> allowed =
			wait ? Set.of("filter", "traders", "holdemerald", "timeoutticks")
				: Set.of("filter", "targets", "count", "interval", "stopafter",
					"refresh", "traders", "autoemerald", "autoshears",
					"keeptotem", "stopwhenmissing");
		for(int i = 1; i < args.length; ++i)
		{
			String[] pair = args[i].split("=", 2);
			String key = pair[0].toLowerCase(Locale.ROOT);
			if(pair.length != 2 || !allowed.contains(key)
				|| values.putIfAbsent(key, pair[1]) != null)
				throw new CmdSyntaxError(
					"Unknown, repeated or malformed option: " + args[i]);
		}
		Opts defaults = hack.options();
		DupeFilter filter = defaults.filter();
		if(values.containsKey("filter"))
		{
			String token = values.get("filter").toLowerCase(Locale.ROOT);
			if(!Set.of("all", "saddle", "saddles", "horse_armor",
				"horse_armour", "carpet", "carpets").contains(token))
				throw new CmdSyntaxError("Invalid filter: " + token);
			filter = DupeFilter.fromToken(token);
		}
		String targets =
			values.getOrDefault("targets", "all").toLowerCase(Locale.ROOT);
		if(!targets.equals("all") && !targets.equals("nearest"))
			throw new CmdSyntaxError("Targets must be all or nearest.");
		Opts opts =
			new Opts(filter, bool(values, "traders", defaults.traders()),
				wait || bool(values, "autoemerald", defaults.autoEmerald()),
				!wait && bool(values, "autoshears", defaults.autoShears()),
				wait || bool(values, "keeptotem", defaults.keepTotem()),
				targets.equals("nearest"),
				!wait && bool(values, "refresh", defaults.refresh()),
				number(values, "interval", defaults.interval(), 1, 20));
		VillagerDupeHack.Run run = new VillagerDupeHack.Run(opts, wait,
			bool(values, "holdemerald", true),
			number(values, "timeoutticks", 0, 0, 1200),
			number(values, "count", action.equals("preset") ? 0 : 1, 0, 64),
			number(values, "stopafter", 0, 0, 1000000),
			bool(values, "stopwhenmissing", true));
		if(!hack.start(run))
			throw new CmdError("VillagerDupe could not be enabled.");
		ChatUtils.message(
			wait ? "Waiting for a villager item." : "Villager Dupe started.");
	}
	
	private static boolean bool(Map<String, String> values, String key,
		boolean fallback) throws CmdSyntaxError
	{
		String value = values.get(key);
		if(value == null)
			return fallback;
		if(value.equalsIgnoreCase("true"))
			return true;
		if(value.equalsIgnoreCase("false"))
			return false;
		throw new CmdSyntaxError(key + " must be true or false.");
	}
	
	private static int number(Map<String, String> values, String key,
		int fallback, int min, int max) throws CmdSyntaxError
	{
		if(!values.containsKey(key))
			return fallback;
		try
		{
			int value = Integer.parseInt(values.get(key));
			if(value >= min && value <= max)
				return value;
		}catch(NumberFormatException ignored)
		{}
		throw new CmdSyntaxError(
			key + " must be between " + min + " and " + max + ".");
	}
}
