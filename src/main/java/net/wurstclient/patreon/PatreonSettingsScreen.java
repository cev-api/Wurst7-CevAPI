/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Util;
import net.wurstclient.WurstClient;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.patreon.SupporterManager.AuthPhase;
import net.wurstclient.patreon.SupporterManager.AuthSnapshot;

/**
 * Supporter account management inside the existing Wurst options flow.
 */
public final class PatreonSettingsScreen extends Screen
{
	private static final int ACCENT = 0xFFFFB300;
	private static final int BODY_COLOR = 0xFFD7DEEA;
	private static final int MUTED_COLOR = 0xFF98A4B5;
	private static final int SUCCESS_COLOR = 0xFF7DFF9B;
	private static final int ERROR_COLOR = 0xFFFF7B72;
	private static final int PANEL_COLOR = 0xA8101622;
	private static final String PATREON_URL =
		"https://www.patreon.com/cw/cevapi_dev";
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
		.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
	
	private final Screen prevScreen;
	private Button connectButton;
	private Button disconnectButton;
	private Button openButton;
	private Button backButton;
	
	public PatreonSettingsScreen(Screen prevScreen)
	{
		super(Component.literal("Patreon Supporter"));
		this.prevScreen = prevScreen;
	}
	
	@Override
	protected void init()
	{
		super.init();
		
		int gap = 8;
		int buttonWidth = Math.max(130, Math.min(200, (width - 48 - gap) / 2));
		int left = width / 2 - buttonWidth - gap / 2;
		int right = width / 2 + gap / 2;
		int bottom = Math.max(40, height - 30);
		int rowTwo = bottom - 24;
		int rowOne = rowTwo - 24;
		
		connectButton = Button
			.builder(Component.literal("Connect with Patreon"),
				b -> startAuthentication())
			.bounds(left, rowOne, buttonWidth, 20).build();
		disconnectButton = Button
			.builder(Component.literal("Disconnect Patreon"), b -> disconnect())
			.bounds(right, rowOne, buttonWidth, 20).build();
		openButton = Button
			.builder(Component.literal("Open Patreon Page"),
				b -> Util.getPlatform().openUri(PATREON_URL))
			.bounds(left, rowTwo, buttonWidth, 20).build();
		backButton = Button.builder(Component.literal("Back"), b -> onClose())
			.bounds(right, rowTwo, buttonWidth, 20).build();
		
		addRenderableWidget(connectButton);
		addRenderableWidget(disconnectButton);
		addRenderableWidget(openButton);
		addRenderableWidget(backButton);
		updateButtonState();
	}
	
