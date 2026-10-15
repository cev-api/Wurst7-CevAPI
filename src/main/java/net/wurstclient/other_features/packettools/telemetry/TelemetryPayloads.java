/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools.telemetry;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Small, versioned opt-in protocol shared by the separate client/server
 * artifacts.
 */
public final class TelemetryPayloads
{
	private static boolean registered;
	
	private TelemetryPayloads()
	{}
	
	public record Subscription(int version, boolean enabled, String captureId)
		implements CustomPacketPayload
	{
		public static final Type<Subscription> TYPE = new Type<>(Identifier
			.fromNamespaceAndPath("cev_tick_telemetry", "subscribe_v1"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Subscription> CODEC =
			StreamCodec.of((buf, value) -> {
				buf.writeVarInt(value.version);
				buf.writeBoolean(value.enabled);
				buf.writeUtf(value.captureId, 36);
			}, buf -> new Subscription(buf.readVarInt(), buf.readBoolean(),
				buf.readUtf(36)));
		
		public Type<Subscription> type()
		{
			return TYPE;
		}
	}
	
	public record Sample(String captureId, String json)
		implements CustomPacketPayload
	{
		public static final Type<Sample> TYPE = new Type<>(
			Identifier.fromNamespaceAndPath("cev_tick_telemetry", "sample_v1"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Sample> CODEC =
			StreamCodec.of((buf, value) -> {
				buf.writeUtf(value.captureId, 36);
				buf.writeUtf(value.json, 16384);
			}, buf -> new Sample(buf.readUtf(36), buf.readUtf(16384)));
		
		public Type<Sample> type()
		{
			return TYPE;
		}
	}
	
	public static synchronized void register()
	{
		if(registered)
			return;
		registered = true;
		PayloadTypeRegistry.serverboundPlay().register(Subscription.TYPE,
			Subscription.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Sample.TYPE,
			Sample.CODEC);
	}
}
