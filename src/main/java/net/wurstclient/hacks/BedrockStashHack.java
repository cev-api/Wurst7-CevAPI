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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;

@SearchTags({"bedrock stash", "bedrock stash esp", "stash finder"})
public final class BedrockStashHack extends Hack
	implements UpdateListener, RenderListener
{
	private static final Direction[] DIRECTIONS = Direction.values();
	private static final int MAX_POCKET_BLOCKS = 4096;
	
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to scan for bedrock stash pockets.",
		ChunkAreaSetting.ChunkArea.A5);
	private final SliderSetting chunksPerTick =
		new SliderSetting("Chunks per tick",
			"How many chunks are scanned each tick. Lower = better FPS.", 1, 1,
			8, 1, ValueDisplay.INTEGER);
	private final SliderSetting verticalRange =
		new SliderSetting("Vertical range",
			"Only scan this many blocks above and below your current Y level.",
			48, 8, 192, 1, ValueDisplay.INTEGER);
	private final SliderSetting renderLimit = new SliderSetting("Render limit",
		"Maximum number of stash boxes rendered at once.", 300, 50, 5000, 10,
		ValueDisplay.INTEGER);
	private final CheckboxSetting fillBoxes = new CheckboxSetting("Fill boxes",
		"Render solid box fill. Disable for wireframe-only performance mode.",
		false);
	private final ColorSetting airColor = new ColorSetting("Air color",
		"Color used for perfectly air-filled bedrock pockets.",
		new Color(90, 220, 255));
	private final ColorSetting breakableColor =
		new ColorSetting("Breakable color",
			"Color used for bedrock pockets containing breakable blocks.",
			new Color(255, 170, 60));
	
	private final ArrayDeque<ChunkPos> scanQueue = new ArrayDeque<>();
	private final HashSet<ChunkPos> queuedChunks = new HashSet<>();
	private final HashMap<ChunkPos, ArrayList<StashHit>> hitsByChunk =
		new HashMap<>();
	private final ArrayList<ColoredBox> airBoxes = new ArrayList<>();
	private final ArrayList<ColoredBox> breakableBoxes = new ArrayList<>();
	private final HashSet<Long> visitedPositions = new HashSet<>();
	
	private Level activeLevel;
	private ChunkPos lastPlayerChunk;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	private int lastCenterY = Integer.MIN_VALUE;
	private int sideBoundaryY = Integer.MIN_VALUE;
	private boolean playerAboveSideBoundary;
	private boolean hasSideBoundary;
	private int foundAirCount;
	private int foundBreakableCount;
	
	public BedrockStashHack()
	{
		super("BedrockStash");
		setCategory(Category.RENDER);
		addSetting(area);
		addSetting(chunksPerTick);
		addSetting(verticalRange);
		addSetting(renderLimit);
		addSetting(fillBoxes);
		addSetting(airColor);
		addSetting(breakableColor);
	}
	
	@Override
	protected void onEnable()
	{
		clearState();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		clearState();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null || MC.player == null)
		{
			clearState();
			return;
		}
		
		if(activeLevel != MC.level)
		{
			clearState();
			activeLevel = MC.level;
		}
		
		ChunkPos currentChunk = MC.player.chunkPosition();
		ChunkAreaSetting.ChunkArea currentArea = area.getSelected();
		int centerY = MC.player.getBlockY();
		updateSideBoundary();
		boolean yChanged = lastCenterY != centerY;
		if(lastPlayerChunk == null || !lastPlayerChunk.equals(currentChunk)
			|| lastAreaSelection != currentArea || yChanged)
		{
			lastPlayerChunk = currentChunk;
			lastAreaSelection = currentArea;
			lastCenterY = centerY;
			rebuildScanQueue();
			if(yChanged)
				hitsByChunk.clear();
		}
		
		if(scanQueue.isEmpty())
			rebuildScanQueue();
		
		for(int i = 0; i < chunksPerTick.getValueI()
			&& !scanQueue.isEmpty(); i++)
			scanChunk(scanQueue.removeFirst());
		
		recountHits();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(airBoxes.isEmpty() && breakableBoxes.isEmpty())
			return;
		
		List<ColoredBox> limitedAir = limitBoxes(airBoxes);
		List<ColoredBox> limitedBreakable = limitBoxes(breakableBoxes);
		
		if(!limitedAir.isEmpty())
		{
			if(fillBoxes.isChecked())
				RenderUtils.drawSolidBoxes(matrixStack, limitedAir, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, limitedAir, false, 2.0);
		}
		
		if(!limitedBreakable.isEmpty())
		{
			if(fillBoxes.isChecked())
				RenderUtils.drawSolidBoxes(matrixStack, limitedBreakable,
					false);
			RenderUtils.drawOutlinedBoxes(matrixStack, limitedBreakable, false,
				2.0);
		}
	}
	
	@Override
	public String getRenderName()
	{
		int total = foundAirCount + foundBreakableCount;
		if(total == 0)
			return getName();
		
		return getName() + " [" + foundAirCount + "A " + foundBreakableCount
			+ "B]";
	}
	
	private void rebuildScanQueue()
	{
		scanQueue.clear();
		queuedChunks.clear();
		visitedPositions.clear();
		
		HashSet<ChunkPos> currentArea = new HashSet<>();
		for(var chunk : area.getChunksInRange())
		{
			ChunkPos pos = chunk.getPos();
			currentArea.add(pos);
			scanQueue.addLast(pos);
			queuedChunks.add(pos);
		}
		
		hitsByChunk.keySet().removeIf(pos -> !currentArea.contains(pos));
	}
	
	private void scanChunk(ChunkPos chunkPos)
	{
		if(MC.level == null)
			return;
		
		ArrayList<StashHit> hits = new ArrayList<>();
		int minY = MC.level.getMinY();
		int maxY = minY + MC.level.getHeight() - 1;
		int centerY = getScanCenterY();
		int scanMinY = Math.max(minY, centerY - verticalRange.getValueI());
		int scanMaxY = Math.min(maxY, centerY + verticalRange.getValueI());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		
		for(int x = chunkPos.getMinBlockX(); x <= chunkPos.getMaxBlockX(); x++)
			for(int z = chunkPos.getMinBlockZ(); z <= chunkPos
				.getMaxBlockZ(); z++)
				for(int y = scanMinY; y <= scanMaxY; y++)
				{
					pos.set(x, y, z);
					if(MC.level.getBlockState(pos).is(Blocks.BEDROCK))
						continue;
					if(!hasAdjacentBedrock(pos))
						continue;
					
					tryAddPocket(pos.immutable(), hits);
				}
			
		hitsByChunk.put(chunkPos, hits);
	}
	
	private void tryAddPocket(BlockPos start, ArrayList<StashHit> hits)
	{
		if(MC.level == null)
			return;
		
		long startKey = start.asLong();
		if(visitedPositions.contains(startKey))
			return;
		
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		HashSet<Long> componentSet = new HashSet<>();
		ArrayList<BlockPos> component = new ArrayList<>();
		queue.add(start);
		componentSet.add(startKey);
		
		boolean allAir = true;
		boolean leaked = false;
		boolean tooLarge = false;
		
		while(!queue.isEmpty())
		{
			BlockPos current = queue.removeFirst();
			if(!MC.level.hasChunkAt(current))
			{
				leaked = true;
				continue;
			}
			
			BlockState state = MC.level.getBlockState(current);
			if(state.is(Blocks.BEDROCK))
				continue;
			
			component.add(current);
			if(component.size() > MAX_POCKET_BLOCKS)
			{
				tooLarge = true;
				break;
			}
			
			if(!state.isAir())
				allAir = false;
			
			for(Direction dir : DIRECTIONS)
			{
				BlockPos neighbor = current.relative(dir);
				if(!MC.level.hasChunkAt(neighbor))
				{
					leaked = true;
					continue;
				}
				
				BlockState neighborState = MC.level.getBlockState(neighbor);
				if(neighborState.is(Blocks.BEDROCK))
					continue;
				
				long key = neighbor.asLong();
				if(!componentSet.add(key))
					continue;
				
				queue.add(neighbor);
			}
		}
		
		visitedPositions.addAll(componentSet);
		
		if(leaked || tooLarge)
			return;
		if(component.size() < 2)
			return;
		if(!hasAtLeastOneTwoTallColumn(component, componentSet))
			return;
		
		if(!isEnclosedByBedrock(component, componentSet))
			return;
		
		addColumnHits(component, componentSet, allAir, hits);
	}
	
	private boolean isEnclosedByBedrock(ArrayList<BlockPos> component,
		HashSet<Long> componentSet)
	{
		if(MC.level == null)
			return false;
		
		for(BlockPos pos : component)
			for(Direction dir : DIRECTIONS)
			{
				BlockPos neighbor = pos.relative(dir);
				if(componentSet.contains(neighbor.asLong()))
					continue;
				
				if(!MC.level.hasChunkAt(neighbor))
					return false;
				if(!MC.level.getBlockState(neighbor).is(Blocks.BEDROCK))
					return false;
			}
		
		return true;
	}
	
	private boolean hasAdjacentBedrock(BlockPos pos)
	{
		if(MC.level == null)
			return false;
		
		for(Direction dir : DIRECTIONS)
		{
			BlockPos neighbor = pos.relative(dir);
			if(!MC.level.hasChunkAt(neighbor))
				continue;
			if(MC.level.getBlockState(neighbor).is(Blocks.BEDROCK))
				return true;
		}
		
		return false;
	}
	
	private boolean hasAtLeastOneTwoTallColumn(ArrayList<BlockPos> component,
		HashSet<Long> componentSet)
	{
		for(BlockPos pos : component)
		{
			if(componentSet.contains(pos.below().asLong()))
				continue;
			if(componentSet.contains(pos.above().asLong()))
				return true;
		}
		
		return false;
	}
	
	private void addColumnHits(ArrayList<BlockPos> component,
		HashSet<Long> componentSet, boolean allAir, ArrayList<StashHit> hits)
	{
		for(BlockPos pos : component)
		{
			if(componentSet.contains(pos.below().asLong()))
				continue;
			
			int x = pos.getX();
			int y = pos.getY();
			int z = pos.getZ();
			int topY = y;
			while(componentSet.contains(BlockPos.asLong(x, topY + 1, z)))
				topY++;
			
			// Do not render 1x1 / 1-high columns.
			if(topY <= y)
				continue;
			
			hits.add(new StashHit(new AABB(x, y, z, x + 1, topY + 1, z + 1),
				allAir));
		}
	}
	
	private void recountHits()
	{
		airBoxes.clear();
		breakableBoxes.clear();
		foundAirCount = 0;
		foundBreakableCount = 0;
		
		for(ArrayList<StashHit> hits : hitsByChunk.values())
			for(StashHit hit : hits)
			{
				if(!isHitOnPlayerSide(hit.box()))
					continue;
				
				ColoredBox coloredBox = new ColoredBox(hit.box(),
					hit.air() ? airColor.getColorI(0xC0)
						: breakableColor.getColorI(0xB0));
				if(hit.air())
				{
					airBoxes.add(coloredBox);
					foundAirCount++;
				}else
				{
					breakableBoxes.add(coloredBox);
					foundBreakableCount++;
				}
			}
	}
	
	private void clearState()
	{
		activeLevel = null;
		lastPlayerChunk = null;
		lastAreaSelection = null;
		lastCenterY = Integer.MIN_VALUE;
		sideBoundaryY = Integer.MIN_VALUE;
		playerAboveSideBoundary = false;
		hasSideBoundary = false;
		scanQueue.clear();
		queuedChunks.clear();
		visitedPositions.clear();
		hitsByChunk.clear();
		airBoxes.clear();
		breakableBoxes.clear();
		foundAirCount = 0;
		foundBreakableCount = 0;
	}
	
	private List<ColoredBox> limitBoxes(ArrayList<ColoredBox> boxes)
	{
		int max = renderLimit.getValueI();
		if(boxes.size() <= max)
			return boxes;
		
		return boxes.subList(0, max);
	}
	
	private int getScanCenterY()
	{
		if(MC.player == null)
			return 0;
			
		// Center scans around the nearest relevant bedrock boundary so
		// roof/floor
		// layers are detected even when player Y is far away from the bedrock
		// plane.
		if(hasSideBoundary)
			return sideBoundaryY;
		
		return MC.player.getBlockY();
	}
	
	private void updateSideBoundary()
	{
		hasSideBoundary = false;
		if(MC.level == null || MC.player == null)
			return;
		
		int px = MC.player.getBlockX();
		int py = MC.player.getBlockY();
		int pz = MC.player.getBlockZ();
		int minY = MC.level.getMinY();
		int maxY = minY + MC.level.getHeight() - 1;
		
		int aboveY = Integer.MIN_VALUE;
		for(int y = py; y <= maxY; y++)
		{
			if(MC.level.getBlockState(new BlockPos(px, y, pz))
				.is(Blocks.BEDROCK))
			{
				aboveY = y;
				break;
			}
		}
		
		int belowY = Integer.MIN_VALUE;
		for(int y = py; y >= minY; y--)
		{
			if(MC.level.getBlockState(new BlockPos(px, y, pz))
				.is(Blocks.BEDROCK))
			{
				belowY = y;
				break;
			}
		}
		
		boolean hasAbove = aboveY != Integer.MIN_VALUE;
		boolean hasBelow = belowY != Integer.MIN_VALUE;
		if(!hasAbove && !hasBelow)
			return;
		
		if(hasBelow && (!hasAbove || py - belowY <= aboveY - py))
		{
			sideBoundaryY = belowY;
			playerAboveSideBoundary = true;
		}else
		{
			sideBoundaryY = aboveY;
			playerAboveSideBoundary = false;
		}
		
		hasSideBoundary = true;
	}
	
	private boolean isHitOnPlayerSide(AABB box)
	{
		if(MC.level != null && MC.player != null
			&& MC.level.dimension() == Level.NETHER)
		{
			boolean onRoofSide = MC.player.getBlockY() >= 123;
			return onRoofSide ? box.minY >= 123 : box.maxY < 123;
		}
		
		if(!hasSideBoundary)
			return true;
		
		if(playerAboveSideBoundary)
			return box.minY >= sideBoundaryY + 1;
		
		return box.maxY <= sideBoundaryY;
	}
	
	private record StashHit(AABB box, boolean air)
	{}
}
