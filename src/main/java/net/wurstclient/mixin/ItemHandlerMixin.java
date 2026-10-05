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

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.item.ItemEntity;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.itemhandler.ItemHandlerHack;

@Mixin(ItemEntity.class)
public class ItemHandlerMixin
{
	@Inject(at = @At("HEAD"),
		method = "playerTouch(Lnet/minecraft/world/entity/player/Player;)V",
		cancellable = true)
	private void onPlayerTouch(Player player, CallbackInfo ci)
	{
		ItemEntity self = (ItemEntity)(Object)this;
		if(player != WurstClient.MC.player)
			return;
		ItemHandlerHack hack = WurstClient.INSTANCE.getHax().itemHandlerHack;
		if(hack != null && hack.isEnabled())
		{
			if(!hack.shouldAllowPickup(self))
				ci.cancel();
			return;
		}
		if(!WurstClient.INSTANCE.getHax().autoLootHack.shouldAllowPickup(self))
			ci.cancel();
	}
}
