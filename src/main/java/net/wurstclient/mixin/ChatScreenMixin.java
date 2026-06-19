/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.LinkedHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
		at = @At("HEAD"),
		cancellable = true)
	public void onSendMessage(String message, boolean addToHistory,
		CallbackInfo ci)
	{
		// Ignore empty messages just like vanilla
		if((message = normalizeChatMessage(message)).isEmpty())
			return;
		
		if(WurstClient.INSTANCE.getOtfs() != null)
		{
			LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
			fields.put("source", "ChatScreen");
			fields.put("message", message);
			fields.put("kind",
				UiUtilsCommandSystem.isUiUtilsCommand(message)
					? "uiutils_command"
					: message.startsWith("/") ? "command" : "chat");
			if(message.startsWith("/"))
				fields.put("command", message.substring(1));
			WurstClient.INSTANCE.getOtfs().packetToolsOtf
				.logVerboseExternalEvent("ChatAction", fields);
		}
		
		if(UiUtilsCommandSystem.isUiUtilsCommand(message))
		{
			String command = UiUtilsCommandSystem.extractCommandBody(message);
			String result = UiUtilsCommandSystem.execute(command);
			
			if(addToHistory)
				minecraft.gui.hud.getChat().addRecentChat(message);
			
			if(minecraft.player != null && !result.isEmpty())
				for(String line : result.split("\n"))
					minecraft.player.sendSystemMessage(Component.literal(line));
				
			minecraft.gui.setScreen(null);
			ci.cancel();
			return;
		}
		
		// Create and fire the chat output event
		ChatOutputEvent event = new ChatOutputEvent(message);
		EventManager.fire(event);
		
		// If the event hasn't been modified or cancelled,
		// let the vanilla method handle the message
		boolean cancelled = event.isCancelled();
		if(!cancelled && !event.isModified())
			return;
		
		// Otherwise, cancel the vanilla method and handle the message here
		ci.cancel();
		
		// Add the message to history, even if it was cancelled
		// Otherwise the up/down arrows won't work correctly
		String newMessage = event.getMessage();
		if(addToHistory)
			minecraft.gui.hud.getChat().addRecentChat(newMessage);
		
		// If the event isn't cancelled, send the modified message
		if(!cancelled)
			if(newMessage.startsWith("/"))
				minecraft.player.connection
					.sendCommand(newMessage.substring(1));
			else
				minecraft.player.connection.sendChat(newMessage);
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
