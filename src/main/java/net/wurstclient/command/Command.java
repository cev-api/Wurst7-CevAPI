/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.command;

import java.util.Locale;
import java.util.Objects;

import net.wurstclient.Category;
import net.wurstclient.Feature;
import net.wurstclient.util.ChatUtils;

public abstract class Command extends Feature
{
	private final String name;
	private final String description;
	private final String[] syntax;
	private Category category;
	
	public Command(String name, String description, String... syntax)
	{
		this.name = Objects.requireNonNull(name);
		this.description = Objects.requireNonNull(description);
		
		Objects.requireNonNull(syntax);
		if(syntax.length > 0)
			syntax[0] = "Syntax: " + syntax[0];
		this.syntax = syntax;
		
		if(name.contains(" "))
			throw new IllegalArgumentException(
				"Feature name must not contain spaces: " + name);
	}
	
	public abstract void call(String[] args) throws CmdException;
	
	@Override
	public final String getName()
	{
		return "." + name;
	}
	
	@Override
	public String getDisplayName()
	{
		String fullName = getName();
		if(fullName.equalsIgnoreCase(".friends"))
			return "Friends";
		
		return fullName;
	}
	
	@Override
	public String getPrimaryAction()
	{
		return "";
	}
	
	@Override
	public final String getDescription()
	{
		String description = this.description;
		
		if(syntax.length > 0)
			description += "\n";
		
		for(String line : syntax)
			description += "\n" + line;
		
		return description;
	}
	
	public final String[] getSyntax()
	{
		return syntax;
	}
	
	public boolean shouldSuggestPlayerNames(int argIndex)
	{
		if(argIndex < 0)
			return false;
		
		for(String line : syntax)
		{
			if(line == null)
				continue;
			
			String trimmed = line.trim();
			if(trimmed.regionMatches(true, 0, "Syntax:", 0, "Syntax:".length()))
				trimmed = trimmed.substring("Syntax:".length()).trim();
			if(!trimmed.startsWith("."))
				continue;
			
			String[] tokens = trimmed.split("\\s+");
			if(tokens.length <= argIndex + 1)
				continue;
			
			String token = tokens[argIndex + 1].toLowerCase(Locale.ROOT);
			if(token.contains("<player>"))
				return true;
		}
		
		return false;
	}
	
	public final void printHelp()
	{
		for(String line : description.split("\n"))
			ChatUtils.message(line);
		
		for(String line : syntax)
			ChatUtils.message(line);
	}
	
	@Override
	public final Category getCategory()
	{
		return category;
	}
	
	protected final void setCategory(Category category)
	{
		this.category = category;
	}
}
