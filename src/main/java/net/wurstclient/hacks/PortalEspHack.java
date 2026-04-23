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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.portalesp.PortalEspBlockGroup;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.EspLimitUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredPoint;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.chunk.ChunkSearcher.Result;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

public final class PortalEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	private final net.wurstclient.settings.CheckboxSetting stickyArea =
		new net.wurstclient.settings.CheckboxSetting("Sticky area",
			"Off: Re-centers every chunk to match ESP drop-off.\n"
				+ "On: Keeps results anchored so you can path back to them.",
			false);
	
	private final PortalEspBlockGroup netherPortal =
		new PortalEspBlockGroup(Blocks.NETHER_PORTAL,
			new ColorSetting("Nether portal color",
				"Nether portals will be highlighted in this color.", Color.RED),
			new CheckboxSetting("Include nether portals", true));
	
	private final PortalEspBlockGroup endPortal =
		new PortalEspBlockGroup(Blocks.END_PORTAL,
			new ColorSetting("End portal color",
				"End portals will be highlighted in this color.", Color.GREEN),
			new CheckboxSetting("Include end portals", true));
	
	private final PortalEspBlockGroup endPortalFrame = new PortalEspBlockGroup(
		Blocks.END_PORTAL_FRAME,
		new ColorSetting("End portal frame color",
			"End portal frames will be highlighted in this color.", Color.BLUE),
		new CheckboxSetting("Include end portal frames", true));
	
	private final PortalEspBlockGroup endGateway = new PortalEspBlockGroup(
		Blocks.END_GATEWAY,
		new ColorSetting("End gateway color",
			"End gateways will be highlighted in this color.", Color.YELLOW),
		new CheckboxSetting("Include end gateways", true));
	
	private final PortalEspBlockGroup brokenNetherPortal =
		new PortalEspBlockGroup(Blocks.OBSIDIAN, new ColorSetting(
			"Broken portal ESP color",
			"Broken nether portal frames will be highlighted in this ESP color.",
			new Color(0xFF8A00)),
			new CheckboxSetting("Find broken nether portals",
				"Find obsidian structures that strongly resemble broken nether portal frames.",
				false));
	
	private final CheckboxSetting brokenNetherPortalTracer =
		new CheckboxSetting("Broken portal tracer",
			"Draw tracers to detected broken nether portal frames.", true);
	private final CheckboxSetting brokenPortalsNetherOnly =
		new CheckboxSetting("Broken portals in Nether only",
			"Only detect broken nether portals while in the Nether dimension.",
			false);
	private final SliderSetting brokenPortalMinConfidence =
		new SliderSetting("Broken portal min confidence",
			"Only render candidates with score at or above this value.", 35, 0,
			100, 1, SliderSetting.ValueDisplay.INTEGER);
	private final SliderSetting brokenPortalMaxInteriorSize = new SliderSetting(
		"Broken portal max interior size",
		"Maximum interior width/height considered for broken portal matching.",
		21, 3, 30, 1, SliderSetting.ValueDisplay.INTEGER);
	private final CheckboxSetting allowCompleteUnlitPortals =
		new CheckboxSetting("Allow complete unlit portals",
			"Allow complete but unlit obsidian portal frames in broken portal detections.",
			false);
	private final CheckboxSetting excludeCryingObsidian =
		new CheckboxSetting("Exclude crying obsidian",
			"Reject candidates with crying obsidian inside or near the frame.",
			true);
	private final CheckboxSetting excludeChests = new CheckboxSetting(
		"Exclude chests",
		"Reject candidates with nearby chests (typical ruined portal loot).",
		true);
	private final CheckboxSetting excludeMagma = new CheckboxSetting(
		"Exclude magma", "Reject candidates with nearby magma blocks.", true);
	private final CheckboxSetting excludeLava = new CheckboxSetting(
		"Exclude lava", "Reject candidates with nearby lava.", true);
	private final CheckboxSetting excludeNetherrack = new CheckboxSetting(
		"Exclude netherrack",
		"Penalize/reject candidates with netherrack around or under the frame.",
		true);
	private final CheckboxSetting excludeGoldBlocks = new CheckboxSetting(
		"Exclude gold blocks",
		"Penalize candidates with nearby gold blocks (ruined portal signal).",
		true);
	
	private final List<PortalEspBlockGroup> groups = Arrays.asList(netherPortal,
		endPortal, endPortalFrame, endGateway, brokenNetherPortal);
	
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.");
	private final SliderSetting lineThickness =
		new SliderSetting("Line thickness", 2.0, 1.0, 10.0, 1.0,
			SliderSetting.ValueDisplay.INTEGER);
	private final CheckboxSetting discoverySound = new CheckboxSetting(
		"Sound on discovery",
		"Plays a sound when PortalESP discovers a new portal block.", false);
	private final EnumSetting<DetectionSound> discoverySoundType =
		new EnumSetting<>("Discovery sound type", DetectionSound.values(),
			DetectionSound.NOTE_BLOCK_CHIME);
	private final SliderSetting discoverySoundVolume = new SliderSetting(
		"Discovery sound volume", "Controls how loud the discovery sound is.",
		100, 0, 200, 1, SliderSetting.ValueDisplay.INTEGER.withSuffix("%"));
	private final TextFieldSetting customDiscoverySoundId =
		new TextFieldSetting("Custom discovery sound ID",
			"Enter a namespaced sound ID like 'minecraft:block.note_block.bell'.",
			"");
	private final CheckboxSetting discoveryChat =
		new CheckboxSetting("Chat message on discovery",
			"Sends a chat message when PortalESP discovers a new portal block.",
			false);
	private final CheckboxSetting ignoreUndersizedPortals = new CheckboxSetting(
		"Ignore undersized portals",
		"Ignores portal detections that are smaller than valid structures.\n"
			+ "Nether portals must be at least 2x3 interior, end portals 3x3, and end portal frames must form a full ring.",
		true);
	
	// Above-ground filter
	private final CheckboxSetting onlyAboveGround =
		new CheckboxSetting("Above ground only",
			"Only show portals at or above the configured Y level.", false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	
	private final BiPredicate<BlockPos, BlockState> query =
		(pos, state) -> state.getBlock() == Blocks.NETHER_PORTAL
			|| state.getBlock() == Blocks.END_PORTAL
			|| state.getBlock() == Blocks.END_PORTAL_FRAME
			|| state.getBlock() == Blocks.END_GATEWAY
			|| state.getBlock() == Blocks.OBSIDIAN;
	
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(query, area);
	
	private boolean groupsUpToDate;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	private ChunkPos lastPlayerChunk;
	private int lastMatchesVersion;
	private long lastBrokenPortalSettingsFingerprint = Long.MIN_VALUE;
	private final HashSet<BlockPos> discoveredPositions = new HashSet<>();
	private final HashSet<BlockPos> brokenPortalAcceptedCache = new HashSet<>();
	private long lastBrokenPortalRecomputeMs;
	private static final long BROKEN_PORTAL_RECOMPUTE_COOLDOWN_MS = 500L;
	private static final int BROKEN_PORTAL_SCAN_CANDIDATE_CAP = 500;
	
	public PortalEspHack()
	{
		super("PortalESP");
		setCategory(Category.RENDER);
		addSetting(style);
		groups.stream().flatMap(PortalEspBlockGroup::getSettings)
			.forEach(this::addSetting);
		addSetting(area);
		addSetting(lineThickness);
		addSetting(discoverySound);
		addSetting(discoverySoundType);
		addSetting(discoverySoundVolume);
		addSetting(customDiscoverySoundId);
		addSetting(discoveryChat);
		addSetting(ignoreUndersizedPortals);
		addSetting(brokenNetherPortalTracer);
		addSetting(brokenPortalsNetherOnly);
		addSetting(brokenPortalMinConfidence);
		addSetting(brokenPortalMaxInteriorSize);
		addSetting(allowCompleteUnlitPortals);
		addSetting(excludeCryingObsidian);
		addSetting(excludeChests);
		addSetting(excludeMagma);
		addSetting(excludeLava);
		addSetting(excludeNetherrack);
		addSetting(excludeGoldBlocks);
		addSetting(stickyArea);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
	}
	
	public List<PortalEspBlockGroup> getMapaGroups()
	{
		return groups;
	}
	
	@Override
	protected void onEnable()
	{
		groupsUpToDate = false;
		discoveredPositions.clear();
		resetBrokenPortalCache();
		lastAreaSelection = area.getSelected();
		lastPlayerChunk = new ChunkPos(MC.player.blockPosition());
		lastMatchesVersion = coordinator.getMatchesVersion();
		lastBrokenPortalSettingsFingerprint =
			computeBrokenPortalSettingsFingerprint();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(net.wurstclient.events.PacketInputListener.class,
			coordinator);
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
		groups.forEach(PortalEspBlockGroup::clear);
		discoveredPositions.clear();
		resetBrokenPortalCache();
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.getSelected().hasLines())
			event.cancel();
	}
	
	@Override
	public void onUpdate()
	{
		long brokenSettingsFingerprint =
			computeBrokenPortalSettingsFingerprint();
		if(brokenSettingsFingerprint != lastBrokenPortalSettingsFingerprint)
		{
			lastBrokenPortalSettingsFingerprint = brokenSettingsFingerprint;
			resetBrokenPortalCache();
			groupsUpToDate = false;
		}
		
		ChunkAreaSetting.ChunkArea currentArea = area.getSelected();
		if(currentArea != lastAreaSelection)
		{
			lastAreaSelection = currentArea;
			coordinator.reset();
			resetBrokenPortalCache();
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
		boolean searchersChanged = coordinator.update();
		if(searchersChanged)
			groupsUpToDate = false;
		int matchesVersion = coordinator.getMatchesVersion();
		if(matchesVersion != lastMatchesVersion)
		{
			lastMatchesVersion = matchesVersion;
			groupsUpToDate = false;
		}
		boolean partialScan =
			WURST.getHax().globalToggleHack.usePartialChunkScan();
		if(!groupsUpToDate && (partialScan ? coordinator.hasReadyMatches()
			: coordinator.isDone()))
			updateGroupBoxes();
		
		if(!isBrokenPortalSearchEnabled()
			&& !brokenPortalAcceptedCache.isEmpty())
			resetBrokenPortalCache();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(style.getSelected().hasBoxes())
			renderBoxes(matrixStack);
		
		if(style.getSelected().hasLines())
			renderTracers(matrixStack, partialTicks);
	}
	
	private void renderBoxes(PoseStack matrixStack)
	{
		for(PortalEspBlockGroup group : groups)
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
		for(PortalEspBlockGroup group : groups)
		{
			if(!group.isEnabled())
				continue;
			if(group == brokenNetherPortal
				&& !brokenNetherPortalTracer.isChecked())
				continue;
			
			List<Vec3> ends = getTracerTargets(group);
			if(ends.isEmpty())
				continue;
			
			int color = group.getColorI(0x80);
			double width = lineThickness.getValue();
			List<ColoredPoint> points =
				ends.stream().map(v -> new ColoredPoint(v, color)).toList();
			
			RenderUtils.drawTracers("portalesp", matrixStack, partialTicks,
				points, false, width);
		}
	}
	
	private void updateGroupBoxes()
	{
		groups.forEach(PortalEspBlockGroup::clear);
		HashMap<PortalEspBlockGroup, ArrayList<BlockPos>> candidatesByGroup =
			new HashMap<>();
		int globalLimit = getEffectiveGlobalEspLimit();
		if(globalLimit > 0)
		{
			for(Result result : getNearestReadyMatches(globalLimit))
				addCandidate(result, candidatesByGroup);
		}else
			coordinator.getReadyMatches()
				.forEach(result -> addCandidate(result, candidatesByGroup));
		
		// End-related structures are always strict to avoid false positives.
		filterUndersizedPortals(candidatesByGroup, endPortal);
		filterUndersizedPortals(candidatesByGroup, endPortalFrame);
		if(ignoreUndersizedPortals.isChecked())
		{
			filterUndersizedPortals(candidatesByGroup, netherPortal);
		}
		if(isBrokenPortalSearchEnabled())
			filterUndersizedPortals(candidatesByGroup, brokenNetherPortal);
		else
			candidatesByGroup.remove(brokenNetherPortal);
		
		HashMap<PortalEspBlockGroup, ArrayList<BlockPos>> newBlocksByGroup =
			commitCandidates(candidatesByGroup);
		
		ArrayList<DiscoveryHit> discoveries =
			buildDiscoveries(newBlocksByGroup);
		
		if(!discoveries.isEmpty())
		{
			if(discoverySound.isChecked())
				playDiscoverySound();
			if(discoveryChat.isChecked())
				sendDiscoveryMessage(discoveries);
		}
		
		groupsUpToDate = true;
	}
	
	private List<Result> getNearestReadyMatches(int limit)
	{
		var eyesPos = RotationUtils.getEyesPos();
		return EspLimitUtils.collectNearest(coordinator.getReadyMatches(),
			limit, r -> r.pos().distToCenterSqr(eyesPos),
			this::isRenderableResult);
	}
	
	private boolean isRenderableResult(Result result)
	{
		if(result == null)
			return false;
		
		BlockPos pos = result.pos();
		if(onlyAboveGround.isChecked() && pos.getY() < aboveGroundY.getValue())
			return false;
		if(result.state().getBlock() == Blocks.OBSIDIAN)
			return isBrokenPortalSearchEnabled();
		
		for(PortalEspBlockGroup group : groups)
		{
			if(result.state().getBlock() != group.getBlock())
				continue;
			if(!group.isEnabled())
				return false;
			if(group == endGateway && !isInEndDimension())
				return false;
			return true;
		}
		
		return false;
	}
	
	private int getEffectiveGlobalEspLimit()
	{
		return WURST.getHax().globalToggleHack
			.getEffectiveGlobalEspRenderLimit();
	}
	
	private void addCandidate(Result result,
		Map<PortalEspBlockGroup, ArrayList<BlockPos>> candidatesByGroup)
	{
		if(result == null)
			return;
		if(onlyAboveGround.isChecked()
			&& result.pos().getY() < aboveGroundY.getValue())
			return;
		
		for(PortalEspBlockGroup group : groups)
			if(result.state().getBlock() == group.getBlock())
			{
				if(!group.isEnabled())
					return;
				
				if(group == brokenNetherPortal)
				{
					if(!isBrokenPortalSearchEnabled())
						return;
				}
				
				if(group == endGateway && !isInEndDimension())
					return;
				
				candidatesByGroup.computeIfAbsent(group, g -> new ArrayList<>())
					.add(result.pos().immutable());
				break;
			}
	}
	
	private HashMap<PortalEspBlockGroup, ArrayList<BlockPos>> commitCandidates(
		Map<PortalEspBlockGroup, ArrayList<BlockPos>> candidatesByGroup)
	{
		HashMap<PortalEspBlockGroup, ArrayList<BlockPos>> newBlocksByGroup =
			new HashMap<>();
		
		for(PortalEspBlockGroup group : groups)
		{
			ArrayList<BlockPos> candidates = candidatesByGroup.get(group);
			if(candidates == null || candidates.isEmpty())
				continue;
			
			for(BlockPos pos : candidates)
			{
				group.add(pos);
				if(discoveredPositions.add(pos))
					newBlocksByGroup
						.computeIfAbsent(group, g -> new ArrayList<>())
						.add(pos);
			}
		}
		
		return newBlocksByGroup;
	}
	
	private void filterUndersizedPortals(
		Map<PortalEspBlockGroup, ArrayList<BlockPos>> candidatesByGroup,
		PortalEspBlockGroup group)
	{
		ArrayList<BlockPos> candidates = candidatesByGroup.get(group);
		if(candidates == null || candidates.isEmpty())
			return;
		
		ArrayList<BlockPos> filtered;
		if(group == netherPortal)
			filtered = filterValidNetherPortalBlocks(candidates);
		else if(group == endPortal)
			filtered = filterValidEndPortalBlocks(candidates);
		else if(group == endPortalFrame)
			filtered = filterValidEndPortalFrameBlocks(candidates);
		else if(group == brokenNetherPortal)
			filtered = filterBrokenNetherPortalFrames(candidates);
		else
			filtered = candidates;
		
		candidatesByGroup.put(group, filtered);
	}
	
	private boolean isInEndDimension()
	{
		return MC.level != null && MC.level.dimension() == Level.END;
	}
	
	private boolean isInNetherDimension()
	{
		return MC.level != null && MC.level.dimension() == Level.NETHER;
	}
	
	private ArrayList<BlockPos> filterValidNetherPortalBlocks(
		List<BlockPos> candidates)
	{
		HashSet<BlockPos> remaining = new HashSet<>(candidates);
		HashSet<BlockPos> valid = new HashSet<>();
		
		while(!remaining.isEmpty())
		{
			BlockPos start = remaining.iterator().next();
			remaining.remove(start);
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(start);
			ArrayList<BlockPos> component = new ArrayList<>();
			
			int minX = start.getX();
			int maxX = start.getX();
			int minY = start.getY();
			int maxY = start.getY();
			int minZ = start.getZ();
			int maxZ = start.getZ();
			
			while(!queue.isEmpty())
			{
				BlockPos current = queue.removeFirst();
				component.add(current);
				minX = Math.min(minX, current.getX());
				maxX = Math.max(maxX, current.getX());
				minY = Math.min(minY, current.getY());
				maxY = Math.max(maxY, current.getY());
				minZ = Math.min(minZ, current.getZ());
				maxZ = Math.max(maxZ, current.getZ());
				
				for(Direction dir : Direction.values())
				{
					BlockPos neighbor = current.relative(dir);
					if(remaining.remove(neighbor))
						queue.addLast(neighbor);
				}
			}
			
			int xSpan = maxX - minX + 1;
			int ySpan = maxY - minY + 1;
			int zSpan = maxZ - minZ + 1;
			boolean validPortal = (xSpan == 1 && zSpan >= 2 && ySpan >= 3)
				|| (zSpan == 1 && xSpan >= 2 && ySpan >= 3);
			if(validPortal)
				valid.addAll(component);
		}
		
		return candidates.stream().filter(valid::contains)
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
	}
	
	private ArrayList<BlockPos> filterValidEndPortalBlocks(
		List<BlockPos> candidates)
	{
		HashSet<BlockPos> remaining = new HashSet<>(candidates);
		HashSet<BlockPos> valid = new HashSet<>();
		
		while(!remaining.isEmpty())
		{
			BlockPos start = remaining.iterator().next();
			remaining.remove(start);
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(start);
			ArrayList<BlockPos> component = new ArrayList<>();
			
			int minX = start.getX();
			int maxX = start.getX();
			int minY = start.getY();
			int maxY = start.getY();
			int minZ = start.getZ();
			int maxZ = start.getZ();
			
			while(!queue.isEmpty())
			{
				BlockPos current = queue.removeFirst();
				component.add(current);
				minX = Math.min(minX, current.getX());
				maxX = Math.max(maxX, current.getX());
				minY = Math.min(minY, current.getY());
				maxY = Math.max(maxY, current.getY());
				minZ = Math.min(minZ, current.getZ());
				maxZ = Math.max(maxZ, current.getZ());
				
				for(Direction dir : Direction.values())
				{
					BlockPos neighbor = current.relative(dir);
					if(remaining.remove(neighbor))
						queue.addLast(neighbor);
				}
			}
			
			int xSpan = maxX - minX + 1;
			int ySpan = maxY - minY + 1;
			int zSpan = maxZ - minZ + 1;
			boolean strict3x3 =
				component.size() == 9 && xSpan == 3 && ySpan == 1 && zSpan == 3;
			int centerX = minX + 1;
			int centerZ = minZ + 1;
			if(strict3x3
				&& isOrientedEndPortalFrameRing(centerX, minY, centerZ))
				valid.addAll(component);
		}
		
		return candidates.stream().filter(valid::contains)
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
	}
	
	private ArrayList<BlockPos> filterValidEndPortalFrameBlocks(
		List<BlockPos> candidates)
	{
		HashSet<BlockPos> frameSet = new HashSet<>(candidates);
		HashSet<BlockPos> valid = new HashSet<>();
		for(BlockPos pos : candidates)
		{
			int y = pos.getY();
			for(int cx = pos.getX() - 2; cx <= pos.getX() + 2; cx++)
				for(int cz = pos.getZ() - 2; cz <= pos.getZ() + 2; cz++)
				{
					if(!isCompleteEndPortalFrameRing(frameSet, cx, y, cz))
						continue;
					addEndPortalFrameRing(valid, cx, y, cz);
				}
		}
		
		return candidates.stream().filter(valid::contains)
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
	}
	
	private boolean isCompleteEndPortalFrameRing(HashSet<BlockPos> frameSet,
		int centerX, int y, int centerZ)
	{
		return hasOrientedFrameAt(frameSet, centerX - 1, y, centerZ - 2,
			centerX, centerZ)
			&& hasOrientedFrameAt(frameSet, centerX, y, centerZ - 2, centerX,
				centerZ)
			&& hasOrientedFrameAt(frameSet, centerX + 1, y, centerZ - 2,
				centerX, centerZ)
			&& hasOrientedFrameAt(frameSet, centerX - 1, y, centerZ + 2,
				centerX, centerZ)
			&& hasOrientedFrameAt(frameSet, centerX, y, centerZ + 2, centerX,
				centerZ)
			&& hasOrientedFrameAt(frameSet, centerX + 1, y, centerZ + 2,
				centerX, centerZ)
			&& hasOrientedFrameAt(frameSet, centerX - 2, y, centerZ - 1,
				centerX, centerZ)
			&& hasOrientedFrameAt(frameSet, centerX - 2, y, centerZ, centerX,
				centerZ)
			&& hasOrientedFrameAt(frameSet, centerX - 2, y, centerZ + 1,
				centerX, centerZ)
			&& hasOrientedFrameAt(frameSet, centerX + 2, y, centerZ - 1,
				centerX, centerZ)
			&& hasOrientedFrameAt(frameSet, centerX + 2, y, centerZ, centerX,
				centerZ)
			&& hasOrientedFrameAt(frameSet, centerX + 2, y, centerZ + 1,
				centerX, centerZ);
	}
	
	private boolean isOrientedEndPortalFrameRing(int centerX, int y,
		int centerZ)
	{
		HashSet<BlockPos> frameSet = new HashSet<>();
		for(int dx = -1; dx <= 1; dx++)
		{
			frameSet.add(new BlockPos(centerX + dx, y, centerZ - 2));
			frameSet.add(new BlockPos(centerX + dx, y, centerZ + 2));
		}
		
		for(int dz = -1; dz <= 1; dz++)
		{
			frameSet.add(new BlockPos(centerX - 2, y, centerZ + dz));
			frameSet.add(new BlockPos(centerX + 2, y, centerZ + dz));
		}
		
		return isCompleteEndPortalFrameRing(frameSet, centerX, y, centerZ);
	}
	
	private boolean hasOrientedFrameAt(HashSet<BlockPos> frameSet, int x, int y,
		int z, int centerX, int centerZ)
	{
		BlockPos pos = new BlockPos(x, y, z);
		if(!frameSet.contains(pos) || MC.level == null)
			return false;
		
		BlockState state = MC.level.getBlockState(pos);
		if(state.getBlock() != Blocks.END_PORTAL_FRAME)
			return false;
		
		Direction facing = state.getValue(EndPortalFrameBlock.FACING);
		Direction expected = expectedFrameFacing(x, z, centerX, centerZ);
		return facing == expected;
	}
	
	private Direction expectedFrameFacing(int x, int z, int centerX,
		int centerZ)
	{
		if(z == centerZ - 2)
			return Direction.SOUTH;
		if(z == centerZ + 2)
			return Direction.NORTH;
		if(x == centerX - 2)
			return Direction.EAST;
		return Direction.WEST;
	}
	
	private void addEndPortalFrameRing(HashSet<BlockPos> valid, int centerX,
		int y, int centerZ)
	{
		for(int dx = -1; dx <= 1; dx++)
		{
			valid.add(new BlockPos(centerX + dx, y, centerZ - 2));
			valid.add(new BlockPos(centerX + dx, y, centerZ + 2));
		}
		
		for(int dz = -1; dz <= 1; dz++)
		{
			valid.add(new BlockPos(centerX - 2, y, centerZ + dz));
			valid.add(new BlockPos(centerX + 2, y, centerZ + dz));
		}
	}
	
	private ArrayList<BlockPos> filterBrokenNetherPortalFrames(
		List<BlockPos> candidates)
	{
		if(candidates.isEmpty())
			return new ArrayList<>();
		
		long now = System.currentTimeMillis();
		boolean shouldRecompute = brokenPortalAcceptedCache.isEmpty() || now
			- lastBrokenPortalRecomputeMs >= BROKEN_PORTAL_RECOMPUTE_COOLDOWN_MS;
		
		if(shouldRecompute)
		{
			List<BlockPos> sampled = candidates;
			if(candidates.size() > BROKEN_PORTAL_SCAN_CANDIDATE_CAP)
			{
				Vec3 eyesPos = RotationUtils.getEyesPos();
				sampled = EspLimitUtils.collectNearest(candidates,
					BROKEN_PORTAL_SCAN_CANDIDATE_CAP,
					p -> p.distToCenterSqr(eyesPos), p -> true);
			}
			
			ArrayList<BlockPos> fresh =
				filterBrokenNetherPortalFramesRaw(sampled);
			brokenPortalAcceptedCache.clear();
			brokenPortalAcceptedCache.addAll(fresh);
			lastBrokenPortalRecomputeMs = now;
		}
		
		return candidates.stream().filter(brokenPortalAcceptedCache::contains)
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
	}
	
	private ArrayList<BlockPos> filterBrokenNetherPortalFramesRaw(
		List<BlockPos> candidates)
	{
		if(candidates.isEmpty() || MC.level == null)
			return new ArrayList<>();
		HashSet<BlockPos> nearbyObsidian = new HashSet<>(candidates);
		if(nearbyObsidian.isEmpty())
			return new ArrayList<>();
		
		int minScore = brokenPortalMinConfidence.getValueI();
		int maxInterior = brokenPortalMaxInteriorSize.getValueI();
		int maxOuter = maxInterior + 2;
		
		ArrayList<BrokenPortalCandidate> scored = new ArrayList<>();
		HashSet<BlockPos> remaining = new HashSet<>(nearbyObsidian);
		while(!remaining.isEmpty())
		{
			BlockPos start = remaining.iterator().next();
			remaining.remove(start);
			
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(start);
			ArrayList<BlockPos> component = new ArrayList<>();
			
			int minX = start.getX();
			int maxX = start.getX();
			int minY = start.getY();
			int maxY = start.getY();
			int minZ = start.getZ();
			int maxZ = start.getZ();
			
			while(!queue.isEmpty())
			{
				BlockPos current = queue.removeFirst();
				component.add(current);
				minX = Math.min(minX, current.getX());
				maxX = Math.max(maxX, current.getX());
				minY = Math.min(minY, current.getY());
				maxY = Math.max(maxY, current.getY());
				minZ = Math.min(minZ, current.getZ());
				maxZ = Math.max(maxZ, current.getZ());
				
				for(int dx = -1; dx <= 1; dx++)
					for(int dy = -1; dy <= 1; dy++)
						for(int dz = -1; dz <= 1; dz++)
						{
							if(dx == 0 && dy == 0 && dz == 0)
								continue;
							BlockPos neighbor = current.offset(dx, dy, dz);
							if(remaining.remove(neighbor))
								queue.addLast(neighbor);
						}
			}
			
			// Big blobs are usually lava noise and expensive to fit.
			if(component.size() > 400)
				continue;
			
			HashSet<Integer> xPlanes = new HashSet<>();
			HashSet<Integer> zPlanes = new HashSet<>();
			HashSet<BlockPos> componentSet = new HashSet<>(component);
			if(componentTouchesActivePortal(componentSet))
				continue;
			for(BlockPos pos : component)
			{
				xPlanes.add(pos.getX());
				zPlanes.add(pos.getZ());
			}
			
			for(int z : zPlanes)
			{
				scoreRectanglesInPlane(scored, nearbyObsidian, minScore,
					maxOuter, RectangleAxis.X, z, minX, maxX, minY, maxY);
				scoreRemnantsInPlane(scored, nearbyObsidian, componentSet,
					minScore, RectangleAxis.X, z);
			}
			for(int x : xPlanes)
			{
				scoreRectanglesInPlane(scored, nearbyObsidian, minScore,
					maxOuter, RectangleAxis.Z, x, minZ, maxZ, minY, maxY);
				scoreRemnantsInPlane(scored, nearbyObsidian, componentSet,
					minScore, RectangleAxis.Z, x);
			}
		}
		
		if(scored.isEmpty())
			return new ArrayList<>();
		
		scored.sort(
			Comparator.comparingInt(BrokenPortalCandidate::score).reversed());
		ArrayList<BrokenPortalCandidate> selected = new ArrayList<>();
		for(BrokenPortalCandidate candidate : scored)
		{
			boolean overlaps = false;
			for(BrokenPortalCandidate existing : selected)
			{
				int overlap = 0;
				for(BlockPos pos : candidate.detectedFrame())
					if(existing.detectedFrame().contains(pos))
						overlap++;
					
				int minSize = Math.min(candidate.detectedFrame().size(),
					existing.detectedFrame().size());
				if(minSize > 0 && overlap >= minSize / 2)
				{
					overlaps = true;
					break;
				}
			}
			if(!overlaps)
				selected.add(candidate);
		}
		
		HashSet<BlockPos> accepted = new HashSet<>();
		for(BrokenPortalCandidate candidate : selected)
		{
			accepted.addAll(candidate.detectedFrame());
			accepted.addAll(getBaseRowExtensions(candidate, nearbyObsidian));
		}
		rejectActivePortalComponents(accepted);
		
		return candidates.stream().filter(accepted::contains)
			.collect(java.util.stream.Collectors.toCollection(ArrayList::new));
	}
	
	private void resetBrokenPortalCache()
	{
		brokenPortalAcceptedCache.clear();
		lastBrokenPortalRecomputeMs = 0L;
	}
	
	private void scoreRectanglesInPlane(ArrayList<BrokenPortalCandidate> scored,
		Set<BlockPos> obsidian, int minScore, int maxOuter, RectangleAxis axis,
		int planeCoord, int minPrimary, int maxPrimary, int minY, int maxY)
	{
		int startPrimary = minPrimary - 1;
		int endPrimary = maxPrimary + 1;
		int startY = minY - 1;
		int endY = maxY + 1;
		for(int primary0 = startPrimary; primary0 <= endPrimary; primary0++)
			for(int outerWidth = 4; outerWidth <= maxOuter; outerWidth++)
			{
				int primary1 = primary0 + outerWidth - 1;
				if(primary1 > endPrimary)
					break;
				
				for(int y0 = startY; y0 <= endY; y0++)
					for(int outerHeight =
						5; outerHeight <= maxOuter; outerHeight++)
					{
						int y1 = y0 + outerHeight - 1;
						if(y1 > endY)
							break;
						
						BrokenPortalCandidate candidate =
							scoreBrokenPortalRectangle(obsidian, axis,
								planeCoord, primary0, primary1, y0, y1,
								minScore);
						if(candidate != null)
							scored.add(candidate);
					}
			}
	}
	
	private void scoreRemnantsInPlane(ArrayList<BrokenPortalCandidate> scored,
		Set<BlockPos> obsidian, Set<BlockPos> component, int minScore,
		RectangleAxis axis, int planeCoord)
	{
		if(MC.level == null)
			return;
		
		HashSet<BlockPos> plane = new HashSet<>();
		for(BlockPos pos : component)
		{
			int planeValue = axis == RectangleAxis.X ? pos.getZ() : pos.getX();
			if(planeValue == planeCoord)
				plane.add(pos);
		}
		
		if(plane.size() < 3)
			return;
		
		for(BlockPos corner : plane)
		{
			int cornerPrimary =
				axis == RectangleAxis.X ? corner.getX() : corner.getZ();
			int cornerY = corner.getY();
			
			for(int horizontalDir : new int[]{-1, 1})
			{
				int horizontalLen = contiguousLength(plane, axis, planeCoord,
					cornerPrimary, cornerY, horizontalDir);
				if(horizontalLen < 3)
					continue;
				
				for(int verticalDir : new int[]{-1, 1})
				{
					int verticalLen = contiguousVerticalLength(plane, axis,
						planeCoord, cornerPrimary, cornerY, verticalDir);
					if(verticalLen < 3)
						continue;
					
					HashSet<BlockPos> detected = new HashSet<>();
					for(int i = 0; i < horizontalLen; i++)
					{
						int primary = cornerPrimary + horizontalDir * i;
						detected.add(axis.toPos(primary, cornerY, planeCoord));
					}
					for(int i = 0; i < verticalLen; i++)
					{
						int y = cornerY + verticalDir * i;
						detected.add(axis.toPos(cornerPrimary, y, planeCoord));
					}
					
					if(detected.size() < 4)
						continue;
					
					int minPrimary = Integer.MAX_VALUE;
					int maxPrimary = Integer.MIN_VALUE;
					int minY = Integer.MAX_VALUE;
					int maxY = Integer.MIN_VALUE;
					for(BlockPos pos : detected)
					{
						int primary =
							axis == RectangleAxis.X ? pos.getX() : pos.getZ();
						minPrimary = Math.min(minPrimary, primary);
						maxPrimary = Math.max(maxPrimary, primary);
						minY = Math.min(minY, pos.getY());
						maxY = Math.max(maxY, pos.getY());
					}
					int spanWidth = maxPrimary - minPrimary + 1;
					int spanHeight = maxY - minY + 1;
					if(spanWidth < 3 || spanHeight < 3)
						continue;
					
					// Very small L-shapes are usually random obsidian noise.
					if(horizontalLen < 4 && verticalLen < 4)
						continue;
					
					int support =
						countSupportEvidence(obsidian, axis, planeCoord,
							minPrimary, maxPrimary, minY, maxY, detected);
					if(support < 2)
						continue;
					if(hasActiveNetherPortal(axis, planeCoord, minPrimary,
						maxPrimary, minY, maxY))
						continue;
					
					int score = 4 + horizontalLen * 5 + verticalLen * 6
						+ Math.min(support * 3, 24);
					if(horizontalLen >= 3 && verticalLen >= 4)
						score += 8;
					RuinedPortalSignals ruinedSignals =
						scanRuinedPortalSignals(obsidian, axis, planeCoord,
							minPrimary, maxPrimary, minY, maxY);
					if(isHardRuinedPortalReject(ruinedSignals))
						continue;
					
					score += scoreRuinedPortalExclusion(ruinedSignals);
					
					if(score < minScore)
						continue;
					
					scored.add(new BrokenPortalCandidate(score, detected, axis,
						planeCoord, minPrimary, maxPrimary, minY, maxY));
				}
			}
		}
	}
	
	private int contiguousLength(Set<BlockPos> plane, RectangleAxis axis,
		int planeCoord, int startPrimary, int y, int dir)
	{
		int len = 0;
		for(int step = 0; step < 30; step++)
		{
			int primary = startPrimary + dir * step;
			BlockPos pos = axis.toPos(primary, y, planeCoord);
			if(!plane.contains(pos))
				break;
			len++;
		}
		return len;
	}
	
	private int contiguousVerticalLength(Set<BlockPos> plane,
		RectangleAxis axis, int planeCoord, int primary, int startY, int dir)
	{
		int len = 0;
		for(int step = 0; step < 40; step++)
		{
			int y = startY + dir * step;
			BlockPos pos = axis.toPos(primary, y, planeCoord);
			if(!plane.contains(pos))
				break;
			len++;
		}
		return len;
	}
	
	private int countSupportEvidence(Set<BlockPos> obsidian, RectangleAxis axis,
		int planeCoord, int minPrimary, int maxPrimary, int minY, int maxY,
		Set<BlockPos> detected)
	{
		int support = 0;
		for(int primary = minPrimary - 1; primary <= maxPrimary + 1; primary++)
			for(int y = minY - 2; y <= maxY + 1; y++)
				for(int planeOffset = -1; planeOffset <= 1; planeOffset++)
				{
					BlockPos pos =
						axis.toPos(primary, y, planeCoord + planeOffset);
					if(!obsidian.contains(pos) || detected.contains(pos))
						continue;
					
					for(Direction dir : Direction.values())
					{
						if(detected.contains(pos.relative(dir)))
						{
							support++;
							break;
						}
					}
				}
		return support;
	}
	
	private boolean componentTouchesActivePortal(Set<BlockPos> component)
	{
		if(MC.level == null || component.isEmpty())
			return false;
		
		for(BlockPos pos : component)
			for(Direction dir : Direction.values())
				if(MC.level.getBlockState(pos.relative(dir))
					.getBlock() == Blocks.NETHER_PORTAL)
					return true;
				
		return false;
	}
	
	private void rejectActivePortalComponents(Set<BlockPos> accepted)
	{
		if(accepted.isEmpty())
			return;
		
		HashSet<BlockPos> remaining = new HashSet<>(accepted);
		HashSet<BlockPos> toRemove = new HashSet<>();
		
		while(!remaining.isEmpty())
		{
			BlockPos start = remaining.iterator().next();
			remaining.remove(start);
			
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(start);
			HashSet<BlockPos> component = new HashSet<>();
			component.add(start);
			
			while(!queue.isEmpty())
			{
				BlockPos current = queue.removeFirst();
				for(Direction dir : Direction.values())
				{
					BlockPos neighbor = current.relative(dir);
					if(remaining.remove(neighbor))
					{
						component.add(neighbor);
						queue.addLast(neighbor);
					}
				}
			}
			
			if(componentTouchesActivePortal(component))
				toRemove.addAll(component);
		}
		
		if(!toRemove.isEmpty())
			accepted.removeAll(toRemove);
	}
	
	private boolean hasActiveNetherPortal(RectangleAxis axis, int planeCoord,
		int minPrimary, int maxPrimary, int minY, int maxY)
	{
		if(MC.level == null)
			return false;
		
		for(int primary = minPrimary; primary <= maxPrimary; primary++)
			for(int y = minY; y <= maxY; y++)
			{
				BlockPos pos = axis.toPos(primary, y, planeCoord);
				if(MC.level.getBlockState(pos)
					.getBlock() == Blocks.NETHER_PORTAL)
					return true;
			}
		
		return false;
	}
	
	private BrokenPortalCandidate scoreBrokenPortalRectangle(
		Set<BlockPos> obsidian, RectangleAxis axis, int planeCoord,
		int primary0, int primary1, int y0, int y1, int minScore)
	{
		if(MC.level == null)
			return null;
		
		int outerWidth = primary1 - primary0 + 1;
		int outerHeight = y1 - y0 + 1;
		int interiorWidth = outerWidth - 2;
		int interiorHeight = outerHeight - 2;
		if(interiorWidth < 2 || interiorHeight < 3)
			return null;
		
		HashSet<BlockPos> nonCornerBorder = new HashSet<>();
		HashSet<BlockPos> corners = new HashSet<>();
		HashSet<BlockPos> sideA = new HashSet<>();
		HashSet<BlockPos> sideB = new HashSet<>();
		HashSet<BlockPos> topEdge = new HashSet<>();
		HashSet<BlockPos> bottomEdge = new HashSet<>();
		HashSet<BlockPos> interior = new HashSet<>();
		
		for(int primary = primary0; primary <= primary1; primary++)
			for(int y = y0; y <= y1; y++)
			{
				BlockPos pos = axis.toPos(primary, y, planeCoord);
				boolean border = primary == primary0 || primary == primary1
					|| y == y0 || y == y1;
				if(border)
				{
					boolean corner =
						(primary == primary0 || primary == primary1)
							&& (y == y0 || y == y1);
					if(corner)
						corners.add(pos);
					else
						nonCornerBorder.add(pos);
					
					if(primary == primary0 && y > y0 && y < y1)
						sideA.add(pos);
					if(primary == primary1 && y > y0 && y < y1)
						sideB.add(pos);
					if(y == y0 && primary > primary0 && primary < primary1)
						bottomEdge.add(pos);
					if(y == y1 && primary > primary0 && primary < primary1)
						topEdge.add(pos);
				}else
					interior.add(pos);
			}
		
		if(nonCornerBorder.isEmpty() || sideA.isEmpty() || sideB.isEmpty()
			|| interior.isEmpty())
			return null;
		
		int nonCornerHit = countHits(obsidian, nonCornerBorder);
		int cornerHit = countHits(obsidian, corners);
		int sideAHit = countHits(obsidian, sideA);
		int sideBHit = countHits(obsidian, sideB);
		int topHit = countHits(obsidian, topEdge);
		int bottomHit = countHits(obsidian, bottomEdge);
		
		double borderRatio = nonCornerHit / (double)nonCornerBorder.size();
		double sideARatio = sideAHit / (double)sideA.size();
		double sideBRatio = sideBHit / (double)sideB.size();
		double topRatio =
			topEdge.isEmpty() ? 0 : topHit / (double)topEdge.size();
		double bottomRatio =
			bottomEdge.isEmpty() ? 0 : bottomHit / (double)bottomEdge.size();
		double strongSideRatio = Math.max(sideARatio, sideBRatio);
		double weakSideRatio = Math.min(sideARatio, sideBRatio);
		
		// Allow heavily damaged frames as long as one side is strong and the
		// opposite side still has meaningful evidence.
		if(strongSideRatio < 0.6 || weakSideRatio < 0.2)
			return null;
		if(Math.max(topRatio, bottomRatio) < 0.1)
			return null;
		if(borderRatio < 0.42)
			return null;
		
		int interiorObsidian = 0;
		int interiorEmpty = 0;
		int interiorPortalBlocks = 0;
		for(BlockPos pos : interior)
		{
			BlockState state = MC.level.getBlockState(pos);
			if(state.getBlock() == Blocks.OBSIDIAN)
				interiorObsidian++;
			if(state.getBlock() == Blocks.NETHER_PORTAL)
				interiorPortalBlocks++;
			if(isPortalInteriorReplaceable(state))
				interiorEmpty++;
		}
		
		double interiorEmptyRatio = interiorEmpty / (double)interior.size();
		double interiorObsidianRatio =
			interiorObsidian / (double)interior.size();
		if(interiorEmptyRatio < 0.35 || interiorObsidianRatio > 0.45)
			return null;
		
		boolean completeWithoutCorners = nonCornerHit == nonCornerBorder.size();
		int missingNonCorner = nonCornerBorder.size() - nonCornerHit;
		boolean activePortal = interiorPortalBlocks > 0;
		if(activePortal)
			return null;
		if(!allowCompleteUnlitPortals.isChecked() && completeWithoutCorners)
			return null;
		
		int score = 0;
		score += 20;
		score += (int)Math.round(borderRatio * 35);
		score += (int)Math.round(strongSideRatio * 18);
		score += (int)Math.round(weakSideRatio * 10);
		score += (int)Math.round(Math.max(topRatio, bottomRatio) * 15);
		score += (int)Math.round(interiorEmptyRatio * 22);
		score += Math.min(cornerHit * 2, 8);
		if(missingNonCorner > 0 && missingNonCorner <= 4)
			score += 8;
		
		// Extra obsidian within the frame footprint usually means blobs/walls.
		score -= Math.min(interiorObsidian * 8, 40);
		
		int irregularObs = countIrregularObsidian(obsidian, axis, planeCoord,
			primary0, primary1, y0, y1, nonCornerBorder, corners);
		score -= Math.min(irregularObs * 3, 36);
		
		RuinedPortalSignals ruinedSignals = scanRuinedPortalSignals(obsidian,
			axis, planeCoord, primary0, primary1, y0, y1);
		if(isHardRuinedPortalReject(ruinedSignals))
			return null;
		score += scoreRuinedPortalExclusion(ruinedSignals);
		
		if(score < minScore)
			return null;
		
		HashSet<BlockPos> detectedFrame = new HashSet<>();
		for(BlockPos pos : nonCornerBorder)
			if(obsidian.contains(pos))
				detectedFrame.add(pos);
		for(BlockPos pos : corners)
			if(obsidian.contains(pos))
				detectedFrame.add(pos);
			
		if(detectedFrame.isEmpty())
			return null;
		
		return new BrokenPortalCandidate(score, detectedFrame, axis, planeCoord,
			primary0, primary1, y0, y1);
	}
	
	private int countHits(Set<BlockPos> haystack, Set<BlockPos> needles)
	{
		int hits = 0;
		for(BlockPos pos : needles)
			if(haystack.contains(pos))
				hits++;
		return hits;
	}
	
	private Set<BlockPos> getBaseRowExtensions(BrokenPortalCandidate candidate,
		Set<BlockPos> obsidian)
	{
		HashSet<BlockPos> extensions = new HashSet<>();
		int y = candidate.y0() - 1;
		for(int primary = candidate.primary0(); primary <= candidate
			.primary1(); primary++)
		{
			BlockPos pos =
				candidate.axis().toPos(primary, y, candidate.planeCoord());
			if(!obsidian.contains(pos))
				continue;
			if(hasAdjacentFrameBlock(pos, candidate.detectedFrame()))
				extensions.add(pos);
		}
		return extensions;
	}
	
	private boolean hasAdjacentFrameBlock(BlockPos pos, Set<BlockPos> frame)
	{
		for(Direction dir : Direction.values())
			if(frame.contains(pos.relative(dir)))
				return true;
		return false;
	}
	
	private boolean isPortalInteriorReplaceable(BlockState state)
	{
		Block block = state.getBlock();
		return state.isAir() || block == Blocks.NETHER_PORTAL
			|| block == Blocks.FIRE || block == Blocks.SOUL_FIRE
			|| state.canBeReplaced();
	}
	
	private int countIrregularObsidian(Set<BlockPos> obsidian,
		RectangleAxis axis, int planeCoord, int primary0, int primary1, int y0,
		int y1, Set<BlockPos> nonCornerBorder, Set<BlockPos> corners)
	{
		int irregular = 0;
		for(int primary = primary0 - 1; primary <= primary1 + 1; primary++)
			for(int y = y0 - 1; y <= y1 + 1; y++)
				for(int planeOffset = -1; planeOffset <= 1; planeOffset++)
				{
					int plane = planeCoord + planeOffset;
					BlockPos pos = axis.toPos(primary, y, plane);
					if(!obsidian.contains(pos))
						continue;
					if(planeOffset == 0 && (nonCornerBorder.contains(pos)
						|| corners.contains(pos)))
						continue;
					irregular++;
				}
		return irregular;
	}
	
	private RuinedPortalSignals scanRuinedPortalSignals(Set<BlockPos> obsidian,
		RectangleAxis axis, int planeCoord, int primary0, int primary1, int y0,
		int y1)
	{
		if(MC.level == null || obsidian == null || obsidian.isEmpty())
			return new RuinedPortalSignals(false, false, false, false, 0, 0, 0);
		
		int obsMinPrimary = Integer.MAX_VALUE;
		int obsMaxPrimary = Integer.MIN_VALUE;
		int obsMinY = Integer.MAX_VALUE;
		int obsMaxY = Integer.MIN_VALUE;
		int obsMinPlane = Integer.MAX_VALUE;
		int obsMaxPlane = Integer.MIN_VALUE;
		for(BlockPos framePos : obsidian)
		{
			int primary =
				axis == RectangleAxis.X ? framePos.getX() : framePos.getZ();
			int plane =
				axis == RectangleAxis.X ? framePos.getZ() : framePos.getX();
			obsMinPrimary = Math.min(obsMinPrimary, primary);
			obsMaxPrimary = Math.max(obsMaxPrimary, primary);
			obsMinY = Math.min(obsMinY, framePos.getY());
			obsMaxY = Math.max(obsMaxY, framePos.getY());
			obsMinPlane = Math.min(obsMinPlane, plane);
			obsMaxPlane = Math.max(obsMaxPlane, plane);
		}
		
		int primaryStart = Math.min(primary0 - 2, obsMinPrimary - 3);
		int primaryEnd = Math.max(primary1 + 2, obsMaxPrimary + 3);
		int yStart = Math.min(y0 - 8, obsMinY - 5);
		int yEnd = Math.max(y1 + 3, obsMaxY + 4);
		int planeStart = Math.min(planeCoord - 2, obsMinPlane - 3);
		int planeEnd = Math.max(planeCoord + 2, obsMaxPlane + 3);
		
		int netherrackCount = 0;
		int netherrackUnder = 0;
		int goldCount = 0;
		boolean hasCrying = false;
		boolean hasChest = false;
		boolean hasMagma = false;
		boolean hasLava = false;
		for(int primary = primaryStart; primary <= primaryEnd; primary++)
			for(int y = yStart; y <= yEnd; y++)
				for(int plane = planeStart; plane <= planeEnd; plane++)
				{
					BlockPos pos = axis.toPos(primary, y, plane);
					BlockState state = MC.level.getBlockState(pos);
					Block block = state.getBlock();
					
					if(block == Blocks.CRYING_OBSIDIAN
						&& isNearObsidianEvidence(obsidian, pos, 2, 3))
						hasCrying = true;
					
					if(block instanceof ChestBlock
						&& (isChestRuinedPortalSignal(axis, planeCoord,
							primary0, primary1, y0, y1, pos)
							|| isNearObsidianEvidence(obsidian, pos, 5, 4)))
						hasChest = true;
					
					if(block == Blocks.MAGMA_BLOCK
						&& isNearObsidianEvidence(obsidian, pos, 2, 2))
						hasMagma = true;
					
					if(state.getFluidState().is(FluidTags.LAVA)
						&& isNearObsidianEvidence(obsidian, pos, 2, 2))
						hasLava = true;
					
					if(block == Blocks.GOLD_BLOCK
						|| block == Blocks.GILDED_BLACKSTONE)
					{
						if(isNearObsidianEvidence(obsidian, pos, 3, 4))
							goldCount++;
					}
					
					if(block == Blocks.NETHERRACK)
					{
						netherrackCount++;
						if(y == y0 - 1 && plane == planeCoord
							&& primary >= primary0 && primary <= primary1)
							netherrackUnder++;
					}
				}
			
		return new RuinedPortalSignals(hasCrying, hasChest, hasMagma, hasLava,
			netherrackCount, netherrackUnder, goldCount);
	}
	
	private boolean isNearObsidianEvidence(Set<BlockPos> obsidian, BlockPos pos,
		int horizontalRadius, int verticalRadius)
	{
		for(BlockPos framePos : obsidian)
		{
			if(Math.abs(framePos.getY() - pos.getY()) > verticalRadius)
				continue;
			if(Math.abs(framePos.getX() - pos.getX()) > horizontalRadius)
				continue;
			if(Math.abs(framePos.getZ() - pos.getZ()) > horizontalRadius)
				continue;
			return true;
		}
		
		return false;
	}
	
	private boolean isChestRuinedPortalSignal(RectangleAxis axis,
		int planeCoord, int primary0, int primary1, int y0, int y1,
		BlockPos chestPos)
	{
		int chestPrimary =
			axis == RectangleAxis.X ? chestPos.getX() : chestPos.getZ();
		int chestNormal =
			axis == RectangleAxis.X ? chestPos.getZ() : chestPos.getX();
		int normalDistance = Math.abs(chestNormal - planeCoord);
		
		// Ruined portal chests are typically in front/behind the frame.
		if(normalDistance < 1 || normalDistance > 4)
			return false;
		
		// Exclude chests clearly to the side of the frame span.
		if(chestPrimary < primary0 - 1 || chestPrimary > primary1 + 1)
			return false;
		
		// Allow a small vertical band around the frame base/height.
		return chestPos.getY() >= y0 - 4 && chestPos.getY() <= y1 + 2;
	}
	
	private boolean isHardRuinedPortalReject(RuinedPortalSignals s)
	{
		if(excludeCryingObsidian.isChecked() && s.hasCrying())
			return true;
		if(excludeChests.isChecked() && s.hasChest())
			return true;
		if(excludeMagma.isChecked() && s.hasMagma())
			return true;
		if(excludeLava.isChecked() && s.hasLava())
			return true;
		if(excludeNetherrack.isChecked()
			&& (s.netherrackUnder() >= 2 || s.netherrackCount() >= 18))
			return true;
		if(excludeGoldBlocks.isChecked() && s.effectiveGoldCount() > 0)
		{
			boolean ruinedContext = s.hasCrying() || s.hasChest()
				|| s.hasMagma() || s.hasLava()
				|| (excludeNetherrack.isChecked() && s.netherrackUnder() > 0);
			if(ruinedContext)
				return true;
		}
		
		return false;
	}
	
	private int scoreRuinedPortalExclusion(RuinedPortalSignals s)
	{
		int score = 0;
		
		if(excludeCryingObsidian.isChecked() && s.hasCrying())
			score -= 90;
		
		if(excludeChests.isChecked() && s.hasChest())
			score -= 110;
		
		if(excludeMagma.isChecked() && s.hasMagma())
			score -= 40;
		
		if(excludeLava.isChecked() && s.hasLava())
			score -= 40;
		
		if(excludeNetherrack.isChecked())
		{
			score -= Math.min(s.netherrackCount(), 20);
			score -= Math.min(s.netherrackUnder() * 3, 30);
		}
		
		if(excludeGoldBlocks.isChecked() && s.effectiveGoldCount() > 0)
		{
			boolean ruinedContext = s.hasCrying() || s.hasChest()
				|| s.hasMagma() || s.hasLava()
				|| (excludeNetherrack.isChecked() && s.netherrackUnder() > 0);
			if(ruinedContext)
				score -= 24 + Math.min(8, s.effectiveGoldCount() * 2);
			else
				score -= Math.min(8, s.effectiveGoldCount());
		}
		
		return score;
	}
	
	private boolean isBrokenPortalSearchEnabled()
	{
		return brokenNetherPortal.isEnabled()
			&& (!brokenPortalsNetherOnly.isChecked() || isInNetherDimension());
	}
	
	private long computeBrokenPortalSettingsFingerprint()
	{
		long hash = 17;
		hash = 31 * hash + (brokenNetherPortal.isEnabled() ? 1 : 0);
		hash = 31 * hash + (brokenNetherPortalTracer.isChecked() ? 1 : 0);
		hash = 31 * hash + (brokenPortalsNetherOnly.isChecked() ? 1 : 0);
		hash = 31 * hash + brokenPortalMinConfidence.getValueI();
		hash = 31 * hash + brokenPortalMaxInteriorSize.getValueI();
		hash = 31 * hash + (allowCompleteUnlitPortals.isChecked() ? 1 : 0);
		hash = 31 * hash + (excludeCryingObsidian.isChecked() ? 1 : 0);
		hash = 31 * hash + (excludeChests.isChecked() ? 1 : 0);
		hash = 31 * hash + (excludeMagma.isChecked() ? 1 : 0);
		hash = 31 * hash + (excludeLava.isChecked() ? 1 : 0);
		hash = 31 * hash + (excludeNetherrack.isChecked() ? 1 : 0);
		hash = 31 * hash + (excludeGoldBlocks.isChecked() ? 1 : 0);
		return hash;
	}
	
	private ArrayList<DiscoveryHit> buildDiscoveries(
		Map<PortalEspBlockGroup, ArrayList<BlockPos>> newBlocksByGroup)
	{
		ArrayList<DiscoveryHit> discoveries = new ArrayList<>();
		for(PortalEspBlockGroup group : groups)
		{
			ArrayList<BlockPos> newBlocks = newBlocksByGroup.get(group);
			if(newBlocks == null || newBlocks.isEmpty())
				continue;
			
			if(!usesStructureCenter(group))
			{
				for(BlockPos pos : newBlocks)
					discoveries.add(new DiscoveryHit(getDiscoveryLabel(group),
						new Vec3(pos.getX() + 0.5, pos.getY() + 0.5,
							pos.getZ() + 0.5)));
				continue;
			}
			
			// For grouped portal types, count one discovery per connected
			// structure, matching tracer behavior.
			HashSet<BlockPos> remaining = new HashSet<>(group.getPositions());
			HashSet<BlockPos> newSet = new HashSet<>(newBlocks);
			while(!remaining.isEmpty())
			{
				BlockPos start = remaining.iterator().next();
				remaining.remove(start);
				
				ArrayDeque<BlockPos> queue = new ArrayDeque<>();
				queue.add(start);
				
				boolean hasNewBlock = false;
				int count = 0;
				double sumX = 0;
				double sumY = 0;
				double sumZ = 0;
				
				while(!queue.isEmpty())
				{
					BlockPos current = queue.removeFirst();
					count++;
					sumX += current.getX() + 0.5;
					sumY += current.getY() + 0.5;
					sumZ += current.getZ() + 0.5;
					if(newSet.contains(current))
						hasNewBlock = true;
					
					for(Direction dir : Direction.values())
					{
						BlockPos neighbor = current.relative(dir);
						if(remaining.remove(neighbor))
							queue.addLast(neighbor);
					}
				}
				
				if(hasNewBlock && count > 0)
					discoveries.add(new DiscoveryHit(getDiscoveryLabel(group),
						new Vec3(sumX / count, sumY / count, sumZ / count)));
			}
		}
		
		return discoveries;
	}
	
	private String getDiscoveryLabel(PortalEspBlockGroup group)
	{
		if(group == null)
			return "Portal";
		if(group == netherPortal)
			return "Nether portal";
		if(group == endPortal)
			return "End portal";
		if(group == endPortalFrame)
			return "End portal frame";
		if(group == endGateway)
			return "End gateway";
		if(group == brokenNetherPortal)
			return "Broken nether portal";
		return "Portal";
	}
	
	private void playDiscoverySound()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		SoundEvent soundEvent = null;
		if(discoverySoundType.getSelected() == DetectionSound.CUSTOM)
		{
			String idStr = customDiscoverySoundId.getValue();
			if(idStr != null)
			{
				idStr = idStr.trim();
				if(!idStr.isEmpty())
				{
					try
					{
						Identifier id = Identifier.parse(idStr);
						soundEvent = BuiltInRegistries.SOUND_EVENT.getValue(id);
					}catch(Exception e)
					{
						// ignore invalid id
					}
				}
			}
		}else
		{
			soundEvent = discoverySoundType.getSelected().resolve();
		}
		
		if(soundEvent == null)
			return;
		
		float target = (float)(discoverySoundVolume.getValue() / 100.0);
		if(target <= 0f)
			return;
		
		int whole = (int)target;
		float remainder = target - whole;
		
		double x = MC.player.getX();
		double y = MC.player.getY();
		double z = MC.player.getZ();
		
		for(int i = 0; i < whole; i++)
		{
			MC.level.playLocalSound(x, y, z, soundEvent, SoundSource.PLAYERS,
				1F, 1F, false);
		}
		
		if(remainder > 0f)
		{
			MC.level.playLocalSound(x, y, z, soundEvent, SoundSource.PLAYERS,
				remainder, 1F, false);
		}
	}
	
	private void sendDiscoveryMessage(List<DiscoveryHit> discoveries)
	{
		if(discoveries == null || discoveries.isEmpty())
			return;
		
		if(discoveries.size() == 1)
		{
			DiscoveryHit d = discoveries.get(0);
			ChatUtils.message(String.format("%s discovered at %d, %d, %d.",
				d.label(), (int)Math.round(d.pos().x),
				(int)Math.round(d.pos().y), (int)Math.round(d.pos().z)));
			return;
		}
		
		DiscoveryHit d = discoveries.get(0);
		ChatUtils.message(String.format(
			"PortalESP discovered %d new portals (first: %s at %d, %d, %d).",
			discoveries.size(), d.label(), (int)Math.round(d.pos().x),
			(int)Math.round(d.pos().y), (int)Math.round(d.pos().z)));
	}
	
	private List<Vec3> getTracerTargets(PortalEspBlockGroup group)
	{
		if(!usesStructureCenter(group))
			return group.getBoxes().stream().map(AABB::getCenter).toList();
		
		List<Vec3> centers = getStructureCenters(group.getPositions());
		if(group == brokenNetherPortal)
			return mergeNearbyTracerTargets(centers, 6.0);
		return centers;
	}
	
	private boolean usesStructureCenter(PortalEspBlockGroup group)
	{
		return group == netherPortal || group == endPortalFrame
			|| group == endPortal || group == brokenNetherPortal;
	}
	
	private List<Vec3> getStructureCenters(List<BlockPos> positions)
	{
		if(positions.isEmpty())
			return List.of();
		
		HashSet<BlockPos> remaining = new HashSet<>(positions);
		ArrayList<Vec3> centers = new ArrayList<>();
		
		while(!remaining.isEmpty())
		{
			BlockPos start = remaining.iterator().next();
			remaining.remove(start);
			
			ArrayDeque<BlockPos> queue = new ArrayDeque<>();
			queue.add(start);
			
			int count = 0;
			double sumX = 0;
			double sumY = 0;
			double sumZ = 0;
			
			while(!queue.isEmpty())
			{
				BlockPos current = queue.removeFirst();
				count++;
				sumX += current.getX() + 0.5;
				sumY += current.getY() + 0.5;
				sumZ += current.getZ() + 0.5;
				
				for(Direction dir : Direction.values())
				{
					BlockPos neighbor = current.relative(dir);
					if(remaining.remove(neighbor))
						queue.addLast(neighbor);
				}
			}
			
			if(count > 0)
				centers.add(new Vec3(sumX / count, sumY / count, sumZ / count));
		}
		
		return centers;
	}
	
	private List<Vec3> mergeNearbyTracerTargets(List<Vec3> centers,
		double maxDistance)
	{
		if(centers.isEmpty())
			return List.of();
		
		double maxDistanceSq = maxDistance * maxDistance;
		ArrayList<Vec3> merged = new ArrayList<>();
		HashSet<Integer> remaining = new HashSet<>();
		for(int i = 0; i < centers.size(); i++)
			remaining.add(i);
		
		while(!remaining.isEmpty())
		{
			int start = remaining.iterator().next();
			remaining.remove(start);
			
			ArrayDeque<Integer> queue = new ArrayDeque<>();
			queue.add(start);
			int count = 0;
			double sumX = 0;
			double sumY = 0;
			double sumZ = 0;
			
			while(!queue.isEmpty())
			{
				int current = queue.removeFirst();
				Vec3 v = centers.get(current);
				count++;
				sumX += v.x;
				sumY += v.y;
				sumZ += v.z;
				
				ArrayList<Integer> toConnect = new ArrayList<>();
				for(int idx : remaining)
				{
					Vec3 other = centers.get(idx);
					if(v.distanceToSqr(other) <= maxDistanceSq)
						toConnect.add(idx);
				}
				
				for(int idx : toConnect)
				{
					remaining.remove(idx);
					queue.addLast(idx);
				}
			}
			
			if(count > 0)
				merged.add(new Vec3(sumX / count, sumY / count, sumZ / count));
		}
		
		return merged;
	}
	
	private record BrokenPortalCandidate(int score,
		HashSet<BlockPos> detectedFrame, RectangleAxis axis, int planeCoord,
		int primary0, int primary1, int y0, int y1)
	{}
	
	private record RuinedPortalSignals(boolean hasCrying, boolean hasChest,
		boolean hasMagma, boolean hasLava, int netherrackCount,
		int netherrackUnder, int effectiveGoldCount)
	{}
	
	private enum RectangleAxis
	{
		X
		{
			@Override
			BlockPos toPos(int primary, int y, int plane)
			{
				return new BlockPos(primary, y, plane);
			}
		},
		Z
		{
			@Override
			BlockPos toPos(int primary, int y, int plane)
			{
				return new BlockPos(plane, y, primary);
			}
		};
		
		abstract BlockPos toPos(int primary, int y, int plane);
	}
	
	private record DiscoveryHit(String label, Vec3 pos)
	{}
	
	private enum DetectionSound
	{
		NOTE_BLOCK_CHIME("Note Block Chime",
			"minecraft:block.note_block.chime"),
		EXPERIENCE_ORB_PICKUP("XP Pickup",
			"minecraft:entity.experience_orb.pickup"),
		AMETHYST_CHIME("Amethyst Chime",
			"minecraft:block.amethyst_block.chime"),
		BELL("Bell", "minecraft:block.bell.use"),
		CUSTOM("Custom", null);
		
		private final String name;
		private final String id;
		
		DetectionSound(String name, String id)
		{
			this.name = name;
			this.id = id;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
		
		private SoundEvent resolve()
		{
			if(id == null)
				return null;
			try
			{
				return BuiltInRegistries.SOUND_EVENT
					.getValue(Identifier.parse(id));
			}catch(Exception e)
			{
				return null;
			}
		}
	}
}
