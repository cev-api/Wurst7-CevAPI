/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.autoflypath.flight;

import net.wurstclient.autoflypath.PathFlightRuntime;
import net.wurstclient.autoflypath.PathFlightConfig;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.UnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class FlightController
{
	private static final int PACK_RADIUS_CHUNKS = 40;
	private static final int TICKS_BETWEEN_PATH_RETRIES = 20;
	private static final int CULL_INTERVAL_TICKS = 2400;
	private static final int CULL_DISTANCE_BLOCKS = 5000;
	private static final double SEGMENT_EXTEND_DISTANCE = 256.0;
	private static final double LOOK_DEADBAND = 0.3;
	private static final double DUCK_OFFSET = 0.4;
	private static final double FALL_RESET_THRESHOLD = 2.0;
	private static final double FALL_RESET_BLIP = 0.08;
	private static final double SPEED_GOVERNOR_MARGIN = 0.75;
	private static final double TIGHT_GOVERNOR_MARGIN = 0.1;
	private static final double PRECISION_SPEED = 0.6;
	private static final double LAVA_AVOID_RADIUS = 3.5;
	private static final double LAVA_PUSH = 0.6;
	private static final double LAVA_MIN_SPEED = 1.2;
	private static final double LAVA_CLIMB = 0.45;
	private static final double LAVA_SAFE_CLEARANCE = 4.0;
	private static final double ADAPTIVE_CEILING_FLOOR = 1.0;
	private static final double ADAPTIVE_CEILING_RECOVERY = 0.002;
	private static final int GOVERNOR_BLOCKED_REPATH_TICKS = 10;
	private static final int STUCK_TICKS = 8;
	private static final int OSCILLATION_TICKS = 40;
	private static final double ESCAPE_PROBE = 4.0;
	private static final double ESCAPE_SPEED = 1.0;
	private static final int FRONTIER_HOLD_PATIENCE_TICKS = 300;
	private static final int DESYNC_CLUSTER_WINDOW = 100;
	private static final int DESYNC_CAUTION_DURATION = 40;
	private final Minecraft mc = Minecraft.getInstance();
	private final PathFlightConfig config;
	private final BlockPos.MutableBlockPos scratch =
		new BlockPos.MutableBlockPos();
	public final PathManager pathManager;
	private FlightPathfinder context;
	private BetterBlockPos destination;
	private Integer planFinalX;
	private Integer planFinalZ;
	private int cruiseY;
	private boolean destinationIsFinal = true;
	private int planUpdateCooldown;
	private boolean reachedGoal;
	private boolean destSanitized;
	private List<BetterBlockPos> visiblePath = Collections.emptyList();
	private Vec3 lastPos;
	private int ticksSinceProgress;
	private double prevCmdSpeed;
	private int ticksSincePathRequest;
	private int ticksSinceCull;
	private boolean aimTight;
	private boolean frontierHold;
	private int frontierHoldTicks;
	private int frontierPushTicks;
	private int pushRepathCounter;
	private int recoveryCooldown;
	private double bestDistToDest = Double.MAX_VALUE;
	private double bestArcPos;
	private double currentArcPos;
	private int ticksSinceGoalProgress;
	private int oscEscapeCount;
	private int hardRecoveryTicks;
	private Vec3 recoveryDir = new Vec3(0.0, 1.0, 0.0);
	private boolean recoveryHover;
	private static final int OSC_ESCAPES_BEFORE_HARD = 2;
	private static final int HARD_RECOVERY_TICKS = 60;
	private static final int ABORT_TICKS = 400;
	private static final int RECOVER_CLIMB_TICKS = 80;
	private static final int THREAD_PATIENCE = 100;
	private static final int PLAN_UPDATE_TICKS = 10;
	private static final int MAX_SEGMENT = 128;
	private static final int DESCEND_RADIUS = 48;
	private static final int HOVER_AFTER_TICKS = 120;
	private double globalBestDist = Double.MAX_VALUE;
	private int ticksSinceGlobalBest;
	private double serverFallEstimate;
	private double lastYForFall = Double.NaN;
	private Vec3 lastCommandedVel = Vec3.ZERO;
	private boolean debugWasOnFire;
	private int ticksSinceServerCorrection = 100000;
	private int debugBiomeCheckTicks;
	private int settleTicks;
	private int desyncLevel;
	private int desyncCautionTicks;
	private double adaptiveCeiling = Double.MAX_VALUE;
	private int governorBlockedTicks;
	private final ArrayDeque<String> debugTrail = new ArrayDeque();
	private boolean debugProblemActive = false;
	private int debugRepeatSuppress = 0;
	private double debugGovHit = -1.0;
	private double debugGovScale = 1.0;
	private Vec3 debugPrevPos = null;
	
	public FlightController(PathFlightConfig config)
	{
		this.config = config;
		this.pathManager = new PathManager(this);
	}
	
	private LocalPlayer player()
	{
		return this.mc.player;
	}
	
	private ClientLevel level()
	{
		return this.mc.level;
	}
	
	private BlockPos feet()
	{
		return this.player().blockPosition();
	}
	
	private Vec3 feetVec()
	{
		return this.player().position();
	}
	
	private Vec3 head()
	{
		return this.player().getEyePosition();
	}
	
	private BlockState blockAt(int x, int y, int z)
	{
		return this.level().getBlockState((BlockPos)this.scratch.set(x, y, z));
	}
	
	private void log(String message)
	{
		if(this.player() != null)
		{
			this.player().sendSystemMessage(
				(Component)Component.literal((String)message));
		}
	}
	
	private static boolean isBurnHazard(BlockState state)
	{
		return state.is(Blocks.LAVA) || state.is(Blocks.FIRE)
			|| state.is(Blocks.SOUL_FIRE);
	}
	
	public boolean isActive()
	{
		return this.destination != null && this.config.flightProcess;
	}
	
	public boolean isFlying()
	{
		if(!this.config.flightProcess)
		{
			return false;
		}
		if(this.config.assumeFlightHack)
		{
			return true;
		}
		LocalPlayer p = this.player();
		if(p != null)
		{
			Abilities abilities = p.getAbilities();
			return abilities.mayfly || abilities.flying;
		}
		return false;
	}
	
	public BlockPos currentDestination()
	{
		return this.destination;
	}
	
	public boolean hasReachedGoal()
	{
		return this.reachedGoal;
	}
	
	public List<BetterBlockPos> getVisiblePath()
	{
		return this.visiblePath;
	}
	
	public List<BetterBlockPos> getCurrentPath()
	{
		return new ArrayList<BetterBlockPos>(this.pathManager.getPath());
	}
	
	public void flyTo(BlockPos dest)
	{
		this.flyTo(dest.getX(), dest.getY(), dest.getZ());
	}
	
	public void flyTo(int x, int y, int z)
	{
		this.planFinalX = null;
		this.planFinalZ = null;
		this.destinationIsFinal = true;
		this.startPath(new BetterBlockPos(x, y, z));
	}
	
	public void flyTo(int x, int z)
	{
		boolean overworld;
		boolean bl = overworld =
			this.level() != null && this.level().dimension() == Level.OVERWORLD;
		if(!overworld)
		{
			this.planFinalX = null;
			this.planFinalZ = null;
			this.destinationIsFinal = true;
			this.startPath(new BetterBlockPos(x,
				this.player() != null ? this.feet().getY() : 64, z));
			return;
		}
		this.planFinalX = x;
		this.planFinalZ = z;
		this.destinationIsFinal = false;
		this.cruiseY = this.computeCruiseY();
		this.planUpdateCooldown = 0;
		this.startPath(this.computePlanDestination(this.feet()));
	}
	
	private int computeCruiseY()
	{
		int base = this.config.flightCruiseHeight > 0
			? this.config.flightCruiseHeight : this.isNether() ? 118 : 230;
		if(this.level() == null)
		{
			return base;
		}
		int min = this.level().getMinY() + 2;
		int max = this.level().getMinY() + this.level().getHeight() - 2;
		return Math.max(min, Math.min(max, base));
	}
	
	private boolean chunkLoaded(int blockX, int blockZ)
	{
		return this.level() != null && this.level().getChunkSource()
			.getChunk(blockX >> 4, blockZ >> 4, false) != null;
	}
	
	private int surfaceY(int x, int z)
	{
		if(this.level() == null)
		{
			return this.cruiseY;
		}
		return this.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
	}
	
	private BetterBlockPos computePlanDestination(BlockPos feet)
	{
		double dz;
		int fx = this.planFinalX;
		int fz = this.planFinalZ;
		double dx = (double)fx + 0.5 - ((double)feet.getX() + 0.5);
		double horiz = Math.sqrt(dx * dx
			+ (dz = (double)fz + 0.5 - ((double)feet.getZ() + 0.5)) * dz);
		if(horiz <= 48.0 && this.chunkLoaded(fx, fz))
		{
			this.destinationIsFinal = true;
			return new BetterBlockPos(fx, this.surfaceY(fx, fz), fz);
		}
		this.destinationIsFinal = false;
		if(horiz < 1.0)
		{
			return new BetterBlockPos(fx, this.cruiseY, fz);
		}
		double ux = dx / horiz;
		double uz = dz / horiz;
		double cap = Math.min(horiz, 128.0);
		double reach = 0.0;
		for(double d = 8.0; d <= cap
			&& this.chunkLoaded((int)Math.floor((double)feet.getX() + ux * d),
				(int)Math.floor((double)feet.getZ() + uz * d)); d += 8.0)
		{
			reach = d;
		}
		double seg = Math.max(16.0, Math.min(reach, cap));
		return new BetterBlockPos(
			(int)Math.floor((double)feet.getX() + ux * seg), this.cruiseY,
			(int)Math.floor((double)feet.getZ() + uz * seg));
	}
	
	private void updateFlightPlan()
	{
		BetterBlockPos newDest = this.computePlanDestination(this.feet());
		boolean moved = this.destination == null
			|| FlightController.sqDist(newDest, this.destination) > 64L;
		this.destination = newDest;
		boolean bl = this.destSanitized = !this.destinationIsFinal;
		if(moved && !this.pathManager.isRecalculating())
		{
			this.pathManager.pathToDestination(this.feet());
		}
	}
	
	private double progressDistance(Vec3 playerPos)
	{
		if(this.planFinalX != null && !this.destinationIsFinal)
		{
			double dx = (double)this.planFinalX.intValue() + 0.5 - playerPos.x;
			double dz = (double)this.planFinalZ.intValue() + 0.5 - playerPos.z;
			return Math.sqrt(dx * dx + dz * dz);
		}
		return playerPos.distanceTo(Vec3.atCenterOf((Vec3i)this.destination));
	}
	
	private static long sqDist(BetterBlockPos a, BetterBlockPos b)
	{
		long dx = a.getX() - b.getX();
		long dy = a.getY() - b.getY();
		long dz = a.getZ() - b.getZ();
		return dx * dx + dy * dy + dz * dz;
	}
	
	public void clientTick()
	{
		if(!this.isActive())
		{
			return;
		}
		this.onTick();
		if(this.isFlying())
		{
			this.tick();
		}
		if(this.hasReachedGoal())
		{
			this.stop();
		}
	}
	
	public void stop()
	{
		this.destination = null;
		this.planFinalX = null;
		this.planFinalZ = null;
		this.destinationIsFinal = true;
		this.reachedGoal = false;
		this.lastPos = null;
		this.serverFallEstimate = 0.0;
		this.lastYForFall = Double.NaN;
		this.visiblePath = Collections.emptyList();
		this.pathManager.clear();
		this.destroyContext();
	}
	
	private void startPath(BetterBlockPos dest)
	{
		this.destination = dest;
		this.reachedGoal = false;
		this.destSanitized = false;
		this.lastPos = null;
		this.ticksSinceProgress = 0;
		this.prevCmdSpeed = 0.0;
		this.ticksSincePathRequest = 0;
		this.bestDistToDest = Double.MAX_VALUE;
		this.bestArcPos = 0.0;
		this.currentArcPos = 0.0;
		this.ticksSinceGoalProgress = 0;
		this.serverFallEstimate = 0.0;
		this.lastYForFall = Double.NaN;
		this.settleTicks = 0;
		this.desyncLevel = 0;
		this.desyncCautionTicks = 0;
		this.adaptiveCeiling = Double.MAX_VALUE;
		this.governorBlockedTicks = 0;
		this.oscEscapeCount = 0;
		this.hardRecoveryTicks = 0;
		this.recoveryHover = false;
		this.globalBestDist = Double.MAX_VALUE;
		this.ticksSinceGlobalBest = 0;
		this.pathManager.clear();
		this.rebuildContext();
		if(this.context != null)
		{
			this.pathManager.pathToDestination(this.feet());
		}
	}
	
	private void destroyContext()
	{
		if(this.context != null)
		{
			FlightPathfinder old = this.context;
			this.context = null;
			PathFlightRuntime.EXECUTOR.execute(old::destroy);
		}
	}
	
	private boolean isNether()
	{
		return this.player() != null
			&& this.player().level().dimension() == Level.NETHER;
	}
	
	private void rebuildContext()
	{
		if(this.level() == null || this.player() == null)
		{
			return;
		}
		this.destroyContext();
		boolean predict = this.isNether() && this.config.flightPredictTerrain;
		long seed = predict ? this.config.flightSeed : 0L;
		this.context = new FlightPathfinder(seed, this.level().getMinY(),
			this.level().getHeight(), predict);
		this.repackChunks();
	}
	
	public void repackChunks()
	{
		if(this.context == null || this.level() == null)
		{
			return;
		}
		ClientChunkCache chunkSource = this.level().getChunkSource();
		int pcx = this.feet().getX() >> 4;
		int pcz = this.feet().getZ() >> 4;
		for(int x = pcx - 40; x <= pcx + 40; ++x)
		{
			for(int z = pcz - 40; z <= pcz + 40; ++z)
			{
				LevelChunk chunk = chunkSource.getChunk(x, z, false);
				if(chunk == null || chunk.isEmpty())
					continue;
				this.context.queueForPacking(chunk);
			}
		}
	}
	
	public void onChunkLoaded(LevelChunk chunk)
	{
		if(this.context != null && chunk != null)
		{
			this.context.queueForPacking(chunk);
		}
	}
	
	public void onBlockUpdate(BlockPos pos, BlockState state)
	{
		if(this.context != null)
		{
			this.context.queueBlockUpdate(pos, state);
		}
	}
	
	private void onTick()
	{
		long wantSeed;
		if(this.destination == null)
		{
			this.pathManager.clear();
			this.visiblePath = Collections.emptyList();
			return;
		}
		++this.ticksSinceServerCorrection;
		if(this.settleTicks > 0)
		{
			--this.settleTicks;
		}
		if(this.desyncCautionTicks > 0 && --this.desyncCautionTicks == 0)
		{
			this.desyncLevel = 0;
		}
		if(this.player() != null)
		{
			double y = this.player().getY();
			if(!Double.isNaN(this.lastYForFall))
			{
				if(y < this.lastYForFall)
				{
					this.serverFallEstimate += this.lastYForFall - y;
				}else if(y > this.lastYForFall)
				{
					this.serverFallEstimate = 0.0;
				}
			}
			this.lastYForFall = y;
		}
		if(this.context == null)
		{
			this.rebuildContext();
			if(this.context != null)
			{
				this.pathManager.pathToDestination(this.feet());
			}
			return;
		}
		boolean wantPredict =
			this.isNether() && this.config.flightPredictTerrain;
		long l = wantSeed = wantPredict ? this.config.flightSeed : 0L;
		if(this.context.isPredictTerrain() != wantPredict
			|| wantPredict && this.context.getSeed() != wantSeed)
		{
			this.rebuildContext();
			this.pathManager.pathToDestination(this.feet());
			return;
		}
		this.sanitizeDestination();
		if(this.pathManager.getPath().isEmpty())
		{
			if(!this.pathManager.isRecalculating()
				&& ++this.ticksSincePathRequest >= 20)
			{
				this.ticksSincePathRequest = 0;
				this.pathManager.pathToDestination(this.feet());
			}
			return;
		}
		if(++this.ticksSinceCull >= 2400)
		{
			this.ticksSinceCull = 0;
			this.context.queueCacheCulling(this.feet().getX() >> 4,
				this.feet().getZ() >> 4, 5000);
		}
		if(this.config.flightDebug && this.isNether()
			&& this.context.getBiomeRisk() != null
			&& ++this.debugBiomeCheckTicks >= 100)
		{
			this.debugBiomeCheckTicks = 0;
			ResourceKey<Biome> predicted = this.context.getBiomeRisk()
				.biomeAt(this.feet().getX(), this.feet().getZ());
			ResourceKey actual =
				this.level().getBiome(this.feet()).unwrapKey().orElse(null);
			if(actual != null && !actual.equals(predicted))
			{
				this.log("[AutoFly] biome predictor MISMATCH: predicted "
					+ String.valueOf(predicted.identifier()) + " actual "
					+ String.valueOf(actual.identifier()));
			}
		}
		this.pathManager.tick();
		int near = this.pathManager.getNear();
		List<BetterBlockPos> path = this.pathManager.getPath();
		this.visiblePath = path.subList(Math.min(near, path.size()),
			Math.min(near + 80, path.size()));
	}
	
	private void tick()
	{
		boolean threading;
		boolean overworld;
		Vec3 target;
		Vec3 destCenter;
		double distToDest0;
		if(this.destination == null || this.reachedGoal)
		{
			return;
		}
		Vec3 playerPos = this.player().position();
		if(this.planFinalX != null && --this.planUpdateCooldown <= 0)
		{
			this.planUpdateCooldown = 10;
			this.updateFlightPlan();
		}
		if((distToDest0 = playerPos.distanceTo(destCenter = Vec3.atCenterOf(
			(Vec3i)this.destination))) < this.config.flightArrivalRadius
			&& this.destinationIsFinal)
		{
			if(this.serverFallEstimate > 2.0)
			{
				this.player().setDeltaMovement(0.0, 0.08, 0.0);
				return;
			}
			this.reachedGoal = true;
			return;
		}
		double progressDist = this.progressDistance(playerPos);
		if(progressDist < this.globalBestDist - 1.0)
		{
			this.globalBestDist = progressDist;
			this.ticksSinceGlobalBest = 0;
		}else if(++this.ticksSinceGlobalBest > 400)
		{
			this.log(
				"[AutoFly] destination appears unreachable from here - stopping.");
			this.stop();
			return;
		}
		if(this.config.flightDebug)
		{
			boolean onFire = this.player().isOnFire();
			if(onFire && !this.debugWasOnFire)
			{
				this.log(String.format(Locale.ROOT,
					"[AutoFly] IGNITED pos=%.2f,%.2f,%.2f vel=%.2f,%.2f,%.2f lavaClearBelow=%.2f serverCorrectionAge=%d",
					playerPos.x, playerPos.y, playerPos.z,
					this.lastCommandedVel.x, this.lastCommandedVel.y,
					this.lastCommandedVel.z, this.lavaClearanceBelow(playerPos),
					this.ticksSinceServerCorrection));
				for(String s : this.debugTrail)
				{
					this.log("   " + s);
				}
			}
			this.debugWasOnFire = onFire;
		}
		if(!this.level().noBlockCollision((Entity)this.player(),
			this.player().getBoundingBox().deflate(1.0E-6)))
		{
			this.performExtraction(playerPos);
			return;
		}
		if(this.frontierHold)
		{
			++this.frontierHoldTicks;
			this.frontierPushTicks = 40;
			if(this.config.flightDebug && this.frontierHoldTicks % 100 == 0)
			{
				this.log(
					"[AutoFly] pushing through unloaded-chunk frontier for "
						+ this.frontierHoldTicks + " ticks");
			}
		}else
		{
			this.frontierHoldTicks = 0;
		}
		if(this.frontierPushTicks > 0)
		{
			--this.frontierPushTicks;
		}
		boolean holdSuppress =
			this.frontierHold && this.frontierHoldTicks < 300;
		double distToDest = playerPos.distanceTo(destCenter);
		int nearIndex = this.pathManager.getNear();
		if(distToDest < this.bestDistToDest - 0.5
			|| this.currentArcPos > this.bestArcPos + 0.15 || holdSuppress)
		{
			this.bestDistToDest = Math.min(this.bestDistToDest, distToDest);
			this.bestArcPos = Math.max(this.bestArcPos, this.currentArcPos);
			this.ticksSinceGoalProgress = 0;
			this.oscEscapeCount = 0;
		}else
		{
			++this.ticksSinceGoalProgress;
		}
		this.frontierHold = false;
		List<BetterBlockPos> path = this.pathManager.getPath();
		if(path.isEmpty())
		{
			target = destCenter;
		}else
		{
			target = this.followTarget(playerPos, path, nearIndex);
			if(target.distanceToSqr(playerPos) < 4.0
				&& playerPos.distanceToSqr(destCenter) > 9.0
				&& nearIndex >= path.size() - 3)
			{
				if(!this.pathManager.isRecalculating())
				{
					this.pathManager.pathToDestination(this.feet());
				}
				if(this.pathManager.isComplete())
				{
					target = destCenter;
				}
			}
		}
		if(this.frontierPushTicks > 0)
		{
			Vec3 toDest = destCenter.subtract(playerPos);
			Vec3 horiz = new Vec3(toDest.x, 0.0, toDest.z);
			if(horiz.lengthSqr() > 1.0E-6)
			{
				target = playerPos.add(horiz.normalize().scale(24.0));
			}
			if(++this.pushRepathCounter >= 20
				&& !this.pathManager.isRecalculating())
			{
				this.pushRepathCounter = 0;
				this.pathManager.pathToDestination(this.feet());
			}
		}
		if(this.recoveryCooldown > 0)
		{
			--this.recoveryCooldown;
		}
		boolean bl = overworld =
			this.level() != null && this.level().dimension() == Level.OVERWORLD;
		if(overworld && !this.aimTight && this.hardRecoveryTicks == 0
			&& this.frontierPushTicks == 0 && this.recoveryCooldown == 0
			&& this.ticksSinceGlobalBest > 80)
		{
			double sp = Math.max(0.5, this.config.flightHorizontalSpeed);
			double upClear =
				this.sweptCollisionDistance(new Vec3(0.0, sp, 0.0), sp + 0.75);
			if(upClear > 4.0)
			{
				this.recoveryDir = new Vec3(0.0, 1.0, 0.0);
				this.recoveryHover = false;
			}else
			{
				double[] clear = new double[1];
				this.recoveryDir = this.mostOpenDirection(clear);
				this.recoveryHover = clear[0] < 6.0;
			}
			this.hardRecoveryTicks = 60;
			this.recoveryCooldown = 100;
			if(this.config.flightDebug)
			{
				this.log("[AutoFly] no net progress - "
					+ (this.recoveryHover ? "hovering" : "climbing")
					+ " to clear obstacle");
			}
		}
		if(this.hardRecoveryTicks > 0)
		{
			--this.hardRecoveryTicks;
			if(this.recoveryHover)
			{
				this.lastCommandedVel = Vec3.ZERO;
				this.player().setDeltaMovement(Vec3.ZERO);
				this.player().resetFallDistance();
				this.trackProgress(playerPos);
				return;
			}
			double sp = Math.max(0.5, this.config.flightHorizontalSpeed);
			double hit = this.sweptCollisionDistance(this.recoveryDir.scale(sp),
				sp + 0.75);
			double allowed = Math.max(0.0, hit - 0.75);
			if(allowed < 0.1)
			{
				this.hardRecoveryTicks = 0;
			}else
			{
				Vec3 v;
				this.lastCommandedVel =
					v = this.recoveryDir.scale(Math.min(sp, allowed));
				this.player().setDeltaMovement(v);
				this.player().resetFallDistance();
				if(this.hardRecoveryTicks % 20 == 0
					&& !this.pathManager.isRecalculating())
				{
					this.pathManager.pathToDestination(this.feet());
				}
				this.trackProgress(playerPos);
				return;
			}
		}
		boolean bl2 =
			threading = this.aimTight && this.ticksSinceGlobalBest < 100;
		if(!(this.frontierPushTicks != 0 || threading
			|| this.ticksSinceProgress <= 8
				&& this.ticksSinceGoalProgress <= 40))
		{
			boolean osc = this.ticksSinceGoalProgress > 40;
			this.performEscape(playerPos, target.subtract(playerPos));
			this.trackProgress(playerPos);
			this.ticksSinceProgress = 0;
			if(osc)
			{
				this.ticksSinceGoalProgress = 0;
				this.bestDistToDest = distToDest;
				if(++this.oscEscapeCount >= 2)
				{
					this.oscEscapeCount = 0;
					double[] clear = new double[1];
					this.recoveryDir = this.mostOpenDirection(clear);
					this.recoveryHover =
						clear[0] < 6.0 || this.ticksSinceGlobalBest > 120;
					this.hardRecoveryTicks = 60;
					if(this.config.flightDebug)
					{
						this.log("[AutoFly] persistent oscillation - recovery "
							+ (this.recoveryHover ? "HOVER"
								: String.format(Locale.ROOT,
									"MOVE %.2f,%.2f,%.2f clear=%.1f",
									this.recoveryDir.x, this.recoveryDir.y,
									this.recoveryDir.z, clear[0])));
					}
				}
			}
			return;
		}
		this.driveToward(playerPos, target, destCenter);
		this.trackProgress(playerPos);
		this.prevCmdSpeed = this.lastCommandedVel.length();
		if(this.config.flightDebug)
		{
			this.flightDebugTick(playerPos, target);
		}
	}
	
	private void performExtraction(Vec3 playerPos)
	{
		ArrayList<Vec3> dirs = new ArrayList<Vec3>();
		if(this.lastCommandedVel.lengthSqr() > 1.0E-6)
		{
			dirs.add(this.lastCommandedVel.normalize().scale(-1.0));
		}
		dirs.add(new Vec3(0.0, 1.0, 0.0));
		dirs.add(new Vec3(0.0, -1.0, 0.0));
		dirs.add(new Vec3(1.0, 0.0, 0.0));
		dirs.add(new Vec3(-1.0, 0.0, 0.0));
		dirs.add(new Vec3(0.0, 0.0, 1.0));
		dirs.add(new Vec3(0.0, 0.0, -1.0));
		AABB box = this.player().getBoundingBox().deflate(1.0E-6);
		for(double mag = 0.1; mag <= 0.85; mag += 0.15)
		{
			for(Vec3 d : dirs)
			{
				Vec3 off = d.scale(mag);
				if(!this.level().noBlockCollision((Entity)this.player(),
					box.move(off.x, off.y, off.z)))
					continue;
				this.player().setPos(playerPos.x + off.x, playerPos.y + off.y,
					playerPos.z + off.z);
				this.player().setDeltaMovement(Vec3.ZERO);
				if(this.config.flightDebug)
				{
					this.log(String.format(Locale.ROOT,
						"[AutoFly] embedded in terrain - extracted by %.2f,%.2f,%.2f",
						off.x, off.y, off.z));
				}
				return;
			}
		}
		this.player().setPos(playerPos.x, playerPos.y + 0.2, playerPos.z);
	}
	
	private void sanitizeDestination()
	{
		if(this.destSanitized || this.destination == null
			|| this.level() == null)
		{
			return;
		}
		int dcx = this.destination.getX() >> 4;
		int dcz = this.destination.getZ() >> 4;
		if(this.level().getChunkSource().getChunk(dcx, dcz, false) == null)
		{
			return;
		}
		this.destSanitized = true;
		BetterBlockPos safe = this.findSafeDestination(this.destination);
		if(safe != null)
		{
			if(this.config.flightDebug)
			{
				this.log(
					"[AutoFly] destination in/over hazard - retargeting to "
						+ safe.getX() + "," + safe.getY() + "," + safe.getZ());
			}
			this.destination = safe;
			this.globalBestDist = Double.MAX_VALUE;
			this.ticksSinceGlobalBest = 0;
			if(!this.pathManager.isRecalculating())
			{
				this.pathManager.pathToDestination(this.feet());
			}
		}
	}
	
	private BetterBlockPos findSafeDestination(BlockPos dest)
	{
		if(this.isSafeCell(dest.getX(), dest.getY(), dest.getZ()))
		{
			return null;
		}
		for(int r = 1; r <= 24; ++r)
		{
			BetterBlockPos best = null;
			double bestD = Double.MAX_VALUE;
			for(int dx = -r; dx <= r; ++dx)
			{
				for(int dy = -r; dy <= r; ++dy)
				{
					for(int dz = -r; dz <= r; ++dz)
					{
						double d;
						int z;
						int y;
						int x;
						if(Math.max(Math.abs(dx),
							Math.max(Math.abs(dy), Math.abs(dz))) != r
							|| !this.isSafeCell(x = dest.getX() + dx,
								y = dest.getY() + dy, z = dest.getZ() + dz)
							|| !((d =
								(double)(dx * dx + dy * dy + dz * dz)) < bestD))
							continue;
						bestD = d;
						best = new BetterBlockPos(x, y, z);
					}
				}
			}
			if(best == null)
				continue;
			return best;
		}
		return null;
	}
	
	private boolean isSafeCell(int x, int y, int z)
	{
		int i;
		for(i = 0; i < 3; ++i)
		{
			BlockState s = this.blockAt(x, y + i, z);
			if(s.isAir() && !FlightController.isBurnHazard(s))
				continue;
			return false;
		}
		for(i = 1; i <= 3; ++i)
		{
			if(!FlightController.isBurnHazard(this.blockAt(x, y - i, z)))
				continue;
			return false;
		}
		return true;
	}
	
	private boolean lineClearOfHazard(Vec3 from, Vec3 to)
	{
		if(this.level() == null)
		{
			return true;
		}
		Vec3 d = to.subtract(from);
		double len = d.length();
		if(len < 1.0E-6)
		{
			return true;
		}
		Vec3 dir = d.scale(1.0 / len);
		block0: for(double t = 0.0; t <= len + 1.0E-9; t += 0.5)
		{
			Vec3 p = from.add(dir.scale(Math.min(t, len)));
			int cx = (int)Math.floor(p.x);
			int cy = (int)Math.floor(p.y);
			int cz = (int)Math.floor(p.z);
			for(int dx = -1; dx <= 1; ++dx)
			{
				for(int dz = -1; dz <= 1; ++dz)
				{
					if(!FlightController
						.isBurnHazard(this.blockAt(cx + dx, cy, cz + dz))
						&& !FlightController.isBurnHazard(
							this.blockAt(cx + dx, cy + 1, cz + dz))
						&& !FlightController.isBurnHazard(
							this.blockAt(cx + dx, cy + 2, cz + dz)))
						continue;
					return false;
				}
			}
			for(int dy = 1; dy <= 4; ++dy)
			{
				BlockState below = this.blockAt(cx, cy - dy, cz);
				if(FlightController.isBurnHazard(below))
				{
					return false;
				}
				if(!below.isAir())
					continue block0;
			}
		}
		return true;
	}
	
	private Vec3 mostOpenDirection(double[] outClear)
	{
		double s = 1.0 / Math.sqrt(2.0);
		Vec3[] dirs = new Vec3[]{new Vec3(0.0, 1.0, 0.0),
			new Vec3(0.0, -1.0, 0.0), new Vec3(1.0, 0.0, 0.0),
			new Vec3(-1.0, 0.0, 0.0), new Vec3(0.0, 0.0, 1.0),
			new Vec3(0.0, 0.0, -1.0), new Vec3(s, 0.0, s), new Vec3(s, 0.0, -s),
			new Vec3(-s, 0.0, s), new Vec3(-s, 0.0, -s), new Vec3(s, s, 0.0),
			new Vec3(s, -s, 0.0), new Vec3(-s, s, 0.0), new Vec3(-s, -s, 0.0),
			new Vec3(0.0, s, s), new Vec3(0.0, s, -s), new Vec3(0.0, -s, s),
			new Vec3(0.0, -s, -s)};
		Vec3 best = dirs[0];
		double bestClear = -1.0;
		for(Vec3 d : dirs)
		{
			double c = this.sweptCollisionDistance(d.scale(8.0), 8.0);
			if(!(c > bestClear))
				continue;
			bestClear = c;
			best = d;
		}
		outClear[0] = bestClear;
		return best;
	}
	
	private void performEscape(Vec3 playerPos, Vec3 travelDir)
	{
		Vec3[] dirs = new Vec3[]{new Vec3(0.0, 1.0, 0.0),
			new Vec3(0.0, -1.0, 0.0), new Vec3(1.0, 0.0, 0.0),
			new Vec3(-1.0, 0.0, 0.0), new Vec3(0.0, 0.0, 1.0),
			new Vec3(0.0, 0.0, -1.0), new Vec3(1.0, 0.0, 1.0).normalize(),
			new Vec3(1.0, 0.0, -1.0).normalize(),
			new Vec3(-1.0, 0.0, 1.0).normalize(),
			new Vec3(-1.0, 0.0, -1.0).normalize()};
		double[] clears = new double[dirs.length];
		double bestClear = -1.0;
		for(int i = 0; i < dirs.length; ++i)
		{
			clears[i] = this.sweptCollisionDistance(dirs[i].scale(4.0), 4.0);
			bestClear = Math.max(bestClear, clears[i]);
		}
		double threshold = Math.min(Math.max(1.0, bestClear * 0.9), bestClear);
		Vec3 tn = travelDir.lengthSqr() > 1.0E-6 ? travelDir.normalize() : null;
		Vec3 best = null;
		double bestChosenClear = 0.0;
		double bestScore = Double.NEGATIVE_INFINITY;
		for(int i = 0; i < dirs.length; ++i)
		{
			double score;
			if(clears[i] < threshold)
				continue;
			double d = score = tn == null ? clears[i] : dirs[i].dot(tn);
			if(!(score > bestScore))
				continue;
			bestScore = score;
			best = dirs[i];
			bestChosenClear = clears[i];
		}
		if(best != null)
		{
			double escSpeed =
				Math.max(0.5, Math.min(1.0, this.config.flightHorizontalSpeed));
			escSpeed = Math.min(escSpeed, Math.max(0.1, bestChosenClear * 0.6));
			this.player().setDeltaMovement(best.scale(escSpeed));
			this.prevCmdSpeed = escSpeed;
			if(this.config.flightDebug)
			{
				this.log(String.format(Locale.ROOT,
					"[AutoFly] escape dir=%.2f,%.2f,%.2f speed=%.2f clear=%.2f",
					best.x, best.y, best.z, escSpeed, bestChosenClear));
			}
		}
		if(!this.pathManager.isRecalculating())
		{
			this.pathManager.pathToDestination(this.feet());
		}
	}
	
	private Vec3 followTarget(Vec3 playerPos, List<BetterBlockPos> path,
		int near)
	{
		int next;
		double lookahead;
		int bestSeg = Math.max(0, Math.min(near, path.size() - 1));
		Vec3 bestPoint = Vec3.atCenterOf((Vec3i)((Vec3i)path.get(bestSeg)));
		double bestDistSq = playerPos.distanceToSqr(bestPoint);
		int lo = Math.max(0, near - 3);
		int hi = Math.min(path.size() - 2, near + 8);
		for(int i = lo; i <= hi; ++i)
		{
			Vec3 a = Vec3.atCenterOf((Vec3i)((Vec3i)path.get(i)));
			Vec3 b = Vec3.atCenterOf((Vec3i)((Vec3i)path.get(i + 1)));
			Vec3 ab = b.subtract(a);
			double abLenSq = ab.lengthSqr();
			double t = abLenSq < 1.0E-9 ? 0.0 : Math.max(0.0,
				Math.min(1.0, playerPos.subtract(a).dot(ab) / abLenSq));
			Vec3 proj = a.add(ab.scale(t));
			double dsq = playerPos.distanceToSqr(proj);
			if(!(dsq < bestDistSq))
				continue;
			bestDistSq = dsq;
			bestSeg = i;
			bestPoint = proj;
		}
		double arc = 0.0;
		for(int k = 0; k < bestSeg; ++k)
		{
			arc += Vec3.atCenterOf((Vec3i)((Vec3i)path.get(k)))
				.distanceTo(Vec3.atCenterOf((Vec3i)((Vec3i)path.get(k + 1))));
		}
		this.currentArcPos =
			arc + Vec3.atCenterOf((Vec3i)((Vec3i)path.get(bestSeg)))
				.distanceTo(bestPoint);
		this.aimTight = false;
		for(double d = lookahead = Math.max(3.0,
			Math.min(32.0,
				this.config.flightHorizontalSpeed * 1.5)); d >= 0.5; d -=
					d > 8.0 ? 1.0 : 0.5)
		{
			Vec3 cand = this.advanceAlongPath(path, bestSeg, bestPoint, d);
			if(this.clearViewForPlayer(playerPos, cand)
				&& this.octreeClear(playerPos, cand)
				&& this.lineClearOfHazard(playerPos, cand))
			{
				return cand;
			}
			Vec3 low = cand.add(0.0, -0.4, 0.0);
			if(!this.clearViewForPlayer(playerPos, low)
				|| !this.octreeClear(playerPos, low)
				|| !this.lineClearOfHazard(playerPos, low))
				continue;
			return low;
		}
		this.aimTight = true;
		int lastIdx = path.size() - 1;
		for(int j = Math.min(bestSeg + 8, lastIdx); j > bestSeg; --j)
		{
			Vec3 vertex = Vec3.atCenterOf((Vec3i)((Vec3i)path.get(j)));
			if(!(vertex.distanceToSqr(playerPos) > 0.12249999999999998)
				|| !this.clearViewForPlayer(playerPos, vertex)
				|| !this.octreeClear(playerPos, vertex)
				|| !this.lineClearOfHazard(playerPos, vertex))
				continue;
			return vertex;
		}
		for(next = Math.min(bestSeg + 1, lastIdx); next < lastIdx
			&& Vec3.atCenterOf((Vec3i)((Vec3i)path.get(next)))
				.distanceToSqr(playerPos) < 0.12249999999999998; ++next)
		{}
		return Vec3.atCenterOf((Vec3i)((Vec3i)path.get(next)));
	}
	
	private boolean octreeClear(Vec3 from, Vec3 to)
	{
		FlightPathfinder context = this.context;
		if(context == null)
		{
			return true;
		}
		return context.pathLineClear(from, to);
	}
	
	private Vec3 advanceAlongPath(List<BetterBlockPos> path, int seg, Vec3 from,
		double dist)
	{
		Vec3 prev = from;
		double remaining = dist;
		for(int j = seg + 1; j < path.size(); ++j)
		{
			Vec3 cur = Vec3.atCenterOf((Vec3i)((Vec3i)path.get(j)));
			double s = prev.distanceTo(cur);
			if(s >= remaining)
			{
				return prev.add(cur.subtract(prev).scale(remaining / s));
			}
			remaining -= s;
			prev = cur;
		}
		return prev;
	}
	
	private void driveToward(Vec3 playerPos, Vec3 target, Vec3 destCenter)
	{
		Vec3 vel;
		Vec3 delta = target.subtract(playerPos);
		double dist = delta.length();
		this.debugGovHit = -1.0;
		this.debugGovScale = 1.0;
		double horizDist = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		if(this.config.flightFaceTravel && horizDist > 0.3)
		{
			this.faceToward(playerPos, target);
		}
		if(this.adaptiveCeiling < this.config.flightHorizontalSpeed)
		{
			this.adaptiveCeiling = Math.min(this.config.flightHorizontalSpeed,
				this.adaptiveCeiling + 0.002);
		}
		double maxSpeed =
			Math.min(this.config.flightHorizontalSpeed, this.adaptiveCeiling);
		if(dist < 1.0E-4)
		{
			vel = Vec3.ZERO;
		}else
		{
			double distToDest2 = playerPos.distanceTo(destCenter);
			double speed = Math.min(Math.min(maxSpeed, dist),
				Math.max(0.2, distToDest2 * 0.6));
			vel = delta.scale(speed / dist);
			double vCap = this.config.flightVerticalSpeed;
			if(vCap > 0.0 && Math.abs(vel.y) > vCap)
			{
				vel = vel.scale(vCap / Math.abs(vel.y));
			}
		}
		vel = this.applyLavaAvoidance(playerPos, vel, maxSpeed);
		double hazardBelow = this.lavaClearanceAhead(playerPos, vel);
		if(!Double.isNaN(hazardBelow) && hazardBelow < 4.0)
		{
			vel = this.liftClamped(vel, 0.45);
			double frac = Math.max(0.0, hazardBelow) / 4.0;
			double cap = 1.2 + frac * Math.max(0.0, maxSpeed - 1.2);
			double s = vel.length();
			if(s > cap && s > 1.0E-6)
			{
				vel = vel.scale(cap / s);
				vel = this.liftClamped(vel, Math.min(0.45, cap));
			}
		}
		vel = this.applyUnloadedChunkBarrier(playerPos, vel);
		if(this.serverFallEstimate > 2.0 && vel.y > -0.2 && vel.y < 0.08)
		{
			vel = new Vec3(vel.x, 0.08, vel.z);
		}
		double desiredSp = vel.length();
		if(!this.aimTight)
		{
			double sp = vel.length();
			if(sp > 1.0E-6)
			{
				double hit;
				double margin = this.governorMargin(sp);
				this.debugGovHit =
					hit = this.sweptCollisionDistance(vel, sp + margin);
				double allowed = Math.max(0.0, hit - margin);
				if(allowed < sp)
				{
					Vec3 scaled = vel.scale(allowed / sp);
					Vec3 slide = this.axisSlide(vel);
					vel =
						slide.lengthSqr() > scaled.lengthSqr() ? slide : scaled;
					this.debugGovScale = vel.length() / sp;
				}
			}
		}else
		{
			this.debugGovHit =
				this.sweptCollisionDistance(vel, vel.length() + 0.5);
			this.debugGovScale = 1.0;
		}
		double sp2 = vel.length();
		if(sp2 > 1.0E-6 && this.context != null)
		{
			double octAllowed;
			double probe = sp2 + this.governorMargin(sp2);
			Vec3 dir = vel.scale(1.0 / sp2);
			Vec3 feetVec = playerPos.add(0.0, 0.1, 0.0);
			double octHit = this.context.raytraceDistance(feetVec,
				feetVec.add(dir.scale(probe)));
			if(octHit != Double.POSITIVE_INFINITY && (octAllowed =
				Math.max(0.0, octHit - this.governorMargin(sp2))) < sp2)
			{
				vel = vel.scale(octAllowed / sp2);
			}
		}
		if(this.settleTicks > 0)
		{
			double spS = vel.length();
			if(spS > 0.1)
			{
				vel = vel.scale(0.1 / spS);
			}
		}else if(this.desyncLevel > 0)
		{
			double cap =
				Math.max(0.5, maxSpeed * Math.pow(0.5, this.desyncLevel));
			double spD = vel.length();
			if(spD > cap)
			{
				vel = vel.scale(cap / spD);
			}
		}
		if(!Double.isNaN(hazardBelow) && hazardBelow < 4.0)
		{
			vel = this.liftClamped(vel, 0.2);
		}
		if(!this.frontierHold && this.hardRecoveryTicks == 0
			&& this.settleTicks == 0 && desiredSp > 0.5
			&& vel.length() < 0.2 * desiredSp)
		{
			if(++this.governorBlockedTicks >= 10)
			{
				this.governorBlockedTicks = 0;
				if(!this.pathManager.isRecalculating())
				{
					this.pathManager.pathToDestination(this.feet());
				}
			}
		}else
		{
			this.governorBlockedTicks = 0;
		}
		this.lastCommandedVel = vel;
		this.player().setDeltaMovement(vel);
		this.player().resetFallDistance();
	}
	
	private Vec3 liftClamped(Vec3 vel, double lift)
	{
		double probe = lift + 0.3;
		double upClear =
			this.sweptCollisionDistance(new Vec3(0.0, probe, 0.0), probe);
		double allowed = Math.max(0.0, upClear - 0.3);
		return new Vec3(vel.x, Math.max(vel.y, Math.min(lift, allowed)), vel.z);
	}
	
	private double lavaClearanceAhead(Vec3 playerPos, Vec3 vel)
	{
		double best = this.lavaClearanceBelow(playerPos);
		for(int k = 1; k <= 2; ++k)
		{
			double c =
				this.lavaClearanceBelow(playerPos.add(vel.scale((double)k)));
			if(Double.isNaN(c) || !Double.isNaN(best) && !(c < best))
				continue;
			best = c;
		}
		return best;
	}
	
	private void faceToward(Vec3 playerPos, Vec3 target)
	{
		Vec3 eye =
			playerPos.add(0.0, (double)this.player().getEyeHeight(), 0.0);
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
		float pitch = (float)(-Math.toDegrees(Math.atan2(dy, horiz)));
		this.player().setYRot(yaw);
		this.player().setXRot(pitch);
	}
	
	private Vec3 applyUnloadedChunkBarrier(Vec3 playerPos, Vec3 vel)
	{
		double sp = vel.length();
		if(sp <= 1.0E-6 || this.level() == null)
		{
			return vel;
		}
		ClientChunkCache chunkSource = this.level().getChunkSource();
		Vec3 dir = vel.scale(1.0 / sp);
		double probe = sp + 6.0;
		for(double d = 2.0; d <= probe; d += 2.0)
		{
			Vec3 p = playerPos.add(dir.scale(d));
			if(chunkSource.getChunk((int)Math.floor(p.x) >> 4,
				(int)Math.floor(p.z) >> 4, false) != null)
				continue;
			double allowed = Math.max(0.0, d - 3.0);
			if(allowed < 0.15 && d > 1.5)
			{
				allowed = 0.15;
			}
			if(allowed < sp)
			{
				if(allowed < 0.2)
				{
					this.frontierHold = true;
				}
				return vel.scale(allowed / sp);
			}
			return vel;
		}
		return vel;
	}
	
	private Vec3 axisSlide(Vec3 vel)
	{
		double hit;
		double z;
		double y;
		double x =
			this.slideComponent(vel.x, new Vec3(Math.signum(vel.x), 0.0, 0.0));
		Vec3 slide = new Vec3(x,
			y = this.slideComponent(vel.y,
				new Vec3(0.0, Math.signum(vel.y), 0.0)),
			z = this.slideComponent(vel.z,
				new Vec3(0.0, 0.0, Math.signum(vel.z))));
		double len = slide.length();
		if(len > 1.0E-6
			&& (hit = this.sweptCollisionDistance(slide, len)) < len)
		{
			slide = slide.scale(hit / len);
		}
		return slide;
	}
	
	private double slideComponent(double v, Vec3 unit)
	{
		double mag = Math.abs(v);
		if(mag < 1.0E-6)
		{
			return 0.0;
		}
		double margin = this.governorMargin(mag);
		double hit = this.sweptCollisionDistance(unit.scale(mag), mag + margin);
		double allowed = Math.max(0.0, hit - margin);
		return Math.copySign(Math.min(mag, allowed), v);
	}
	
	private double governorMargin(double sp)
	{
		double base = Math.min(0.75, Math.max(0.2, sp * 0.75));
		return this.aimTight ? Math.min(base, 0.1) : base;
	}
	
	private double sweptCollisionDistance(Vec3 move, double probe)
	{
		double len = move.length();
		if(len < 1.0E-6)
		{
			return probe;
		}
		Vec3 dir = move.scale(1.0 / len);
		AABB box = this.player().getBoundingBox();
		double step = 0.25;
		for(double d = 0.25; d <= probe; d += 0.25)
		{
			Vec3 off = dir.scale(d);
			AABB moved = box.move(off.x, off.y, off.z);
			if(this.level().noBlockCollision((Entity)this.player(), moved)
				&& !this.boxIntersectsBurnHazard(moved.inflate(0.1)))
				continue;
			return d - 0.25;
		}
		return probe;
	}
	
	private boolean boxIntersectsBurnHazard(AABB box)
	{
		if(this.level() == null)
		{
			return false;
		}
		int minX = (int)Math.floor(box.minX);
		int maxX = (int)Math.floor(box.maxX);
		int minY = (int)Math.floor(box.minY);
		int maxY = (int)Math.floor(box.maxY);
		int minZ = (int)Math.floor(box.minZ);
		int maxZ = (int)Math.floor(box.maxZ);
		for(int x = minX; x <= maxX; ++x)
		{
			for(int y = minY; y <= maxY; ++y)
			{
				for(int z = minZ; z <= maxZ; ++z)
				{
					if(!FlightController.isBurnHazard(this.blockAt(x, y, z)))
						continue;
					return true;
				}
			}
		}
		return false;
	}
	
	private Vec3 applyLavaAvoidance(Vec3 playerPos, Vec3 vel, double maxSpeed)
	{
		if(this.level() == null)
		{
			return vel;
		}
		Vec3 center =
			playerPos.add(0.0, (double)this.player().getBbHeight() * 0.5, 0.0);
		Vec3 segEnd = center.add(vel);
		int r = (int)Math.ceil(3.5);
		int minX = (int)Math.floor(Math.min(center.x, segEnd.x)) - r;
		int maxX = (int)Math.floor(Math.max(center.x, segEnd.x)) + r;
		int minY = (int)Math.floor(Math.min(center.y, segEnd.y)) - r;
		int maxY = (int)Math.floor(Math.max(center.y, segEnd.y)) + r;
		int minZ = (int)Math.floor(Math.min(center.z, segEnd.z)) - r;
		int maxZ = (int)Math.floor(Math.max(center.z, segEnd.z)) + r;
		Vec3 away = Vec3.ZERO;
		double nearest = Double.MAX_VALUE;
		for(int x = minX; x <= maxX; ++x)
		{
			for(int y = minY; y <= maxY; ++y)
			{
				for(int z = minZ; z <= maxZ; ++z)
				{
					Vec3 cell;
					double d;
					if(!FlightController.isBurnHazard(this.blockAt(x, y, z))
						|| (d =
							FlightController.distToSegment(
								cell = new Vec3((double)x + 0.5,
									(double)y + 0.5, (double)z + 0.5),
								center, segEnd)) > 3.5
						|| d < 1.0E-4)
						continue;
					nearest = Math.min(nearest, d);
					Vec3 diff = center.subtract(cell);
					double dp = Math.max(diff.length(), 0.5);
					away = away.add(diff.scale(1.0 / (dp * dp * dp)));
				}
			}
		}
		if(nearest == Double.MAX_VALUE)
		{
			return vel;
		}
		if(away.lengthSqr() > 1.0E-6)
		{
			vel = vel.add(away.normalize().scale(0.6));
		}
		double frac = Math.max(0.0, (nearest - 1.0) / 2.5);
		double cap = 1.2 + frac * (maxSpeed - 1.2);
		double s = vel.length();
		if(s > cap && s > 1.0E-6)
		{
			vel = vel.scale(cap / s);
		}
		return vel;
	}
	
	private static double distToSegment(Vec3 p, Vec3 a, Vec3 b)
	{
		Vec3 ab = b.subtract(a);
		double lenSq = ab.lengthSqr();
		if(lenSq < 1.0E-9)
		{
			return p.distanceTo(a);
		}
		double t = Math.max(0.0, Math.min(1.0, p.subtract(a).dot(ab) / lenSq));
		return p.distanceTo(a.add(ab.scale(t)));
	}
	
	private double lavaClearanceBelow(Vec3 playerPos)
	{
		double[][] offs;
		if(this.level() == null)
		{
			return Double.NaN;
		}
		double best = Double.NaN;
		block0: for(double[] o : offs = new double[][]{{0.0, 0.0}, {0.3, 0.3},
			{0.3, -0.3}, {-0.3, 0.3}, {-0.3, -0.3}})
		{
			int feetY;
			int x = (int)Math.floor(playerPos.x + o[0]);
			int z = (int)Math.floor(playerPos.z + o[1]);
			for(int y = feetY = (int)Math.floor(playerPos.y); y >= feetY
				- 8; --y)
			{
				if(!FlightController.isBurnHazard(this.blockAt(x, y, z)))
					continue;
				double clear = playerPos.y - ((double)y + 0.889);
				if(!Double.isNaN(best) && !(clear < best))
					continue block0;
				best = clear;
				continue block0;
			}
		}
		return best;
	}
	
	public void noteServerCorrection()
	{
		if(this.destination != null)
		{
			this.settleTicks = 3;
			if(this.ticksSinceServerCorrection < 100)
			{
				this.desyncLevel = Math.min(3, this.desyncLevel + 1);
				this.desyncCautionTicks = 40;
				this.adaptiveCeiling =
					Math.max(1.0, Math.min(this.adaptiveCeiling,
						this.config.flightHorizontalSpeed) * 0.5);
				if(this.config.flightDebug)
				{
					this.log(String.format(Locale.ROOT,
						"[AutoFly] repeated server corrections - desync caution level %d, speed ceiling %.2f",
						this.desyncLevel, this.adaptiveCeiling));
				}
			}
		}
		this.ticksSinceServerCorrection = 0;
	}
	
	private void trackProgress(Vec3 playerPos)
	{
		if(this.frontierHold && this.frontierHoldTicks < 300)
		{
			this.ticksSinceProgress = 0;
			this.lastPos = playerPos;
			return;
		}
		if(this.lastPos != null)
		{
			double moved = this.lastPos.distanceTo(playerPos);
			this.ticksSinceProgress =
				moved < Math.max(0.05, 0.3 * this.prevCmdSpeed)
					? ++this.ticksSinceProgress : 0;
		}
		this.lastPos = playerPos;
	}
	
	public boolean clearView(Vec3 start, Vec3 dest)
	{
		if(start.equals((Object)dest))
		{
			return true;
		}
		return this.level()
			.clip(new ClipContext(start, dest, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.ANY, (Entity)this.player()))
			.getType() == HitResult.Type.MISS;
	}
	
	private boolean clearViewForPlayer(Vec3 from, Vec3 to)
	{
		double[][] offsets;
		double halfWidth = (double)this.player().getBbWidth() / 2.0;
		double height = this.player().getBbHeight();
		for(double[] o : offsets = new double[][]{{0.0, 0.0},
			{halfWidth, halfWidth}, {halfWidth, -halfWidth},
			{-halfWidth, halfWidth}, {-halfWidth, -halfWidth}})
		{
			if(!this.clearView(from.add(o[0], 0.0, o[1]),
				to.add(o[0], 0.0, o[1])))
			{
				return false;
			}
			if(this.clearView(from.add(o[0], height, o[1]),
				to.add(o[0], height, o[1])))
				continue;
			return false;
		}
		return true;
	}
	
	private boolean passable(int x, int y, int z)
	{
		return this.level() == null || this.blockAt(x, y, z).isAir();
	}
	
	private void flightDebugTick(Vec3 playerPos, Vec3 aim)
	{
		boolean stuck;
		LocalPlayer p = this.player();
		boolean horiz = p.horizontalCollision;
		boolean vert = p.verticalCollision;
		boolean minor = p.minorHorizontalCollision;
		boolean inLava = p.isInLava();
		boolean aimClear =
			aim != null && this.clearViewForPlayer(playerPos, aim);
		double advanced = this.debugPrevPos == null ? 0.0
			: playerPos.distanceTo(this.debugPrevPos);
		this.debugPrevPos = playerPos;
		double cmdSpeed = this.lastCommandedVel.length();
		boolean stuckShort = this.ticksSinceProgress > 6 && cmdSpeed > 0.3;
		boolean goalStalled = this.ticksSinceGoalProgress > 20;
		boolean problem =
			horiz || vert || minor || inLava || stuckShort || goalStalled;
		this.debugTrail.addLast(String.format(Locale.ROOT,
			"pos=%.1f,%.1f,%.1f vel=%.2f,%.2f,%.2f near=%d aimClr=%s lavaClr=%.2f",
			playerPos.x, playerPos.y, playerPos.z, this.lastCommandedVel.x,
			this.lastCommandedVel.y, this.lastCommandedVel.z,
			this.pathManager.getNear(), aimClear,
			this.lavaClearanceBelow(playerPos)));
		while(this.debugTrail.size() > 12)
		{
			this.debugTrail.removeFirst();
		}
		if(!problem)
		{
			this.debugProblemActive = false;
			return;
		}
		if(!inLava && this.debugProblemActive && --this.debugRepeatSuppress > 0)
		{
			return;
		}
		this.debugProblemActive = true;
		this.debugRepeatSuppress = 10;
		boolean bl = stuck =
			stuckShort || goalStalled || advanced < 0.15 && cmdSpeed > 0.5;
		String label = inLava ? "LAVA"
			: (goalStalled ? "OSCILLATION" : (stuck ? "STUCK" : "graze"));
		this.log(String.format(Locale.ROOT,
			"[AutoFly] %s horiz=%s vert=%s minor=%s inLava=%s advanced=%.2f goalStall=%d",
			label, horiz, vert, minor, inLava, advanced,
			this.ticksSinceGoalProgress));
		this.log(String.format(Locale.ROOT,
			"  pos=%.1f,%.1f,%.1f aim=%s near=%d aimClr=%s govHit=%.2f govScale=%.2f cmd=%.2f",
			playerPos.x, playerPos.y, playerPos.z,
			aim == null ? "none"
				: String.format(Locale.ROOT, "%.1f,%.1f,%.1f", aim.x, aim.y,
					aim.z),
			this.pathManager.getNear(), aimClear, this.debugGovHit,
			this.debugGovScale, cmdSpeed));
		BlockPos f = this.feet();
		this.log("  feet=" + this.classifyAt(f) + " head="
			+ this.classifyAt(f.above()) + " above2="
			+ this.classifyAt(f.above(2)));
		if(stuck || inLava)
		{
			List<BetterBlockPos> path = this.pathManager.getPath();
			this.log("  pathSize=" + path.size() + " complete="
				+ this.pathManager.isComplete() + " recalc="
				+ this.pathManager.isRecalculating() + " hardRecovery="
				+ this.hardRecoveryTicks);
			this.log("  trail (oldest first):");
			for(String s : this.debugTrail)
			{
				this.log("   " + s);
			}
		}
	}
	
	private String classifyAt(BlockPos pos)
	{
		if(this.level() == null)
		{
			return "?";
		}
		BlockState s = this.blockAt(pos.getX(), pos.getY(), pos.getZ());
		if(s.is(Blocks.LAVA))
		{
			return "LAVA";
		}
		if(s.is(Blocks.WATER))
		{
			return "water";
		}
		if(!s.getFluidState().isEmpty())
		{
			return "fluid";
		}
		if(s.isAir())
		{
			return "air";
		}
		return BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
	}
	
	public final class PathManager
	{
		private List<BetterBlockPos> path;
		private boolean completePath;
		private boolean recalculating;
		private int pathFailures;
		private int maxPlayerNear;
		private int ticksNearUnchanged;
		private int playerNear;
		final /* synthetic */ FlightController this$0;
		
		public PathManager(FlightController this$0)
		{
			FlightController flightController = this$0;
			Objects.requireNonNull(flightController);
			this.this$0 = flightController;
			this.path = new ArrayList<BetterBlockPos>();
			this.clear();
		}
		
		public void tick()
		{
			this.updatePlayerNear();
			int prevMaxNear = this.maxPlayerNear;
			this.maxPlayerNear = Math.max(this.maxPlayerNear, this.playerNear);
			this.ticksNearUnchanged = this.maxPlayerNear == prevMaxNear
				? ++this.ticksNearUnchanged : 0;
			this.pathfindAroundObstacles();
			this.attemptNextSegment();
		}
		
		public CompletableFuture<Void> pathToDestination(BlockPos from)
		{
			if(this.this$0.destination == null || this.this$0.context == null)
			{
				return CompletableFuture.completedFuture(null);
			}
			this.recalculating = true;
			return this
				.path0(from, this.this$0.destination, UnaryOperator.identity())
				.whenComplete((result, ex) -> {
					this.recalculating = false;
					if(ex != null)
					{
						this.logHandledException((Throwable)ex);
						this.this$0.mc.execute(this::onPathFailure);
					}else
					{
						this.pathFailures = 0;
					}
				});
		}
		
		private void onPathFailure()
		{
			if(++this.pathFailures >= 3)
			{
				this.pathFailures = 0;
				this.path = new ArrayList<BetterBlockPos>();
				this.completePath = true;
				this.playerNear = 0;
				this.ticksNearUnchanged = 0;
				this.maxPlayerNear = 0;
			}
		}
		
		private CompletableFuture<Void> pathRecalcSegment(OptionalInt upToIncl)
		{
			if(this.recalculating)
			{
				return CompletableFuture.completedFuture(null);
			}
			this.recalculating = true;
			List<BetterBlockPos> after = upToIncl.isPresent()
				? new ArrayList<BetterBlockPos>(this.path
					.subList(upToIncl.getAsInt() + 1, this.path.size()))
				: Collections.emptyList();
			boolean complete = this.completePath;
			BetterBlockPos segDest = upToIncl.isPresent()
				? this.path.get(upToIncl.getAsInt()) : this.this$0.destination;
			return this
				.path0(this.this$0.feet(), segDest,
					segment -> segment.isFinished() || !upToIncl.isPresent()
						? segment.append(after.stream(),
							complete || segment.isFinished()
								&& !upToIncl.isPresent())
						: segment)
				.whenComplete((result, ex) -> {
					this.recalculating = false;
					if(ex != null)
					{
						this.logHandledException((Throwable)ex);
						this.this$0.mc.execute(this::onPathFailure);
					}else
					{
						this.pathFailures = 0;
					}
				});
		}
		
		private void pathNextSegment(int afterIncl)
		{
			if(this.recalculating)
			{
				return;
			}
			this.recalculating = true;
			ArrayList<BetterBlockPos> before = new ArrayList<BetterBlockPos>(
				this.path.subList(0, afterIncl + 1));
			BetterBlockPos pathStart = this.path.get(afterIncl);
			this.path0(pathStart, this.this$0.destination,
				segment -> segment.prepend(before.stream()))
				.whenComplete((result, ex) -> {
					this.recalculating = false;
					if(ex != null)
					{
						if(this.this$0.player().distanceToSqr(
							Vec3.atCenterOf((Vec3i)pathStart)) < 256.0)
						{
							this.completePath = true;
						}
						this.logHandledException((Throwable)ex);
					}
				});
		}
		
		public void clear()
		{
			this.path = new ArrayList<BetterBlockPos>();
			this.completePath = true;
			this.recalculating = false;
			this.playerNear = 0;
			this.ticksNearUnchanged = 0;
			this.maxPlayerNear = 0;
		}
		
		private void setPath(UnpackedSegment segment)
		{
			this.path = segment.collect();
			this.completePath = segment.isFinished();
			this.playerNear = 0;
			this.ticksNearUnchanged = 0;
			this.maxPlayerNear = 0;
			this.this$0.bestArcPos = 0.0;
			this.this$0.currentArcPos = 0.0;
		}
		
		public List<BetterBlockPos> getPath()
		{
			return this.path;
		}
		
		public int getNear()
		{
			return this.playerNear;
		}
		
		public boolean isComplete()
		{
			return this.completePath;
		}
		
		public boolean isRecalculating()
		{
			return this.recalculating;
		}
		
		private CompletableFuture<Void> path0(BlockPos src, BlockPos dst,
			UnaryOperator<UnpackedSegment> operator)
		{
			FlightPathfinder context = this.this$0.context;
			if(context == null)
			{
				return CompletableFuture.completedFuture(null);
			}
			return context.pathFindAsync(src, dst).thenApply(operator)
				.thenAcceptAsync(this::setPath, this.this$0.mc::execute);
		}
		
		private void pathfindAroundObstacles()
		{
			int rangeEndExcl;
			if(this.recalculating || this.path.isEmpty())
			{
				return;
			}
			FlightPathfinder context = this.this$0.context;
			if(context == null)
			{
				return;
			}
			int rangeStartIncl = this.playerNear;
			for(rangeEndExcl = this.playerNear; rangeEndExcl < this.path.size()
				&& context.hasChunk(ChunkPos.containing(
					(BlockPos)this.path.get(rangeEndExcl))); ++rangeEndExcl)
			{}
			if(rangeStartIncl >= rangeEndExcl)
			{
				return;
			}
			BetterBlockPos rangeStart = this.path.get(rangeStartIncl);
			if(!this.this$0.passable(rangeStart.x, rangeStart.y, rangeStart.z))
			{
				return;
			}
			if(this.ticksNearUnchanged > 100)
			{
				this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1));
				this.ticksNearUnchanged = 0;
				return;
			}
			boolean canSeeAny = false;
			for(int i = rangeStartIncl; i < rangeEndExcl - 1; ++i)
			{
				Vec3 a = Vec3.atCenterOf((Vec3i)((Vec3i)this.path.get(i)));
				Vec3 b = Vec3.atCenterOf((Vec3i)((Vec3i)this.path.get(i + 1)));
				if(this.this$0.clearView(this.this$0.feetVec(), a)
					|| this.this$0.clearView(this.this$0.head(), a))
				{
					canSeeAny = true;
				}
				if(this.this$0.clearView(a, b)
					&& this.this$0.clearView(a.add(0.0, 2.0, 0.0),
						b.add(0.0, 2.0, 0.0))
					&& context.pathSegmentSafe(this.path.get(i),
						this.path.get(i + 1)))
					continue;
				OptionalInt rejoinAt = this.path.get(rangeEndExcl - 1)
					.distanceSq(this.this$0.destination) < this.path
						.get(rangeStartIncl).distanceSq(this.this$0.destination)
							? OptionalInt.of(rangeEndExcl - 1)
							: OptionalInt.empty();
				this.pathRecalcSegment(rejoinAt);
				return;
			}
			if(!canSeeAny && rangeStartIncl < rangeEndExcl - 2)
			{
				this.pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1));
			}
		}
		
		private void attemptNextSegment()
		{
			boolean predictedReach;
			if(this.recalculating || this.path.isEmpty() || this.completePath)
			{
				return;
			}
			int last = this.path.size() - 1;
			FlightPathfinder context = this.this$0.context;
			boolean bl = predictedReach = context != null
				&& context.isPredictTerrain()
				&& this.path.get(last).distanceSq(this.this$0.feet()) < 65536.0;
			if(this.this$0.level().isLoaded((BlockPos)this.path.get(last))
				|| predictedReach)
			{
				this.pathNextSegment(last);
			}
		}
		
		public void updatePlayerNear()
		{
			int i;
			if(this.path.isEmpty())
			{
				return;
			}
			int index = this.playerNear;
			BlockPos pos = this.this$0.feet();
			for(i = index; i >= Math.max(index - 1000, 0); i -= 10)
			{
				if(!(this.path.get(i).distanceSq(pos) < this.path.get(index)
					.distanceSq(pos)))
					continue;
				index = i;
			}
			for(i = index; i < Math.min(index + 1000, this.path.size()); i +=
				10)
			{
				if(!(this.path.get(i).distanceSq(pos) < this.path.get(index)
					.distanceSq(pos)))
					continue;
				index = i;
			}
			for(i = index; i >= Math.max(index - 50, 0); --i)
			{
				if(!(this.path.get(i).distanceSq(pos) < this.path.get(index)
					.distanceSq(pos)))
					continue;
				index = i;
			}
			for(i = index; i < Math.min(index + 50, this.path.size()); ++i)
			{
				if(!(this.path.get(i).distanceSq(pos) < this.path.get(index)
					.distanceSq(pos)))
					continue;
				index = i;
			}
			this.playerNear = index;
		}
		
		private void logHandledException(Throwable ex)
		{
			Throwable cause;
			Throwable throwable = cause =
				ex instanceof CompletionException && ex.getCause() != null
					? ex.getCause() : ex;
			if(!(cause instanceof PathCalculationException))
			{
				cause.printStackTrace();
			}
		}
	}
}
