/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.hacks.VaultRollHack;
import net.wurstclient.hacks.vaultroll.VaultRollMode;
import net.wurstclient.hacks.vaultroll.VaultRollPredictor;
import net.wurstclient.hacks.vaultroll.VaultRollSynchronizer;

public final class VaultRollCmd extends Command
{
	public VaultRollCmd()
	{
		super("vaultroll", "Predicts deterministic trial Vault loot.",
			".vaultroll",
			".vaultroll (status|help|reset|resetall|fresh|resync|mode)",
			".vaultroll seed [<seed>|clear]",
			".vaultroll observe [normal|ominous] <item=count,...>",
			".vaultroll target <item> [count|clear]",
			".vaultroll search <item> [count]", ".vaultroll next <openings>");
	}
	
	@Override
	public List<String> getArgumentSuggestions(String[] args, int argIndex,
		String prefix)
	{
		if(argIndex == 0)
		{
			List<String> suggestions = new ArrayList<>(
				List.of("status", "help", "setup", "reset", "resetall", "fresh",
					"resync", "refresh", "mode", "seed", "observe", "target",
					"search", "next", "normal", "ominous"));
			suggestions.addAll(VaultRollPredictor.itemIds());
			return suggestions;
		}
		if(args.length == 0)
			return List.of();
		
		String command = args[0].toLowerCase(Locale.ROOT);
		switch(command)
		{
			case "seed":
			return argIndex == 1 ? List.of("clear") : List.of();
			case "mode":
			return argIndex == 1 ? modeSuggestions() : List.of();
			case "fresh":
			return argIndex == 1 ? modeSuggestions() : List.of();
			case "resync", "refresh":
			return argIndex == 1 ? numericOrModeSuggestions() : List.of();
			case "observe":
			if(argIndex == 1)
			{
				List<String> suggestions = new ArrayList<>(modeSuggestions());
				suggestions.addAll(observationItemSuggestions(prefix));
				return suggestions;
			}
			if(argIndex >= 2)
				return observationItemSuggestions(prefix);
			return List.of();
			case "target":
			if(argIndex == 1)
				return VaultRollPredictor.itemIds();
			if(argIndex == 2)
				return List.of("1", "2", "3", "4", "8", "16", "clear");
			return List.of();
			case "search":
			if(argIndex == 1)
				return VaultRollPredictor.itemIds();
			if(argIndex == 2)
				return numericSuggestions();
			return List.of();
			case "next":
			return argIndex == 1 ? numericSuggestions() : List.of();
			default:
			if(VaultRollMode.parse(command) != null)
				return List.of();
			if(VaultRollPredictor.itemIds().contains(command) && argIndex == 1)
				return numericSuggestions();
			return List.of();
		}
	}
	
	private List<String> modeSuggestions()
	{
		return List.of("normal", "ominous");
	}
	
	private List<String> numericOrModeSuggestions()
	{
		List<String> suggestions = new ArrayList<>(modeSuggestions());
		suggestions.addAll(List.of("1000", "100000", "1000000"));
		return suggestions;
	}
	
	private List<String> numericSuggestions()
	{
		return List.of("1", "2", "3", "4", "8", "16", "32", "64");
	}
	
	private List<String> observationItemSuggestions(String prefix)
	{
		int comma = prefix.lastIndexOf(',');
		int equals = prefix.lastIndexOf('=');
		if(equals > comma)
		{
			String countPrefix = prefix.substring(0, equals + 1);
			return List.of("1", "2", "3", "4", "8", "12", "16").stream()
				.map(count -> countPrefix + count).toList();
		}
		String base = "";
		if(comma >= 0)
			base = prefix.substring(0, comma + 1);
		String basePrefix = base;
		return VaultRollPredictor.itemIds().stream()
			.map(item -> basePrefix + item + "=").toList();
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		VaultRollHack hack = WURST.getHax().vaultRollHack;
		if(args.length == 0)
		{
			hack.printStatus();
			return;
		}
		String command = args[0].toLowerCase(Locale.ROOT);
		switch(command)
		{
			case "status":
			if(args.length != 1)
				throw new CmdSyntaxError();
			hack.printStatus();
			return;
			case "help", "setup":
			if(args.length != 1)
				throw new CmdSyntaxError();
			hack.printHelp();
			return;
			case "reset":
			if(args.length != 1)
				throw new CmdSyntaxError();
			hack.resetFromCommand();
			return;
			case "resetall":
			if(args.length != 1)
				throw new CmdSyntaxError();
			hack.resetAllFromCommand();
			return;
			case "fresh":
			handleFresh(hack, args);
			return;
			case "resync", "refresh":
			handleResync(hack, args);
			return;
			case "mode":
			if(args.length != 2)
				throw new CmdSyntaxError();
			hack.setMode(args[1]);
			return;
			case "seed":
			handleSeed(hack, args);
			return;
			case "observe":
			handleObserve(hack, args);
			return;
			case "target":
			handleTarget(hack, args);
			return;
			case "search":
			handleSearch(hack, args);
			return;
			case "next":
			handleNext(hack, args);
			return;
			default:
			if(args.length == 1 && VaultRollMode.parse(args[0]) != null)
			{
				hack.setMode(args[0]);
				return;
			}
			if(args.length == 1 || args.length == 2)
			{
				Integer count = args.length == 2
					? parsePositiveInt(args[1], "search count") : null;
				hack.search(args[0], count);
				return;
			}
			throw new CmdError("Unknown subcommand: " + args[0]);
		}
	}
	
