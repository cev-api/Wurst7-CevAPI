/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager.screens;

import java.time.Duration;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.wurstclient.WurstClient;
import net.wurstclient.altbot.AltBotManager;
import net.wurstclient.altbot.AltBotState;
import net.wurstclient.altbot.BotState;
import net.wurstclient.altmanager.TokenAlt;

/**
 * Shows the live state of one bot account and offers disconnect, switch and
 * messaging actions.
 */
public final class AltBotDetailsScreen extends Screen
{
	private final Screen prevScreen;
	private final TokenAlt alt;
	
	private Button disconnectButton;
	private Button switchButton;
	private Button sendChatButton;
	
	public AltBotDetailsScreen(Screen prevScreen, TokenAlt alt)
	{
		super(Component.literal("Bot Details"));
		this.prevScreen = prevScreen;
		this.alt = alt;
	}
	
	@Override
	protected void init()
	{
		addRenderableWidget(disconnectButton = Button
			.builder(Component.literal("Disconnect Bot"),
				b -> pressDisconnect())
			.bounds(width / 2 - 154, height - 52, 100, 20).build());
		
		addRenderableWidget(switchButton =
			Button.builder(Component.literal("Switch To"), b -> pressSwitch())
				.bounds(width / 2 - 50, height - 52, 100, 20).build());
		
		addRenderableWidget(sendChatButton =
			Button.builder(Component.literal("Send Chat"), b -> pressSendChat())
				.bounds(width / 2 + 54, height - 52, 100, 20).build());
		
		addRenderableWidget(
			Button.builder(Component.literal("Back"), b -> onClose())
				.bounds(width / 2 - 100, height - 28, 200, 20).build());
		
		updateButtons();
	}
	
	private void pressDisconnect()
	{
		WurstClient.INSTANCE.getAltBotManager().disconnectBot(alt);
	}
	
	private void pressSwitch()
	{
		WurstClient.INSTANCE.getAltSwitchController().startSwitch(alt);
	}
	
	private void pressSendChat()
	{
		minecraft.setScreen(new AltBotSendChatScreen(this, alt));
	}
	
	private void updateButtons()
	{
		AltBotManager manager = WurstClient.INSTANCE.getAltBotManager();
		AltBotState state = manager.getState(alt);
		
		boolean connected = state.getState() == BotState.PLAY
			|| state.getState() == BotState.LOGIN
			|| state.getState() == BotState.CONFIGURING
			|| state.getState() == BotState.CONNECTING
			|| state.getState() == BotState.FAILED;
		disconnectButton.active = connected;
		
		boolean activeClient = manager.isActiveClientAlt(alt);
		boolean switchBusy =
			WurstClient.INSTANCE.getAltSwitchController().isBusy();
		switchButton.active = !activeClient && !switchBusy;
		
		sendChatButton.active = manager.isBotReady(alt);
	}
	
	@Override
	public void tick()
	{
		updateButtons();
	}
	
	@Override
	public boolean keyPressed(KeyEvent context)
	{
		if(context.key() == GLFW.GLFW_KEY_ESCAPE)
		{
			onClose();
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
	public void render(GuiGraphics context, int mouseX,
		int mouseY, float partialTicks)
	{
		context.drawCenteredString(font, "Bot Details", width / 2, 12,
			CommonColors.WHITE);
		
		AltBotState state =
			WurstClient.INSTANCE.getAltBotManager().getState(alt);
		
		int x = width / 2 - 150;
		int y = 40;
		int lineHeight = 12;
		
		context.drawString(font, "Account: " + state.getDisplayName(), x, y,
			CommonColors.LIGHT_GRAY);
		y += lineHeight;
		
		context.drawString(font,
			"UUID: " + (state.getUuid() == null ? "unknown" : state.getUuid()),
			x, y, CommonColors.LIGHT_GRAY);
		y += lineHeight;
		
		context.drawString(font,
			"Server: " + (state.getServer() == null ? "-" : state.getServer()),
			x, y, CommonColors.LIGHT_GRAY);
		y += lineHeight;
		
		context.drawString(font, "State: " + stateText(state), x, y,
			stateColor(state));
		y += lineHeight;
		
		if(state.getLastError() != null && !state.getLastError().isBlank())
		{
			String error = state.getLastError();
			context.drawString(font,
				"Error: " + (error.length() > 60
					? error.substring(0, 60) + "..." : error),
				x, y, CommonColors.RED);
			y += lineHeight;
		}
		
		if(state.getConnectionStartMillis() > 0)
		{
			long seconds = Duration.ofMillis(
				System.currentTimeMillis() - state.getConnectionStartMillis())
				.getSeconds();
			context.drawString(font, "Session duration: " + formatDuration(seconds),
				x, y, CommonColors.LIGHT_GRAY);
			y += lineHeight;
		}
		
		if(state.hasPosition())
			context
				.text(font,
					"Position: " + String.format(java.util.Locale.ROOT,
						"%.1f, %.1f, %.1f", state.getX(), state.getY(),
						state.getZ()),
					x, y, CommonColors.LIGHT_GRAY);
		else
			context.drawString(font, "Position: unknown", x, y,
				CommonColors.LIGHT_GRAY);
		
		for(Renderable drawable : renderables)
			drawable.render(context, mouseX, mouseY, partialTicks);
	}
	
	private static String stateText(AltBotState state)
	{
		return switch(state.getState())
		{
			case DISCONNECTED -> "Offline";
			case AUTHENTICATING -> "Authenticating";
			case CONNECTING -> "Connecting";
			case LOGIN -> "Login";
			case CONFIGURING -> "Configuring";
			case PLAY -> "Connected";
			case DISCONNECTING -> "Disconnecting";
			case FAILED -> "Failed";
		};
	}
	
	private static int stateColor(AltBotState state)
	{
		return switch(state.getState())
		{
			case PLAY -> CommonColors.GREEN;
			case FAILED -> CommonColors.RED;
			case AUTHENTICATING, CONNECTING, LOGIN, CONFIGURING, DISCONNECTING -> 0xFFFF55;
			case DISCONNECTED -> CommonColors.LIGHT_GRAY;
		};
	}
	
	private static String formatDuration(long seconds)
	{
		long h = seconds / 3600;
		long m = (seconds % 3600) / 60;
		long s = seconds % 60;
		return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, m, s);
	}
	
	@Override
	public void onClose()
	{
		minecraft.setScreen(prevScreen);
	}
}
