/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.EnchantmentHandlerHack;

@Mixin(ItemStack.class)
public class ItemStackMixin
{
	@Inject(method = "getTooltipLines", at = @At("RETURN"), cancellable = true)
	private void colorEnchantmentTooltipLines(Item.TooltipContext context,
		Player player, TooltipFlag flag,
		CallbackInfoReturnable<List<Component>> cir)
	{
		if(WurstClient.INSTANCE == null
			|| WurstClient.INSTANCE.getHax() == null)
			return;
		
		EnchantmentHandlerHack hack =
			WurstClient.INSTANCE.getHax().enchantmentHandlerHack;
		if(hack == null || !hack.isEnabled()
			|| !hack.shouldShowColorsInTooltips())
			return;
		
		ItemStack stack = (ItemStack)(Object)this;
		List<Component> colored = new ArrayList<>();
		for(Component line : cir.getReturnValue())
			colored
				.add(EnchantmentHandlerHack.colorizeTooltipLine(stack, line));
		cir.setReturnValue(colored);
	}
}
