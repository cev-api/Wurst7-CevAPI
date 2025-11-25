/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringUtil;
import net.wurstclient.WurstClient;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.wurstclient.clickgui.screens.ClickGuiScreen;
import net.wurstclient.hacks.AutoReconnectHack;
import net.wurstclient.hacks.OfflineSettingsHack;
import net.wurstclient.mixinterface.LoginOverlayAccessor;
import net.wurstclient.navigator.NavigatorListScreen;
import net.wurstclient.nochatreports.ForcedChatReportsScreen;
import net.wurstclient.nochatreports.NcrModRequiredScreen;
import net.wurstclient.options.EnterProfileNameScreen;
import net.wurstclient.util.LastServerRememberer;

@Mixin(value = DisconnectedScreen.class, remap = false)
public class DisconnectedScreenMixin extends Screen
	implements LoginOverlayAccessor
{
	private int autoReconnectTimer;
	private Button autoReconnectButton;
	private boolean showLoginOverlay;
	private Button overlayAutoButton;
	private Button overlayRandomButton;
	private Button overlayRejoinButton;
	private Button overlayPickPlayerButton;
	private Button overlayCommandButton;
	private OfflineSettingsHack overlayHack;
	private String overlayReasonText;
	private Component overlayReasonComponent;
	private List<FormattedCharSequence> overlayLines = Collections.emptyList();
	private int overlayWidth;
	private int overlayHeight;
	private int overlayX;
	private int overlayY;
	
	private Button reconnectButton;
	
	private void ensureValidParent()
	{
		if(parent != null && !(parent instanceof ClickGuiScreen))
			return;
		
		parent = new JoinMultiplayerScreen(new TitleScreen());
	}
	
	@Shadow
	@Final
	private DisconnectionDetails details;
	@Shadow
	@Final
	@Mutable
	private Screen parent;
	@Shadow
	@Final
	private LinearLayout layout;
	
	private DisconnectedScreenMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	@Inject(at = @At("TAIL"), method = "init()V")
	private void onInit(CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		Component reason = details.reason();
		System.out.println("Disconnected: " + reason);
		
		if(ForcedChatReportsScreen.isCausedByNoChatReports(reason))
		{
			minecraft.setScreen(new ForcedChatReportsScreen(parent));
			return;
		}
		
		if(NcrModRequiredScreen.isCausedByLackOfNCR(reason))
		{
			minecraft.setScreen(new NcrModRequiredScreen(parent));
			return;
		}
		
		addReconnectButtons(reason);
	}
	
	private void addReconnectButtons(Component reason)
	{
		ensureValidParent();
		OfflineSettingsHack offlineSettingsHack =
			WurstClient.INSTANCE.getHax().offlineSettingsHack;
		
		reconnectButton = layout.addChild(Button
			.builder(Component.literal("Reconnect"),
				b -> LastServerRememberer.reconnect(parent))
			.width(200).build());
		
		autoReconnectButton =
			layout.addChild(Button.builder(Component.literal("AutoReconnect"),
				b -> pressAutoReconnect()).width(200).build());
		
		// Show player location (click to copy to clipboard)
		final String posString;
		if(minecraft.player != null)
		{
			var ppos = minecraft.player.blockPosition();
			posString = ppos.getX() + ", " + ppos.getY() + ", " + ppos.getZ();
		}else
			posString = "Unknown";
		Button copyLocButton = layout.addChild(Button
			.builder(Component.literal("Copy location: " + posString), b -> {
				minecraft.keyboardHandler.setClipboard(posString);
			}).width(200).build());
		
		layout.arrangeElements();
		Stream.of(reconnectButton, autoReconnectButton, copyLocButton)
			.filter(Objects::nonNull).forEach(this::addRenderableWidget);
		
		offlineSettingsHack.handleDisconnect(reason);
		boolean loginElsewhere = isLoginElsewhere(reason);
		boolean crackedServer = offlineSettingsHack.wasLastServerCracked();
		
		if(loginElsewhere || crackedServer)
		{
			Component overlayReason = reason;
			if(overlayReason == null)
				overlayReason = Component.literal(
					crackedServer ? "Cracked server detected" : "Disconnected");
			showLoginOverlay(offlineSettingsHack, overlayReason);
			
			if(loginElsewhere
				&& offlineSettingsHack.consumeAutoReconnectRequest())
				offlineSettingsHack.performAutoReconnect(parent);
		}else
			hideLoginOverlay();
		
		AutoReconnectHack autoReconnect =
			WurstClient.INSTANCE.getHax().autoReconnectHack;
		
		if(autoReconnect.isEnabled())
			autoReconnectTimer = autoReconnect.getWaitTicks();
	}
	
	private void pressAutoReconnect()
	{
		AutoReconnectHack autoReconnect =
			WurstClient.INSTANCE.getHax().autoReconnectHack;
		
		autoReconnect.setEnabled(!autoReconnect.isEnabled());
		
		if(autoReconnect.isEnabled())
			autoReconnectTimer = autoReconnect.getWaitTicks();
	}
	
	private void layoutOverlay()
	{
		if(!showLoginOverlay || overlayReasonText == null)
			return;
		
		Font font = minecraft.font;
		int maxWidth = Math.min(width - 40, 360);
		overlayLines =
			font.split(Component.literal(overlayReasonText), maxWidth - 20);
		int textWidth = overlayLines.stream().mapToInt(font::width).max()
			.orElse(font.width(Component.literal(overlayReasonText)));
		overlayWidth = Math.max(textWidth + 20, 260);
		overlayWidth = Math.min(overlayWidth, width - 40);
		
		int padding = 10;
		int textHeight = overlayLines.size() * (font.lineHeight + 2);
		int buttonSpacing = 6;
		int buttonHeightSum = 0;
		int buttonCount = 0;
		
		if(overlayAutoButton != null && overlayAutoButton.visible)
		{
			buttonHeightSum += overlayAutoButton.getHeight();
			buttonCount++;
		}
		
		if(overlayRejoinButton != null && overlayRejoinButton.visible)
		{
			buttonHeightSum += overlayRejoinButton.getHeight();
			buttonCount++;
		}
		
		if(overlayRandomButton != null && overlayRandomButton.visible)
		{
			buttonHeightSum += overlayRandomButton.getHeight();
			buttonCount++;
		}
		
		if(overlayCommandButton != null && overlayCommandButton.visible)
		{
			buttonHeightSum += overlayCommandButton.getHeight();
			buttonCount++;
		}
		
		if(overlayPickPlayerButton != null && overlayPickPlayerButton.visible)
		{
			buttonHeightSum += overlayPickPlayerButton.getHeight();
			buttonCount++;
		}
		
		int totalButtonSpacing = Math.max(0, buttonCount - 1) * buttonSpacing;
		int textButtonGap = overlayLines.isEmpty() ? 0 : 8;
		overlayHeight = padding + textHeight + textButtonGap + buttonHeightSum
			+ totalButtonSpacing + padding;
		if(overlayHeight < padding * 2 + 20)
			overlayHeight = padding * 2 + 20;
		
		overlayX = (width - overlayWidth) / 2;
		
		// place overlay well above the vanilla
		// "Connection Lost" area by anchoring it near the top.
		overlayY = 30;
		if(overlayY + overlayHeight > height - 20)
			overlayY = Math.max(20, height - overlayHeight - 20);
		
		int currentY = overlayY + padding + textHeight;
		if(!overlayLines.isEmpty())
			currentY += textButtonGap;
		
		int placedButtons = 0;
		
		if(overlayAutoButton != null && overlayAutoButton.visible)
		{
			int buttonX =
				overlayX + (overlayWidth - overlayAutoButton.getWidth()) / 2;
			overlayAutoButton.setPosition(buttonX, currentY);
			currentY += overlayAutoButton.getHeight();
			placedButtons++;
			if(placedButtons < buttonCount)
				currentY += buttonSpacing;
		}
		
		if(overlayRejoinButton != null && overlayRejoinButton.visible)
		{
			int buttonX =
				overlayX + (overlayWidth - overlayRejoinButton.getWidth()) / 2;
			overlayRejoinButton.setPosition(buttonX, currentY);
			currentY += overlayRejoinButton.getHeight();
			placedButtons++;
			if(placedButtons < buttonCount)
				currentY += buttonSpacing;
		}
		
		if(overlayRandomButton != null && overlayRandomButton.visible)
		{
			int buttonX =
				overlayX + (overlayWidth - overlayRandomButton.getWidth()) / 2;
			overlayRandomButton.setPosition(buttonX, currentY);
			currentY += overlayRandomButton.getHeight();
			placedButtons++;
			if(placedButtons < buttonCount)
				currentY += buttonSpacing;
		}
		
		if(overlayCommandButton != null && overlayCommandButton.visible)
		{
			int buttonX =
				overlayX + (overlayWidth - overlayCommandButton.getWidth()) / 2;
			overlayCommandButton.setPosition(buttonX, currentY);
			currentY += overlayCommandButton.getHeight();
			placedButtons++;
			if(placedButtons < buttonCount)
				currentY += buttonSpacing;
		}
		
		if(overlayPickPlayerButton != null && overlayPickPlayerButton.visible)
		{
			int buttonX = overlayX
				+ (overlayWidth - overlayPickPlayerButton.getWidth()) / 2;
			overlayPickPlayerButton.setPosition(buttonX, currentY);
			currentY += overlayPickPlayerButton.getHeight();
			placedButtons++;
			if(placedButtons < buttonCount)
				currentY += buttonSpacing;
		}
	}
	
	@Override
	public boolean isLoginOverlayVisible()
	{
		return showLoginOverlay;
	}
	
	@Override
	public List<FormattedCharSequence> getOverlayLines()
	{
		return overlayLines;
	}
	
	@Override
	public int getOverlayWidth()
	{
		return overlayWidth;
	}
	
	@Override
	public int getOverlayHeight()
	{
		return overlayHeight;
	}
	
	@Override
	public int getOverlayX()
	{
		return overlayX;
	}
	
	@Override
	public int getOverlayY()
	{
		return overlayY;
	}
	
	@Override
	public void layoutLoginOverlay(Font font, int width, int height)
	{
		layoutOverlay();
	}
	
	private Button createOverlayButton(Component text, Runnable action,
		int width)
	{
		return Button.builder(text, b -> action.run()).width(width).build();
	}
	
	private void ensureOverlayButtons(OfflineSettingsHack hack)
	{
		overlayHack = hack;
		if(overlayRandomButton == null)
		{
			overlayRandomButton = createOverlayButton(
				Component.literal("Reconnect as random user"), () -> {
					hideLoginOverlay();
					hack.reconnectWithRandomName(parent);
				}, 220);
			overlayRandomButton.visible = false;
			addRenderableWidget(overlayRandomButton);
		}
		
		if(overlayRejoinButton == null)
		{
			overlayRejoinButton = createOverlayButton(
				Component.literal("Reconnect with selected name"), () -> {
					promptReconnectWithCustomName(hack);
				}, 220);
			overlayRejoinButton.visible = false;
			addRenderableWidget(overlayRejoinButton);
		}
		
		if(overlayAutoButton == null)
		{
			overlayAutoButton = createOverlayButton(getAutoReconnectLabel(),
				this::toggleAutoReconnect, 200);
			overlayAutoButton.visible = false;
			addRenderableWidget(overlayAutoButton);
		}else
			overlayAutoButton.setMessage(getAutoReconnectLabel());
		
		if(overlayCommandButton == null)
		{
			overlayCommandButton = createOverlayButton(
				Component.literal("Reconnect & run command"), () -> {
					showReconnectCommandPrompt(hack);
				}, 220);
			overlayCommandButton.visible = false;
			addRenderableWidget(overlayCommandButton);
		}
		if(overlayPickPlayerButton == null)
		{
			overlayPickPlayerButton = createOverlayButton(
				Component.literal("Reconnect as specific player"), () -> {
					showPlayerPicker(hack);
				}, 220);
			overlayPickPlayerButton.visible = false;
			addRenderableWidget(overlayPickPlayerButton);
		}
	}
	
	private void toggleAutoReconnect()
	{
		if(overlayHack == null)
			return;
		
		boolean enabled = !overlayHack.isAutoReconnectEnabled();
		overlayHack.setAutoReconnectEnabled(enabled);
		
		if(overlayAutoButton != null)
			overlayAutoButton.setMessage(getAutoReconnectLabel());
		
		if(enabled)
		{
			hideLoginOverlay();
			overlayHack.reconnectWithSelectedName(parent);
		}
	}
	
	private Component getAutoReconnectLabel()
	{
		boolean enabled =
			overlayHack != null && overlayHack.isAutoReconnectEnabled();
		String text = enabled ? "AutoReconnect ON" : "AutoReconnect OFF";
		return Component.literal(text)
			.withStyle(style -> style.withColor(enabled
				? TextColor.fromRgb(0x00FF00) : TextColor.fromRgb(0xFF0000)));
	}
	
	private void showLoginOverlay(OfflineSettingsHack hack, Component reason)
	{
		showLoginOverlay = true;
		overlayReasonText = StringUtil.stripColor(reason.getString());
		overlayReasonComponent = reason;
		overlayLines = Collections.emptyList();
		ensureOverlayButtons(hack);
		overlayRandomButton.visible = true;
		if(overlayAutoButton != null)
		{
			overlayAutoButton.visible = true;
			overlayAutoButton.setMessage(getAutoReconnectLabel());
		}
		if(overlayRejoinButton != null)
			overlayRejoinButton.visible = true;
		if(overlayPickPlayerButton != null)
			overlayPickPlayerButton.visible = true;
		if(overlayCommandButton != null)
			overlayCommandButton.visible = true;
		
		layoutOverlay();
	}
	
	private void hideLoginOverlay()
	{
		showLoginOverlay = false;
		if(overlayRandomButton != null)
			overlayRandomButton.visible = false;
		if(overlayRejoinButton != null)
			overlayRejoinButton.visible = false;
		if(overlayPickPlayerButton != null)
			overlayPickPlayerButton.visible = false;
		if(overlayAutoButton != null)
			overlayAutoButton.visible = false;
		if(overlayCommandButton != null)
			overlayCommandButton.visible = false;
		overlayReasonText = null;
		overlayLines = Collections.emptyList();
	}
	
	private static boolean isLoginElsewhere(Component reason)
	{
		if(reason == null)
			return false;
		
		String text = StringUtil.stripColor(reason.getString());
		return "You logged in from another location".equals(text);
	}
	
	private void promptReconnectWithCustomName(OfflineSettingsHack hack)
	{
		if(minecraft == null || hack == null)
			return;
		
		Screen returnScreen = (Screen)(Object)this;
		minecraft.setScreen(new EnterProfileNameScreen(returnScreen, input -> {
			if(input == null)
				return;
			
			String trimmed = input.trim();
			if(trimmed.isEmpty()
				|| !OfflineSettingsHack.isValidOfflineNameFormat(trimmed))
				return;
			
			hideLoginOverlay();
			hack.reconnectWithCustomName(trimmed, parent);
		}, Component.literal("Enter offline name"), value -> {
			if(value == null)
				return false;
			
			String trimmed = value.trim();
			return !trimmed.isEmpty()
				&& OfflineSettingsHack.isValidOfflineNameFormat(trimmed);
		}));
	}
	
	private void showPlayerPicker(OfflineSettingsHack hack)
	{
		if(minecraft == null || hack == null)
			return;
		
		ArrayList<String> names = new ArrayList<>();
		
		if(minecraft.player != null && minecraft.player.connection != null)
		{
			minecraft.player.connection.getOnlinePlayers().forEach(info -> {
				if(info == null || info.getProfile() == null
					|| info.getProfile().name() == null)
					return;
				
				String name = info.getProfile().name().trim();
				if(!name.isEmpty() && !names.contains(name))
					names.add(name);
			});
		}
		
		if(names.isEmpty())
			names.addAll(hack.getCapturedPlayerNames());
		
		if(names.isEmpty())
			return;
		
		Component reason = overlayReasonComponent != null
			? overlayReasonComponent : Component.literal("Disconnected");
		Runnable restoreOverlay = () -> {
			if(overlayHack != null)
				showLoginOverlay(overlayHack, reason);
		};
		
		hideLoginOverlay();
		minecraft.setScreen(new NavigatorListScreen(
			Component.literal("Select player"), names, selected -> {
				if(selected != null && !selected.trim().isEmpty())
					hack.reconnectWithCustomName(selected.trim(), parent);
			}, (player, command) -> {
				if(player == null || player.trim().isEmpty())
					return;
				if(command == null || command.trim().isEmpty())
					return;
				
				hack.queueReconnectCommand(command.trim());
				hack.reconnectWithCustomName(player.trim(), parent);
			}, this, restoreOverlay));
	}
	
	private void showReconnectCommandPrompt(OfflineSettingsHack hack)
	{
		if(minecraft == null || hack == null)
			return;
		
		Screen returnScreen = (Screen)(Object)this;
		minecraft.setScreen(new EnterProfileNameScreen(returnScreen, input -> {
			if(input == null)
				return;
			
			String trimmed = input.trim();
			if(trimmed.isEmpty())
				return;
			
			hideLoginOverlay();
			hack.queueReconnectCommand(trimmed);
			hack.reconnectWithSelectedName(parent);
		}, Component.literal("Enter reconnect command"),
			value -> value != null && !value.trim().isEmpty()));
	}
	
	@Override
	public void tick()
	{
		if(!WurstClient.INSTANCE.isEnabled() || autoReconnectButton == null)
			return;
		
		AutoReconnectHack autoReconnect =
			WurstClient.INSTANCE.getHax().autoReconnectHack;
		
		if(!autoReconnect.isEnabled())
		{
			autoReconnectButton.setMessage(Component.literal("AutoReconnect"));
			return;
		}
		
		autoReconnectButton.setMessage(Component.literal("AutoReconnect ("
			+ (int)Math.ceil(autoReconnectTimer / 20.0) + ")"));
		
		if(autoReconnectTimer > 0)
		{
			autoReconnectTimer--;
			return;
		}
		
		LastServerRememberer.reconnect(parent);
	}
}
