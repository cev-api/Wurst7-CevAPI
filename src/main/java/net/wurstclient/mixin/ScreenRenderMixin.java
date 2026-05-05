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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.wurstclient.mixinterface.LoginOverlayAccessor;
import net.wurstclient.util.ConnectionLogOverlay;

@Mixin(value = Screen.class, remap = false)
public abstract class ScreenRenderMixin extends AbstractContainerEventHandler
	implements Renderable
{
	@Shadow
	protected Minecraft minecraft;
	@Shadow
	protected int width;
	@Shadow
	protected int height;
	
	protected ScreenRenderMixin(Component title)
	{
		
	}
	
	@Inject(at = @At("TAIL"),
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V")
	private void renderLoginOverlay(GuiGraphicsExtractor graphics, int mouseX,
		int mouseY, float partialTicks, CallbackInfo ci)
	{
		if(net.wurstclient.WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(!(((Object)this) instanceof DisconnectedScreen))
			return;
		
		if(!(this instanceof LoginOverlayAccessor overlay))
			return;
		
		if(!overlay.isLoginOverlayVisible())
			return;
		
		Font font = minecraft.font;
		overlay.layoutLoginOverlay(font, width, height);
		
		ConnectionLogOverlay.getInstance().render(graphics);
	}
}
