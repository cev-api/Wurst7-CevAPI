/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;
import net.wurstclient.util.BanMemoryManager;
import net.wurstclient.util.ConnectionLogOverlay;

@Mixin(value = ConnectScreen.class, remap = false)
public abstract class ConnectScreenMixin extends Screen
{
	private static final ThreadLocal<Boolean> WURST_BYPASS =
		ThreadLocal.withInitial(() -> false);
	
	@Inject(method = "startConnecting", at = @At("HEAD"), cancellable = true)
	private static void wurst$guardConnection(Screen previousScreen,
		Minecraft minecraft, ServerAddress address, ServerData server,
		boolean quickPlay, TransferState transferState, CallbackInfo ci)
	{
		WurstClient wurst = WurstClient.INSTANCE;
		if(WURST_BYPASS.get() || !wurst.isEnabled() || wurst.getOtfs() == null
			|| !wurst.getOtfs().wurstOptionsOtf.shouldRememberBansAndProxies()
			|| wurst.getBanMemoryManager() == null)
			return;
		
		BanMemoryManager memory = wurst.getBanMemoryManager();
		BanMemoryManager.ConnectionContext context =
			memory.createContext(server, address);
		BanMemoryManager.Warning warning = memory.findWarning(context);
		if(warning == null)
		{
			memory.rememberAttempt(context);
			return;
		}
		
		ci.cancel();
		minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
			if(!confirmed)
			{
				minecraft.gui.setScreen(previousScreen);
				return;
			}
			
			memory.markContinue(context);
			memory.rememberAttempt(context);
			WURST_BYPASS.set(true);
			try
			{
				ConnectScreen.startConnecting(previousScreen, minecraft,
					address, server, quickPlay, transferState);
			}finally
			{
				WURST_BYPASS.remove();
			}
		}, Component.literal("Remember Bans/Proxies"),
			Component.literal(warning.message())));
	}
	
	private ConnectScreenMixin(Component title)
	{
		super(title);
	}
	
	@Inject(at = @At("TAIL"),
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V")
	private void onRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
		float partialTicks, CallbackInfo ci)
	{
		ConnectionLogOverlay.getInstance().render(graphics);
	}
}
