/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.EnchantmentHandlerHack;

@Mixin(ContainerEventHandler.class)
public interface ContainerEventHandlerMixin
{
	@Inject(at = @At("HEAD"),
		method = "mouseScrolled(DDDD)Z",
		cancellable = true)
	default void wurst$handleMouseScroll(double mouseX, double mouseY,
		double horizontalAmount, double verticalAmount,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(!(this instanceof AbstractContainerScreen<?> screen))
			return;
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		EnchantmentHandlerHack enchantHack =
			WurstClient.INSTANCE.getHax().enchantmentHandlerHack;
		if(enchantHack != null && enchantHack.isEnabled() && enchantHack
			.handleMouseScroll(screen, mouseX, mouseY, verticalAmount))
		{
			cir.setReturnValue(true);
		}
	}
}
