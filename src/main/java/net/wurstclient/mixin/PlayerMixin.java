/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.AirMinerHack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class PlayerMixin
{
	@ModifyReturnValue(
		method = "getDestroySpeed(Lnet/minecraft/world/level/block/state/BlockState;)F",
		at = @At("RETURN"))
	private float airMiner$airMineSpeed(float speed, BlockState state)
	{
		Player player = (Player)(Object)this;
		if(!player.isLocalPlayer())
			return speed;
		if(WurstClient.INSTANCE.getHax() == null)
			return speed;
		AirMinerHack airMiner = WurstClient.INSTANCE.getHax().airMinerHack;
		return airMiner != null && airMiner.shouldBoostMining() ? speed * 5.0F
			: speed;
	}
}
