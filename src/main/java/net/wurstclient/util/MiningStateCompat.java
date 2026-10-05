/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.wurstclient.WurstClient;
import net.wurstclient.util.BlockBreaker.BlockBreakingParams;

/**
 * Client-thread tracking for SpeedNuker. Packet callbacks run after vanilla's
 * thread check and processing, so cancelled packets never count as corrections.
 * Prediction sequence allocation remains entirely owned by Minecraft.
 */
public final class MiningStateCompat
{
	private static final Tracker tracker = new Tracker();
	private static final Pipeline pipeline = new Pipeline();
	private static ClientLevel level;
	private static ClientPacketListener connection;
	private static BlockBreakingParams ownedTarget;
	private static BlockBreakingParams stagedTarget;
	private static BlockPos rescueTarget;
	private static final BreakSchedule schedule = new BreakSchedule();
	private static final ServerAgeClock serverAge = new ServerAgeClock();
	private static final EarlyStopGuard stopGuard = new EarlyStopGuard();
	private static ClientLevel clockLevel;
	private static ClientPacketListener clockConnection;
	private static LocalPlayer clockPlayer;
	
	private MiningStateCompat()
	{}
	
	public static void reset()
	{
		tracker.reset();
		pipeline.reset();
		schedule.reset();
		ownedTarget = null;
		stagedTarget = null;
		rescueTarget = null;
		level = null;
		connection = null;
	}
	
	private static boolean checkWorld()
	{
		Minecraft mc = WurstClient.MC;
		// Hack toggles reset the mining queue, but not the server's game-mode
		// age.
		if(clockLevel != mc.level || clockConnection != mc.getConnection()
			|| clockPlayer != mc.player)
		{
			serverAge.reset();
			stopGuard.reset();
			clockLevel = mc.level;
			clockConnection = mc.getConnection();
			clockPlayer = mc.player;
			reset();
		}
		if(level != mc.level || connection != mc.getConnection())
		{
			reset();
			level = mc.level;
			connection = mc.getConnection();
		}
		return level != null && connection != null && mc.player != null;
	}
	
	public static void onServerTime(ClientPacketListener source, long gameTime)
	{
		if(!checkWorld() || source != connection)
			return;
		long previousAge = serverAge.lowerBound();
		serverAge.sample(gameTime);
		if(serverAge.lowerBound() < previousAge)
			stopGuard.reset();
		stopGuard.serverTime(serverAge.lowerBound());
	}
	
	/** Records actual vanilla and synthetic STARTs on the client thread. */
	public static void onPredictedAction(Packet<?> packet)
	{
		if(packet instanceof ServerboundPlayerActionPacket action
			&& action.getAction() == Action.START_DESTROY_BLOCK && checkWorld())
			stopGuard.start(action.getPos().asLong(), action.getSequence(),
				serverAge.lowerBound());
	}
	
	/**
	 * HackList-only estimate using the same clock and conditions as the guard.
	 */
	public static MiningReadiness getMiningReadiness()
	{
		if(!checkWorld())
			return new MiningReadiness(-1, 0);
		LocalPlayer player = WurstClient.MC.player;
		if(player.getAbilities().instabuild)
			return new MiningReadiness(0, 1);
		if(serverAge.first == Long.MIN_VALUE)
			return new MiningReadiness(-1, 0);
		float progress = Blocks.OBSIDIAN.defaultBlockState()
			.getDestroyProgress(player, level, player.blockPosition());
		// Vanilla divides mining speed by five while airborne. Creative flight
		// should not make this server-readiness countdown jump around, so undo
		// that penalty only while the player is actively flying.
		if(player.getAbilities().flying)
			progress *= 5;
		return MiningReadiness.forProgress(progress, serverAge.lowerBound());
	}
	
	public record MiningReadiness(long remainingTicks, double fraction)
	{
		static MiningReadiness forProgress(float progress, long age)
		{
			if(!Float.isFinite(progress) || progress <= 0)
				return new MiningReadiness(-1, 0);
			long readyAge = (long)Math.ceil(1.0 / progress) - 1;
			return new MiningReadiness(Math.max(0, readyAge - age),
				Math.min(1, progress * (age + 1.0)));
		}
		
