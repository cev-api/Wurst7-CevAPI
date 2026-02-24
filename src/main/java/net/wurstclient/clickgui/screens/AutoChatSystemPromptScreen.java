/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.hacks.AutoChatHack;

public final class AutoChatSystemPromptScreen extends Screen
{
	private final Screen prevScreen;
	private final AutoChatHack hack;
	
	private MultiLineEditBox promptField;
	
	public AutoChatSystemPromptScreen(Screen prevScreen, AutoChatHack hack)
	{
		super(Component.literal("AutoChat System Prompt"));
		this.prevScreen = prevScreen;
		this.hack = hack;
	}
	
	@Override
	public void init()
	{
		int editorWidth = Math.min(900, width - 40);
		int editorHeight = Math.max(180, height - 120);
		int x = (width - editorWidth) / 2;
		int y = 40;
		
		promptField = MultiLineEditBox.builder().setX(x).setY(y)
			.setPlaceholder(
				Component.literal("Enter AutoChat system prompt here..."))
			.build(font, editorWidth, editorHeight, Component.literal(""));
		promptField.setCharacterLimit(32767);
		promptField.setValue(hack.getSystemPromptEditorText());
		promptField.setFocused(true);
		addRenderableWidget(promptField);
		setFocused(promptField);
		
		int buttonY = y + editorHeight + 10;
		int smallButtonWidth = 120;
		int doneWidth = 150;
		int gap = 6;
		int totalWidth = doneWidth + smallButtonWidth * 2 + gap * 2;
		int startX = (width - totalWidth) / 2;
		
		addRenderableWidget(
			Button.builder(Component.literal("Done"), b -> done())
				.bounds(startX, buttonY, doneWidth, 20).build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Default"),
				b -> promptField
					.setValue(hack.getGeneratedDefaultSystemPrompt()))
			.bounds(startX + doneWidth + gap, buttonY, smallButtonWidth, 20)
			.build());
		
		addRenderableWidget(Button
			.builder(Component.literal("Cancel"),
				b -> minecraft.setScreen(prevScreen))
			.bounds(startX + doneWidth + gap + smallButtonWidth + gap, buttonY,
				smallButtonWidth, 20)
			.build());
	}
	
	private void done()
	{
		hack.setCustomSystemPrompt(promptField.getValue());
		minecraft.setScreen(prevScreen);
	}
	
	@Override
	public boolean keyPressed(KeyEvent event)
	{
		if(event.key() == GLFW.GLFW_KEY_ESCAPE)
		{
			minecraft.setScreen(prevScreen);
			return true;
		}
		
		if(event.key() == GLFW.GLFW_KEY_ENTER && event.hasControlDown())
		{
			done();
			return true;
		}
		
		return super.keyPressed(event);
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		context.drawCenteredString(font, "AutoChat System Prompt", width / 2,
			14, CommonColors.WHITE);
		context.drawCenteredString(font,
			"Ctrl+Enter to save. Use Default to load the generated prompt.",
			width / 2, 26, 0xAAAAAA);
		
		super.render(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
}
