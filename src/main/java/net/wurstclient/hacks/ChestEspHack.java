/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.chestesp.ChestEspGroup;
import net.wurstclient.hacks.chestesp.ChestEspGroupManager;
import net.wurstclient.chestsearch.ChestEntry;
import net.wurstclient.chestsearch.ChestManager;
import net.wurstclient.chestsearch.ChestSearchMarkerRenderer;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.hacks.chestesp.ChestEspBlockGroup;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.chunk.ChunkUtils;

public class ChestEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener, PacketInputListener
{
	private static final double OPENED_MARKER_THICKNESS = 2.0;
	private static final long BLOCK_UPDATE_GRACE_MS = 750L;
	private static final long CHUNK_SCAN_EXPIRY_MS = 30000L;
	private static final long REVEAL_SAMPLE_EXPIRY_MS = 120000L;
	private static final long BURST_WINDOW_MS = 2000L;
	private static final long BURST_QUIET_MS = 10000L;
	private static final long OPENED_CHESTS_REFRESH_MS = 1000L;
	private static final long ENVIRONMENT_CACHE_REFRESH_MS = 125L;
	private static final long ENV_FILTER_CACHE_TTL_MS = 250L;
	private static final int BURST_THRESHOLD = 40;
	private static final int REVEAL_MIN_SAMPLES = 12;
	
	private final EspStyleSetting style = new EspStyleSetting();
	private final net.wurstclient.settings.CheckboxSetting stickyArea =
		new net.wurstclient.settings.CheckboxSetting("Sticky area",
			"Off: ESP drop-off follows you as chunks change.\n"
				+ "On: Keeps results anchored (useful for pathing back).\n"
				+ "Note: ChestESP tracks loaded block entities; visibility is still limited by server view distance.",
			false);
	private final ChestEspGroupManager groups = new ChestEspGroupManager();
	