		public String text()
		{
			if(remainingTicks < 0)
				return "[Sync]";
			if(remainingTicks == 0)
				return "[OK]";
			long seconds = (remainingTicks + 19) / 20;
			return "[" + seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "")
				+ seconds % 60 + "]";
		}
		
		public int color()
		{
			if(remainingTicks == 0)
				return 0x55FF55;
			if(fraction >= 0.6)
				return 0xFFFF55;
			if(fraction >= 0.25)
				return 0xFFAA00;
			return 0xFF5555;
		}
	}
	
	public static boolean canStartDelayedBreak(BlockPos pos)
	{
		if(!checkWorld())
			return false;
		if(WurstClient.MC.player.getAbilities().instabuild)
			return true;
		float progress = level.getBlockState(pos)
			.getDestroyProgress(WurstClient.MC.player, level, pos);
		boolean safe =
			EarlyStopGuard.delayedReady(progress, serverAge.lowerBound());
		if(!safe && tracker.now % 20 == 0)
			log("early break deferred; server mining timer too young", pos);
		return safe;
	}
	
	public static boolean canSendAcceleratedStop(BlockPos pos)
	{
		if(!checkWorld())
			return false;
		if(WurstClient.MC.player.getAbilities().instabuild
			|| level.getBlockState(pos).isAir())
			return true;
		float progress = level.getBlockState(pos)
			.getDestroyProgress(WurstClient.MC.player, level, pos);
		boolean safe =
			stopGuard.canStop(pos.asLong(), progress, serverAge.lowerBound());
		if(!safe && tracker.now % 20 == 0)
			log("unsafe accelerated STOP suppressed", pos);
		return safe;
	}
	
	public static boolean ownsTarget()
	{
		return checkWorld() && ownedTarget != null;
	}
	
	public static boolean controlsMining()
	{
		return checkWorld()
			&& (ownedTarget != null || !pipeline.pending.isEmpty());
	}
	
	public static boolean isPending(BlockPos pos)
	{
		return checkWorld() && pipeline.pending.containsKey(pos.asLong());
	}
	
	public static int getPendingCount()
	{
		return checkWorld() ? pipeline.pending.size() : 0;
	}
	
	public static BlockPos getOwnedTarget()
	{
		if(!checkWorld())
			return null;
		if(ownedTarget != null)
			return ownedTarget.pos();
		return rescueTarget;
	}
	
	public static void retainTargets(Iterable<BlockPos> candidates)
	{
		if(!checkWorld())
			return;
		Set<Long> eligible = new HashSet<>();
		for(BlockPos pos : candidates)
			eligible.add(pos.asLong());
		pipeline.retainTargets(eligible);
	}
	
	public static void adoptVanillaTarget(BlockPos pos)
	{
		if(!checkWorld() || ownedTarget != null || !canStartDelayedBreak(pos))
			return;
		ownedTarget = BlockBreaker.getBlockBreakingParams(pos);
		if(ownedTarget == null)
			return;
		schedule.start(level.getBlockState(pos)
			.getDestroyProgress(WurstClient.MC.player, level, pos));
		onAttempt(pos);
		log("existing vanilla target adopted", pos);
		if(!level.getBlockState(pos).isAir())
			sendTimedStop(pos, "optimistic STOP sent");
		// Keep the real active target and its progress; never wait indefinitely
		// for manual mining to finish before SpeedNuker can take ownership.
	}
	
	public static void discardTarget(BlockPos pos)
	{
		pipeline.confirm(pos.asLong());
		tracker.defer(pos.asLong());
		releaseTarget();
	}
	
	/** Submit without waiting for the previous target's network round trip. */
	public static void breakPipelinedBlock(BlockPos pos)
	{
		if(!checkWorld() || ownedTarget != null || !canAttempt(pos)
			|| !canStartDelayedBreak(pos))
			return;
		boolean instant = canBurst(pos);
		if(!pipeline.canSubmit(pos.asLong(), tracker.now, instant))
			return;
		BlockBreakingParams params = BlockBreaker.getBlockBreakingParams(pos);
		if(params == null)
			return;
		var duraSwap = WurstClient.INSTANCE.getHax().duraSwapHack;
		duraSwap.onBeforePacketBreak(pos);
		try
		{
			WurstClient.IMC.getInteractionManager().sendPlayerActionC2SPacket(
				Action.START_DESTROY_BLOCK, pos, params.side());
			int sequence = WurstClient.IMC.getInteractionManager()
				.sendPlayerActionC2SPacketWithSequence(
					Action.STOP_DESTROY_BLOCK, pos, params.side());
			if(sequence < 0)
			{
				log("pipeline STOP withheld by timer guard", pos);
				return;
			}
			// Retry this STOP next tick before another START can replace the
			// active position. The first STOP may meet an occupied delayed
			// slot.
			if(!instant)
				stagedTarget = params;
			pipeline.submit(pos.asLong(), tracker.now, instant, sequence);
			onAttempt(pos);
			log("pipeline target submitted", pos);
		}finally
		{
			duraSwap.onAfterBreakPacket();
		}
	}
	
	private static void finishStagedTarget()
	{
		if(stagedTarget == null)
			return;
		BlockBreakingParams target = stagedTarget;
		stagedTarget = null;
		var duraSwap = WurstClient.INSTANCE.getHax().duraSwapHack;
		duraSwap.onBeforePacketBreak(target.pos());
		try
		{
			int sequence = WurstClient.IMC.getInteractionManager()
				.sendPlayerActionC2SPacketWithSequence(
					Action.STOP_DESTROY_BLOCK, target.pos(), target.side());
			pipeline.markStopped(target.pos().asLong(), sequence);
			log(sequence >= 0 ? "pipeline STOP sent"
				: "staged STOP withheld by timer guard", target.pos());
		}finally
		{
			duraSwap.onAfterBreakPacket();
		}
	}
	
	public static void releaseTarget()
	{
		rescueTarget = null;
		if(checkWorld())
			finishStagedTarget();
		if(!ownsTarget())
			return;
		ownedTarget = null;
		schedule.reset();
		if(WurstClient.MC.gameMode != null)
			WurstClient.MC.gameMode.stopDestroyBlock();
	}
	
	/** Bulk START/STOP is safe only when START itself can finish the block. */
	public static boolean canBurst(BlockPos pos)
	{
		Minecraft mc = WurstClient.MC;
		return !tracker.wasCorrected(pos.asLong())
			&& (mc.player.getAbilities().instabuild
				|| mc.level.getBlockState(pos).getDestroyProgress(mc.player,
					mc.level, pos) >= 1);
	}
	
	public static void breakTimedBlock(BlockPos pos)
	{
		if(!checkWorld())
			return;
		Minecraft mc = WurstClient.MC;
		if(ownedTarget == null)
		{
			ownedTarget = BlockBreaker.getBlockBreakingParams(pos);
			if(ownedTarget == null)
			{
				// Predicted air without an applied server update cannot leave
				// the whole pipeline reserved forever.
				Pipeline.Pending submitted = pipeline.pending.get(pos.asLong());
				if(submitted != null && submitted.acknowledgedAt >= 0
					&& tracker.now - submitted.acknowledgedAt > 48)
					discardTarget(pos);
				return;
			}
			schedule.start(mc.level.getBlockState(pos)
				.getDestroyProgress(mc.player, mc.level, pos));
			// Establish the real vanilla target so cleanup can actually abort
			// it.
			mc.gameMode.destroyDelay = 0;
			if(!mc.gameMode.startDestroyBlock(pos, ownedTarget.side()))
			{
				discardTarget(pos);
				return;
			}
			onAttempt(pos);
			log("timed target started", pos);
			// A stalled pipeline retains its oldest target. Re-establish it
			// as the vanilla target and mature a STOP at the same position to
			// drain the server's delayed destroy before admitting more blocks.
			if(!mc.level.getBlockState(pos).isAir())
				sendTimedStop(pos, "optimistic STOP sent");
			return;
		}
		pos = ownedTarget.pos();
		if(schedule.tickTarget())
		{
			log("target confirmation timed out; deferred", pos);
			discardTarget(pos);
			return;
		}
		// Local predicted air is not server confirmation. Keep ownership while
		// waiting so another START cannot displace an unresolved server target.
		if(mc.level.getBlockState(pos).isAir())
		{
			if(schedule.waitForConfirmation())
				discardTarget(pos);
			return;
		}
		mc.gameMode.destroyDelay = 0;
		mc.gameMode.continueDestroyBlock(pos, ownedTarget.side());
		if(mc.level.getBlockState(pos).isAir())
			return;
		float progress = mc.level.getBlockState(pos)
			.getDestroyProgress(mc.player, mc.level, pos);
		// An acknowledged STOP can have been ignored while another delayed
		// destroy was occupied. Keep STOPping this active position, as
		// FastBreak does, without restarting its accumulated server progress.
		BreakSchedule.StopAction stop =
			schedule.nextStop(progress, mc.gameMode.destroyProgress);
		sendTimedStop(pos, stop == BreakSchedule.StopAction.MATURE
			? "mature STOP sent" : "STOP retry sent");
	}
	
	private static void sendTimedStop(BlockPos pos, String action)
	{
		var duraSwap = WurstClient.INSTANCE.getHax().duraSwapHack;
		duraSwap.onBeforePacketBreak(pos);
		try
		{
			int sequence = WurstClient.IMC.getInteractionManager()
				.sendPlayerActionC2SPacketWithSequence(
					Action.STOP_DESTROY_BLOCK, pos, ownedTarget.side());
			log(sequence >= 0 ? action : "timed STOP withheld by timer guard",
				pos);
		}finally
		{
			duraSwap.onAfterBreakPacket();
		}
	}
	
	public static boolean canAttempt(BlockPos pos)
	{
		return checkWorld() && tracker.canAttempt(pos.asLong())
			&& (!pipeline.pending.containsKey(pos.asLong())
				|| pipeline.canRetry(pos.asLong(), tracker.now));
	}
	
	public static void onAttempt(BlockPos pos)
	{
		if(!checkWorld())
			return;
		tracker.attempt(pos.asLong());
		log("attempt", pos);
	}
	
	public static void onBlockUpdate(ClientPacketListener source, BlockPos pos,
		BlockState state)
	{
		if(!checkWorld() || source != connection)
			return;
		if(state.isAir())
		{
			pipeline.confirm(pos.asLong());
			if(stagedTarget != null && stagedTarget.pos().equals(pos))
				stagedTarget = null;
		}
		if(tracker.blockUpdate(pos.asLong(), state.isAir()))
			log(state.isAir() ? "confirmed air" : "non-air correction", pos);
		if(state.isAir() && (pos.equals(rescueTarget)
			|| ownedTarget != null && ownedTarget.pos().equals(pos)))
			releaseTarget();
	}
	
	public static void onAcknowledgement(ClientPacketListener source,
		int sequence)
	{
		if(checkWorld() && source == connection)
		{
			stopGuard.acknowledge(sequence);
			tracker.acknowledge(sequence);
			pipeline.acknowledge(sequence, tracker.now);
		}
	}
	
	public static void tick()
	{
		if(!checkWorld())
			return;
		boolean needsRescue = tracker.tick();
		if(pipeline.readyToStop(tracker.now))
			finishStagedTarget();
		if(needsRescue && ownedTarget == null)
		{
			Long oldest = pipeline.oldest();
			rescueTarget = oldest == null ? null : BlockPos.of(oldest);
			log("repeated corrections; same-target rescue requested",
				rescueTarget);
		}
	}
	
	private static void log(String action, BlockPos pos)
	{
		WurstClient wurst = WurstClient.INSTANCE;
		if(wurst.getOtfs() == null)
			return;
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("version", 12);
		fields.put("action", action);
		fields.put("position", pos == null ? "" : pos.toShortString());
		fields.put("outstanding", tracker.attempts.size());
		fields.put("pipelinePending", pipeline.pending.size());
		fields.put("targetTicks", schedule.targetTicks);
		fields.put("minimumProgress", schedule.minimumProgress);
		fields.put("confirmedServerAge", serverAge.lowerBound());
		fields.put("startServerAge", stopGuard.startAge);
		if(pos != null && level != null)
		{
			BlockState state = level.getBlockState(pos);
			fields.put("blockState", state.toString());
			fields.put("currentProgress",
				state.getDestroyProgress(WurstClient.MC.player, level, pos));
		}
		wurst.getOtfs().packetToolsOtf.logVerboseExternalEvent("MiningState",
			fields);
	}
	
	/**
	 * Bounded, ordered attempts; ACKs alone never count as block completion.
	 */
	static final class Pipeline
	{
		static final int CAPACITY = 32;
		static final int RETRY_ACK_TICKS = 2;
		static final int ACK_TIMEOUT_TICKS = 40;
		private final LinkedHashMap<Long, Pending> pending =
			new LinkedHashMap<>();
		private long lastTimedTick = Long.MIN_VALUE;
		private Long pendingStop;
		
		void reset()
		{
			pending.clear();
			lastTimedTick = Long.MIN_VALUE;
			pendingStop = null;
		}
		
		boolean canRetry(long pos, long now)
		{
			Pending target = pending.get(pos);
			if(target == null)
				return false;
			return target.acknowledgedAt >= 0
				? now - target.acknowledgedAt >= RETRY_ACK_TICKS
				: now - target.submittedAt >= ACK_TIMEOUT_TICKS;
		}
		
		boolean canSubmit(long pos, long now, boolean instant)
		{
			if(pendingStop != null || !instant && lastTimedTick == now)
				return false;
			return pending.containsKey(pos) ? canRetry(pos, now)
				: pending.size() < CAPACITY;
		}
		
		void submit(long pos, long now, boolean instant, int sequence)
		{
			if(!canSubmit(pos, now, instant))
				return;
			// Replacing the value preserves insertion order and the oldest
			// rescue
			// candidate while allowing an acknowledged failed attempt to retry.
			pending.put(pos, new Pending(sequence, now));
			if(!instant)
			{
				lastTimedTick = now;
				pendingStop = pos;
			}
		}
		
		void acknowledge(int sequence, long now)
		{
			for(Pending target : pending.values())
				if(target.sequence >= 0 && target.acknowledgedAt < 0
					&& target.sequence <= sequence)
					target.acknowledgedAt = now;
		}
		
		boolean readyToStop(long now)
		{
			return pendingStop != null && now > lastTimedTick;
		}
		
		void markStopped(long pos, int sequence)
		{
			Pending target = pending.get(pos);
			if(target != null && sequence >= 0)
			{
				target.sequence = sequence;
				target.acknowledgedAt = -1;
			}
			if(pendingStop != null && pendingStop == pos)
				pendingStop = null;
		}
		
		void confirm(long pos)
		{
			if(pendingStop != null && pendingStop == pos)
				pendingStop = null;
			pending.remove(pos);
		}
		
		void retainTargets(Set<Long> eligible)
		{
			// Moving/turning or changing a filter must not leave unreachable
			// targets occupying every queue slot. Finish an issued START first.
			pending.keySet().removeIf(
				pos -> !pos.equals(pendingStop) && !eligible.contains(pos));
		}
		
		Long oldest()
		{
			return pending.isEmpty() ? null
				: pending.keySet().iterator().next();
		}
		
		private static final class Pending
		{
			private int sequence;
			private final long submittedAt;
			private long acknowledgedAt = -1;
			
			private Pending(int sequence, long now)
			{
				this.sequence = sequence;
				submittedAt = now;
			}
		}
	}
	
	/**
	 * STOP retries retain the same target and its elapsed progress. The mature
	 * STOP remains available when delayed destruction itself cannot finish.
	 */
	static final class BreakSchedule
	{
		private float minimumProgress;
		private int ticks;
		private int confirmationTicks;
		private boolean stopSent;
		private float lastClientProgress;
		private int targetTicks;
		private int timeoutTicks;
		
		void reset()
		{
			minimumProgress = 0;
			ticks = 0;
			confirmationTicks = 0;
			stopSent = false;
			lastClientProgress = 0;
			targetTicks = 0;
			timeoutTicks = 60;
		}
		
		void start(float progress)
		{
			reset();
			minimumProgress = progress;
			timeoutTicks = progress > 0
				? Math.min(4000, (int)Math.ceil(1 / progress) + 60) : 60;
		}
		
		enum StopAction
		{
			RETRY,
			MATURE
		}
		
		StopAction nextStop(float progress, float clientProgress)
		{
			return update(progress, clientProgress) ? StopAction.MATURE
				: StopAction.RETRY;
		}
		
		boolean update(float progress, float clientProgress)
		{
			if(clientProgress < lastClientProgress)
			{
				// Vanilla restarts when the held item changes. Never reuse the
				// previous target/tool's elapsed time for an accelerated STOP.
				ticks = 0;
				minimumProgress = progress;
				stopSent = false;
			}
			lastClientProgress = clientProgress;
			ticks++;
			confirmationTicks = 0;
			minimumProgress = Math.min(minimumProgress, progress);
			if(progress > 0)
				timeoutTicks = Math.max(timeoutTicks,
					Math.min(4000, (int)Math.ceil(1 / progress) + 60));
			// Two extra ticks cover the boundary between client and server
			// ticks.
			if(stopSent || minimumProgress <= 0 || clientProgress < 0.7F
				|| (ticks - 2) * minimumProgress < 0.7F)
				return false;
			stopSent = true;
			return true;
		}
		
		boolean tickTarget()
		{
			return ++targetTicks > timeoutTicks;
		}
		
		boolean waitForConfirmation()
		{
			return ++confirmationTicks > 40;
		}
	}
	
	/**
	 * Server packet deltas are a conservative lower bound, not global world
	 * age.
	 */
	static final class ServerAgeClock
	{
		private long first = Long.MIN_VALUE;
		private long last = Long.MIN_VALUE;
		
		void reset()
		{
			first = last = Long.MIN_VALUE;
		}
		
		void sample(long gameTime)
		{
			if(first == Long.MIN_VALUE || gameTime < last)
				first = gameTime;
			last = gameTime;
		}
		
		long lowerBound()
		{
			return first == Long.MIN_VALUE ? 0 : Math.max(0, last - first);
		}
	}
	
	/** Never create a delayed slot that the stored START tick cannot finish. */
	static final class EarlyStopGuard
	{
		private long target;
		private int startSequence = -1;
		private long startAge;
		private boolean acknowledged;
		private long matureFrom = -1;
		
		void reset()
		{
			startSequence = -1;
			acknowledged = false;
			matureFrom = -1;
			startAge = 0;
		}
		
		void start(long pos, int sequence, long age)
		{
			target = pos;
			startSequence = sequence;
			startAge = age;
			acknowledged = false;
			matureFrom = -1;
		}
		
		void acknowledge(int sequence)
		{
			if(startSequence >= 0 && sequence >= startSequence)
				acknowledged = true;
		}
		
		void serverTime(long age)
		{
			// A time packet after the START's ACK proves the START has already
			// reached the server. Count mature progress from this later anchor.
			if(acknowledged && matureFrom < 0)
				matureFrom = age;
		}
		
		static boolean delayedReady(float progress, long startAge)
		{
			return progress > 0 && progress * (startAge + 1.0) >= 1.0;
		}
		
		boolean canStop(long pos, float progress, long age)
		{
			if(startSequence < 0 || pos != target || progress <= 0)
				return false;
			return delayedReady(progress, startAge) || matureFrom >= 0
				&& progress * Math.max(0, age - matureFrom - 2) >= 0.7;
		}
	}
	
	// Kept independent of Minecraft initialization for deterministic checks.
	static final class Tracker
	{
		static final int RETRY_TICKS = 5;
		static final int WINDOW_TICKS = 40;
		static final int MAX_TARGETS = 4096;
		private final Map<Long, Attempt> attempts = new HashMap<>();
		private final ArrayDeque<Correction> corrections = new ArrayDeque<>();
		private final ArrayDeque<Long> acknowledgements = new ArrayDeque<>();
		private long now;
		private long lastAttempt = -WINDOW_TICKS;
		private long lastSuccess;
		private int lastSequence = -1;
		
		void reset()
		{
			attempts.clear();
			corrections.clear();
			acknowledgements.clear();
			now = 0;
			lastAttempt = -WINDOW_TICKS;
			lastSuccess = 0;
			lastSequence = -1;
		}
		
		boolean canAttempt(long pos)
		{
			Attempt attempt = attempts.get(pos);
			return attempt == null ? attempts.size() < MAX_TARGETS
				: now >= attempt.retryAt;
		}
		
		void attempt(long pos)
		{
			if(!canAttempt(pos))
				return;
			attempts.put(pos, new Attempt(now));
			lastAttempt = now;
		}
		
		boolean wasCorrected(long pos)
		{
			Attempt attempt = attempts.get(pos);
			return attempt != null && attempt.corrected;
		}
		
		void defer(long pos)
		{
			Attempt attempt = new Attempt(now);
			attempt.retryAt = now + 100;
			attempt.corrected = true;
			if(attempts.containsKey(pos) || attempts.size() < MAX_TARGETS)
				attempts.put(pos, attempt);
		}
		
		boolean blockUpdate(long pos, boolean air)
		{
			Attempt attempt = attempts.get(pos);
			if(attempt == null)
				return false;
			if(air)
			{
				attempts.remove(pos);
				lastSuccess = now;
				return true;
			}
			// Duplicate updates for one attempt count only once. A correction
			// permits a faster retry, but never a fresh pair every single tick.
			if(attempt.corrected || now - attempt.sentAt > WINDOW_TICKS)
				return false;
			attempt.retryAt = Math.min(attempt.retryAt, attempt.sentAt + 2);
			attempt.corrected = true;
			if(corrections.size() == 128)
				corrections.removeFirst();
			corrections.addLast(new Correction(pos, now));
			return true;
		}
		
		void acknowledge(int sequence)
		{
			if(sequence <= lastSequence)
				return;
			lastSequence = sequence;
			if(acknowledgements.size() == 128)
				acknowledgements.removeFirst();
			acknowledgements.addLast(now);
		}
		
		boolean tick()
		{
			now++;
			attempts.values().removeIf(a -> now - a.sentAt > 100);
			while(!corrections.isEmpty()
				&& now - corrections.peekFirst().tick > WINDOW_TICKS)
				corrections.removeFirst();
			while(!acknowledgements.isEmpty()
				&& now - acknowledgements.peekFirst() > WINDOW_TICKS)
				acknowledgements.removeFirst();
			if(now - lastAttempt > RETRY_TICKS || now - lastSuccess < 20
				|| acknowledgements.size() < 2 || corrections.size() < 12)
				return false;
			Map<Long, Integer> rejected = new HashMap<>();
			for(Correction correction : corrections)
				rejected.merge(correction.pos, 1, Integer::sum);
			// At least four different targets must each fail more than once.
			if(rejected.values().stream().filter(count -> count >= 2)
				.count() < 4)
				return false;
			attempts.clear();
			corrections.clear();
			acknowledgements.clear();
			lastAttempt = now - WINDOW_TICKS;
			lastSuccess = now;
			return true;
		}
		
		private static final class Attempt
		{
			private final long sentAt;
			private long retryAt;
			private boolean corrected;
			
			private Attempt(long tick)
			{
				sentAt = tick;
				retryAt = tick + RETRY_TICKS;
			}
		}
		
		private record Correction(long pos, long tick)
		{}
	}
}
