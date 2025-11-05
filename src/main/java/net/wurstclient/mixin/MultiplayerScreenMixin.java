/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
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

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.Text;
import net.wurstclient.WurstClient;
import net.wurstclient.serverfinder.CleanUpScreen;
import net.wurstclient.serverfinder.ServerFinderScreen;
import net.wurstclient.util.LastServerRememberer;
import net.cevapi.config.AntiFingerprintConfigScreen;
import net.wurstclient.nicewurst.NiceWurstModule;

@Mixin(MultiplayerScreen.class)
public class MultiplayerScreenMixin extends Screen
{
	private ButtonWidget lastServerButton;
	@Unique
	private ButtonWidget antiFingerprintButton;
	@Unique
	private ButtonWidget cornerServerFinderButton;
	@Unique
	private ButtonWidget cornerCleanUpButton;
	
	private MultiplayerScreenMixin(WurstClient wurst, Text title)
	{
		super(title);
	}
	
	@Inject(at = @At("TAIL"), method = "init()V")
	private void onInit(CallbackInfo ci)
	{
		antiFingerprintButton = null;
		cornerServerFinderButton = null;
		cornerCleanUpButton = null;
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		lastServerButton = addDrawableChild(ButtonWidget
			.builder(Text.literal("Last Server"),
				b -> LastServerRememberer
					.joinLastServer((MultiplayerScreen)(Object)this))
			.dimensions(width / 2 - 154, 10, 100, 20).build());
		updateLastServerButton();
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		if(antiFingerprintButton == null)
		{
			if(!NiceWurstModule.showAntiFingerprintControls())
			{
				antiFingerprintButton = null;
			}else
			{
				antiFingerprintButton = ButtonWidget
					.builder(Text.literal("Anti-Fingerprint"),
						b -> client.setScreen(new AntiFingerprintConfigScreen(
							(MultiplayerScreen)(Object)this)))
					.dimensions(0, 0, 100, 20).build();
				addDrawableChild(antiFingerprintButton);
			}
		}
		
		if(antiFingerprintButton != null)
		{
			antiFingerprintButton.setX(width / 2 + 54);
			antiFingerprintButton.setY(10);
			antiFingerprintButton.setWidth(100);
			antiFingerprintButton.visible = true;
		}
		
		if(cornerServerFinderButton == null)
		{
			cornerServerFinderButton = ButtonWidget
				.builder(Text.literal("Server Finder"),
					b -> client.setScreen(new ServerFinderScreen(
						(MultiplayerScreen)(Object)this)))
				.dimensions(0, 0, 100, 20).build();
			addDrawableChild(cornerServerFinderButton);
		}
		cornerServerFinderButton.setX(width / 2 + 154 + 4);
		cornerServerFinderButton.setY(height - 54);
		cornerServerFinderButton.setWidth(100);
		
		if(cornerCleanUpButton == null)
		{
			cornerCleanUpButton = ButtonWidget
				.builder(Text.literal("Clean Up"),
					b -> client.setScreen(
						new CleanUpScreen((MultiplayerScreen)(Object)this)))
				.dimensions(0, 0, 100, 20).build();
			addDrawableChild(cornerCleanUpButton);
		}
		cornerCleanUpButton.setX(width / 2 + 154 + 4);
		cornerCleanUpButton.setY(height - 30);
		cornerCleanUpButton.setWidth(100);
	}
	
	@Inject(at = @At("HEAD"),
		method = "connect(Lnet/minecraft/client/network/ServerInfo;)V")
	private void onConnect(ServerInfo entry, CallbackInfo ci)
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
	}
}
