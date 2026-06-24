/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.hacks.AutoChatHack;
import net.wurstclient.hacks.AutoChatPromptManager;

public final class AutoChatPromptScreen extends Screen
{
	private final Screen prevScreen;
	private final AutoChatHack hack;
	private final AutoChatPromptManager promptManager;
	private final boolean isSystemPromptMode;
	
	private MultiLineEditBox promptEditor;
	private EditBox fileNameInput;
	private Button saveBtn;
	private Button deleteBtn;
	private Button loadBtn;
	private Button cancelBtn;
	
	private List<String> availablePrompts = new ArrayList<>();
	private int selectedPromptIndex = -1;
	private int listScrollOffset;
	private boolean fileNameEdited;
	private String statusMessage = "";
	private int statusColor = 0xAAAAAA;
	
	private static final int LIST_WIDTH = 160;
	private static final int LIST_ITEM_HEIGHT = 14;
	
	public AutoChatPromptScreen(Screen prevScreen, AutoChatHack hack,
		boolean isSystemPromptMode)
	{
		super(Component.literal(isSystemPromptMode ? "AutoChat System Prompts"
			: "AutoChat Personas"));
		this.prevScreen = prevScreen;
		this.hack = hack;
		this.promptManager = new AutoChatPromptManager();
		this.isSystemPromptMode = isSystemPromptMode;
	}
	
	@Override
	public void init()
	{
		refreshPromptList();
		
		int leftPanelX = 10;
		int rightPanelX = leftPanelX + LIST_WIDTH + 12;
		int editorWidth = Math.max(300, width - rightPanelX - 10);
		int topY = 38;
		int editorHeight = Math.max(120, height - topY - 80);
		int buttonY = topY + editorHeight + 8;
		
		// File name input
		fileNameInput = new EditBox(font, rightPanelX, topY - 18,
			Math.min(200, editorWidth), 16, Component.empty());
		fileNameInput.setMaxLength(60);
		fileNameInput.setHint(Component.literal("File name..."));
		addRenderableWidget(fileNameInput);
		
		// Prompt editor
		promptEditor = MultiLineEditBox.builder().setX(rightPanelX).setY(topY)
			.setPlaceholder(Component.literal(isSystemPromptMode
				? "Enter system prompt here..." : "Enter persona here..."))
			.build(font, editorWidth, editorHeight, Component.literal(""));
		promptEditor.setCharacterLimit(32767);
		addRenderableWidget(promptEditor);
		
		// Buttons
		int btnW = 70;
		int gap = 4;
		
		saveBtn = Button.builder(Component.literal("Save"), b -> savePrompt())
			.bounds(rightPanelX, buttonY, btnW, 20).build();
		addRenderableWidget(saveBtn);
		
		loadBtn =
			Button.builder(Component.literal("Load"), b -> loadSelectedPrompt())
				.bounds(rightPanelX + btnW + gap, buttonY, btnW, 20).build();
		addRenderableWidget(loadBtn);
		
		deleteBtn = Button
			.builder(Component.literal("Delete"), b -> deleteSelectedPrompt())
			.bounds(rightPanelX + (btnW + gap) * 2, buttonY, btnW, 20).build();
		addRenderableWidget(deleteBtn);
		
		int doneW = 100;
		cancelBtn = Button
			.builder(Component.literal("Done"),
				b -> minecraft.setScreen(prevScreen))
			.bounds(rightPanelX + editorWidth - doneW, buttonY, doneW, 20)
			.build();
		addRenderableWidget(cancelBtn);
		
		setFocused(promptEditor);
	}
	
	private void refreshPromptList()
	{
		Map<String, String> prompts =
			isSystemPromptMode ? promptManager.listSystemPrompts()
				: promptManager.listChatPrompts();
		availablePrompts = new ArrayList<>(prompts.keySet());
		if(selectedPromptIndex >= availablePrompts.size())
			selectedPromptIndex =
				availablePrompts.isEmpty() ? -1 : availablePrompts.size() - 1;
	}
	
	private void loadSelectedPrompt()
	{
		if(selectedPromptIndex < 0
			|| selectedPromptIndex >= availablePrompts.size())
			return;
		
		String name = availablePrompts.get(selectedPromptIndex);
		String content =
			isSystemPromptMode ? promptManager.loadSystemPrompt(name)
				: promptManager.loadChatPrompt(name);
		promptEditor.setValue(content);
		fileNameInput.setValue(name);
		fileNameEdited = false;
	}
	
	private void savePrompt()
	{
		String name = fileNameInput.getValue().trim();
		if(name.isEmpty())
		{
			statusMessage = "Enter a file name before saving.";
			statusColor = 0xFFFF5555;
			setFocused(fileNameInput);
			fileNameInput.setFocused(true);
			return;
		}
		
		name = AutoChatPromptManager.sanitizeFileName(name);
		fileNameInput.setValue(name);
		
		String content = promptEditor.getValue();
		if(isSystemPromptMode)
			promptManager.saveSystemPrompt(name, content);
		else
			promptManager.saveChatPrompt(name, content);
		
		refreshPromptList();
		fileNameEdited = false;
		
		// Select the saved file
		selectedPromptIndex = availablePrompts.indexOf(name);
		statusMessage = "Saved " + name + ".txt";
		statusColor = 0xFF55FF55;
	}
	
