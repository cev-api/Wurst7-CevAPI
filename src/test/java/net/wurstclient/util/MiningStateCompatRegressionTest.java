/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.util.MiningStateCompat.Tracker;
import net.wurstclient.util.MiningStateCompat.Pipeline;
import net.wurstclient.util.MiningStateCompat.BreakSchedule;

/** Standalone mining-state checks without a Minecraft window or server. */
public final class MiningStateCompatRegressionTest
{
	private static int checks;
	
	public static void main(String[] args)
	{
		checkTimedMining();
		checkTracker();
		checkPipeline();
		checkRecordedPacketOrder();
		checkMissedStop();
		checkEarlyStopGuard();
		checkMiningReadiness();
		checkTunnelGeometry();
		System.out.println("Mining-state regression checks passed: " + checks);
	}
	
	private static void checkTracker()
	{
		Tracker state = new Tracker();
		state.attempt(1);
		advance(state, 4);
		check(!state.canAttempt(1), "retry bounded between attempts");
		state.tick();
		check(state.canAttempt(1), "normal retry becomes eligible");
		state.attempt(1);
		check(state.blockUpdate(1, true), "air completes attempt");
		check(state.canAttempt(1), "completed target released");
		state.attempt(2);
		check(state.blockUpdate(2, false), "correction recorded");
		check(!state.blockUpdate(2, false), "duplicate correction ignored");
		advance(state, 2);
		check(state.canAttempt(2), "corrected target gets bounded early retry");
		state.reset();
		advance(state, 20);
		check(!rejectRounds(state, 4, 3, false),
			"no rescue from silence alone");
		state.acknowledge(10);
		state.acknowledge(10);
		check(!state.tick(), "duplicate ACK cannot establish live progress");
		state.acknowledge(11);
		state.attempt(99);
		check(state.tick(),
			"repeated corrections with live ACKs request rescue");
		check(state.canAttempt(1), "rescue introduces no global quiet period");
		check(!state.tick(), "consumed corrections cannot repeat rescue");
		state.reset();
		advance(state, 20);
		check(!rejectRounds(state, 1, 12, true),
			"one rejected target cannot stop whole queue");
		state.reset();
		advance(state, 20);
		check(!rejectRounds(state, 12, 1, true),
			"one-time corrections do not request rescue");
		state.reset();
		advance(state, 20);
		state.attempt(99);
		state.blockUpdate(99, true);
		check(!rejectRounds(state, 4, 3, true),
			"healthy air keeps fast path running");
		state.reset();
		for(int pos = 0; pos < Tracker.MAX_TARGETS; pos++)
			state.attempt(pos);
		check(!state.canAttempt(Tracker.MAX_TARGETS),
			"attempt history bounded");
		advance(state, 101);
		check(state.canAttempt(Tracker.MAX_TARGETS), "stale history expires");
		state.reset();
		state.defer(1);
		state.blockUpdate(1, false);
		advance(state, 99);
		check(!state.canAttempt(1), "duplicate correction preserves deferral");
		state.tick();
		check(state.canAttempt(1), "deferral expires");
		state.reset();
		check(state.canAttempt(1), "lifecycle reset clears history");
	}
	
