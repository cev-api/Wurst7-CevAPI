/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.Locale;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.ChatUtils;

/**
 * Reports server-applied teleports with their source, destination and range.
 */
@SearchTags({"telemetrics", "teleport metrics", "teleport logger"})
public final class TelemetricsHack extends Hack
	implements PacketInputListener, PacketOutputListener, UpdateListener
{
	private static final int RESPAWN_WAIT_TICKS = 5;
	private static final long JOIN_POSITION_GRACE_MS = 3000L;
	private static final long OUTGOING_POSITION_HISTORY_MS = 3000L;
	private static final double OUTGOING_POSITION_MATCH_EPSILON = 1.0E-4;
	
	private Vec3 pendingRespawnStart;
	private String pendingRespawnOriginDimension;
	private String pendingRespawnDestinationDimension;
	private LocalPlayer pendingRespawnPlayer;
	private ClientboundPlayerPositionPacket pendingRespawnPosition;
	private Vec3 pendingRespawnPositionBase;
	private int pendingRespawnTicks;
	private long ignorePositionPacketsUntilMs;
	private final ArrayDeque<OutgoingPosition> outgoingPositions =
		new ArrayDeque<>();
	
	public TelemetricsHack()
	{
		super("Telemetrics");
		setCategory(Category.INTEL);
	}
	
	@Override
	protected synchronized void onEnable()
	{
		clearPendingRespawn();
		ignorePositionPacketsUntilMs = 0;
		outgoingPositions.clear();
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected synchronized void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		clearPendingRespawn();
		ignorePositionPacketsUntilMs = 0;
		outgoingPositions.clear();
	}
	
	@Override
	public synchronized void onReceivedPacket(PacketInputEvent event)
	{
		if(event.getPacket() instanceof ClientboundLoginPacket)
		{
			// The first position packet after login initializes the local
			// player;
			// it is not a teleport performed after joining the server.
			ignorePositionPacketsUntilMs =
				System.currentTimeMillis() + JOIN_POSITION_GRACE_MS;
			outgoingPositions.clear();
			return;
		}
		
		if(event.getPacket() instanceof ClientboundRespawnPacket packet)
		{
			rememberRespawn(packet);
			return;
		}
		
		if(!(event
			.getPacket() instanceof ClientboundPlayerPositionPacket packet))
			return;
		
		LocalPlayer player = MC.player;
		if(player == null || MC.level == null)
			return;
		
		if(System.currentTimeMillis() < ignorePositionPacketsUntilMs)
			return;
		
		if(pendingRespawnStart != null)
		{
			pendingRespawnPosition = packet;
			pendingRespawnPositionBase = player.position();
			pendingRespawnTicks = RESPAWN_WAIT_TICKS;
			return;
		}
		
		// A server correction reuses a position that the client already sent.
		// It is movement reconciliation, not a teleport to a new destination.
		if(isServerMovementCorrection(packet, player))
			return;
		
		Vec3 start = player.position();
		Vec3 destination = resolveDestination(packet, start);
		announce(start, destination, dimensionKey(MC.level),
			dimensionKey(MC.level));
	}
	
	@Override
	public synchronized void onSentPacket(PacketOutputEvent event)
	{
		if(!(event.getPacket() instanceof ServerboundMovePlayerPacket packet)
			|| !packet.hasPosition())
			return;
		
		double x = packet.getX(Double.NaN);
		double y = packet.getY(Double.NaN);
		double z = packet.getZ(Double.NaN);
		if(Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z))
			return;
		
		long now = System.currentTimeMillis();
		purgeOutgoingPositions(now);
		outgoingPositions.addLast(new OutgoingPosition(new Vec3(x, y, z), now));
	}
	
	@Override
	public synchronized void onUpdate()
	{
		if(pendingRespawnStart == null || MC.player == null || MC.level == null)
			return;
		
		String currentDimension = dimensionKey(MC.level);
		boolean respawnApplied = MC.player != pendingRespawnPlayer
			|| !currentDimension.equals(pendingRespawnOriginDimension);
		if(!respawnApplied && --pendingRespawnTicks > 0)
			return;
		
		Vec3 destination = MC.player.position();
		if(pendingRespawnPosition != null && pendingRespawnPositionBase != null)
			destination = resolveDestination(pendingRespawnPosition,
				pendingRespawnPositionBase);
		
		announce(pendingRespawnStart, destination,
			pendingRespawnOriginDimension, pendingRespawnDestinationDimension);
		clearPendingRespawn();
	}
	
	private void rememberRespawn(ClientboundRespawnPacket packet)
	{
		if(MC.player == null || MC.level == null)
			return;
		
		pendingRespawnStart = MC.player.position();
		pendingRespawnOriginDimension = dimensionKey(MC.level);
		pendingRespawnDestinationDimension =
			packet.commonPlayerSpawnInfo().dimension().identifier().toString();
		pendingRespawnPlayer = MC.player;
		pendingRespawnPosition = null;
		pendingRespawnPositionBase = null;
		pendingRespawnTicks = RESPAWN_WAIT_TICKS;
	}
	
	private Vec3 resolveDestination(ClientboundPlayerPositionPacket packet,
		Vec3 base)
	{
		Vec3 position = packet.change().position();
		return new Vec3(
			packet.relatives().contains(Relative.X) ? base.x + position.x
				: position.x,
			packet.relatives().contains(Relative.Y) ? base.y + position.y
				: position.y,
			packet.relatives().contains(Relative.Z) ? base.z + position.z
				: position.z);
	}
	
	private void announce(Vec3 start, Vec3 destination, String originDimension,
		String destinationDimension)
	{
		if(start == null || destination == null || originDimension == null
			|| destinationDimension == null)
			return;
		
		double distance = start.distanceTo(destination);
		String message;
		if(originDimension.equals(destinationDimension))
			message = String.format(Locale.ROOT,
				"Telemetrics: %s -> %s | distance %.1f blocks",
				formatPosition(start), formatPosition(destination), distance);
		else
			message = String.format(Locale.ROOT,
				"Telemetrics: %s %s -> %s %s | distance %.1f blocks",
				originDimension, formatPosition(start), destinationDimension,
				formatPosition(destination), distance);
		
		ChatUtils.message(message);
	}
	
	private String dimensionKey(
		net.minecraft.client.multiplayer.ClientLevel level)
	{
		return level.dimension().identifier().toString();
	}
	
	private String formatPosition(Vec3 position)
	{
		return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", position.x,
			position.y, position.z);
	}
	
	private void clearPendingRespawn()
	{
		pendingRespawnStart = null;
		pendingRespawnOriginDimension = null;
		pendingRespawnDestinationDimension = null;
		pendingRespawnPlayer = null;
		pendingRespawnPosition = null;
		pendingRespawnPositionBase = null;
		pendingRespawnTicks = 0;
	}
	
	private boolean isServerMovementCorrection(
		ClientboundPlayerPositionPacket packet, LocalPlayer player)
	{
		long now = System.currentTimeMillis();
		purgeOutgoingPositions(now);
		
		Vec3 destination = resolveDestination(packet, player.position());
		double epsilonSquared =
			OUTGOING_POSITION_MATCH_EPSILON * OUTGOING_POSITION_MATCH_EPSILON;
		for(OutgoingPosition outgoing : outgoingPositions)
			if(outgoing.position().distanceToSqr(destination) <= epsilonSquared)
				return true;
		return false;
	}
	
	private void purgeOutgoingPositions(long now)
	{
		while(!outgoingPositions.isEmpty() && now - outgoingPositions
			.peekFirst().timestamp() > OUTGOING_POSITION_HISTORY_MS)
			outgoingPositions.removeFirst();
	}
	
	private record OutgoingPosition(Vec3 position, long timestamp)
	{}
}
