/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.ui_utils;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.UiUtilsHack;
import net.wurstclient.uiutils.UiUtilsState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class ChestSlotOverlayMixin extends Screen
{
	@Shadow
	protected int leftPos;
	
	@Shadow
	protected int topPos;
	
	@Shadow
	protected int imageWidth;
	
	@Shadow
	protected int imageHeight;
	
	@Shadow
	public AbstractContainerMenu menu;
	
	protected ChestSlotOverlayMixin()
	{
		super(Component.literal(""));
	}
	
	@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
		at = @At("TAIL"))
	private void wurst$renderSlotOverlay(GuiGraphics graphics, int mouseX,
		int mouseY, float delta, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		if(!UiUtilsState.isUiEnabled())
			return;
		
		UiUtilsHack hack;
		try
		{
			hack = WurstClient.INSTANCE.getHax().uiUtilsHack;
		}catch(Throwable t)
		{
			return;
		}
		
		if(hack == null || !hack.isSlotOverlayEnabled())
			return;
			
		// Show for all container menus, not just chests (e.g.,
		// merchants/traders)
		
		int totalSlots = menu.slots.size();
		int color = hack.getSlotOverlayColorI();
		int offsetX = hack.getSlotOverlayOffsetX();
		int offsetY = hack.getSlotOverlayOffsetY();
		boolean hoverOnly = hack.isSlotOverlayHoverOnly();
		
		for(int index = 0; index < totalSlots; index++)
		{
			Slot slot = menu.slots.get(index);
			if(slot == null)
				continue;
			
			if(hoverOnly)
			{
				int sx = leftPos + slot.x;
				int sy = topPos + slot.y;
				if(!(mouseX >= sx && mouseX < sx + 16 && mouseY >= sy
					&& mouseY < sy + 16))
					continue;
			}
			int textX = leftPos + slot.x + offsetX;
			int textY = topPos + slot.y + offsetY;
			String label = String.valueOf(index);
			graphics.drawString(font, label, textX, textY, color, true);
		}
	}
}
