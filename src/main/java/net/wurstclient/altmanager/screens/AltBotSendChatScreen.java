/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager.screens;

import java.util.List;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.WurstClient;
import net.wurstclient.altbot.AltBotManager;
import net.wurstclient.altmanager.TokenAlt;

/**
 * Lets the user send a chat message or command as a selected bot account.
 * Normal text is sent as chat; text starting with "/" is sent as a command.
 */
public final class AltBotSendChatScreen extends Screen
{
	private final Screen prevScreen;
	private final TokenAlt alt;
	
	private EditBox messageBox;
	private Button sendButton;
	private List<String> history = List.of();
	private String feedback = "";
	private boolean feedbackError;
	private int errorTimer;
	private int historyIndex = -1;
	private String historyDraft = "";
	
	public AltBotSendChatScreen(Screen prevScreen, TokenAlt alt)
	{
		super(Component.literal("Send Chat as " + alt.getDisplayName()));
		this.prevScreen = prevScreen;
		this.alt = alt;
	}
	
	@Override
	protected void init()
	{
		messageBox = new EditBox(font, width / 2 - 150, height / 2 - 40, 300,
			20, Component.literal(""));
		messageBox.setMaxLength(256);
		messageBox
			.setHint(Component.literal("Message... /command for commands"));
		addWidget(messageBox);
		setFocused(messageBox);
		
		addRenderableWidget(sendButton =
			Button.builder(Component.literal("Send"), b -> sendMessage())
				.bounds(width / 2 - 100, height / 2 - 12, 200, 20).build());
		
		addRenderableWidget(
			Button.builder(Component.literal("Back"), b -> onClose())
				.bounds(width / 2 - 100, height / 2 + 14, 200, 20).build());
		
		refreshHistory();
		updateSendButton();
	}
	
	private void sendMessage()
	{
		String text = messageBox.getValue().trim();
		if(text.isEmpty())
			return;
		
		AltBotManager manager = WurstClient.INSTANCE.getAltBotManager();
		if(!manager.isBotReady(alt))
		{
			feedbackError = true;
			feedback = "Bot \"" + alt.getDisplayName()
				+ "\" is not in the play state yet.";
			errorTimer = 10;
			return;
		}
		
		boolean sent = manager.sendChat(alt, text);
		if(sent)
		{
			feedbackError = false;
			feedback = "Sent as " + alt.getDisplayName() + ".";
			errorTimer = 10;
			messageBox.setValue("");
			historyIndex = -1;
			historyDraft = "";
			refreshHistory();
		}else
		{
			feedbackError = true;
			feedback =
				"Failed to send through \"" + alt.getDisplayName() + "\".";
			errorTimer = 10;
		}
	}
	
	private void refreshHistory()
	{
		AltBotManager manager = WurstClient.INSTANCE.getAltBotManager();
		var session = manager.getSession(alt);
		history = session == null ? List.of() : session.getChatHistory();
	}
	
	private void updateSendButton()
	{
		if(sendButton == null)
			return;
		boolean ready = WurstClient.INSTANCE.getAltBotManager().isBotReady(alt);
		sendButton.active = ready && !messageBox.getValue().isBlank();
		sendButton
			.setMessage(Component.literal(ready ? "Send" : "Not connected"));
	}
	
	@Override
	public void tick()
	{
		updateSendButton();
		if(errorTimer > 0)
			errorTimer--;
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == GLFW.GLFW_KEY_UP && messageBox.isFocused()
			&& !history.isEmpty())
		{
			if(historyIndex == -1)
				historyDraft = messageBox.getValue();
			if(historyIndex < history.size() - 1)
			{
				historyIndex++;
				messageBox
					.setValue(history.get(history.size() - 1 - historyIndex));
			}
			return true;
		}
		
		if(context.key() == GLFW.GLFW_KEY_DOWN && messageBox.isFocused()
			&& historyIndex >= 0)
		{
			historyIndex--;
			if(historyIndex < 0)
			{
				messageBox.setValue(historyDraft);
				historyDraft = "";
			}else
				messageBox
					.setValue(history.get(history.size() - 1 - historyIndex));
			return true;
		}
		
		return super.keyPressed(context);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		if(context.button() == GLFW.GLFW_MOUSE_BUTTON_4)
		{
			onClose();
			return true;
		}
		
		return super.mouseClicked(context, doubleClick);
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		context.centeredText(font, "Sending as: \u00a7a" + alt.getDisplayName(),
			width / 2, height / 2 - 70, CommonColors.WHITE);
		
		if(errorTimer > 0 && !feedback.isEmpty())
			context.centeredText(font, feedback, width / 2, height / 2 + 42,
				feedbackError ? CommonColors.RED : CommonColors.GREEN);
		
		// recent sent history
		int y = height / 2 + 60;
		int shown = Math.min(history.size(), 6);
		for(int i = history.size() - shown; i < history.size(); i++)
		{
			String line = history.get(i);
			context.text(font, "\u00a77"
				+ (line.length() > 60 ? line.substring(0, 60) + "..." : line),
				width / 2 - 150, y, CommonColors.LIGHT_GRAY);
			y += 9;
		}
		
		// addWidget() does not put the EditBox in the renderables list, so it
		// must be drawn explicitly (same as EditTokenAltScreen does).
		messageBox.extractRenderState(context, mouseX, mouseY, partialTicks);
		
		for(Renderable drawable : renderables)
			drawable.extractRenderState(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public void onClose()
	{
		minecraft.gui.setScreen(prevScreen);
	}
}
