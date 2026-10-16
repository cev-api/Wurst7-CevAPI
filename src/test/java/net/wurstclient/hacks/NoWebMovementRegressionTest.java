/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class NoWebMovementRegressionTest
{
	public static void main(String[] args) throws Exception
	{
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		verifyContactLifecycle();
		verifyCornerCollisions();
		Vec3 start = new Vec3(4738.99, -44, -1317.4);
		Vec3 target = start.add(0.5, 0, 0);
		check(rejectedByWebMove(start, target, new Vec3(0.25, 0.05, 0.25)),
			"Captured unsplit SpeedHack step exceeds vanilla 26.2 discrepancy tolerance");
		for(Vec3 delta : List.of(new Vec3(0.5, 0, 0), new Vec3(0.7, 0, 0.7),
			new Vec3(-9.9, 0, 0), new Vec3(0.5, -1.5, -0.5), new Vec3(0, 2, 0),
			new Vec3(0.001, 0, 0), Vec3.ZERO))
			for(boolean rotated : List.of(false, true))
				verifyMovement(start, start.add(delta), rotated);
		verifyMovement(new Vec3(-29_999_990, 129, 29_999_990),
			new Vec3(-29_999_985, 127, 29_999_986), true);
		for(Vec3 invalid : List.of(start.add(17, 0, 0),
			new Vec3(Double.NaN, 0, 0),
			new Vec3(0, Double.POSITIVE_INFINITY, 0)))
			check(
				NoWebHack.intermediateMovement(start,
					new ServerboundMovePlayerPacket.Pos(invalid, true, false))
					.isEmpty(),
				"Teleports and invalid coordinates must not create bursts");
		check(NoWebHack
			.intermediateMovement(start,
				new ServerboundMovePlayerPacket.Rot(12, -34, false, true))
			.isEmpty(), "Rotation-only packets must remain unchanged");
		check(NoWebHack
			.intermediateMovement(start,
				new ServerboundMovePlayerPacket.StatusOnly(false, true))
			.isEmpty(), "Status-only packets must remain unchanged");
		System.out.println(
			"NoWeb movement checks passed: web entry/exit and correction reset, vanilla corner collisions, captured 26.2 discrepancy, unchanged displacement, bounded steps, falling/diagonals and packet flags.");
	}
	
	private static void verifyContactLifecycle()
	{
		NoWebHack hack = new NoWebHack();
		check(!hack.requiresWebSubdivision(false),
			"Ordinary movement must not trigger web subdivision");
		check(
			hack.requiresWebSubdivision(true)
				&& hack.requiresWebSubdivision(true),
			"Entering and staying in webs must trigger subdivision");
		check(hack.requiresWebSubdivision(false),
			"The first movement after leaving must consume pending web slowdown");
		check(!hack.requiresWebSubdivision(false),
			"Later ordinary movement must not retain web state");
		hack.requiresWebSubdivision(true);
		hack.onServerPositionApplied();
		check(!hack.requiresWebSubdivision(false),
			"A server correction must clear previous web contact");
	}
	
	private static void verifyCornerCollisions() throws Exception
	{
		Vec3 from = Vec3.ZERO;
		Vec3 to = new Vec3(0.5, 0, 1);
		AABB playerBox = new AABB(-0.3, 0, -0.3, 0.3, 1.8, 0.3);
		AABB nearbyWall = new AABB(0.6, 0, -0.25, 0.7, 2, -0.05);
		check(playerBox.expandTowards(to).intersects(nearbyWall),
			"The old broad collision box incorrectly includes this nearby wall");
		ServerboundMovePlayerPacket packet =
			new ServerboundMovePlayerPacket.Pos(to, true, false);
		List<ServerboundMovePlayerPacket> steps =
			NoWebHack.intermediateMovement(from, packet);
		Method collide = Entity.class.getDeclaredMethod("collideWithShapes",
			Vec3.class, AABB.class, List.class);
		collide.setAccessible(true);
		for(boolean blocked : List.of(false, true))
		{
			List<VoxelShape> obstacles = List.of(Shapes.create(
				blocked ? new AABB(0.15, 0, 0.4, 0.35, 2, 0.6) : nearbyWall));
			boolean clear = NoWebHack.isMovementPathClear(from, steps, packet,
				(start, end) -> {
					try
					{
						Vec3 delta = end.subtract(start);
						Vec3 resolved = (Vec3)collide.invoke(null, delta,
							playerBox.move(start), obstacles);
						return resolved.subtract(delta).lengthSqr() < 1.0E-12;
					}catch(ReflectiveOperationException e)
					{
						throw new AssertionError(e);
					}
				});
			check(clear != blocked,
				"Vanilla collision resolution must allow nearby corners and reject actual path obstacles");
		}
	}
	
	private static void verifyMovement(Vec3 start, Vec3 target, boolean rotated)
	{
		ServerboundMovePlayerPacket original = rotated
			? new ServerboundMovePlayerPacket.PosRot(target, 123, -45, false,
				true)
			: new ServerboundMovePlayerPacket.Pos(target, true, false);
		List<ServerboundMovePlayerPacket> sequence =
			new ArrayList<>(NoWebHack.intermediateMovement(start, original));
		check(sequence.size() <= 63, "Packet burst must stay bounded");
		if(start.distanceTo(target) <= 0.25)
			check(sequence.isEmpty(), "Small movement needs no extra packets");
		sequence.add(original);
		Vec3 previous = start;
		double travelled = 0;
		for(ServerboundMovePlayerPacket packet : sequence)
		{
			Vec3 position =
				new Vec3(packet.getX(0), packet.getY(0), packet.getZ(0));
			check(position.distanceTo(previous) <= 0.250001,
				"Each positional update must fit the vanilla discrepancy budget");
			check(
				!rejectedByWebMove(previous, position,
					new Vec3(0.25, 0.05, 0.25)),
				"Every update must pass the vanilla 26.2 cobweb discrepancy check");
			check(
				!rejectedByWebMove(previous, position,
					new Vec3(0.5, 0.25, 0.5)),
				"Weaving must also remain within the discrepancy budget");
			check(
				packet.getClass() == original.getClass()
					&& packet.isOnGround() == original.isOnGround()
					&& packet.horizontalCollision() == original
						.horizontalCollision(),
				"Packet type, grounded and collision flags must be preserved");
			if(rotated)
				check(packet.getYRot(0) == 123 && packet.getXRot(0) == -45,
					"Rotation must be preserved");
			travelled += previous.distanceTo(position);
			previous = position;
		}
		check(previous.equals(target),
			"Original destination must be unchanged");
		check(Math.abs(travelled - start.distanceTo(target)) < 1.0E-6,
			"Subdivision must retain full displacement without detours");
	}
	
	private static boolean rejectedByWebMove(Vec3 from, Vec3 to,
		Vec3 multiplier)
	{
		Vec3 simulated = from.add(to.subtract(from).multiply(multiplier));
		Vec3 error = to.subtract(simulated);
		return error.lengthSqr() > 0.0625;
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
