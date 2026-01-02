/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.options;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.minecraft.util.Util;
import net.wurstclient.WurstClient;
import net.wurstclient.util.WurstColors;

public final class PresetManagerScreen extends Screen
{
	private final Screen prevScreen;
	
	private ListGui listGui;
	private Button loadButton;
	private Button deleteButton;
	private Button backButton;
	
	public PresetManagerScreen(Screen prevScreen)
	{
		super(Component.literal(""));
		this.prevScreen = prevScreen;
	}
	
	@Override
	public void init()
	{
		listGui = new ListGui(minecraft, this,
			WurstClient.INSTANCE.getPresetManager().listPresets());
		addWidget(listGui);
		
		addRenderableWidget(
			Button.builder(Component.literal("Open Folder"), b -> openFolder())
				.bounds(8, 8, 100, 20).build());
		
		addRenderableWidget(Button
			.builder(Component.literal("New Preset"),
				b -> minecraft.setScreen(
					new EnterProfileNameScreen(this, this::newPreset)))
			.bounds(width / 2 - 154, height - 48, 100, 20).build());
		
		loadButton = addRenderableWidget(
			Button.builder(Component.literal("Load"), b -> loadSelected())
				.bounds(width / 2 - 50, height - 48, 100, 20).build());
		
		deleteButton = addRenderableWidget(
			Button.builder(Component.literal("Delete"), b -> deleteSelected())
				.bounds(width / 2 + 54, height - 48, 100, 20).build());
		
		backButton = addRenderableWidget(Button
			.builder(Component.literal("Back"),
				b -> minecraft.setScreen(prevScreen))
			.bounds(width / 2 - 50, height - 24, 100, 20).build());
	}
	
	private void openFolder()
	{
		Util.getPlatform().openFile(WurstClient.INSTANCE.getPresetManager()
			.getPresetsFolder().toFile());
	}
	
	private void newPreset(String name)
	{
		if(name == null)
			return;
		
		String trimmed = name.trim();
		if(trimmed.isEmpty())
			return;
		
		try
		{
			WurstClient.INSTANCE.getPresetManager().savePreset(trimmed);
			minecraft.setScreen(this);
			
		}catch(IOException e)
		{
			throw new RuntimeException(e);
		}
	}
	
	private void loadSelected()
	{
		Path path = listGui.getSelectedPath();
		if(path == null)
		{
			minecraft.setScreen(prevScreen);
			return;
		}
		
		try
		{
			String name = "" + path.getFileName();
			WurstClient.INSTANCE.getPresetManager().loadPreset(name);
			minecraft.setScreen(prevScreen);
			
		}catch(IOException e)
		{
			e.printStackTrace();
			return;
		}
	}
	
	private void deleteSelected()
	{
		Path path = listGui.getSelectedPath();
		if(path == null)
			return;
		
		String name = "" + path.getFileName();
		minecraft.setScreen(new ConfirmScreen(confirmed -> {
			if(confirmed)
			{
				try
				{
					WurstClient.INSTANCE.getPresetManager().deletePreset(name);
					
				}catch(IOException e)
				{
					throw new RuntimeException(e);
				}
			}
			
			minecraft.setScreen(this);
		}, Component.literal("Delete preset '" + name + "'?"),
			Component.literal("This cannot be undone!")));
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		switch(context.key())
		{
			case GLFW.GLFW_KEY_ENTER:
			loadSelected();
			break;
			
			case GLFW.GLFW_KEY_DELETE:
			if(deleteButton.active)
				deleteSelected();
			break;
			
			case GLFW.GLFW_KEY_ESCAPE:
			backButton.onPress(context);
			break;
			
			default:
			break;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public void tick()
	{
		boolean selected = listGui.getSelected() != null;
		loadButton.active = selected;
		deleteButton.active = selected;
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		listGui.render(context, mouseX, mouseY, partialTicks);
		
		context.drawCenteredString(minecraft.font, "Presets", width / 2, 12,
			CommonColors.WHITE);
		
		for(Renderable drawable : renderables)
			drawable.render(context, mouseX, mouseY, partialTicks);
		
		if(loadButton.isHoveredOrFocused() && !loadButton.active)
			context.setComponentTooltipForNextFrame(font,
				Arrays.asList(
					Component.literal("You must first select a preset.")),
				mouseX, mouseY);
	}
	
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}
	
	private final class Entry
		extends ObjectSelectionList.Entry<PresetManagerScreen.Entry>
	{
		private final Path path;
		
		public Entry(Path path)
		{
			this.path = Objects.requireNonNull(path);
		}
		
		@Override
		public Component getNarration()
		{
			return Component.translatable("narrator.select",
				"Preset " + path.getFileName());
		}
		
		@Override
		public void renderContent(GuiGraphics context, int mouseX, int mouseY,
			boolean hovered, float tickDelta)
		{
			int x = getContentX();
			int y = getContentY();
			
			Font tr = minecraft.font;
			
			String name = "" + path.getFileName();
			context.drawString(tr, name, x + 28, y,
				WurstColors.VERY_LIGHT_GRAY);
			
			String relPath =
				"" + minecraft.gameDirectory.toPath().relativize(path);
			context.drawString(tr, relPath, x + 28, y + 9,
				CommonColors.LIGHT_GRAY);
		}
	}
	
	private final class ListGui
		extends ObjectSelectionList<PresetManagerScreen.Entry>
	{
		public ListGui(Minecraft mc, PresetManagerScreen screen,
			List<Path> list)
		{
			super(mc, screen.width, screen.height - 96, 36, 20);
			
			list.stream().map(PresetManagerScreen.Entry::new)
				.forEach(this::addEntry);
		}
		
		public Path getSelectedPath()
		{
			PresetManagerScreen.Entry selected = getSelected();
			return selected != null ? selected.path : null;
		}
	}
}