	private final CheckboxSetting showCountInHackList = new CheckboxSetting(
		"HackList count",
		"Appends the number of detected chests/containers to this hack's entry in the HackList.",
		false);
	private final SliderSetting boxAlpha = new SliderSetting("Box opacity",
		"Opacity for filled boxes (0 = fully transparent, 255 = opaque).", 64,
		0, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	private final SliderSetting lineAlpha = new SliderSetting("Line opacity",
		"Opacity for outlines and tracers (0 = fully transparent, 255 = opaque).",
		128, 0, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	private final SliderSetting tracerAlpha =
		new SliderSetting("Tracer opacity",
			"Opacity for tracers (0 = fully transparent, 255 = opaque).", 128,
			0, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	private final CheckboxSetting chestEspRenderLimitEnabled =
		new CheckboxSetting("Enable ChestESP render limit",
			"Limits how many ChestESP targets are processed per update.",
			false);
	private final SliderSetting chestEspRenderLimit =
		new SliderSetting("ChestESP render limit",
			"Max ChestESP targets processed per update.\n0 = unlimited", 0, 0,
			2000, 1, SliderSetting.ValueDisplay.INTEGER);
	private int foundCount;
	private boolean preFilteredEnv;
	
	private final CheckboxSetting onlyAboveGround =
		new CheckboxSetting("Above ground only",
			"Only show chests/containers at or above the configured Y level.",
			false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	private java.util.List<ChestEntry> openedChests = java.util.List.of();
	private long lastOpenedChestsRefreshMs;
	
	// Buried highlighting
	private final CheckboxSetting highlightBuried = new CheckboxSetting(
		"Highlight buried separately",
		"Chests with a non-air block above and shulker boxes whose opening side is blocked will be highlighted in a different color.",
		false);
	private final ColorSetting buriedColor = new ColorSetting("Buried color",
		"Color used for buried chests and shulkers.",
		new java.awt.Color(0xFF7F00));
	
	private final CheckboxSetting onlyBuried = new CheckboxSetting(
		"Only buried",
		"Only show buried containers (non-air above for chests/barrels; blocked opening side for shulkers).",
		false);
	
	private final CheckboxSetting filterNearSpawners = new CheckboxSetting(
		"Filter spawners",
		"Hides single chests that are near a mob spawner in the Overworld. Does not affect double chests or shulkers.",
		false);
	
	private final CheckboxSetting filterTrialChambers = new CheckboxSetting(
		"Filter trial chambers",
		"Hides single chests, barrels, hoppers, and dispensers that match common trial chamber layouts. Does not affect double chests or shulkers.",
		false);
	
	private final CheckboxSetting doubleChestsOnly =
		new CheckboxSetting("Double chests only",
			"Only highlight/tracer double chests when searching for chests.",
			false);
	
	private final CheckboxSetting filterVillages = new CheckboxSetting(
		"Filter villages",
		"Hides single chests that appear to belong to villages. Does not affect double chests or shulkers.",
		false);
	
	private final CheckboxSetting shulkerChatAlerts =
		new CheckboxSetting("Shulker chat alerts",
			"Sends a chat alert when ChestESP detects a shulker box.", false);
	private final CheckboxSetting antiEspDetection = new CheckboxSetting(
		"Anti-ESP detection",
		"Detects common anti-container-ESP packet patterns (missing block entities, delayed reveals, fixed reveal radius, fake replacements, and packet bursts).",
		true);
	private final CheckboxSetting antiEspAlerts = new CheckboxSetting(
		"Anti-ESP alerts",
		"Sends chat warnings when ChestESP detects suspicious anti-ESP behavior.",
		true);
	
	private List<BlockPos> cachedTrialSpawners = List.of();
	private List<BlockPos> cachedSpawners = List.of();
	private List<Vec3> cachedVillagerPositions = List.of();
	private List<Vec3> cachedGolemPositions = List.of();
	private final HashMap<BlockPos, EnvFilterCacheEntry> envFilterCache =
		new HashMap<>();
	private int lastEnvFilterSignature;
	private long lastEnvironmentalRefreshMs;
	private BlockPos lastEnvironmentRefreshAnchor;
	private String lastEnvironmentRefreshDimension;
	private boolean environmentalCachesDirty = true;
	private final HashSet<BlockPos> alertedShulkers = new HashSet<>();
	private final HashMap<BlockPos, Long> missingContainerAt = new HashMap<>();
	private final HashMap<BlockPos, Long> lastBlockUpdateAt = new HashMap<>();
	private final HashSet<BlockPos> discoveredContainers = new HashSet<>();
	private final ArrayDeque<RevealSample> revealSamples = new ArrayDeque<>();
	private final ArrayDeque<Long> blockEntityBurstTimes = new ArrayDeque<>();
	private final ArrayDeque<ChunkScanRequest> chunkScanQueue =
		new ArrayDeque<>();
	private final HashSet<Long> queuedChunkScans = new HashSet<>();
	private final HashMap<String, Long> antiEspCooldowns = new HashMap<>();
	private final Object antiEspLock = new Object();
	private long lastContainerBePacketMs;
	private long burstStartMs = -1L;
	private boolean antiEspSuspicious;
	private int antiEspSignals;
	private boolean antiEspPreviouslyEnabled;
	
	private static final TagKey<Block> WAXED_COPPER_BLOCKS_TAG = TagKey.create(
		Registries.BLOCK,
		Identifier.fromNamespaceAndPath("minecraft", "waxed_copper_blocks"));
	
	public ChestEspHack()
	{
		super("ChestESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(stickyArea);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
		addSetting(highlightBuried);
		addSetting(buriedColor);
		addSetting(onlyBuried);
		addSetting(filterNearSpawners);
		addSetting(filterTrialChambers);
		addSetting(doubleChestsOnly);
		addSetting(filterVillages);
		addSetting(shulkerChatAlerts);
		addSetting(antiEspDetection);
		addSetting(antiEspAlerts);
		addSetting(showCountInHackList);
		addSetting(boxAlpha);
		addSetting(lineAlpha);
		addSetting(tracerAlpha);
		addSetting(chestEspRenderLimitEnabled);
		addSetting(chestEspRenderLimit);
		groups.allGroups.stream().flatMap(ChestEspGroup::getSettings)
			.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		resetAntiEspState();
		antiEspPreviouslyEnabled = antiEspDetection.isChecked();
		lastOpenedChestsRefreshMs = 0L;
		lastEnvironmentalRefreshMs = 0L;
		lastEnvironmentRefreshAnchor = null;
		lastEnvironmentRefreshDimension = null;
		environmentalCachesDirty = true;
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		
		groups.allGroups.forEach(ChestEspGroup::clear);
		foundCount = 0;
		cachedTrialSpawners = List.of();
		cachedSpawners = List.of();
		cachedVillagerPositions = List.of();
		cachedGolemPositions = List.of();
		envFilterCache.clear();
		lastEnvFilterSignature = 0;
		lastEnvironmentalRefreshMs = 0L;
		lastEnvironmentRefreshAnchor = null;
		lastEnvironmentRefreshDimension = null;
		environmentalCachesDirty = true;
		preFilteredEnv = false;
		alertedShulkers.clear();
		resetAntiEspState();
		antiEspPreviouslyEnabled = false;
	}
	
	@Override
	public void onUpdate()
	{
		boolean antiEspNow = antiEspDetection.isChecked();
		if(antiEspNow != antiEspPreviouslyEnabled)
		{
			resetAntiEspState();
			antiEspPreviouslyEnabled = antiEspNow;
		}
		
		if(antiEspDetection.isChecked())
		{
			processQueuedChunkScans(2);
			checkRevealPattern();
			pruneAntiEspState();
		}
		
		groups.allGroups.forEach(ChestEspGroup::clear);
		
		// Build environmental caches first so we can pre-filter before adding
		invalidateEnvFilterCacheIfNeeded();
		refreshEnvironmentalCaches();
		preFilteredEnv = MC.level != null && (isSpawnerFilterActive()
			|| filterTrialChambers.isChecked() || filterVillages.isChecked());
		
		double yLimit = aboveGroundY.getValue();
		boolean enforceAboveGround = onlyAboveGround.isChecked();
		int effectiveLimit = getEffectiveEspLimit();
		HashSet<BlockPos> seenShulkers =
			shulkerChatAlerts.isChecked() ? new HashSet<>() : null;
		
		java.util.stream.Stream<BlockEntity> blockEntityStream =
			effectiveLimit > 0
				? getNearestLoadedBlockEntities(effectiveLimit).stream()
				: ChunkUtils.getLoadedBlockEntities();
		blockEntityStream.forEach(be -> {
			if(enforceAboveGround && be.getBlockPos().getY() < yLimit)
				return;
			
			if(antiEspDetection.isChecked())
				recordContainerDiscovery(be);
			
			if(seenShulkers != null && isShulkerBlockEntity(be))
			{
				BlockPos pos = be.getBlockPos().immutable();
				seenShulkers.add(pos);
				if(alertedShulkers.add(pos))
					ChatUtils.message("ChestESP: Shulker box at " + pos.getX()
						+ ", " + pos.getY() + ", " + pos.getZ());
			}
			
			// Pre-filter by environment to avoid flicker and wasted work
			if(preFilteredEnv && shouldFilterBlockEntityByEnvironment(be))
				return;
			
			// Respect double-chest-only suppression
			if(shouldSkipSingleChest(be))
				return;
			
			groups.blockGroups.forEach(group -> group.addIfMatches(be));
		});
		
		if(seenShulkers != null)
			alertedShulkers.retainAll(seenShulkers);
		else
			alertedShulkers.clear();
		
		if(MC.level != null)
		{
			Iterable<Entity> entities = effectiveLimit > 0
				? getNearestEntitiesForRendering(effectiveLimit)
				: MC.level.entitiesForRendering();
			for(Entity entity : entities)
			{
				if(enforceAboveGround && entity.getY() < yLimit)
					continue;
				
				groups.entityGroups
					.forEach(group -> group.addIfMatches(entity));
			}
		}
		
		int total = groups.allGroups.stream().filter(ChestEspGroup::isEnabled)
			.mapToInt(g -> g.getBoxes().size()).sum();
		foundCount = Math.min(total, 999);
		
		refreshOpenedChestCacheIfNeeded();
	}
	
	private int getEffectiveEspLimit()
	{
		int localLimit = chestEspRenderLimitEnabled.isChecked()
			? chestEspRenderLimit.getValueI() : 0;
		int globalLimit =
			WURST.getHax().globalToggleHack.getEffectiveGlobalEspRenderLimit();
		
		if(localLimit <= 0)
			return globalLimit;
		if(globalLimit <= 0)
			return localLimit;
		
		return Math.min(localLimit, globalLimit);
	}
	
	private void refreshOpenedChestCacheIfNeeded()
	{
		long now = System.currentTimeMillis();
		if(now - lastOpenedChestsRefreshMs < OPENED_CHESTS_REFRESH_MS)
			return;
		
		lastOpenedChestsRefreshMs = now;
		
		// Keep ChestESP markers available without requiring ChestSearch to be
		// enabled, but avoid expensive DB reads every tick.
		try
		{
			ChestManager mgr = new ChestManager();
			openedChests = mgr.all();
		}catch(Throwable ignored)
		{
			openedChests = java.util.List.of();
		}
	}
	
	private List<BlockEntity> getNearestLoadedBlockEntities(int limit)
	{
		var eyesPos = RotationUtils.getEyesPos();
		PriorityQueue<BlockEntity> heap = new PriorityQueue<>(limit + 1,
			Comparator.comparingDouble(
				(BlockEntity be) -> be.getBlockPos().distToCenterSqr(eyesPos))
				.reversed());
		
		ChunkUtils.getLoadedBlockEntities().forEach(be -> {
			if(heap.size() < limit)
				heap.offer(be);
			else if(be.getBlockPos().distToCenterSqr(eyesPos) < heap.peek()
				.getBlockPos().distToCenterSqr(eyesPos))
			{
				heap.poll();
				heap.offer(be);
			}
		});
		
		return new ArrayList<>(heap);
	}
	
	private List<Entity> getNearestEntitiesForRendering(int limit)
	{
		PriorityQueue<Entity> heap = new PriorityQueue<>(limit + 1,
			Comparator.comparingDouble((Entity e) -> e.distanceToSqr(MC.player))
				.reversed());
		
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(heap.size() < limit)
				heap.offer(entity);
			else if(entity.distanceToSqr(MC.player) < heap.peek()
				.distanceToSqr(MC.player))
			{
				heap.poll();
				heap.offer(entity);
			}
		}
		
		return new ArrayList<>(heap);
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!antiEspDetection.isChecked() || MC.level == null)
			return;
		
		Packet<?> packet = event.getPacket();
		ChunkPos affected = ChunkUtils.getAffectedChunk(packet);
		if(affected != null)
		{
			queueChunkScan(affected);
			environmentalCachesDirty = true;
		}
		
		if(packet instanceof ClientboundBlockUpdatePacket blockUpdate)
		{
			environmentalCachesDirty = true;
			handleBlockUpdate(blockUpdate.getPos(),
				blockUpdate.getBlockState());
			return;
		}
		
		if(packet instanceof ClientboundSectionBlocksUpdatePacket deltaUpdate)
		{
			environmentalCachesDirty = true;
			deltaUpdate.runUpdates(this::handleBlockUpdate);
			return;
		}
		
		if(packet instanceof ClientboundBlockEntityDataPacket bePacket)
		{
			environmentalCachesDirty = true;
			handleBlockEntityDataPacket(bePacket);
		}
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		groups.entityGroups.stream().filter(ChestEspGroup::isEnabled)
			.forEach(g -> g.updateBoxes(partialTicks));
		
		if(style.hasBoxes())
			renderBoxes(matrixStack);
		
		if(style.hasLines())
			renderTracers(matrixStack, partialTicks);
	}
	
	private void renderBoxes(PoseStack matrixStack)
	{
		boolean workstationEnabled = false;
		boolean redstoneEnabled = false;
		boolean redstoneActiveOnly = false;
		try
		{
			var hax = net.wurstclient.WurstClient.INSTANCE.getHax();
			workstationEnabled = hax.workstationEspHack != null
				&& hax.workstationEspHack.isEnabled();
			redstoneEnabled =
				hax.redstoneEspHack != null && hax.redstoneEspHack.isEnabled();
			redstoneActiveOnly = hax.redstoneEspHack != null
				&& hax.redstoneEspHack.isActiveOnlyMode();
		}catch(Throwable ignored)
		{}
		ChestSearchHack csh = null;
		boolean canMarkOpened = false;
		ChestSearchHack.OpenedChestMarker markerMode =
			ChestSearchHack.OpenedChestMarker.LINE;
		if(!openedChests.isEmpty())
		{
			try
			{
				csh = net.wurstclient.WurstClient.INSTANCE
					.getHax().chestSearchHack;
				if(csh != null && csh.isMarkOpenedChest())
				{
					canMarkOpened = true;
					ChestSearchHack.OpenedChestMarker selected =
						csh.getOpenedChestMarker();
					if(selected != null)
						markerMode = selected;
				}
			}catch(Throwable ignored)
			{
				csh = null;
				canMarkOpened = false;
			}
		}
		
		String curDimFull = MC.level == null ? "overworld"
			: MC.level.dimension().identifier().toString();
		String curDim = MC.level == null ? "overworld"
			: MC.level.dimension().identifier().getPath();
		
		boolean applyEnvFilters = MC.level != null && !preFilteredEnv
			&& (isSpawnerFilterActive() || filterTrialChambers.isChecked()
				|| filterVillages.isChecked());
		
		for(ChestEspGroup group : groups.allGroups)
		{
			if(!group.isEnabled())
				continue;
			
			// Suppress overlapping categories when specialized hacks are active
			if(workstationEnabled
				&& (group == groups.crafters || group == groups.furnaces))
				continue;
			if(redstoneEnabled && !redstoneActiveOnly
				&& (group == groups.droppers || group == groups.dispensers
					|| group == groups.hoppers))
				continue;
			
			List<AABB> boxes = group.getBoxes();
			List<AABB> buriedBoxes = java.util.Collections.emptyList();
			if(group instanceof ChestEspBlockGroup bg)
				buriedBoxes = bg.getBuriedBoxes();
			
			if(MC.level != null
				&& (highlightBuried.isChecked() || onlyBuried.isChecked())
				&& buriedBoxes != null && !buriedBoxes.isEmpty())
			{
				buriedBoxes = filterToChestShulkerBarrelBoxes(buriedBoxes);
			}
			
			if((buriedBoxes.isEmpty()
				&& (highlightBuried.isChecked() || onlyBuried.isChecked()))
				&& MC.level != null && boxes != null && !boxes.isEmpty())
			{
				buriedBoxes =
					computeBuriedBoxesByAboveForChestShulkerBarrel(boxes);
			}
			
			if(applyEnvFilters)
			{
				if(boxes != null && !boxes.isEmpty())
					boxes = filterBoxesByEnvironment(boxes);
				if(buriedBoxes != null && !buriedBoxes.isEmpty())
					buriedBoxes = filterBoxesByEnvironment(buriedBoxes);
			}
			
			if(onlyBuried.isChecked())
				boxes = buriedBoxes;
			
			if(boxes.isEmpty() && buriedBoxes.isEmpty())
				continue;
			
			int quadAlpha = boxAlpha.getValueI();
			int lineAlphaI = lineAlpha.getValueI();
			int quadsColor = group.getColorI(quadAlpha);
			int linesColor = group.getColorI(lineAlphaI);
			
			// Compute opened/closed across both normal and buried boxes
			List<AABB> openedBoxes = java.util.Collections.emptyList();
			// Base normal = boxes minus buried (by identity)
			java.util.Set<AABB> buriedId = java.util.Collections
				.newSetFromMap(new java.util.IdentityHashMap<>());
			buriedId.addAll(buriedBoxes);
			java.util.ArrayList<AABB> baseNormal = new java.util.ArrayList<>();
			for(AABB b : boxes)
				if(!buriedId.contains(b))
					baseNormal.add(b);
			List<AABB> closedNormal = baseNormal;
			List<AABB> closedBuried = buriedBoxes;
			if(canMarkOpened)
			{
				List<AABB> opened = new ArrayList<>();
				List<AABB> cNormal = new ArrayList<>();
				List<AABB> cBuried = new ArrayList<>();
				// check normal
				for(AABB box : baseNormal)
				{
					if(isRecordedChest(box, curDimFull, curDim))
						opened.add(box);
					else
						cNormal.add(box);
				}
				// check buried as well
				for(AABB box : buriedBoxes)
				{
					if(isRecordedChest(box, curDimFull, curDim))
						opened.add(box);
					else
						cBuried.add(box);
				}
				openedBoxes = opened;
				closedNormal = cNormal;
				closedBuried = cBuried;
			}
			
			boolean useRecolor = canMarkOpened
				&& markerMode == ChestSearchHack.OpenedChestMarker.RECOLOR
				&& !openedBoxes.isEmpty();
			
			if(useRecolor && csh != null)
			{
				// draw closed normals in group color
				if(!closedNormal.isEmpty())
				{
					RenderUtils.drawSolidBoxes(matrixStack, closedNormal,
						quadsColor, false);
					RenderUtils.drawOutlinedBoxes(matrixStack, closedNormal,
						linesColor, false);
				}
				// draw closed buried with buried color if enabled, else group
				// color
				if(!closedBuried.isEmpty())
				{
					int bFill = highlightBuried.isChecked()
						? buriedColor.getColorI(quadAlpha) : quadsColor;
					int bLine = highlightBuried.isChecked()
						? buriedColor.getColorI(lineAlphaI) : linesColor;
					RenderUtils.drawSolidBoxes(matrixStack, closedBuried, bFill,
						false);
					RenderUtils.drawOutlinedBoxes(matrixStack, closedBuried,
						bLine, false);
				}
				
				int markColor = csh.getMarkXColorARGB();
				int openedFillColor =
					(markColor & 0x00FFFFFF) | (quadAlpha << 24);
				int openedLineColor =
					(markColor & 0x00FFFFFF) | (lineAlphaI << 24);
				RenderUtils.drawSolidBoxes(matrixStack, openedBoxes,
					openedFillColor, false);
				RenderUtils.drawOutlinedBoxes(matrixStack, openedBoxes,
					openedLineColor, false);
			}else
			{
				if(!buriedBoxes.isEmpty())
				{
					if(highlightBuried.isChecked())
					{
						if(!baseNormal.isEmpty())
						{
							RenderUtils.drawSolidBoxes(matrixStack, baseNormal,
								quadsColor, false);
							RenderUtils.drawOutlinedBoxes(matrixStack,
								baseNormal, linesColor, false);
						}
						int bFill = buriedColor.getColorI(quadAlpha);
						int bLine = buriedColor.getColorI(lineAlphaI);
						RenderUtils.drawSolidBoxes(matrixStack, buriedBoxes,
							bFill, false);
						RenderUtils.drawOutlinedBoxes(matrixStack, buriedBoxes,
							bLine, false);
					}else
					{
						// Draw all with default group color
						RenderUtils.drawSolidBoxes(matrixStack, boxes,
							quadsColor, false);
						RenderUtils.drawOutlinedBoxes(matrixStack, boxes,
							linesColor, false);
					}
				}else
				{
					RenderUtils.drawSolidBoxes(matrixStack, boxes, quadsColor,
						false);
					RenderUtils.drawOutlinedBoxes(matrixStack, boxes,
						linesColor, false);
				}
				
				if(canMarkOpened
					&& markerMode == ChestSearchHack.OpenedChestMarker.LINE
					&& csh != null && !openedBoxes.isEmpty())
				{
					int markerColor = (csh.getMarkXColorARGB() & 0x00FFFFFF)
						| (lineAlphaI << 24);
					for(AABB box : openedBoxes)
					{
						ChestSearchMarkerRenderer.drawMarker(matrixStack, box,
							markerColor, OPENED_MARKER_THICKNESS, false);
					}
				}
			}
		}
	}
	
	private boolean isChestShulkerOrBarrelBox(AABB box)
	{
		if(MC.level == null || box == null)
			return false;
		
		int boxMinX = (int)Math.floor(box.minX + 1e-6);
		int boxMaxX = (int)Math.floor(box.maxX - 1e-6);
		int boxMinY = (int)Math.floor(box.minY + 1e-6);
		int boxMaxY = (int)Math.floor(box.maxY - 1e-6);
		int boxMinZ = (int)Math.floor(box.minZ + 1e-6);
		int boxMaxZ = (int)Math.floor(box.maxZ - 1e-6);
		
		for(int x = boxMinX; x <= boxMaxX; x++)
		{
			for(int y = boxMinY; y <= boxMaxY; y++)
			{
				for(int z = boxMinZ; z <= boxMaxZ; z++)
				{
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = MC.level.getBlockState(pos);
					if(state == null)
						continue;
					
					var block = state.getBlock();
					if(block instanceof ChestBlock
						|| block instanceof ShulkerBoxBlock
						|| block instanceof BarrelBlock)
						return true;
				}
			}
		}
		
		return false;
	}
	
	private List<AABB> filterToChestShulkerBarrelBoxes(List<AABB> boxes)
	{
		if(MC.level == null || boxes == null || boxes.isEmpty())
			return java.util.Collections.emptyList();
		
		java.util.ArrayList<AABB> out = new java.util.ArrayList<>();
		for(AABB box : boxes)
			if(isChestShulkerOrBarrelBox(box))
				out.add(box);
			
		return out;
	}
	
	private List<AABB> computeBuriedBoxesByAboveForChestShulkerBarrel(
		List<AABB> boxes)
	{
		if(MC.level == null || boxes == null || boxes.isEmpty())
			return java.util.Collections.emptyList();
		
		java.util.ArrayList<AABB> buried = new java.util.ArrayList<>();
		
		for(AABB box : boxes)
		{
			if(box == null)
				continue;
			
			if(!isChestShulkerOrBarrelBox(box))
				continue;
			
			int boxMinX = (int)Math.floor(box.minX + 1e-6);
			int boxMaxX = (int)Math.floor(box.maxX - 1e-6);
			int boxMinY = (int)Math.floor(box.minY + 1e-6);
			int boxMaxY = (int)Math.floor(box.maxY - 1e-6);
			int boxMinZ = (int)Math.floor(box.minZ + 1e-6);
			int boxMaxZ = (int)Math.floor(box.maxZ - 1e-6);
			
			boolean isBuried = false;
			
			for(int x = boxMinX; x <= boxMaxX && !isBuried; x++)
			{
				for(int y = boxMinY; y <= boxMaxY && !isBuried; y++)
				{
					for(int z = boxMinZ; z <= boxMaxZ && !isBuried; z++)
					{
						BlockPos above = new BlockPos(x, y, z).above();
						BlockState aboveState = MC.level.getBlockState(above);
						if(!aboveState.isAir()
							&& !(aboveState.getBlock() instanceof HopperBlock))
							isBuried = true;
					}
				}
			}
			
			if(isBuried)
				buried.add(box);
		}
		
		return buried;
	}
	
	private boolean isRecordedChest(AABB box, String curDimFull, String curDim)
	{
		if(openedChests.isEmpty())
			return false;
		
		int boxMinX = (int)Math.floor(box.minX + 1e-6);
		int boxMaxX = (int)Math.floor(box.maxX - 1e-6);
		int boxMinY = (int)Math.floor(box.minY + 1e-6);
		int boxMaxY = (int)Math.floor(box.maxY - 1e-6);
		int boxMinZ = (int)Math.floor(box.minZ + 1e-6);
		int boxMaxZ = (int)Math.floor(box.maxZ - 1e-6);
		
		for(ChestEntry e : openedChests)
		{
			if(e == null || e.dimension == null)
				continue;
			
			String ed = e.dimension;
			boolean sameDimension = ed.equals(curDimFull) || ed.equals(curDim)
				|| ed.endsWith(":" + curDim);
			if(!sameDimension)
				continue;
			
			int minX = Math.min(e.x, e.maxX);
			int maxX = Math.max(e.x, e.maxX);
			int minY = Math.min(e.y, e.maxY);
			int maxY = Math.max(e.y, e.maxY);
			int minZ = Math.min(e.z, e.maxZ);
			int maxZ = Math.max(e.z, e.maxZ);
			boolean overlap =
				boxMinX <= maxX && boxMaxX >= minX && boxMinY <= maxY
					&& boxMaxY >= minY && boxMinZ <= maxZ && boxMaxZ >= minZ;
			if(overlap)
				return true;
		}
		
		return false;
	}
	
	private void renderTracers(PoseStack matrixStack, float partialTicks)
	{
		boolean workstationEnabled = false;
		boolean redstoneEnabled = false;
		boolean redstoneActiveOnly = false;
		try
		{
			var hax = net.wurstclient.WurstClient.INSTANCE.getHax();
			workstationEnabled = hax.workstationEspHack != null
				&& hax.workstationEspHack.isEnabled();
			redstoneEnabled =
				hax.redstoneEspHack != null && hax.redstoneEspHack.isEnabled();
			redstoneActiveOnly = hax.redstoneEspHack != null
				&& hax.redstoneEspHack.isActiveOnlyMode();
		}catch(Throwable ignored)
		{}
		
		ChestSearchHack csh = null;
		boolean hideOpenedTracers = false;
		if(!openedChests.isEmpty())
		{
			try
			{
				csh = net.wurstclient.WurstClient.INSTANCE
					.getHax().chestSearchHack;
				hideOpenedTracers =
					csh != null && csh.shouldHideOpenedChestTracers();
			}catch(Throwable ignored)
			{
				csh = null;
				hideOpenedTracers = false;
			}
		}
		
		String curDimFull = null;
		String curDim = null;
		if(hideOpenedTracers)
		{
			curDimFull = MC.level == null ? "overworld"
				: MC.level.dimension().identifier().toString();
			curDim = MC.level == null ? "overworld"
				: MC.level.dimension().identifier().getPath();
		}
		
		boolean applyEnvFilters = MC.level != null && !preFilteredEnv
			&& (isSpawnerFilterActive() || filterTrialChambers.isChecked()
				|| filterVillages.isChecked());
		
		for(ChestEspGroup group : groups.allGroups)
		{
			if(!group.isEnabled())
				continue;
			
			if(workstationEnabled
				&& (group == groups.crafters || group == groups.furnaces))
				continue;
			if(redstoneEnabled && !redstoneActiveOnly
				&& (group == groups.droppers || group == groups.dispensers
					|| group == groups.hoppers))
				continue;
			
			List<AABB> boxes = group.getBoxes();
			List<AABB> buriedBoxes = java.util.Collections.emptyList();
			if(group instanceof ChestEspBlockGroup bg)
				buriedBoxes = bg.getBuriedBoxes();
			
			if(MC.level != null && onlyBuried.isChecked() && buriedBoxes != null
				&& !buriedBoxes.isEmpty())
			{
				buriedBoxes = filterToChestShulkerBarrelBoxes(buriedBoxes);
			}
			
			if(onlyBuried.isChecked() && MC.level != null
				&& (buriedBoxes == null || buriedBoxes.isEmpty())
				&& boxes != null && !boxes.isEmpty())
			{
				buriedBoxes =
					computeBuriedBoxesByAboveForChestShulkerBarrel(boxes);
			}
			
			if(applyEnvFilters)
			{
				if(boxes != null && !boxes.isEmpty())
					boxes = filterBoxesByEnvironment(boxes);
				if(buriedBoxes != null && !buriedBoxes.isEmpty())
					buriedBoxes = filterBoxesByEnvironment(buriedBoxes);
			}
			
			if(onlyBuried.isChecked())
				boxes = buriedBoxes;
			
			if(boxes == null || boxes.isEmpty())
				continue;
			
			List<Vec3> ends;
			if(hideOpenedTracers)
			{
				java.util.ArrayList<Vec3> filtered =
					new java.util.ArrayList<>();
				for(AABB box : boxes)
				{
					if(isRecordedChest(box, curDimFull, curDim))
						continue;
					
					filtered.add(box.getCenter());
				}
				if(filtered.isEmpty())
					continue;
				
				ends = filtered;
			}else
			{
				ends = boxes.stream().map(AABB::getCenter).toList();
			}
			int color = group.getColorI(tracerAlpha.getValueI());
			
			RenderUtils.drawTracers("chestesp", matrixStack, partialTicks, ends,
				color, false);
		}
	}
	
	private void handleBlockUpdate(BlockPos pos, BlockState newState)
	{
		if(MC.level == null || pos == null || newState == null)
			return;
		
		long now = System.currentTimeMillis();
		BlockState oldState = MC.level.getBlockState(pos);
		boolean oldContainer = isContainerBlock(oldState);
		boolean newContainer = isContainerBlock(newState);
		
		if(oldContainer || newContainer)
			synchronized(antiEspLock)
			{
				lastBlockUpdateAt.put(pos.immutable(), now);
			}
		
		if(!oldContainer && newContainer)
		{
			double distance = MC.player == null ? 0
				: MC.player.position().distanceTo(Vec3.atCenterOf(pos));
			if(distance > 5)
				flagAntiEsp("fake-replacement",
					"Container block appeared via block update at "
						+ formatPos(pos) + " (" + formatBlock(oldState) + " -> "
						+ formatBlock(newState) + ")");
		}
	}
	
	private void handleBlockEntityDataPacket(ClientboundBlockEntityDataPacket p)
	{
		if(MC.level == null)
			return;
		
		long now = System.currentTimeMillis();
		BlockPos pos = p.getPos().immutable();
		BlockState state = MC.level.getBlockState(pos);
		if(!isContainerBlock(state))
			return;
		
		boolean lateWithoutUpdate;
		boolean burstDetected;
		int burstSize;
		synchronized(antiEspLock)
		{
			Long missingAt = missingContainerAt.get(pos);
			Long updatedAt = lastBlockUpdateAt.get(pos);
			boolean missingRecently =
				missingAt != null && now - missingAt <= CHUNK_SCAN_EXPIRY_MS;
			boolean noRecentUpdate =
				updatedAt == null || now - updatedAt > BLOCK_UPDATE_GRACE_MS;
			lateWithoutUpdate = missingRecently && noRecentUpdate;
			
			long sinceLast = now - lastContainerBePacketMs;
			if(lastContainerBePacketMs > 0 && sinceLast > BURST_QUIET_MS)
			{
				burstStartMs = now;
				blockEntityBurstTimes.clear();
			}
			lastContainerBePacketMs = now;
			
			blockEntityBurstTimes.addLast(now);
			while(!blockEntityBurstTimes.isEmpty()
				&& now - blockEntityBurstTimes.peekFirst() > BURST_WINDOW_MS)
				blockEntityBurstTimes.removeFirst();
			
			burstSize = blockEntityBurstTimes.size();
			burstDetected =
				burstStartMs > 0 && now - burstStartMs <= BURST_WINDOW_MS
					&& burstSize >= BURST_THRESHOLD;
		}
		
		if(lateWithoutUpdate)
			flagAntiEsp("late-block-entity", "Block entity for container at "
				+ formatPos(pos) + " arrived later without a block change");
		
		if(burstDetected)
			flagAntiEsp("be-burst",
				"Burst of " + burstSize + " block-entity packets in "
					+ (BURST_WINDOW_MS / 1000) + "s after a quiet period");
	}
	
	private void queueChunkScan(ChunkPos chunkPos)
	{
		if(chunkPos == null)
			return;
		
		long key = chunkKey(chunkPos);
		synchronized(antiEspLock)
		{
			if(!queuedChunkScans.add(key))
				return;
			
			chunkScanQueue.addLast(new ChunkScanRequest(chunkPos, key,
				System.currentTimeMillis()));
		}
	}
	
	private void processQueuedChunkScans(int maxPerTick)
	{
		if(MC.level == null || maxPerTick <= 0)
			return;
		
		long now = System.currentTimeMillis();
		for(int i = 0; i < maxPerTick; i++)
		{
			ChunkScanRequest request;
			synchronized(antiEspLock)
			{
				request = chunkScanQueue.pollFirst();
				if(request != null)
					queuedChunkScans.remove(request.key());
			}
			if(request == null)
				return;
			
			if(now - request.queuedAt() > CHUNK_SCAN_EXPIRY_MS)
				continue;
			
			scanChunk(request.chunkPos());
		}
	}
	
	private void scanChunk(ChunkPos chunkPos)
	{
		if(MC.level == null || !MC.level.hasChunk(chunkPos.x, chunkPos.z))
			return;
		
		LevelChunk chunk = MC.level.getChunk(chunkPos.x, chunkPos.z);
		if(chunk == null)
			return;
		
		long now = System.currentTimeMillis();
		int minY = chunk.getMinY();
		int minSectionY = minY >> 4;
		int containerBlocks = 0;
		int withBlockEntity = 0;
		int withoutBlockEntity = 0;
		
		LevelChunkSection[] sections = chunk.getSections();
		for(int sectionIndex =
			0; sectionIndex < sections.length; sectionIndex++)
		{
			LevelChunkSection section = sections[sectionIndex];
			if(section == null || section.hasOnlyAir())
				continue;
			
			int sectionY = minSectionY + sectionIndex;
			int baseY = sectionY << 4;
			for(int lx = 0; lx < 16; lx++)
				for(int ly = 0; ly < 16; ly++)
					for(int lz = 0; lz < 16; lz++)
					{
						BlockState state = section.getStates().get(lx, ly, lz);
						if(!isContainerBlock(state))
							continue;
						
						containerBlocks++;
						BlockPos pos =
							new BlockPos(chunkPos.getMinBlockX() + lx,
								baseY + ly, chunkPos.getMinBlockZ() + lz);
						BlockEntity be = chunk.getBlockEntity(pos);
						if(be == null)
						{
							withoutBlockEntity++;
							synchronized(antiEspLock)
							{
								missingContainerAt.put(pos.immutable(), now);
							}
						}else
							withBlockEntity++;
					}
		}
		
		if(withoutBlockEntity > 0)
			flagAntiEsp("missing-be",
				"Chunk " + chunkPos.x + ", " + chunkPos.z + " has "
					+ withoutBlockEntity
					+ " container blocks without block entities");
		
		if(containerBlocks >= 8 && withBlockEntity == 0)
			flagAntiEsp("chunk-te-mismatch",
				"Chunk " + chunkPos.x + ", " + chunkPos.z + " has "
					+ containerBlocks
					+ " container blocks but 0 block entities");
	}
	
	private void recordContainerDiscovery(BlockEntity be)
	{
		if(MC.player == null || be == null || !isContainerBlockEntity(be))
			return;
		
		BlockPos pos = be.getBlockPos().immutable();
		double distance = MC.player.position().distanceTo(Vec3.atCenterOf(pos));
		synchronized(antiEspLock)
		{
			if(!discoveredContainers.add(pos))
				return;
			
			revealSamples.addLast(
				new RevealSample(distance, System.currentTimeMillis()));
			while(revealSamples.size() > 64)
				revealSamples.removeFirst();
		}
	}
	
	private void checkRevealPattern()
	{
		ArrayList<RevealSample> samples;
		synchronized(antiEspLock)
		{
			if(revealSamples.size() < REVEAL_MIN_SAMPLES)
				return;
			
			samples = new ArrayList<>(revealSamples);
		}
		
		double sum = 0;
		for(RevealSample sample : samples)
			sum += sample.distance();
		
		double mean = sum / samples.size();
		double variance = 0;
		for(RevealSample sample : samples)
		{
			double d = sample.distance() - mean;
			variance += d * d;
		}
		double stddev = Math.sqrt(variance / samples.size());
		
		boolean suspiciousRadius = mean >= 12 && mean <= 32 && stddev <= 2.8;
		if(suspiciousRadius)
			flagAntiEsp("reveal-radius",
				"Containers are consistently discovered at ~"
					+ String.format(java.util.Locale.ROOT, "%.1f", mean)
					+ " blocks (stddev "
					+ String.format(java.util.Locale.ROOT, "%.1f", stddev)
					+ ")");
	}
	
	private void pruneAntiEspState()
	{
		long now = System.currentTimeMillis();
		synchronized(antiEspLock)
		{
			missingContainerAt.entrySet()
				.removeIf(e -> now - e.getValue() > CHUNK_SCAN_EXPIRY_MS);
			lastBlockUpdateAt.entrySet()
				.removeIf(e -> now - e.getValue() > CHUNK_SCAN_EXPIRY_MS);
			revealSamples.removeIf(
				sample -> now - sample.timestamp() > REVEAL_SAMPLE_EXPIRY_MS);
		}
	}
	
	private void flagAntiEsp(String key, String message)
	{
		boolean showAlert;
		synchronized(antiEspLock)
		{
			long now = System.currentTimeMillis();
			Long cooldown = antiEspCooldowns.get(key);
			if(cooldown != null && now - cooldown < 8000)
				return;
			
			antiEspCooldowns.put(key, now);
			antiEspSuspicious = true;
			antiEspSignals = Math.min(antiEspSignals + 1, 999);
			showAlert = antiEspAlerts.isChecked();
		}
		
		if(showAlert)
			ChatUtils.warning("ChestESP Anti-ESP: " + message);
	}
	
	private void resetAntiEspState()
	{
		synchronized(antiEspLock)
		{
			missingContainerAt.clear();
			lastBlockUpdateAt.clear();
			discoveredContainers.clear();
			revealSamples.clear();
			blockEntityBurstTimes.clear();
			chunkScanQueue.clear();
			queuedChunkScans.clear();
			antiEspCooldowns.clear();
			lastContainerBePacketMs = 0L;
			burstStartMs = -1L;
			antiEspSuspicious = false;
			antiEspSignals = 0;
		}
	}
	
	private boolean isContainerBlockEntity(BlockEntity be)
	{
		return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity
			|| be instanceof HopperBlockEntity
			|| be instanceof DispenserBlockEntity || isShulkerBlockEntity(be)
			|| be instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity
			|| be instanceof net.minecraft.world.level.block.entity.BeaconBlockEntity;
	}
	
	private boolean isContainerBlock(BlockState state)
	{
		return state != null && isContainerBlock(state.getBlock());
	}
	
	private boolean isContainerBlock(Block block)
	{
		if(block == null)
			return false;
		
		return block instanceof ChestBlock || block instanceof BarrelBlock
			|| block instanceof ShulkerBoxBlock || block instanceof HopperBlock
			|| block instanceof DispenserBlock || block == Blocks.DROPPER
			|| block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE
			|| block == Blocks.SMOKER || block == Blocks.BEACON
			|| block == Blocks.ENDER_CHEST || block == Blocks.CRAFTER;
	}
	
	private static long chunkKey(ChunkPos pos)
	{
		return ((long)pos.x << 32) ^ (pos.z & 0xFFFFFFFFL);
	}
	
	private static String formatPos(BlockPos pos)
	{
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
	
	private static String formatBlock(BlockState state)
	{
		if(state == null)
			return "null";
		
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}
	
	@Override
	public String getRenderName()
	{
		String base = getName();
		if(antiEspDetection.isChecked())
		{
			boolean suspicious;
			int signals;
			synchronized(antiEspLock)
			{
				suspicious = antiEspSuspicious;
				signals = antiEspSignals;
			}
			
			if(suspicious)
				base += " [AntiESP:" + signals + "]";
		}
		if(showCountInHackList.isChecked() && foundCount > 0)
			return base + " [" + foundCount + "]";
		return base;
	}
	
	private void refreshEnvironmentalCaches()
	{
		long now = System.currentTimeMillis();
		
		if(MC.level == null)
		{
			cachedTrialSpawners = List.of();
			cachedSpawners = List.of();
			cachedVillagerPositions = List.of();
			cachedGolemPositions = List.of();
			lastEnvironmentRefreshAnchor = null;
			lastEnvironmentRefreshDimension = null;
			environmentalCachesDirty = true;
			return;
		}
		
		BlockPos currentAnchor =
			MC.player != null ? MC.player.blockPosition() : BlockPos.ZERO;
		String currentDimension = MC.level.dimension().identifier().toString();
		boolean dimensionChanged = lastEnvironmentRefreshDimension == null
			|| !lastEnvironmentRefreshDimension.equals(currentDimension);
		boolean movedSignificantly = lastEnvironmentRefreshAnchor == null
			|| lastEnvironmentRefreshAnchor.distSqr(currentAnchor) >= 4.0;
		boolean refreshByTime =
			now - lastEnvironmentalRefreshMs >= ENVIRONMENT_CACHE_REFRESH_MS;
		
		// Refresh immediately when environment context changes around the
		// player, otherwise keep periodic refresh to avoid unnecessary scans.
		if(!environmentalCachesDirty && !dimensionChanged && !movedSignificantly
			&& !refreshByTime)
			return;
		
		lastEnvironmentalRefreshMs = now;
		lastEnvironmentRefreshAnchor = currentAnchor.immutable();
		lastEnvironmentRefreshDimension = currentDimension;
		
		if(isSpawnerFilterActive())
			cachedSpawners = collectSpawnerPositions();
		else
			cachedSpawners = List.of();
		
		if(filterTrialChambers.isChecked())
			cachedTrialSpawners = collectTrialSpawnerPositions();
		else
			cachedTrialSpawners = List.of();
		
		if(filterVillages.isChecked())
		{
			cachedVillagerPositions = collectEntityPositions(Villager.class);
			cachedGolemPositions = collectEntityPositions(IronGolem.class);
		}else
		{
			cachedVillagerPositions = List.of();
			cachedGolemPositions = List.of();
		}
		
		// Environmental context changes as chunks/entities stream in. Reset
		// per-position filter cache so stale "visible" entries don't linger.
		envFilterCache.clear();
		environmentalCachesDirty = false;
	}
	
	private List<BlockPos> collectTrialSpawnerPositions()
	{
		return ChunkUtils.getLoadedBlockEntities()
			.filter(be -> be instanceof TrialSpawnerBlockEntity)
			.map(BlockEntity::getBlockPos).map(BlockPos::immutable)
			.collect(Collectors.toList());
	}
	
	private List<BlockPos> collectSpawnerPositions()
	{
		return ChunkUtils.getLoadedBlockEntities()
			.filter(be -> be instanceof SpawnerBlockEntity)
			.map(BlockEntity::getBlockPos).map(BlockPos::immutable)
			.collect(Collectors.toList());
	}
	
	private boolean isShulkerBlockEntity(BlockEntity be)
	{
		if(be == null)
			return false;
		
		if(be instanceof net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)
			return true;
		
		BlockState state = be.getBlockState();
		return state != null && state.getBlock() instanceof ShulkerBoxBlock;
	}
	
	private <T extends Entity> List<Vec3> collectEntityPositions(Class<T> type)
	{
		if(MC.level == null)
			return List.of();
		
		java.util.ArrayList<Vec3> out = new java.util.ArrayList<>();
		for(Entity e : MC.level.entitiesForRendering())
		{
			if(e == null || e.isRemoved())
				continue;
			
			if(type.isInstance(e))
				out.add(Vec3.atCenterOf(e.blockPosition()));
		}
		
		return out;
	}
	
	private List<AABB> filterBoxesByEnvironment(List<AABB> boxes)
	{
		if(MC.level == null || boxes == null || boxes.isEmpty())
			return boxes;
		
		java.util.ArrayList<AABB> out = new java.util.ArrayList<>(boxes.size());
		for(AABB box : boxes)
		{
			if(box == null)
				continue;
			
			BlockPos singleChestPos = getSingleChestPosIfApplicable(box);
			if(singleChestPos == null)
			{
				BlockPos barrelPos = getSingleBarrelPosIfApplicable(box);
				if(barrelPos == null)
				{
					// Trial chamber ignore for hoppers
					BlockPos hopperPos = getSingleHopperPosIfApplicable(box);
					if(hopperPos != null)
					{
						if(filterTrialChambers.isChecked()
							&& isInTrialChamberArea(hopperPos))
							continue;
						out.add(box);
						continue;
					}
					
					// Trial chamber ignore for dispensers
					BlockPos dispenserPos =
						getSingleDispenserPosIfApplicable(box);
					if(dispenserPos != null)
					{
						if(filterTrialChambers.isChecked()
							&& isInTrialChamberArea(dispenserPos))
							continue;
						out.add(box);
						continue;
					}
					
					out.add(box);
					continue;
				}
				
				if(filterTrialChambers.isChecked()
					&& isInTrialChamberArea(barrelPos))
					continue;
				
				out.add(box);
				continue;
			}
			
			if(isSpawnerFilterActive() && isNearSpawner(singleChestPos, 7))
				continue;
			
			if(filterTrialChambers.isChecked()
				&& isInTrialChamberArea(singleChestPos))
				continue;
			
			if(filterVillages.isChecked()
				&& isLikelyVillageChest(singleChestPos))
				continue;
			
			out.add(box);
		}
		
		return out;
	}
	
	// Fast pre-filter used in onUpdate() to keep filtered blocks from ever
	// entering render lists. Mirrors the logic of filterBoxesByEnvironment
	// but operates directly on BlockEntity types for speed.
	private boolean shouldFilterBlockEntityByEnvironment(BlockEntity be)
	{
		BlockPos key = be.getBlockPos().immutable();
		long now = System.currentTimeMillis();
		EnvFilterCacheEntry cached = envFilterCache.get(key);
		if(cached != null
			&& now - cached.timestampMs() <= ENV_FILTER_CACHE_TTL_MS)
			return cached.filtered();
		
		boolean filtered = computeShouldFilterBlockEntityByEnvironment(be);
		envFilterCache.put(key, new EnvFilterCacheEntry(filtered, now));
		return filtered;
	}
	
	private boolean computeShouldFilterBlockEntityByEnvironment(BlockEntity be)
	{
		if(MC.level == null)
			return false;
		
		BlockPos pos = be.getBlockPos();
		BlockState state = be.getBlockState();
		
		// Chest-specific single/double handling
		if(be instanceof ChestBlockEntity)
		{
			// Be strict: environment filters apply only to confirmed single
			// chests. If chest type can't be read, don't filter it.
			if(state == null || !state.hasProperty(ChestBlock.TYPE))
				return false;
			
			if(state.getValue(ChestBlock.TYPE) != ChestType.SINGLE)
				return false;
			
			if(isSpawnerFilterActive() && isNearSpawner(pos, 7))
				return true;
			if(filterTrialChambers.isChecked() && isInTrialChamberArea(pos))
				return true;
			if(filterVillages.isChecked() && isLikelyVillageChest(pos))
				return true;
			
			return false;
		}
		
		// Barrels in Trial Chambers
		if(be instanceof BarrelBlockEntity)
		{
			if(filterTrialChambers.isChecked() && isInTrialChamberArea(pos))
				return true;
			return false;
		}
		
		// Hoppers in Trial Chambers
		if(be instanceof HopperBlockEntity)
		{
			if(filterTrialChambers.isChecked() && isInTrialChamberArea(pos))
				return true;
			return false;
		}
		
		// Dispensers in Trial Chambers
		if(be instanceof DispenserBlockEntity)
		{
			if(filterTrialChambers.isChecked() && isInTrialChamberArea(pos))
				return true;
			return false;
		}
		
		return false;
	}
	
	private BlockPos getSingleChestPosIfApplicable(AABB box)
	{
		if(MC.level == null || box == null)
			return null;
		
		int boxMinX = (int)Math.floor(box.minX + 1e-6);
		int boxMaxX = (int)Math.floor(box.maxX - 1e-6);
		int boxMinY = (int)Math.floor(box.minY + 1e-6);
		int boxMaxY = (int)Math.floor(box.maxY - 1e-6);
		int boxMinZ = (int)Math.floor(box.minZ + 1e-6);
		int boxMaxZ = (int)Math.floor(box.maxZ - 1e-6);
		
		BlockPos foundChest = null;
		int chestCount = 0;
		
		for(int x = boxMinX; x <= boxMaxX; x++)
		{
			for(int y = boxMinY; y <= boxMaxY; y++)
			{
				for(int z = boxMinZ; z <= boxMaxZ; z++)
				{
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = MC.level.getBlockState(pos);
					if(state == null)
						continue;
					
					Block b = state.getBlock();
					if(b instanceof ShulkerBoxBlock)
						return null;
					
					if(b instanceof ChestBlock)
					{
						chestCount++;
						if(foundChest == null)
							foundChest = pos;
						
						if(!state.hasProperty(ChestBlock.TYPE))
							return null;
						
						ChestType t = state.getValue(ChestBlock.TYPE);
						if(t != ChestType.SINGLE)
							return null;
						
						if(chestCount > 1)
							return null;
					}
				}
			}
		}
		
		return foundChest;
	}
	
	private BlockPos getSingleBarrelPosIfApplicable(AABB box)
	{
		if(MC.level == null || box == null)
			return null;
		
		int boxMinX = (int)Math.floor(box.minX + 1e-6);
		int boxMaxX = (int)Math.floor(box.maxX - 1e-6);
		int boxMinY = (int)Math.floor(box.minY + 1e-6);
		int boxMaxY = (int)Math.floor(box.maxY - 1e-6);
		int boxMinZ = (int)Math.floor(box.minZ + 1e-6);
		int boxMaxZ = (int)Math.floor(box.maxZ - 1e-6);
		
		BlockPos foundBarrel = null;
		int barrelCount = 0;
		
		for(int x = boxMinX; x <= boxMaxX; x++)
		{
			for(int y = boxMinY; y <= boxMaxY; y++)
			{
				for(int z = boxMinZ; z <= boxMaxZ; z++)
				{
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = MC.level.getBlockState(pos);
					if(state == null)
						continue;
					
					if(state.getBlock() instanceof BarrelBlock)
					{
						barrelCount++;
						if(foundBarrel == null)
							foundBarrel = pos;
						
						if(barrelCount > 1)
							return null;
					}
				}
			}
		}
		
		return foundBarrel;
	}
	
	private boolean shouldSkipSingleChest(BlockEntity be)
	{
		if(!doubleChestsOnly.isChecked() || !(be instanceof ChestBlockEntity))
			return false;
		
		BlockState state = be.getBlockState();
		if(!state.hasProperty(ChestBlock.TYPE))
			return false;
		
		return state.getValue(ChestBlock.TYPE) == ChestType.SINGLE;
	}
	
	private boolean isNearSpawner(BlockPos center, int range)
	{
		if(!isSpawnerFilterActive())
			return false;
		
		if(isNearCachedSpawner(center, range))
			return true;
			
		// If we already have a stable non-empty spawner cache and no nearby
		// hit,
		// trust it to avoid expensive per-chest block scans.
		if(!environmentalCachesDirty && !cachedSpawners.isEmpty())
			return false;
			
		// Fallback to block-state scan so newly streamed areas don't briefly
		// render near-spawner chests before block-entity caches catch up.
		return isNearSpawnerBlock(center, range);
	}
	
	private boolean isSpawnerFilterActive()
	{
		return filterNearSpawners.isChecked() && isInOverworld();
	}
	
	private boolean isInOverworld()
	{
		return MC.level != null && MC.level.dimension().equals(Level.OVERWORLD);
	}
	
	private boolean isNearCachedSpawner(BlockPos center, int range)
	{
		if(cachedSpawners.isEmpty())
			return false;
		
		double rangeSq = range * range;
		Vec3 centerVec = Vec3.atCenterOf(center);
		for(BlockPos pos : cachedSpawners)
		{
			if(Vec3.atCenterOf(pos).distanceToSqr(centerVec) <= rangeSq)
				return true;
		}
		
		return false;
	}
	
	private boolean isNearSpawnerBlock(BlockPos center, int range)
	{
		if(MC.level == null)
			return false;
		
		int minX = center.getX() - range;
		int maxX = center.getX() + range;
		int minY = center.getY() - range;
		int maxY = center.getY() + range;
		int minZ = center.getZ() - range;
		int maxZ = center.getZ() + range;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		
		for(int x = minX; x <= maxX; x++)
			for(int y = minY; y <= maxY; y++)
				for(int z = minZ; z <= maxZ; z++)
				{
					cursor.set(x, y, z);
					var state = MC.level.getBlockState(cursor);
					if(state.is(Blocks.SPAWNER))
						return true;
					
					String idPath = BuiltInRegistries.BLOCK
						.getKey(state.getBlock()).getPath();
					if(idPath.contains("trial_spawner"))
						return true;
				}
			
		return false;
	}
	
	private boolean isInTrialChamberArea(BlockPos pos)
	{
		int y = pos.getY();
		// Trial chambers typically generate underground; keep a broad sanity
		// window
		if(y < -64 || y > 48)
			return false;
			
		// Primary signal: container is anchored on characteristic Trial
		// Chamber blocks (prevents unrelated underground chest false
		// positives).
		boolean anchoredOnTrialBlock = isOnLikelyTrialSupportBlock(pos);
		if(!anchoredOnTrialBlock)
			return false;
			
		// Secondary signals: nearby spawner or compact local block palette.
		// Keep ranges conservative to avoid bleeding into nearby structures.
		if(isNearTrialSpawner(pos, 96))
			return true;
		
		return isNearLikelyTrialBlocks(pos, 4);
	}
	
	private boolean isNearLikelyTrialBlocks(BlockPos center, int range)
	{
		if(MC.level == null)
			return false;
		
		return BlockUtils.getAllInBoxStream(center, range).anyMatch(pos -> {
			Block b = BlockUtils.getBlock(pos);
			return isLikelyTrialBlock(b);
		});
	}
	
	private boolean isOnLikelyTrialSupportBlock(BlockPos pos)
	{
		if(MC.level == null)
			return false;
		
		// Primary check: the supporting block directly below.
		BlockState supportState = MC.level.getBlockState(pos.below());
		if(isLikelyTrialSupportBlock(supportState))
			return true;
		
		// Some trial containers can be embedded against trial palette blocks.
		for(BlockPos nearby : new BlockPos[]{pos.north(), pos.south(),
			pos.east(), pos.west()})
		{
			if(isLikelyTrialSupportBlock(MC.level.getBlockState(nearby)))
				return true;
		}
		
		return false;
	}
	
	private boolean isLikelyTrialBlock(Block block)
	{
		if(block == null)
			return false;
		
		String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
		if(path.contains("trial_spawner") || path.contains("vault"))
			return true;
		
		return isLikelyTrialSupportPath(path);
	}
	
	private boolean isLikelyTrialSupportBlock(BlockState state)
	{
		if(state == null)
			return false;
		
		if(isWaxedCopper(state))
			return true;
		
		String path =
			BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return isLikelyTrialSupportPath(path);
	}
	
	private boolean isLikelyTrialSupportPath(String path)
	{
		if(path == null || path.isEmpty())
			return false;
		
		// Common Trial Chamber palette and copper fixtures.
		return path.contains("tuff_bricks") || path.contains("chiseled_tuff")
			|| path.contains("polished_tuff") || path.contains("tuff_stairs")
			|| path.contains("tuff_slab") || path.contains("tuff_wall")
			|| path.contains("copper_bulb") || path.contains("waxed_copper");
	}
	
	// Backward-compatibility alias for any callers still using the old name
	@Deprecated
	private boolean isTrialChamberChest(BlockPos pos)
	{
		return isInTrialChamberArea(pos);
	}
	
	private BlockPos getSingleHopperPosIfApplicable(AABB box)
	{
		if(MC.level == null || box == null)
			return null;
		
		int boxMinX = (int)Math.floor(box.minX + 1e-6);
		int boxMaxX = (int)Math.floor(box.maxX - 1e-6);
		int boxMinY = (int)Math.floor(box.minY + 1e-6);
		int boxMaxY = (int)Math.floor(box.maxY - 1e-6);
		int boxMinZ = (int)Math.floor(box.minZ + 1e-6);
		int boxMaxZ = (int)Math.floor(box.maxZ - 1e-6);
		
		BlockPos found = null;
		int count = 0;
		
		for(int x = boxMinX; x <= boxMaxX; x++)
			for(int y = boxMinY; y <= boxMaxY; y++)
				for(int z = boxMinZ; z <= boxMaxZ; z++)
				{
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = MC.level.getBlockState(pos);
					if(state == null)
						continue;
					if(state.getBlock() instanceof HopperBlock)
					{
						count++;
						if(found == null)
							found = pos;
						if(count > 1)
							return null;
					}
				}
			
		return found;
	}
	
	private BlockPos getSingleDispenserPosIfApplicable(AABB box)
	{
		if(MC.level == null || box == null)
			return null;
		
		int boxMinX = (int)Math.floor(box.minX + 1e-6);
		int boxMaxX = (int)Math.floor(box.maxX - 1e-6);
		int boxMinY = (int)Math.floor(box.minY + 1e-6);
		int boxMaxY = (int)Math.floor(box.maxY - 1e-6);
		int boxMinZ = (int)Math.floor(box.minZ + 1e-6);
		int boxMaxZ = (int)Math.floor(box.maxZ - 1e-6);
		
		BlockPos found = null;
		int count = 0;
		
		for(int x = boxMinX; x <= boxMaxX; x++)
			for(int y = boxMinY; y <= boxMaxY; y++)
				for(int z = boxMinZ; z <= boxMaxZ; z++)
				{
					BlockPos pos = new BlockPos(x, y, z);
					BlockState state = MC.level.getBlockState(pos);
					if(state == null)
						continue;
					if(state.getBlock() instanceof DispenserBlock)
					{
						count++;
						if(found == null)
							found = pos;
						if(count > 1)
							return null;
					}
				}
			
		return found;
	}
	
	private boolean isNearWaxedCopper(BlockPos center, int range)
	{
		if(MC.level == null)
			return false;
		
		return BlockUtils.getAllInBoxStream(center, range)
			.anyMatch(pos -> isWaxedCopper(MC.level.getBlockState(pos)));
	}
	
	private boolean isWaxedCopper(BlockState state)
	{
		if(state == null)
			return false;
		
		if(state.is(WAXED_COPPER_BLOCKS_TAG))
			return true;
		
		String idPath =
			BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return idPath.contains("waxed") && idPath.contains("copper");
	}
	
	private boolean isNearTrialSpawner(BlockPos center, int range)
	{
		if(cachedTrialSpawners.isEmpty())
			return false;
		
		double rangeSq = range * range;
		Vec3 centerVec = Vec3.atCenterOf(center);
		return cachedTrialSpawners.stream().anyMatch(
			pos -> Vec3.atCenterOf(pos).distanceToSqr(centerVec) <= rangeSq);
	}
	
	private boolean isLikelyVillageChest(BlockPos pos)
	{
		// Use static structure signals first so village chests are filtered
		// even before villagers/golems are tracked on the client.
		if(!hasDoorNearby(pos, 4))
			return false;
		
		boolean hayCluster = hasHayBaleCluster(pos, 6);
		
		if(hayCluster)
			return true;
		
		if(hasGlassPaneCluster(pos, 4, 1))
			return true;
			
		// Dynamic hints for less common village layouts that do not match the
		// above static block signatures.
		return isEntityWithinRange(cachedVillagerPositions, pos, 24)
			|| isEntityWithinRange(cachedGolemPositions, pos, 24);
	}
	
	private boolean isEntityWithinRange(List<Vec3> positions, BlockPos center,
		double range)
	{
		if(positions.isEmpty())
			return false;
		
		double rangeSq = range * range;
		Vec3 centerVec = Vec3.atCenterOf(center);
		return positions.stream()
			.anyMatch(pos -> pos.distanceToSqr(centerVec) <= rangeSq);
	}
	
	private boolean hasHayBaleCluster(BlockPos center, int range)
	{
		if(MC.level == null)
			return false;
		
		long count = BlockUtils.getAllInBoxStream(center, range)
			.filter(pos -> BlockUtils.getBlock(pos) == Blocks.HAY_BLOCK)
			.limit(16).count();
		return count >= 4;
	}
	
	private boolean hasDoorNearby(BlockPos center, int range)
	{
		if(MC.level == null)
			return false;
		
		return BlockUtils.getAllInBoxStream(center, range)
			.anyMatch(pos -> BlockUtils.getBlock(pos) instanceof DoorBlock);
	}
	
	private boolean hasGlassPaneCluster(BlockPos center, int range,
		int requiredCount)
	{
		if(MC.level == null)
			return false;
		
		long glassCount = BlockUtils.getAllInBoxStream(center, range)
			.filter(pos -> isGlassPane(BlockUtils.getBlock(pos)))
			.limit(requiredCount).count();
		return glassCount >= requiredCount;
	}
	
	private boolean isGlassPane(Block block)
	{
		String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
		return path.contains("glass_pane");
	}
	
	private void invalidateEnvFilterCacheIfNeeded()
	{
		int signature = 0;
		signature = 31 * signature + (filterNearSpawners.isChecked() ? 1 : 0);
		signature = 31 * signature + (filterTrialChambers.isChecked() ? 1 : 0);
		signature = 31 * signature + (filterVillages.isChecked() ? 1 : 0);
		signature = 31 * signature + (onlyAboveGround.isChecked() ? 1 : 0);
		signature = 31 * signature + aboveGroundY.getValueI();
		
		if(signature != lastEnvFilterSignature)
		{
			envFilterCache.clear();
			lastEnvironmentalRefreshMs = 0L;
			lastEnvironmentRefreshAnchor = null;
			lastEnvironmentRefreshDimension = null;
			environmentalCachesDirty = true;
			lastEnvFilterSignature = signature;
		}
	}
	
	private record RevealSample(double distance, long timestamp)
	{}
	
	private record EnvFilterCacheEntry(boolean filtered, long timestampMs)
	{}
	
	private record ChunkScanRequest(ChunkPos chunkPos, long key, long queuedAt)
	{}
}
