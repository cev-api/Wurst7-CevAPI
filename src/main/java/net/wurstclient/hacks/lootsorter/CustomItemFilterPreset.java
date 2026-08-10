/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;

/** Globally reusable exact-item preset. The editor stores registry IDs. */
public final class CustomItemFilterPreset implements ItemFilter
{
	private final String name;
	private final Set<String> included;
	private final Set<String> excluded;
	private final Set<String> itemTags;
	private final ItemFilterModifiers modifiers;
	
	public CustomItemFilterPreset(String name, Set<String> included,
		Set<String> excluded, Boolean enchanted, Boolean customNamed)
	{
		this(name, included, excluded, Set.of(),
			new ItemFilterModifiers(enchanted, null, null, null, customNamed));
	}
	
	public CustomItemFilterPreset(String name, Set<String> included,
		Set<String> excluded, Set<String> itemTags,
		ItemFilterModifiers modifiers)
	{
		this.name = name;
		this.included = new LinkedHashSet<>(included);
		this.excluded = new LinkedHashSet<>(excluded);
		this.itemTags = new LinkedHashSet<>(itemTags);
		this.modifiers = modifiers;
	}
	
	@Override
	public boolean matches(ItemStack stack)
	{
		if(stack.isEmpty())
			return false;
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		if(excluded.contains(id))
			return false;
		boolean explicitMatch = included.contains(id);
		boolean tagMatch =
			itemTags.stream().anyMatch(tag -> matchesTag(stack, tag));
		if((!included.isEmpty() || !itemTags.isEmpty()) && !explicitMatch
			&& !tagMatch)
			return false;
		return modifiers == null || modifiers.matches(stack);
	}
	
	@Override
	public int specificity()
	{
		return 80;
	}
	
	@Override
	public String getDisplayName()
	{
		return name;
	}
	
	public Set<String> getIncluded()
	{
		return Set.copyOf(included);
	}
	
	public String getName()
	{
		return name;
	}
	
	public ItemFilterModifiers getModifiers()
	{
		return modifiers;
	}
	
	public Set<String> getExcluded()
	{
		return Set.copyOf(excluded);
	}
	
	public Set<String> getItemTags()
	{
		return Set.copyOf(itemTags);
	}
	
	private boolean matchesTag(ItemStack stack, String raw)
	{
		try
		{
			Identifier id = Identifier.parse(raw);
			return stack.is(TagKey.create(Registries.ITEM, id));
		}catch(IllegalArgumentException e)
		{
			return false;
		}
	}
}
