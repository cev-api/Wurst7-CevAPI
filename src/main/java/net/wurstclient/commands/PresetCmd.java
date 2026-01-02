/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;

import net.wurstclient.DontBlock;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.MathUtils;

@DontBlock
public final class PresetCmd extends Command
{
	public PresetCmd()
	{
		super("preset",
			"Saves or loads full Wurst presets (hacks, UI, keybinds, etc.).",
			".preset save <name>", ".preset load <name>",
			".preset list [<page>]",
			"Presets are saved in '.minecraft/wurst/presets'.");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length < 1)
			throw new CmdSyntaxError();
		
		switch(args[0].toLowerCase())
		{
			case "save":
			savePreset(args);
			break;
			
			case "load":
			loadPreset(args);
			break;
			
			case "list":
			listPresets(args);
			break;
			
			default:
			throw new CmdSyntaxError();
		}
	}
	
	private void savePreset(String[] args) throws CmdException
	{
		if(args.length != 2)
			throw new CmdSyntaxError();
		
		String name = args[1];
		
		try
		{
			WURST.getPresetManager().savePreset(name);
			ChatUtils.message("Preset saved: " + name);
			
		}catch(IOException e)
		{
			e.printStackTrace();
			throw new CmdError("Couldn't save preset: " + e.getMessage());
		}
	}
	
	private void loadPreset(String[] args) throws CmdException
	{
		if(args.length != 2)
			throw new CmdSyntaxError();
		
		String name = args[1];
		
		try
		{
			WURST.getPresetManager().loadPreset(name);
			ChatUtils.message("Preset loaded: " + name);
			
		}catch(NoSuchFileException e)
		{
			throw new CmdError("Preset '" + name + "' doesn't exist.");
			
		}catch(IOException e)
		{
			e.printStackTrace();
			throw new CmdError("Couldn't load preset: " + e.getMessage());
		}
	}
	
	private void listPresets(String[] args) throws CmdException
	{
		if(args.length > 2)
			throw new CmdSyntaxError();
		
		ArrayList<Path> files = WURST.getPresetManager().listPresets();
		int page = parsePage(args);
		int pages = (int)Math.ceil(files.size() / 8.0);
		pages = Math.max(pages, 1);
		
		if(page > pages || page < 1)
			throw new CmdSyntaxError("Invalid page: " + page);
		
		String total = "Total: " + files.size() + " preset";
		total += files.size() != 1 ? "s" : "";
		ChatUtils.message(total);
		
		int start = (page - 1) * 8;
		int end = Math.min(page * 8, files.size());
		
		ChatUtils.message("Preset list (page " + page + "/" + pages + ")");
		for(int i = start; i < end; i++)
			ChatUtils.message(files.get(i).getFileName().toString());
	}
	
	private int parsePage(String[] args) throws CmdSyntaxError
	{
		if(args.length < 2)
			return 1;
		
		if(!MathUtils.isInteger(args[1]))
			throw new CmdSyntaxError("Not a number: " + args[1]);
		
		return Integer.parseInt(args[1]);
	}
}
