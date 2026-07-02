/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.keymap;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.Feature;
import net.wurstclient.WurstClient;
import net.wurstclient.keybinds.Keybind;

public final class KeyCommandEditScreen extends Screen
{
	private final Screen prevScreen;
	private final VisualKey visualKey;
	private final WurstBindBridge bindBridge;
	private final String currentCommand;
	
	private EditBox commandField;
	private String suggestion = "";
	
	public KeyCommandEditScreen(Screen prevScreen, VisualKey visualKey,
		String currentCommand)
	{
		super(Component.literal(""));
		this.prevScreen = prevScreen;
		this.visualKey = visualKey;
		this.currentCommand = currentCommand == null ? "" : currentCommand;
		this.bindBridge = new WurstBindBridge();
	}
	
	@Override
	public void init()
	{
		Font font = minecraft.font;
		int fieldW = Math.min(420, width - 40);
		int fieldX = (width - fieldW) / 2;
		
		commandField =
			new EditBox(font, fieldX, 76, fieldW, 20, Component.literal(""));
		commandField.setMaxLength(65536);
		commandField.setValue(currentCommand);
		addWidget(commandField);
		setFocused(commandField);
		commandField.setFocused(true);
		refreshSuggestion();
		
		addRenderableWidget(
			Button.builder(Component.literal("Save"), b -> save())
				.bounds(width / 2 - 154, height - 48, 100, 20).build());
		
		addRenderableWidget(
			Button.builder(Component.literal("Clear"), b -> clearAndClose())
				.bounds(width / 2 - 50, height - 48, 100, 20).build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Cancel"),
				b -> minecraft.gui.setScreen(prevScreen))
			.bounds(width / 2 + 54, height - 48, 100, 20).build());
	}
	
	private void save()
	{
		String command = commandField.getValue();
		if(command == null || command.trim().isEmpty())
			bindBridge.clearCommandForKey(visualKey.key());
		else
			bindBridge.setCommandForKey(visualKey.key(), command);
		
		minecraft.gui.setScreen(prevScreen);
	}
	
	private void clearAndClose()
	{
		bindBridge.clearCommandForKey(visualKey.key());
		minecraft.gui.setScreen(prevScreen);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		commandField.mouseClicked(context, doubleClick);
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public boolean charTyped(CharacterEvent event)
	{
		boolean handled = super.charTyped(event);
		refreshSuggestion();
		return handled;
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		switch(context.key())
		{
			case GLFW.GLFW_KEY_ENTER:
			save();
			return true;
			
			case GLFW.GLFW_KEY_TAB:
			acceptSuggestion();
			return true;
			
			case GLFW.GLFW_KEY_ESCAPE:
			minecraft.gui.setScreen(prevScreen);
			return true;
			
			default:
			boolean handled = super.keyPressed(context);
			refreshSuggestion();
			return handled;
		}
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		context.fillGradient(0, 0, width, height, 0xDF0C1118, 0xE7161D28);
		
		context.centeredText(font, "Editing " + visualKey.label(), width / 2,
			20, CommonColors.WHITE);
		
		context.centeredText(font,
			"Physical key: " + Keybind.getDisplayKey(visualKey.key().getName()),
			width / 2, 36, CommonColors.LIGHT_GRAY);
		
		context.text(font, "Command for this key", commandField.getX(),
			commandField.getY() - 12, CommonColors.LIGHT_GRAY);
		if(!suggestion.isBlank())
			context.text(font, suggestion, commandField.getX() + 6,
				commandField.getY() + 7, 0xFF9A9A9A);
		
		commandField.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public boolean shouldCloseOnEsc()
	{
		return false;
	}
	
	private void refreshSuggestion()
	{
		if(commandField == null)
			return;
		
		String current = commandField.getValue().substring(0,
			commandField.getCursorPosition());
		String token = extractToken(current);
		if(token.isBlank())
		{
			suggestion = "";
			commandField.setSuggestion("");
			return;
		}
		
		String completion = findCompletion(token);
		if(completion == null || completion.length() <= token.length())
		{
			suggestion = "";
			commandField.setSuggestion("");
			return;
		}
		
		suggestion = completion.substring(token.length());
		commandField.setSuggestion(suggestion);
	}
	
	private void acceptSuggestion()
	{
		if(suggestion.isBlank())
			return;
		
		commandField.setValue(commandField.getValue() + suggestion);
		commandField.setCursorPosition(commandField.getValue().length());
		suggestion = "";
		commandField.setSuggestion("");
	}
	
	private String extractToken(String text)
	{
		if(text == null || text.isEmpty())
			return "";
		
		int lastSep = Math.max(text.lastIndexOf(';'), text.lastIndexOf('\n'));
		String segment = lastSep >= 0 ? text.substring(lastSep + 1) : text;
		return segment.stripLeading();
	}
	
	private String findCompletion(String token)
	{
		if(token == null || token.isBlank())
			return null;
		
		String lower = token.toLowerCase(Locale.ROOT);
		Set<String> candidates = new LinkedHashSet<>();
		
		String prefix = ".";
		try
		{
			prefix = WurstClient.INSTANCE.getOtfs().commandPrefixOtf
				.getPrefixSetting().getSelected().toString();
		}catch(Throwable ignored)
		{}
		
		for(var cmd : WurstClient.INSTANCE.getCmds().getAllCmds())
		{
			String name = cmd.getName();
			if(name.startsWith("."))
				name = name.substring(1);
			candidates.add(name);
			candidates.add(prefix + name);
		}
		
		for(Feature feature : WurstClient.INSTANCE.getHax().getAllHax())
			candidates.add(feature.getName());
		
		for(Feature feature : WurstClient.INSTANCE.getOtfs().getAllOtfs())
			candidates.add(feature.getName());
		
		for(String candidate : candidates)
		{
			if(candidate.toLowerCase(Locale.ROOT).startsWith(lower))
				return candidate;
		}
		
		return null;
	}
}
