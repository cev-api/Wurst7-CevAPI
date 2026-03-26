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
	private final ArrayDeque<ChunkPos> scanQueue = new ArrayDeque<>();
	private final HashSet<ChunkPos> queuedChunks = new HashSet<>();
	private final ConcurrentLinkedQueue<ChunkPos> dirtyChunkQueue =
		new ConcurrentLinkedQueue<>();
	private final java.util.Set<ChunkPos> dirtyChunkSet =
		ConcurrentHashMap.newKeySet();
	private final HashMap<Block, Boolean> naturalWallCache = new HashMap<>();
	private final HashSet<ChunkPos> areaChunkCache = new HashSet<>();
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
	
	public TunnelHoleStairEspHack()
	{
		super("TunnelHoleStairESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(detectionMode);
		addSetting(stickyArea);
		addSetting(area);
		addSetting(chunksPerTick);
		addSetting(scanTimeBudgetMs);
		addSetting(refreshInterval);
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
		clearRuntimeState();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		ChunkAreaSetting.ChunkArea currentAreaSelection = area.getSelected();
		
		if(currentAreaSelection != lastAreaSelection)
		{
			lastAreaSelection = currentAreaSelection;
			clearRuntimeState();
		}
		
		if(!isEnabledInCurrentDimension())
		{
			if(!detectionsByChunk.isEmpty() || !scanQueue.isEmpty())
				clearRuntimeState();
			return;
		}
		
		int currentHash = getScanConfigHash();
		if(currentHash != scanConfigHash)
		{
			scanConfigHash = currentHash;
			clearRuntimeState();
		}
		
		HashSet<ChunkPos> areaChunks = getAreaChunks();
		boolean changed = syncToArea(areaChunks);
		enqueueDirtyChunks(areaChunks);
		enqueuePeriodicRefresh(areaChunks);
		changed |= processQueuedScans(areaChunks);
		
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
		if(!holeBoxes.isEmpty())
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack,
				partialTicks, getCenters(holeBoxes), holeColor.getColorI(0x95),
				false);
		
		if(!tunnelBoxes.isEmpty())
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack,
				partialTicks, getCenters(tunnelBoxes),
				tunnelColor.getColorI(0x95), false);
		
		if(!stairBoxes.isEmpty())
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack,
				partialTicks, getCenters(stairBoxes),
				stairColor.getColorI(0x95), false);
		
		if(!ladderBoxes.isEmpty())
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack,
				partialTicks, getCenters(ladderBoxes),
				ladderColor.getColorI(0x95), false);
		
		if(!bubbleColumnBoxes.isEmpty())
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack,
				partialTicks, getCenters(bubbleColumnBoxes),
				bubbleColumnColor.getColorI(0x95), false);
		
		if(!waterColumnBoxes.isEmpty())
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack,
				partialTicks, getCenters(waterColumnBoxes),
				waterColumnColor.getColorI(0x95), false);
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
	
	private HashSet<ChunkPos> getAreaChunks()
	{
		ChunkPos center = getAreaCenterChunk();
		ChunkAreaSetting.ChunkArea selection = area.getSelected();
		ResourceKey<Level> dimension = MC.level.dimension();
		
		// Rebuild every tick so newly loaded chunks get scanned without
		// requiring
		// a hack toggle or area change.
		areaChunkCache.clear();
		int chunkRange = getChunkRange(selection);
		for(int x = center.x() - chunkRange; x <= center.x() + chunkRange; x++)
			for(int z = center.z() - chunkRange; z <= center.z()
				+ chunkRange; z++)
				if(MC.level.hasChunk(x, z))
					areaChunkCache.add(new ChunkPos(x, z));
				
		cachedAreaCenter = center;
		cachedAreaSelection = selection;
		cachedAreaDimension = dimension;
		
		return areaChunkCache;
	}
	
	private boolean syncToArea(HashSet<ChunkPos> areaChunks)
	{
		boolean changed = false;
		
		if(!stickyArea.isChecked() && detectionsByChunk.keySet()
			.removeIf(pos -> !areaChunks.contains(pos)))
			changed = true;
		
		scanQueue.removeIf(pos -> !areaChunks.contains(pos));
		queuedChunks.retainAll(areaChunks);
		
		ChunkPos priorityCenter = getAreaCenterChunk();
		ArrayList<ChunkPos> missingChunks = new ArrayList<>();
		for(ChunkPos pos : areaChunks)
			if(!detectionsByChunk.containsKey(pos)
				&& !queuedChunks.contains(pos))
				missingChunks.add(pos);
			
		missingChunks.sort(Comparator
			.comparingInt(pos -> getChunkDistance(pos, priorityCenter)));
		// Prioritize nearby chunks so stale long-range detections are corrected
		// quickly when the player moves toward them.
		for(int i = missingChunks.size() - 1; i >= 0; i--)
		{
			ChunkPos pos = missingChunks.get(i);
			if(queuedChunks.add(pos))
				scanQueue.addFirst(pos);
		}
		
		return changed;
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
			
			if(queuedChunks.add(pos))
				scanQueue.addFirst(pos);
			
			promoted++;
		}
	}
	
	private boolean processQueuedScans(HashSet<ChunkPos> areaChunks)
	{
		int scans = Math.max(1, chunksPerTick.getValueI());
		long budgetNs = Math.max(1L, scanTimeBudgetMs.getValueI()) * 1_000_000L;
		long startNs = System.nanoTime();
		boolean changed = false;
		
		for(int i = 0; i < scans && !scanQueue.isEmpty(); i++)
		{
			if(System.nanoTime() - startNs >= budgetNs)
				break;
			
			ChunkPos pos = scanQueue.removeFirst();
			queuedChunks.remove(pos);
			
			if(!areaChunks.contains(pos))
				continue;
			
			ChunkDetections detections = scanChunk(pos);
			detectionsByChunk.put(pos, detections);
			changed = true;
		}
		
		return changed;
	}
	
	private ChunkDetections scanChunk(ChunkPos chunkPos)
	{
		ChunkDetections result = new ChunkDetections();
		if(MC.level == null || !MC.level.hasChunk(chunkPos.x(), chunkPos.z()))
			return result;
		
		LevelChunk chunk = MC.level.getChunk(chunkPos.x(), chunkPos.z());
		if(chunk == null)
			return result;
		
		int minY = MC.level.getMinY() + minYOffset.getValueI();
		int maxY = MC.level.getMaxY() - 1 - maxYOffset.getValueI();
		if(minY > maxY)
			return result;
		
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
		
		for(int sectionIndex =
			sections.length - 1; sectionIndex >= 0; sectionIndex--)
		{
			LevelChunkSection section = sections[sectionIndex];
			if(section == null || section.hasOnlyAir())
				continue;
			
			int sectionBaseY = (minSectionY + sectionIndex) << 4;
			int sectionTopY = sectionBaseY + 15;
			if(sectionTopY < minY || sectionBaseY > maxY)
				continue;
			
			int startY = Math.max(minY, sectionBaseY);
			int endY = Math.min(maxY, sectionTopY);
			
			for(int y = endY; y >= startY; y--)
				for(int lx = 0; lx < 16; lx++)
					for(int lz = 0; lz < 16; lz++)
					{
						if((!detectHoles || result.holes.size() >= chunkLimit)
							&& (!detectTunnels
								|| result.tunnels.size() >= chunkLimit)
							&& (!detectStairs
								|| result.stairs.size() >= chunkLimit)
							&& (!laddersEnabled
								|| result.ladders.size() >= chunkLimit)
							&& (!bubbleColumnsEnabled
								|| result.bubbleColumns.size() >= chunkLimit)
							&& (!waterColumnsEnabled
								|| result.waterColumns.size() >= chunkLimit))
							return result;
						
						int localY = y - sectionBaseY;
						BlockState state =
							section.getStates().get(lx, localY, lz);
						
						mutablePos.set(chunkPos.getMinBlockX() + lx, y,
							chunkPos.getMinBlockZ() + lz);
						BlockPos pos = mutablePos.immutable();
						
						if(laddersEnabled && result.ladders.size() < chunkLimit)
							tryAddLadderColumn(pos, state, minY, maxY, result);
						
						if(bubbleColumnsEnabled
							&& result.bubbleColumns.size() < chunkLimit)
							tryAddBubbleColumn(pos, state, minY, maxY, result);
						
						if(waterColumnsEnabled
							&& result.waterColumns.size() < chunkLimit)
							tryAddWaterColumn(pos, state, minY, maxY, result);
						
						boolean passable = isPassable(mutablePos, state);
						boolean holePassable =
							detectHoles && isHolePassable(mutablePos, state);
						boolean tunnelPassable = detectTunnels
							&& isTunnelPassable(mutablePos, state);
						if(!passable && !holePassable && !tunnelPassable)
							continue;
						
						boolean hasSolidBelow = false;
						if((detectTunnels && tunnelPassable
							&& result.tunnels.size() < chunkLimit)
							|| (detectStairs && passable
								&& result.stairs.size() < chunkLimit))
							hasSolidBelow = isSolid(pos.below());
						
						if(detectHoles && holePassable
							&& result.holes.size() < chunkLimit)
							tryAddHole(pos, maxY, result);
						
						if(detectTunnels && tunnelPassable && hasSolidBelow
							&& result.tunnels.size() < chunkLimit)
							for(Direction dir : TUNNEL_DIRECTIONS)
								tryAddTunnel(pos, dir, result);
							
						if(detectStairs && passable && hasSolidBelow
							&& result.stairs.size() < chunkLimit)
							for(Direction dir : CARDINALS)
								tryAddStaircase(pos, dir, result);
					}
		}
		
		return result;
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
			return;
		
		if(refreshTimerTicks > 0)
		{
			refreshTimerTicks--;
			return;
		}
		
		refreshTimerTicks = intervalTicks;
		for(ChunkPos pos : areaChunks)
			if(queuedChunks.add(pos))
				scanQueue.addLast(pos);
	}
	
	private void tryAddTunnel(BlockPos start, Direction dir,
		ChunkDetections result)
	{
		if(!isTunnelSection(start, dir))
			return;
		if(isTunnelSection(start.relative(dir.getOpposite()), dir))
			return;
		
		BlockPos.MutableBlockPos cursor = start.mutable();
		BlockPos end = start;
		int length = 0;
		int minHeight = Integer.MAX_VALUE;
		int maxHeight = Integer.MIN_VALUE;
		int minWidth = Integer.MAX_VALUE;
		int maxWidth = Integer.MIN_VALUE;
		
		while(isTunnelSection(cursor, dir))
		{
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
		
		Direction side = dir.getClockWise();
		BlockPos sideEnd = end.relative(side, maxWidth - 1);
		AABB box = new AABB(
			Math.min(Math.min(start.getX(), end.getX()), sideEnd.getX()),
			start.getY(),
			Math.min(Math.min(start.getZ(), end.getZ()), sideEnd.getZ()),
			Math.max(Math.max(start.getX(), end.getX()), sideEnd.getX()) + 1,
			start.getY() + maxHeight,
			Math.max(Math.max(start.getZ(), end.getZ()), sideEnd.getZ()) + 1);
		
		if(!intersectsAny(result.tunnels, box))
			result.tunnels.add(box);
	}
	
	private void tryAddLadderColumn(BlockPos start, BlockState startState,
		int minY, int maxY, ChunkDetections result)
	{
		if(!(startState.getBlock() instanceof LadderBlock))
			return;
		if(start.getY() > minY && MC.level.getBlockState(start.below())
			.getBlock() instanceof LadderBlock)
			return;
		
		BlockPos.MutableBlockPos cursor = start.mutable();
		int height = 0;
		while(cursor.getY() <= maxY
			&& MC.level.getBlockState(cursor).getBlock() instanceof LadderBlock)
		{
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
			if(MC.level.getBlockState(belowPos)
				.getBlock() instanceof BubbleColumnBlock
				&& isBubbleColumnSectionInHole(belowPos))
				return;
		}
		
		BlockPos.MutableBlockPos cursor = start.mutable();
		int height = 0;
		while(cursor.getY() <= maxY
			&& MC.level.getBlockState(cursor)
				.getBlock() instanceof BubbleColumnBlock
			&& isBubbleColumnSectionInHole(cursor))
		{
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
			BlockState below = MC.level.getBlockState(belowPos);
			if(below.getFluidState().is(FluidTags.WATER)
				&& !(below.getBlock() instanceof BubbleColumnBlock)
				&& isBubbleColumnSectionInHole(belowPos))
				return;
		}
		
		BlockPos.MutableBlockPos cursor = start.mutable();
		int height = 0;
		while(cursor.getY() <= maxY)
		{
			BlockState state = MC.level.getBlockState(cursor);
			if(!state.getFluidState().is(FluidTags.WATER)
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
		BlockState state = MC.level.getBlockState(pos);
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
		return isTunnelPassable(pos, MC.level.getBlockState(pos));
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
		return isPassable(pos, MC.level.getBlockState(pos));
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
		BlockState state = MC.level.getBlockState(pos);
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
		
		return height;
	}
	
	private boolean isLikelyNaturalWall(BlockPos pos)
	{
		BlockState state = MC.level.getBlockState(pos);
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
		
		for(Map.Entry<ChunkPos, ChunkDetections> entry : detectionsByChunk
			.entrySet())
		{
			ChunkPos chunkPos = entry.getKey();
			if(MC.level == null
				|| !MC.level.hasChunk(chunkPos.x(), chunkPos.z()))
				continue;
			
			ChunkDetections detections = entry.getValue();
			holeBoxes.addAll(detections.holes);
			tunnelBoxes.addAll(detections.tunnels);
			stairBoxes.addAll(detections.stairs);
			ladderBoxes.addAll(detections.ladders);
			bubbleColumnBoxes.addAll(detections.bubbleColumns);
			waterColumnBoxes.addAll(detections.waterColumns);
		}
	}
	
	private void clearRuntimeState()
	{
		detectionsByChunk.clear();
		scanQueue.clear();
		queuedChunks.clear();
		dirtyChunkQueue.clear();
		dirtyChunkSet.clear();
		areaChunkCache.clear();
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
		return selection.ordinal() + 1;
	}
	
	private int getChunkDistance(ChunkPos a, ChunkPos b)
	{
		return Math.abs(a.x() - b.x()) + Math.abs(a.z() - b.z());
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
			refreshInterval.getValueI(), naturalWallsOnly.isChecked(),
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
	
	private static final class ChunkDetections
	{
		private final ArrayList<AABB> holes = new ArrayList<>();
		private final ArrayList<AABB> tunnels = new ArrayList<>();
		private final ArrayList<AABB> stairs = new ArrayList<>();
		private final ArrayList<AABB> ladders = new ArrayList<>();
		private final ArrayList<AABB> bubbleColumns = new ArrayList<>();
		private final ArrayList<AABB> waterColumns = new ArrayList<>();
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