	private static void checkPipeline()
	{
		Pipeline pipeline = new Pipeline();
		for(int latency : new int[]{3, 6, 12, 20})
		{
			pipeline.reset();
			for(int tick = 0; tick < 100; tick++)
			{
				if(tick > 0)
				{
					check(pipeline.readyToStop(tick),
						"next tick retries previous STOP");
					pipeline.markStopped(tick - 1, (tick - 1) * 3 + 3);
				}
				if(tick >= latency)
					pipeline.acknowledge((tick - latency) * 3 + 3, tick);
				if(tick > latency)
					pipeline.confirm(tick - latency - 1);
				check(pipeline.canSubmit(tick, tick, false),
					"healthy submission continues every tick at all tested latencies");
				pipeline.submit(tick, tick, false, tick * 3 + 2);
				check(!pipeline.readyToStop(tick),
					"tail STOP waits for next tick");
				check(!pipeline.canSubmit(1000 + tick, tick, true),
					"even instant START cannot displace pending tail STOP");
			}
		}
		pipeline.reset();
		pipeline.submit(1, 0, false, 10);
		pipeline.acknowledge(10, 1);
		pipeline.markStopped(1, 11);
		check(!pipeline.canRetry(1, 39),
			"initial ACK cannot acknowledge tail STOP");
		pipeline.acknowledge(11, 100);
		check(!pipeline.canRetry(1, 101), "air gets a post-ACK grace");
		check(pipeline.canRetry(1, 102),
			"missed target can retry without normal mining wait");
		check(pipeline.canSubmit(2, 102, false),
			"missed target cannot block new positions");
		check(pipeline.canSubmit(1, 102, false),
			"retry uses existing capacity");
		pipeline.submit(1, 102, false, 12);
		pipeline.markStopped(1, 13);
		check(!pipeline.canRetry(1, 141),
			"retry waits for its own ACK or bounded timeout");
		check(pipeline.canRetry(1, 142),
			"missing ACK cannot permanently reserve a target");
		check(pipeline.oldest() == 1, "retry retains original rescue order");
		pipeline.confirm(1);
		check(pipeline.oldest() == null, "authoritative air releases target");
		pipeline.reset();
		for(int pos = 0; pos < Pipeline.CAPACITY; pos++)
			pipeline.submit(pos, 0, true, pos + 1);
		check(!pipeline.canSubmit(99, 1, true), "in-flight capacity bounded");
		pipeline.acknowledge(Pipeline.CAPACITY, 1);
		check(pipeline.canSubmit(0, 3, true),
			"full queue can still retry its own targets");
		pipeline.retainTargets(java.util.Set.of());
		check(pipeline.canSubmit(99, 4, false),
			"moving away frees stale queue slots");
		pipeline.submit(99, 4, false, 100);
		pipeline.retainTargets(java.util.Set.of());
		check(pipeline.readyToStop(5),
			"pruning does not orphan an issued START");
		pipeline.markStopped(99, 101);
		pipeline.retainTargets(java.util.Set.of());
		check(pipeline.oldest() == null, "finished stale target is pruned");
		pipeline.reset();
		check(pipeline.canSubmit(0, 0, false) && !pipeline.readyToStop(1),
			"reset clears both stage and tick limiter");
		checkServerHandoff();
	}
	
	private static void checkMissedStop()
	{
		int stops = 0;
		int activeEvents = 0;
		boolean acknowledged = false;
		try(var input = MiningStateCompatRegressionTest.class
			.getResourceAsStream("mining-18-12-missed-stop.csv");
			var reader = new BufferedReader(
				new InputStreamReader(input, StandardCharsets.UTF_8)))
		{
			for(String line; (line = reader.readLine()) != null;)
			{
				if(line.startsWith("#") || line.isBlank())
					continue;
				String event = line.split(",")[1];
				if(event.equals("STOP_DESTROY_BLOCK"))
					stops++;
				if(event.equals("ACK"))
					acknowledged = true;
				if(event.equals("ACTIVE") && acknowledged)
					activeEvents++;
			}
		}catch(IOException e)
		{
			throw new AssertionError("Cannot read stalled-target fixture", e);
		}
		check(stops == 1 && acknowledged && activeEvents >= 10,
			"capture reproduces one ACKed STOP followed by sustained active mining");
		// 18:14:30.894: START/STOP 2358/2359 for the held block meets the
		// previous target's delayed slot. The server then emits active mining
		// events for this block, but v4 sends no further STOP for 3+ seconds.
		MultiTargetServer server = new MultiTargetServer();
		server.start(1, 1000);
		server.stop(1, 1000);
		server.start(2, 1001);
		server.stop(2, 1001);
		server.tick();
		check(
			server.active == 2 && server.delayed == -1 && server.destroyed == 1,
			"reproduces ignored STOP followed by active-mining events");
		BreakSchedule schedule = new BreakSchedule();
		schedule.start(0.006666667F);
		check(
			schedule.nextStop(0.006666667F,
				0.006666667F) == BreakSchedule.StopAction.RETRY,
			"held target retries without waiting 107 ticks");
		server.stop(2, 1002);
		server.tick();
		check(server.destroyed == 2 && server.delayed == -1,
			"next-tick STOP re-enters glitch completion without another START or reset");
	}
	
