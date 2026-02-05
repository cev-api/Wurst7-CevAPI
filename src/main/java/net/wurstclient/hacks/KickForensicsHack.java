/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Locale;

import com.google.gson.Gson;
import com.mojang.serialization.JsonOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.ServerObserver;

@SearchTags({"kick", "kicked", "disconnect", "forensics", "packet", "flight"})
public final class KickForensicsHack extends Hack
	implements PacketInputListener, PacketOutputListener, UpdateListener
{
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter
		.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
	
	private final SliderSetting windowSeconds = new SliderSetting("Window (s)",
		"How many seconds of packets and player state to keep before a kick.",
		2, 1, 10, 1, ValueDisplay.INTEGER);
	
	private final ArrayDeque<PacketEntry> packetEntries = new ArrayDeque<>();
	private final ArrayDeque<StateEntry> stateEntries = new ArrayDeque<>();
	private final ArrayDeque<Long> keepAliveC2S = new ArrayDeque<>();
	private final ArrayDeque<Long> keepAliveS2C = new ArrayDeque<>();
	private final ArrayDeque<Long> transactionC2S = new ArrayDeque<>();
	private final ArrayDeque<Long> transactionS2C = new ArrayDeque<>();
	private long lastDisconnectLogMs = -1L;
	
	public KickForensicsHack()
	{
		super("KickForensics");
		setCategory(Category.OTHER);
		addSetting(windowSeconds);
	}
	
	@Override
	protected void onEnable()
	{
		clearAll();
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		clearAll();
	}
	
	@Override
	public void onUpdate()
	{
		long now = System.currentTimeMillis();
		pruneOld(now);
		
		if(MC.player == null)
			return;
		
		Vec3 pos = MC.player.position();
		Vec3 vel = MC.player.getDeltaMovement();
		int tick = MC.player.tickCount;
		boolean onGround = MC.player.onGround();
		float yaw = MC.player.getYRot();
		float pitch = MC.player.getXRot();
		double fallDistance = MC.player.fallDistance;
		int ping = getPlayerPing();
		
		stateEntries.addLast(new StateEntry(now, pos, vel, onGround, tick, yaw,
			pitch, fallDistance, ping));
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		long now = System.currentTimeMillis();
		Packet<?> packet = event.getPacket();
		String summary = buildPacketSummary("C2S", packet);
		boolean movement = packet instanceof ServerboundMovePlayerPacket;
		boolean flightRelated = isFlightRelatedPacket(packet);
		packetEntries.addLast(
			new PacketEntry(now, summary, movement, flightRelated, true));
		
		if(isKeepAlive(packet))
			keepAliveC2S.addLast(now);
		if(isTransaction(packet))
			transactionC2S.addLast(now);
		
		pruneOld(now);
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		long now = System.currentTimeMillis();
		Packet<?> packet = event.getPacket();
		String summary = buildPacketSummary("S2C", packet);
		boolean movement = packet instanceof ClientboundPlayerPositionPacket
			|| isMovementRelatedS2C(packet);
		boolean flightRelated = isFlightRelatedPacket(packet);
		packetEntries.addLast(
			new PacketEntry(now, summary, movement, flightRelated, false));
		
		if(isKeepAlive(packet))
			keepAliveS2C.addLast(now);
		if(isTransaction(packet))
			transactionS2C.addLast(now);
		
		pruneOld(now);
	}
	
	public void onDisconnected(Component reason)
	{
		if(!isEnabled())
			return;
		
		long now = System.currentTimeMillis();
		if(lastDisconnectLogMs == now)
			return;
		
		lastDisconnectLogMs = now;
		pruneOld(now);
		
		StringBuilder out = new StringBuilder(8192);
		out.append("KickForensics @ ")
			.append(TIME_FORMAT.format(Instant.ofEpochMilli(now)));
		out.append("\nPhase: ").append(getPhase());
		out.append("\nServer: ").append(getServerAddress());
		out.append("\nConnection: ").append(getConnectionState());
		out.append("\nServer observer: ").append(getServerObserverStatus());
		out.append("\nTPS: ").append(formatDouble(getServerTps()));
		out.append("\nFlight enabled: ")
			.append(WURST.getHax().flightHack.isEnabled());
		out.append("\nWindow: ").append(windowSeconds.getValueI()).append("s");
		
		String reasonText = reason != null ? reason.getString() : "<null>";
		out.append("\nReason: ").append(reasonText);
		String reasonJson = encodeComponentJson(reason);
		if(reasonJson != null)
			out.append("\nReason JSON: ").append(reasonJson);
		
		out.append("\nKeepAlive cadence C2S: ")
			.append(formatCadence(keepAliveC2S, now));
		out.append("\nKeepAlive cadence S2C: ")
			.append(formatCadence(keepAliveS2C, now));
		out.append("\nTransaction cadence C2S: ")
			.append(formatCadence(transactionC2S, now));
		out.append("\nTransaction cadence S2C: ")
			.append(formatCadence(transactionS2C, now));
		
		out.append("\n\n-- Player state (last ")
			.append(windowSeconds.getValueI()).append("s) --");
		if(stateEntries.isEmpty())
			out.append("\n<no player state recorded>");
		for(StateEntry entry : stateEntries)
			out.append("\n").append(formatStateEntry(entry, now));
		
		out.append("\n\n-- Packets (last ").append(windowSeconds.getValueI())
			.append("s) --");
		if(packetEntries.isEmpty())
			out.append("\n<no packets recorded>");
		for(PacketEntry entry : packetEntries)
			out.append("\n").append(formatPacketEntry(entry, now));
		
		out.append("\n\n-- Flight-related packets (last ")
			.append(windowSeconds.getValueI()).append("s) --");
		boolean anyFlightRelated = false;
		for(PacketEntry entry : packetEntries)
			if(entry.flightRelated)
			{
				anyFlightRelated = true;
				out.append("\n").append(formatPacketEntry(entry, now));
			}
		if(!anyFlightRelated)
			out.append("\n<no flight-related packets recorded>");
		
		out.append("\n\n-- Movement packets sent (last ")
			.append(windowSeconds.getValueI()).append("s) --");
		boolean anyMovement = false;
		for(PacketEntry entry : packetEntries)
			if(entry.movement && entry.c2s)
			{
				anyMovement = true;
				out.append("\n").append(formatPacketEntry(entry, now));
			}
		if(!anyMovement)
			out.append("\n<no movement packets recorded>");
		
		Path logPath = writeLog(out.toString());
		if(logPath != null)
			ChatUtils.message("KickForensics: saved log to " + logPath);
	}
	
	private void clearAll()
	{
		packetEntries.clear();
		stateEntries.clear();
		keepAliveC2S.clear();
		keepAliveS2C.clear();
		transactionC2S.clear();
		transactionS2C.clear();
		lastDisconnectLogMs = -1L;
	}
	
	private void pruneOld(long now)
	{
		long windowMs = getWindowMs();
		pruneDeque(packetEntries, now, windowMs);
		pruneDeque(stateEntries, now, windowMs);
		pruneTimes(keepAliveC2S, now, windowMs);
		pruneTimes(keepAliveS2C, now, windowMs);
		pruneTimes(transactionC2S, now, windowMs);
		pruneTimes(transactionS2C, now, windowMs);
	}
	
	private long getWindowMs()
	{
		return Math.max(1L, windowSeconds.getValueI()) * 1000L;
	}
	
	private static void pruneDeque(ArrayDeque<? extends TimedEntry> deque,
		long now, long windowMs)
	{
		while(true)
		{
			TimedEntry first = deque.peekFirst();
			if(first == null)
				return;
			if(now - first.getTimeMs() > windowMs)
				deque.removeFirst();
			else
				return;
		}
	}
	
	private static void pruneTimes(ArrayDeque<Long> deque, long now,
		long windowMs)
	{
		while(!deque.isEmpty() && now - deque.peekFirst() > windowMs)
			deque.removeFirst();
	}
	
	private String buildPacketSummary(String dir, Packet<?> packet)
	{
		StringBuilder sb = new StringBuilder(128);
		String name = packet.getClass().getSimpleName();
		String fullName = packet.getClass().getName();
		String friendly = getFriendlyPacketName(packet);
		String displayName = friendly != null ? friendly : name;
		sb.append(dir).append(' ').append(displayName);
		if(fullName != null && !fullName.equals(displayName))
			sb.append(" (").append(fullName).append(')');
		
		if(packet instanceof ServerboundMovePlayerPacket move)
		{
			double x = move.getX(Double.NaN);
			double y = move.getY(Double.NaN);
			double z = move.getZ(Double.NaN);
			float yaw = move.getYRot(Float.NaN);
			float pitch = move.getXRot(Float.NaN);
			sb.append(" pos=(").append(formatDouble(x)).append(", ")
				.append(formatDouble(y)).append(", ").append(formatDouble(z))
				.append(")");
			sb.append(" rot=(").append(formatDouble(yaw)).append(", ")
				.append(formatDouble(pitch)).append(")");
			sb.append(" onGround=").append(move.isOnGround());
		}else if(isMovementRelatedS2C(packet))
		{
			PositionInfo posInfo = tryExtractPosition(packet);
			if(posInfo != null && posInfo.hasAny())
			{
				sb.append(" pos=(").append(formatDouble(posInfo.x)).append(", ")
					.append(formatDouble(posInfo.y)).append(", ")
					.append(formatDouble(posInfo.z)).append(")");
				if(posInfo.hasRot)
					sb.append(" rot=(").append(formatDouble(posInfo.yaw))
						.append(", ").append(formatDouble(posInfo.pitch))
						.append(")");
			}
			
			if(isCorrectionPacket(packet))
				sb.append(" [correction]");
		}
		
		if(isVelocityPacket(name))
			sb.append(" [velocity]");
		if(isKeepAlive(packet))
			sb.append(" [keepalive]");
		if(isTransaction(packet))
			sb.append(" [transaction]");
		
		return sb.toString();
	}
	
	private static boolean isMovementRelatedS2C(Packet<?> packet)
	{
		String name =
			packet.getClass().getSimpleName().toLowerCase(Locale.ROOT);
		return name.contains("position") || name.contains("teleport")
			|| name.contains("move") || name.contains("motion")
			|| name.contains("velocity");
	}
	
	private static boolean isFlightRelatedPacket(Packet<?> packet)
	{
		String name =
			packet.getClass().getSimpleName().toLowerCase(Locale.ROOT);
		return name.contains("move") || name.contains("position")
			|| name.contains("teleport") || name.contains("velocity")
			|| name.contains("motion") || name.contains("player")
			|| name.contains("abilities") || name.contains("flying")
			|| name.contains("elytra") || name.contains("vehicle");
	}
	
	private static boolean isCorrectionPacket(Packet<?> packet)
	{
		if(packet instanceof ClientboundPlayerPositionPacket)
			return true;
		
		String name =
			packet.getClass().getSimpleName().toLowerCase(Locale.ROOT);
		return name.contains("playerposition") || name.contains("teleport");
	}
	
	private static boolean isVelocityPacket(String name)
	{
		String lower = name.toLowerCase(Locale.ROOT);
		return lower.contains("velocity") || lower.contains("motion");
	}
	
	private static boolean isKeepAlive(Packet<?> packet)
	{
		String name =
			packet.getClass().getSimpleName().toLowerCase(Locale.ROOT);
		return name.contains("keepalive") || name.contains("ping");
	}
	
	private static boolean isTransaction(Packet<?> packet)
	{
		String name =
			packet.getClass().getSimpleName().toLowerCase(Locale.ROOT);
		return name.contains("transaction") || name.contains("confirm")
			|| name.contains("ack");
	}
	
	private String formatPacketEntry(PacketEntry entry, long now)
	{
		long delta = now - entry.timeMs;
		return String.format(Locale.ROOT, "%+dms %s", delta, entry.summary);
	}
	
	private String formatStateEntry(StateEntry entry, long now)
	{
		long delta = now - entry.timeMs;
		return String.format(Locale.ROOT,
			"%+dms tick=%d pos=(%s, %s, %s) vel=(%s, %s, %s) onGround=%s yaw=%s pitch=%s fall=%.2f ping=%d",
			delta, entry.tickCount, formatDouble(entry.pos.x),
			formatDouble(entry.pos.y), formatDouble(entry.pos.z),
			formatDouble(entry.vel.x), formatDouble(entry.vel.y),
			formatDouble(entry.vel.z), entry.onGround, formatDouble(entry.yaw),
			formatDouble(entry.pitch), entry.fallDistance, entry.pingMs);
	}
	
	private static String formatCadence(ArrayDeque<Long> times, long now)
	{
		if(times.size() < 2)
			return "<insufficient data>";
		
		long prev = -1L;
		long sum = 0L;
		int count = 0;
		long last = -1L;
		for(Long t : times)
		{
			if(prev >= 0)
			{
				sum += t - prev;
				count++;
				last = t - prev;
			}
			prev = t;
		}
		if(count <= 0)
			return "<insufficient data>";
		
		long avg = sum / count;
		long age = now - times.peekLast();
		return avg + "ms avg, " + last + "ms last, " + age + "ms ago";
	}
	
	private Path writeLog(String content)
	{
		Path folder = WURST.getWurstFolder();
		Path file = folder.resolve("kick-forensics.log");
		String payload =
			content + System.lineSeparator() + "----" + System.lineSeparator();
		try
		{
			Files.writeString(file, payload, StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
			return file;
		}catch(IOException e)
		{
			return null;
		}
	}
	
	private String encodeComponentJson(Component reason)
	{
		if(reason == null)
			return null;
		
		try
		{
			Object json = ComponentSerialization.CODEC
				.encodeStart(JsonOps.INSTANCE, reason).getOrThrow();
			return new Gson().toJson(json);
		}catch(Throwable t)
		{
			return null;
		}
	}
	
	private String getPhase()
	{
		if(MC.level != null)
			return "play";
		if(MC.getConnection() != null)
			return "login";
		return "unknown";
	}
	
	private String getServerAddress()
	{
		ServerObserver observer = WURST.getServerObserver();
		if(observer == null)
			return "<unknown>";
		String address = observer.getServerAddress();
		return address != null ? address : "<unknown>";
	}
	
	private String getServerObserverStatus()
	{
		ServerObserver observer = WURST.getServerObserver();
		return observer != null ? observer.getDebugStatus() : "<unknown>";
	}
	
	private double getServerTps()
	{
		ServerObserver observer = WURST.getServerObserver();
		return observer != null ? observer.getTps() : Double.NaN;
	}
	
	private String getConnectionState()
	{
		if(MC.getConnection() == null)
			return "null";
		
		try
		{
			var connection = MC.getConnection().getConnection();
			if(connection == null)
				return "netty=null";
			boolean connected = connection.isConnected();
			return connected ? "connected" : "disconnected";
		}catch(Throwable t)
		{
			return "unknown";
		}
	}
	
	private int getPlayerPing()
	{
		try
		{
			var handler = MC.getConnection();
			if(handler == null || MC.player == null)
				return -1;
			var entry = handler.getPlayerInfo(MC.player.getUUID());
			if(entry == null)
				return -1;
			
			try
			{
				java.lang.reflect.Method m =
					entry.getClass().getMethod("getLatency");
				Object o = m.invoke(entry);
				if(o instanceof Integer)
					return (Integer)o;
				if(o instanceof Long)
					return ((Long)o).intValue();
			}catch(NoSuchMethodException ignored)
			{}
			
			try
			{
				java.lang.reflect.Method m =
					entry.getClass().getMethod("getLatencyMs");
				Object o = m.invoke(entry);
				if(o instanceof Integer)
					return (Integer)o;
				if(o instanceof Long)
					return ((Long)o).intValue();
			}catch(NoSuchMethodException ignored)
			{}
			
			try
			{
				java.lang.reflect.Field f =
					entry.getClass().getDeclaredField("latency");
				f.setAccessible(true);
				Object o = f.get(entry);
				if(o instanceof Integer)
					return (Integer)o;
				if(o instanceof Long)
					return ((Long)o).intValue();
			}catch(NoSuchFieldException ignored)
			{}
		}catch(Throwable t)
		{}
		
		return -1;
	}
	
	private static String formatDouble(double value)
	{
		if(Double.isNaN(value))
			return "NaN";
		return String.format(Locale.ROOT, "%.3f", value);
	}
	
	private static String getFriendlyPacketName(Packet<?> packet)
	{
		if(packet instanceof ServerboundMovePlayerPacket.PosRot)
			return "ServerboundMovePlayerPacket.PosRot";
		if(packet instanceof ServerboundMovePlayerPacket.Pos)
			return "ServerboundMovePlayerPacket.Pos";
		if(packet instanceof ServerboundMovePlayerPacket.Rot)
			return "ServerboundMovePlayerPacket.Rot";
		if(packet instanceof ServerboundMovePlayerPacket.StatusOnly)
			return "ServerboundMovePlayerPacket.StatusOnly";
		if(packet instanceof ServerboundMovePlayerPacket)
			return "ServerboundMovePlayerPacket";
		if(packet instanceof ClientboundPlayerPositionPacket)
			return "ClientboundPlayerPositionPacket";
		
		return null;
	}
	
	private static PositionInfo tryExtractPosition(Object target)
	{
		double x = tryInvokeDouble(target, "getX", "x", "getXCoord", "xCoord");
		double y = tryInvokeDouble(target, "getY", "y", "getYCoord", "yCoord");
		double z = tryInvokeDouble(target, "getZ", "z", "getZCoord", "zCoord");
		float yaw = tryInvokeFloat(target, "getYRot", "getYaw", "yaw", "yRot");
		float pitch =
			tryInvokeFloat(target, "getXRot", "getPitch", "pitch", "xRot");
		
		if(Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z))
		{
			Object pos = tryInvokeObject(target, "getPosition", "position",
				"getPos", "pos", "getPositionVec", "getPosVec");
			if(pos instanceof Vec3 vec)
			{
				x = vec.x;
				y = vec.y;
				z = vec.z;
			}else if(pos != null)
			{
				x = tryInvokeDouble(pos, "getX", "x");
				y = tryInvokeDouble(pos, "getY", "y");
				z = tryInvokeDouble(pos, "getZ", "z");
			}
		}
		
		PositionInfo info = new PositionInfo(x, y, z, yaw, pitch);
		return info.hasAny() ? info : null;
	}
	
	private static Object tryInvokeObject(Object target, String... methods)
	{
		for(String name : methods)
		{
			try
			{
				java.lang.reflect.Method m = target.getClass().getMethod(name);
				return m.invoke(target);
			}catch(ReflectiveOperationException ignored)
			{}
		}
		return null;
	}
	
	private static double tryInvokeDouble(Object target, String... methods)
	{
		for(String name : methods)
		{
			try
			{
				java.lang.reflect.Method m = target.getClass().getMethod(name);
				Object value = m.invoke(target);
				if(value instanceof Double)
					return (Double)value;
				if(value instanceof Float)
					return ((Float)value).doubleValue();
				if(value instanceof Integer)
					return ((Integer)value).doubleValue();
				if(value instanceof Long)
					return ((Long)value).doubleValue();
			}catch(ReflectiveOperationException ignored)
			{}
		}
		
		for(String name : methods)
		{
			try
			{
				java.lang.reflect.Field f =
					target.getClass().getDeclaredField(name);
				f.setAccessible(true);
				Object value = f.get(target);
				if(value instanceof Double)
					return (Double)value;
				if(value instanceof Float)
					return ((Float)value).doubleValue();
				if(value instanceof Integer)
					return ((Integer)value).doubleValue();
				if(value instanceof Long)
					return ((Long)value).doubleValue();
			}catch(ReflectiveOperationException ignored)
			{}
		}
		return Double.NaN;
	}
	
	private static float tryInvokeFloat(Object target, String... methods)
	{
		for(String name : methods)
		{
			try
			{
				java.lang.reflect.Method m = target.getClass().getMethod(name);
				Object value = m.invoke(target);
				if(value instanceof Float)
					return (Float)value;
				if(value instanceof Double)
					return ((Double)value).floatValue();
				if(value instanceof Integer)
					return ((Integer)value).floatValue();
				if(value instanceof Long)
					return ((Long)value).floatValue();
			}catch(ReflectiveOperationException ignored)
			{}
		}
		
		for(String name : methods)
		{
			try
			{
				java.lang.reflect.Field f =
					target.getClass().getDeclaredField(name);
				f.setAccessible(true);
				Object value = f.get(target);
				if(value instanceof Float)
					return (Float)value;
				if(value instanceof Double)
					return ((Double)value).floatValue();
				if(value instanceof Integer)
					return ((Integer)value).floatValue();
				if(value instanceof Long)
					return ((Long)value).floatValue();
			}catch(ReflectiveOperationException ignored)
			{}
		}
		return Float.NaN;
	}
	
	private static final class PositionInfo
	{
		private final double x;
		private final double y;
		private final double z;
		private final float yaw;
		private final float pitch;
		private final boolean hasPos;
		private final boolean hasRot;
		
		private PositionInfo(double x, double y, double z, float yaw,
			float pitch)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.hasPos =
				!Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z);
			this.hasRot = !Float.isNaN(yaw) && !Float.isNaN(pitch);
		}
		
		private boolean hasAny()
		{
			return hasPos || hasRot;
		}
	}
	
	private interface TimedEntry
	{
		long getTimeMs();
	}
	
	private static final class PacketEntry implements TimedEntry
	{
		private final long timeMs;
		private final String summary;
		private final boolean movement;
		private final boolean flightRelated;
		private final boolean c2s;
		
		private PacketEntry(long timeMs, String summary, boolean movement,
			boolean flightRelated, boolean c2s)
		{
			this.timeMs = timeMs;
			this.summary = summary;
			this.movement = movement;
			this.flightRelated = flightRelated;
			this.c2s = c2s;
		}
		
		@Override
		public long getTimeMs()
		{
			return timeMs;
		}
	}
	
	private static final class StateEntry implements TimedEntry
	{
		private final long timeMs;
		private final Vec3 pos;
		private final Vec3 vel;
		private final boolean onGround;
		private final int tickCount;
		private final float yaw;
		private final float pitch;
		private final double fallDistance;
		private final int pingMs;
		
		private StateEntry(long timeMs, Vec3 pos, Vec3 vel, boolean onGround,
			int tickCount, float yaw, float pitch, double fallDistance,
			int pingMs)
		{
			this.timeMs = timeMs;
			this.pos = pos;
			this.vel = vel;
			this.onGround = onGround;
			this.tickCount = tickCount;
			this.yaw = yaw;
			this.pitch = pitch;
			this.fallDistance = fallDistance;
			this.pingMs = pingMs;
		}
		
		@Override
		public long getTimeMs()
		{
			return timeMs;
		}
	}
}
