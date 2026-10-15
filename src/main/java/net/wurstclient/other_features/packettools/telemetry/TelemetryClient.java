/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features.packettools.telemetry;

import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.wurstclient.WurstClient;

/**
 * No probing: only explicitly trusted addresses that advertise the optional
 * protocol are contacted.
 */
public final class TelemetryClient
{
	private static final Set<String> trusted = new HashSet<>();
	private static volatile String subscribedSession;
	private static volatile boolean trustedConnection;
	private static final Map<TelemetryPayloads.Sample, Boolean> observed =
		new WeakHashMap<>();
	
	private TelemetryClient()
	{}
	
	public static void initialize()
	{
		TelemetryPayloads.register();
		Path path = FabricLoader.getInstance().getConfigDir()
			.resolve("packettools-telemetry-trust.json");
		try
		{
			if(!Files.exists(path))
				Files.writeString(path,
					new GsonBuilder().setPrettyPrinting().create()
						.toJson(Map.of("trustedServers", List.of())),
					StandardOpenOption.CREATE_NEW);
			JsonObject config = JsonParser.parseString(Files.readString(path))
				.getAsJsonObject();
			if(config.get("trustedServers") instanceof JsonArray servers)
				for(JsonElement server : servers)
					trusted.add(normalize(server.getAsString()));
		}catch(Exception error)
		{
			trusted.clear();
			com.mojang.logging.LogUtils.getLogger().warn(
				"Server telemetry trust configuration unavailable; authoritative telemetry disabled",
				error);
		}
		ClientPlayNetworking.registerGlobalReceiver(
			TelemetryPayloads.Sample.TYPE, (sample, context) -> receive(sample,
				System.nanoTime(), "FABRIC_CLIENT_RECEIVER"));
	}
	
	private static String normalize(String address)
	{
		String result = address.trim().toLowerCase(Locale.ROOT);
		return result.indexOf(':') < 0 ? result + ":25565" : result;
	}
	
	public static void poll(String session)
	{
		Minecraft mc = Minecraft.getInstance();
		var server = mc.getCurrentServer();
		trustedConnection =
			server != null && trusted.contains(normalize(server.ip));
		if(!trustedConnection || mc.getConnection() == null
			|| session.equals(subscribedSession) || !ClientPlayNetworking
				.canSend(TelemetryPayloads.Subscription.TYPE))
			return;
		ClientPlayNetworking
			.send(new TelemetryPayloads.Subscription(1, true, session));
		subscribedSession = session;
	}
	
	public static void stop(String session)
	{
		if(!session.equals(subscribedSession))
			return;
		subscribedSession = null;
		trustedConnection = false;
		Minecraft mc = Minecraft.getInstance();
		var connection = mc.getConnection();
		mc.execute(() -> {
			if(mc.getConnection() == connection && connection != null
				&& subscribedSession == null && ClientPlayNetworking
					.canSend(TelemetryPayloads.Subscription.TYPE))
				ClientPlayNetworking.send(
					new TelemetryPayloads.Subscription(1, false, session));
		});
		synchronized(observed)
		{
			observed.clear();
		}
	}
	
	public static boolean isTrustedConnection()
	{
		return trustedConnection;
	}
	
	public static void decoded(Packet<?> packet, long now)
	{
		if(packet instanceof ClientboundCustomPayloadPacket custom
			&& custom.payload() instanceof TelemetryPayloads.Sample sample)
			receive(sample, now, "DECODED_NETWORK_PAYLOAD");
	}
	
	private static void receive(TelemetryPayloads.Sample sample, long now,
		String stage)
	{
		if(!trustedConnection || !sample.captureId().equals(subscribedSession))
			return;
		synchronized(observed)
		{
			if(observed.put(sample, true) != null)
				return;
			if(observed.size() > 256)
				observed.remove(observed.keySet().iterator().next());
		}
		WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.recordAuthoritativeTelemetry(sample.captureId(), sample.json(),
				now, stage);
	}
}
