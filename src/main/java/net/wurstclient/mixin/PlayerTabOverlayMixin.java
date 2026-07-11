/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.awt.Color;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.objects.PlayerSprite;
import net.minecraft.world.item.component.ResolvableProfile;
import net.wurstclient.WurstClient;
import net.wurstclient.hud.ClientMessageOverlay;
import net.wurstclient.hacks.ClientChatOverlayHack;
import net.wurstclient.other_features.WurstOptionsOtf;

@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin
{
	private static final int PING_TEXT_WIDTH = 36;
	private static final String PING_TEXT_PADDING = "        ";
	
	@Inject(method = "getNameForDisplay",
		at = @At("RETURN"),
		cancellable = true)
	private void addWurstTabListDecorations(PlayerInfo info,
		CallbackInfoReturnable<Component> cir)
	{
		WurstOptionsOtf options = getOptions();
		if(options == null)
			return;
		
		Component name = cir.getReturnValue();
		ClientChatOverlayHack chatHack = getChatSettings();
		if(chatHack != null && chatHack.isEnabled()
			&& chatHack.shouldColorUsernames())
		{
			String profileName = info.getProfile().name();
			int start = name.getString().indexOf(profileName);
			int rgb =
				ClientMessageOverlay.getUsernameColorForTabList(profileName);
			if(start >= 0 && rgb >= 0)
				name = ClientMessageOverlay.colorizeComponentRangeForDisplay(
					name, start, start + profileName.length(), rgb);
		}
		
		if(options.shouldShowTabListHeads()
			&& (chatHack == null || !chatHack.shouldShowChatHeads()))
		{
			if(!ClientMessageOverlay.containsPlayerSprite(name))
			{
				MutableComponent decorated = Component.empty()
					.append(Component.object(new PlayerSprite(
						ResolvableProfile.createResolved(info.getProfile()),
						info.showHat())))
					.append(name);
				name = decorated;
			}
		}
		
		// Reserve the space occupied by the text replacement for the vanilla
		// 8px-wide ping sprite. The trailing spaces are not visible, but they
		// keep the ping from overlapping the player's name or scoreboard.
		if(options.shouldShowTabListPing())
			name = name.copy().append(Component.literal(PING_TEXT_PADDING));
		
		cir.setReturnValue(name);
	}
	
	@Inject(method = "extractPingIcon", at = @At("HEAD"), cancellable = true)
	private void replacePingIcon(GuiGraphicsExtractor context, int columnWidth,
		int rowX, int y, PlayerInfo info, CallbackInfo ci)
	{
		WurstOptionsOtf options = getOptions();
		if(options == null || !options.shouldShowTabListPing())
			return;
		
		String ping = info.getLatency() < 0 ? "?" : info.getLatency() + "ms";
		Component text = Component.literal(ping);
		int textWidth = WurstClient.MC.font.width(text);
		int pingSlotLeft = rowX + columnWidth - PING_TEXT_WIDTH;
		int textX = pingSlotLeft + (PING_TEXT_WIDTH - textWidth) / 2;
		int textColor = options.shouldColorTabListPing()
			? getPingColor(info.getLatency()) : 0xFFFFFFFF;
		context.text(WurstClient.MC.font, text, textX, y, textColor);
		ci.cancel();
	}
	
	private static int getPingColor(int ping)
	{
		if(ping < 0)
			return 0xFFAAAAAA;
		
		float hue = 1F / 3F - Math.min(ping, 500) / 500F / 3F;
		return 0xFF000000 | (Color.HSBtoRGB(hue, 1F, 1F) & 0x00FFFFFF);
	}
	
	private static WurstOptionsOtf getOptions()
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getOtfs() == null)
			return null;
		
		return WurstClient.INSTANCE.getOtfs().wurstOptionsOtf;
	}
	
	private static ClientChatOverlayHack getChatSettings()
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getHax() == null)
			return null;
		
		return WurstClient.INSTANCE.getHax().clientChatOverlayHack;
	}
}
