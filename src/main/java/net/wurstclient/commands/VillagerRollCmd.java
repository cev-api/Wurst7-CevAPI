/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.hacks.VillagerRollHack;
import net.wurstclient.hacks.villageroll.VillagerRollPredictor;
import net.wurstclient.hacks.villageroll.VillagerRollSynchronizer;

public final class VillagerRollCmd extends Command
{
	public VillagerRollCmd()
	{
		super("villageroll", "Predicts deterministic novice librarian trades.",
			".villageroll", ".villageroll (status|help|reset|fresh|resync)",
			".villageroll seed [<seed>|clear]",
			".villageroll target <enchantment> [level|clear]",
			".villageroll <enchantment> [level] [maxprice <emeralds>]",
			".villageroll next <rolls>");
	}
	
	@Override
	public List<String> getArgumentSuggestions(String[] args, int argIndex,
		String prefix)
	{
		if(argIndex == 0)
		{
			List<String> suggestions =
				new ArrayList<>(List.of("status", "help", "setup", "reset",
					"fresh", "resync", "refresh", "seed", "target", "next"));
			suggestions.addAll(enchantmentSuggestions());
			return suggestions;
		}
		if(args.length == 0)
			return List.of();
		
		String command = args[0].toLowerCase(Locale.ROOT);
		switch(command)
		{
			case "seed":
			return argIndex == 1 ? List.of("clear") : List.of();
			case "target":
			if(argIndex == 1)
				return enchantmentSuggestions();
			if(argIndex == 2 && args.length > 1)
			{
				VillagerRollPredictor.EnchantmentInfo enchantment =
					VillagerRollPredictor.findEnchantment(args[1]);
				if(enchantment == null)
					return List.of("clear");
				List<String> levels = new ArrayList<>();
				for(int level = 1; level <= enchantment.maxLevel(); level++)
					levels.add(Integer.toString(level));
				levels.add("clear");
				return levels;
			}
			return List.of();
			case "next", "resync", "refresh":
			return argIndex == 1 ? List.of("1000", "100000", "1000000")
				: List.of();
			default:
			VillagerRollPredictor.EnchantmentInfo enchantment =
				VillagerRollPredictor.findEnchantment(command);
			if(enchantment == null)
				return List.of();
			if(argIndex == 1)
			{
				List<String> levels = new ArrayList<>();
				for(int level = 1; level <= enchantment.maxLevel(); level++)
					levels.add(Integer.toString(level));
				levels.add("maxprice");
				return levels;
			}
			if((argIndex == 2 && args.length > 1
				&& args[1].equalsIgnoreCase("maxprice"))
				|| (argIndex == 3 && args.length > 2
					&& args[2].equalsIgnoreCase("maxprice")))
				return List.of("10", "20", "30", "64");
			if(argIndex == 2)
				return List.of("maxprice");
			return List.of();
		}
	}
	
	private List<String> enchantmentSuggestions()
	{
		return VillagerRollPredictor.enchantments().stream()
			.map(enchantment -> shortId(enchantment.id())).toList();
	}
	
	private String shortId(String id)
	{
		int separator = id.lastIndexOf(':');
		return separator < 0 ? id : id.substring(separator + 1);
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		VillagerRollHack hack = WURST.getHax().villagerRollHack;
		if(args.length == 0)
		{
			hack.printStatus();
			return;
		}
		
		switch(args[0].toLowerCase(Locale.ROOT))
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
			case "fresh":
			if(args.length != 1)
				throw new CmdSyntaxError();
			hack.setFreshFromCommand();
			return;
			case "resync", "refresh":
			handleResync(hack, args);
			return;
			case "seed":
			handleSeed(hack, args);
			return;
			case "target":
			handleTarget(hack, args);
			return;
			case "next":
			handleNext(hack, args);
			return;
			default:
			handleEnchantmentSearch(hack, args);
		}
	}
	
	private void handleResync(VillagerRollHack hack, String[] args)
		throws CmdException
	{
		int horizon = VillagerRollSynchronizer.EXTENDED_SEARCH_HORIZON;
		if(args.length == 2)
			horizon = parsePositiveInt(args[1], "horizon");
		else if(args.length != 1)
			throw new CmdSyntaxError();
		hack.resynchronize(horizon);
	}
	
	private void handleSeed(VillagerRollHack hack, String[] args)
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
	
	private void handleTarget(VillagerRollHack hack, String[] args)
		throws CmdException
	{
		if(args.length == 2 && args[1].equalsIgnoreCase("clear"))
		{
			hack.clearTarget();
			return;
		}
		if(args.length < 2 || args.length > 3)
			throw new CmdSyntaxError();
		Integer level =
			args.length == 3 ? parsePositiveInt(args[2], "level") : null;
		hack.setTarget(args[1], level);
	}
	
	private void handleNext(VillagerRollHack hack, String[] args)
		throws CmdException
	{
		if(args.length != 2)
			throw new CmdSyntaxError();
		hack.printUpcoming(parsePositiveInt(args[1], "rolls"));
	}
	
	private void handleEnchantmentSearch(VillagerRollHack hack, String[] args)
		throws CmdException
	{
		if(VillagerRollPredictor.findEnchantment(args[0]) == null)
			throw new CmdError("Unknown subcommand or enchantment: " + args[0]);
		
		Integer level = null;
		Integer maxPrice = null;
		if(args.length == 2)
		{
			if(args[1].equalsIgnoreCase("maxprice"))
				throw new CmdSyntaxError();
			level = parsePositiveInt(args[1], "level");
		}else if(args.length == 3 && args[1].equalsIgnoreCase("maxprice"))
			maxPrice = parsePositiveInt(args[2], "maximum price");
		else if(args.length == 4 && args[2].equalsIgnoreCase("maxprice"))
		{
			level = parsePositiveInt(args[1], "level");
			maxPrice = parsePositiveInt(args[3], "maximum price");
		}else if(args.length != 1)
			throw new CmdSyntaxError();
		
		hack.search(args[0], level, maxPrice);
	}
	
	private int parsePositiveInt(String input, String name) throws CmdError
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
	
}
