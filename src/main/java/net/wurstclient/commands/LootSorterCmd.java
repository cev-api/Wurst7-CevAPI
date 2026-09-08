/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.List;
import java.util.ArrayList;
import net.wurstclient.Category;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.hacks.LootSorterHack;
import net.wurstclient.util.ChatUtils;

/** Commands for reusable, independently saved LootSorter selections. */
public class LootSorterCmd extends Command
{
	public LootSorterCmd()
	{
		this("lootsorter");
	}
	
	/** Keeps .lootsorter while allowing the shorter .lootsort alias. */
	public LootSorterCmd(String commandName)
	{
		super(commandName,
			"Loads, saves, lists, or deletes LootSorter source and destination presets.",
			"." + commandName + " source <preset>",
			"." + commandName + " destination <preset>",
			"." + commandName + " set source <preset>",
			"." + commandName + " set destination <preset>",
			"." + commandName + " delete <source|destination> <preset>",
			"." + commandName + " delete preset <number>",
			"." + commandName + " list [source|destination]",
			"." + commandName + " presets <source|destination>",
			"." + commandName + " show [source preset]",
			"." + commandName + " frames [on|off|toggle]",
			"." + commandName + " bulk [on|off|toggle]",
			"." + commandName + " repeat [source|destination|both]",
			"." + commandName + " help");
		setCategory(Category.ITEMS);
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length == 0 || args[0].equalsIgnoreCase("help"))
		{
			printLootSorterHelp();
			return;
		}
		LootSorterHack hack = WURST.getHax().lootSorterHack;
		switch(args[0].toLowerCase())
		{
			case "source" -> load(hack, args, true);
			case "destination" -> load(hack, args, false);
			case "set" -> set(hack, args);
			case "delete", "remove" -> delete(hack, args);
			case "list", "presets" -> list(hack, args);
			case "show" -> show(hack, args);
			case "frames", "frame", "autosortframes" -> setFrames(hack, args);
			case "bulk", "area" -> setBulk(hack, args);
			case "repeat", "again" -> repeat(hack, args);
			default -> throw new CmdSyntaxError();
		}
	}
	
	private void repeat(LootSorterHack hack, String[] args)
		throws CmdSyntaxError
	{
		if(args.length > 2)
			throw new CmdSyntaxError("Expected source, destination, or both.");
		String part = args.length == 1 ? "both" : args[1].toLowerCase();
		if(!part.equals("source") && !part.equals("sources")
			&& !part.equals("destination") && !part.equals("destinations")
			&& !part.equals("both"))
			throw new CmdSyntaxError("Expected source, destination, or both.");
		hack.repeatLastRun(part.startsWith("source") ? "source"
			: part.startsWith("destination") ? "destination" : "both");
	}
	
	private void printLootSorterHelp()
	{
		ChatUtils.message("LootSorter commands:");
		ChatUtils
			.message(".lootsorter set source <name> - save source containers.");
		ChatUtils.message(
			".lootsorter set destination <name> - save destination rules.");
		ChatUtils.message(
			".lootsorter source/destination <name> - load a preset for a run.");
		ChatUtils.message(
			".lootsorter bulk - select all loaded containers between two points.");
		ChatUtils.message(
			".lootsorter repeat [source|destination|both] - reuse the last layout.");
		ChatUtils.message(
			".lootsorter list [source|destination] - list saved presets.");
		ChatUtils.message("Use .lootsorter help for this message.");
	}
	
	private void load(LootSorterHack hack, String[] args, boolean source)
		throws CmdSyntaxError
	{
		if(args.length < 2)
			throw new CmdSyntaxError();
		String name = resolvePreset(hack, joinName(args, 1), source);
		if(source)
			hack.loadSourcePresetForRun(name);
		else
			hack.loadDestinationPresetForRun(name);
	}
	
	private void set(LootSorterHack hack, String[] args) throws CmdSyntaxError
	{
		if(args.length < 3)
			throw new CmdSyntaxError();
		String type = args[1].toLowerCase();
		String name = joinName(args, 2);
		switch(type)
		{
			case "source", "sources" -> hack.saveOrBeginSourcePreset(name);
			case "destination", "destinations" -> hack
				.beginDestinationPresetSetup(name);
			default -> throw new CmdSyntaxError(
				"Preset type must be source or destination.");
		}
	}
	
	private void delete(LootSorterHack hack, String[] args)
		throws CmdSyntaxError
	{
		if(args.length < 3)
			throw new CmdSyntaxError();
		String type = args[1].toLowerCase();
		if(type.equals("preset") || type.equals("presets"))
		{
			if(args.length != 3)
				throw new CmdSyntaxError("Use delete preset <number>.");
			int number = parsePresetNumber(args[2]);
			List<String> names = new ArrayList<>(hack.getSourcePresetNames());
			names.addAll(hack.getDestinationPresetNames());
			if(number < 1 || number > names.size())
				throw new CmdSyntaxError("No preset numbered " + number + ".");
			String name = names.get(number - 1);
			boolean deleted = hack.deleteSourcePreset(name)
				|| hack.deleteDestinationPreset(name);
			if(deleted)
				ChatUtils.message("LootSorter: deleted preset " + number + " ("
					+ name + ").");
			return;
		}
		String name = joinName(args, 2);
		if(args.length == 3 && args[2].matches("\\d+"))
		{
			boolean source = isType(type, "source");
			name = resolvePreset(hack, args[2], source);
		}
		boolean deleted = switch(type)
		{
			case "source", "sources" -> hack.deleteSourcePreset(name);
			case "destination", "destinations" -> hack
				.deleteDestinationPreset(name);
			default -> throw new CmdSyntaxError(
				"Preset type must be source or destination.");
		};
		if(deleted)
			ChatUtils.message("LootSorter: deleted " + type.replaceAll("s$", "")
				+ " preset " + name + ".");
	}
	
	private void list(LootSorterHack hack, String[] args) throws CmdSyntaxError
	{
		if(args.length > 2)
			throw new CmdSyntaxError();
		if(args.length == 1 || isType(args[1], "source"))
			printPresets("source", hack.getSourcePresetNames());
		if(args.length == 1 || isType(args[1], "destination"))
			printPresets("destination", hack.getDestinationPresetNames());
		if(args.length == 2 && !isType(args[1], "source")
			&& !isType(args[1], "destination"))
			throw new CmdSyntaxError(
				"Preset type must be source or destination.");
	}
	
	private void show(LootSorterHack hack, String[] args)
	{
		hack.showSourceChestSearch(args.length == 1 ? null : joinName(args, 1));
	}
	
	private void setFrames(LootSorterHack hack, String[] args)
		throws CmdSyntaxError
	{
		hack.setAutosortBasedOnFrames(
			parseToggle(args, 1, hack.isAutosortBasedOnFrames()));
		ChatUtils.message("LootSorter: autosort based on frames "
			+ (hack.isAutosortBasedOnFrames() ? "enabled" : "disabled") + ".");
	}
	
	private void setBulk(LootSorterHack hack, String[] args)
		throws CmdSyntaxError
	{
		hack.setBulkSelection(
			parseToggle(args, 1, hack.isBulkSelectionEnabled()));
		if(hack.isBulkSelectionEnabled() && !hack.isEnabled())
			hack.setEnabled(true);
		ChatUtils.message("LootSorter: bulk area selection "
			+ (hack.isBulkSelectionEnabled() ? "enabled" : "disabled") + ".");
		if(hack.isBulkSelectionEnabled())
			ChatUtils.message(
				"LootSorter: when selection starts, right-click point A, then point B, then press Enter.");
	}
	
	private boolean parseToggle(String[] args, int index, boolean current)
		throws CmdSyntaxError
	{
		if(args.length == index)
			return !current;
		if(args.length != index + 1)
			throw new CmdSyntaxError("Expected on, off, or toggle.");
		return switch(args[index].toLowerCase())
		{
			case "on", "enable", "enabled" -> true;
			case "off", "disable", "disabled" -> false;
			case "toggle" -> !current;
			default -> throw new CmdSyntaxError("Expected on, off, or toggle.");
		};
	}
	
	private boolean isType(String value, String type)
	{
		return value.equalsIgnoreCase(type)
			|| value.equalsIgnoreCase(type + "s");
	}
	
	private void printPresets(String type, List<String> names)
	{
		if(names.isEmpty())
		{
			ChatUtils.message("LootSorter " + type + " presets: none");
			return;
		}
		for(int i = 0; i < names.size(); i++)
			ChatUtils.message((i + 1) + ". " + names.get(i));
	}
	
	private String resolvePreset(LootSorterHack hack, String value,
		boolean source) throws CmdSyntaxError
	{
		if(!value.matches("\\d+"))
			return value;
		int number = parsePresetNumber(value);
		List<String> names = source ? hack.getSourcePresetNames()
			: hack.getDestinationPresetNames();
		if(number < 1 || number > names.size())
			throw new CmdSyntaxError("No " + (source ? "source" : "destination")
				+ " preset numbered " + number + ".");
		return names.get(number - 1);
	}
	
	private int parsePresetNumber(String value) throws CmdSyntaxError
	{
		try
		{
			return Integer.parseInt(value);
		}catch(NumberFormatException e)
		{
			throw new CmdSyntaxError("Preset number must be positive.");
		}
	}
	
	@Override
	public List<String> getArgumentSuggestions(String[] args, int argIndex,
		String prefix)
	{
		List<String> result = new ArrayList<>(
			super.getArgumentSuggestions(args, argIndex, prefix));
		LootSorterHack hack = WURST.getHax().lootSorterHack;
		if(argIndex == 0)
			result.addAll(List.of("help", "source", "destination", "presets",
				"list", "delete", "set", "show", "frames", "bulk", "repeat"));
		else if(argIndex == 1 && args.length > 0
			&& (args[0].equalsIgnoreCase("source")
				|| args[0].equalsIgnoreCase("destination")))
		{
			boolean source = args[0].equalsIgnoreCase("source");
			result.addAll(source ? hack.getSourcePresetNames()
				: hack.getDestinationPresetNames());
		}
		return result;
	}
	
	private String joinName(String[] args, int from)
	{
		return String.join(" ",
			java.util.Arrays.copyOfRange(args, from, args.length));
	}
}
