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
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.hacks.ophack.NBTEditorHack;
import net.wurstclient.util.WurstColors;

public final class NBTPresetListScreen extends Screen
{
	private final Screen returnScreen;
	private final Consumer<String> loadCallback;
	private final NBTEditorHack hack;
	private ListGui list;
	private Button load;
	private Button delete;
	
	public NBTPresetListScreen(NBTEditorScreen editorScreen, NBTEditorHack hack)
	{
		this(editorScreen, hack, editorScreen::loadPresetText);
	}
	
	public NBTPresetListScreen(Screen returnScreen, NBTEditorHack hack,
		Consumer<String> loadCallback)
	{
		super(Component.literal("NBT Presets"));
		this.returnScreen = returnScreen;
		this.hack = hack;
		this.loadCallback = loadCallback;
	}
	
	@Override
	public void init()
	{
		list =
			new ListGui(minecraft, this, hack.presetNames().stream().toList());
		addWidget(list);
		load = addRenderableWidget(
			Button.builder(Component.literal("Load"), b -> load())
				.bounds(width / 2 - 156, height - 48, 100, 20).build());
		delete = addRenderableWidget(
			Button.builder(Component.literal("Delete"), b -> delete())
				.bounds(width / 2 - 50, height - 48, 100, 20).build());
		addRenderableWidget(
			Button.builder(Component.literal("Done"), b -> done())
				.bounds(width / 2 + 56, height - 48, 100, 20).build());
	}
	
	private String selected()
	{
		Entry entry = list.getSelected();
		return entry == null ? null : entry.name;
	}
	
	private void load()
	{
		String name = selected();
		if(name == null)
			return;
		String value = hack.loadPreset(name);
		if(value != null)
			loadCallback.accept(value);
		done();
	}
	
	private void delete()
	{
		String name = selected();
		if(name == null)
			return;
		hack.deletePreset(name);
		minecraft.gui.setScreen(
			new NBTPresetListScreen(returnScreen, hack, loadCallback));
	}
	
	private void done()
	{
		minecraft.gui.setScreen(returnScreen);
	}
	
	@Override
	public void tick()
	{
		load.active = delete.active = list.getSelected() != null;
	}
	
	@Override
	public boolean keyPressed(KeyEvent event)
	{
		if(event.key() == GLFW.GLFW_KEY_ESCAPE)
		{
			done();
			return true;
		}
		if(event.key() == GLFW.GLFW_KEY_ENTER)
		{
			load();
			return true;
		}
		return super.keyPressed(event);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		list.extractRenderState(context, mouseX, mouseY, partialTicks);
		context.centeredText(font, "NBT Presets", width / 2, 12,
			CommonColors.WHITE);
		for(var drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
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
	
	private final class Entry extends ObjectSelectionList.Entry<Entry>
	{
		private final String name;
		
		Entry(String name)
		{
			this.name = name;
		}
		
		@Override
		public Component getNarration()
		{
			return Component.literal("NBT preset " + name);
		}
		
		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX,
			int mouseY, boolean hovered, float tickDelta)
		{
			int x = getContentX(), y = getContentY();
			Font f = minecraft.font;
			context.text(f, name + ".snbt", x + 12, y,
				WurstColors.VERY_LIGHT_GRAY);
			context.text(f, "wurst/nbt-presets/" + name + ".snbt", x + 12,
				y + 9, CommonColors.LIGHT_GRAY);
		}
	}
	
	private final class ListGui extends ObjectSelectionList<Entry>
	{
		ListGui(Minecraft mc, NBTPresetListScreen screen, List<String> names)
		{
			super(mc, screen.width, screen.height - 96, 36, 20);
			names.stream().map(name -> NBTPresetListScreen.this.new Entry(name))
				.forEach(entry -> addEntry(entry));
		}
	}
}
