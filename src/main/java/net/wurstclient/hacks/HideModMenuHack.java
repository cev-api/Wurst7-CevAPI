/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.TextFieldSetting;

@SearchTags({"mod menu", "modmenu", "hide mod", "mod list", "hide list"})
public final class HideModMenuHack extends Hack
{
	private final TextFieldSetting keywords =
		new TextFieldSetting("Hide keywords",
			"Comma-separated keywords to hide mods in ModMenu.", "");
	
	public HideModMenuHack()
	{
		super("HideModMenu");
		setCategory(Category.OTHER);
		addSetting(keywords);
	}
	
	public Set<String> getKeywords()
	{
		String value = keywords.getValue();
		if(value == null || value.isBlank())
			return Set.of();
		
		return Arrays.stream(value.split(",")).map(String::trim)
			.filter(s -> !s.isEmpty()).map(String::toLowerCase)
			.collect(Collectors.toCollection(LinkedHashSet::new));
	}
}
