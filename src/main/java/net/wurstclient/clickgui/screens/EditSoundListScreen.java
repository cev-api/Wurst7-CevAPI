/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.CommonColors;
import net.wurstclient.clickgui.widgets.MultiSelectEntryListWidget;
import net.wurstclient.settings.SoundListSetting;

public final class EditSoundListScreen extends Screen
{
	private final Screen previous;
	private final SoundListSetting setting;
	private final Set<Identifier> pending = new LinkedHashSet<>();
	private SoundList list;
	private EditBox search;
	private Button toggle;
	
	public EditSoundListScreen(Screen previous, SoundListSetting setting)
	{
		super(Component.literal(""));
		this.previous = previous;
		this.setting = setting;
	}
	
	@Override
	public void init()
	{
		pending.clear();
		pending.addAll(setting.getMutedSounds());
		search = new EditBox(minecraft.font, width / 2 - 150, 30, 300, 20,
			Component.literal("Search"));
		search.setMaxLength(256);
		search.setHint(Component.literal("Search sound IDs"));
		search.setResponder(value -> reload());
		addRenderableWidget(search);
		list = new SoundList(minecraft, this);
		addWidget(list);
		reload();
		if(!list.children().isEmpty())
			list.setSelection(List.of(list.children().get(0).selectionKey()),
				0);
		toggle = Button
			.builder(Component.literal(toggleText()), b -> toggleSelected())
			.bounds(width / 2 - 155, height - 28, 120, 20).build();
		addRenderableWidget(toggle);
		addRenderableWidget(
			Button.builder(Component.literal("Apply"), b -> apply())
				.bounds(width / 2 - 30, height - 28, 65, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
			apply();
			minecraft.gui.setScreen(previous);
		}).bounds(width / 2 + 40, height - 28, 65, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
			pending.clear();
			updateButton();
		}).bounds(width - 130, 8, 120, 20).build());
	}
	
	private void reload()
	{
		if(list == null)
			return;
		String query = search == null ? ""
			: search.getValue().trim().toLowerCase(Locale.ROOT);
		list.reloadEntries();
		for(Identifier id : setting.getAvailableSounds())
			if(query.isEmpty()
				|| id.toString().toLowerCase(Locale.ROOT).contains(query))
				list.addSound(new Entry(list, id));
		if(list.getSelected() == null && !list.children().isEmpty())
			list.setSelection(List.of(list.children().get(0).selectionKey()),
				0);
		updateButton();
	}
	
	private void toggleSelected()
	{
		Entry selected = list.getSelected();
		if(selected == null)
			return;
		if(!pending.add(selected.id))
			pending.remove(selected.id);
		updateButton();
	}
	
	private void apply()
	{
		setting.setMuted(new LinkedHashSet<>(pending));
	}
	
	private String toggleText()
	{
		Entry selected = list == null ? null : list.getSelected();
		return selected != null && pending.contains(selected.id) ? "Unmute"
			: "Mute";
	}
	
	private void updateButton()
	{
		if(toggle != null)
			toggle.setMessage(Component.literal(toggleText()));
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		list.extractRenderState(context, mouseX, mouseY, partialTicks);
		context.centeredText(minecraft.font,
			"Muted sounds (" + pending.size() + ")", width / 2, 12,
			CommonColors.WHITE);
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == GLFW.GLFW_KEY_ESCAPE)
		{
			minecraft.gui.setScreen(previous);
			return true;
		}
		if(context.key() == GLFW.GLFW_KEY_ENTER && toggle != null)
		{
			toggle.onPress(context);
			return true;
		}
		return super.keyPressed(context);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
	
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}
	
	private final class Entry extends MultiSelectEntryListWidget.Entry<Entry>
	{
		private final Identifier id;
		
		private Entry(SoundList parent, Identifier id)
		{
			super(parent);
			this.id = Objects.requireNonNull(id);
		}
		
		@Override
		public Component getNarration()
		{
			return Component.literal(id.toString());
		}
		
		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX,
			int mouseY, boolean hovered, float tickDelta)
		{
			int x = getContentX(), y = getContentY();
			boolean muted = pending.contains(id);
			context.text(minecraft.font, muted ? "[x]" : "[ ]", x + 4, y + 5,
				muted ? CommonColors.GREEN : CommonColors.LIGHT_GRAY, false);
			context.text(minecraft.font, id.toString(), x + 28, y + 5,
				CommonColors.WHITE, false);
		}
		
		@Override
		public String selectionKey()
		{
			return id.toString();
		}
	}
	
	private final class SoundList
		extends MultiSelectEntryListWidget<EditSoundListScreen.Entry>
	{
		private SoundList(Minecraft minecraft, EditSoundListScreen screen)
		{
			super(minecraft, screen.width, screen.height - 96, 56, 24);
		}
		
		private void reloadEntries()
		{
			clearEntries();
		}
		
		private void addSound(EditSoundListScreen.Entry entry)
		{
			addEntry(entry);
		}
		
		@Override
		public int getRowWidth()
		{
			return Math.min(1000, width - 160);
		}
		
		@Override
		protected String getSelectionKey(EditSoundListScreen.Entry entry)
		{
			return entry.selectionKey();
		}
	}
}
