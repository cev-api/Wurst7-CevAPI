/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.MovementPacketCompat;

public final class NoWebHack extends Hack
{
	private boolean skipNextMovement = true;
	private boolean lastMovementTouchedWeb;
	private final Set<String> reportedMovementStatuses = new HashSet<>();
	
	public NoWebHack()
	{
		super("NoWeb",
			"Cancels cobweb slowdown. With SpeedHack, splits movement into smaller immediate updates without lowering speed. Server movement checks may still reject it.",
			false);
		setCategory(Category.MOVEMENT);
	}
	
	@Override
	protected void onEnable()
	{
		skipNextMovement = true;
		lastMovementTouchedWeb = false;
		reportedMovementStatuses.clear();
	}
	
	public void onServerPositionApplied()
	{
		skipNextMovement = true;
		lastMovementTouchedWeb = false;
	}
	
	public void sendIntermediateMovement(ClientPacketListener connection,
		Packet<?> packet, double lastX, double lastY, double lastZ)
	{
		if(!isEnabled() || !(packet instanceof ServerboundMovePlayerPacket move)
			|| !move.hasPosition())
			return;
		if(skipNextMovement)
		{
			skipNextMovement = false;
			return;
		}
		if(MC.player == null || MC.level == null
			|| connection != MC.getConnection() || MC.hasSingleplayerServer()
			|| !WURST.getHax().speedHackHack.isEnabled())
		{
			lastMovementTouchedWeb = false;
			return;
		}
		
		Vec3 from = new Vec3(lastX, lastY, lastZ);
		Vec3 destination =
			new Vec3(move.getX(lastX), move.getY(lastY), move.getZ(lastZ));
		double distance = from.distanceTo(destination);
		if(!Double.isFinite(distance) || distance > 16)
		{
			lastMovementTouchedWeb = false;
			return;
		}
		AABB path =
			MC.player.getBoundingBox().move(from.subtract(MC.player.position()))
				.expandTowards(destination.subtract(from)).deflate(1.0E-7);
		if(!MC.level.hasChunksAt(
			BlockPos.containing(path.minX, path.minY, path.minZ),
			BlockPos.containing(path.maxX, path.maxY, path.maxZ)))
		{
			lastMovementTouchedWeb = false;
			return;
		}
		if(!requiresWebSubdivision(
			MC.level.getBlockStates(path).anyMatch(s -> s.is(Blocks.COBWEB))))
			return;
		List<ServerboundMovePlayerPacket> intermediate =
			intermediateMovement(from, move);
		if(intermediate.isEmpty())
			return;
		if(!isMovementPathClear(from, intermediate, move, (start, end) -> {
			AABB box = MC.player.getBoundingBox()
				.move(start.subtract(MC.player.position())).deflate(1.0E-7);
			Vec3 delta = end.subtract(start);
			Vec3 resolved = Entity.collideBoundingBox(MC.player, delta, box,
				MC.level, MC.level.getEntityCollisions(MC.player,
					box.expandTowards(delta)));
			return resolved.subtract(delta).lengthSqr() < 1.0E-12;
		}))
		{
			reportMovementStatus(
				"SpeedHack compatibility blocked: collision on an intermediate movement step.");
			return;
		}
		var firewall = WURST.getOtfs().packetFirewallOtf;
		if(firewall.isVanillaOnlyPacketsMode() && (!firewall.isAllowedHack(this)
			|| !firewall.isAllowedHack(WURST.getHax().speedHackHack)))
		{
			reportMovementStatus(
				"SpeedHack compatibility blocked by PacketFirewall settings.");
			return;
		}
		if(!MovementPacketCompat.supportsPositionBursts())
		{
			reportMovementStatus(
				"SpeedHack compatibility blocked: unresolved or unsupported connection protocol.");
			return;
		}
		
		reportMovementStatus(
			"SpeedHack compatibility active: subdividing movement without lowering speed.");
		for(ServerboundMovePlayerPacket step : intermediate)
			connection.send(step);
	}
	
	boolean requiresWebSubdivision(boolean touchesWeb)
	{
		boolean required = touchesWeb || lastMovementTouchedWeb;
		lastMovementTouchedWeb = touchesWeb;
		return required;
	}
	
	static boolean isMovementPathClear(Vec3 from,
		List<ServerboundMovePlayerPacket> intermediate,
		ServerboundMovePlayerPacket original, BiPredicate<Vec3, Vec3> canMove)
	{
		Vec3 previous = from;
		for(ServerboundMovePlayerPacket step : intermediate)
		{
			Vec3 next = new Vec3(step.getX(from.x), step.getY(from.y),
				step.getZ(from.z));
			if(!canMove.test(previous, next))
				return false;
			previous = next;
		}
		return canMove.test(previous, new Vec3(original.getX(from.x),
			original.getY(from.y), original.getZ(from.z)));
	}
	
	private void reportMovementStatus(String status)
	{
		if(reportedMovementStatuses.add(status))
			ChatUtils.message("[NoWeb] " + status);
	}
	
	static List<ServerboundMovePlayerPacket> intermediateMovement(Vec3 from,
		ServerboundMovePlayerPacket move)
	{
		if(!move.hasPosition())
			return List.of();
		Vec3 destination =
			new Vec3(move.getX(from.x), move.getY(from.y), move.getZ(from.z));
		double distance = from.distanceTo(destination);
		// The older server's squared position discrepancy threshold is 0.0625.
		if(!Double.isFinite(distance) || distance <= 0.25 || distance > 16)
			return List.of();
		int steps = (int)Math.ceil(distance / 0.25);
		List<ServerboundMovePlayerPacket> packets = new ArrayList<>(steps - 1);
		for(int i = 1; i < steps; i++)
		{
			Vec3 position = from.lerp(destination, (double)i / steps);
			packets.add(move.hasRotation()
				? new ServerboundMovePlayerPacket.PosRot(position,
					move.getYRot(0), move.getXRot(0), move.isOnGround(),
					move.horizontalCollision())
				: new ServerboundMovePlayerPacket.Pos(position,
					move.isOnGround(), move.horizontalCollision()));
		}
		return packets;
	}
	
	public boolean shouldCancelCobwebSlowdown(Entity entity)
	{
		if(!isEnabled() || entity == null || MC.player == null)
			return false;
		
		return entity == MC.player || entity instanceof ServerPlayer player
			&& player.getUUID().equals(MC.player.getUUID());
	}
}
