/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altbot;

import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.BuiltinFlags;
import org.geysermc.mcprotocollib.network.ProxyInfo;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.wurstclient.altmanager.MinecraftProfile;
import net.wurstclient.altmanager.TokenAlt;

/**
 * One real authenticated protocol-client connection. Owns the Netty session
 * and a dedicated packet-handler executor, tracks connection state and the
 * server-side position, and reports changes back to the {@link AltBotManager}.
 *
 * <p>
 * All mutable fields are volatile / atomic so the GUI can read snapshots from
 * the render thread while Netty threads update the live state. Minecraft GUI
 * calls are never made from Netty callbacks.
 */
public final class AltBotSession
{
	private static final PlainTextComponentSerializer TEXT =
		PlainTextComponentSerializer.plainText();
	
	private final AltBotManager manager;
	private final TokenAlt alt;
	private final MinecraftProfile profile;
	private final String host;
	private final int port;
	private final UUID uuid;
	private final String username;
	private final String label;
	
	private final BotChatSession chatSession;
	private final PlayerCertificates certificates;
	private final ProxyInfo proxy;
	
	private final AtomicBoolean cleanedUp = new AtomicBoolean();
	
	private volatile BotState state = BotState.DISCONNECTED;
	private volatile String lastError;
	private volatile long connectionStart;
	private volatile boolean ready;
	
	private volatile double x;
	private volatile double y;
	private volatile double z;
	private volatile boolean havePos;
	
	private final ArrayDeque<String> messageBuffer = new ArrayDeque<>();
	private final ArrayDeque<String> chatHistory = new ArrayDeque<>();
	private final Object bufferLock = new Object();
	
	private volatile ClientSession session;
	private volatile ExecutorService packetExecutor;
	
	AltBotSession(AltBotManager manager, TokenAlt alt, MinecraftProfile profile,
		String host, int port, PlayerCertificates certificates, ProxyInfo proxy)
	{
		this.manager = manager;
		this.alt = alt;
		this.profile = profile;
		this.host = host;
		this.port = port;
		this.proxy = proxy;
		uuid = profile.getUUID();
		username = profile.getName();
		label = AltBotUtils.getLabel(username, uuid);
		this.certificates = certificates;
		chatSession = new BotChatSession(uuid, certificates);
	}
	
