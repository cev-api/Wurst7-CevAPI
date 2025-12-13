/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.util.Timer;
import java.util.TimerTask;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Font.DisplayMode;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerConfig;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerStateData;
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.CameraTransformViewBobbingListener.CameraTransformViewBobbingEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixin.TrialSpawnerDataAccessor;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;
import net.wurstclient.nicewurst.NiceWurstModule;
import net.wurstclient.util.chunk.ChunkUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;

public final class TrialSpawnerEspHack extends Hack
	implements UpdateListener, CameraTransformViewBobbingListener,
	RenderListener, net.wurstclient.events.RightClickListener
{
	private final SliderSetting maxDistance =
		new SliderSetting("Max distance", 160, 0, 256, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting drawTracers =
		new CheckboxSetting("Show tracer", true);
	private final CheckboxSetting fillShapes =
		new CheckboxSetting("Fill boxes", true);
	private final CheckboxSetting showOverlay =
		new CheckboxSetting("Text overlay", true);
	private final SliderSetting overlayScale = new SliderSetting(
		"Overlay scale", 1.0, 0.5, 2.0, 0.05, ValueDisplay.DECIMAL);
	private final CheckboxSetting showMobType =
		new CheckboxSetting("Show mob type", true);
	private final CheckboxSetting showStatus =
		new CheckboxSetting("Show status", true);
	// removed wave info setting (simplified status display)
	private final CheckboxSetting showNextSpawn =
		new CheckboxSetting("Show next wave", true);
	private final CheckboxSetting showCooldown =
		new CheckboxSetting("Show cooldown", true);
	private final CheckboxSetting markOnApproach =
		new CheckboxSetting("Mark on approach", false);
	private final SliderSetting approachRange =
		new SliderSetting("Approach range", 4, 1, 16, 1, ValueDisplay.INTEGER);
	// spawn tracers removed
	private final ColorSetting vaultBoxColor =
		new ColorSetting("Vault box color", new Color(0xFF7CF2C9));
	private final ColorSetting ominousVaultBoxColor =
		new ColorSetting("Ominous vault color", new Color(0xFF9B59B6));
	private final CheckboxSetting showDistance =
		new CheckboxSetting("Show distance", true);
	private final CheckboxSetting showTrialType =
		new CheckboxSetting("Show trial type", true);
	private final CheckboxSetting showActivationRadius =
		new CheckboxSetting("Show activation radius", true);
	private final CheckboxSetting showVaultLink =
		new CheckboxSetting("Show vault link", true);
	private final SliderSetting vaultLinkRange = new SliderSetting(
		"Vault link range", 48, 8, 96, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting showCountInHackList =
		new CheckboxSetting("HackList count", false);
	
	private final ColorSetting colorIdle =
		new ColorSetting("Idle color", new Color(0xFFAAAAAA));
	private final ColorSetting colorCharging =
		new ColorSetting("Charging color", new Color(0xFFF4C542));
	private final ColorSetting colorActive =
		new ColorSetting("Active color", new Color(0xFFE5534B));
	private final ColorSetting colorCompleted =
		new ColorSetting("Completed color", new Color(0xFF51C271));
	private final ColorSetting radiusColor =
		new ColorSetting("Radius color", new Color(0xFF66B3FF));
	private final ColorSetting vaultLinkColor =
		new ColorSetting("Vault link color", new Color(0xFF7CF2C9));
	
	private final ArrayList<TrialSpawnerInfo> spawners = new ArrayList<>();
	private final ArrayList<VaultInfo> vaults = new ArrayList<>();
	private final HashSet<String> openedVaultKeys = new HashSet<>();
	private boolean openedVaultsLoaded = false;
	private final java.util.Set<String> approachScheduledKeys =
		new java.util.HashSet<>();
	private final Timer approachTimer = new Timer(true);
	private static final Gson GSON =
		new GsonBuilder().setPrettyPrinting().create();
	// predicted cooldown end ticks for spawners when we observe the transition
	private final Map<BlockPos, Long> predictedCooldownEnds = new HashMap<>();
	// previous known states to detect transitions
	private final Map<BlockPos, TrialSpawnerState> prevStates = new HashMap<>();
	private int foundCount;
	private final CheckboxSetting estimateCooldownOnTransition =
		new CheckboxSetting("Estimate cooldown when observed", true);
	
	public TrialSpawnerEspHack()
	{
		super("TrialSpawnerESP");
		setCategory(Category.RENDER);
		addSetting(maxDistance);
		addSetting(drawTracers);
		addSetting(fillShapes);
		addSetting(showOverlay);
		addSetting(overlayScale);
		addSetting(showMobType);
		addSetting(showStatus);
		addSetting(showNextSpawn);
		addSetting(showCooldown);
		addSetting(markOnApproach);
		addSetting(approachRange);
		// spawn tracers removed
		addSetting(estimateCooldownOnTransition);
		addSetting(showDistance);
		addSetting(showTrialType);
		addSetting(showActivationRadius);
		addSetting(showVaultLink);
		addSetting(vaultLinkRange);
		addSetting(vaultBoxColor);
		addSetting(ominousVaultBoxColor);
		addSetting(showCountInHackList);
		addSetting(colorIdle);
		addSetting(colorCharging);
		addSetting(colorActive);
		addSetting(colorCompleted);
		addSetting(radiusColor);
		addSetting(vaultLinkColor);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(net.wurstclient.events.RightClickListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(net.wurstclient.events.RightClickListener.class, this);
		spawners.clear();
		vaults.clear();
		foundCount = 0;
		// reset opened vaults load state so they are reloaded on next world
		openedVaultsLoaded = false;
		openedVaultKeys.clear();
	}
	
	@Override
	public void onUpdate()
	{
		spawners.clear();
		if(MC.level == null || MC.player == null)
		{
			vaults.clear();
			foundCount = 0;
			return;
		}
		
		// load opened vaults for current server once
		if(!openedVaultsLoaded)
		{
			loadOpenedVaults();
			openedVaultsLoaded = true;
		}
		
		vaults.clear();
		ChunkUtils.getLoadedBlockEntities()
			.filter(be -> be instanceof VaultBlockEntity)
			.map(be -> (VaultBlockEntity)be).forEach(be -> {
				BlockPos vpos = be.getBlockPos().immutable();
				vaults.add(new VaultInfo(vpos));
				// detect ominous vaults that have been opened / ejected
				if(MC.level != null)
				{
					BlockState state = MC.level.getBlockState(vpos);
					if(state.is(Blocks.VAULT)
						&& state.hasProperty(VaultBlock.OMINOUS)
						&& state.getValue(VaultBlock.OMINOUS))
					{
						if(state.hasProperty(VaultBlock.STATE))
						{
							VaultState vs = state.getValue(VaultBlock.STATE);
							// treat any non-INACTIVE /
							// ejecting/unlocking/active as opened
							if(vs == VaultState.EJECTING)
							{
								String dim = "unknown";
								try
								{
									if(MC != null && MC.level != null)
										dim = MC.level.dimension().identifier()
											.toString();
								}catch(Throwable ignored)
								{}
								String key = dim + "|" + vpos.getX() + ","
									+ vpos.getY() + "," + vpos.getZ();
								if(openedVaultKeys.add(key))
									saveOpenedVaults();
							}
						}
					}
				}
			});
		
		boolean limit = maxDistance.getValue() > 0;
		double maxDistanceSq = maxDistance.getValue() * maxDistance.getValue();
		
		// schedule approach-based checks for ominous vaults
		if(markOnApproach.isChecked())
		{
			double range = approachRange.getValue();
			double rangeSq = range * range;
			for(VaultInfo v : vaults)
			{
				BlockPos vpos = v.pos();
				String dim = "unknown";
				try
				{
					if(MC != null && MC.level != null)
						dim = MC.level.dimension().identifier().toString();
				}catch(Throwable ignored)
				{}
				String key = dim + "|" + vpos.getX() + "," + vpos.getY() + ","
					+ vpos.getZ();
				if(openedVaultKeys.contains(key)
					|| approachScheduledKeys.contains(key))
					continue;
				if(MC.player.distanceToSqr(vpos.getX() + 0.5, vpos.getY() + 0.5,
					vpos.getZ() + 0.5) <= rangeSq)
				{
					BlockState state = MC.level.getBlockState(vpos);
					VaultState prev = state.hasProperty(VaultBlock.STATE)
						? state.getValue(VaultBlock.STATE) : null;
					// make final copies for inner class
					final VaultState prevFinal = prev;
					final String keyFinal = key;
					final BlockPos vposFinal = vpos;
					approachScheduledKeys.add(keyFinal);
					approachTimer.schedule(new TimerTask()
					{
						@Override
						public void run()
						{
							try
							{
								if(MC == null || MC.level == null)
									return;
								BlockState after =
									MC.level.getBlockState(vposFinal);
								VaultState afterState =
									after.hasProperty(VaultBlock.STATE)
										? after.getValue(VaultBlock.STATE)
										: null;
								boolean prevIdle = prevFinal == null
									|| prevFinal == VaultState.INACTIVE;
								if(prevIdle && ((afterState == null
									&& prevFinal == null)
									|| (afterState == prevFinal)))
								{
									// unchanged and was idle -> assume already
									// opened/locked
									if(openedVaultKeys.add(keyFinal))
										saveOpenedVaults();
								}
							}catch(Throwable ignored)
							{}finally
							{
								approachScheduledKeys.remove(keyFinal);
							}
						}
					}, 1500L);
				}
			}
		}
		
		ChunkUtils.getLoadedBlockEntities()
			.filter(be -> be instanceof TrialSpawnerBlockEntity)
			.map(be -> (TrialSpawnerBlockEntity)be).forEach(spawner -> {
				BlockPos pos = spawner.getBlockPos();
				double distSq = MC.player.distanceToSqr(pos.getX() + 0.5,
					pos.getY() + 0.5, pos.getZ() + 0.5);
				if(limit && distSq > maxDistanceSq)
					return;
				
				BlockPos immutablePos = pos.immutable();
				VaultInfo link = showVaultLink.isChecked()
					? findLinkedVault(immutablePos) : null;
				String decorMob = detectMobFromDecor(immutablePos);
				spawners.add(new TrialSpawnerInfo(spawner, immutablePos, distSq,
					link, decorMob));
			});
		
		foundCount = spawners.size();
		// detect transitions and predict cooldown end when configured
		if(MC.level != null && estimateCooldownOnTransition.isChecked())
		{
			long worldTime = MC.level.getGameTime();
			for(TrialSpawnerInfo info : spawners)
			{
				var be = info.blockEntity();
				if(be == null || be.isRemoved() || be.getLevel() != MC.level)
					continue;
				TrialSpawner logic = be.getTrialSpawner();
				if(logic == null)
					continue;
				TrialSpawnerState state = logic.getState() == null
					? TrialSpawnerState.INACTIVE : logic.getState();
				TrialSpawnerStateData data = logic.getStateData();
				long cooldownEnd = 0;
				if(data != null)
				{
					TrialSpawnerDataAccessor acc =
						(TrialSpawnerDataAccessor)data;
					cooldownEnd = acc.getCooldownEnd();
				}
				BlockPos pos = info.pos();
				TrialSpawnerState prev = prevStates.get(pos);
				// if we observed it go from ACTIVE to non-ACTIVE and there's no
				// server cooldown
				if(prev == TrialSpawnerState.ACTIVE
					&& state != TrialSpawnerState.ACTIVE
					&& cooldownEnd <= worldTime)
				{
					long predicted =
						worldTime + logic.getTargetCooldownLength();
					predictedCooldownEnds.put(pos, predicted);
				}
				// if server set a cooldown, prefer that and remove any
				// prediction
				if(cooldownEnd > worldTime)
					predictedCooldownEnds.remove(pos);
				// update previous state
				prevStates.put(pos, state);
			}
			// cleanup expired predictions
			predictedCooldownEnds.entrySet()
				.removeIf(e -> e.getValue() <= MC.level.getGameTime());
		}
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(drawTracers.isChecked() || showOverlay.isChecked())
			event.cancel();
	}
	
	@Override
	public void onRightClick(
		net.wurstclient.events.RightClickListener.RightClickEvent event)
	{
		if(MC == null || MC.level == null || MC.player == null)
			return;
		var hit = MC.hitResult;
		if(!(hit instanceof net.minecraft.world.phys.BlockHitResult bhr))
			return;
		BlockPos pos = bhr.getBlockPos();
		BlockState state = MC.level.getBlockState(pos);
		if(!state.is(Blocks.VAULT))
			return;
		// remember initial state and schedule a check after 1.5s
		VaultState before = state.hasProperty(VaultBlock.STATE)
			? state.getValue(VaultBlock.STATE) : null;
		String dim = "unknown";
		try
		{
			if(MC.level != null)
				dim = MC.level.dimension().identifier().toString();
		}catch(Throwable ignored)
		{}
		// make final copies for inner class
		final VaultState beforeFinal = before;
		final String dimFinal = dim;
		final BlockPos posFinal = pos;
		TimerTask task = new TimerTask()
		{
			@Override
			public void run()
			{
				try
				{
					if(MC == null || MC.level == null)
						return;
					BlockState after = MC.level.getBlockState(posFinal);
					VaultState afterState = after.hasProperty(VaultBlock.STATE)
						? after.getValue(VaultBlock.STATE) : null;
					// Only mark as opened if the vault was idle/inactive when
					// we checked and the state stayed unchanged. If it was
					// ACTIVE (mouth opened) or transitioned to ACTIVE/EJECTING,
					// do not mark.
					boolean beforeIdle = beforeFinal == null
						|| beforeFinal == VaultState.INACTIVE;
					if(beforeIdle
						&& ((afterState == null && beforeFinal == null)
							|| (afterState == beforeFinal)))
					{
						String key = dimFinal + "|" + posFinal.getX() + ","
							+ posFinal.getY() + "," + posFinal.getZ();
						if(openedVaultKeys.add(key))
							saveOpenedVaults();
					}
				}catch(Throwable ignored)
				{}
			}
		};
		new Timer(true).schedule(task, 1500);
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(MC.level == null)
			return;
		
		ArrayList<ColoredBox> outlineBoxes = new ArrayList<>();
		ArrayList<ColoredBox> filledBoxes =
			fillShapes.isChecked() ? new ArrayList<>() : null;
		ArrayList<ColoredPoint> tracerTargets =
			drawTracers.isChecked() ? new ArrayList<>() : null;
		ArrayList<OverlayEntry> overlays = new ArrayList<>();
		
		// Render vault ESP boxes and simple status labels while hack is enabled
		if(!vaults.isEmpty())
		{
			for(VaultInfo v : vaults)
			{
				BlockPos vpos = v.pos();
				BlockState vstate = MC.level.getBlockState(vpos);
				int vcolor = vaultBoxColor.getColorI();
				boolean ominous = vstate.hasProperty(VaultBlock.OMINOUS)
					&& vstate.getValue(VaultBlock.OMINOUS);
				if(ominous)
					vcolor = ominousVaultBoxColor.getColorI();
				// if we know this ominous vault was opened before, mark it
				// differently
				String dim = "unknown";
				try
				{
					if(MC != null && MC.level != null)
						dim = MC.level.dimension().identifier().toString();
				}catch(Throwable ignored)
				{}
				String key = dim + "|" + vpos.getX() + "," + vpos.getY() + ","
					+ vpos.getZ();
				if(ominous && openedVaultKeys.contains(key))
				{
					// darken color to indicate opened
					vcolor = mixWithWhite(vcolor, 0.6F);
				}
				AABB vbox = new AABB(vpos);
				outlineBoxes.add(new ColoredBox(vbox, vcolor));
				if(filledBoxes != null)
					filledBoxes
						.add(new ColoredBox(vbox, withAlpha(vcolor, 0.18F)));
				
				// show simple status label above the vault
				String status = describeVaultState(vstate);
				ArrayList<OverlayLine> lines = new ArrayList<>();
				lines.add(new OverlayLine("Vault", vcolor));
				lines.add(new OverlayLine(status, 0xFFFFFFFF));
				if(ominous && openedVaultKeys.contains(key))
					lines.add(new OverlayLine("Opened", 0xFFDD4444));
				Vec3 original = Vec3.atCenterOf(vpos).add(0, 1.0, 0);
				Vec3 resolved = resolveLabelPosition(original);
				overlays.add(new OverlayEntry(original, resolved, lines,
					overlayScale.getValueF()));
			}
		}
		
		for(TrialSpawnerInfo info : spawners)
		{
			TrialSpawnerBlockEntity be = info.blockEntity();
			if(be == null || be.isRemoved() || be.getLevel() != MC.level)
				continue;
			
			TrialSpawner logic = be.getTrialSpawner();
			if(logic == null)
				continue;
			
			TrialSpawnerState state = logic.getState() == null
				? TrialSpawnerState.INACTIVE : logic.getState();
			TrialStatus status = TrialStatus.fromState(state);
			int color = getColorForStatus(status);
			AABB box = new AABB(info.pos());
			outlineBoxes.add(new ColoredBox(box, color));
			if(filledBoxes != null)
				filledBoxes.add(new ColoredBox(box, withAlpha(color, 0.18F)));
			if(tracerTargets != null)
				tracerTargets
					.add(new ColoredPoint(Vec3.atCenterOf(info.pos()), color));
			
			if(showActivationRadius.isChecked())
				drawActivationRadius(matrices, info, logic, color);
			
			if(showVaultLink.isChecked() && info.vault() != null)
				drawVaultLink(matrices, info, color, overlays);
			
			// spawn tracers removed
			
			if(showOverlay.isChecked())
				drawOverlay(matrices, info, logic, state, color, overlays);
		}
		
		if(filledBoxes != null && !filledBoxes.isEmpty())
			RenderUtils.drawSolidBoxes(matrices, filledBoxes, false);
		if(!outlineBoxes.isEmpty())
			RenderUtils.drawOutlinedBoxes(matrices, outlineBoxes, false);
		if(tracerTargets != null && !tracerTargets.isEmpty())
			RenderUtils.drawTracers(matrices, partialTicks, tracerTargets,
				false);
		
		// draw overlays after all ESP geometry so labels are not overwritten
		java.util.HashSet<BlockPos> drawn = new java.util.HashSet<>();
		for(OverlayEntry e : overlays)
		{
			if(!NiceWurstModule.shouldRenderTarget(e.original()))
				continue;
			BlockPos key = BlockPos.containing(e.original());
			if(drawn.contains(key))
				continue;
			drawn.add(key);
			drawLabel(matrices, e.resolved(), e.lines(), e.scale());
		}
	}
	
	private void drawActivationRadius(PoseStack matrices, TrialSpawnerInfo info,
		TrialSpawner logic, int stateColor)
	{
		int radius = logic.getRequiredPlayerRange();
		if(radius <= 0)
			return;
		
		Vec3 center = Vec3.atCenterOf(info.pos()).add(0, 0.05, 0);
		double radiusSq = radius * radius;
		Vec3 playerPos = MC.player == null ? null
			: new Vec3(MC.player.getX(), MC.player.getY(), MC.player.getZ());
		boolean inside =
			playerPos != null && playerPos.distanceToSqr(center) <= radiusSq;
		int color = withAlpha(
			inside ? mixWithWhite(stateColor, 0.35F) : radiusColor.getColorI(),
			inside ? 0.65F : 0.35F);
		int segments = Math.max(32, radius * 12);
		double step = (Math.PI * 2) / segments;
		
		Vec3 prev = center.add(radius, 0, 0);
		for(int i = 1; i <= segments; i++)
		{
			double angle = i * step;
			Vec3 next = center.add(radius * Math.cos(angle), 0,
				radius * Math.sin(angle));
			RenderUtils.drawLine(matrices, prev, next, color, false);
			prev = next;
		}
	}
	
	private void drawVaultLink(PoseStack matrices, TrialSpawnerInfo info,
		int stateColor, ArrayList<OverlayEntry> overlays)
	{
		if(MC.level == null || info.vault() == null)
			return;
		
		BlockPos vaultPos = info.vault().pos();
		Vec3 start = Vec3.atCenterOf(info.pos());
		Vec3 end = Vec3.atCenterOf(vaultPos).add(0, 0.25, 0);
		int color = vaultLinkColor.getColorI();
		RenderUtils.drawLine(matrices, start, end, color, false);
		
		BlockState state = MC.level.getBlockState(vaultPos);
		if(!state.is(Blocks.VAULT))
			return;
		
		String status = describeVaultState(state);
		List<OverlayLine> lines = List.of(new OverlayLine("Vault", stateColor),
			new OverlayLine(status, color));
		Vec3 original = end.add(0, 0.6, 0);
		Vec3 resolved = resolveLabelPosition(original);
		overlays.add(new OverlayEntry(original, resolved, lines,
			overlayScale.getValueF()));
	}
	
	private void drawOverlay(PoseStack matrices, TrialSpawnerInfo info,
		TrialSpawner logic, TrialSpawnerState state, int headerColor,
		ArrayList<OverlayEntry> overlays)
	{
		if(MC.level == null)
			return;
		
		TrialSpawnerStateData data = logic.getStateData();
		if(data == null)
			return;
		
		TrialSpawnerDataAccessor accessor = (TrialSpawnerDataAccessor)data;
		long worldTime = MC.level.getGameTime();
		long cooldownTicks = accessor.getCooldownEnd() - worldTime;
		double cooldownSeconds = Math.max(0, cooldownTicks / 20.0);
		boolean cooldownEstimated = false;
		// if server did not set a cooldown, check predicted values we observed
		if(cooldownTicks <= 0 && estimateCooldownOnTransition.isChecked())
		{
			Long predicted = predictedCooldownEnds.get(info.pos());
			if(predicted != null && predicted > worldTime)
			{
				cooldownSeconds = Math.max(0, (predicted - worldTime) / 20.0);
				cooldownEstimated = true;
			}
		}
		long nextSpawnTicks = accessor.getNextMobSpawnsAt() - worldTime;
		double nextSpawnSeconds = Math.max(0, nextSpawnTicks / 20.0);
		
		int additionalPlayers = data.countAdditionalPlayers(info.pos());
		TrialSpawnerConfig config = logic.activeConfig();
		int totalMobs =
			Math.max(1, config.calculateTargetTotalMobs(additionalPlayers));
		int simultaneous = Math.max(1,
			config.calculateTargetSimultaneousMobs(additionalPlayers));
		int trackedSpawned =
			Mth.clamp(accessor.getTotalSpawnedMobs(), 0, totalMobs);
		Set<UUID> aliveSet = accessor.getSpawnedMobsAlive();
		int aliveFromData =
			aliveSet == null ? 0 : Math.min(aliveSet.size(), totalMobs);
		int totalWaves =
			Math.max(1, (int)Math.ceil(totalMobs / (double)simultaneous));
		String decorMob = info.decorMob() == null ? "" : info.decorMob();
		String spawnId = readMobId(data.getUpdateTag(state));
		String resolvedMob = resolveMobName(spawnId);
		String mobName = !decorMob.isEmpty() ? decorMob
			: (resolvedMob == null ? "" : resolvedMob);
		if(mobName.isEmpty())
			mobName = "Unknown";
		EntityType<?> mobType = resolveEntityType(spawnId, decorMob);
		int aliveFromWorld =
			countWorldMobs(info.pos(), logic, mobType, mobName);
		int alive = Math.max(aliveFromWorld, aliveFromData);
		if(trackedSpawned <= 0 && alive > 0)
			trackedSpawned = alive;
		int mobsProgress =
			Mth.clamp(Math.max(trackedSpawned, alive), 0, totalMobs);
		int currentWave = Mth.clamp(
			(int)Math.ceil(Math.max(1, mobsProgress) / (double)simultaneous),
			mobsProgress > 0 ? 1 : 0, totalWaves);
		
		String trialType = describeTrialType(mobName, spawnId);
		TrialStatus status = TrialStatus.fromState(state);
		String statusLine = describeStatus(status);
		
		ArrayList<OverlayLine> lines = new ArrayList<>();
		String title =
			logic.isOminous() ? "Ominous Trial Spawner" : "Trial Spawner";
		lines.add(new OverlayLine(title, headerColor));
		
		if(showMobType.isChecked())
			lines.add(new OverlayLine("Mob: " + mobName, 0xFFFFFFFF));
		
		if(showStatus.isChecked())
			lines.add(new OverlayLine("Status: " + statusLine, 0xFFFFFFFF));
		
		if(alive > 0)
			lines.add(new OverlayLine("Active mobs: " + alive, 0xFFFFFFFF));
		
		// removed wave info display (keeps overlay simple)
		
		if(showNextSpawn.isChecked() && state == TrialSpawnerState.ACTIVE)
		{
			String next = "Next: " + simultaneous + "x " + mobName + " in "
				+ formatSeconds(nextSpawnSeconds);
			lines.add(new OverlayLine(next, 0xFFFFFFFF));
		}
		
		if(showCooldown.isChecked() && cooldownSeconds > 0)
		{
			String prefix =
				cooldownEstimated ? "Cooldown (est): " : "Cooldown: ";
			lines.add(new OverlayLine(prefix + formatSeconds(cooldownSeconds),
				0xFFFFFFFF));
		}
		// raw timer debug removed
		
		if(showTrialType.isChecked())
			lines.add(new OverlayLine("Trial: " + trialType, 0xFFFFFFFF));
		
		if(showDistance.isChecked())
		{
			double meters = Math.sqrt(info.distanceSq());
			lines.add(new OverlayLine("Distance: " + Math.round(meters) + "m",
				0xFFFFFFFF));
		}
		
		if(showVaultLink.isChecked() && info.vault() != null)
		{
			String vaultInfo =
				describeVaultState(MC.level.getBlockState(info.vault().pos()));
			lines.add(new OverlayLine("Vault: " + vaultInfo, 0xFFFFFFFF));
		}
		
		Vec3 original = Vec3.atCenterOf(info.pos()).add(0, 1.6, 0);
		Vec3 resolved = resolveLabelPosition(original);
		overlays.add(new OverlayEntry(original, resolved, lines,
			overlayScale.getValueF()));
	}
	
	private void drawLabel(PoseStack matrices, Vec3 position,
		List<OverlayLine> lines, float scale)
	{
		if(lines.isEmpty() || MC.font == null)
			return;
		
		matrices.pushPose();
		Vec3 cam = RenderUtils.getCameraPos();
		matrices.translate(position.x - cam.x, position.y - cam.y,
			position.z - cam.z);
		var camEntity = MC.getCameraEntity();
		if(camEntity != null)
		{
			matrices.mulPose(Axis.YP.rotationDegrees(-camEntity.getYRot()));
			matrices.mulPose(Axis.XP.rotationDegrees(camEntity.getXRot()));
		}
		matrices.mulPose(Axis.YP.rotationDegrees(180));
		float s = 0.025F * scale;
		matrices.scale(s, -s, s);
		
		Font tr = MC.font;
		int bg = (int)(MC.options.getBackgroundOpacity(0.25F) * 255) << 24;
		int lineHeight = tr.lineHeight + 2;
		int maxWidth = lines.stream().mapToInt(line -> tr.width(line.text()))
			.max().orElse(0);
		int x = -maxWidth / 2;
		
		BufferSource vcp = RenderUtils.getVCP();
		for(int i = 0; i < lines.size(); i++)
		{
			OverlayLine line = lines.get(i);
			int y = i * lineHeight;
			DisplayMode layerType =
				NiceWurstModule.enforceTextLayer(DisplayMode.SEE_THROUGH);
			tr.drawInBatch(line.text(), x, y, line.color(), false,
				matrices.last().pose(), vcp, layerType, bg, 0xF000F0);
		}
		vcp.endBatch();
		matrices.popPose();
	}
	
	private Vec3 resolveLabelPosition(Vec3 target)
	{
		Vec3 cam = RenderUtils.getCameraPos();
		double distance = cam.distanceTo(target);
		double anchor = 12;
		if(distance <= anchor)
			return target;
		
		Vec3 dir = target.subtract(cam);
		double len = dir.length();
		if(len < 1e-4)
			return target;
		
		return cam.add(dir.scale(anchor / len));
	}
	
	private VaultInfo findLinkedVault(BlockPos spawnerPos)
	{
		if(vaults.isEmpty())
			return null;
		
		double maxRangeSq =
			vaultLinkRange.getValue() * vaultLinkRange.getValue();
		VaultInfo closest = null;
		double best = maxRangeSq;
		for(VaultInfo info : vaults)
		{
			double distSq = info.pos().distSqr(spawnerPos);
			if(distSq <= best)
			{
				closest = info;
				best = distSq;
			}
		}
		return closest;
	}
	
	private void loadOpenedVaults()
	{
		String server = null;
		try
		{
			if(MC != null && MC.getCurrentServer() != null)
				server = MC.getCurrentServer().ip;
		}catch(Throwable ignored)
		{}
		String name = sanitizeServer(server);
		File dir = new File("config/wurst/opened_vaults");
		if(!dir.exists())
			dir.mkdirs();
		File file = new File(dir, name + ".json");
		if(!file.isFile())
			return;
		try(FileReader r = new FileReader(file))
		{
			JsonObject root = GSON.fromJson(r, JsonObject.class);
			JsonArray arr = root.getAsJsonArray("openedPositions");
			if(arr == null)
				return;
			for(var el : arr)
			{
				try
				{
					JsonObject o = el.getAsJsonObject();
					int x = o.get("x").getAsInt();
					int y = o.get("y").getAsInt();
					int z = o.get("z").getAsInt();
					String dim = null;
					if(o.has("dimension"))
						dim = o.get("dimension").getAsString();
					if(dim == null)
					{
						try
						{
							if(MC != null && MC.level != null)
								dim = MC.level.dimension().identifier()
									.toString();
						}catch(Throwable ignored)
						{}
						if(dim == null)
							dim = "unknown";
					}
					String key = dim + "|" + x + "," + y + "," + z;
					openedVaultKeys.add(key);
				}catch(Throwable ignored)
				{}
			}
		}catch(Throwable ignored)
		{}
	}
	
	private void saveOpenedVaults()
	{
		String server = null;
		try
		{
			if(MC != null && MC.getCurrentServer() != null)
				server = MC.getCurrentServer().ip;
		}catch(Throwable ignored)
		{}
		String name = sanitizeServer(server);
		File dir = new File("config/wurst/opened_vaults");
		if(!dir.exists())
			dir.mkdirs();
		File file = new File(dir, name + ".json");
		JsonObject root = new JsonObject();
		JsonArray arr = new JsonArray();
		for(String key : openedVaultKeys)
		{
			try
			{
				String dim = "unknown";
				String coords = key;
				int sep = key.indexOf('|');
				if(sep >= 0)
				{
					dim = key.substring(0, sep);
					coords = key.substring(sep + 1);
				}
				String[] parts = coords.split(",");
				if(parts.length >= 3)
				{
					int x = Integer.parseInt(parts[0]);
					int y = Integer.parseInt(parts[1]);
					int z = Integer.parseInt(parts[2]);
					JsonObject o = new JsonObject();
					o.addProperty("dimension", dim);
					o.addProperty("x", x);
					o.addProperty("y", y);
					o.addProperty("z", z);
					arr.add(o);
				}
			}catch(Throwable ignored)
			{}
		}
		root.add("openedPositions", arr);
		try(FileWriter w = new FileWriter(file))
		{
			w.write(GSON.toJson(root));
		}catch(Throwable ignored)
		{}
	}
	
	private String sanitizeServer(String serverIp)
	{
		if(serverIp == null || serverIp.isBlank())
			return "singleplayer";
		return serverIp.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]",
			"_");
	}
	
	private String detectMobFromDecor(BlockPos spawnerPos)
	{
		if(MC.level == null || spawnerPos == null)
			return "";
		
		BlockPos base = spawnerPos.below();
		boolean hasCobble = false;
		boolean hasMossyCobble = false;
		boolean hasStone = false;
		boolean hasCobweb = false;
		boolean hasPodzol = false;
		boolean hasMushroom = false;
		boolean hasChiseledSandstone = false;
		boolean hasChiseledTuff = false;
		boolean hasPackedIce = false;
		boolean hasStoneBricks = false;
		boolean hasMossBlock = false;
		boolean hasBoneBlock = false;
		
		for(int dx = -1; dx <= 1; dx++)
		{
			for(int dz = -1; dz <= 1; dz++)
			{
				if(dx == 0 && dz == 0)
					continue;
				
				BlockPos check = base.offset(dx, 0, dz);
				BlockState state = MC.level.getBlockState(check);
				BlockState above = MC.level.getBlockState(check.above());
				
				if(state.is(Blocks.COBBLESTONE))
					hasCobble = true;
				if(state.is(Blocks.MOSSY_COBBLESTONE))
					hasMossyCobble = true;
				if(state.is(Blocks.STONE))
					hasStone = true;
				if(state.is(Blocks.COBWEB) || above.is(Blocks.COBWEB))
					hasCobweb = true;
				if(state.is(Blocks.PODZOL))
					hasPodzol = true;
				if(isMushroom(state) || isMushroom(above))
					hasMushroom = true;
				if(state.is(Blocks.CHISELED_SANDSTONE))
					hasChiseledSandstone = true;
				if(state.is(Blocks.CHISELED_TUFF))
					hasChiseledTuff = true;
				if(state.is(Blocks.PACKED_ICE))
					hasPackedIce = true;
				if(state.is(Blocks.STONE_BRICKS))
					hasStoneBricks = true;
				if(state.is(Blocks.MOSS_BLOCK))
					hasMossBlock = true;
				if(state.is(Blocks.BONE_BLOCK))
					hasBoneBlock = true;
			}
		}
		
		boolean hasPodzolMushroom = hasPodzol && hasMushroom;
		if(hasBoneBlock && hasPodzolMushroom)
			return "Bogged";
		if(hasCobble && hasMossyCobble)
			return "Baby Zombie";
		if(hasStone && hasCobweb && hasPodzolMushroom)
			return "Cave Spider";
		if(hasStone && hasCobweb)
			return "Spider";
		if(hasChiseledTuff)
			return "Breeze";
		if(hasChiseledSandstone)
			return "Husk";
		if(hasPackedIce)
			return "Stray";
		if(hasMossBlock)
			return "Slime";
		if(hasBoneBlock)
			return "Skeleton";
		if(hasStoneBricks)
			return "Silverfish";
		if(hasMossyCobble)
			return "Zombie";
		return "";
	}
	
	private boolean isMushroom(BlockState state)
	{
		return state.is(Blocks.BROWN_MUSHROOM) || state.is(Blocks.RED_MUSHROOM);
	}
	
	private int getColorForStatus(TrialStatus status)
	{
		return switch(status)
		{
			case ACTIVE -> colorActive.getColorI();
			case CHARGING -> colorCharging.getColorI();
			case COMPLETED -> colorCompleted.getColorI();
			case IDLE -> colorIdle.getColorI();
		};
	}
	
	private String readMobId(CompoundTag spawnData)
	{
		if(spawnData == null)
			return "";
		Optional<CompoundTag> entity = spawnData.getCompound("entity");
		if(entity.isPresent())
		{
			Optional<String> id = entity.get().getString("id");
			if(id.isPresent())
				return id.get();
		}
		return spawnData.getString("id").orElse("");
	}
	
	private String resolveMobName(String mobId)
	{
		if(mobId == null || mobId.isEmpty())
			return "Unknown";
		
		Identifier id = Identifier.tryParse(mobId);
		if(id != null)
		{
			EntityType<?> entity = BuiltInRegistries.ENTITY_TYPE.getValue(id);
			if(entity != null)
				return entity.getDescription().getString();
		}
		
		return prettifyId(mobId);
	}
	
	private String prettifyId(String raw)
	{
		int colon = raw.indexOf(':');
		String clean = colon >= 0 ? raw.substring(colon + 1) : raw;
		String[] parts = clean.split("_");
		StringBuilder sb = new StringBuilder();
		for(String part : parts)
		{
			if(part.isEmpty())
				continue;
			if(sb.length() > 0)
				sb.append(' ');
			sb.append(Character.toUpperCase(part.charAt(0)));
			if(part.length() > 1)
				sb.append(part.substring(1));
		}
		return sb.length() == 0 ? clean : sb.toString();
	}
	
	private String describeTrialType(String resolvedName, String mobId)
	{
		String trial = describeTrialTypeFromValue(resolvedName);
		if(!"Unknown".equals(trial))
			return trial;
		return describeTrialTypeFromValue(mobId);
	}
	
	private String describeTrialTypeFromValue(String value)
	{
		if(value == null || value.isEmpty())
			return "Unknown";
		
		String lower = value.toLowerCase(Locale.ROOT);
		if(lower.contains("breeze"))
			return "Breeze / Parkour";
		if(lower.contains("bogged") || lower.contains("drifter")
			|| lower.contains("husk") || lower.contains("skeleton")
			|| lower.contains("stray") || lower.contains("zombie")
			|| lower.contains("spider") || lower.contains("slime")
			|| lower.contains("silverfish") || lower.contains("baby"))
			return "Combat";
		return "Unknown";
	}
	
	private EntityType<?> resolveEntityType(String mobId, String decorMob)
	{
		EntityType<?> type = parseMobType(mobId);
		if(type != null)
			return type;
		return mapDecorMobToType(decorMob);
	}
	
	private EntityType<?> parseMobType(String mobId)
	{
		if(mobId == null || mobId.isEmpty())
			return null;
		Identifier id = Identifier.tryParse(mobId);
		if(id == null)
			return null;
		ResourceKey<EntityType<?>> key =
			ResourceKey.create(Registries.ENTITY_TYPE, id);
		return BuiltInRegistries.ENTITY_TYPE.getValue(key);
	}
	
	private EntityType<?> mapDecorMobToType(String decorMob)
	{
		if(decorMob == null || decorMob.isEmpty())
			return null;
		
		return switch(decorMob.toLowerCase(Locale.ROOT))
		{
			case "baby zombie", "zombie" -> EntityType.ZOMBIE;
			case "husk" -> EntityType.HUSK;
			case "spider" -> EntityType.SPIDER;
			case "cave spider" -> EntityType.CAVE_SPIDER;
			case "silverfish" -> EntityType.SILVERFISH;
			case "slime" -> EntityType.SLIME;
			case "skeleton" -> EntityType.SKELETON;
			case "bogged" -> EntityType.BOGGED;
			case "stray" -> EntityType.STRAY;
			case "breeze" -> EntityType.BREEZE;
			default -> null;
		};
	}
	
	private int countWorldMobs(BlockPos pos, TrialSpawner logic,
		EntityType<?> mobType, String mobName)
	{
		if(MC.level == null || pos == null)
			return 0;
		
		double radius =
			logic == null ? 8 : Math.max(6, logic.getRequiredPlayerRange() + 4);
		AABB box = AABB.ofSize(Vec3.atCenterOf(pos), radius * 2, radius * 2,
			radius * 2);
		return MC.level.getEntitiesOfClass(LivingEntity.class, box,
			entity -> matchesMob(entity, mobType, mobName)).size();
	}
	
	private boolean matchesMob(LivingEntity entity, EntityType<?> mobType,
		String mobName)
	{
		if(entity == null)
			return false;
		if(mobType != null && entity.getType() == mobType)
			return true;
		if(mobName == null || mobName.isEmpty())
			return false;
		
		String typeName = entity.getType().getDescription().getString();
		return typeName.equalsIgnoreCase(mobName);
	}
	
	private String describeVaultState(BlockState state)
	{
		if(!state.is(Blocks.VAULT) || !state.hasProperty(VaultBlock.STATE))
			return "Missing";
		
		VaultState vaultState = state.getValue(VaultBlock.STATE);
		boolean ominous = state.hasProperty(VaultBlock.OMINOUS)
			&& state.getValue(VaultBlock.OMINOUS);
		String prefix = ominous ? "Ominous " : "";
		return switch(vaultState)
		{
			case ACTIVE -> prefix + "Active";
			case UNLOCKING -> prefix + "Unlocking";
			case EJECTING -> prefix + "Ejecting loot";
			case INACTIVE -> prefix + "Locked";
		};
	}
	
	private String describeStatus(TrialStatus status)
	{
		return switch(status)
		{
			case ACTIVE -> "Spawning";
			case CHARGING -> "Priming";
			case COMPLETED -> "Completed";
			case IDLE -> "Idle";
		};
	}
	
	private String formatSeconds(double seconds)
	{
		if(seconds >= 60)
		{
			int mins = (int)Math.floor(seconds / 60.0);
			int secs = (int)Math.round(seconds - mins * 60);
			if(secs == 60)
			{
				mins++;
				secs = 0;
			}
			return mins + "m " + String.format(Locale.ROOT, "%02ds", secs);
		}
		if(seconds >= 10)
			return (int)Math.round(seconds) + "s";
		return String.format(Locale.ROOT, "%.1fs", seconds);
	}
	
	private String progressBar(double progress)
	{
		final int segments = 10;
		int filled =
			Mth.clamp((int)Math.round(progress * segments), 0, segments);
		StringBuilder sb = new StringBuilder("[");
		for(int i = 0; i < segments; i++)
			sb.append(i < filled ? "#" : "-");
		sb.append("]");
		return sb.toString();
	}
	
	private int withAlpha(int color, float alpha)
	{
		int a = Mth.clamp((int)(alpha * 255), 0, 255);
		return (color & 0x00FFFFFF) | (a << 24);
	}
	
	private int mixWithWhite(int color, float factor)
	{
		int a = color >>> 24;
		int r = (color >> 16) & 0xFF;
		int g = (color >> 8) & 0xFF;
		int b = color & 0xFF;
		r = Mth.clamp((int)Mth.lerpInt(factor, r, 255), 0, 255);
		g = Mth.clamp((int)Mth.lerpInt(factor, g, 255), 0, 255);
		b = Mth.clamp((int)Mth.lerpInt(factor, b, 255), 0, 255);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
	
	@Override
	public String getRenderName()
	{
		if(showCountInHackList.isChecked() && foundCount > 0)
			return getName() + " [" + foundCount + "]";
		return super.getRenderName();
	}
	
	private record TrialSpawnerInfo(TrialSpawnerBlockEntity blockEntity,
		BlockPos pos, double distanceSq, VaultInfo vault, String decorMob)
	{}
	
	private record VaultInfo(BlockPos pos)
	{}
	
	private record OverlayLine(String text, int color)
	{}
	
	private record OverlayEntry(Vec3 original, Vec3 resolved,
		List<OverlayLine> lines, float scale)
	{}
	
	private enum TrialStatus
	{
		IDLE,
		CHARGING,
		ACTIVE,
		COMPLETED;
		
		public static TrialStatus fromState(TrialSpawnerState state)
		{
			return switch(state)
			{
				case ACTIVE -> ACTIVE;
				case WAITING_FOR_PLAYERS -> CHARGING;
				case WAITING_FOR_REWARD_EJECTION, EJECTING_REWARD -> COMPLETED;
				case COOLDOWN, INACTIVE -> IDLE;
			};
		}
	}
}
