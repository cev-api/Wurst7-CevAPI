/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.wurstclient.hacks.lootsorter.ItemFilter;

/** Collects a name only when the user explicitly saves a custom list. */
public final class LootSorterCustomPresetNameScreen extends Screen
{
	private final Screen previous;
	private final Function<String, ItemFilter> save;
	private final Consumer<ItemFilter> accepted;
	private EditBox name;
	
	public LootSorterCustomPresetNameScreen(Screen previous,
		Function<String, ItemFilter> save, Consumer<ItemFilter> accepted)
	{
		super(Component.literal("Save custom item list"));
		this.previous = previous;
		this.save = save;
		this.accepted = accepted;
	}
	
	@Override
	public void init()
	{
		int x = width / 2 - 110;
		int y = height / 2 - 42;
		Button heading = addRenderableWidget(
			Button.builder(title, button -> {}).bounds(x, y, 220, 20).build());
		heading.active = false;
		name = new EditBox(minecraft.font, x, y + 26, 220, 20,
			Component.literal("List name"));
		name.setMaxLength(64);
		name.setHint(Component.literal("List name"));
		name.setValue("Custom item list");
		addRenderableWidget(name);
		addRenderableWidget(
			Button.builder(Component.literal("Save"), button -> save())
				.bounds(x, y + 52, 106, 20).build());
		addRenderableWidget(Button
			.builder(Component.literal("Cancel"),
				button -> minecraft.setScreen(previous))
			.bounds(x + 114, y + 52, 106, 20).build());
		setInitialFocus(name);
	}
	
	@Override
	public void onClose()
	{
		minecraft.setScreen(previous);
	}
	
	private void save()
	{
		ItemFilter result = save.apply(name.getValue().trim());
		if(result != null)
		{
			accepted.accept(result);
			minecraft.setScreen(previous);
		}
	}
}