	private void deleteSelectedPrompt()
	{
		if(selectedPromptIndex < 0
			|| selectedPromptIndex >= availablePrompts.size())
			return;
		
		String name = availablePrompts.get(selectedPromptIndex);
		if(isSystemPromptMode)
			promptManager.deleteSystemPrompt(name);
		else
			promptManager.deleteChatPrompt(name);
		
		promptEditor.setValue("");
		fileNameInput.setValue("");
		refreshPromptList();
		statusMessage = "Deleted " + name + ".txt";
		statusColor = 0xFFAAAAAA;
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		// Title
		context.drawCenteredString(font, isSystemPromptMode
			? "AutoChat System Prompts" : "AutoChat Personas", width / 2, 8,
			CommonColors.WHITE);
		
		String hint =
			"Ctrl+S to save  |  Ctrl+Enter to confirm  |  Esc to close";
		context.drawCenteredString(font, hint, width / 2, 20, 0xAAAAAA);
		
		if(!statusMessage.isEmpty())
			context.drawCenteredString(font, statusMessage, width / 2, 30,
				statusColor);
		
		// Draw prompt list
		int leftPanelX = 10;
		int listY = 38;
		int listHeight = Math.max(120, height - listY - 80);
		
		context.fill(leftPanelX, listY, leftPanelX + LIST_WIDTH,
			listY + listHeight, 0x88000000);
		
		int maxVisible = listHeight / LIST_ITEM_HEIGHT;
		int totalPrompts = availablePrompts.size();
		
		// Clamp scroll
		int maxScroll = Math.max(0, totalPrompts - maxVisible);
		if(listScrollOffset > maxScroll)
			listScrollOffset = maxScroll;
		if(listScrollOffset < 0)
			listScrollOffset = 0;
		
		for(int i = 0; i < maxVisible; i++)
		{
			int idx = listScrollOffset + i;
			if(idx >= totalPrompts)
				break;
			
			String name = availablePrompts.get(idx);
			int itemY = listY + 1 + i * LIST_ITEM_HEIGHT;
			int color = idx == selectedPromptIndex ? 0xFF00FF00 : 0xFFCCCCCC;
			int bgColor = idx == selectedPromptIndex ? 0x44FFFFFF : 0x00000000;
			
			if(idx == selectedPromptIndex)
				context.fill(leftPanelX + 1, itemY, leftPanelX + LIST_WIDTH - 1,
					itemY + LIST_ITEM_HEIGHT - 1, bgColor);
			
			// Check hover for click detection
			boolean hovered =
				mouseX >= leftPanelX + 1 && mouseX < leftPanelX + LIST_WIDTH - 1
					&& mouseY >= itemY && mouseY < itemY + LIST_ITEM_HEIGHT;
			
			if(hovered)
				context.fill(leftPanelX + 1, itemY, leftPanelX + LIST_WIDTH - 1,
					itemY + LIST_ITEM_HEIGHT - 1, 0x33FFFFFF);
			
			String display =
				name.length() > 22 ? name.substring(0, 21) + "..." : name;
			context.drawCenteredString(font, display,
				leftPanelX + LIST_WIDTH / 2, itemY + 3, color);
		}
		
		super.render(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
	{
		double mouseX = event.x();
		double mouseY = event.y();
		int button = event.button();
		if(button == 0 && mouseX >= 10 && mouseX < 10 + LIST_WIDTH
			&& mouseY >= 38)
		{
			int maxVisible = Math.min(availablePrompts.size(),
				(Math.max(120, height - 38 - 80)) / LIST_ITEM_HEIGHT);
			for(int i = 0; i < maxVisible; i++)
			{
				int idx = listScrollOffset + i;
				if(idx >= availablePrompts.size())
					break;
				
				int itemY = 38 + 1 + i * LIST_ITEM_HEIGHT;
				if(mouseY >= itemY && mouseY < itemY + LIST_ITEM_HEIGHT)
				{
					selectedPromptIndex = idx;
					return true;
				}
			}
		}
		
		return super.mouseClicked(event, doubleClick);
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY,
		double horizontalAmount, double verticalAmount)
	{
		if(mouseX >= 10 && mouseX < 10 + LIST_WIDTH && mouseY >= 38)
		{
			listScrollOffset -= (int)verticalAmount;
			int maxVisible2 = Math.min(availablePrompts.size(),
				(Math.max(120, height - 38 - 80)) / LIST_ITEM_HEIGHT);
			int maxScroll2 = Math.max(0, availablePrompts.size() - maxVisible2);
			listScrollOffset = Math.clamp(listScrollOffset, 0, maxScroll2);
			return true;
		}
		
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount,
			verticalAmount);
	}
	
	@Override
	public boolean keyPressed(KeyEvent event)
	{
		if(event.key() == GLFW.GLFW_KEY_ESCAPE)
		{
			minecraft.setScreen(prevScreen);
			return true;
		}
		
		if(event.key() == GLFW.GLFW_KEY_S && event.hasControlDown())
		{
			savePrompt();
			return true;
		}
		
		if(event.key() == GLFW.GLFW_KEY_ENTER && event.hasControlDown())
		{
			minecraft.setScreen(prevScreen);
			return true;
		}
		
		return super.keyPressed(event);
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
}
