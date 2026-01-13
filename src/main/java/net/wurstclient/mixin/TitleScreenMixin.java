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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;
import net.wurstclient.config.BuildConfig;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.options.WurstOptionsScreen;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen
{
	private AbstractWidget realmsButton = null;
	private Button wurstOptionsButton;
	
	private TitleScreenMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	/**
	 * Adds the Wurst Options button to the title screen. This mixin must not
	 * run in demo mode, as the Realms button doesn't exist there.
	 */
	@Inject(at = @At("RETURN"), method = "createNormalMenuOptions(II)I")
	private void onAddNormalWidgets(int y, int spacingY,
		CallbackInfoReturnable<Integer> cir)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		for(AbstractWidget button : Screens.getButtons(this))
		{
			if(!button.getMessage().getString().equals(I18n.get("menu.online")))
				continue;
			
			realmsButton = button;
			break;
		}
		
		if(realmsButton == null)
			throw new IllegalStateException("Couldn't find realms button!");
		
		if(NiceWurstModule.showAltManager())
		{
			// make Realms button smaller
			realmsButton.setWidth(98);
			
			// add Wurst Options button
			addRenderableWidget(wurstOptionsButton = Button
				.builder(
					Component.literal(
						NiceWurstModule.getOptionsLabel("Wurst Options")),
					b -> minecraft.setScreen(new WurstOptionsScreen(this)))
				.bounds(width / 2 + 2, realmsButton.getY(), 98, 20).build());
		}else
			wurstOptionsButton = null;
	}
	
	@Inject(at = @At("RETURN"), method = "tick()V")
	private void onTick(CallbackInfo ci)
	{
		if(realmsButton == null || wurstOptionsButton == null)
			return;
			
		// adjust Wurst Options button if Realms button has been moved
		// happens when ModMenu is installed
		wurstOptionsButton.setY(realmsButton.getY());
	}
	
	@Inject(at = @At("TAIL"),
		method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
	private void onRender(GuiGraphics graphics, int mouseX, int mouseY,
		float partialTicks, CallbackInfo ci)
	{
		Font font = minecraft.font;
		String brand = NiceWurstModule.isActive() ? "NiceWurst" : "Wurst";
		String text = brand + " " + BuildConfig.MOD_VERSION;
		graphics.drawString(font, Component.literal(text).getVisualOrderText(),
			4, 4, 0xFFFFFFFF, true);
	}
	
	/**
	 * Stops the multiplayer button being grayed out if the user's Microsoft
	 * account is parental-control'd or banned from online play.
	 */
	@Inject(at = @At("HEAD"),
		method = "getMultiplayerDisabledReason()Lnet/minecraft/network/chat/Component;",
		cancellable = true)
	private void onGetMultiplayerDisabledText(
		CallbackInfoReturnable<Component> cir)
	{
		cir.setReturnValue(null);
	}
}