	/** Connects to the server. Must be called from a worker thread. */
	void start()
	{
		if(state != BotState.DISCONNECTED)
			return;
		
		state = BotState.CONNECTING;
		connectionStart = System.currentTimeMillis();
		lastError = null;
		ready = false;
		havePos = false;
		
		try
		{
			GameProfile gameProfile = new GameProfile(uuid, username);
			MinecraftProtocol protocol =
				new MinecraftProtocol(gameProfile, profile.getAccessToken());
			
			packetExecutor = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "Wurst AltBot packets " + label);
				t.setDaemon(true);
				return t;
			});
			
			ClientNetworkSessionFactory factory = ClientNetworkSessionFactory
				.factory()
				.setRemoteSocketAddress(new InetSocketAddress(host, port))
				.setProtocol(protocol).setPacketHandlerExecutor(packetExecutor);
			if(proxy != null)
				factory = factory.setProxy(proxy);
			ClientSession client = factory.create();
			session = client;
			
			client.setFlag(MinecraftConstants.SESSION_SERVICE_KEY,
				new SessionService());
			client.setFlag(MinecraftConstants.CLIENT_HOST, host);
			client.setFlag(MinecraftConstants.CLIENT_PORT, port);
			client.setFlag(MinecraftConstants.SEND_BLANK_KNOWN_PACKS_RESPONSE,
				true);
			// Detect dead connections so stale bots are cleaned up.
			client.setFlag(BuiltinFlags.READ_TIMEOUT, 30);
			client.setFlag(BuiltinFlags.CLIENT_CONNECT_TIMEOUT, 10);
			
			client.addListener(new SessionAdapter()
			{
				@Override
				public void connected(ConnectedEvent event)
				{
					manager.log(label, "connected to " + host + ":" + port
						+ ", sending login handshake");
					state = BotState.LOGIN;
				}
				
				@Override
				public void packetReceived(Session s, Packet packet)
				{
					handlePacket(packet);
				}
				
				@Override
				public void disconnected(DisconnectedEvent event)
				{
					handleDisconnected(event);
				}
			});
			
			state = BotState.LOGIN;
			client.connect();
			
		}catch(Throwable e)
		{
			fail("Connection failed: " + safeMessage(e));
			manager.log(label, "connection setup failed: " + safeMessage(e));
		}
	}
	
	private void handlePacket(Packet packet)
	{
		if(packet instanceof ClientboundPingPacket ping)
		{
			if(session != null)
				session.send(new ServerboundPongPacket(ping.getId()));
			return;
		}
		
		if(packet instanceof ClientboundLoginFinishedPacket)
		{
			manager.log(label, "login finished, entering configuration");
			state = BotState.CONFIGURING;
			sendClientInformation();
			return;
		}
		
		if(packet instanceof ClientboundLoginPacket loginPacket)
		{
			manager.log(label, "reached play state (entity id "
				+ loginPacket.getEntityId() + ")");
			state = BotState.PLAY;
			ready = true;
			havePos = false;
			sendClientInformation();
			chatSession.sendSessionUpdate(session);
			manager.onBotReady(this);
			return;
		}
		
		if(packet instanceof ClientboundPlayerPositionPacket posPacket)
		{
			applyPosition(posPacket);
			int teleportId = posPacket.getId();
			if(teleportId >= 0 && session != null)
				session
					.send(new ServerboundAcceptTeleportationPacket(teleportId));
			return;
		}
		
		if(packet instanceof ClientboundSystemChatPacket sc)
		{
			addMessage("[SERVER] " + toPlainText(sc.getContent()));
			return;
		}
		
		if(packet instanceof ClientboundPlayerChatPacket pc)
		{
			String msg = pc.getUnsignedContent() != null
				? toPlainText(pc.getUnsignedContent()) : pc.getContent();
			addMessage("<" + toPlainText(pc.getName()) + "> " + msg);
			return;
		}
		
		if(packet instanceof ClientboundDisguisedChatPacket dc)
		{
			addMessage("<" + toPlainText(dc.getName()) + "> "
				+ toPlainText(dc.getMessage()));
			return;
		}
		
		handleResourcePack(packet);
	}
	
	private void sendClientInformation()
	{
		if(session == null)
			return;
		session.send(new ServerboundClientInformationPacket("en_us", 12,
			ChatVisibility.FULL, true, Arrays.asList(SkinPart.values()),
			HandPreference.RIGHT_HAND, false, true, ParticleStatus.ALL));
	}
	
	private void applyPosition(ClientboundPlayerPositionPacket packet)
	{
		Vector3d position = packet.getPosition();
		List<PositionElement> relatives = packet.getRelatives();
		double nx = position.getX();
		double ny = position.getY();
		double nz = position.getZ();
		if(relatives.contains(PositionElement.X))
			nx += x;
		if(relatives.contains(PositionElement.Y))
			ny += y;
		if(relatives.contains(PositionElement.Z))
			nz += z;
		x = nx;
		y = ny;
		z = nz;
		havePos = true;
	}
	
	private void handleResourcePack(Packet packet)
	{
		try
		{
			String simple = packet.getClass().getSimpleName();
			if(!simple.contains("ResourcePack")
				|| !simple.startsWith("Clientbound"))
				return;
			
			UUID id = null;
			try
			{
				id = (UUID)packet.getClass().getMethod("getId").invoke(packet);
			}catch(Throwable ignored)
			{}
			if(id == null)
				try
				{
					id = (UUID)packet.getClass().getMethod("getPackId")
						.invoke(packet);
				}catch(Throwable ignored)
				{}
			if(id == null)
				id = UUID.randomUUID();
			
			UUID packId = id;
			String[] statuses =
				{"ACCEPTED", "DOWNLOADED", "SUCCESSFULLY_LOADED"};
			String[] candidates = {
				"org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket",
				"org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundResourcePackPacket",
				"org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundResourcePackPacket"};
			for(String fqcn : candidates)
				try
				{
					Class<?> cls = Class.forName(fqcn);
					for(var ctor : cls.getConstructors())
					{
						Class<?>[] params = ctor.getParameterTypes();
						if(params.length == 2 && params[0] == UUID.class
							&& params[1].isEnum())
						{
							Object status = pickEnum(params[1], statuses);
							if(status != null && session != null)
							{
								session.send(
									(Packet)ctor.newInstance(packId, status));
								return;
							}
						}
					}
				}catch(Throwable ignored)
				{}
			
		}catch(Throwable ignored)
		{}
	}
	
	private static Object pickEnum(Class<?> enumType, String[] preferred)
	{
		for(String name : preferred)
			for(Object constant : enumType.getEnumConstants())
				if(constant.toString().equalsIgnoreCase(name))
					return constant;
		return enumType.getEnumConstants().length > 0
			? enumType.getEnumConstants()[0] : null;
	}
	
	private void handleDisconnected(DisconnectedEvent event)
	{
		String reason = event.getReason() != null
			? toPlainText(event.getReason()) : "(no reason given)";
		boolean expected = state == BotState.DISCONNECTING;
		
		manager.log(label, "disconnected ("
			+ (expected ? "expected" : "unexpected") + "): " + reason);
		
		ready = false;
		lastError = expected ? null : "Disconnected: " + reason;
		state = expected ? BotState.DISCONNECTED : BotState.FAILED;
		cleanup();
		manager.onBotDisconnected(this, reason, expected);
	}
	
	/** Requests a clean disconnect. Must be called from a worker thread. */
	void disconnect(String reason)
	{
		state = BotState.DISCONNECTING;
		ClientSession s = session;
		if(s != null && s.isConnected())
			try
			{
				s.disconnect(reason);
				// DisconnectedEvent fires synchronously inside disconnect().
				return;
			}catch(Throwable e)
			{
				manager.log(label,
					"error while disconnecting: " + safeMessage(e));
			}
		
		ready = false;
		lastError = null;
		cleanup();
		state = BotState.DISCONNECTED;
		manager.onBotDisconnected(this, "disconnect", true);
	}
	
	/** Frees resources. Safe to call multiple times. */
	private void cleanup()
	{
		if(!cleanedUp.compareAndSet(false, true))
			return;
		
		session = null;
		ExecutorService executor = packetExecutor;
		packetExecutor = null;
		if(executor != null)
			executor.shutdown();
	}
	
	private void fail(String message)
	{
		lastError = message;
		ready = false;
		state = BotState.FAILED;
		cleanup();
	}
	
	private void addMessage(String message)
	{
		synchronized(bufferLock)
		{
			messageBuffer.addLast(message);
			while(messageBuffer.size() > 64)
				messageBuffer.removeFirst();
		}
	}
	
	private void addChatHistory(String text)
	{
		synchronized(bufferLock)
		{
			chatHistory.addLast(text);
			while(chatHistory.size() > 32)
				chatHistory.removeFirst();
		}
	}
	
	/** @return true if the bot is in the play state and can send packets. */
	public boolean isReady()
	{
		return ready && session != null && session.isConnected();
	}
	
	/**
	 * Sends chat or a command through this bot. Text starting with "/" is sent
	 * as a command.
	 *
	 * @return true if a packet was sent.
	 */
	public boolean sendChat(String text)
	{
		if(!isReady())
			return false;
		ClientSession s = session;
		boolean sent = chatSession.send(s, text);
		if(sent)
		{
			addChatHistory(text);
			manager.log(label, "chat sent ("
				+ (text.startsWith("/") ? "command" : "message") + ")");
		}
		return sent;
	}
	
	/** Sends a command through this bot, prepending "/" if needed. */
	public boolean sendCommand(String command)
	{
		if(command == null || command.isBlank())
			return false;
		String text = command.startsWith("/") ? command : "/" + command;
		return sendChat(text);
	}
	
	BotState getState()
	{
		return state;
	}
	
	public String getLastError()
	{
		return lastError;
	}
	
	long getConnectionStart()
	{
		return connectionStart;
	}
	
	TokenAlt getAlt()
	{
		return alt;
	}
	
	UUID getUuid()
	{
		return uuid;
	}
	
	String getUsername()
	{
		return username;
	}
	
	String getLabel()
	{
		return label;
	}
	
	String getServer()
	{
		return host + ":" + port;
	}
	
	boolean hasPosition()
	{
		return havePos;
	}
	
	double getX()
	{
		return x;
	}
	
	double getY()
	{
		return y;
	}
	
	double getZ()
	{
		return z;
	}
	
	/** @return a copy of the recent received-message buffer. */
	public List<String> getMessageBuffer()
	{
		synchronized(bufferLock)
		{
			return List.copyOf(messageBuffer);
		}
	}
	
	/** @return a copy of the recent sent-chat buffer. */
	public List<String> getChatHistory()
	{
		synchronized(bufferLock)
		{
			return List.copyOf(chatHistory);
		}
	}
	
	/**
	 * Renders a chat component to plain text, handling proxy translate keys.
	 */
	private static String toPlainText(Component component)
	{
		try
		{
			if(component instanceof TranslatableComponent translatable)
			{
				if("%s".equals(translatable.key())
					&& !translatable.arguments().isEmpty())
					return argText(translatable.arguments().get(0));
				if("multiplayer.player.joined".equals(translatable.key())
					&& !translatable.arguments().isEmpty())
					return argText(translatable.arguments().get(0))
						+ " joined the game";
				if("multiplayer.player.left".equals(translatable.key())
					&& !translatable.arguments().isEmpty())
					return argText(translatable.arguments().get(0))
						+ " left the game";
			}
			return TEXT.serialize(component);
		}catch(Exception e)
		{
			return String.valueOf(component);
		}
	}
	
	private static String argText(TranslationArgument argument)
	{
		Object value = argument.value();
		if(value instanceof Component component)
			return TEXT.serialize(component);
		return String.valueOf(value);
	}
	
	private static String safeMessage(Throwable t)
	{
		String m = t.getMessage();
		return m == null || m.isBlank() ? t.getClass().getSimpleName() : m;
	}
}
