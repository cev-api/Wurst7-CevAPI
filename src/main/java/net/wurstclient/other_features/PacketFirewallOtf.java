/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.StatusOnly;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.util.HackActivityTracker;
import net.wurstclient.util.PacketUtils;
import net.wurstclient.util.text.WText;

@SearchTags({"packet firewall", "movement packet validator", "movement packets",
	"anti kick"})
public final class PacketFirewallOtf extends OtherFeature
	implements PacketOutputListener, UpdateListener
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final long HACK_ACTIVITY_WINDOW_MS = 250;
	
	private final CheckboxSetting enabledSetting =
		new CheckboxSetting("Enabled", false);
	private final CheckboxSetting dropInvalidSetting =
		new CheckboxSetting("Drop invalid", false);
	private final CheckboxSetting clampPitchSetting =
		new CheckboxSetting("Clamp pitch", false);
	private final CheckboxSetting wrapYawSetting =
		new CheckboxSetting("Wrap yaw", false);
	private final CheckboxSetting dedupMovementSetting =
		new CheckboxSetting("Dedup movement", false);
	private final CheckboxSetting debugLoggingSetting =
		new CheckboxSetting("Debug logging", false);
	
	private Vec3 lastGoodPos;
	private float lastGoodYaw;
	private float lastGoodPitch;
	private boolean lastGoodOnGround;
	private long lastGoodTickTime = -1;
	private boolean lastGoodValid;
	
	private long droppedNaN;
	private long sanitizedRot;
	private long sanitizedPos;
	private long sanitizedOnGround;
	private long dedupedMoves;
	
	private PendingMovement pendingMovement;
	private boolean sendingPending;
	
	public PacketFirewallOtf()
	{
		super("PacketFirewall",
			"description.wurst.other_feature.packet_firewall");
		
		SettingGroup group = new SettingGroup("Packet Firewall", WText.literal(
			"Validates outbound movement packets, clamps rotation, and blocks malformed values."),
			false, true);
		group.addChildren(enabledSetting, dropInvalidSetting, clampPitchSetting,
			wrapYawSetting, dedupMovementSetting, debugLoggingSetting);
		addSetting(group);
		
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	public Category getCategory()
	{
		return Category.OTHER;
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(!isFirewallEnabled())
			return;
		
		boolean allowDedup = !sendingPending;
		Packet<?> packet = event.getPacket();
		
		if(packet instanceof ServerboundMovePlayerPacket movePacket)
		{
			handleMovePlayer(event, movePacket, allowDedup);
			return;
		}
		
		// Movement-adjacent packet that carries vehicle position/rotation.
		if(packet instanceof ServerboundMoveVehiclePacket vehiclePacket)
		{
			handleMoveVehicle(event, vehiclePacket, allowDedup);
		}
	}
	
	@Override
	public void onUpdate()
	{
		if(!isFirewallEnabled())
			return;
		
		if(pendingMovement == null)
			return;
		
		long tick = getClientTick();
		if(pendingMovement.packet.tick <= tick)
			sendPendingMovement("dedup-tick");
	}
	
	private boolean isFirewallEnabled()
	{
		return enabledSetting.isChecked() && WURST.isEnabled();
	}
	
	private void handleMovePlayer(PacketOutputEvent event,
		ServerboundMovePlayerPacket packet, boolean allowDedup)
	{
		MovementSnapshot original = snapshot(packet);
		MovementSnapshot sanitized = sanitizeSnapshot(original, packet);
		
		if(sanitized.dropPacket)
		{
			event.cancel();
			logDrop("invalid position values", packet, original, sanitized);
			return;
		}
		
		ServerboundMovePlayerPacket newPacket =
			applySanitized(packet, sanitized);
		
		if(sanitized.sanitizedRot)
			sanitizedRot++;
		if(sanitized.sanitizedPos)
			sanitizedPos++;
		if(sanitized.sanitizedOnGround)
			sanitizedOnGround++;
		
		if(sanitized.hasChanges())
			logSanitize(packet, original, sanitized);
		
		long tick = getClientTick();
		if(allowDedup && queueForDedup(event, newPacket, sanitized, tick))
			return;
		
		if(newPacket != packet)
			event.setPacket(newPacket);
		
		updateLastGoodFromMovePlayer(newPacket, sanitized);
	}
	
	private void handleMoveVehicle(PacketOutputEvent event,
		ServerboundMoveVehiclePacket packet, boolean allowDedup)
	{
		VehicleSnapshot original = snapshot(packet);
		VehicleSnapshot sanitized = sanitizeSnapshot(original);
		
		if(sanitized.dropPacket)
		{
			event.cancel();
			logDrop("invalid vehicle position values", packet, original,
				sanitized);
			return;
		}
		
		ServerboundMoveVehiclePacket newPacket =
			applySanitized(packet, sanitized);
		
		if(sanitized.sanitizedRot)
			sanitizedRot++;
		if(sanitized.sanitizedPos)
			sanitizedPos++;
		
		if(sanitized.hasChanges())
			logSanitize(packet, original, sanitized);
		
		long tick = getClientTick();
		if(allowDedup && queueForDedup(event, newPacket, sanitized, tick))
			return;
		
		if(newPacket != packet)
			event.setPacket(newPacket);
		
		updateLastGoodFromVehicle(sanitized);
	}
	
	private boolean queueForDedup(PacketOutputEvent event, Packet<?> packet,
		Snapshot snapshot, long tick)
	{
		if(!dedupMovementSetting.isChecked())
			return false;
		
		boolean replacedSameTick =
			pendingMovement != null && pendingMovement.packet.tick == tick;
		if(pendingMovement != null && pendingMovement.packet.tick != tick)
			sendPendingMovement("dedup-tick-boundary");
		
		MovementPacket pending = new MovementPacket(packet, snapshot, tick);
		pendingMovement = new PendingMovement(pending);
		event.cancel();
		if(replacedSameTick)
		{
			dedupedMoves++;
			logDedup(packet);
		}
		return true;
	}
	
	private void sendPendingMovement(String reason)
	{
		if(pendingMovement == null || MC.getConnection() == null)
			return;
		
		MovementPacket packet = pendingMovement.packet;
		pendingMovement = null;
		
		try
		{
			sendingPending = true;
			MC.getConnection().send(packet.packet);
			
		}finally
		{
			sendingPending = false;
		}
		
		if(debugLoggingSetting.isChecked())
			LOGGER.info("[PacketFirewall] sent deduped {} ({})",
				packet.packet.getClass().getSimpleName(), reason);
	}
	
	private MovementSnapshot snapshot(ServerboundMovePlayerPacket packet)
	{
		boolean hasPos = packet instanceof Pos || packet instanceof PosRot;
		boolean hasRot = packet instanceof Rot || packet instanceof PosRot;
		
		double x = hasPos ? packet.getX(0) : 0;
		double y = hasPos ? packet.getY(0) : 0;
		double z = hasPos ? packet.getZ(0) : 0;
		float yaw = hasRot ? packet.getYRot(0) : 0;
		float pitch = hasRot ? packet.getXRot(0) : 0;
		
		return new MovementSnapshot(hasPos, hasRot, x, y, z, yaw, pitch,
			packet.isOnGround(), packet.horizontalCollision());
	}
	
	private MovementSnapshot sanitizeSnapshot(MovementSnapshot original,
		ServerboundMovePlayerPacket packet)
	{
		MovementSnapshot sanitized = original.copy();
		
		if(original.hasPos && !isFinitePosition(original))
		{
			droppedNaN++;
			if(dropInvalidSetting.isChecked())
			{
				sanitized.dropPacket = true;
				return sanitized;
			}
			
			Vec3 fallback = getFallbackPos();
			sanitized.x = fallback.x;
			sanitized.y = fallback.y;
			sanitized.z = fallback.z;
			sanitized.sanitizedPos = true;
		}
		
		if(original.hasRot)
		{
			float yaw = original.yaw;
			float pitch = original.pitch;
			
			if(!Float.isFinite(yaw))
			{
				yaw = getFallbackYaw();
				sanitized.sanitizedRot = true;
			}
			
			if(wrapYawSetting.isChecked())
			{
				float wrapped = Mth.wrapDegrees(yaw);
				if(wrapped != yaw)
					sanitized.sanitizedRot = true;
				yaw = wrapped;
			}
			
			if(!Float.isFinite(pitch))
			{
				pitch = getFallbackPitch();
				sanitized.sanitizedRot = true;
			}
			
			if(clampPitchSetting.isChecked())
			{
				float clamped = Mth.clamp(pitch, -90.0F, 90.0F);
				if(clamped != pitch)
					sanitized.sanitizedRot = true;
				pitch = clamped;
			}
			
			sanitized.yaw = yaw;
			sanitized.pitch = pitch;
		}
		
		if(packet instanceof StatusOnly && !lastGoodValid)
		{
			sanitized.onGround = lastGoodOnGround;
			sanitized.sanitizedOnGround = true;
		}
		
		return sanitized;
	}
	
	private ServerboundMovePlayerPacket applySanitized(
		ServerboundMovePlayerPacket packet, MovementSnapshot sanitized)
	{
		ServerboundMovePlayerPacket updated = packet;
		
		if(sanitized.hasPos && sanitized.sanitizedPos)
			updated = PacketUtils.modifyPosition(updated, sanitized.x,
				sanitized.y, sanitized.z);
		
		if(sanitized.hasRot && sanitized.sanitizedRot)
			updated = PacketUtils.modifyRotation(updated, sanitized.yaw,
				sanitized.pitch);
		
		if(sanitized.sanitizedOnGround)
			updated = PacketUtils.modifyOnGround(updated, sanitized.onGround);
		
		return updated;
	}
	
	private VehicleSnapshot snapshot(ServerboundMoveVehiclePacket packet)
	{
		Vec3 position = packet.position();
		return new VehicleSnapshot(position.x, position.y, position.z,
			packet.yRot(), packet.xRot(), packet.onGround());
	}
	
	private VehicleSnapshot sanitizeSnapshot(VehicleSnapshot original)
	{
		VehicleSnapshot sanitized = original.copy();
		
		if(!isFinitePosition(original))
		{
			droppedNaN++;
			if(dropInvalidSetting.isChecked())
			{
				sanitized.dropPacket = true;
				return sanitized;
			}
			
			Vec3 fallback = getFallbackPos();
			sanitized.x = fallback.x;
			sanitized.y = fallback.y;
			sanitized.z = fallback.z;
			sanitized.sanitizedPos = true;
		}
		
		float yaw = original.yaw;
		float pitch = original.pitch;
		
		if(!Float.isFinite(yaw))
		{
			yaw = getFallbackYaw();
			sanitized.sanitizedRot = true;
		}
		
		if(wrapYawSetting.isChecked())
		{
			float wrapped = Mth.wrapDegrees(yaw);
			if(wrapped != yaw)
				sanitized.sanitizedRot = true;
			yaw = wrapped;
		}
		
		if(!Float.isFinite(pitch))
		{
			pitch = getFallbackPitch();
			sanitized.sanitizedRot = true;
		}
		
		if(clampPitchSetting.isChecked())
		{
			float clamped = Mth.clamp(pitch, -90.0F, 90.0F);
			if(clamped != pitch)
				sanitized.sanitizedRot = true;
			pitch = clamped;
		}
		
		sanitized.yaw = yaw;
		sanitized.pitch = pitch;
		
		return sanitized;
	}
	
	private ServerboundMoveVehiclePacket applySanitized(
		ServerboundMoveVehiclePacket packet, VehicleSnapshot sanitized)
	{
		if(!sanitized.hasChanges())
			return packet;
		
		return new ServerboundMoveVehiclePacket(
			new Vec3(sanitized.x, sanitized.y, sanitized.z), sanitized.yaw,
			sanitized.pitch, sanitized.onGround);
	}
	
	private void updateLastGoodFromMovePlayer(
		ServerboundMovePlayerPacket packet, MovementSnapshot sanitized)
	{
		if(sanitized.hasPos && !sanitized.dropPacket)
			lastGoodPos = new Vec3(sanitized.x, sanitized.y, sanitized.z);
		
		if(sanitized.hasRot && !sanitized.dropPacket)
		{
			lastGoodYaw = sanitized.yaw;
			lastGoodPitch = sanitized.pitch;
		}
		
		if(!sanitized.dropPacket)
		{
			lastGoodOnGround = sanitized.onGround;
			lastGoodTickTime = getClientTick();
			lastGoodValid = true;
		}
	}
	
	private void updateLastGoodFromVehicle(VehicleSnapshot snapshot)
	{
		if(snapshot.dropPacket)
			return;
		
		lastGoodPos = new Vec3(snapshot.x, snapshot.y, snapshot.z);
		lastGoodYaw = snapshot.yaw;
		lastGoodPitch = snapshot.pitch;
		lastGoodOnGround = snapshot.onGround;
		lastGoodTickTime = getClientTick();
		lastGoodValid = true;
	}
	
	private Vec3 getFallbackPos()
	{
		if(lastGoodValid && lastGoodPos != null)
			return lastGoodPos;
		if(MC.player != null)
			return MC.player.position();
		return Vec3.ZERO;
	}
	
	private float getFallbackYaw()
	{
		if(lastGoodValid)
			return lastGoodYaw;
		if(MC.player != null)
			return MC.player.getYRot();
		return 0.0F;
	}
	
	private float getFallbackPitch()
	{
		if(lastGoodValid)
			return lastGoodPitch;
		if(MC.player != null)
			return MC.player.getXRot();
		return 0.0F;
	}
	
	private long getClientTick()
	{
		if(MC.level != null)
			return MC.level.getGameTime();
		if(MC.player != null)
			return MC.player.tickCount;
		return System.currentTimeMillis();
	}
	
	private boolean isFinitePosition(MovementSnapshot snapshot)
	{
		return Double.isFinite(snapshot.x) && Double.isFinite(snapshot.y)
			&& Double.isFinite(snapshot.z);
	}
	
	private boolean isFinitePosition(VehicleSnapshot snapshot)
	{
		return Double.isFinite(snapshot.x) && Double.isFinite(snapshot.y)
			&& Double.isFinite(snapshot.z);
	}
	
	private void logDrop(String reason, Packet<?> packet, Snapshot original,
		Snapshot sanitized)
	{
		String packetName = packet.getClass().getSimpleName();
		if(debugLoggingSetting.isChecked())
		{
			LOGGER.warn(
				"[PacketFirewall] dropped {}: {} | original={} sanitized={} sender={}",
				packetName, reason, original.describe(), sanitized.describe(),
				resolveSenderInfo());
			return;
		}
		
		LOGGER.warn("[PacketFirewall] dropped {}: {}", packetName, reason);
	}
	
	private void logSanitize(Packet<?> packet, Snapshot original,
		Snapshot sanitized)
	{
		String packetName = packet.getClass().getSimpleName();
		if(debugLoggingSetting.isChecked())
		{
			LOGGER.info(
				"[PacketFirewall] sanitized {} | original={} sanitized={} sender={}",
				packetName, original.describe(), sanitized.describe(),
				resolveSenderInfo());
			return;
		}
		
		LOGGER.info("[PacketFirewall] sanitized {}", packetName);
	}
	
	private void logDedup(Packet<?> packet)
	{
		if(!debugLoggingSetting.isChecked())
			return;
		
		LOGGER.info("[PacketFirewall] deduped {}", packet.getClass().getName());
	}
	
	private String resolveSenderInfo()
	{
		Hack recentHack = HackActivityTracker.getMostRecentActive(
			WURST.getHax().getAllHax(), HACK_ACTIVITY_WINDOW_MS);
		if(recentHack != null)
			return "hack=" + recentHack.getName();
		
		return "stack=" + shortStackTrace();
	}
	
	private String shortStackTrace()
	{
		StackTraceElement[] trace = Thread.currentThread().getStackTrace();
		StringBuilder builder = new StringBuilder();
		int added = 0;
		for(StackTraceElement element : trace)
		{
			String className = element.getClassName();
			if(className.contains(PacketFirewallOtf.class.getName()))
				continue;
			if(className.startsWith("net.wurstclient.event."))
				continue;
			if(className.startsWith("java.lang.Thread"))
				continue;
			
			if(added > 0)
				builder.append(" <- ");
			builder.append(element.getClassName()).append(".")
				.append(element.getMethodName()).append(":")
				.append(element.getLineNumber());
			added++;
			if(added >= 3)
				break;
		}
		return builder.toString();
	}
	
	private record MovementPacket(Packet<?> packet, Snapshot snapshot,
		long tick)
	{}
	
	private record PendingMovement(MovementPacket packet)
	{}
	
	private interface Snapshot
	{
		String describe();
	}
	
	private static final class MovementSnapshot implements Snapshot
	{
		private final boolean hasPos;
		private final boolean hasRot;
		private double x;
		private double y;
		private double z;
		private float yaw;
		private float pitch;
		private boolean onGround;
		private final boolean horizontalCollision;
		private boolean dropPacket;
		private boolean sanitizedPos;
		private boolean sanitizedRot;
		private boolean sanitizedOnGround;
		
		private MovementSnapshot(boolean hasPos, boolean hasRot, double x,
			double y, double z, float yaw, float pitch, boolean onGround,
			boolean horizontalCollision)
		{
			this.hasPos = hasPos;
			this.hasRot = hasRot;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.onGround = onGround;
			this.horizontalCollision = horizontalCollision;
		}
		
		private MovementSnapshot copy()
		{
			return new MovementSnapshot(hasPos, hasRot, x, y, z, yaw, pitch,
				onGround, horizontalCollision);
		}
		
		private boolean hasChanges()
		{
			return sanitizedPos || sanitizedRot || sanitizedOnGround;
		}
		
		@Override
		public String describe()
		{
			StringBuilder builder = new StringBuilder();
			builder.append("pos=");
			if(hasPos)
				builder.append(x).append(",").append(y).append(",").append(z);
			else
				builder.append("n/a");
			builder.append(" rot=");
			if(hasRot)
				builder.append(yaw).append(",").append(pitch);
			else
				builder.append("n/a");
			builder.append(" onGround=").append(onGround);
			builder.append(" hCollision=").append(horizontalCollision);
			return builder.toString();
		}
	}
	
	private static final class VehicleSnapshot implements Snapshot
	{
		private double x;
		private double y;
		private double z;
		private float yaw;
		private float pitch;
		private boolean onGround;
		private boolean dropPacket;
		private boolean sanitizedPos;
		private boolean sanitizedRot;
		
		private VehicleSnapshot(double x, double y, double z, float yaw,
			float pitch, boolean onGround)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.onGround = onGround;
		}
		
		private VehicleSnapshot copy()
		{
			return new VehicleSnapshot(x, y, z, yaw, pitch, onGround);
		}
		
		private boolean hasChanges()
		{
			return sanitizedPos || sanitizedRot;
		}
		
		@Override
		public String describe()
		{
			return "pos=" + x + "," + y + "," + z + " rot=" + yaw + "," + pitch
				+ " onGround=" + onGround;
		}
	}
}
