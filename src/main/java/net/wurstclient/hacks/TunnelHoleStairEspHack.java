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
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
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
public final class TunnelHoleStairEspHack extends Hack implements UpdateListener,
	RenderListener, CameraTransformViewBobbingListener, PacketInputListener
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
	private final SliderSetting minTunnelLength = new SliderSetting(
		"Min tunnel length", "Minimum length for a straight tunnel.", 4, 2, 30,
		1, ValueDisplay.INTEGER);
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
	private final SliderSetting maxPerChunk =
		new SliderSetting("Max spots per chunk",
			"Maximum amount of holes/tunnels/stair spots to keep per chunk.",
			24, 4, 256, 1, ValueDisplay.INTEGER);
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
	private ChunkPos stickyCenterChunk;
	private boolean lastStickyArea;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	
	private final ArrayList<AABB> holeBoxes = new ArrayList<>();
	private final ArrayList<AABB> tunnelBoxes = new ArrayList<>();
	private final ArrayList<AABB> stairBoxes = new ArrayList<>();
	
	private int scanConfigHash;
	
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
		addSetting(airOnly);
		addSetting(minYOffset);
		addSetting(maxYOffset);
		addSetting(minHoleDepth);
		addSetting(minTunnelLength);
		addSetting(minTunnelHeight);
		addSetting(maxTunnelHeight);
		addSetting(minStairLength);
		addSetting(minStairHeight);
		addSetting(maxStairHeight);
		addSetting(maxPerChunk);
		addSetting(naturalWallsOnly);
		addSetting(naturalWallRatio);
		addSetting(holeColor);
		addSetting(tunnelColor);
		addSetting(stairColor);
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
		if(holes + tunnels + stairs == 0)
			return getName();
		
		return getName() + " [" + holes + "H " + tunnels + "T " + stairs + "S]";
	}
	
	@Override
	protected void onEnable()
	{
		scanConfigHash = getScanConfigHash();
		stickyCenterChunk =
			MC.player == null ? null : MC.player.chunkPosition();
		lastStickyArea = stickyArea.isChecked();
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
		
		boolean sticky = stickyArea.isChecked();
		ChunkPos playerChunk = MC.player.chunkPosition();
		ChunkAreaSetting.ChunkArea currentAreaSelection = area.getSelected();
		
		if(currentAreaSelection != lastAreaSelection)
		{
			lastAreaSelection = currentAreaSelection;
			clearRuntimeState();
		}
		
		if(sticky != lastStickyArea)
		{
			lastStickyArea = sticky;
			stickyCenterChunk = playerChunk;
			clearRuntimeState();
		}else if(!sticky)
			stickyCenterChunk = playerChunk;
		else if(stickyCenterChunk == null)
			stickyCenterChunk = playerChunk;
		
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
	}
	
	private void renderTracers(PoseStack matrixStack, float partialTicks)
	{
		if(!holeBoxes.isEmpty())
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack, partialTicks,
				getCenters(holeBoxes), holeColor.getColorI(0x95), false);
		
		if(!tunnelBoxes.isEmpty())
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack, partialTicks,
				getCenters(tunnelBoxes), tunnelColor.getColorI(0x95), false);
		
		if(!stairBoxes.isEmpty())
			RenderUtils.drawTracers("TunnelHoleStairESP", matrixStack, partialTicks,
				getCenters(stairBoxes), stairColor.getColorI(0x95), false);
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
		
		if(!center.equals(cachedAreaCenter) || selection != cachedAreaSelection
			|| dimension != cachedAreaDimension)
		{
			areaChunkCache.clear();
			int chunkRange = getChunkRange(selection);
			for(int x = center.x - chunkRange; x <= center.x + chunkRange; x++)
				for(int z = center.z - chunkRange; z <= center.z
					+ chunkRange; z++)
					if(MC.level.hasChunk(x, z))
						areaChunkCache.add(new ChunkPos(x, z));
					
			cachedAreaCenter = center;
			cachedAreaSelection = selection;
			cachedAreaDimension = dimension;
		}
		
		return areaChunkCache;
	}
	
	private boolean syncToArea(HashSet<ChunkPos> areaChunks)
	{
		boolean changed = false;
		
		if(detectionsByChunk.keySet()
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
		for(ChunkPos pos : missingChunks)
			if(queuedChunks.add(pos))
				scanQueue.addLast(pos);
			
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
				scanQueue.addLast(pos);
			
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
		if(MC.level == null || !MC.level.hasChunk(chunkPos.x, chunkPos.z))
			return result;
		
		LevelChunk chunk = MC.level.getChunk(chunkPos.x, chunkPos.z);
		if(chunk == null)
			return result;
		
		int minY = MC.level.getMinY() + minYOffset.getValueI();
		int maxY = MC.level.getMaxY() - 1 - maxYOffset.getValueI();
		if(minY > maxY)
			return result;
		
		boolean detectHoles = shouldDetectHoles();
		boolean detectTunnels = shouldDetectTunnels();
		boolean detectStairs = shouldDetectStairs();
		int chunkLimit = Math.max(1, maxPerChunk.getValueI());
		
		BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
		LevelChunkSection[] sections = chunk.getSections();
		int minSectionY = chunk.getMinY() >> 4;
		
		for(int sectionIndex =
			0; sectionIndex < sections.length; sectionIndex++)
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
			
			for(int y = startY; y <= endY; y++)
				for(int lx = 0; lx < 16; lx++)
					for(int lz = 0; lz < 16; lz++)
					{
						if((!detectHoles || result.holes.size() >= chunkLimit)
							&& (!detectTunnels
								|| result.tunnels.size() >= chunkLimit)
							&& (!detectStairs
								|| result.stairs.size() >= chunkLimit))
							return result;
						
						int localY = y - sectionBaseY;
						BlockState state =
							section.getStates().get(lx, localY, lz);
						
						mutablePos.set(chunkPos.getMinBlockX() + lx, y,
							chunkPos.getMinBlockZ() + lz);
						if(!isPassable(mutablePos, state))
							continue;
						
						BlockPos pos = mutablePos.immutable();
						boolean hasSolidBelow = false;
						if((detectTunnels && result.tunnels.size() < chunkLimit)
							|| (detectStairs
								&& result.stairs.size() < chunkLimit))
							hasSolidBelow = isSolid(pos.below());
						
						if(detectHoles && result.holes.size() < chunkLimit)
							tryAddHole(pos, maxY, result);
						
						if(detectTunnels && hasSolidBelow
							&& result.tunnels.size() < chunkLimit)
							for(Direction dir : TUNNEL_DIRECTIONS)
								tryAddTunnel(pos, dir, result);
							
						if(detectStairs && hasSolidBelow
							&& result.stairs.size() < chunkLimit)
							for(Direction dir : CARDINALS)
								tryAddStaircase(pos, dir, result);
					}
		}
		
		return result;
	}
	
	private void tryAddHole(BlockPos start, int maxY, ChunkDetections result)
	{
		if(!isHoleSection(start))
			return;
		if(isHoleSection(start.below()))
			return;
		
		BlockPos.MutableBlockPos cursor = start.mutable();
		int depth = 0;
		int totalWalls = 0;
		int naturalWalls = 0;
		
		while(cursor.getY() <= maxY && isHoleSection(cursor))
		{
			if(naturalWallsOnly.isChecked())
			{
				for(Direction dir : CARDINALS)
				{
					totalWalls++;
					if(isLikelyNaturalWall(cursor.relative(dir)))
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
			start.getX() + 1, cursor.getY(), start.getZ() + 1);
		if(!intersectsAny(result.holes, box))
			result.holes.add(box);
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
		
		while(isTunnelSection(cursor, dir))
		{
			int height = getClearHeight(cursor, maxTunnelHeight.getValueI());
			minHeight = Math.min(minHeight, height);
			maxHeight = Math.max(maxHeight, height);
			end = cursor.immutable();
			cursor.move(dir);
			length++;
		}
		
		if(length < minTunnelLength.getValueI())
			return;
		if(maxHeight - minHeight > 1)
			return;
		
		AABB box = new AABB(Math.min(start.getX(), end.getX()), start.getY(),
			Math.min(start.getZ(), end.getZ()),
			Math.max(start.getX(), end.getX()) + 1, start.getY() + maxHeight,
			Math.max(start.getZ(), end.getZ()) + 1);
		
		if(!intersectsAny(result.tunnels, box))
			result.tunnels.add(box);
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
	
	private boolean isHoleSection(BlockPos pos)
	{
		if(!isPassable(pos))
			return false;
		
		for(Direction dir : CARDINALS)
			if(!isSolid(pos.relative(dir)))
				return false;
			
		return true;
	}
	
	private boolean isTunnelSection(BlockPos pos, Direction dir)
	{
		int minHeight = minTunnelHeight.getValueI();
		int maxHeight = maxTunnelHeight.getValueI();
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
		
		for(ChunkDetections detections : detectionsByChunk.values())
		{
			holeBoxes.addAll(detections.holes);
			tunnelBoxes.addAll(detections.tunnels);
			stairBoxes.addAll(detections.stairs);
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
	}
	
	private ChunkPos getAreaCenterChunk()
	{
		if(stickyArea.isChecked())
			return stickyCenterChunk == null ? MC.player.chunkPosition()
				: stickyCenterChunk;
		
		return MC.player.chunkPosition();
	}
	
	private int getChunkRange(ChunkAreaSetting.ChunkArea selection)
	{
		return selection.ordinal() + 1;
	}
	
	private int getChunkDistance(ChunkPos a, ChunkPos b)
	{
		return Math.abs(a.x - b.x) + Math.abs(a.z - b.z);
	}
	
	private int getScanConfigHash()
	{
		return Objects.hash(detectionMode.getSelected(), airOnly.isChecked(),
			minYOffset.getValueI(), maxYOffset.getValueI(),
			minHoleDepth.getValueI(), minTunnelLength.getValueI(),
			minTunnelHeight.getValueI(), maxTunnelHeight.getValueI(),
			minStairLength.getValueI(), minStairHeight.getValueI(),
			maxStairHeight.getValueI(), maxPerChunk.getValueI(),
			naturalWallsOnly.isChecked(), naturalWallRatio.getValue(),
			overworld.isChecked(), nether.isChecked(), end.isChecked());
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