	private void handleFresh(VaultRollHack hack, String[] args)
		throws CmdException
	{
		if(args.length == 1)
		{
			hack.setFreshFromCommand(null);
			return;
		}
		if(args.length != 2)
			throw new CmdSyntaxError();
		VaultRollMode mode = parseMode(args[1]);
		hack.setFreshFromCommand(mode);
	}
	
	private void handleResync(VaultRollHack hack, String[] args)
		throws CmdException
	{
		int horizon = hack.getSynchronizationHorizon();
		if(args.length == 2)
		{
			if(VaultRollMode.parse(args[1]) != null)
			{
				hack.setMode(args[1]);
				hack.resynchronize(horizon);
				return;
			}
			horizon = parseNonNegativeInt(args[1], "horizon");
		}else if(args.length != 1)
			throw new CmdSyntaxError();
		if(horizon < VaultRollSynchronizer.INITIAL_SEARCH_HORIZON)
			throw new CmdError("Horizon must be at least 1000.");
		hack.resynchronize(horizon);
	}
	
	private void handleSeed(VaultRollHack hack, String[] args)
		throws CmdException
	{
		if(args.length == 1)
		{
			hack.printSeed();
			return;
		}
		if(args.length != 2)
			throw new CmdSyntaxError();
		if(args[1].equalsIgnoreCase("clear"))
			hack.clearManualSeed();
		else
			hack.setManualSeed(args[1]);
	}
	
	private void handleObserve(VaultRollHack hack, String[] args)
		throws CmdException
	{
		if(args.length < 2)
			throw new CmdSyntaxError();
		int start = 1;
		VaultRollMode mode = VaultRollMode.parse(args[start]);
		if(mode != null)
			start++;
		if(start >= args.length)
			throw new CmdSyntaxError();
		String input =
			String.join(" ", Arrays.copyOfRange(args, start, args.length));
		hack.observeFromCommand(mode, input);
	}
	
	private void handleTarget(VaultRollHack hack, String[] args)
		throws CmdException
	{
		if(args.length == 2 && args[1].equalsIgnoreCase("clear"))
		{
			hack.clearTarget();
			return;
		}
		if(args.length < 2 || args.length > 3)
			throw new CmdSyntaxError();
		Integer count =
			args.length == 3 ? parsePositiveInt(args[2], "target count") : null;
		hack.setTarget(args[1], count);
	}
	
	private void handleSearch(VaultRollHack hack, String[] args)
		throws CmdException
	{
		if(args.length < 2 || args.length > 3)
			throw new CmdSyntaxError();
		Integer count =
			args.length == 3 ? parsePositiveInt(args[2], "search count") : null;
		hack.search(args[1], count);
	}
	
	private void handleNext(VaultRollHack hack, String[] args)
		throws CmdException
	{
		if(args.length != 2)
			throw new CmdSyntaxError();
		hack.printUpcoming(parseNonNegativeInt(args[1], "openings"));
	}
	
	private VaultRollMode parseMode(String input) throws CmdError
	{
		VaultRollMode mode = VaultRollMode.parse(input);
		if(mode == null)
			throw new CmdError("Mode must be normal or ominous.");
		return mode;
	}
	
	private int parseNonNegativeInt(String input, String name) throws CmdError
	{
		try
		{
			int value = Integer.parseInt(input);
			if(value < 0)
				throw new NumberFormatException();
			return value;
		}catch(NumberFormatException e)
		{
			throw new CmdError(name + " must be a non-negative integer.");
		}
	}
	
	private int parsePositiveInt(String input, String name) throws CmdError
	{
		int value = parseNonNegativeInt(input, name);
		if(value == 0)
			throw new CmdError(name + " must be positive.");
		return value;
	}
}
