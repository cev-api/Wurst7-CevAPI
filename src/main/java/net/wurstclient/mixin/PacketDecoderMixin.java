/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.wurstclient.hacks.NbtFilterHack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketDecoder.class)
public abstract class PacketDecoderMixin
{
	@Shadow
	@Final
	private ProtocolInfo<?> protocolInfo;
	@org.spongepowered.asm.mixin.Unique
	private int wurst$outputStart;
	
	@Inject(method = "decode", at = @At("HEAD"), cancellable = true)
	private void onDecode(ChannelHandlerContext context, ByteBuf buf,
		List<Object> out, CallbackInfo ci)
	{
		wurst$outputStart = out.size();
		if(!NbtFilterHack.shouldDropRawClientboundPacket(protocolInfo, buf))
			return;
		
		buf.skipBytes(buf.readableBytes());
		ci.cancel();
	}
	
	@Inject(method = "decode", at = @At("TAIL"))
	private void wurst$decoded(ChannelHandlerContext context, ByteBuf buf,
		List<Object> out, CallbackInfo ci)
	{
		for(int i = wurst$outputStart; i < out.size(); i++)
			if(out.get(
				i) instanceof net.minecraft.network.protocol.Packet<?> packet)
			{
				net.wurstclient.WurstClient.INSTANCE.getOtfs().packetToolsOtf
					.recordForensicTiming(packet, "S2C", "DECODED");
			}
	}
}