	private static void checkServerHandoff()
	{
		MultiTargetServer immediate = new MultiTargetServer();
		java.util.Set<Long> oldAir = new java.util.HashSet<>();
		immediate.start(0, 1000);
		immediate.stop(0, 1000);
		for(int pos = 1; pos < 20; pos++)
		{
			immediate.start(pos, 1000 + pos);
			immediate.stop(pos, 1000 + pos);
			immediate.tick();
			if(immediate.destroyed >= 0)
				oldAir.add(immediate.destroyed);
		}
		check(oldAir.size() == 19 && !oldAir.contains(1L),
			"immediate next pair loses its target while the prior delayed slot is occupied");
		MultiTargetServer handoff = new MultiTargetServer();
		java.util.Set<Long> newAir = new java.util.HashSet<>();
		handoff.start(0, 1000);
		for(int tick = 1; tick <= 20; tick++)
		{
			handoff.stop(tick - 1, 1000 + tick);
			if(tick < 20)
				handoff.start(tick, 1000 + tick);
			handoff.tick();
			newAir.add(handoff.destroyed);
		}
		check(newAir.size() == 20 && !newAir.contains(-1L),
			"STOP-then-START handoff completes one block per server tick");
	}
	
	private static void checkRecordedPacketOrder()
	{
		Pipeline pipeline = new Pipeline();
		try(var input = MiningStateCompatRegressionTest.class
			.getResourceAsStream("mining-17-28-first-batch.csv");
			var reader = new BufferedReader(
				new InputStreamReader(input, StandardCharsets.UTF_8)))
		{
			String line;
			while((line = reader.readLine()) != null)
			{
				if(line.startsWith("#") || line.isBlank())
					continue;
				String[] fields = line.split(",");
				long now = Math.round(Integer.parseInt(fields[0]) / 50F);
				int sequence = Integer.parseInt(fields[2]);
				long pos = Long.parseLong(fields[3]);
				check(pipeline.canSubmit(999, now, true),
					"recorded overdue replies never block unrelated targets");
				switch(fields[1])
				{
					case "submit" ->
					{
						check(pipeline.canSubmit(pos, now, true),
							"recorded target admitted without confirmation gate");
						pipeline.submit(pos, now, true, sequence);
					}
					case "ack" -> pipeline.acknowledge(sequence, now);
					case "air" -> pipeline.confirm(pos);
					default -> throw new AssertionError(
						"Unknown fixture action");
				}
			}
		}catch(IOException e)
		{
			throw new AssertionError("Cannot read captured mining fixture", e);
		}
		for(int pos = 0; pos < 7; pos++)
			check(pipeline.canSubmit(pos, 20, true),
				"recorded authoritative air completes every pending target");
	}
	
	private static final class MultiTargetServer
	{
		private long active = -1;
		private long destroyPos = -1;
		private long delayed = -1;
		private long destroyed = -1;
		private int startTick;
		private int delayedStartTick;
		
		void start(long pos, int tick)
		{
			active = pos;
			destroyPos = pos;
			startTick = tick;
		}
		
		void stop(long pos, int tick)
		{
			if(destroyPos != pos || destroyed == pos)
				return;
			if(0.01F * (tick - startTick + 1) >= 0.7F)
			{
				destroyed = pos;
				active = -1;
			}else if(delayed == -1)
			{
				delayed = pos;
				delayedStartTick = startTick;
				active = -1;
			}
		}
		
		void tick()
		{
			if(delayed >= 0 && (delayed == destroyed
				|| 0.01F * (delayedStartTick + 1) >= 1))
			{
				destroyed = delayed;
				delayed = -1;
			}
		}
	}
	
