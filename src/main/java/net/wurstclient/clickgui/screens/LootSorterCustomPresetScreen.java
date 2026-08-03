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
import net.wurstclient.hacks.lootsorter.CustomItemFilterPreset;
import net.wurstclient.hacks.lootsorter.CustomPresetStore;

/** Selects a saved custom list without forcing the user through settings. */
public final class LootSorterCustomPresetScreen extends Screen
{
	private final Screen previous;
	private final Consumer<CustomItemFilterPreset> select;
	private int page;
	
	public LootSorterCustomPresetScreen(Screen previous,
		Consumer<CustomItemFilterPreset> select)
	{
		super(Component.literal("Saved custom item lists"));
		this.previous = previous;
		this.select = select;
	}
	
	@Override
	public void init()
	{
		int x = width / 2 - 120;
		int rows = Math.max(4, Math.min(12, (height - 92) / 24));
		Button heading = addRenderableWidget(
			Button.builder(title, button -> {}).bounds(x, 12, 240, 20).build());
		heading.active = false;
		List<CustomItemFilterPreset> presets = new CustomPresetStore().load();
		if(presets.isEmpty())
		{
			Button empty = addRenderableWidget(
				Button.builder(Component.literal("No saved custom item lists"),
					button -> {}).bounds(x, 38, 240, 20).build());
			empty.active = false;
		}
		int start = page * rows;
		for(int i = 0; i < rows && start + i < presets.size(); i++)
		{
			CustomItemFilterPreset preset = presets.get(start + i);
			addRenderableWidget(
				Button.builder(Component.literal(preset.getName()), button -> {
					select.accept(preset);
					minecraft.gui.setScreen(previous);
				}).bounds(x, 38 + i * 24, 240, 20).build());
		}
		if(page > 0)
			addRenderableWidget(
				Button.builder(Component.literal("Previous"), button -> {
					page--;
					rebuildPage();
				}).bounds(x + 82, height - 30, 74, 20).build());
		if((page + 1) * rows < presets.size())
			addRenderableWidget(
				Button.builder(Component.literal("Next"), button -> {
					page++;
					rebuildPage();
				}).bounds(x + 166, height - 30, 74, 20).build());
		addRenderableWidget(Button
			.builder(Component.literal("Back"),
				button -> minecraft.gui.setScreen(previous))
			.bounds(x, height - 30, 74, 20).build());
	}
	
	@Override
	public void onClose()
	{
		minecraft.gui.setScreen(previous);
	}
	
	private void rebuildPage()
	{
		clearWidgets();
		init();
	}
}
