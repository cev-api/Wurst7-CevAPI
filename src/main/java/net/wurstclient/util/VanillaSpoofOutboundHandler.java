/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.function.UnaryOperator;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.fabricmc.fabric.impl.networking.payload.FriendlyByteBufLoginQueryResponse;
import net.minecraft.network.protocol.login.ServerboundCustomQueryAnswerPacket;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

/** Filters packet objects once on the event loop, before payload encoding. */
public final class VanillaSpoofOutboundHandler extends ChannelDuplexHandler
{
	private final UnaryOperator<Packet<?>> filter;
	private final Runnable disconnect;
	
	public VanillaSpoofOutboundHandler(UnaryOperator<Packet<?>> filter,
		Runnable disconnect)
	{
		this.filter = filter;
		this.disconnect = disconnect;
	}
	
	@Override
	public void write(ChannelHandlerContext context, Object message,
		ChannelPromise promise) throws Exception
	{
		if(!(message instanceof Packet<?> packet))
		{
			// Includes protocol transition tasks and already encoded buffers.
			context.write(message, promise);
			return;
		}
		
		Packet<?> filtered;
		try
		{
			filtered = filter.apply(packet);
		}catch(RuntimeException | Error e)
		{
			releasePacket(packet);
			throw e;
		}
		if(filtered == null)
		{
			releasePacket(packet);
			// Complete callbacks for intentional suppression; never send a null
			// packet or leave a write promise pending.
			promise.trySuccess();
			return;
		}
		
		if(filtered != packet)
			releasePacket(packet);
		context.write(filtered, promise);
	}
	
	private static void releasePacket(Packet<?> packet)
	{
		if(packet instanceof ServerboundCustomPayloadPacket custom)
			ReferenceCountUtil.release(custom.payload());
		else if(packet instanceof ServerboundCustomQueryAnswerPacket answer)
		{
			if(answer
				.payload() instanceof FriendlyByteBufLoginQueryResponse payload)
				ReferenceCountUtil.release(payload.data());
			else
				ReferenceCountUtil.release(answer.payload());
		}
		ReferenceCountUtil.release(packet);
	}
	
	@Override
	public void channelInactive(ChannelHandlerContext context) throws Exception
	{
		disconnect.run();
		context.fireChannelInactive();
	}
}
