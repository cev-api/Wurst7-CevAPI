/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.CameraTransformViewBobbingListener;
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
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkUtils;

public class ChestEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
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
	private int foundCount;
	
	private final CheckboxSetting onlyAboveGround =
		new CheckboxSetting("Above ground only",
			"Only show chests/containers at or above the configured Y level.",
			false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	private java.util.List<ChestEntry> openedChests = java.util.List.of();
	
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
		"Hides single chests that are near a mob spawner. Does not affect double chests or shulkers.",
		false);
	
	private final CheckboxSetting filterTrialChambers = new CheckboxSetting(
		"Filter trial chambers",
		"Hides single chests that match common trial chamber layouts. Does not affect double chests or shulkers.",
		false);
	
	private final CheckboxSetting filterVillages = new CheckboxSetting(
		"Filter villages",
		"Hides single chests that appear to belong to villages. Does not affect double chests or shulkers.",
		false);
	
	private List<BlockPos> cachedTrialSpawners = List.of();
	private List<Vec3> cachedVillagerPositions = List.of();
	private List<Vec3> cachedGolemPositions = List.of();
	
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
		addSetting(filterVillages);
		addSetting(showCountInHackList);
		groups.allGroups.stream().flatMap(ChestEspGroup::getSettings)
			.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		groups.allGroups.forEach(ChestEspGroup::clear);
		foundCount = 0;
		cachedTrialSpawners = List.of();
		cachedVillagerPositions = List.of();
		cachedGolemPositions = List.of();
	}
	
	@Override
	public void onUpdate()
	{
		groups.allGroups.forEach(ChestEspGroup::clear);
		
		double yLimit = aboveGroundY.getValue();
		boolean enforceAboveGround = onlyAboveGround.isChecked();
		
		ChunkUtils.getLoadedBlockEntities().forEach(be -> {
			if(enforceAboveGround && be.getBlockPos().getY() < yLimit)
				return;
			
			groups.blockGroups.forEach(group -> group.addIfMatches(be));
		});
		
		if(MC.level != null)
		{
			for(Entity entity : MC.level.entitiesForRendering())
			{
				if(enforceAboveGround && entity.getY() < yLimit)
					continue;
				
				groups.entityGroups
					.forEach(group -> group.addIfMatches(entity));
			}
		}
		
		refreshEnvironmentalCaches();
		
		int total = groups.allGroups.stream().filter(ChestEspGroup::isEnabled)
			.mapToInt(g -> g.getBoxes().size()).sum();
		foundCount = Math.min(total, 999);
		
		// Always load recorded chests from ChestSearch DB so ChestESP can
		// mark them even if the ChestSearch UI/hack isn't "enabled".
		try
		{
			ChestManager mgr = new ChestManager();
			openedChests = mgr.all();
		}catch(Throwable ignored)
		{
			openedChests = java.util.List.of();
		}
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
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
		try
		{
			var hax = net.wurstclient.WurstClient.INSTANCE.getHax();
			workstationEnabled = hax.workstationEspHack != null
				&& hax.workstationEspHack.isEnabled();
			redstoneEnabled =
				hax.redstoneEspHack != null && hax.redstoneEspHack.isEnabled();
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
		
		boolean applyEnvFilters =
			MC.level != null && (filterNearSpawners.isChecked()
				|| filterTrialChambers.isChecked()
				|| filterVillages.isChecked());
		
		for(ChestEspGroup group : groups.allGroups)
		{
			if(!group.isEnabled())
				continue;
			
			// Suppress overlapping categories when specialized hacks are active
			if(workstationEnabled
				&& (group == groups.crafters || group == groups.furnaces))
				continue;
			if(redstoneEnabled && (group == groups.droppers
				|| group == groups.dispensers || group == groups.hoppers))
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
			
			int quadsColor = group.getColorI(0x40);
			int linesColor = group.getColorI(0x80);
			
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
						? buriedColor.getColorI(0x40) : quadsColor;
					int bLine = highlightBuried.isChecked()
						? buriedColor.getColorI(0x80) : linesColor;
					RenderUtils.drawSolidBoxes(matrixStack, closedBuried, bFill,
						false);
					RenderUtils.drawOutlinedBoxes(matrixStack, closedBuried,
						bLine, false);
				}
				
				int markColor = csh.getMarkXColorARGB();
				int openedFillColor = (markColor & 0x00FFFFFF) | (0x40 << 24);
				int openedLineColor = markColor;
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
						int bFill = buriedColor.getColorI(0x40);
						int bLine = buriedColor.getColorI(0x80);
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
					for(AABB box : openedBoxes)
					{
						ChestSearchMarkerRenderer.drawMarker(matrixStack, box,
							csh.getMarkXColorARGB(), csh.getMarkXThickness(),
							false);
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
		try
		{
			var hax = net.wurstclient.WurstClient.INSTANCE.getHax();
			workstationEnabled = hax.workstationEspHack != null
				&& hax.workstationEspHack.isEnabled();
			redstoneEnabled =
				hax.redstoneEspHack != null && hax.redstoneEspHack.isEnabled();
		}catch(Throwable ignored)
		{}
		
		boolean applyEnvFilters =
			MC.level != null && (filterNearSpawners.isChecked()
				|| filterTrialChambers.isChecked()
				|| filterVillages.isChecked());
		
		for(ChestEspGroup group : groups.allGroups)
		{
			if(!group.isEnabled())
				continue;
			
			if(workstationEnabled
				&& (group == groups.crafters || group == groups.furnaces))
				continue;
			if(redstoneEnabled && (group == groups.droppers
				|| group == groups.dispensers || group == groups.hoppers))
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
			
			List<Vec3> ends = boxes.stream().map(AABB::getCenter).toList();
			int color = group.getColorI(0x80);
			
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, color,
				false);
		}
	}
	
	@Override
	public String getRenderName()
	{
		String base = getName();
		if(showCountInHackList.isChecked() && foundCount > 0)
			return base + " [" + foundCount + "]";
		return base;
	}
	
	private void refreshEnvironmentalCaches()
	{
		if(MC.level == null)
		{
			cachedTrialSpawners = List.of();
			cachedVillagerPositions = List.of();
			cachedGolemPositions = List.of();
			return;
		}
		
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
	}
	
	private List<BlockPos> collectTrialSpawnerPositions()
	{
		return ChunkUtils.getLoadedBlockEntities()
			.filter(be -> be instanceof TrialSpawnerBlockEntity)
			.map(BlockEntity::getBlockPos).map(BlockPos::immutable)
			.collect(Collectors.toList());
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
				out.add(box);
				continue;
			}
			
			if(filterNearSpawners.isChecked()
				&& isNearSpawner(singleChestPos, 7))
				continue;
			
			if(filterTrialChambers.isChecked()
				&& isTrialChamberChest(singleChestPos))
				continue;
			
			if(filterVillages.isChecked()
				&& isLikelyVillageChest(singleChestPos))
				continue;
			
			out.add(box);
		}
		
		return out;
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
						
						if(state.hasProperty(ChestBlock.TYPE))
						{
							ChestType t = state.getValue(ChestBlock.TYPE);
							if(t != ChestType.SINGLE)
								return null;
						}
						
						if(chestCount > 1)
							return null;
					}
				}
			}
		}
		
		return foundChest;
	}
	
	private boolean isNearSpawner(BlockPos center, int range)
	{
		return BlockUtils.getAllInBoxStream(center, range)
			.anyMatch(pos -> BlockUtils.getBlock(pos) == Blocks.SPAWNER);
	}
	
	private boolean isTrialChamberChest(BlockPos pos)
	{
		int y = pos.getY();
		if(y < -38 || y > 10)
			return false;
		
		if(!isNearWaxedCopper(pos, 5))
			return false;
		
		return isNearTrialSpawner(pos, 100);
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
		if(!hasDoorNearby(pos, 4))
			return false;
		
		boolean hasVillageEntity =
			isEntityWithinRange(cachedVillagerPositions, pos, 24)
				|| isEntityWithinRange(cachedGolemPositions, pos, 24);
		boolean hayCluster = hasHayBaleCluster(pos, 6);
		
		if(hasVillageEntity || hayCluster)
			return true;
		
		return hasGlassPaneCluster(pos, 4, 1);
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
}
