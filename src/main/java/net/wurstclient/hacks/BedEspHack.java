/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.bedesp.BedEspBlockGroup;
// checkbox setting not needed here (stickyArea uses fully-qualified name)
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.chunk.ChunkSearcher.Result;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;
import net.wurstclient.util.chunk.ChunkUtils;

@SearchTags({"BedESP", "bed esp"})
public final class BedEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	private final net.wurstclient.settings.CheckboxSetting stickyArea =
		new net.wurstclient.settings.CheckboxSetting("Sticky area",
			"Off: Re-centers every chunk to match ESP drop-off.\n"
				+ "On: Keeps results anchored so you can path back to them.",
			false);
	
	private final BedEspBlockGroup beds =
		new BedEspBlockGroup(new ColorSetting("Bed color",
			"Beds will be highlighted in this color.", new Color(0xFF69B4)));
	
	private final List<BedEspBlockGroup> groups = Arrays.asList(beds);
	// New: optionally show detected count in HackList
	private final net.wurstclient.settings.CheckboxSetting showCountInHackList =
		new net.wurstclient.settings.CheckboxSetting("HackList count",
			"Appends the number of found beds to this hack's entry in the HackList.",
			false);
	
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.");
	
	// Above-ground filter
	private final net.wurstclient.settings.CheckboxSetting onlyAboveGround =
		new net.wurstclient.settings.CheckboxSetting("Above ground only",
			"Only show beds at or above the configured Y level.", false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	private final net.wurstclient.settings.CheckboxSetting filterTrialChambers =
		new net.wurstclient.settings.CheckboxSetting("Filter trial chambers",
			"Hides beds that match common trial chamber layouts.", false);
	private final net.wurstclient.settings.CheckboxSetting filterVillageBeds =
		new net.wurstclient.settings.CheckboxSetting("Filter village beds",
			"Hides beds that appear to belong to villages.", false);
	
	private final BiPredicate<BlockPos, BlockState> query =
		(pos, state) -> state.getBlock() instanceof BedBlock;
	
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(query, area);
	
	private boolean groupsUpToDate;
	private ChunkPos lastPlayerChunk;
	private int foundCount;
	private int lastMatchesVersion;
	private List<BlockPos> cachedTrialSpawners = List.of();
	private List<Vec3> cachedVillagerPositions = List.of();
	private List<Vec3> cachedGolemPositions = List.of();
	private static final TagKey<Block> WAXED_COPPER_BLOCKS_TAG = TagKey.create(
		Registries.BLOCK,
		Identifier.fromNamespaceAndPath("minecraft", "waxed_copper_blocks"));
	private boolean lastTrialFilterState;
	private boolean lastVillageFilterState;
	
	public BedEspHack()
	{
		super("BedESP");
		setCategory(Category.RENDER);
		addSetting(style);
		groups.stream().flatMap(BedEspBlockGroup::getSettings)
			.forEach(this::addSetting);
		addSetting(showCountInHackList);
		addSetting(area);
		addSetting(stickyArea);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
		addSetting(filterTrialChambers);
		addSetting(filterVillageBeds);
		
		lastTrialFilterState = filterTrialChambers.isChecked();
		lastVillageFilterState = filterVillageBeds.isChecked();
	}
	
	@Override
	protected void onEnable()
	{
		groupsUpToDate = false;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, coordinator);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
		lastPlayerChunk = new ChunkPos(MC.player.blockPosition());
		lastMatchesVersion = coordinator.getMatchesVersion();
		lastTrialFilterState = filterTrialChambers.isChecked();
		lastVillageFilterState = filterVillageBeds.isChecked();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, coordinator);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		coordinator.reset();
		lastMatchesVersion = coordinator.getMatchesVersion();
		groups.forEach(BedEspBlockGroup::clear);
		// reset count
		foundCount = 0;
		cachedTrialSpawners = List.of();
		cachedVillagerPositions = List.of();
		cachedGolemPositions = List.of();
	}
	
	@Override
	public void onUpdate()
	{
		boolean searchersChanged = coordinator.update();
		if(searchersChanged)
			groupsUpToDate = false;
		int matchesVersion = coordinator.getMatchesVersion();
		if(matchesVersion != lastMatchesVersion)
		{
			lastMatchesVersion = matchesVersion;
			groupsUpToDate = false;
		}
		// Recenter per chunk when sticky is off
		ChunkPos currentChunk = new ChunkPos(MC.player.blockPosition());
		if(!stickyArea.isChecked() && !currentChunk.equals(lastPlayerChunk))
		{
			lastPlayerChunk = currentChunk;
			coordinator.reset();
			groupsUpToDate = false;
		}
		
		if(didFiltersChange())
			groupsUpToDate = false;
		
		if(!groupsUpToDate && coordinator.isDone())
			updateGroupBoxes();
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
		if(style.hasBoxes())
			renderBoxes(matrixStack);
		
		if(style.hasLines())
			renderTracers(matrixStack, partialTicks);
	}
	
	private void renderBoxes(PoseStack matrixStack)
	{
		for(BedEspBlockGroup group : groups)
		{
			if(!group.isEnabled())
				continue;
			
			List<AABB> boxes = group.getBoxes();
			int quadsColor = group.getColorI(0x40);
			int linesColor = group.getColorI(0x80);
			
			RenderUtils.drawSolidBoxes(matrixStack, boxes, quadsColor, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, linesColor,
				false);
		}
	}
	
	private void renderTracers(PoseStack matrixStack, float partialTicks)
	{
		for(BedEspBlockGroup group : groups)
		{
			if(!group.isEnabled())
				continue;
			
			List<AABB> boxes = group.getBoxes();
			List<Vec3> ends = boxes.stream().map(AABB::getCenter).toList();
			int color = group.getColorI(0x80);
			
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, color,
				false);
		}
	}
	
	private void updateGroupBoxes()
	{
		groups.forEach(BedEspBlockGroup::clear);
		java.util.List<Result> results = coordinator.getMatches().toList();
		refreshEnvironmentalCaches();
		results.forEach(this::addToGroupBoxes);
		groupsUpToDate = true;
		// update count for HUD (clamped to 999) based on displayed boxes
		int total = groups.stream().mapToInt(g -> g.getBoxes().size()).sum();
		foundCount = Math.min(total, 999);
	}
	
	@Override
	public String getRenderName()
	{
		String base = getName();
		if(showCountInHackList.isChecked() && foundCount > 0)
			return base + " [" + foundCount + "]";
		return base;
	}
	
	private void addToGroupBoxes(Result result)
	{
		BlockState state = result.state();
		if(!(state.getBlock() instanceof BedBlock)
			|| state.getValue(BedBlock.PART) == BedPart.FOOT)
			return;
		
		BlockPos headPos = result.pos();
		if(onlyAboveGround.isChecked()
			&& headPos.getY() < aboveGroundY.getValue())
			return;
		
		if(filterTrialChambers.isChecked() && isTrialChamberBed(headPos))
			return;
		
		if(filterVillageBeds.isChecked() && isLikelyVillageBed(headPos))
			return;
		for(BedEspBlockGroup group : groups)
		{
			group.add(result);
			break;
		}
	}
	
	private void refreshEnvironmentalCaches()
	{
		if(filterTrialChambers.isChecked())
			cachedTrialSpawners = collectTrialSpawnerPositions();
		else
			cachedTrialSpawners = List.of();
		
		if(filterVillageBeds.isChecked())
		{
			cachedVillagerPositions = collectEntityPositions(Villager.class);
			cachedGolemPositions = collectEntityPositions(IronGolem.class);
		}else
		{
			cachedVillagerPositions = List.of();
			cachedGolemPositions = List.of();
		}
	}
	
	private boolean didFiltersChange()
	{
		boolean trial = filterTrialChambers.isChecked();
		boolean village = filterVillageBeds.isChecked();
		if(trial != lastTrialFilterState || village != lastVillageFilterState)
		{
			lastTrialFilterState = trial;
			lastVillageFilterState = village;
			return true;
		}
		
		return false;
	}
	
	private List<BlockPos> collectTrialSpawnerPositions()
	{
		if(MC.level == null)
			return List.of();
		
		return ChunkUtils.getLoadedBlockEntities()
			.filter(be -> be instanceof TrialSpawnerBlockEntity)
			.map(BlockEntity::getBlockPos).map(BlockPos::immutable)
			.collect(Collectors.toList());
	}
	
	private <T extends Entity> List<Vec3> collectEntityPositions(Class<T> type)
	{
		if(MC.level == null)
			return List.of();
		
		return StreamSupport
			.stream(MC.level.entitiesForRendering().spliterator(), false)
			.filter(e -> !e.isRemoved()).filter(type::isInstance)
			.map(entity -> Vec3.atCenterOf(entity.blockPosition()))
			.collect(Collectors.toList());
	}
	
	private boolean isTrialChamberBed(BlockPos headPos)
	{
		int y = headPos.getY();
		if(y < -38 || y > 10)
			return false;
		
		if(!isNearWaxedCopper(headPos, 5))
			return false;
		
		return isNearTrialSpawner(headPos, 100);
	}
	
	private boolean isNearWaxedCopper(BlockPos center, int range)
	{
		if(MC.level == null)
			return false;
		
		return BlockUtils.getAllInBoxStream(center, range)
			.anyMatch(pos -> isWaxedCopper(BlockUtils.getState(pos)));
	}
	
	private boolean isWaxedCopper(BlockState state)
	{
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
	
	private boolean isLikelyVillageBed(BlockPos headPos)
	{
		if(!hasDoorNearby(headPos, 4))
			return false;
		
		boolean hasVillageEntity =
			isEntityWithinRange(cachedVillagerPositions, headPos, 24)
				|| isEntityWithinRange(cachedGolemPositions, headPos, 24);
		boolean hayCluster = hasHayBaleCluster(headPos, 6);
		
		if(hasVillageEntity || hayCluster)
			return true;
		
		return hasGlassPaneCluster(headPos, 4, 1);
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