	private static void checkTimedMining()
	{
		BreakSchedule schedule = new BreakSchedule();
		schedule.start(0.01F);
		int stopTick = -1;
		for(int tick = 1; tick <= 100; tick++)
		{
			boolean stop = schedule.update(0.01F, tick * 0.01F);
			if(stop)
			{
				check(stopTick == -1, "only one accelerated STOP per target");
				stopTick = tick;
			}
		}
		check(stopTick >= 72 && stopTick < 100,
			"STOP matures before vanilla completion with a tick margin");
		
		// Model the verified 26.3 ServerPlayerGameMode branches: an early
		// STOP stores destroyProgressStart in delayedTickStart; delayed tick
		// passes that unchanged to incrementDestroyProgress. ABORT clears
		// isDestroyingBlock, not hasDelayedDestroy. A quiet period cannot fix
		// it.
		DelayedServer old = new DelayedServer(0.01F);
		old.start(2);
		old.stop(2);
		old.abort();
		for(int tick = 3; tick < 1000; tick++)
			old.tick();
		check(old.delayed && !old.destroyed,
			"reproduces early STOP lockup despite ABORT and quiet ticks");
		DelayedServer fixed = new DelayedServer(0.01F);
		fixed.start(2);
		fixed.stop(2 + stopTick);
		check(fixed.destroyed && !fixed.delayed,
			"mature STOP avoids the stuck delayed-destroy branch");
		DelayedServer fallback = new DelayedServer(0.01F);
		fallback.start(2);
		fallback.stop(2);
		fallback.stop(2 + stopTick);
		fallback.tick();
		check(fallback.destroyed && !fallback.delayed,
			"same-target mature STOP drains a stalled optimistic attempt");
		DelayedServer fast = new DelayedServer(0.01F);
		fast.start(1000);
		fast.stop(1000);
		fast.tick();
		check(fast.destroyed && !fast.delayed,
			"successful optimistic attempts retain one-tick server completion");
		
		schedule.start(0.1F);
		for(int tick = 1; tick <= 6; tick++)
			check(!schedule.update(0.1F, tick * 0.1F), "no early STOP");
		check(!schedule.update(0.02F, 0.02F),
			"tool change resets elapsed mining time");
		check(!schedule.update(0.02F, 0.72F),
			"client progress alone cannot force an early STOP");
		schedule.start(0.1F);
		check(!schedule.update(0, 0.8F),
			"zero mining progress is not accelerated");
		schedule.start(0.1F);
		for(int tick = 0; tick < 40; tick++)
			check(!schedule.waitForConfirmation(),
				"predicted air retains ownership");
		check(schedule.waitForConfirmation(),
			"missing confirmation is bounded");
		schedule.start(0.1F);
		for(int tick = 0; tick < 70; tick++)
			check(!schedule.tickTarget(),
				"target lifetime allows normal completion");
		check(schedule.tickTarget(),
			"repeated server restorations cannot monopolize mining");
		schedule.reset();
		check(!schedule.update(0.1F, 1), "reset clears mature STOP state");
	}
	
	private static final class DelayedServer
	{
		private final float progress;
		private int startTick;
		private boolean active;
		private boolean delayed;
		private boolean destroyed;
		
		private DelayedServer(float progress)
		{
			this.progress = progress;
		}
		
		private void start(int tick)
		{
			startTick = tick;
			active = true;
		}
		
		private void stop(int tick)
		{
			if(progress * (tick - startTick + 1) >= 0.7F)
			{
				destroyed = true;
				active = false;
			}else if(!delayed)
			{
				delayed = true;
				active = false;
			}
		}
		
		private void abort()
		{
			active = false;
		}
		
		private void tick()
		{
			if(delayed && (destroyed || progress * (startTick + 1) >= 1))
			{
				delayed = false;
				destroyed = true;
			}
		}
	}
	
