/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;
import net.wurstclient.WurstClient;
import net.cevapi.config.AntiFingerprintConfigScreen;
import net.cevapi.security.ResourcePackProtector;
import net.wurstclient.serverfinder.ServerFinderScreen;
import net.wurstclient.util.LastServerRememberer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.serverfinder.CleanUpScreen;

@Mixin(JoinMultiplayerScreen.class)
public class MultiplayerScreenMixin extends Screen
{
	private Button lastServerButton;
	@Unique
	private Button antiFingerprintButton;
	@Unique
	private Button cornerServerFinderButton;
	@Unique
	private Button cornerCleanUpButton;
	
	private MultiplayerScreenMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	@Inject(at = @At("HEAD"), method = "init()V")
	private void beforeVanillaButtons(CallbackInfo ci)
	{
		antiFingerprintButton = null;
		cornerServerFinderButton = null;
		cornerCleanUpButton = null;
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		JoinMultiplayerScreen mpScreen = (JoinMultiplayerScreen)(Object)this;
		
		// Add Last Server button early for better tab navigation
		lastServerButton = Button
			.builder(Component.nullToEmpty("Last Server"),
				b -> LastServerRememberer.joinLastServer(mpScreen))
			.width(100).build();
		addRenderableWidget(lastServerButton);
	}
	
	@Inject(at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/gui/screens/multiplayer/JoinMultiplayerScreen;repositionElements()V",
		ordinal = 0), method = "init()V")
	private void afterVanillaButtons(CallbackInfo ci,
		@Local(ordinal = 1) LinearLayout footerTopRow,
		@Local(ordinal = 2) LinearLayout footerBottomRow)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		// Footer buttons are not added here to avoid duplicates; corner buttons
		// are created/positioned in `repositionElements()` instead.
	}
	
	@Inject(at = @At("TAIL"), method = "repositionElements()V")
	private void onRefreshWidgetPositions(CallbackInfo ci)
	{
		updateLastServerButton();
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		boolean showAntiFingerprintButton = NiceWurstModule
			.showAntiFingerprintControls()
			&& ResourcePackProtector.getConfig().shouldShowMultiplayerButton();
		
		if(showAntiFingerprintButton)
		{
			if(antiFingerprintButton == null)
			{
				antiFingerprintButton = Button.builder(
					Component.literal("Anti-Fingerprint"),
					b -> minecraft.setScreen(new AntiFingerprintConfigScreen(
						(JoinMultiplayerScreen)(Object)this)))
					.bounds(0, 0, 100, 20).build();
				addRenderableWidget(antiFingerprintButton);
			}
			
			antiFingerprintButton.setX(width / 2 + 54);
			antiFingerprintButton.setY(10);
			antiFingerprintButton.setWidth(100);
			antiFingerprintButton.visible = true;
		}else if(antiFingerprintButton != null)
		{
			antiFingerprintButton.visible = false;
		}
		
		if(cornerServerFinderButton == null)
		{
			cornerServerFinderButton = Button
				.builder(Component.literal("Server Finder"),
					b -> minecraft.setScreen(new ServerFinderScreen(
						(JoinMultiplayerScreen)(Object)this)))
				.bounds(0, 0, 100, 20).build();
			addRenderableWidget(cornerServerFinderButton);
		}
		cornerServerFinderButton.setX(width / 2 + 154 + 4);
		cornerServerFinderButton.setY(height - 54);
		cornerServerFinderButton.setWidth(100);
		
		if(cornerCleanUpButton == null)
		{
			cornerCleanUpButton =
				Button
					.builder(Component.literal("Clean Up"),
						b -> minecraft.setScreen(new CleanUpScreen(
							(JoinMultiplayerScreen)(Object)this)))
					.bounds(0, 0, 100, 20).build();
			addRenderableWidget(cornerCleanUpButton);
		}
		cornerCleanUpButton.setX(width / 2 + 154 + 4);
		cornerCleanUpButton.setY(height - 30);
		cornerCleanUpButton.setWidth(100);
	}
	
	@Inject(at = @At("HEAD"),
		method = "join(Lnet/minecraft/client/multiplayer/ServerData;)V")
	private void onConnect(ServerData entry, CallbackInfo ci)
	{
		LastServerRememberer.setLastServer(entry);
		updateLastServerButton();
	}
	
	@Unique
	private void updateLastServerButton()
	{
		if(lastServerButton == null)
			return;
		
		lastServerButton.active = LastServerRememberer.getLastServer() != null;
		lastServerButton.setX(width / 2 - 154);
		lastServerButton.setY(6);
	}
}
