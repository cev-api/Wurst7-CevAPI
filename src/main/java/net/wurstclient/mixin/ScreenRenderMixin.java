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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.AbstractContainerEventHandler;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.wurstclient.mixinterface.LoginOverlayAccessor;

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
		method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
	private void renderLoginOverlay(GuiGraphics graphics, int mouseX,
		int mouseY, float partialTicks, CallbackInfo ci)
	{
		if(!(((Object)this) instanceof DisconnectedScreen))
			return;
		
		if(!(this instanceof LoginOverlayAccessor overlay))
			return;
		
		if(!overlay.isLoginOverlayVisible())
			return;
		
		Font font = minecraft.font;
		overlay.layoutLoginOverlay(font, width, height);
		
		graphics.fill(0, 0, width, height, 0x97000000);
		graphics.fill(overlay.getOverlayX(), overlay.getOverlayY(),
			overlay.getOverlayX() + overlay.getOverlayWidth(),
			overlay.getOverlayY() + overlay.getOverlayHeight(), 0xFF1E1E1E);
		
		int borderColor = 0xFF555555;
		graphics.fill(overlay.getOverlayX(), overlay.getOverlayY(),
			overlay.getOverlayX() + overlay.getOverlayWidth(),
			overlay.getOverlayY() + 2, borderColor);
		graphics.fill(overlay.getOverlayX(),
			overlay.getOverlayY() + overlay.getOverlayHeight() - 2,
			overlay.getOverlayX() + overlay.getOverlayWidth(),
			overlay.getOverlayY() + overlay.getOverlayHeight(), borderColor);
		graphics.fill(overlay.getOverlayX(), overlay.getOverlayY(),
			overlay.getOverlayX() + 2,
			overlay.getOverlayY() + overlay.getOverlayHeight(), borderColor);
		graphics.fill(overlay.getOverlayX() + overlay.getOverlayWidth() - 2,
			overlay.getOverlayY(),
			overlay.getOverlayX() + overlay.getOverlayWidth(),
			overlay.getOverlayY() + overlay.getOverlayHeight(), borderColor);
		
		int textY = overlay.getOverlayY() + 10;
		for(FormattedCharSequence line : overlay.getOverlayLines())
		{
			graphics.drawString(font, line, overlay.getOverlayX() + 10, textY,
				0xFFFFFFFF, false);
			textY += font.lineHeight + 2;
		}
	}
}
