/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.wurstclient.hacks.lootsorter.BuiltInItemFilter;
import net.wurstclient.hacks.lootsorter.ItemFilter;

/** A ClickGUI-style hierarchy for built-in and custom LootSorter filters. */
public final class LootSorterFilterMenuScreen extends Screen
{
	private final Screen back;
	private final Screen editor;
	private final Consumer<ItemFilter> selection;
	private final Runnable editCustomItems;
	private final Runnable loadCustomList;
	private final FilterGroup group;
	private int page;
	
	public LootSorterFilterMenuScreen(Screen editor,
		Consumer<ItemFilter> selection, Runnable editCustomItems,
		Runnable loadCustomList)
	{
		this(editor, editor, selection, editCustomItems, loadCustomList, null);
	}
	
	private LootSorterFilterMenuScreen(Screen back, Screen editor,
		Consumer<ItemFilter> selection, Runnable editCustomItems,
		Runnable loadCustomList, FilterGroup group)
	{
		super(Component.literal(
			group == null ? "LootSorter filter categories" : group.title));
		this.back = back;
		this.editor = editor;
		this.selection = selection;
		this.editCustomItems = editCustomItems;
		this.loadCustomList = loadCustomList;
		this.group = group;
	}
	
	@Override
	public void init()
	{
		int x = width / 2 - 120;
		int rows = Math.max(4, Math.min(10, (height - 142) / 24));
		Button heading = addRenderableWidget(
			Button.builder(title, button -> {}).bounds(x, 12, 240, 20).build());
		heading.active = false;
		if(group == null)
			addGroupPage(x, rows);
		else
			addFilterPage(x, rows);
		if(group == null)
		{
			addRenderableWidget(Button
				.builder(Component.literal("Set custom item list"),
					button -> editCustomItems.run())
				.bounds(x, height - 82, 116, 20).build());
			addRenderableWidget(Button
				.builder(Component.literal("Load custom list"),
					button -> loadCustomList.run())
				.bounds(x + 124, height - 82, 116, 20).build());
		}
		addRenderableWidget(Button
			.builder(Component.literal("Back"),
				button -> minecraft.gui.setScreen(back))
			.bounds(x, height - 30, 74, 20).build());
	}
	
	private void addGroupPage(int x, int rows)
	{
		List<FilterGroup> entries = List.of(FilterGroup.values());
		int groupRows = Math.max(1, rows - 1);
		int start = page * rows;
		addRenderableWidget(
			Button
				.builder(
					Component
						.literal(BuiltInItemFilter.AUTOSORT.getDisplayName()),
					button -> select(BuiltInItemFilter.AUTOSORT))
				.bounds(x, 38, 240, 20).build());
		start = page * groupRows;
		for(int i = 0; i < groupRows && start + i < entries.size(); i++)
		{
			FilterGroup entry = entries.get(start + i);
			addRenderableWidget(Button
				.builder(Component.literal(entry.title + " >"),
					button -> minecraft.gui
						.setScreen(new LootSorterFilterMenuScreen(this, editor,
							selection, editCustomItems, loadCustomList, entry)))
				.bounds(x, 62 + i * 24, 240, 20).build());
		}
		addPageButtons(x, groupRows, entries.size());
	}
	
	private void addFilterPage(int x, int rows)
	{
		List<BuiltInItemFilter> entries = List.of(group.filters);
		int start = page * rows;
		for(int i = 0; i < rows && start + i < entries.size(); i++)
		{
			BuiltInItemFilter entry = entries.get(start + i);
			addRenderableWidget(Button
				.builder(Component.literal(entry.getDisplayName()),
					button -> select(entry))
				.bounds(x, 38 + i * 24, 240, 20).build());
		}
		addPageButtons(x, rows, entries.size());
	}
	
	private void addPageButtons(int x, int rows, int entryCount)
	{
		if(page > 0)
			addRenderableWidget(
				Button.builder(Component.literal("Previous"), button -> {
					page--;
					rebuildPage();
				}).bounds(x + 82, height - 30, 74, 20).build());
		if((page + 1) * rows < entryCount)
			addRenderableWidget(
				Button.builder(Component.literal("Next"), button -> {
					page++;
					rebuildPage();
				}).bounds(x + 166, height - 30, 74, 20).build());
	}
	
	private void select(ItemFilter filter)
	{
		selection.accept(filter);
		minecraft.gui.setScreen(editor);
	}
	
	private void rebuildPage()
	{
		clearWidgets();
		init();
	}
	
	@Override
	public void onClose()
	{
		minecraft.gui.setScreen(back);
	}
	
