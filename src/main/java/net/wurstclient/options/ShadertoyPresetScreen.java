/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.options;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.util.ShadertoyBackgroundManager;
import net.wurstclient.util.WurstColors;

public final class ShadertoyPresetScreen extends Screen
{
	private final CustomShadertoyBackgroundScreen prevScreen;
	
	private ListGui listGui;
	private Button loadButton;
	private Button deleteButton;
	private String status = "";
	private boolean loading;
	
	public ShadertoyPresetScreen(CustomShadertoyBackgroundScreen prevScreen)
	{
		super(Component.literal("Shadertoy Presets"));
		this.prevScreen = prevScreen;
	}
	
	@Override
	protected void init()
	{
		listGui = new ListGui(minecraft, this,
			ShadertoyBackgroundManager.listPresets());
		addWidget(listGui);
		
		loadButton = addRenderableWidget(
			Button.builder(Component.literal("Load"), b -> loadSelected())
				.bounds(width / 2 - 154, height - 28, 100, 20).build());
		
		deleteButton = addRenderableWidget(
			Button.builder(Component.literal("Delete"), b -> deleteSelected())
				.bounds(width / 2 - 50, height - 28, 100, 20).build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Back"),
				b -> minecraft.gui.setScreen(prevScreen))
			.bounds(width / 2 + 54, height - 28, 100, 20).build());
		
		status = listGui.children().isEmpty() ? "No saved presets yet."
			: "Select a preset to load it.";
	}
	
	private void loadSelected()
	{
		Path path = listGui.getSelectedPath();
		if(path == null || loading)
			return;
		
		loading = true;
		status = "Loading preset...";
		Thread worker = new Thread(() -> {
			String result;
			try
			{
				result = ShadertoyBackgroundManager.loadPreset(path);
			}catch(Exception e)
			{
				result = "Failed: " + e.getMessage();
			}
			
			String finalResult = result;
			minecraft.execute(() -> {
				loading = false;
				if(finalResult.startsWith("Failed"))
				{
					status = finalResult;
					return;
				}
				
				prevScreen.reloadFromDisk(finalResult);
				minecraft.gui.setScreen(prevScreen);
			});
		}, "Wurst Shadertoy Preset Loader");
		worker.setDaemon(true);
		worker.start();
	}
	
	private void deleteSelected()
	{
		Path path = listGui.getSelectedPath();
		if(path == null || loading)
			return;
		
		String name = ShadertoyBackgroundManager.getPresetDisplayName(path);
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if(!confirmed)
			{
				minecraft.gui.setScreen(this);
				return;
			}
			
			try
			{
				status = ShadertoyBackgroundManager.deletePreset(path);
				minecraft.gui.setScreen(new ShadertoyPresetScreen(prevScreen));
			}catch(Exception e)
			{
				status = "Failed: " + e.getMessage();
				minecraft.gui.setScreen(this);
			}
		}, Component.literal("Delete preset '" + name + "'?"),
			Component.literal("This cannot be undone.")));
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
			deleteSelected();
			break;
			
			case GLFW.GLFW_KEY_ESCAPE:
			minecraft.gui.setScreen(prevScreen);
			break;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public void tick()
	{
		boolean selected = listGui.getSelected() != null;
		loadButton.active = selected && !loading;
		deleteButton.active = selected && !loading;
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		listGui.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		context.centeredText(font, "Load Shadertoy Preset", width / 2, 12,
			CommonColors.WHITE);
		
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		int statusColor = status.startsWith("Failed") ? WurstColors.LIGHT_RED
			: CommonColors.LIGHT_GRAY;
		context.centeredText(font, status, width / 2, height - 42, statusColor);
		
		if(loadButton.isHoveredOrFocused() && !loadButton.active && !loading)
			context.setComponentTooltipForNextFrame(font,
				Arrays.asList(Component.literal("Select a preset first.")),
				mouseX, mouseY);
		
		if(deleteButton.isHoveredOrFocused() && !deleteButton.active
			&& !loading)
			context.setComponentTooltipForNextFrame(font,
				Arrays.asList(Component.literal("Select a preset first.")),
				mouseX, mouseY);
	}
	
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}
	
	private final class Entry
		extends ObjectSelectionList.Entry<ShadertoyPresetScreen.Entry>
	{
		private final Path path;
		
		private Entry(Path path)
		{
			this.path = Objects.requireNonNull(path);
		}
		
		@Override
		public Component getNarration()
		{
			return Component.translatable("narrator.select", "Preset "
				+ ShadertoyBackgroundManager.getPresetDisplayName(path));
		}
		
		@Override
		public void extractContent(GuiGraphicsExtractor context, int mouseX,
			int mouseY, boolean hovered, float tickDelta)
		{
			int x = getContentX();
			int y = getContentY();
			Font tr = minecraft.font;
			
			String name = ShadertoyBackgroundManager.getPresetDisplayName(path);
			context.text(tr, name, x + 8, y + 1, WurstColors.VERY_LIGHT_GRAY);
			
			String relPath =
				"" + minecraft.gameDirectory.toPath().relativize(path);
			context.text(tr, relPath, x + 8, y + 11, CommonColors.LIGHT_GRAY);
		}
	}
	
	private final class ListGui
		extends ObjectSelectionList<ShadertoyPresetScreen.Entry>
	{
		private ListGui(Minecraft mc, Screen screen, List<Path> list)
		{
			super(mc, screen.width, screen.height - 88, 32, 24);
			list.stream().map(ShadertoyPresetScreen.Entry::new)
				.forEach(this::addEntry);
		}
		
		private Path getSelectedPath()
		{
			ShadertoyPresetScreen.Entry selected = getSelected();
			return selected != null ? selected.path : null;
		}
	}
}