	private static void checkTunnelGeometry()
	{
		Vec3 feet = new Vec3(0.5, 10, 0.5);
		var straight = MiningTunnel.blocks(feet, new Vec3(1, 0, 0), 5);
		check(straight.size() == 10,
			"cardinal tunnel retains five two-high columns");
		check(straight.contains(new BlockPos(5, 11, 0)),
			"cardinal range retained");
		check(!straight.contains(new BlockPos(0, 10, 0)),
			"current opening excluded");
		check(straight.stream().noneMatch(pos -> pos.getY() < 10),
			"horizontal tunnel preserves floor");
		var diagonal = MiningTunnel.blocks(feet, new Vec3(1, 0, 1), 4);
		for(int y = 10; y <= 11; y++)
		{
			check(diagonal.contains(new BlockPos(1, y, 1)),
				"diagonal path included");
			check(
				diagonal.contains(new BlockPos(1, y, 0))
					&& diagonal.contains(new BlockPos(0, y, 1)),
				"both blocking corners cleared at 45 degrees");
		}
		check(MiningTunnel.blocks(feet, new Vec3(1, 1, 0), 4)
			.contains(new BlockPos(1, 13, 0)), "uphill tunnel clears headroom");
		check(MiningTunnel.blocks(feet, new Vec3(1, -1, 0), 4).contains(
			new BlockPos(1, 9, 0)), "downhill tunnel clears stair floor");
		check(MiningTunnel.blocks(feet, Vec3.ZERO, 4).isEmpty(),
			"zero direction creates no excavation");
		// Check body clearance across compass directions, intermediate angles,
		// slopes, negative coordinates, and sideways offsets within a block.
		for(double offset : new double[]{0.2, 0.5, 0.8})
			for(int yaw = 0; yaw < 360; yaw += 15)
				for(int pitch : new int[]{-45, 0, 45})
				{
					double yawRad = Math.toRadians(yaw);
					double pitchRad = Math.toRadians(pitch);
					Vec3 start = new Vec3(-3 + offset, 10, -3 + offset);
					Vec3 aim = new Vec3(Math.cos(yawRad) * Math.cos(pitchRad),
						Math.sin(pitchRad),
						Math.sin(yawRad) * Math.cos(pitchRad));
					var selected =
						new HashSet<>(MiningTunnel.blocks(start, aim, 4));
					for(double t = 0.1; t < 4; t += 0.1)
					{
						Vec3 center = start.add(aim.scale(t));
						for(int x = (int)Math
							.floor(center.x - 0.3 + 1E-7); x <= (int)Math
								.floor(center.x + 0.3 - 1E-7); x++)
							for(int y =
								(int)Math.floor(center.y + 1E-7); y <= (int)Math
									.floor(center.y + 2 - 1E-7); y++)
								for(int z = (int)Math.floor(
									center.z - 0.3 + 1E-7); z <= (int)Math
										.floor(center.z + 0.3 - 1E-7); z++)
								{
									boolean initial =
										x + 1 > start.x - 0.3 + 1E-7
											&& x < start.x + 0.3 - 1E-7
											&& y >= 10 && y < 12
											&& z + 1 > start.z - 0.3 + 1E-7
											&& z < start.z + 0.3 - 1E-7;
									check(
										initial || selected
											.contains(new BlockPos(x, y, z)),
										"swept tunnel clears player footprint at every angle");
								}
					}
				}
	}
	
	private static void checkMiningReadiness()
	{
		var fresh = MiningStateCompat.MiningReadiness.forProgress(0.0002F, 0);
		check(fresh.text().equals("[4:10]"), "hand obsidian countdown on join");
		check(fresh.color() == 0xFF5555, "young timer is red");
		check(MiningStateCompat.MiningReadiness.forProgress(0.0002F, 1250)
			.color() == 0xFFAA00, "partly ready timer is orange");
		check(MiningStateCompat.MiningReadiness.forProgress(0.0002F, 3000)
			.color() == 0xFFFF55, "nearly ready timer is yellow");
		var almost =
			MiningStateCompat.MiningReadiness.forProgress(0.0002F, 4999);
		check(almost.text().equals("[0:01]"), "last partial second rounds up");
		var ready =
			MiningStateCompat.MiningReadiness.forProgress(0.0002F, 5000);
		check(ready.text().equals("[OK]") && ready.color() == 0x55FF55,
			"obsidian readiness ends countdown in green");
		check(
			MiningStateCompat.MiningReadiness.forProgress(0.00004F, 5000)
				.remainingTicks() > 0,
			"air or water invalidates dry readiness");
		check(MiningStateCompat.MiningReadiness.forProgress(0, 5000).text()
			.equals("[Sync]"), "unknown progress never claims ready");
		for(float progress : new float[]{0.0002F, 0.00004F, 0.0047F, 1F})
			for(long age = 0; age < 26000; age++)
				check((MiningStateCompat.MiningReadiness
					.forProgress(progress, age)
					.remainingTicks() == 0) == MiningStateCompat.EarlyStopGuard
						.delayedReady(progress, age),
					"display readiness agrees with accelerated mining guard");
	}
	
