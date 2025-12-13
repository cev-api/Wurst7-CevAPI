/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.AntiDropHack;
import net.wurstclient.hacks.EnchantmentHandlerHack;

@Mixin(AbstractContainerScreen.class)
public abstract class HandledScreenMixin
{
	@Shadow
	public abstract net.minecraft.world.inventory.AbstractContainerMenu getMenu();
	
	@Inject(at = @At("HEAD"),
		method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ClickType;)V",
		cancellable = true)
	private void onMouseClick(Slot slot, int slotId, int button,
		ClickType actionType, CallbackInfo ci)
	{
		if(actionType != ClickType.THROW && slotId != -999)
			return;
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		AntiDropHack antiDrop = WurstClient.INSTANCE.getHax().antiDropHack;
		if(!antiDrop.isEnabled())
			return;
		
		ItemStack stack = ItemStack.EMPTY;
		
		if(slotId == -999)
			stack = getMenu().getCarried();
		else if(slot != null)
			stack = slot.getItem();
		
		if(antiDrop.shouldBlock(stack))
			ci.cancel();
	}
	
	@Inject(at = @At("HEAD"), method = "mouseClicked(DDI)Z", cancellable = true)
	private void wurst$handleMouseClick(double mouseX, double mouseY,
		int button, CallbackInfoReturnable<Boolean> cir)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		EnchantmentHandlerHack enchantHack =
			WurstClient.INSTANCE.getHax().enchantmentHandlerHack;
		if(enchantHack != null && enchantHack.isEnabled()
			&& enchantHack.handleMouseClick(
				(AbstractContainerScreen<?>)(Object)this, mouseX, mouseY,
				button))
		{
			cir.setReturnValue(true);
			cir.cancel();
		}
	}
	
	@Inject(at = @At("TAIL"),
		method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V")
	private void wurst$renderOverlay(GuiGraphics context, int mouseX,
		int mouseY, float delta, CallbackInfo ci)
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		EnchantmentHandlerHack enchantHack =
			WurstClient.INSTANCE.getHax().enchantmentHandlerHack;
		
		if(enchantHack != null && enchantHack.isEnabled())
			enchantHack.renderOnHandledScreen(
				(AbstractContainerScreen<?>)(Object)this, context, delta);
	}
}
