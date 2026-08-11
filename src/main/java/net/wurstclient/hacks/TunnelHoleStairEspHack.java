/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TorchBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkUtils;

@SearchTags({"dig spot esp", "digspot esp", "hole esp", "tunnel esp",
	"stairs esp"})
public final class TunnelHoleStairEspHack extends Hack
	implements UpdateListener, RenderListener,
	CameraTransformViewBobbingListener, PacketInputListener
{
	private static final Direction[] CARDINALS =
		{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
	private static final Direction[] TUNNEL_DIRECTIONS =
		{Direction.EAST, Direction.SOUTH};
	
	private final EspStyleSetting style = new EspStyleSetting();
	private final EnumSetting<DetectionMode> detectionMode = new EnumSetting<>(
		"Detection mode", "Choose what TunnelHoleStairESP should detect.",
		DetectionMode.values(), DetectionMode.ALL);
	private final CheckboxSetting stickyArea =
		new CheckboxSetting("Sticky area",
			"Off: Re-centers every chunk to match ESP drop-off.\n"
				+ "On: Keeps results anchored so you can path back to them.",
			false);
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to scan for dug spots.");
	private final SliderSetting chunksPerTick =
		new SliderSetting("Chunks per tick",
			"How many chunks to scan every tick.\n"
				+ "Higher values update faster but can cost more FPS.",
			2, 1, 16, 1, ValueDisplay.INTEGER);
	private final SliderSetting scanTimeBudgetMs = new SliderSetting(
		"Scan time budget",
		"Hard CPU budget for scanning each tick. Lower values = smoother FPS,\n"
			+ "higher values = faster detection updates.",
		2, 1, 20, 1, ValueDisplay.INTEGER.withSuffix(" ms"));
	private final SliderSetting refreshInterval = new SliderSetting(
		"Refresh interval",
		"Periodically re-queue all chunks in range so stale detections refresh\n"
			+ "without toggling the hack.",
		10, 0, 60, 1, ValueDisplay.INTEGER.withSuffix(" s"));
	private final CheckboxSetting adaptiveMovementScan = new CheckboxSetting(
		"Adaptive movement scan",
		"Adjusts scan area and throughput based on your movement speed.\n"
			+ "Standing still keeps your selected area. Moving/flying tightens area\n"
			+ "and boosts nearby scan speed.",
		true);
	private final SliderSetting nearbyPriorityRadius = new SliderSetting(
		"Nearby priority radius",
		"Always prioritize chunks in this radius around you each tick.\n"
			+ "Higher values discover nearby tunnels faster, but can cost more CPU.",
		2, 0, 12, 1, ValueDisplay.INTEGER.withSuffix(" chunks"));
	private final CheckboxSetting airOnly = new CheckboxSetting("Air only",
		"Only treat pure air as passable. Turning this off will also treat\n"
			+ "other non-solid blocks as passable.",
		true);
	private final SliderSetting minYOffset = new SliderSetting("Min Y offset",
		"Scan this many blocks above the world's minimum build height.", 0, 0,
		319, 1, ValueDisplay.INTEGER);
	private final SliderSetting maxYOffset = new SliderSetting("Max Y offset",
		"Scan this many blocks below the world's maximum build height.", 0, 0,
		319, 1, ValueDisplay.INTEGER);
	private final SliderSetting minHoleDepth = new SliderSetting(
		"Min hole depth", "Minimum depth for a vertical 1x1 hole.", 4, 1, 20, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting minHoleWidth =
		new SliderSetting("Min hole width", "Minimum hole interior width.", 1,
			1, 8, 1, ValueDisplay.INTEGER);
	private final SliderSetting maxHoleWidth =
		new SliderSetting("Max hole width", "Maximum hole interior width.", 2,
			1, 8, 1, ValueDisplay.INTEGER);
	private final SliderSetting minTunnelLength = new SliderSetting(
		"Min tunnel length", "Minimum length for a straight tunnel.", 4, 2, 30,
		1, ValueDisplay.INTEGER);
	private final SliderSetting minTunnelWidth =
		new SliderSetting("Min tunnel width", "Minimum tunnel interior width.",
			1, 1, 8, 1, ValueDisplay.INTEGER);
	private final SliderSetting maxTunnelWidth =
		new SliderSetting("Max tunnel width", "Maximum tunnel interior width.",
			3, 1, 8, 1, ValueDisplay.INTEGER);
	private final SliderSetting minTunnelHeight = new SliderSetting(
		"Min tunnel height", "Minimum tunnel interior height.", 2, 1, 6, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting maxTunnelHeight = new SliderSetting(
		"Max tunnel height", "Maximum tunnel interior height.", 3, 2, 8, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting minStairLength = new SliderSetting(
		"Min stair length", "Minimum amount of staircase steps.", 4, 2, 30, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting minStairHeight = new SliderSetting(
		"Min stair height", "Minimum staircase interior height.", 2, 1, 6, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting maxStairHeight = new SliderSetting(
		"Max stair height", "Maximum staircase interior height.", 4, 2, 8, 1,
		ValueDisplay.INTEGER);
	private final CheckboxSetting detectLadders =
		new CheckboxSetting("Detect ladders",
			"Detect vertical ladder columns in this ESP module.", true);
	private final SliderSetting minLadderHeight =
		new SliderSetting("Min ladder height", "Minimum ladder column height.",
			5, 1, 64, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting detectBubbleColumns =
		new CheckboxSetting("Detect bubble columns",
			"Detect bubble columns in this ESP module.", true);
	private final SliderSetting minBubbleColumnHeight =
		new SliderSetting("Min bubble column height",
			"Minimum bubble-column height.", 4, 1, 64, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting detectWaterColumns =
		new CheckboxSetting("Detect water holes",
			"Detect enclosed water-filled holes without bubble columns.", true);
	private final SliderSetting minWaterColumnHeight = new SliderSetting(
		"Min water hole height", "Minimum water-hole column height.", 4, 1, 64,
		1, ValueDisplay.INTEGER);
	private final SliderSetting maxPerChunk =
		new SliderSetting("Max spots per chunk",
			"Maximum amount of spots to keep per chunk for each type.", 24, 4,
			256, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting tracerFlash = new CheckboxSetting(
		"Tracer flash", "Make tracers pulse with a smooth fade.", false);
	private final CheckboxSetting naturalWallsOnly = new CheckboxSetting(
		"Natural wall filter",
		"Reject shapes where too many surrounding wall blocks look"
			+ " non-terrain.\nThis helps reduce worldgen/structure false positives.",
		true);
	private final SliderSetting naturalWallRatio = new SliderSetting(
		"Natural wall ratio",
		"Minimum ratio of surrounding wall blocks that must look like natural terrain.",
		0.70, 0.25, 1.00, 0.01, ValueDisplay.PERCENTAGE);
	
	private final ColorSetting holeColor = new ColorSetting("Hole color",
		"Render color for detected holes.", new Color(255, 60, 60));
	private final ColorSetting tunnelColor = new ColorSetting("Tunnel color",
		"Render color for detected tunnels.", new Color(70, 140, 255));
	private final ColorSetting stairColor = new ColorSetting("Stair color",
		"Render color for detected staircases.", new Color(255, 90, 220));
	private final ColorSetting ladderColor = new ColorSetting("Ladder color",
		"Render color for detected ladder columns.", new Color(255, 190, 40));
	private final ColorSetting bubbleColumnColor = new ColorSetting(
		"Bubble column color", "Render color for detected bubble columns.",
		new Color(60, 220, 255));
	private final ColorSetting waterColumnColor = new ColorSetting(
		"Water hole color", "Render color for detected water-filled holes.",
		new Color(40, 120, 255));
	
	private final CheckboxSetting overworld =
		new CheckboxSetting("Overworld", true);
	private final CheckboxSetting nether = new CheckboxSetting("Nether", true);
	private final CheckboxSetting end = new CheckboxSetting("End", true);
	
	private final HashMap<ChunkPos, ChunkDetections> detectionsByChunk =
		new HashMap<>();
	private final ArrayDeque<ChunkPos> dirtyScanQueue = new ArrayDeque<>();
	private final ArrayDeque<ChunkPos> nearbyScanQueue = new ArrayDeque<>();
	private final ArrayDeque<ChunkPos> normalScanQueue = new ArrayDeque<>();
	private final ArrayDeque<ChunkPos> refreshScanQueue = new ArrayDeque<>();
	private final HashSet<ChunkPos> queuedChunks = new HashSet<>();
	private final HashMap<ChunkPos, ScanPriority> queuedPriorities =
		new HashMap<>();
	private final HashSet<ChunkPos> scannedChunks = new HashSet<>();
	private final HashMap<ChunkPos, ChunkScanState> partialScans =
		new HashMap<>();
	private final ConcurrentLinkedQueue<ChunkPos> dirtyChunkQueue =
		new ConcurrentLinkedQueue<>();
	private final java.util.Set<ChunkPos> dirtyChunkSet =
		ConcurrentHashMap.newKeySet();
	private final HashMap<Block, Boolean> naturalWallCache = new HashMap<>();
	private final HashSet<ChunkPos> areaChunkCache = new HashSet<>();
	private final HashSet<ChunkPos> loadedChunkCache = new HashSet<>();
	private final HashSet<ChunkPos> lastLoadedChunks = new HashSet<>();
	private final HashSet<ChunkPos> lastEffectiveArea = new HashSet<>();
	private final ArrayList<ChunkPos> refreshCandidates = new ArrayList<>();
	private ChunkPos cachedAreaCenter;
	private ChunkAreaSetting.ChunkArea cachedAreaSelection;
	private ResourceKey<Level> cachedAreaDimension;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	
	private final ArrayList<AABB> holeBoxes = new ArrayList<>();
	private final ArrayList<AABB> tunnelBoxes = new ArrayList<>();
	private final ArrayList<AABB> stairBoxes = new ArrayList<>();
	private final ArrayList<AABB> ladderBoxes = new ArrayList<>();
	private final ArrayList<AABB> bubbleColumnBoxes = new ArrayList<>();
	private final ArrayList<AABB> waterColumnBoxes = new ArrayList<>();
	
	private int scanConfigHash;
	private int refreshTimerTicks;
	private int refreshCursor;
	private boolean refreshSweepActive;
	private int nearbyCursor;
	private int nearbyCursorRadius = -1;
	private int highPriorityStreak;
	private int nearbyPriorityStreak;
	private int normalPriorityStreak;
	private boolean lastStickyAreaSetting;
	private Level activeLevel;
	private Vec3 lastPlayerPos;
	
	public TunnelHoleStairEspHack()
	{
		super("TunnelHoleStairESP");
		setCategory(Category.INTEL);
		addSetting(style);
		addSetting(detectionMode);
		addSetting(stickyArea);
		addSetting(area);
		addSetting(chunksPerTick);
		addSetting(scanTimeBudgetMs);
		addSetting(refreshInterval);
		addSetting(adaptiveMovementScan);
		addSetting(nearbyPriorityRadius);
		addSetting(airOnly);
		addSetting(minYOffset);
		addSetting(maxYOffset);
		addSetting(minHoleDepth);
		addSetting(minHoleWidth);
		addSetting(maxHoleWidth);
		addSetting(minTunnelLength);
		addSetting(minTunnelWidth);
		addSetting(maxTunnelWidth);
		addSetting(minTunnelHeight);
		addSetting(maxTunnelHeight);
		addSetting(minStairLength);
		addSetting(minStairHeight);
		addSetting(maxStairHeight);
		addSetting(detectLadders);
		addSetting(minLadderHeight);
		addSetting(detectBubbleColumns);
		addSetting(minBubbleColumnHeight);
		addSetting(detectWaterColumns);
		addSetting(minWaterColumnHeight);
		addSetting(maxPerChunk);
		addSetting(tracerFlash);
		addSetting(naturalWallsOnly);
		addSetting(naturalWallRatio);
		addSetting(holeColor);
		addSetting(tunnelColor);
		addSetting(stairColor);
		addSetting(ladderColor);
		addSetting(bubbleColumnColor);
		addSetting(waterColumnColor);
		addSetting(overworld);
		addSetting(nether);
		addSetting(end);
	}
	
	@Override
	public String getRenderName()
	{
		int holes = holeBoxes.size();
		int tunnels = tunnelBoxes.size();
		int stairs = stairBoxes.size();
		int ladders = ladderBoxes.size();
		int bubbles = bubbleColumnBoxes.size();
		int waters = waterColumnBoxes.size();
		if(holes + tunnels + stairs + ladders + bubbles + waters == 0)
			return getName();
		
		return getName() + " [" + holes + "H " + tunnels + "T " + stairs + "S "
			+ ladders + "L " + bubbles + "B " + waters + "W]";
	}
	
	@Override
	protected void onEnable()
	{
		scanConfigHash = getScanConfigHash();
		refreshTimerTicks = 0;
		lastAreaSelection = area.getSelected();
		activeLevel = null;
		lastPlayerPos = null;
		clearRuntimeState();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		activeLevel = null;
		lastPlayerPos = null;
		clearRuntimeState();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
		{
			// Clear stale detections while disconnecting or switching worlds.
			if(activeLevel != null || !detectionsByChunk.isEmpty()
				|| !holeBoxes.isEmpty() || !tunnelBoxes.isEmpty()
				|| !stairBoxes.isEmpty() || !ladderBoxes.isEmpty()
				|| !bubbleColumnBoxes.isEmpty() || !waterColumnBoxes.isEmpty())
			{
				activeLevel = null;
				clearRuntimeState();
			}
			return;
		}
		
		if(activeLevel != MC.level)
		{
			activeLevel = MC.level;
			refreshTimerTicks = 0;
			lastAreaSelection = area.getSelected();
			scanConfigHash = getScanConfigHash();
			lastPlayerPos = null;
			clearRuntimeState();
		}
		
		ChunkAreaSetting.ChunkArea currentAreaSelection = area.getSelected();
		
		if(currentAreaSelection != lastAreaSelection)
		{
			lastAreaSelection = currentAreaSelection;
			clearRuntimeState();
		}
		
		if(!isEnabledInCurrentDimension())
		{
			if(!detectionsByChunk.isEmpty() || !queuedChunks.isEmpty()
				|| !partialScans.isEmpty())
				clearRuntimeState();
			return;
		}
		
		int currentHash = getScanConfigHash();
		if(currentHash != scanConfigHash)
		{
			scanConfigHash = currentHash;
			clearRuntimeState();
		}
		
		ScanProfile profile = getScanProfile();
		HashSet<ChunkPos> areaChunks = getAreaChunks(profile.range);
		boolean changed = syncToArea(areaChunks);
		enqueueDirtyChunks(areaChunks);
		enqueuePeriodicRefresh(areaChunks);
		promoteNearbyChunks(areaChunks, profile.nearbyRadius);
		changed |=
			processQueuedScans(areaChunks, profile.scans, profile.budgetNs);
		
		if(changed)
			rebuildRenderCache();
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(MC.level == null || MC.player == null || !isEnabled())
			return;
		
		ChunkPos chunkPos = ChunkUtils.getAffectedChunk(event.getPacket());
		if(chunkPos != null && dirtyChunkSet.add(chunkPos))
			dirtyChunkQueue.add(chunkPos);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(style.hasBoxes())
			renderBoxes(matrixStack);
		
		if(style.hasLines())
			renderTracers(matrixStack, partialTicks);
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	private void renderBoxes(PoseStack matrixStack)
	{
		if(!holeBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrixStack, holeBoxes,
				holeColor.getColorI(0x30), false);
			RenderUtils.drawOutlinedBoxes(matrixStack, holeBoxes,
				holeColor.getColorI(0x95), false);
		}
		
		if(!tunnelBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrixStack, tunnelBoxes,
				tunnelColor.getColorI(0x30), false);
			RenderUtils.drawOutlinedBoxes(matrixStack, tunnelBoxes,
				tunnelColor.getColorI(0x95), false);
		}
		
		if(!stairBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrixStack, stairBoxes,
				stairColor.getColorI(0x30), false);
			RenderUtils.drawOutlinedBoxes(matrixStack, stairBoxes,
				stairColor.getColorI(0x95), false);
		}
		
		if(!ladderBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrixStack, ladderBoxes,
				ladderColor.getColorI(0x30), false);
			RenderUtils.drawOutlinedBoxes(matrixStack, ladderBoxes,
				ladderColor.getColorI(0x95), false);
		}
		
		if(!bubbleColumnBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrixStack, bubbleColumnBoxes,
				bubbleColumnColor.getColorI(0x30), false);
			RenderUtils.drawOutlinedBoxes(matrixStack, bubbleColumnBoxes,
				bubbleColumnColor.getColorI(0x95), false);
		}
		
		if(!waterColumnBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrixStack, waterColumnBoxes,
				waterColumnColor.getColorI(0x30), false);
			RenderUtils.drawOutlinedBoxes(matrixStack, waterColumnBoxes,
				waterColumnColor.getColorI(0x95), false);
		}
	}
	
	private void renderTracers(PoseStack matrixStack, float partialTicks)
	{
		boolean flash = tracerFlash.isChecked();
		if(!holeBoxes.isEmpty())
		{
			int color = holeColor.getColorI(0x95);
			if(flash)
				color = RenderUtils.flashColor(color);
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack,
				partialTicks, getCenters(holeBoxes), color, false);
		}
		
		if(!tunnelBoxes.isEmpty())
		{
			int color = tunnelColor.getColorI(0x95);
			if(flash)
				color = RenderUtils.flashColor(color);
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack,
				partialTicks, getCenters(tunnelBoxes), color, false);
		}
		
		if(!stairBoxes.isEmpty())
		{
			int color = stairColor.getColorI(0x95);
			if(flash)
				color = RenderUtils.flashColor(color);
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack,
				partialTicks, getCenters(stairBoxes), color, false);
		}
		
		if(!ladderBoxes.isEmpty())
		{
			int color = ladderColor.getColorI(0x95);
			if(flash)
				color = RenderUtils.flashColor(color);
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack,
				partialTicks, getCenters(ladderBoxes), color, false);
		}
		
		if(!bubbleColumnBoxes.isEmpty())
		{
			int color = bubbleColumnColor.getColorI(0x95);
			if(flash)
				color = RenderUtils.flashColor(color);
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack,
				partialTicks, getCenters(bubbleColumnBoxes), color, false);
		}
		
		if(!waterColumnBoxes.isEmpty())
		{
			int color = waterColumnColor.getColorI(0x95);
			if(flash)
				color = RenderUtils.flashColor(color);
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack,
				partialTicks, getCenters(waterColumnBoxes), color, false);
		}
	}
	
	private ArrayList<Vec3> getCenters(ArrayList<AABB> boxes)
	{
		ArrayList<Vec3> centers = new ArrayList<>(boxes.size());
		for(AABB box : boxes)
			centers.add(box.getCenter());
		return centers;
	}
	
	private boolean isEnabledInCurrentDimension()
	{
		ResourceKey<Level> dim = MC.level.dimension();
		if(dim == Level.OVERWORLD)
			return overworld.isChecked();
		if(dim == Level.NETHER)
			return nether.isChecked();
		if(dim == Level.END)
			return end.isChecked();
		
		return true;
	}
	
	private HashSet<ChunkPos> getAreaChunks(int chunkRange)
	{
		ChunkPos center = getAreaCenterChunk();
		ChunkAreaSetting.ChunkArea selection = area.getSelected();
		ResourceKey<Level> dimension = MC.level.dimension();
		
		// ChunkUtils enumerates the client-side view-distance window and
		// filters
		// it through hasChunk(). This keeps the selected area an upper bound
		// instead of making a large area setting force terrain loads.
		loadedChunkCache.clear();
		ChunkUtils.getLoadedChunks()
			.forEach(chunk -> loadedChunkCache.add(chunk.getPos()));
		
		areaChunkCache.clear();
		for(ChunkPos pos : loadedChunkCache)
			if(Math.abs(pos.x - center.x) <= chunkRange
				&& Math.abs(pos.z - center.z) <= chunkRange)
				areaChunkCache.add(pos);
			
		cachedAreaCenter = center;
		cachedAreaSelection = selection;
		cachedAreaDimension = dimension;
		
		return areaChunkCache;
	}
	
	private boolean syncToArea(HashSet<ChunkPos> areaChunks)
	{
		boolean loadedChanged = !lastLoadedChunks.equals(loadedChunkCache);
		boolean areaChanged = !lastEffectiveArea.equals(areaChunks);
		boolean stickyChanged = lastStickyAreaSetting != stickyArea.isChecked();
		boolean changed = loadedChanged;
		lastLoadedChunks.clear();
		lastLoadedChunks.addAll(loadedChunkCache);
		
		// A moving player can change the effective area even when the set of
		// loaded chunks has not changed. Partial work then has to restart with
		// the new local boundary rules.
		if(areaChanged)
		{
			partialScans.clear();
			lastEffectiveArea.clear();
			lastEffectiveArea.addAll(areaChunks);
		}
		lastStickyAreaSetting = stickyArea.isChecked();
		
		if(areaChanged || loadedChanged || stickyChanged)
		{
			if(!stickyArea.isChecked() && detectionsByChunk.keySet()
				.removeIf(pos -> !areaChunks.contains(pos)))
				changed = true;
				
			// Completion state is intentionally separate from retained
			// detections.
			// Sticky detections can survive an unload, but the unloaded chunk
			// must
			// be scanned again if it later becomes available.
			scannedChunks.retainAll(areaChunks);
			partialScans.keySet().removeIf(pos -> !areaChunks.contains(pos));
			removePendingOutsideArea(areaChunks);
		}
		
		// Queue only a bounded number of new chunks per tick. The next ticks
		// pick
		// up where this one left off without sorting the whole loaded area.
		int maxNewChunks = Math.max(64, chunksPerTick.getValueI() * 32);
		int queued = 0;
		for(ChunkPos pos : areaChunks)
		{
			if(queued >= maxNewChunks)
				break;
			if(!scannedChunks.contains(pos) && !partialScans.containsKey(pos)
				&& !queuedChunks.contains(pos))
			{
				enqueueChunk(pos, ScanPriority.NORMAL);
				queued++;
			}
		}
		
		return changed;
	}
	
	private void removePendingOutsideArea(HashSet<ChunkPos> areaChunks)
	{
		removeOutsideArea(dirtyScanQueue, areaChunks);
		removeOutsideArea(nearbyScanQueue, areaChunks);
		removeOutsideArea(normalScanQueue, areaChunks);
		removeOutsideArea(refreshScanQueue, areaChunks);
		queuedChunks.removeIf(pos -> !areaChunks.contains(pos));
		queuedPriorities.keySet().removeIf(pos -> !areaChunks.contains(pos));
	}
	
	private void removeOutsideArea(ArrayDeque<ChunkPos> queue,
		HashSet<ChunkPos> areaChunks)
	{
		queue.removeIf(pos -> !areaChunks.contains(pos));
	}
	
	private void enqueueDirtyChunks(HashSet<ChunkPos> areaChunks)
	{
		int promoted = 0;
		int maxPromotions = Math.max(32, chunksPerTick.getValueI() * 16);
		
		while(promoted < maxPromotions)
		{
			ChunkPos pos = dirtyChunkQueue.poll();
			if(pos == null)
				return;
			
			dirtyChunkSet.remove(pos);
			
			if(!areaChunks.contains(pos))
				continue;
			
			partialScans.remove(pos);
			scannedChunks.remove(pos);
			enqueueChunk(pos, ScanPriority.DIRTY);
			
			promoted++;
		}
	}
	
	private void promoteNearbyChunks(HashSet<ChunkPos> areaChunks,
		int nearbyRadius)
	{
		ChunkPos center = getAreaCenterChunk();
		int radius = Math.min(getChunkRange(area.getSelected()), nearbyRadius);
		if(radius != nearbyCursorRadius)
		{
			nearbyCursorRadius = radius;
			nearbyCursor = 0;
		}
		
		int diameter = radius * 2 + 1;
		int total = diameter * diameter;
		int maxChecks = Math.max(32, chunksPerTick.getValueI() * 8);
		int maxPromotions = Math.max(8, chunksPerTick.getValueI() * 4);
		int checked = 0;
		int promoted = 0;
		while(checked++ < maxChecks && promoted < maxPromotions)
		{
			int index = nearbyCursor++ % total;
			int dx = index / diameter - radius;
			int dz = index % diameter - radius;
			ChunkPos pos = new ChunkPos(center.x + dx, center.z + dz);
			if(!areaChunks.contains(pos) || scannedChunks.contains(pos)
				|| partialScans.containsKey(pos))
				continue;
			
			ScanPriority oldPriority = queuedPriorities.get(pos);
			enqueueChunk(pos, ScanPriority.NEARBY);
			if(oldPriority != ScanPriority.NEARBY)
				promoted++;
		}
	}
	
	private boolean processQueuedScans(HashSet<ChunkPos> areaChunks, int scans,
		long budgetNs)
	{
		long startNs = System.nanoTime();
		long deadlineNs = startNs + Math.max(1L, budgetNs);
		boolean changed = false;
		
		for(int i = 0; i < scans; i++)
		{
			if(System.nanoTime() - startNs >= budgetNs)
				break;
			
			ScanWork work = pollNextScanWork();
			if(work == null)
				break;
			
			ChunkPos pos = work.pos;
			
			if(!areaChunks.contains(pos) || !loadedChunkCache.contains(pos))
				continue;
			
			ChunkScanState state = partialScans.remove(pos);
			if(state == null)
				state = new ChunkScanState(pos, work.priority);
			else
				state.priority = work.priority;
			
			ScanStep step = scanChunk(state, deadlineNs, areaChunks);
			if(step.unloaded)
				continue;
				
			// A packet can arrive while a scan is in progress. Do not publish
			// the
			// result in that case; the dirty queue will process a fresh scan.
			if(!step.complete || dirtyChunkSet.contains(pos))
			{
				if(!step.complete)
				{
					partialScans.put(pos, state);
					enqueueChunk(pos, state.priority);
				}
				continue;
			}
			
			ChunkDetections old = detectionsByChunk.put(pos, state.result);
			scannedChunks.add(pos);
			changed |= old == null || !old.sameAs(state.result);
		}
		
		return changed;
	}
	
	private void enqueueChunk(ChunkPos pos, ScanPriority priority)
	{
		ScanPriority oldPriority = queuedPriorities.get(pos);
		if(oldPriority != null)
		{
			if(priority.ordinal() >= oldPriority.ordinal())
				return;
			
			removePendingChunk(pos);
		}else
			queuedChunks.add(pos);
		
		queuedPriorities.put(pos, priority);
		switch(priority)
		{
			case DIRTY -> dirtyScanQueue.addFirst(pos);
			case NEARBY -> nearbyScanQueue.addLast(pos);
			case NORMAL -> normalScanQueue.addLast(pos);
			case REFRESH -> refreshScanQueue.addLast(pos);
		}
	}
	
	private void removePendingChunk(ChunkPos pos)
	{
		dirtyScanQueue.remove(pos);
		nearbyScanQueue.remove(pos);
		normalScanQueue.remove(pos);
		refreshScanQueue.remove(pos);
		queuedChunks.remove(pos);
		queuedPriorities.remove(pos);
	}
	
	private ScanWork pollNextScanWork()
	{
		boolean lowerWork = !nearbyScanQueue.isEmpty()
			|| !normalScanQueue.isEmpty() || !refreshScanQueue.isEmpty();
		if(!dirtyScanQueue.isEmpty() && (highPriorityStreak < 4 || !lowerWork))
		{
			highPriorityStreak++;
			return pollScanWork(dirtyScanQueue, ScanPriority.DIRTY);
		}
		
		if(!nearbyScanQueue.isEmpty()
			&& (nearbyPriorityStreak < 4 || normalScanQueue.isEmpty()))
		{
			highPriorityStreak = 0;
			nearbyPriorityStreak++;
			return pollScanWork(nearbyScanQueue, ScanPriority.NEARBY);
		}
		
		if(!normalScanQueue.isEmpty()
			&& (normalPriorityStreak < 8 || refreshScanQueue.isEmpty()))
		{
			highPriorityStreak = 0;
			nearbyPriorityStreak = 0;
			normalPriorityStreak++;
			return pollScanWork(normalScanQueue, ScanPriority.NORMAL);
		}
		
		if(!refreshScanQueue.isEmpty())
		{
			highPriorityStreak = 0;
			nearbyPriorityStreak = 0;
			normalPriorityStreak = 0;
			return pollScanWork(refreshScanQueue, ScanPriority.REFRESH);
		}
		
		if(!normalScanQueue.isEmpty())
		{
			normalPriorityStreak++;
			return pollScanWork(normalScanQueue, ScanPriority.NORMAL);
		}
		if(!nearbyScanQueue.isEmpty())
			return pollScanWork(nearbyScanQueue, ScanPriority.NEARBY);
		if(!dirtyScanQueue.isEmpty())
			return pollScanWork(dirtyScanQueue, ScanPriority.DIRTY);
		return null;
	}
	
	private ScanWork pollScanWork(ArrayDeque<ChunkPos> queue,
		ScanPriority priority)
	{
		ChunkPos pos = queue.removeFirst();
		queuedChunks.remove(pos);
		queuedPriorities.remove(pos);
		return new ScanWork(pos, priority);
	}
	
	private ScanProfile getScanProfile()
	{
		int baseRange = getChunkRange(area.getSelected());
		int baseScans = Math.max(1, chunksPerTick.getValueI());
		long baseBudgetNs =
			Math.max(1L, scanTimeBudgetMs.getValueI()) * 1_000_000L;
		int baseNearby = nearbyPriorityRadius.getValueI();
		
		if(MC.player == null)
			return new ScanProfile(baseRange, baseScans, baseBudgetNs,
				baseNearby);
		
		Vec3 currentPos = MC.player.position();
		double speed = 0;
		if(lastPlayerPos != null)
			speed = currentPos.subtract(lastPlayerPos).horizontalDistance();
		lastPlayerPos = currentPos;
		
		if(!adaptiveMovementScan.isChecked())
			return new ScanProfile(baseRange, baseScans, baseBudgetNs,
				baseNearby);
		
		boolean flying =
			MC.player.getAbilities().flying || MC.player.isFallFlying();
		if(flying || speed >= 0.9)
		{
			int range = Math.min(baseRange, 6);
			int scans = Math.max(baseScans, 16);
			long budgetNs = Math.max(baseBudgetNs, 16_000_000L);
			int nearby = Math.min(Math.max(baseNearby, 3), 6);
			return new ScanProfile(range, scans, budgetNs, nearby);
		}
		
		if(speed >= 0.25)
		{
			int range = Math.min(baseRange, 12);
			int scans = Math.max(baseScans, 10);
			long budgetNs = Math.max(baseBudgetNs, 10_000_000L);
			int nearby = Math.min(Math.max(baseNearby, 3), 8);
			return new ScanProfile(range, scans, budgetNs, nearby);
		}
		
		// Standing/slow movement: keep full selected area and user throughput.
		return new ScanProfile(baseRange, baseScans, baseBudgetNs, baseNearby);
	}
	
	private ScanStep scanChunk(ChunkScanState state, long deadlineNs,
		HashSet<ChunkPos> areaChunks)
	{
		state.budget.deadlineNs = deadlineNs;
		state.budget.paused = false;
		ChunkPos chunkPos = state.pos;
		if(MC.level == null || !loadedChunkCache.contains(chunkPos))
			return ScanStep.UNLOADED;
		
		LevelChunk chunk = MC.level.getChunk(chunkPos.x, chunkPos.z);
		if(chunk == null)
			return ScanStep.UNLOADED;
		
		int minY = MC.level.getMinY() + minYOffset.getValueI();
		int maxY = MC.level.getMaxY() - 1 - maxYOffset.getValueI();
		if(minY > maxY)
			return ScanStep.COMPLETE;
		
		boolean detectHoles = shouldDetectHoles();
		boolean detectTunnels = shouldDetectTunnels();
		boolean detectStairs = shouldDetectStairs();
		boolean laddersEnabled = detectLadders.isChecked();
		boolean bubbleColumnsEnabled = detectBubbleColumns.isChecked();
		boolean waterColumnsEnabled = detectWaterColumns.isChecked();
		int chunkLimit = Math.max(1, maxPerChunk.getValueI());
		
		BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
		LevelChunkSection[] sections = chunk.getSections();
		int minSectionY = chunk.getMinY() >> 4;
		if(state.sectionIndex >= sections.length)
			state.sectionIndex = sections.length - 1;
		
		while(state.sectionIndex >= 0)
		{
			LevelChunkSection section = sections[state.sectionIndex];
			int sectionBaseY = (minSectionY + state.sectionIndex) << 4;
			int sectionTopY = sectionBaseY + 15;
			int startY = Math.max(minY, sectionBaseY);
			int endY = Math.min(maxY, sectionTopY);
			if(section == null || section.hasOnlyAir() || sectionTopY < minY
				|| sectionBaseY > maxY)
			{
				advanceSection(state);
				continue;
			}
			
			if(state.y == Integer.MIN_VALUE)
			{
				state.y = endY;
				state.localX = 0;
				state.localZ = 0;
			}
			
			while(state.y >= startY)
			{
				while(state.localX < 16)
				{
					while(state.localZ < 16)
					{
						if(state.budget.shouldPause(deadlineNs))
							return ScanStep.INCOMPLETE;
						
						if((!detectHoles
							|| state.result.holes.size() >= chunkLimit)
							&& (!detectTunnels
								|| state.result.tunnels.size() >= chunkLimit)
							&& (!detectStairs
								|| state.result.stairs.size() >= chunkLimit)
							&& (!laddersEnabled
								|| state.result.ladders.size() >= chunkLimit)
							&& (!bubbleColumnsEnabled
								|| state.result.bubbleColumns
									.size() >= chunkLimit)
							&& (!waterColumnsEnabled
								|| state.result.waterColumns
									.size() >= chunkLimit))
							return ScanStep.COMPLETE;
						
						int localY = state.y - sectionBaseY;
						BlockState blockState = section.getStates()
							.get(state.localX, localY, state.localZ);
						mutablePos.set(chunkPos.getMinBlockX() + state.localX,
							state.y, chunkPos.getMinBlockZ() + state.localZ);
						BlockPos pos = mutablePos.immutable();
						
						if(laddersEnabled
							&& state.result.ladders.size() < chunkLimit)
							tryAddLadderColumn(pos, blockState, minY, maxY,
								state.result);
						
						if(bubbleColumnsEnabled
							&& state.result.bubbleColumns.size() < chunkLimit)
							tryAddBubbleColumn(pos, blockState, minY, maxY,
								state.result);
						
						if(waterColumnsEnabled
							&& state.result.waterColumns.size() < chunkLimit)
							tryAddWaterColumn(pos, blockState, minY, maxY,
								state.result);
						
						boolean passable = isPassable(mutablePos, blockState);
						boolean holePassable = detectHoles
							&& isHolePassable(mutablePos, blockState);
						boolean tunnelPassable = detectTunnels
							&& isTunnelPassable(mutablePos, blockState);
						if(passable || holePassable || tunnelPassable)
						{
							boolean hasSolidBelow = false;
							if((detectTunnels && tunnelPassable
								&& state.result.tunnels.size() < chunkLimit)
								|| (detectStairs && passable
									&& state.result.stairs.size() < chunkLimit))
								hasSolidBelow = isSolid(pos.below());
							
							if(detectHoles && holePassable
								&& state.result.holes.size() < chunkLimit)
								tryAddHole(pos, maxY, state.result);
							
							if(detectTunnels && tunnelPassable && hasSolidBelow
								&& state.result.tunnels.size() < chunkLimit)
								for(Direction dir : TUNNEL_DIRECTIONS)
								{
									tryAddTunnel(pos, dir, state.result, state,
										areaChunks);
									if(state.budget.paused)
										return ScanStep.INCOMPLETE;
								}
							
							if(detectStairs && passable && hasSolidBelow
								&& state.result.stairs.size() < chunkLimit)
								for(Direction dir : CARDINALS)
									tryAddStaircase(pos, dir, state.result);
						}
						
						state.localZ++;
					}
					state.localZ = 0;
					state.localX++;
				}
				state.localX = 0;
				state.y--;
			}
			
			advanceSection(state);
		}
		
		return ScanStep.COMPLETE;
	}
	
	private void advanceSection(ChunkScanState state)
	{
		state.sectionIndex--;
		state.y = Integer.MIN_VALUE;
		state.localX = 0;
		state.localZ = 0;
	}
	
	private void tryAddHole(BlockPos start, int maxY, ChunkDetections result)
	{
		int width = -1;
		for(int w = maxHoleWidth.getValueI(); w >= minHoleWidth
			.getValueI(); w--)
			if(isHoleSection(start, w))
			{
				width = w;
				break;
			}
		
		if(width < 1)
			return;
		if(isHoleSection(start.west(), width)
			|| isHoleSection(start.north(), width))
			return;
		if(isHoleSection(start.below(), width))
			return;
		
		BlockPos.MutableBlockPos cursor = start.mutable();
		int depth = 0;
		int totalWalls = 0;
		int naturalWalls = 0;
		
		while(cursor.getY() <= maxY && isHoleSection(cursor, width))
		{
			if(naturalWallsOnly.isChecked())
			{
				for(int i = 0; i < width; i++)
				{
					BlockPos west = cursor.offset(-1, 0, i);
					BlockPos east = cursor.offset(width, 0, i);
					BlockPos north = cursor.offset(i, 0, -1);
					BlockPos south = cursor.offset(i, 0, width);
					
					totalWalls += 4;
					if(isLikelyNaturalWall(west))
						naturalWalls++;
					if(isLikelyNaturalWall(east))
						naturalWalls++;
					if(isLikelyNaturalWall(north))
						naturalWalls++;
					if(isLikelyNaturalWall(south))
						naturalWalls++;
				}
			}
			
			cursor.move(Direction.UP);
			depth++;
		}
		
		if(depth < minHoleDepth.getValueI())
			return;
		
		if(naturalWallsOnly.isChecked() && totalWalls > 0)
		{
			double ratio = naturalWalls / (double)totalWalls;
			if(ratio < naturalWallRatio.getValue())
				return;
		}
		
		AABB box = new AABB(start.getX(), start.getY(), start.getZ(),
			start.getX() + width, cursor.getY(), start.getZ() + width);
		if(!intersectsAny(result.holes, box))
			result.holes.add(box);
	}
	
	private void enqueuePeriodicRefresh(HashSet<ChunkPos> areaChunks)
	{
		int intervalTicks = refreshInterval.getValueI() * 20;
		if(intervalTicks <= 0)
		{
			refreshSweepActive = false;
			refreshCandidates.clear();
			refreshCursor = 0;
			return;
		}
		
		if(refreshTimerTicks > 0)
			refreshTimerTicks--;
		else if(!refreshSweepActive)
		{
			refreshTimerTicks = intervalTicks;
			refreshCandidates.clear();
			refreshCandidates.addAll(areaChunks);
			refreshCursor = 0;
			refreshSweepActive = true;
		}
		
		// Refresh is deliberately spread over multiple ticks and has the lowest
		// priority. A new or dirty chunk never waits for this sweep to finish.
		int promoted = 0;
		while(refreshSweepActive && refreshCursor < refreshCandidates.size()
			&& promoted < 2)
		{
			ChunkPos pos = refreshCandidates.get(refreshCursor++);
			if(areaChunks.contains(pos) && scannedChunks.contains(pos))
			{
				ScanPriority old = queuedPriorities.get(pos);
				enqueueChunk(pos, ScanPriority.REFRESH);
				if(old == null)
					promoted++;
			}
		}
		if(refreshCursor >= refreshCandidates.size())
			refreshSweepActive = false;
	}
	
	private void tryAddTunnel(BlockPos start, Direction dir,
		ChunkDetections result, ChunkScanState scanState,
		HashSet<ChunkPos> areaChunks)
	{
		if(!isInEffectiveScanArea(start, areaChunks)
			|| !isTunnelSection(start, dir))
			return;
		
		TunnelVisit startVisit = new TunnelVisit(start.asLong(), dir.getAxis());
		if(scanState.visitedTunnelCells.contains(startVisit))
			return;
			
		// A tunnel is intentionally discovered from the first available local
		// cell. There is no predecessor test: an unloaded chunk, scan boundary,
		// or selected-area boundary is a valid local segment boundary.
		ArrayList<TunnelVisit> runVisits = new ArrayList<>();
		
		BlockPos.MutableBlockPos cursor = start.mutable();
		BlockPos end = start;
		int length = 0;
		int minHeight = Integer.MAX_VALUE;
		int maxHeight = Integer.MIN_VALUE;
		int minWidth = Integer.MAX_VALUE;
		int maxWidth = Integer.MIN_VALUE;
		
		int maxLocalLength = Math.max(16, minTunnelLength.getValueI());
		while(length < maxLocalLength
			&& isInEffectiveScanArea(cursor, areaChunks)
			&& isTunnelSection(cursor, dir))
		{
			if(scanState.budget.checkNow())
				return;
			runVisits.add(new TunnelVisit(cursor.asLong(), dir.getAxis()));
			int height =
				getTunnelClearHeight(cursor, maxTunnelHeight.getValueI());
			int width = getTunnelWidth(cursor, dir, height);
			minHeight = Math.min(minHeight, height);
			maxHeight = Math.max(maxHeight, height);
			minWidth = Math.min(minWidth, width);
			maxWidth = Math.max(maxWidth, width);
			end = cursor.immutable();
			cursor.move(dir);
			length++;
		}
		
		if(length < minTunnelLength.getValueI())
			return;
		if(maxHeight - minHeight > 1)
			return;
		if(minWidth < minTunnelWidth.getValueI()
			|| maxWidth > maxTunnelWidth.getValueI())
			return;
		if(maxWidth - minWidth > 1)
			return;
		
		scanState.visitedTunnelCells.addAll(runVisits);
		
		Direction side = dir.getClockWise();
		BlockPos sideEnd = end.relative(side, maxWidth - 1);
		AABB box = new AABB(
			Math.min(Math.min(start.getX(), end.getX()), sideEnd.getX()),
			start.getY(),
			Math.min(Math.min(start.getZ(), end.getZ()), sideEnd.getZ()),
			Math.max(Math.max(start.getX(), end.getX()), sideEnd.getX()) + 1,
			start.getY() + maxHeight,
			Math.max(Math.max(start.getZ(), end.getZ()), sideEnd.getZ()) + 1);
		
		if(!intersectsTunnel(result.tunnels, box, dir.getAxis(), start.getY(),
			maxWidth, maxHeight))
			result.tunnels.add(new TunnelDetection(box, dir.getAxis(),
				start.getY(), maxWidth, maxHeight));
	}
	
	private void tryAddLadderColumn(BlockPos start, BlockState startState,
		int minY, int maxY, ChunkDetections result)
	{
		if(!(startState.getBlock() instanceof LadderBlock))
			return;
		BlockState belowState = getLoadedBlockState(start.below());
		if(start.getY() > minY && belowState != null
			&& belowState.getBlock() instanceof LadderBlock)
			return;
		
		BlockPos.MutableBlockPos cursor = start.mutable();
		int height = 0;
		while(cursor.getY() <= maxY)
		{
			BlockState state = getLoadedBlockState(cursor);
			if(state == null || !(state.getBlock() instanceof LadderBlock))
				break;
			height++;
			cursor.move(Direction.UP);
		}
		
		if(height < minLadderHeight.getValueI())
			return;
		
		AABB box = new AABB(start.getX(), start.getY(), start.getZ(),
			start.getX() + 1, start.getY() + height, start.getZ() + 1);
		if(!intersectsAny(result.ladders, box))
			result.ladders.add(box);
	}
	
	private void tryAddBubbleColumn(BlockPos start, BlockState startState,
		int minY, int maxY, ChunkDetections result)
	{
		if(!(startState.getBlock() instanceof BubbleColumnBlock))
			return;
		if(start.getY() > minY)
		{
			BlockPos belowPos = start.below();
			BlockState belowState = getLoadedBlockState(belowPos);
			if(belowState != null
				&& belowState.getBlock() instanceof BubbleColumnBlock
				&& isBubbleColumnSectionInHole(belowPos))
				return;
		}
		
		BlockPos.MutableBlockPos cursor = start.mutable();
		int height = 0;
		while(cursor.getY() <= maxY)
		{
			BlockState state = getLoadedBlockState(cursor);
			if(state == null || !(state.getBlock() instanceof BubbleColumnBlock)
				|| !isBubbleColumnSectionInHole(cursor))
				break;
			height++;
			cursor.move(Direction.UP);
		}
		
		if(height < minBubbleColumnHeight.getValueI())
			return;
		
		AABB box = new AABB(start.getX(), start.getY(), start.getZ(),
			start.getX() + 1, start.getY() + height, start.getZ() + 1);
		if(!intersectsAny(result.bubbleColumns, box))
			result.bubbleColumns.add(box);
	}
	
	private void tryAddWaterColumn(BlockPos start, BlockState startState,
		int minY, int maxY, ChunkDetections result)
	{
		if(!startState.getFluidState().is(FluidTags.WATER)
			|| startState.getBlock() instanceof BubbleColumnBlock)
			return;
		
		if(start.getY() > minY)
		{
			BlockPos belowPos = start.below();
			BlockState below = getLoadedBlockState(belowPos);
			if(below != null && below.getFluidState().is(FluidTags.WATER)
				&& !(below.getBlock() instanceof BubbleColumnBlock)
				&& isBubbleColumnSectionInHole(belowPos))
				return;
		}
		
		BlockPos.MutableBlockPos cursor = start.mutable();
		int height = 0;
		while(cursor.getY() <= maxY)
		{
			BlockState state = getLoadedBlockState(cursor);
			if(state == null || !state.getFluidState().is(FluidTags.WATER)
				|| state.getBlock() instanceof BubbleColumnBlock
				|| !isBubbleColumnSectionInHole(cursor))
				break;
			
			height++;
			cursor.move(Direction.UP);
		}
		
		if(height < minWaterColumnHeight.getValueI())
			return;
		
		AABB box = new AABB(start.getX(), start.getY(), start.getZ(),
			start.getX() + 1, start.getY() + height, start.getZ() + 1);
		if(!intersectsAny(result.waterColumns, box))
			result.waterColumns.add(box);
	}
	
	private boolean isBubbleColumnSectionInHole(BlockPos pos)
	{
		for(Direction dir : CARDINALS)
			if(!isSolid(pos.relative(dir)))
				return false;
			
		return true;
	}
	
	private void tryAddStaircase(BlockPos start, Direction dir,
		ChunkDetections result)
	{
		if(!isStairSection(start, dir))
			return;
		
		BlockPos prev = start.relative(dir.getOpposite()).below();
		if(isStairSection(prev, dir))
			return;
		
		BlockPos.MutableBlockPos cursor = start.mutable();
		ArrayList<AABB> boxes = new ArrayList<>();
		int length = 0;
		int minHeight = Integer.MAX_VALUE;
		int maxHeight = Integer.MIN_VALUE;
		
		while(isStairSection(cursor, dir))
		{
			int height = getClearHeight(cursor, maxStairHeight.getValueI());
			minHeight = Math.min(minHeight, height);
			maxHeight = Math.max(maxHeight, height);
			
			boxes.add(new AABB(cursor.getX(), cursor.getY(), cursor.getZ(),
				cursor.getX() + 1, cursor.getY() + height, cursor.getZ() + 1));
			
			cursor.move(dir);
			cursor.move(Direction.UP);
			length++;
		}
		
		if(length < minStairLength.getValueI())
			return;
		if(maxHeight - minHeight > 1)
			return;
		
		for(AABB box : boxes)
			if(!intersectsAny(result.stairs, box))
				result.stairs.add(box);
	}
	
	private boolean isHoleSection(BlockPos pos, int width)
	{
		for(int x = 0; x < width; x++)
			for(int z = 0; z < width; z++)
				if(!isHolePassable(pos.offset(x, 0, z)))
					return false;
				
		for(int i = 0; i < width; i++)
		{
			if(!isSolid(pos.offset(-1, 0, i)))
				return false;
			if(!isSolid(pos.offset(width, 0, i)))
				return false;
			if(!isSolid(pos.offset(i, 0, -1)))
				return false;
			if(!isSolid(pos.offset(i, 0, width)))
				return false;
		}
		
		return true;
	}
	
	private boolean isHolePassable(BlockPos pos)
	{
		BlockState state = getLoadedBlockState(pos);
		if(state == null)
			return false;
		return isHolePassable(pos, state);
	}
	
	private boolean isHolePassable(BlockPos pos, BlockState state)
	{
		return isPassable(pos, state)
			|| state.getBlock() instanceof LadderBlock;
	}
	
	private boolean isTunnelSection(BlockPos pos, Direction dir)
	{
		int minHeight = minTunnelHeight.getValueI();
		int maxHeight = maxTunnelHeight.getValueI();
		int height = getTunnelClearHeight(pos, maxHeight);
		
		if(height < minHeight || height > maxHeight)
			return false;
		
		int width = getTunnelWidth(pos, dir, height);
		if(width < minTunnelWidth.getValueI()
			|| width > maxTunnelWidth.getValueI())
			return false;
		
		Direction left = dir.getCounterClockWise();
		Direction right = dir.getClockWise();
		Direction side = dir.getClockWise();
		BlockPos rightEdge = pos.relative(side, width - 1);
		int naturalWalls = 0;
		int totalWalls = 0;
		
		for(int w = 0; w < width; w++)
		{
			BlockPos lane = pos.relative(side, w);
			BlockPos floor = lane.below();
			BlockPos ceiling = lane.above(height);
			if(!isSolid(floor) || !isSolid(ceiling))
				return false;
			
			if(naturalWallsOnly.isChecked())
			{
				totalWalls += 2;
				if(isLikelyNaturalWall(floor))
					naturalWalls++;
				if(isLikelyNaturalWall(ceiling))
					naturalWalls++;
			}
		}
		
		for(int i = 0; i < height; i++)
		{
			BlockPos leftPos = pos.above(i).relative(left);
			BlockPos rightPos = rightEdge.above(i).relative(right);
			
			if(!isSolid(leftPos) || !isSolid(rightPos))
				return false;
			
			if(naturalWallsOnly.isChecked())
			{
				totalWalls += 2;
				if(isLikelyNaturalWall(leftPos))
					naturalWalls++;
				if(isLikelyNaturalWall(rightPos))
					naturalWalls++;
			}
		}
		
		if(!naturalWallsOnly.isChecked())
			return true;
		
		double ratio = naturalWalls / (double)Math.max(1, totalWalls);
		return ratio >= naturalWallRatio.getValue();
	}
	
	private int getTunnelWidth(BlockPos pos, Direction dir, int height)
	{
		int maxWidth = maxTunnelWidth.getValueI();
		Direction side = dir.getClockWise();
		int width = 0;
		
		while(width < maxWidth
			&& isTunnelLaneAtHeight(pos.relative(side, width), height))
			width++;
			
		// Do not silently clamp an actually wider tunnel to the configured
		// maximum. Returning max+1 makes the caller reject it.
		if(width == maxWidth
			&& isTunnelLaneAtHeight(pos.relative(side, width), height))
			return maxWidth + 1;
		
		return width;
	}
	
	private boolean isTunnelLaneAtHeight(BlockPos pos, int height)
	{
		if(!isSolid(pos.below()) || !isSolid(pos.above(height)))
			return false;
		
		for(int i = 0; i < height; i++)
			if(!isTunnelPassable(pos.above(i)))
				return false;
			
		return true;
	}
	
	private boolean isTunnelPassable(BlockPos pos)
	{
		BlockState state = getLoadedBlockState(pos);
		return state != null && isTunnelPassable(pos, state);
	}
	
	private boolean isTunnelPassable(BlockPos pos, BlockState state)
	{
		return isPassable(pos, state) || state.getBlock() instanceof TorchBlock
			|| state.getBlock() instanceof WallTorchBlock;
	}
	
	private boolean isStairSection(BlockPos pos, Direction dir)
	{
		int minHeight = minStairHeight.getValueI();
		int maxHeight = maxStairHeight.getValueI();
		int height = getClearHeight(pos, maxHeight);
		
		if(height < minHeight || height > maxHeight)
			return false;
		if(!isSolid(pos.below()) || !isSolid(pos.above(height)))
			return false;
		
		Direction left = dir.getCounterClockWise();
		Direction right = dir.getClockWise();
		int naturalWalls = 0;
		int totalWalls = 2;
		
		if(naturalWallsOnly.isChecked())
		{
			if(isLikelyNaturalWall(pos.below()))
				naturalWalls++;
			if(isLikelyNaturalWall(pos.above(height)))
				naturalWalls++;
		}
		
		for(int i = 0; i < height; i++)
		{
			BlockPos leftPos = pos.above(i).relative(left);
			BlockPos rightPos = pos.above(i).relative(right);
			
			if(!isSolid(leftPos) || !isSolid(rightPos))
				return false;
			
			if(naturalWallsOnly.isChecked())
			{
				totalWalls += 2;
				if(isLikelyNaturalWall(leftPos))
					naturalWalls++;
				if(isLikelyNaturalWall(rightPos))
					naturalWalls++;
			}
		}
		
		if(!naturalWallsOnly.isChecked())
			return true;
		
		double ratio = naturalWalls / (double)Math.max(1, totalWalls);
		return ratio >= naturalWallRatio.getValue();
	}
	
	private boolean isPassable(BlockPos pos)
	{
		BlockState state = getLoadedBlockState(pos);
		return state != null && isPassable(pos, state);
	}
	
	private boolean isPassable(BlockPos pos, BlockState state)
	{
		if(airOnly.isChecked())
			return state.isAir();
		
		if(!state.getFluidState().isEmpty())
			return false;
		
		return state.getCollisionShape(MC.level, pos).isEmpty();
	}
	
	private boolean isSolid(BlockPos pos)
	{
		BlockState state = getLoadedBlockState(pos);
		if(state == null)
			return false;
		if(!state.getFluidState().isEmpty())
			return false;
		
		return state.isCollisionShapeFullBlock(MC.level, pos);
	}
	
	private int getClearHeight(BlockPos pos, int maxHeight)
	{
		int height = 0;
		while(height < maxHeight && isPassable(pos.above(height)))
			height++;
		
		return height;
	}
	
	private int getTunnelClearHeight(BlockPos pos, int maxHeight)
	{
		int height = 0;
		while(height < maxHeight && isTunnelPassable(pos.above(height)))
			height++;
			
		// As with width, distinguish an exact maximum from a tunnel that is
		// taller than the configured maximum.
		if(height == maxHeight && isTunnelPassable(pos.above(height)))
			return maxHeight + 1;
		
		return height;
	}
	
	private boolean isLikelyNaturalWall(BlockPos pos)
	{
		BlockState state = getLoadedBlockState(pos);
		if(state == null)
			return false;
		if(!state.isCollisionShapeFullBlock(MC.level, pos)
			|| !state.getFluidState().isEmpty())
			return false;
		
		Block block = state.getBlock();
		Boolean cached = naturalWallCache.get(block);
		if(cached != null)
			return cached;
		
		String path = BuiltInRegistries.BLOCK.getKey(block).getPath()
			.toLowerCase(Locale.ROOT);
		
		boolean natural = looksNaturalTerrain(path);
		naturalWallCache.put(block, natural);
		return natural;
	}
	
	private boolean isInEffectiveScanArea(BlockPos pos,
		HashSet<ChunkPos> areaChunks)
	{
		return areaChunks
			.contains(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4));
	}
	
	private BlockState getLoadedBlockState(BlockPos pos)
	{
		if(MC.level == null || pos.getY() < MC.level.getMinY()
			|| pos.getY() >= MC.level.getMaxY()
			|| !MC.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4))
			return null;
		return MC.level.getBlockState(pos);
	}
	
	private boolean looksNaturalTerrain(String path)
	{
		String[] strongReject = {"planks", "log", "wood", "stripped", "fence",
			"stairs", "slab", "door", "trapdoor", "rail", "brick", "tile",
			"prismarine", "purpur", "quartz", "wool", "glass", "copper",
			"trial", "obsidian", "amethyst", "bookshelf", "chest", "torch",
			"lantern", "chain", "spawner", "barrel", "furnace", "sculk"};
		
		for(String reject : strongReject)
			if(path.contains(reject))
				return false;
			
		if(path.contains("ore") || path.contains("debris"))
			return true;
		
		String[] terrainHints =
			{"stone", "deepslate", "netherrack", "blackstone", "basalt",
				"end_stone", "dirt", "gravel", "sand", "clay", "tuff",
				"calcite", "mud", "terracotta", "bedrock", "andesite",
				"diorite", "granite", "soul_sand", "soul_soil", "nylium"};
		
		for(String hint : terrainHints)
			if(path.contains(hint))
				return true;
			
		return false;
	}
	
	private void rebuildRenderCache()
	{
		holeBoxes.clear();
		tunnelBoxes.clear();
		stairBoxes.clear();
		ladderBoxes.clear();
		bubbleColumnBoxes.clear();
		waterColumnBoxes.clear();
		
		ArrayList<TunnelDetection> tunnels = new ArrayList<>();
		for(Map.Entry<ChunkPos, ChunkDetections> entry : detectionsByChunk
			.entrySet())
		{
			ChunkPos chunkPos = entry.getKey();
			if(MC.level == null || !loadedChunkCache.contains(chunkPos))
				continue;
			
			ChunkDetections detections = entry.getValue();
			holeBoxes.addAll(detections.holes);
			for(TunnelDetection tunnel : detections.tunnels)
				tunnels.add(new TunnelDetection(tunnel.box, tunnel.axis,
					tunnel.floorY, tunnel.width, tunnel.height));
			stairBoxes.addAll(detections.stairs);
			ladderBoxes.addAll(detections.ladders);
			bubbleColumnBoxes.addAll(detections.bubbleColumns);
			waterColumnBoxes.addAll(detections.waterColumns);
		}
		
		mergeTunnelDetections(tunnels);
		for(TunnelDetection tunnel : tunnels)
			tunnelBoxes.add(tunnel.box);
	}
	
	private void mergeTunnelDetections(ArrayList<TunnelDetection> tunnels)
	{
		tunnels.sort(Comparator.comparing((TunnelDetection t) -> t.axis)
			.thenComparingInt(t -> t.floorY).thenComparingInt(t -> t.height)
			.thenComparingInt(t -> t.width)
			.thenComparingDouble(t -> tunnelCrossMin(t))
			.thenComparingDouble(t -> tunnelAlongMin(t)));
		
		for(int i = 1; i < tunnels.size();)
		{
			TunnelDetection previous = tunnels.get(i - 1);
			TunnelDetection current = tunnels.get(i);
			if(!areCompatibleTunnels(previous, current))
			{
				i++;
				continue;
			}
			previous.box = mergeBoxes(previous.box, current.box);
			tunnels.remove(i);
		}
	}
	
	private boolean areCompatibleTunnels(TunnelDetection a, TunnelDetection b)
	{
		if(a.axis != b.axis || a.floorY != b.floorY || a.height != b.height
			|| a.width != b.width)
			return false;
		if(Double.compare(tunnelCrossMin(a), tunnelCrossMin(b)) != 0
			|| Double.compare(tunnelCrossMax(a), tunnelCrossMax(b)) != 0)
			return false;
		return tunnelAlongMin(b) <= tunnelAlongMax(a);
	}
	
	private double tunnelAlongMin(TunnelDetection tunnel)
	{
		return tunnel.axis == Direction.Axis.X ? tunnel.box.minX
			: tunnel.box.minZ;
	}
	
	private double tunnelAlongMax(TunnelDetection tunnel)
	{
		return tunnel.axis == Direction.Axis.X ? tunnel.box.maxX
			: tunnel.box.maxZ;
	}
	
	private double tunnelCrossMin(TunnelDetection tunnel)
	{
		return tunnel.axis == Direction.Axis.X ? tunnel.box.minZ
			: tunnel.box.minX;
	}
	
	private double tunnelCrossMax(TunnelDetection tunnel)
	{
		return tunnel.axis == Direction.Axis.X ? tunnel.box.maxZ
			: tunnel.box.maxX;
	}
	
	private AABB mergeBoxes(AABB a, AABB b)
	{
		return new AABB(Math.min(a.minX, b.minX), Math.min(a.minY, b.minY),
			Math.min(a.minZ, b.minZ), Math.max(a.maxX, b.maxX),
			Math.max(a.maxY, b.maxY), Math.max(a.maxZ, b.maxZ));
	}
	
	private void clearRuntimeState()
	{
		refreshTimerTicks = 0;
		detectionsByChunk.clear();
		dirtyScanQueue.clear();
		nearbyScanQueue.clear();
		normalScanQueue.clear();
		refreshScanQueue.clear();
		queuedChunks.clear();
		queuedPriorities.clear();
		scannedChunks.clear();
		partialScans.clear();
		dirtyChunkQueue.clear();
		dirtyChunkSet.clear();
		areaChunkCache.clear();
		loadedChunkCache.clear();
		lastLoadedChunks.clear();
		lastEffectiveArea.clear();
		refreshCandidates.clear();
		refreshCursor = 0;
		refreshSweepActive = false;
		nearbyCursor = 0;
		nearbyCursorRadius = -1;
		highPriorityStreak = 0;
		nearbyPriorityStreak = 0;
		normalPriorityStreak = 0;
		lastPlayerPos = null;
		cachedAreaCenter = null;
		cachedAreaSelection = null;
		cachedAreaDimension = null;
		naturalWallCache.clear();
		holeBoxes.clear();
		tunnelBoxes.clear();
		stairBoxes.clear();
		ladderBoxes.clear();
		bubbleColumnBoxes.clear();
		waterColumnBoxes.clear();
	}
	
	private ChunkPos getAreaCenterChunk()
	{
		return MC.player.chunkPosition();
	}
	
	private int getChunkRange(ChunkAreaSetting.ChunkArea selection)
	{
		// The enum names are total diameters: A1 is radius 0, A19 is
		// radius 9, and A65 is radius 32.
		return selection.ordinal();
	}
	
	private int getScanConfigHash()
	{
		return Objects.hash(detectionMode.getSelected(), airOnly.isChecked(),
			minYOffset.getValueI(), maxYOffset.getValueI(),
			minHoleDepth.getValueI(), minHoleWidth.getValueI(),
			maxHoleWidth.getValueI(), minTunnelLength.getValueI(),
			minTunnelWidth.getValueI(), maxTunnelWidth.getValueI(),
			minTunnelHeight.getValueI(), maxTunnelHeight.getValueI(),
			minStairLength.getValueI(), minStairHeight.getValueI(),
			maxStairHeight.getValueI(), detectLadders.isChecked(),
			minLadderHeight.getValueI(), detectBubbleColumns.isChecked(),
			minBubbleColumnHeight.getValueI(), detectWaterColumns.isChecked(),
			minWaterColumnHeight.getValueI(), maxPerChunk.getValueI(),
			refreshInterval.getValueI(), adaptiveMovementScan.isChecked(),
			nearbyPriorityRadius.getValueI(), naturalWallsOnly.isChecked(),
			naturalWallRatio.getValue(), overworld.isChecked(),
			nether.isChecked(), end.isChecked());
	}
	
	private boolean shouldDetectHoles()
	{
		DetectionMode mode = detectionMode.getSelected();
		return mode == DetectionMode.ALL || mode == DetectionMode.HOLES
			|| mode == DetectionMode.HOLES_AND_TUNNELS
			|| mode == DetectionMode.HOLES_AND_STAIRCASES;
	}
	
	private boolean shouldDetectTunnels()
	{
		DetectionMode mode = detectionMode.getSelected();
		return mode == DetectionMode.ALL || mode == DetectionMode.TUNNELS
			|| mode == DetectionMode.HOLES_AND_TUNNELS
			|| mode == DetectionMode.TUNNELS_AND_STAIRCASES;
	}
	
	private boolean shouldDetectStairs()
	{
		DetectionMode mode = detectionMode.getSelected();
		return mode == DetectionMode.ALL || mode == DetectionMode.STAIRCASES
			|| mode == DetectionMode.HOLES_AND_STAIRCASES
			|| mode == DetectionMode.TUNNELS_AND_STAIRCASES;
	}
	
	private boolean intersectsAny(ArrayList<AABB> boxes, AABB candidate)
	{
		for(AABB existing : boxes)
			if(existing.intersects(candidate))
				return true;
			
		return false;
	}
	
	private boolean intersectsTunnel(ArrayList<TunnelDetection> tunnels,
		AABB candidate, Direction.Axis axis, int floorY, int width, int height)
	{
		for(TunnelDetection existing : tunnels)
			if(existing.axis == axis && existing.floorY == floorY
				&& existing.width == width && existing.height == height
				&& existing.box.intersects(candidate))
				return true;
		return false;
	}
	
	private static final class ChunkDetections
	{
		private final ArrayList<AABB> holes = new ArrayList<>();
		private final ArrayList<TunnelDetection> tunnels = new ArrayList<>();
		private final ArrayList<AABB> stairs = new ArrayList<>();
		private final ArrayList<AABB> ladders = new ArrayList<>();
		private final ArrayList<AABB> bubbleColumns = new ArrayList<>();
		private final ArrayList<AABB> waterColumns = new ArrayList<>();
		
		private boolean sameAs(ChunkDetections other)
		{
			return sameBoxes(holes, other.holes)
				&& sameTunnelBoxes(tunnels, other.tunnels)
				&& sameBoxes(stairs, other.stairs)
				&& sameBoxes(ladders, other.ladders)
				&& sameBoxes(bubbleColumns, other.bubbleColumns)
				&& sameBoxes(waterColumns, other.waterColumns);
		}
		
		private static boolean sameBoxes(ArrayList<AABB> a, ArrayList<AABB> b)
		{
			if(a.size() != b.size())
				return false;
			for(int i = 0; i < a.size(); i++)
				if(!sameBox(a.get(i), b.get(i)))
					return false;
			return true;
		}
		
		private static boolean sameTunnelBoxes(ArrayList<TunnelDetection> a,
			ArrayList<TunnelDetection> b)
		{
			if(a.size() != b.size())
				return false;
			for(int i = 0; i < a.size(); i++)
			{
				TunnelDetection left = a.get(i);
				TunnelDetection right = b.get(i);
				if(left.axis != right.axis || left.floorY != right.floorY
					|| left.width != right.width || left.height != right.height
					|| !sameBox(left.box, right.box))
					return false;
			}
			return true;
		}
		
		private static boolean sameBox(AABB a, AABB b)
		{
			return Double.compare(a.minX, b.minX) == 0
				&& Double.compare(a.minY, b.minY) == 0
				&& Double.compare(a.minZ, b.minZ) == 0
				&& Double.compare(a.maxX, b.maxX) == 0
				&& Double.compare(a.maxY, b.maxY) == 0
				&& Double.compare(a.maxZ, b.maxZ) == 0;
		}
	}
	
	private static final class TunnelDetection
	{
		private AABB box;
		private final Direction.Axis axis;
		private final int floorY;
		private final int width;
		private final int height;
		
		private TunnelDetection(AABB box, Direction.Axis axis, int floorY,
			int width, int height)
		{
			this.box = box;
			this.axis = axis;
			this.floorY = floorY;
			this.width = width;
			this.height = height;
		}
	}
	
	private record TunnelVisit(long pos, Direction.Axis axis)
	{}
	
	private enum ScanPriority
	{
		DIRTY,
		NEARBY,
		NORMAL,
		REFRESH
	}
	
	private static final class ScanWork
	{
		private final ChunkPos pos;
		private final ScanPriority priority;
		
		private ScanWork(ChunkPos pos, ScanPriority priority)
		{
			this.pos = pos;
			this.priority = priority;
		}
	}
	
	private static final class ScanStep
	{
		private static final ScanStep COMPLETE = new ScanStep(true, false);
		private static final ScanStep INCOMPLETE = new ScanStep(false, false);
		private static final ScanStep UNLOADED = new ScanStep(false, true);
		private final boolean complete;
		private final boolean unloaded;
		
		private ScanStep(boolean complete, boolean unloaded)
		{
			this.complete = complete;
			this.unloaded = unloaded;
		}
	}
	
	private static final class ScanBudget
	{
		private int examinedBlocks;
		private boolean paused;
		private long deadlineNs;
		
		private boolean shouldPause(long deadlineNs)
		{
			if(++examinedBlocks < 256)
				return false;
			examinedBlocks = 0;
			return checkNow(deadlineNs);
		}
		
		private boolean checkNow(long deadlineNs)
		{
			if(System.nanoTime() < deadlineNs)
				return false;
			paused = true;
			return true;
		}
		
		private boolean checkNow()
		{
			return checkNow(deadlineNs);
		}
	}
	
	private static final class ChunkScanState
	{
		private final ChunkPos pos;
		private final ChunkDetections result = new ChunkDetections();
		private final HashSet<TunnelVisit> visitedTunnelCells = new HashSet<>();
		private final ScanBudget budget = new ScanBudget();
		private ScanPriority priority;
		private int sectionIndex = Integer.MAX_VALUE;
		private int y = Integer.MIN_VALUE;
		private int localX;
		private int localZ;
		
		private ChunkScanState(ChunkPos pos, ScanPriority priority)
		{
			this.pos = pos;
			this.priority = priority;
		}
	}
	
	private static final class ScanProfile
	{
		private final int range;
		private final int scans;
		private final long budgetNs;
		private final int nearbyRadius;
		
		private ScanProfile(int range, int scans, long budgetNs,
			int nearbyRadius)
		{
			this.range = range;
			this.scans = scans;
			this.budgetNs = budgetNs;
			this.nearbyRadius = nearbyRadius;
		}
	}
	
	private enum DetectionMode
	{
		ALL("All"),
		HOLES_AND_TUNNELS("Holes + tunnels"),
		HOLES_AND_STAIRCASES("Holes + staircases"),
		TUNNELS_AND_STAIRCASES("Tunnels + staircases"),
		HOLES("Holes"),
		TUNNELS("Tunnels"),
		STAIRCASES("Staircases");
		
		private final String name;
		
		private DetectionMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
}