	private SupporterManager getManager()
	{
		return WurstClient.INSTANCE.getSupporterManager();
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		renderBackground(context, mouseX, mouseY, partialTicks);
		renderContent(context);
		
		for(Renderable renderable : renderables)
			renderable.render(context, mouseX, mouseY, partialTicks);
	}
	
	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		context.fillGradient(0, 0, width, height, 0xF0060910, 0xF0162233);
		context.fill(0, 0, width, 2, ACCENT);
	}
	
	private void renderContent(GuiGraphics context)
	{
		Font font = minecraft.font;
		boolean compact = height < 300;
		int panelWidth = Math.min(width - 24, 640);
		int panelX = (width - panelWidth) / 2;
		int panelY = 18;
		int panelHeight = Math.max(20, height - 36);
		context.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight,
			PANEL_COLOR);
		context.fill(panelX, panelY, panelX + panelWidth, panelY + 1,
			0x8048FFD8);
		
		String brand = NiceWurstModule.isActive() ? "NiceWurst" : "Wurst";
		context.drawCenteredString(font,
			Component.literal(brand + " Patreon Supporter").withStyle(
				ChatFormatting.BOLD),
			width / 2, panelY + 14, ACCENT);
		context.drawCenteredString(font,
			"Startup promotions stay hidden while your membership remains active.",
			width / 2, panelY + 28, MUTED_COLOR);
		
		SupporterManager manager = getManager();
		int y = panelY + 52;
		if(manager == null)
		{
			context.drawCenteredString(font,
				"Supporter services are not available in this build.",
				width / 2, y, ERROR_COLOR);
			return;
		}
		
		boolean valid = manager.hasValidSupporterEntitlement();
		int labelX = panelX + 20;
		int valueX = panelX + Math.min(190, panelWidth / 2);
		y = drawLine(context, font, labelX, valueX, "Status",
			manager.getShortStatus(valid), valid ? SUCCESS_COLOR : BODY_COLOR,
			y);
		y = drawLine(context, font, labelX, valueX, "Entitlement",
			describeEntitlement(manager, valid), BODY_COLOR, y);
		y = drawLine(context, font, labelX, valueX, "Last verification",
			describeLastVerification(manager), BODY_COLOR, y);
		y = drawLine(
			context, font, labelX, valueX, "Startup promotion", valid
				? "Disabled while verified" : "Will appear on the next launch",
			BODY_COLOR, y);
		
		AuthSnapshot snapshot = manager.getAuthSnapshot();
		String message = snapshot.message();
		if(message == null || message.isBlank())
			message = manager.getStatusMessage();
		if(message != null && !message.isBlank())
		{
			int color = switch(snapshot.phase())
			{
				case SUCCESS -> SUCCESS_COLOR;
				case ERROR -> ERROR_COLOR;
				case WAITING, DISCONNECTING -> ACCENT;
				case IDLE -> MUTED_COLOR;
			};
			int maxWidth = panelWidth - 32;
			List<FormattedCharSequence> lines =
				font.split(Component.literal(message), maxWidth);
			int messageY = Math.max(y + 10, height - (compact ? 120 : 160));
			int lineCount = Math.min(lines.size(), compact ? 2 : 4);
			for(int i = 0; i < lineCount; i++)
			{
				context.drawCenteredString(font, lines.get(i), width / 2,
					messageY, color);
				messageY += font.lineHeight;
			}
		}
		
		if(!compact)
			context.drawCenteredString(font,
				"No passwords or activation codes are used. Authentication happens on Patreon.",
				width / 2, height - 100, MUTED_COLOR);
	}
	
	private int drawLine(GuiGraphics context, Font font, int labelX, int valueX,
		String label, String value, int valueColor, int y)
	{
		context.drawString(font, label + ":", labelX, y, MUTED_COLOR);
		context.drawString(font, value, valueX, y, valueColor);
		return y + 13;
	}
	
	private String describeEntitlement(SupporterManager manager, boolean valid)
	{
		long expiresAt = manager.getEntitlementExpiresAt();
		if(expiresAt <= 0L)
			return manager.hasRefreshCredentials() ? "Refresh required"
				: "Not connected";
		if(valid)
			return "Valid until " + format(expiresAt);
		return "Expired " + format(expiresAt);
	}
	
	private String describeLastVerification(SupporterManager manager)
	{
		long lastRefresh = manager.getLastSuccessfulRefresh();
		if(lastRefresh > 0L)
			return format(lastRefresh);
		
		long lastVerified = manager.getLastVerifiedAt();
		if(lastVerified > 0L)
			return "This launch";
		
		return "Never";
	}
	
	private static String format(long epochSeconds)
	{
		if(epochSeconds <= 0L)
			return "Unknown";
		return DATE_FORMAT.format(Instant.ofEpochSecond(epochSeconds));
	}
	
	@Override
	public void tick()
	{
		super.tick();
		updateButtonState();
	}
	
	private void updateButtonState()
	{
		if(connectButton == null || disconnectButton == null)
			return;
		
		SupporterManager manager = getManager();
		if(manager == null)
		{
			connectButton.active = false;
			disconnectButton.active = false;
			return;
		}
		
		AuthPhase phase = manager.getAuthSnapshot().phase();
		boolean busy =
			phase == AuthPhase.WAITING || phase == AuthPhase.DISCONNECTING;
		boolean connected = manager.hasValidSupporterEntitlement()
			|| manager.hasRefreshCredentials();
		
		connectButton.active = !busy;
		connectButton.setMessage(Component.literal(
			connected ? "Reconnect with Patreon" : "Connect with Patreon"));
		disconnectButton.active = !busy && connected;
	}
	
	private void startAuthentication()
	{
		SupporterManager manager = getManager();
		if(manager == null)
			return;
		manager.beginAuthentication(url -> Util.getPlatform().openUri(url));
		updateButtonState();
	}
	
	private void disconnect()
	{
		SupporterManager manager = getManager();
		if(manager == null)
			return;
		manager.disconnectAsync();
		updateButtonState();
	}
	
	@Override
	public void onClose()
	{
		if(prevScreen != null)
			minecraft.setScreen(prevScreen);
		else
			super.onClose();
	}
	
	@Override
	public boolean isPauseScreen()
	{
		return false;
	}
}
