/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest;

import java.lang.reflect.Field;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ClientboundTrackedWaypointPacket;
import net.minecraft.world.waypoints.Waypoint;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.hacks.LocatorTriangulationHack;
import net.wurstclient.hacks.locator.LocatorTriangulator;
import net.wurstclient.settings.CheckboxSetting;

public final class LocatorTriangulationGameTest implements FabricClientGameTest
{
	@Override
	public void runTest(ClientGameTestContext context)
	{
		try(TestSingleplayerContext world = context.worldBuilder().create())
		{
			context.waitTicks(2);
			world.getConnection().waitForChunksRender();
			context.runOnClient(this::testSessions);
		}
		WurstTest.LOGGER.info("Triangulator session regression tests passed");
	}
	
	private void testSessions(Minecraft mc)
	{
		LocatorTriangulationHack hack = new LocatorTriangulationHack();
		((CheckboxSetting)get(hack, "chatResults")).setChecked(false);
		double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
		float yaw = mc.player.getYRot();
		try
		{
			startPass(mc, hack, 0, 0, -15000, -15000);
			completePass(mc, hack, -15000, -15000);
			assertPosition(hack, -15000, -15000);
			expireResult(hack);
			
			// The same UUID has respawned at spawn, with the observer now far
			// away.
			startPass(mc, hack, -15000, -15000, 0, 0);
			completePass(mc, hack, 0, 0);
			assertPosition(hack, 0, 0);
			expireResult(hack);
			
			// A death/removal during collection must discard the partial fit.
			startPass(mc, hack, 0, 0, -15000, -15000);
			move(mc, hack, 32, -15000, -15000);
			check(get(hack, "result") != null, "Expected a partial fit");
			deliver(mc, hack, ClientboundTrackedWaypointPacket
				.removeWaypoint(mc.player.getUUID()));
			hack.onUpdate();
			assertState(hack, "LOST");
			check(get(hack, "result") == null, "Lost target retained its fit");
			
			mc.player.setPos(-15000, y, -15000);
			faceWaypoint(mc, hack, 0, 0);
			hack.onUpdate();
			turnAndBegin(mc, hack);
			completePass(mc, hack, 0, 0);
			assertPosition(hack, 0, 0);
			
			// Toggling must also discard the displayed result and collected
			// data.
			hack.setEnabled(true);
			hack.setEnabled(false);
			assertState(hack, "SEARCHING");
			check(get(hack, "result") == null, "Toggle retained its result");
			startPass(mc, hack, 0, 0, -15000, -15000);
			completePass(mc, hack, -15000, -15000);
			assertPosition(hack, -15000, -15000);
			expireResult(hack);
			
			// A failed fit must time out too, allowing another measurement.
			((CheckboxSetting)get(hack, "autoExtend")).setChecked(false);
			startPass(mc, hack, 0, 0, -15000, -15000);
			double baselineYaw = (double)get(hack, "baselineYaw");
			mc.player.setPos(-Math.sin(baselineYaw) * 256, y,
				Math.cos(baselineYaw) * 256);
			set(hack, "lastSampleAt", 0L);
			hack.onUpdate();
			assertState(hack, "RESULT");
			check(get(hack, "result") == null,
				"Parallel bearings produced a fit");
			expireResult(hack);
			assertState(hack, "SEARCHING");
		}finally
		{
			hack.setEnabled(false);
			ClientboundTrackedWaypointPacket.removeWaypoint(mc.player.getUUID())
				.apply(mc.getConnection().getWaypointManager());
			mc.player.setPos(x, y, z);
			mc.player.setYRot(yaw);
		}
	}
	
	private void startPass(Minecraft mc, LocatorTriangulationHack hack,
		double x, double z, double targetX, double targetZ)
	{
		mc.player.setPos(x, mc.player.getY(), z);
		faceWaypoint(mc, hack, targetX, targetZ);
		hack.onUpdate();
		// Advance only the wall-clock gates; all state transitions use
		// onUpdate.
		set(hack, "candidateSince", 0L);
		hack.onUpdate();
		turnAndBegin(mc, hack);
	}
	
	private void turnAndBegin(Minecraft mc, LocatorTriangulationHack hack)
	{
		assertState(hack, "TURN_PERPENDICULAR");
		mc.player
			.setYRot((float)Math.toDegrees((double)get(hack, "baselineYaw")));
		hack.onUpdate();
		assertState(hack, "MOVING");
	}
	
	private void completePass(Minecraft mc, LocatorTriangulationHack hack,
		double targetX, double targetZ)
	{
		for(int distance = 16; distance <= 256; distance += 16)
		{
			move(mc, hack, distance, targetX, targetZ);
			if(get(hack, "state").toString().equals("RESULT"))
				return;
		}
		throw new AssertionError("Triangulation did not finish");
	}
	
	private void move(Minecraft mc, LocatorTriangulationHack hack,
		double distance, double targetX, double targetZ)
	{
		double yaw = (double)get(hack, "baselineYaw");
		mc.player.setPos((double)get(hack, "startX") - Math.sin(yaw) * distance,
			mc.player.getY(),
			(double)get(hack, "startZ") + Math.cos(yaw) * distance);
		deliver(mc, hack,
			ClientboundTrackedWaypointPacket.updateWaypointAzimuth(
				mc.player.getUUID(), Waypoint.Icon.NULL,
				bearing(mc, targetX, targetZ)));
		set(hack, "lastSampleAt", 0L);
		hack.onUpdate();
	}
	
	private void faceWaypoint(Minecraft mc, LocatorTriangulationHack hack,
		double targetX, double targetZ)
	{
		float bearing = bearing(mc, targetX, targetZ);
		deliver(mc, hack, ClientboundTrackedWaypointPacket.addWaypointAzimuth(
			mc.player.getUUID(), Waypoint.Icon.NULL, bearing));
		mc.player.setYRot((float)Math.toDegrees(bearing));
	}
	
	private float bearing(Minecraft mc, double x, double z)
	{
		return (float)Math.atan2(mc.player.getX() - x, z - mc.player.getZ());
	}
	
	private void deliver(Minecraft mc, LocatorTriangulationHack hack,
		ClientboundTrackedWaypointPacket packet)
	{
		hack.onReceivedPacket(new PacketInputEvent(packet));
		packet.apply(mc.getConnection().getWaypointManager());
	}
	
	private void expireResult(LocatorTriangulationHack hack)
	{
		set(hack, "resultSince", 0L);
		hack.onUpdate();
	}
	
	private void assertPosition(LocatorTriangulationHack hack, double x,
		double z)
	{
		LocatorTriangulator.Result result =
			(LocatorTriangulator.Result)get(hack, "result");
		check(result != null && Math.hypot(result.x() - x, result.z() - z) < 5,
			"Expected position " + x + ", " + z + ", got " + result);
	}
	
	private void assertState(LocatorTriangulationHack hack, String state)
	{
		check(get(hack, "state").toString().equals(state),
			"Expected " + state + ", got " + get(hack, "state"));
	}
	
	private Object get(LocatorTriangulationHack hack, String name)
	{
		try
		{
			return field(name).get(hack);
		}catch(ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}
	
	private void set(LocatorTriangulationHack hack, String name, Object value)
	{
		try
		{
			field(name).set(hack, value);
		}catch(ReflectiveOperationException e)
		{
			throw new AssertionError(e);
		}
	}
	
	private Field field(String name) throws NoSuchFieldException
	{
		Field field = LocatorTriangulationHack.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
	
	private void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
