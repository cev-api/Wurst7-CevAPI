/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.ChatOutputListener.ChatOutputEvent;
import net.wurstclient.hacks.ClientChatOverlayHack;
import net.wurstclient.uiutils.UiUtilsCommandSystem;

@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen
{
	@Shadow
	protected EditBox input;
	
	private ChatScreenMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	@Inject(method = "init()V", at = @At("TAIL"))
	protected void onInit(CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax().infiniChatHack.isEnabled())
			input.setMaxLength(Integer.MAX_VALUE);
		
		updateCommandTextColor();
	}
	
	@Inject(method = "handleChatInput(Ljava/lang/String;Z)V",
		at = @At(value = "INVOKE",
			target = "Ljava/lang/String;isEmpty()Z",
			shift = At.Shift.AFTER),
		cancellable = true)
	private void onHandleChatInput(String messageDontUse, boolean addToHistory,
		CallbackInfo ci, @Local(argsOnly = true) LocalRef<String> message)
	{
		String normalized = normalizeChatMessage(message.get());
		if(normalized.isEmpty())
			return;
		
		if(WurstClient.INSTANCE.getOtfs() != null)
		{
			String kind = UiUtilsCommandSystem.isUiUtilsCommand(normalized)
				? "uiutils_command"
				: normalized.startsWith("/") ? "command" : "chat";
			WurstClient.INSTANCE.getOtfs().packetToolsOtf
				.logVerboseChatInput("ChatScreen", normalized, kind);
		}
		
		if(UiUtilsCommandSystem.isUiUtilsCommand(normalized))
		{
			String command =
				UiUtilsCommandSystem.extractCommandBody(normalized);
			String result = UiUtilsCommandSystem.execute(command);
			
			if(addToHistory)
				minecraft.gui.hud.getChat().addRecentChat(normalized);
			
			if(minecraft.player != null && !result.isEmpty())
				for(String line : result.split("\n"))
					minecraft.player.sendSystemMessage(Component.literal(line));
				
			minecraft.gui.setScreen(null);
			ci.cancel();
			return;
		}
		
		// Create and fire the chat output event
		ChatOutputEvent event = new ChatOutputEvent(normalized);
		EventManager.fire(event);
		
		if(event.isCancelled())
		{
			// Preserve history even for cancelled messages
			// Otherwise the up/down arrows won't work correctly
			if(addToHistory)
				minecraft.gui.hud.getChat().addRecentChat(event.getMessage());
			
			ci.cancel();
			return;
		}
		
		if(!event.getMessage().equals(event.getOriginalMessage()))
			message.set(event.getMessage());
	}
	
	@Inject(
		method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At("TAIL"))
	private void onRender(GuiGraphicsExtractor guiGraphics, int mouseX,
		int mouseY, float partialTicks, CallbackInfo ci)
	{
		updateCommandTextColor();
	}
	
	@Inject(at = @At("HEAD"),
		method = "mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z",
		cancellable = true)
	private void wurst$handleChestSearchPreviewClick(MouseButtonEvent context,
		boolean doubleClick, CallbackInfoReturnable<Boolean> cir)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(WurstClient.INSTANCE.getHud().getChestSearchMousePreview()
			.handleMouseClick(context.x(), context.y(), context.button()))
		{
			cir.setReturnValue(true);
			cir.cancel();
		}
	}
	
	@Inject(at = @At("HEAD"),
		method = "mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z",
		cancellable = true)
	private void wurst$handleChestSearchPreviewRelease(MouseButtonEvent context,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(WurstClient.INSTANCE.getHud().getChestSearchMousePreview()
			.handleMouseRelease(context.button()))
		{
			cir.setReturnValue(true);
			cir.cancel();
		}
	}
	
	private void updateCommandTextColor()
	{
		ClientChatOverlayHack overlay =
			WurstClient.INSTANCE.getHax().clientChatOverlayHack;
		if(!overlay.shouldColorCommandText())
		{
			input.setTextColor(0xFFE0E0E0);
			return;
		}
		
		String text = input.getValue();
		
		// Determine if this looks like a Wurst command
		boolean isCommand =
			text != null && !text.isEmpty() && !text.startsWith("/");
		if(isCommand)
		{
			String prefix = getCommandPrefix();
			isCommand =
				prefix != null && !prefix.isEmpty() && text.startsWith(prefix);
		}
		
		if(isCommand)
			input.setTextColor(overlay.getCommandTextColorI());
		else
			input.setTextColor(0xFFE0E0E0);
	}
	
	private String getCommandPrefix()
	{
		try
		{
			return WurstClient.INSTANCE.getOtfs().commandPrefixOtf
				.getPrefixSetting().getSelected().toString();
		}catch(Throwable ignored)
		{
			return ".";
		}
	}
	
	@Shadow
	public abstract String normalizeChatMessage(String chatText);
}
