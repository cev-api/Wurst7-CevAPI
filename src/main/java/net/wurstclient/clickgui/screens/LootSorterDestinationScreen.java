/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.wurstclient.hacks.lootsorter.BuiltInItemFilter;
import net.wurstclient.hacks.lootsorter.DestinationRule;
import net.wurstclient.hacks.lootsorter.ItemFilter;
import net.wurstclient.hacks.lootsorter.ItemFilterModifiers;
import net.wurstclient.hacks.lootsorter.ModifiedItemFilter;
import net.wurstclient.settings.ItemListSetting;

/**
 * Filter-first destination editor. A destination has one clear receiving rule:
 * pick its filter, optionally narrow it, then save it.
 */
public final class LootSorterDestinationScreen extends Screen
{
	private final Screen previous;
	private final DestinationRule rule;
	private final ItemListSetting customItems;
	private final Supplier<ItemFilter> customFilter;
	private final Function<String, ItemFilter> saveCustomList;
	private final Runnable removeDestination;
	private final Runnable finalAction;
	private final String finalActionLabel;
	private ItemFilter selected = BuiltInItemFilter.ALL;
	private ItemFilterModifiers modifiers = new ItemFilterModifiers(null, null,
		null, null, null, null, null, null, null);
	private boolean draftCustomList;
	
	public LootSorterDestinationScreen(Screen previous, DestinationRule rule,
		ItemListSetting customItems, Supplier<ItemFilter> customFilter,
		Function<String, ItemFilter> saveCustomList, Runnable removeDestination,
		Runnable finalAction, String finalActionLabel)
	{
		super(Component.literal("LootSorter destination"));
		this.previous = previous;
		this.rule = rule;
		this.customItems = customItems;
		this.customFilter = customFilter;
		this.saveCustomList = saveCustomList;
		this.removeDestination = removeDestination;
		this.finalAction = finalAction;
		this.finalActionLabel = finalActionLabel;
		if(!rule.getFilters().isEmpty())
			loadFilter(rule.getFilters().getFirst());
	}
	
	@Override
	public void init()
	{
		int x = width / 2 - 110;
		int y = Math.max(24, height / 2 - 92);
		addRenderableWidget(Button.builder(filterText(), button -> minecraft.gui
			.setScreen(new LootSorterFilterMenuScreen(this, filter -> {
				selected = filter;
				draftCustomList = false;
			}, this::editCustomItems, this::loadCustomList)))
			.bounds(x, y, 220, 20).build());
		addRenderableWidget(Button.builder(matchingRulesText(),
			button -> minecraft.setScreen(new LootSorterFilterRulesScreen(
				this, modifiers, updated -> modifiers = updated)))
			.bounds(x, y + 26, 220, 20).build());
		addRenderableWidget(Button
			.builder(Component.literal("Set custom item list"),
				button -> editCustomItems())
			.bounds(x, y + 52, 220, 20).build());
		addRenderableWidget(Button
			.builder(Component.literal("Save custom item list"),
				button -> promptCustomListName())
			.bounds(x, y + 78, 220, 20).build());
		addRenderableWidget(Button
			.builder(Component.literal("Save destination"),
				button -> saveDestinationAndReturn())
			.bounds(x, y + 104, 220, 20).build());
		addRenderableWidget(
			Button
				.builder(Component.literal(finalActionLabel),
					button -> saveAndRun())
				.bounds(x, y + 130, 220, 20).build());
		addRenderableWidget(
			Button.builder(Component.literal("Remove destination"), button -> {
				removeDestination.run();
				minecraft.setScreen(previous);
			}).bounds(x, y + 156, 220, 20).build());
	}
	
	@Override
	public void onClose()
	{
		if(!rule.isConfigured())
			removeDestination.run();
		minecraft.setScreen(previous);
	}
	
	private void editCustomItems()
	{
		selected = customFilter.get();
		draftCustomList = true;
		minecraft.setScreen(new EditItemListScreen(this, customItems));
	}
	
	private void loadCustomList()
	{
		minecraft.gui
			.setScreen(new LootSorterCustomPresetScreen(this, preset -> {
				selected = preset;
				draftCustomList = false;
			}));
	}
	
	private Component filterText()
	{
		return Component
			.literal("Select filter: " + currentSelected().getDisplayName());
	}
	
	private Component matchingRulesText()
	{
		return Component.literal(hasModifiers() ? "Matching rules: configured"
			: "Matching rules: none");
	}
	
	private boolean hasModifiers()
	{
		return modifiers.enchanted() != null || modifiers.damaged() != null
			|| modifiers.minimumDurabilityPercent() != null
			|| modifiers.minimumEnchantmentLevel() != null
			|| modifiers.customNamed() != null
			|| modifiers.requiredEnchantmentId() != null
			|| modifiers.material() != null || modifiers.curse() != null;
	}
	
	private ItemFilter selectedFilter()
	{
		ItemFilter current = currentSelected();
		return hasModifiers() ? new ModifiedItemFilter(current, modifiers)
			: current;
	}
	
	/** Refreshes a temporary custom list after the item editor changes it. */
	private ItemFilter currentSelected()
	{
		return draftCustomList ? customFilter.get() : selected;
	}
	
	private boolean saveDestination()
	{
		rule.clearFilters();
		rule.addFilter(selectedFilter());
		rule.setConfigured(true);
		return true;
	}
	
	private void saveDestinationAndReturn()
	{
		if(saveDestination())
			minecraft.setScreen(previous);
	}
	
	private void saveAndRun()
	{
		if(!saveDestination())
			return;
		minecraft.setScreen(previous);
		finalAction.run();
	}
	
	private void promptCustomListName()
	{
		minecraft.setScreen(new LootSorterCustomPresetNameScreen(this,
			saveCustomList, saved -> {
				selected = saved;
				draftCustomList = false;
			}));
	}
	
	private void loadFilter(ItemFilter filter)
	{
		if(filter instanceof ModifiedItemFilter modified)
		{
			modifiers = modified.modifiers();
			loadFilter(modified.base());
			return;
		}
		selected = filter;
	}
}
