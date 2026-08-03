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

/** Explicitly asks whether a run should trust a complete saved source scan. */
public final class LootSorterSourceScanChoiceScreen extends Screen
{
	private final Screen previous;
	private final String message;
	private final Runnable useSavedContents;
	private final Runnable rescanSources;
	
	public LootSorterSourceScanChoiceScreen(Screen previous, String message,
		Runnable useSavedContents, Runnable rescanSources)
	{
		super(Component.literal("LootSorter source scan"));
		this.previous = previous;
		this.message = message;
		this.useSavedContents = useSavedContents;
		this.rescanSources = rescanSources;
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
			.builder(Component.literal("Continue without re-checking"),
				button -> run(useSavedContents))
			.bounds(x, y + 26, 220, 20).build());
		addRenderableWidget(Button
			.builder(Component.literal("Re-check all sources"),
				button -> run(rescanSources))
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
		run(rescanSources);
	}
}