	private static void checkEarlyStopGuard()
	{
		Pipeline withheld = new Pipeline();
		withheld.submit(1, 0, false, 100);
		withheld.markStopped(1, -1);
		withheld.acknowledge(100, 1);
		check(withheld.canRetry(1, 3),
			"suppressed STOP cannot overwrite a real packet's ACK sequence");
		var clock = new MiningStateCompat.ServerAgeClock();
		var guard = new MiningStateCompat.EarlyStopGuard();
		clock.sample(67698390); // First authoritative time after the 23:50:44
								// join.
		clock.sample(67699159); // Last time before obsidian START918 at
								// 23:51:24.150.
		check(clock.lowerBound() == 769,
			"capture is a young session despite a very old global world clock");
		float handObsidian = 0.0002F;
		check(
			!MiningStateCompat.EarlyStopGuard.delayedReady(handObsidian,
				clock.lowerBound()),
			"recorded hand-speed obsidian cannot finish an early delayed STOP");
		check(
			MiningStateCompat.EarlyStopGuard.delayedReady(0.006666667F,
				clock.lowerBound()),
			"ordinary stone stays eligible at the SAME recorded session age");
		check(
			MiningStateCompat.EarlyStopGuard.delayedReady(0.0047F,
				clock.lowerBound()),
			"a suitable pick can use the glitch earlier than empty hands");
		guard.start(185, 918, clock.lowerBound());
		check(!guard.canStop(185, handObsidian, clock.lowerBound()),
			"first unsafe FastBreak STOP suppressed");
		guard.acknowledge(917);
		guard.serverTime(800);
		check(!guard.canStop(185, handObsidian, 6000),
			"world age alone cannot repair the OLD absolute START tick");
		guard.acknowledge(918);
		guard.serverTime(800);
		check(!guard.canStop(185, handObsidian, 4290),
			"server elapsed progress below mature threshold suppressed");
		check(guard.canStop(185, handObsidian, 4400),
			"same original position can finish with a server-confirmed mature STOP");
		check(!guard.canStop(186, handObsidian, 6000),
			"mature progress cannot transfer to another block");
		var unsafe = new DelayedServer(handObsidian);
		unsafe.start(769);
		unsafe.stop(769);
		unsafe.tick();
		unsafe.abort();
		check(unsafe.delayed && !unsafe.destroyed,
			"recorded policy poisons delayed slot even after mouse ABORT");
		var protectedAttempt = new DelayedServer(handObsidian);
		protectedAttempt.start(769);
		guard.start(185, 918, 769);
		if(guard.canStop(185, handObsidian, 769))
			protectedAttempt.stop(769);
		protectedAttempt.abort();
		check(!protectedAttempt.delayed,
			"suppressed early STOP leaves no poisoned slot when the player moves on");
		guard.start(186, 940, 800);
		check(guard.canStop(186, 0.006666667F, 800),
			"next ordinary block breaks immediately without a portal");
		guard.start(185, 950, 6000);
		check(guard.canStop(185, handObsidian, 6000),
			"new obsidian START uses the glitch after enough world time");
		var matureSession = new DelayedServer(handObsidian);
		matureSession.start(6000);
		if(guard.canStop(185, handObsidian, 6000))
			matureSession.stop(6000);
		matureSession.tick();
		check(matureSession.destroyed && !matureSession.delayed,
			"aged-session obsidian retains fast delayed completion");
		check(!guard.canStop(185, 0.00004F, 6000),
			"water or airborne penalty cannot reuse dry-ground readiness");
		guard.start(185, 951, 6001);
		guard.acknowledge(950);
		guard.serverTime(6010);
		check(!guard.canStop(185, 0.00004F, 30000),
			"old ACK cannot mature a later START");
		guard.reset();
		check(!guard.canStop(185, handObsidian, 30000),
			"respawn reset releases the old START proof");
		clock.reset();
		check(clock.lowerBound() == 0,
			"portal/respawn begins a new conservative mining timer");
		check(!MiningStateCompat.EarlyStopGuard.delayedReady(handObsidian, 0),
			"fresh-session obsidian remains guarded");
		check(MiningStateCompat.EarlyStopGuard.delayedReady(1, 0),
			"instant START destruction needs no waiting period");
		clock.sample(2000);
		clock.sample(1900);
		check(clock.lowerBound() == 0,
			"authoritative clock rollback resets elapsed estimate");
	}
	
	private static boolean rejectRounds(Tracker state, int targets, int rounds,
		boolean acknowledge)
	{
		boolean requested = false;
		for(int round = 0; round < rounds; round++)
		{
			for(int pos = 0; pos < targets; pos++)
			{
				state.attempt(pos);
				state.blockUpdate(pos, false);
			}
			if(acknowledge)
				state.acknowledge(round + 1);
			for(int tick = 0; tick < 2; tick++)
				requested |= state.tick();
		}
		return requested;
	}
	
	private static void advance(Tracker state, int ticks)
	{
		for(int i = 0; i < ticks; i++)
			state.tick();
	}
	
	private static void check(boolean condition, String description)
	{
		checks++;
		if(!condition)
			throw new AssertionError(description);
	}
}