	private enum FilterGroup
	{
		ALL_AND_MISC("Everything and miscellaneous", BuiltInItemFilter.ALL,
			BuiltInItemFilter.MISCELLANEOUS),
		WEAPONS("Weapons", BuiltInItemFilter.WEAPONS,
			BuiltInItemFilter.WEAPONS_ENCHANTED,
			BuiltInItemFilter.WEAPONS_UNENCHANTED, BuiltInItemFilter.SWORDS,
			BuiltInItemFilter.WEAPON_AXES, BuiltInItemFilter.BOWS_AND_CROSSBOWS,
			BuiltInItemFilter.TRIDENTS, BuiltInItemFilter.MACES,
			BuiltInItemFilter.SPEARS),
		TOOLS("Tools", BuiltInItemFilter.TOOLS,
			BuiltInItemFilter.TOOLS_ENCHANTED,
			BuiltInItemFilter.TOOLS_UNENCHANTED, BuiltInItemFilter.PICKAXES,
			BuiltInItemFilter.TOOL_AXES, BuiltInItemFilter.SHOVELS,
			BuiltInItemFilter.HOES, BuiltInItemFilter.SHEARS,
			BuiltInItemFilter.FISHING_RODS),
		ARMOUR("Armour", BuiltInItemFilter.ARMOUR,
			BuiltInItemFilter.ARMOUR_ENCHANTED,
			BuiltInItemFilter.ARMOUR_UNENCHANTED, BuiltInItemFilter.HELMETS,
			BuiltInItemFilter.CHESTPLATES, BuiltInItemFilter.LEGGINGS,
			BuiltInItemFilter.BOOTS, BuiltInItemFilter.SHIELDS,
			BuiltInItemFilter.ELYTRA),
		CONSUMABLES("Food and potions", BuiltInItemFilter.FOOD,
			BuiltInItemFilter.POTIONS),
		ENCHANTED_BOOKS("Enchanted books", BuiltInItemFilter.ENCHANTED_BOOKS,
			BuiltInItemFilter.ENCHANTED_BOOK_LEVEL_1,
			BuiltInItemFilter.ENCHANTED_BOOK_LEVEL_2,
			BuiltInItemFilter.ENCHANTED_BOOK_LEVEL_3,
			BuiltInItemFilter.ENCHANTED_BOOK_LEVEL_4,
			BuiltInItemFilter.ENCHANTED_BOOK_LEVEL_5,
			BuiltInItemFilter.ENCHANTED_BOOK_TREASURE,
			BuiltInItemFilter.ENCHANTED_BOOK_CURSES),
		BUILDING("Building blocks", BuiltInItemFilter.BUILDING_BLOCKS,
			BuiltInItemFilter.STONE_LIKE, BuiltInItemFilter.WOOD,
			BuiltInItemFilter.GLASS, BuiltInItemFilter.CONCRETE_TERRACOTTA,
			BuiltInItemFilter.BRICKS, BuiltInItemFilter.SLABS_STAIRS_WALLS,
			BuiltInItemFilter.LIGHTING, BuiltInItemFilter.DECORATIVE_BLOCKS),
		NATURAL("Natural blocks", BuiltInItemFilter.NATURAL_BLOCKS,
			BuiltInItemFilter.DIRT_GRASS_MUD, BuiltInItemFilter.SAND_GRAVEL,
			BuiltInItemFilter.LOGS_LEAVES, BuiltInItemFilter.FLOWERS_PLANTS,
			BuiltInItemFilter.ICE_SNOW),
		WORKSTATIONS("Workstations", BuiltInItemFilter.WORKSTATIONS),
		DIMENSIONAL("Nether and End", BuiltInItemFilter.NETHER_BLOCKS,
			BuiltInItemFilter.NETHER_MATERIALS, BuiltInItemFilter.END_BLOCKS,
			BuiltInItemFilter.END_MATERIALS),
		MATERIALS("Ores and materials", BuiltInItemFilter.ORES_AND_MATERIALS,
			BuiltInItemFilter.ORES, BuiltInItemFilter.INGOTS_AND_GEMS,
			BuiltInItemFilter.CRAFTING_MATERIALS),
		REDSTONE("Redstone", BuiltInItemFilter.REDSTONE,
			BuiltInItemFilter.REDSTONE_COMPONENTS, BuiltInItemFilter.RAILS,
			BuiltInItemFilter.PISTONS, BuiltInItemFilter.STORAGE_COMPONENTS,
			BuiltInItemFilter.REDSTONE_BLOCKS_AND_DUST,
			BuiltInItemFilter.REDSTONE_SIGNAL,
			BuiltInItemFilter.REDSTONE_DEVICES),
		FARMING("Farming", BuiltInItemFilter.FARMING, BuiltInItemFilter.SEEDS,
			BuiltInItemFilter.CROPS, BuiltInItemFilter.FARMING_TOOLS,
			BuiltInItemFilter.ANIMAL_PRODUCTS),
		MOB_DROPS("Mob drops", BuiltInItemFilter.MOB_DROPS,
			BuiltInItemFilter.TOTEMS_OF_UNDYING,
			BuiltInItemFilter.MOB_DROPS_COMMON,
			BuiltInItemFilter.MOB_DROPS_RARE,
			BuiltInItemFilter.MOB_DROPS_NETHER),
		TRANSPORTATION("Transportation", BuiltInItemFilter.TRANSPORTATION,
			BuiltInItemFilter.BOATS_AND_RAFTS, BuiltInItemFilter.MINECARTS,
			BuiltInItemFilter.HORSE_EQUIPMENT,
			BuiltInItemFilter.TRANSPORTATION_MISC),
		UTILITY("Utility items", BuiltInItemFilter.UTILITY_ITEMS,
			BuiltInItemFilter.BUCKETS, BuiltInItemFilter.CONTAINERS,
			BuiltInItemFilter.UTILITY_TOOLS,
			BuiltInItemFilter.HORSE_AND_PLAYER_EQUIPMENT),
		DECORATIVE("Decorative items", BuiltInItemFilter.DECORATIVE_ITEMS,
			BuiltInItemFilter.DECORATIVE_BLOCKS, BuiltInItemFilter.LIGHTING,
			BuiltInItemFilter.FLOWERS_PLANTS,
			BuiltInItemFilter.CONCRETE_TERRACOTTA);
		
		private final String title;
		private final BuiltInItemFilter[] filters;
		
		FilterGroup(String title, BuiltInItemFilter... filters)
		{
			this.title = title;
			this.filters = filters;
		}
	}
}
