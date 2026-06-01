/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin.ui_utils;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.wurstclient.WurstClient;
import net.wurstclient.chestsearch.SlotHighlighter;
import net.wurstclient.clickgui.screens.ChestSearchScreen;
import net.wurstclient.hacks.ChestSearchHack;
import net.wurstclient.hacks.EnchantmentHandlerHack;
import net.wurstclient.hacks.UiUtilsHack;
import net.wurstclient.uiutils.UiUtilsState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
	
	@Inject(
		method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",
		at = @At("TAIL"))
	private void wurst$renderSlotOverlay(GuiGraphicsExtractor graphics,
		int mouseX, int mouseY, float delta, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
			
		// Show for all container menus, not just chests (e.g.,
		// merchants/traders)
		int activeHighlightSlot = -1;
		int activeHighlightColor = 0;
		boolean drawActiveHighlight = false;
		try
		{
			EnchantmentHandlerHack ench =
				WurstClient.INSTANCE.getHax().enchantmentHandlerHack;
			if(ench != null && ench.isEnabled()
				&& SlotHighlighter.INSTANCE.isEnchantmentHandlerActive()
				&& ench.isSlotHighlightEnabled())
			{
				activeHighlightSlot =
					SlotHighlighter.INSTANCE.getEnchantmentHandlerSlot();
				drawActiveHighlight = activeHighlightSlot >= 0;
				activeHighlightColor = ench.getSlotHighlightColor();
			}
		}catch(Throwable ignored)
		{}
		// A remembered ChestSearch selection wins while its chest is open.
		// EnchantmentHandler hover remains independent underneath it.
		try
		{
			ChestSearchHack chestSearch =
				WurstClient.INSTANCE.getHax().chestSearchHack;
			if(chestSearch != null
				&& SlotHighlighter.INSTANCE.isChestSearchActive()
				&& chestSearch.isSlotHighlightEnabled())
			{
				activeHighlightSlot =
					SlotHighlighter.INSTANCE.getChestSearchSlot();
				drawActiveHighlight = activeHighlightSlot >= 0;
				activeHighlightColor = chestSearch.getSlotHighlightColorARGB();
			}
		}catch(Throwable ignored)
		{}
		
		UiUtilsHack hack = null;
		boolean drawSlotNumbers = false;
		try
		{
			hack = WurstClient.INSTANCE.getHax().uiUtilsHack;
			drawSlotNumbers = UiUtilsState.isUiEnabled() && hack != null
				&& hack.isSlotOverlayEnabled();
		}catch(Throwable ignored)
		{}
		
		if(!drawActiveHighlight && !drawSlotNumbers)
			return;
		
		int totalSlots = menu.slots.size();
		int color = drawSlotNumbers ? hack.getSlotOverlayColorI() : 0;
		int offsetX = drawSlotNumbers ? hack.getSlotOverlayOffsetX() : 0;
		int offsetY = drawSlotNumbers ? hack.getSlotOverlayOffsetY() : 0;
		boolean hoverOnly = drawSlotNumbers && hack.isSlotOverlayHoverOnly();
		int highlightIndex = activeHighlightSlot;
		if(drawActiveHighlight
			&& (highlightIndex < 0 || highlightIndex >= totalSlots))
			highlightIndex = -1;
		if(drawActiveHighlight && highlightIndex < 0
			&& activeHighlightSlot >= 0)
		{
			for(int j = 0; j < totalSlots; j++)
			{
				Slot cand = menu.slots.get(j);
				if(cand != null
					&& cand.getContainerSlot() == activeHighlightSlot)
				{
					highlightIndex = j;
					break;
				}
			}
		}
		
		for(int index = 0; index < totalSlots; index++)
		{
			Slot slot = menu.slots.get(index);
			if(slot == null)
				continue;
			
			if(drawActiveHighlight && index == highlightIndex)
			{
				int sx = leftPos + slot.x;
				int sy = topPos + slot.y;
				graphics.fill(sx, sy, sx + 16, sy + 16, activeHighlightColor);
				if(mouseX >= sx && mouseX < sx + 16 && mouseY >= sy
					&& mouseY < sy + 16)
				{
					if(SlotHighlighter.INSTANCE.isChestSearchActive())
					{
						ChestSearchScreen.forgetSlotSelection(
							SlotHighlighter.INSTANCE.getChestSearchDimension(),
							SlotHighlighter.INSTANCE.getChestSearchPos(),
							SlotHighlighter.INSTANCE.getChestSearchSlot());
						SlotHighlighter.INSTANCE.forgetChestSearchSelection();
					}else if(SlotHighlighter.INSTANCE.isStickyActive())
						SlotHighlighter.INSTANCE
							.clearEnchantmentHandlerActive();
				}
			}
			
			if(!drawSlotNumbers)
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
			graphics.text(font, label, textX, textY, color, true);
		}
	}
	
	@Inject(method = "mouseClicked", at = @At("HEAD"))
	private void wurst$clearSlotHighlightOnClick(MouseButtonEvent event,
		boolean doubleClick, CallbackInfoReturnable<Boolean> cir)
	{
		int targetSlot = SlotHighlighter.INSTANCE.getChestSearchSlot();
		if(targetSlot < 0)
			targetSlot = SlotHighlighter.INSTANCE.getEnchantmentHandlerSlot();
		if(targetSlot < 0 || targetSlot >= menu.slots.size())
			return;
		Slot slot = menu.slots.get(targetSlot);
		if(slot == null)
			return;
		double mouseX = event.x();
		double mouseY = event.y();
		int sx = leftPos + slot.x;
		int sy = topPos + slot.y;
		if(mouseX >= sx && mouseX < sx + 16 && mouseY >= sy && mouseY < sy + 16)
		{
			if(SlotHighlighter.INSTANCE.isChestSearchActive())
			{
				ChestSearchScreen.forgetSlotSelection(
					SlotHighlighter.INSTANCE.getChestSearchDimension(),
					SlotHighlighter.INSTANCE.getChestSearchPos(),
					SlotHighlighter.INSTANCE.getChestSearchSlot());
				SlotHighlighter.INSTANCE.forgetChestSearchSelection();
			}else
				SlotHighlighter.INSTANCE.clearEnchantmentHandlerActive();
		}
	}
	
	@Inject(method = "removed", at = @At("HEAD"))
	private void wurst$clearSlotHighlightOnClose(CallbackInfo ci)
	{
		SlotHighlighter.INSTANCE.clearActive();
	}
}
