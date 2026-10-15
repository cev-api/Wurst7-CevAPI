/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.client.Minecraft;
import net.wurstclient.WurstClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public abstract class ForensicPacketHandlerMixin
{
	@WrapOperation(method = "handle",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
	private void wurst$handler(Packet packet, PacketListener listener,
		Operation<Void> original)
	{
		if(!Minecraft.getInstance().isSameThread())
		{
			original.call(packet, listener);
			return;
		}
		var tools = WurstClient.INSTANCE.getOtfs().packetToolsOtf;
		tools.recordForensicsPreApplication(packet);
		try
		{
			original.call(packet, listener);
		}finally
		{
			tools.recordForensicHandlerEnd(packet);
		}
	}
}
