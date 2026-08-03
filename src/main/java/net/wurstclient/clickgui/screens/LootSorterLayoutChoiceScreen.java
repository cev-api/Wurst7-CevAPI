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

/** Explicit choice shown before a retained layout may be used again. */
public final class LootSorterLayoutChoiceScreen extends Screen
{
	private final Screen previous;
	private final String message;
	private final Runnable continueLayout;
	private final Runnable startNew;
	
	public LootSorterLayoutChoiceScreen(Screen previous, String message,
		Runnable continueLayout, Runnable startNew)
	{
		super(Component.literal("LootSorter layout"));
		this.previous = previous;
		this.message = message;
		this.continueLayout = continueLayout;
		this.startNew = startNew;
	}
	
	@Override
	public void init()
	{
		int x = width / 2 - 100;
		int y = height / 2 - 38;
		Button heading = addRenderableWidget(
			Button.builder(Component.literal(message), button -> {})
				.bounds(x, y, 200, 20).build());
		heading.active = false;
		addRenderableWidget(Button
			.builder(Component.literal("Continue previous layout"), button -> {
				continueLayout.run();
				if(minecraft.gui.screen() == this)
					minecraft.gui.setScreen(previous);
			}).bounds(x, y + 26, 200, 20).build());
		addRenderableWidget(
			Button.builder(Component.literal("Start new selection"), button -> {
				startNew.run();
				if(minecraft.gui.screen() == this)
					minecraft.gui.setScreen(previous);
			}).bounds(x, y + 52, 200, 20).build());
	}
	
	@Override
	public void onClose()
	{
		startNew.run();
		minecraft.gui.setScreen(previous);
	}
}
