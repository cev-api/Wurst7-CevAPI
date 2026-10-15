/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import net.minecraft.network.PacketProcessor;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.wurstclient.WurstClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketProcessor.class)
public abstract class ForensicPacketScheduleMixin
{
	@Inject(method = "scheduleIfPossible", at = @At("HEAD"))
	private void wurst$schedule(PacketListener listener, Packet<?> packet,
		CallbackInfo ci)
	{
		WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.recordForensicTiming(packet, "S2C", "CLIENT_SCHEDULE_REQUESTED");
	}
}
