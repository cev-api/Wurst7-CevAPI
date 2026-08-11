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

import java.util.LinkedHashSet;
import java.util.List;

import net.wurstclient.Category;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.Setting;
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
	
	/**
	 * @return true if the syntax token at the given argument index expects a
	 *         saved account name (e.g. <code>&lt;alt&gt;</code>), used by the
	 *         Alt Manager autocomplete to suggest compatible saved alts.
	 */
	public boolean shouldSuggestAltNames(int argIndex)
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
			if(token.contains("<alt>") || token.contains("<account>"))
				return true;
		}
		
		return false;
	}
	
	/**
	 * @return extra autocomplete candidates for the argument currently being
	 *         typed. <code>args</code> is everything after the command name
	 *         (the token being typed is <code>args[args.length - 1]</code>),
	 *         <code>argIndex</code> is the 0-based index of that token and
	 *         <code>prefix</code> is what was typed so far for it. The caller
	 *         applies its own prefix filtering to whatever is returned.
	 */
	public java.util.List<String> getArgumentSuggestions(String[] args,
		int argIndex, String prefix)
	{
		LinkedHashSet<String> suggestions = new LinkedHashSet<>();
		for(String line : syntax)
		{
			String trimmed = line.replaceFirst("(?i)^Syntax:\\s*", "").trim();
			if(!trimmed.startsWith("."))
				continue;
			String[] tokens = trimmed.split("\\s+");
			if(tokens.length <= argIndex + 1
				|| !matchesPreviousLiterals(tokens, args, argIndex))
				continue;
			addTokenSuggestions(suggestions, tokens[argIndex + 1], args,
				argIndex);
		}
		return List.copyOf(suggestions);
	}
	
	private boolean matchesPreviousLiterals(String[] syntaxTokens,
		String[] args, int argIndex)
	{
		for(int i = 0; i < argIndex && i < args.length; i++)
		{
			String expected = syntaxTokens[i + 1];
			if(expected.startsWith("<") || expected.startsWith("[")
				|| expected.startsWith("("))
				continue;
			if(!expected.equalsIgnoreCase(args[i]))
				return false;
		}
		return true;
	}
	
	private void addTokenSuggestions(LinkedHashSet<String> suggestions,
		String token, String[] args, int argIndex)
	{
		String bare = token.replaceAll("^[\\[(]|[\\])]$", "");
		if(token.startsWith("("))
		{
			for(String option : bare.split("\\|"))
				suggestions.add(option);
			return;
		}
		if(!token.startsWith("<") && !token.startsWith("["))
		{
			suggestions.add(token);
			return;
		}
		String placeholder = bare.toLowerCase(Locale.ROOT);
		if(placeholder.contains("feature"))
		{
			WurstClient.INSTANCE.getNavigator().getList().stream()
				.filter(feature -> !feature.getSettings().isEmpty())
				.map(Feature::getName).map(name -> name.replace(" ", "_"))
				.forEach(suggestions::add);
			return;
		}
		if(!placeholder.contains("setting") && !placeholder.contains("mode"))
			return;
		Feature feature = findSuggestedFeature(args, argIndex);
		if(feature == null)
			return;
		if(placeholder.contains("setting"))
		{
			feature.getSettings().values().stream().map(Setting::getName)
				.map(name -> name.replace(" ", "_")).forEach(suggestions::add);
			return;
		}
		if(args.length > 1)
		{
			Setting setting = feature.getSettings()
				.get(args[1].replace("_", " ").toLowerCase(Locale.ROOT));
			if(setting instanceof EnumSetting<?> enumSetting)
				for(Enum<?> value : enumSetting.getValues())
					suggestions.add(value.toString().replace(" ", "_"));
		}
	}
	
	private Feature findSuggestedFeature(String[] args, int argIndex)
	{
		if(argIndex < 1 || args.length == 0)
			return null;
		String name = args[0].replace("_", " ");
		return WurstClient.INSTANCE.getNavigator().getList().stream()
			.filter(feature -> feature.getName().equalsIgnoreCase(name))
			.findFirst().orElse(null);
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
