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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
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
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredPoint;
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
	
	private final List<PortalEspBlockGroup> groups =
		Arrays.asList(netherPortal, endPortal, endPortalFrame, endGateway);
	
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
			|| state.getBlock() == Blocks.END_GATEWAY;
	
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(query, area);
	
	private boolean groupsUpToDate;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	private ChunkPos lastPlayerChunk;
	private int lastMatchesVersion;
	private final HashSet<BlockPos> discoveredPositions = new HashSet<>();
	
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
		addSetting(stickyArea);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
	}
	
	@Override
	protected void onEnable()
	{
		groupsUpToDate = false;
		discoveredPositions.clear();
		lastAreaSelection = area.getSelected();
		lastPlayerChunk = new ChunkPos(MC.player.blockPosition());
		lastMatchesVersion = coordinator.getMatchesVersion();
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
		ChunkAreaSetting.ChunkArea currentArea = area.getSelected();
		if(currentArea != lastAreaSelection)
		{
			lastAreaSelection = currentArea;
			coordinator.reset();
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
				return;
			
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
			
			List<Vec3> ends = getTracerTargets(group);
			if(ends.isEmpty())
				continue;
			
			int color = group.getColorI(0x80);
			double width = lineThickness.getValue();
			List<ColoredPoint> points =
				ends.stream().map(v -> new ColoredPoint(v, color)).toList();
			
			RenderUtils.drawTracers(matrixStack, partialTicks, points, false,
				width);
		}
	}
	
	private void updateGroupBoxes()
	{
		groups.forEach(PortalEspBlockGroup::clear);
		HashMap<PortalEspBlockGroup, ArrayList<BlockPos>> newBlocksByGroup =
			new HashMap<>();
		coordinator.getReadyMatches()
			.forEach(result -> addToGroupBoxes(result, newBlocksByGroup));
		
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
	
	private void addToGroupBoxes(Result result,
		Map<PortalEspBlockGroup, ArrayList<BlockPos>> newBlocksByGroup)
	{
		if(onlyAboveGround.isChecked()
			&& result.pos().getY() < aboveGroundY.getValue())
			return;
		for(PortalEspBlockGroup group : groups)
			if(result.state().getBlock() == group.getBlock())
			{
				BlockPos pos = result.pos().immutable();
				group.add(pos);
				if(discoveredPositions.add(pos))
					newBlocksByGroup
						.computeIfAbsent(group, g -> new ArrayList<>())
						.add(pos);
				break;
			}
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
		
		return getStructureCenters(group.getPositions());
	}
	
	private boolean usesStructureCenter(PortalEspBlockGroup group)
	{
		return group == netherPortal || group == endPortalFrame
			|| group == endPortal;
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
