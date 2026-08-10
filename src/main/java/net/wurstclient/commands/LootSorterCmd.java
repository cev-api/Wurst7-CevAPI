/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.util.List;
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
			"." + commandName + " list [source|destination]",
			"." + commandName + " show [source preset]");
		setCategory(Category.ITEMS);
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length == 0)
			throw new CmdSyntaxError();
		LootSorterHack hack = WURST.getHax().lootSorterHack;
		switch(args[0].toLowerCase())
		{
			case "source" -> load(hack, args, true);
			case "destination" -> load(hack, args, false);
			case "set" -> set(hack, args);
			case "delete", "remove" -> delete(hack, args);
			case "list" -> list(hack, args);
			case "show" -> show(hack, args);
			default -> throw new CmdSyntaxError();
		}
	}
	
	private void load(LootSorterHack hack, String[] args, boolean source)
		throws CmdSyntaxError
	{
		if(args.length < 2)
			throw new CmdSyntaxError();
		String name = joinName(args, 1);
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
		String name = joinName(args, 2);
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
	
	private boolean isType(String value, String type)
	{
		return value.equalsIgnoreCase(type)
			|| value.equalsIgnoreCase(type + "s");
	}
	
	private void printPresets(String type, List<String> names)
	{
		ChatUtils.message("LootSorter " + type + " presets (" + names.size()
			+ "): " + (names.isEmpty() ? "none" : String.join(", ", names)));
	}
	
	private String joinName(String[] args, int from)
	{
		return String.join(" ",
			java.util.Arrays.copyOfRange(args, from, args.length));
	}
}
