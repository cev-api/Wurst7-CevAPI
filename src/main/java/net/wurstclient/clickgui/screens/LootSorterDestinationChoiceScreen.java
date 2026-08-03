/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Chooses whether a retained source layout should keep its destinations. */
public final class LootSorterDestinationChoiceScreen extends Screen
{
	private final Screen previous;
	private final String message;
	private final Runnable useSavedDestinations;
	private final Runnable setNewDestinations;
	
	public LootSorterDestinationChoiceScreen(Screen previous, String message,
		Runnable useSavedDestinations, Runnable setNewDestinations)
	{
		super(Component.literal("LootSorter destinations"));
		this.previous = previous;
		this.message = message;
		this.useSavedDestinations = useSavedDestinations;
		this.setNewDestinations = setNewDestinations;
	}
	
	@Override
	public void init()
	{
		int x = width / 2 - 110;
		int y = height / 2 - 38;
		Button heading = addRenderableWidget(
			Button.builder(Component.literal(message), button -> {})
				.bounds(x, y, 220, 20).build());
		heading.active = false;
		addRenderableWidget(Button
			.builder(Component.literal("Use saved destinations"),
				button -> run(useSavedDestinations))
			.bounds(x, y + 26, 220, 20).build());
		addRenderableWidget(Button
			.builder(Component.literal("Set new destinations"),
				button -> run(setNewDestinations))
			.bounds(x, y + 52, 220, 20).build());
	}
	
	private void run(Runnable action)
	{
		action.run();
		if(minecraft.gui.screen() == this)
			minecraft.gui.setScreen(null);
	}
	
	@Override
	public void onClose()
	{
		// Closing the prompt must not begin an automated run unexpectedly.
		setNewDestinations.run();
		minecraft.gui.setScreen(previous);
	}
}
