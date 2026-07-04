/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.PrimitiveTopology;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstRenderLayers;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.BlockVertexCompiler;
import net.wurstclient.util.EasyVertexBuffer;
import net.wurstclient.util.RegionPos;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkSearcher;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

@SearchTags({"base finder", "factions"})
public final class BaseFinderHack extends Hack implements UpdateListener,
	RenderListener, CameraTransformViewBobbingListener, PacketInputListener
{
	private final BlockListSetting naturalBlocks = new BlockListSetting(
		"Natural Blocks",
		"These blocks will be considered part of natural generation.\n\n"
			+ "They will NOT be highlighted as player bases.",
		// Air/fluids/gases
		"minecraft:air", "minecraft:cave_air", "minecraft:bubble_column",
		"minecraft:void_air", "minecraft:water", "minecraft:lava",
		
		// Terrain (Overworld stone & dirt family)
		"minecraft:stone", "minecraft:dirt", "minecraft:coarse_dirt",
		"minecraft:rooted_dirt", "minecraft:podzol", "minecraft:mycelium",
		"minecraft:grass_block", "minecraft:gravel", "minecraft:clay",
		"minecraft:sand", "minecraft:red_sand", "minecraft:sandstone",
		"minecraft:red_sandstone", "minecraft:granite", "minecraft:diorite",
		"minecraft:andesite", "minecraft:tuff", "minecraft:calcite",
		"minecraft:smooth_basalt", "minecraft:bedrock",
		"minecraft:infested_stone", "minecraft:mossy_cobblestone",
		"minecraft:cobblestone", "minecraft:infested_chiseled_stone_bricks",
		"minecraft:infested_cobblestone",
		"minecraft:infested_cracked_stone_bricks",
		"minecraft:infested_deepslate", "minecraft:infested_mossy_stone_bricks",
		"minecraft:infested_stone_bricks",
		
		// Snow & ice
		"minecraft:snow", "minecraft:snow_block", "minecraft:powder_snow",
		"minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice",
		
		// Geodes
		"minecraft:amethyst_block", "minecraft:budding_amethyst",
		"minecraft:amethyst_cluster", "minecraft:large_amethyst_bud",
		"minecraft:medium_amethyst_bud", "minecraft:small_amethyst_bud",
		
		// Dripstone & caves
		"minecraft:dripstone_block", "minecraft:pointed_dripstone",
		"minecraft:glow_lichen", "minecraft:spore_blossom",
		"minecraft:small_dripleaf", "minecraft:big_dripleaf",
		"minecraft:cave_vines", "minecraft:cave_vines_plant",
		
		// Lush caves / moss & azalea
		"minecraft:moss_block", "minecraft:moss_carpet", "minecraft:azalea",
		"minecraft:flowering_azalea", "minecraft:azalea_leaves",
		"minecraft:flowering_azalea_leaves", "minecraft:rooted_dirt",
		"minecraft:hanging_roots",
		
		// Plants & flowers (Overworld)
		"minecraft:grass", "minecraft:tall_grass", "minecraft:fern",
		"minecraft:large_fern", "minecraft:dead_bush", "minecraft:dandelion",
		"minecraft:poppy", "minecraft:blue_orchid", "minecraft:allium",
		"minecraft:azure_bluet", "minecraft:red_tulip",
		"minecraft:orange_tulip", "minecraft:white_tulip",
		"minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower",
		"minecraft:lily_of_the_valley", "minecraft:sunflower",
		"minecraft:lilac", "minecraft:rose_bush", "minecraft:peony",
		"minecraft:lily_pad", "minecraft:sweet_berry_bush",
		"minecraft:leaf_litter", "minecraft:pink_petals",
		"minecraft:short_dry_grass", "minecraft:tall_dry_grass",
		"minecraft:wildflowers", "minecraft:bush", "minecraft:cactus_flower",
		"minecraft:firefly_bush",
		
		// Mushrooms
		"minecraft:red_mushroom", "minecraft:brown_mushroom",
		"minecraft:red_mushroom_block", "minecraft:brown_mushroom_block",
		"minecraft:mushroom_stem",
		
		// Trees & leaves (all current vanilla species)
		"minecraft:oak_log", "minecraft:oak_leaves", "minecraft:spruce_log",
		"minecraft:spruce_leaves", "minecraft:birch_log",
		"minecraft:birch_leaves", "minecraft:jungle_log",
		"minecraft:jungle_leaves", "minecraft:acacia_log",
		"minecraft:acacia_leaves", "minecraft:dark_oak_log",
		"minecraft:dark_oak_leaves", "minecraft:mangrove_log",
		"minecraft:mangrove_leaves", "minecraft:mangrove_roots",
		"minecraft:muddy_mangrove_roots", "minecraft:mangrove_propagule",
		"minecraft:cherry_log", "minecraft:cherry_leaves", "minecraft:bamboo",
		
		// Aquatic vegetation & coral
		"minecraft:seagrass", "minecraft:tall_seagrass", "minecraft:kelp",
		"minecraft:kelp_plant", "minecraft:sea_pickle", "minecraft:sugar_cane",
		"minecraft:tube_coral_block", "minecraft:brain_coral_block",
		"minecraft:bubble_coral_block", "minecraft:fire_coral_block",
		"minecraft:horn_coral_block", "minecraft:tube_coral",
		"minecraft:brain_coral", "minecraft:bubble_coral",
		"minecraft:fire_coral", "minecraft:horn_coral",
		"minecraft:tube_coral_fan", "minecraft:brain_coral_fan",
		"minecraft:bubble_coral_fan", "minecraft:fire_coral_fan",
		"minecraft:horn_coral_fan", "minecraft:dead_tube_coral_block",
		"minecraft:dead_brain_coral_block", "minecraft:dead_bubble_coral_block",
		"minecraft:dead_fire_coral_block", "minecraft:dead_horn_coral_block",
		"minecraft:dead_tube_coral", "minecraft:dead_brain_coral",
		"minecraft:dead_bubble_coral", "minecraft:dead_fire_coral",
		"minecraft:dead_horn_coral", "minecraft:dead_tube_coral_fan",
		"minecraft:dead_brain_coral_fan", "minecraft:dead_bubble_coral_fan",
		"minecraft:dead_fire_coral_fan", "minecraft:dead_horn_coral_fan",
		
		// Ocean monuments
		"minecraft:wet_sponge",
		
		// Ores (Overworld & Deepslate)
		"minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore",
		"minecraft:gold_ore", "minecraft:redstone_ore", "minecraft:emerald_ore",
		"minecraft:diamond_ore", "minecraft:lapis_ore", "minecraft:deepslate",
		"minecraft:deepslate_coal_ore", "minecraft:deepslate_iron_ore",
		"minecraft:deepslate_copper_ore", "minecraft:deepslate_gold_ore",
		"minecraft:deepslate_redstone_ore", "minecraft:deepslate_emerald_ore",
		"minecraft:deepslate_diamond_ore", "minecraft:deepslate_lapis_ore",
		"minecraft:raw_copper_block",
		
		// Archaeology
		"minecraft:suspicious_sand", "minecraft:suspicious_gravel",
		
		// Sculk / Deep Dark
		"minecraft:sculk", "minecraft:sculk_vein", "minecraft:sculk_sensor",
		"minecraft:sculk_catalyst", "minecraft:sculk_shrieker",
		
		// Nether terrain & flora
		"minecraft:netherrack", "minecraft:basalt", "minecraft:blackstone",
		"minecraft:soul_sand", "minecraft:soul_soil", "minecraft:magma_block",
		"minecraft:nether_quartz_ore", "minecraft:nether_gold_ore",
		"minecraft:ancient_debris", "minecraft:glowstone",
		"minecraft:crimson_nylium", "minecraft:warped_nylium",
		"minecraft:crimson_stem", "minecraft:warped_stem",
		"minecraft:crimson_roots", "minecraft:warped_roots",
		"minecraft:nether_sprouts", "minecraft:shroomlight",
		"minecraft:weeping_vines", "minecraft:weeping_vines_plant",
		"minecraft:twisting_vines", "minecraft:twisting_vines_plant",
		"minecraft:nether_wart_block", "minecraft:warped_wart_block",
		"minecraft:crimson_fungus", "minecraft:warped_fungus",
		"minecraft:nether_wart",
		
		// End terrain & plants (no player-crafted building sets)
		"minecraft:end_stone", "minecraft:chorus_plant",
		"minecraft:chorus_flower",
		
		// Pale Garden (Overworld)
		"minecraft:pale_oak_log", "minecraft:pale_oak_leaves",
		"minecraft:pale_moss_block", "minecraft:pale_moss_carpet",
		"minecraft:pale_hanging_moss", "minecraft:open_eyeblossom",
		"minecraft:closed_eyeblossom", "minecraft:resin_clump",
		
		// Crops & produce that world-generate
		"minecraft:pumpkin", "minecraft:melon", "minecraft:cocoa",
		
		// Trial Chambers
		"minecraft:trial_spawner", "minecraft:vault", "minecraft:candle",
		"minecraft:cobweb", "minecraft:chiseled_tuff",
		"minecraft:chiseled_tuff_bricks", "minecraft:polished_tuff",
		"minecraft:polished_tuff_slab", "minecraft:tuff_bricks",
		"minecraft:copper_block", "minecraft:cut_copper",
		"minecraft:cut_copper_slab", "minecraft:cut_copper_stairs",
		"minecraft:chiseled_copper", "minecraft:exposed_copper",
		"minecraft:exposed_cut_copper", "minecraft:exposed_cut_copper_slab",
		"minecraft:exposed_cut_copper_stairs",
		"minecraft:exposed_chiseled_copper", "minecraft:weathered_copper",
		"minecraft:weathered_cut_copper", "minecraft:weathered_cut_copper_slab",
		"minecraft:weathered_cut_copper_stairs",
		"minecraft:weathered_chiseled_copper", "minecraft:oxidized_copper",
		"minecraft:oxidized_cut_copper", "minecraft:oxidized_cut_copper_slab",
		"minecraft:oxidized_cut_copper_stairs",
		"minecraft:oxidized_chiseled_copper", "minecraft:waxed_copper_block",
		"minecraft:waxed_cut_copper", "minecraft:waxed_cut_copper_slab",
		"minecraft:waxed_cut_copper_stairs", "minecraft:waxed_chiseled_copper",
		"minecraft:waxed_exposed_copper", "minecraft:waxed_exposed_cut_copper",
		"minecraft:waxed_exposed_cut_copper_slab",
		"minecraft:waxed_exposed_cut_copper_stairs",
		"minecraft:waxed_exposed_chiseled_copper",
		"minecraft:waxed_weathered_copper",
		"minecraft:waxed_weathered_cut_copper",
		"minecraft:waxed_weathered_cut_copper_slab",
		"minecraft:waxed_weathered_cut_copper_stairs",
		"minecraft:waxed_weathered_chiseled_copper",
		"minecraft:waxed_oxidized_copper",
		"minecraft:waxed_oxidized_cut_copper",
		"minecraft:waxed_oxidized_cut_copper_slab",
		"minecraft:waxed_oxidized_cut_copper_stairs",
		"minecraft:waxed_oxidized_chiseled_copper", "minecraft:copper_bars",
		"minecraft:copper_grate", "minecraft:copper_chain",
		"minecraft:copper_lantern", "minecraft:copper_bulb",
		"minecraft:copper_door", "minecraft:copper_trapdoor",
		"minecraft:copper_torch", "minecraft:copper_wall_torch",
		"minecraft:copper_chest", "minecraft:copper_golem_statue",
		"minecraft:exposed_copper_bars", "minecraft:exposed_copper_grate",
		"minecraft:exposed_copper_chain", "minecraft:exposed_copper_lantern",
		"minecraft:exposed_copper_bulb", "minecraft:exposed_copper_door",
		"minecraft:exposed_copper_trapdoor", "minecraft:exposed_copper_chest",
		"minecraft:exposed_copper_golem_statue",
		"minecraft:weathered_copper_bars", "minecraft:weathered_copper_grate",
		"minecraft:weathered_copper_chain",
		"minecraft:weathered_copper_lantern", "minecraft:weathered_copper_bulb",
		"minecraft:weathered_copper_door",
		"minecraft:weathered_copper_trapdoor",
		"minecraft:weathered_copper_chest",
		"minecraft:weathered_copper_golem_statue",
		"minecraft:oxidized_copper_bars", "minecraft:oxidized_copper_grate",
		"minecraft:oxidized_copper_chain", "minecraft:oxidized_copper_lantern",
		"minecraft:oxidized_copper_bulb", "minecraft:oxidized_copper_door",
		"minecraft:oxidized_copper_trapdoor", "minecraft:oxidized_copper_chest",
		"minecraft:oxidized_copper_golem_statue", "minecraft:waxed_copper_bars",
		"minecraft:waxed_copper_grate", "minecraft:waxed_copper_chain",
		"minecraft:waxed_copper_lantern", "minecraft:waxed_copper_bulb",
		"minecraft:waxed_copper_door", "minecraft:waxed_copper_trapdoor",
		"minecraft:waxed_copper_chest", "minecraft:waxed_copper_golem_statue",
		"minecraft:waxed_exposed_copper_bars",
		"minecraft:waxed_exposed_copper_grate",
		"minecraft:waxed_exposed_copper_chain",
		"minecraft:waxed_exposed_copper_lantern",
		"minecraft:waxed_exposed_copper_bulb",
		"minecraft:waxed_exposed_copper_door",
		"minecraft:waxed_exposed_copper_trapdoor",
		"minecraft:waxed_exposed_copper_chest",
		"minecraft:waxed_exposed_copper_golem_statue",
		"minecraft:waxed_weathered_copper_bars",
		"minecraft:waxed_weathered_copper_grate",
		"minecraft:waxed_weathered_copper_chain",
		"minecraft:waxed_weathered_copper_lantern",
		"minecraft:waxed_weathered_copper_bulb",
		"minecraft:waxed_weathered_copper_door",
		"minecraft:waxed_weathered_copper_trapdoor",
		"minecraft:waxed_weathered_copper_chest",
		"minecraft:waxed_weathered_copper_golem_statue",
		"minecraft:waxed_oxidized_copper_bars",
		"minecraft:waxed_oxidized_copper_grate",
		"minecraft:waxed_oxidized_copper_chain",
		"minecraft:waxed_oxidized_copper_lantern",
		"minecraft:waxed_oxidized_copper_bulb",
		"minecraft:waxed_oxidized_copper_door",
		"minecraft:waxed_oxidized_copper_trapdoor",
		"minecraft:waxed_oxidized_copper_chest",
		"minecraft:waxed_oxidized_copper_golem_statue",
		
		// Natural obsidian (e.g., lava-water, ruined portals) & world spawners
		"minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:spawner",
		
		// Common naturally-generated "false base" noise blocks
		"minecraft:oak_planks", "minecraft:oak_fence", "minecraft:rail",
		"minecraft:torch", "minecraft:wall_torch", "minecraft:packed_mud",
		
		// Misc vines
		"minecraft:vine");
	
	private final ColorSetting color = new ColorSetting("Color",
		"Man-made blocks will be highlighted in this color.", Color.RED);
	
	// Y limit controls
	private final SliderSetting minY = new SliderSetting("Min Y", 0, -64, 384,
		1, SliderSetting.ValueDisplay.INTEGER);
	private final SliderSetting maxY = new SliderSetting("Max Y", 319, -64, 384,
		1, SliderSetting.ValueDisplay.INTEGER);
	
	// Tracer option
	private final net.wurstclient.settings.CheckboxSetting showTracers =
		new net.wurstclient.settings.CheckboxSetting("Tracers",
			"Draw tracer lines from your view to found blocks.", false);
	private final CheckboxSetting tracerFlash = new CheckboxSetting(
		"Tracer flash", "Make tracers pulse with a smooth fade.", false);
	
	// Static area (sticky) option
	private final CheckboxSetting stickyArea = new CheckboxSetting(
		"Sticky area",
		"Off: Re-centers every scan around your position.\nOn: Keeps results anchored so you can path back to them.",
		false);
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around you to scan for man-made blocks.",
		ChunkAreaSetting.ChunkArea.A11);
	private boolean lastSticky;
	private ChunkPos lastPlayerChunk;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	
	private ArrayList<String> blockNames;
	private java.util.Set<String> naturalExactIds;
	private java.util.Set<String> forcedManMadeAlwaysIds;
	private java.util.Set<String> forcedManMadeUnderwaterIds;
	
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(area);
	private ArrayList<int[]> vertices = new ArrayList<>();
	private java.util.ArrayList<net.minecraft.world.phys.Vec3> tracerEnds =
		new java.util.ArrayList<>();
	private EasyVertexBuffer vertexBuffer;
	
	private int counter;
	private int lastMatchesVersion = -1;
	private int lastMinY;
	private int lastMaxY;
	
	private RegionPos lastRegion;
	
	public BaseFinderHack()
	{
		super("BaseFinder");
		setCategory(Category.INTEL);
		addSetting(naturalBlocks);
		addSetting(color);
		addSetting(minY);
		addSetting(maxY);
		addSetting(showTracers);
		addSetting(tracerFlash);
		addSetting(stickyArea);
		addSetting(area);
	}
	
	@Override
	public String getRenderName()
	{
		String name = getName() + " [";
		
		// counter
		if(counter >= 10000)
			name += "10000+ blocks";
		else if(counter == 1)
			name += "1 block";
		else if(counter == 0)
			name += "nothing";
		else
			name += counter + " blocks";
		
		name += " found]";
		return name;
	}
	
	@Override
	protected void onEnable()
	{
		syncNaturalBlocksWithBuiltins();
		blockNames = new ArrayList<>(naturalBlocks.getBlockNames());
		rebuildNaturalCaches();
		lastMinY = minY.getValueI();
		lastMaxY = maxY.getValueI();
		lastSticky = stickyArea.isChecked();
		lastPlayerChunk = ChunkPos.containing(MC.player.blockPosition());
		lastAreaSelection = area.getSelected();
		coordinator.setQuery((pos, state) -> {
			String idFull = BlockUtils.getName(state.getBlock());
			if(forcedManMadeAlwaysIds != null
				&& forcedManMadeAlwaysIds.contains(idFull))
				return true;
			if(forcedManMadeUnderwaterIds != null
				&& forcedManMadeUnderwaterIds.contains(idFull))
				return state.getFluidState() == null
					|| state.getFluidState().isEmpty();
			return naturalExactIds == null || !naturalExactIds.contains(idFull);
		});
		
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
		vertices.clear();
		tracerEnds.clear();
		coordinator.reset();
		counter = 0;
		
		if(vertexBuffer != null)
			vertexBuffer.close();
		vertexBuffer = null;
		lastRegion = null;
		lastMatchesVersion = -1;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		RegionPos region = RenderUtils.getCameraRegion();
		if(vertexBuffer != null && !region.equals(lastRegion))
			rebuildVertexBuffer(region);
		
		if(vertexBuffer == null)
			return;
		
		matrixStack.pushPose();
		RenderUtils.applyRegionalRenderOffset(matrixStack, region);
		
		vertexBuffer.draw(matrixStack, WurstRenderLayers.ESP_QUADS,
			color.getColorF(), 0.25F);
		
		matrixStack.popPose();
		
		// Tracers (world-space, no regional offset) using last compiled set
		if(showTracers.isChecked() && !tracerEnds.isEmpty())
		{
			int lineColor = color.getColorI(0x80);
			if(tracerFlash.isChecked())
				lineColor = RenderUtils.flashColor(lineColor);
			RenderUtils.drawTracers("BaseFinder", matrixStack, partialTicks,
				tracerEnds, lineColor, false);
		}
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(showTracers.isChecked())
			event.cancel();
	}
	
	@Override
	public void onUpdate()
	{
		syncNaturalBlocksWithBuiltins();
		// Detect block list changes without toggling the hack
		java.util.ArrayList<String> current =
			new java.util.ArrayList<>(naturalBlocks.getBlockNames());
		if(!current.equals(blockNames))
		{
			blockNames = current;
			rebuildNaturalCaches();
			coordinator.setQuery((pos, state) -> {
				String idFull = BlockUtils.getName(state.getBlock());
				if(forcedManMadeAlwaysIds != null
					&& forcedManMadeAlwaysIds.contains(idFull))
					return true;
				if(forcedManMadeUnderwaterIds != null
					&& forcedManMadeUnderwaterIds.contains(idFull))
					return state.getFluidState() == null
						|| state.getFluidState().isEmpty();
				return naturalExactIds == null
					|| !naturalExactIds.contains(idFull);
			});
			clearRenderCache();
		}
		
		// Search-like sticky behavior
		boolean sticky = stickyArea.isChecked();
		if(sticky != lastSticky)
		{
			coordinator.reset();
			clearRenderCache();
			lastSticky = sticky;
		}
		
		ChunkPos currentChunk = ChunkPos.containing(MC.player.blockPosition());
		if(!sticky && !currentChunk.equals(lastPlayerChunk))
		{
			lastPlayerChunk = currentChunk;
			coordinator.reset();
			clearRenderCache();
		}
		
		ChunkAreaSetting.ChunkArea currentArea = area.getSelected();
		if(currentArea != lastAreaSelection)
		{
			lastAreaSelection = currentArea;
			coordinator.reset();
			clearRenderCache();
		}
		
		int worldMinY = MC.level.getMinY();
		int worldMaxY = MC.level.getMaxY() - 1;
		int clampedMinY = Math.max(worldMinY, minY.getValueI());
		int clampedMaxY = Math.min(worldMaxY, maxY.getValueI());
		if(clampedMinY > clampedMaxY)
		{
			// Auto-recover invalid user range ordering.
			int t = clampedMinY;
			clampedMinY = clampedMaxY;
			clampedMaxY = t;
		}
		
		if(clampedMinY != lastMinY || clampedMaxY != lastMaxY)
		{
			lastMinY = clampedMinY;
			lastMaxY = clampedMaxY;
			clearRenderCache();
		}
		
		coordinator.update();
		if(!coordinator.hasReadyMatches())
			return;
		
		int matchesVersion = coordinator.getMatchesVersion();
		if(matchesVersion == lastMatchesVersion)
			return;
		lastMatchesVersion = matchesVersion;
		
		HashSet<BlockPos> filtered = new HashSet<>();
		for(ChunkSearcher.Result r : coordinator.getReadyMatches().toList())
		{
			BlockPos pos = r.pos();
			if(pos.getY() < clampedMinY || pos.getY() > clampedMaxY)
				continue;
			filtered.add(pos.immutable());
			if(filtered.size() >= 10000)
				break;
		}
		
		counter = filtered.size();
		vertices = BlockVertexCompiler.compile(filtered);
		tracerEnds = buildAdaptiveTracerEnds(filtered);
		rebuildVertexBuffer(RenderUtils.getCameraRegion());
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		coordinator.onReceivedPacket(event);
	}
	
	private void clearRenderCache()
	{
		vertices.clear();
		tracerEnds.clear();
		counter = 0;
		lastMatchesVersion = -1;
		if(vertexBuffer != null)
		{
			vertexBuffer.close();
			vertexBuffer = null;
		}
		lastRegion = null;
	}
	
	private void rebuildVertexBuffer(RegionPos region)
	{
		if(vertexBuffer != null)
			vertexBuffer.close();
		vertexBuffer = EasyVertexBuffer.createAndUpload(PrimitiveTopology.QUADS,
			DefaultVertexFormat.POSITION_COLOR, buffer -> {
				for(int[] vertex : vertices)
					buffer.addVertex(vertex[0] - region.x(), vertex[1],
						vertex[2] - region.z()).setColor(0xFFFFFFFF);
			});
		lastRegion = region;
	}
	
	private java.util.ArrayList<net.minecraft.world.phys.Vec3> buildAdaptiveTracerEnds(
		java.util.Set<BlockPos> filtered)
	{
		java.util.ArrayList<net.minecraft.world.phys.Vec3> all =
			new java.util.ArrayList<>(filtered.size());
		for(BlockPos p : filtered)
			all.add(new net.minecraft.world.phys.Vec3(p.getX() + 0.5,
				p.getY() + 0.5, p.getZ() + 0.5));
		
		if(all.size() <= 80 || MC.player == null)
			return all;
		
		net.minecraft.world.phys.Vec3 eyes = MC.player.getEyePosition(1.0F);
		net.minecraft.world.phys.Vec3 look =
			MC.player.getViewVector(1.0F).normalize();
		
		double nearestSq = Double.MAX_VALUE;
		for(net.minecraft.world.phys.Vec3 v : all)
			nearestSq = Math.min(nearestSq, v.distanceToSqr(eyes));
		
		double nearest = Math.sqrt(nearestSq);
		int maxTracers;
		int binDeg;
		double fovDot;
		if(nearest < 32)
		{
			maxTracers = 72;
			binDeg = 12;
			fovDot = Math.cos(Math.toRadians(75));
		}else if(nearest < 64)
		{
			maxTracers = 48;
			binDeg = 18;
			fovDot = Math.cos(Math.toRadians(70));
		}else if(nearest < 128)
		{
			maxTracers = 24;
			binDeg = 26;
			fovDot = Math.cos(Math.toRadians(65));
		}else
		{
			maxTracers = 12;
			binDeg = 36;
			fovDot = Math.cos(Math.toRadians(60));
		}
		
		java.util.HashMap<Long, net.minecraft.world.phys.Vec3> inFovBins =
			new java.util.HashMap<>();
		java.util.HashMap<Long, net.minecraft.world.phys.Vec3> outFovBins =
			new java.util.HashMap<>();
		java.util.HashMap<Long, Double> inFovDist = new java.util.HashMap<>();
		java.util.HashMap<Long, Double> outFovDist = new java.util.HashMap<>();
		
		for(net.minecraft.world.phys.Vec3 v : all)
		{
			net.minecraft.world.phys.Vec3 d = v.subtract(eyes);
			double distSq = d.lengthSqr();
			if(distSq < 1.0E-6)
				continue;
			
			net.minecraft.world.phys.Vec3 n = d.normalize();
			double dot = n.dot(look);
			double yaw = Math.toDegrees(Math.atan2(n.z, n.x));
			double pitch = Math.toDegrees(Math.asin(n.y));
			int yawBin = (int)Math.floor((yaw + 180.0) / binDeg);
			int pitchBin = (int)Math.floor((pitch + 90.0) / binDeg);
			long key = (((long)yawBin) << 32) ^ (pitchBin & 0xffffffffL);
			
			if(dot >= fovDot)
			{
				Double old = inFovDist.get(key);
				if(old == null || distSq < old)
				{
					inFovDist.put(key, distSq);
					inFovBins.put(key, v);
				}
			}else
			{
				Double old = outFovDist.get(key);
				if(old == null || distSq < old)
				{
					outFovDist.put(key, distSq);
					outFovBins.put(key, v);
				}
			}
		}
		
		java.util.ArrayList<net.minecraft.world.phys.Vec3> selected =
			new java.util.ArrayList<>(inFovBins.values());
		selected.sort(
			java.util.Comparator.comparingDouble(v -> v.distanceToSqr(eyes)));
		
		if(selected.size() < maxTracers)
		{
			java.util.ArrayList<net.minecraft.world.phys.Vec3> fallback =
				new java.util.ArrayList<>(outFovBins.values());
			fallback.sort(java.util.Comparator
				.comparingDouble(v -> v.distanceToSqr(eyes)));
			for(net.minecraft.world.phys.Vec3 v : fallback)
			{
				if(selected.size() >= maxTracers)
					break;
				selected.add(v);
			}
		}
		
		if(selected.isEmpty())
		{
			all.sort(java.util.Comparator
				.comparingDouble(v -> v.distanceToSqr(eyes)));
			selected.add(all.get(0));
		}
		
		if(selected.size() > maxTracers)
			selected.subList(maxTracers, selected.size()).clear();
		
		return selected;
	}
	
	private void rebuildNaturalCaches()
	{
		java.util.HashSet<String> exact = new java.util.HashSet<>();
		for(String s : blockNames)
		{
			net.minecraft.resources.Identifier id =
				net.minecraft.resources.Identifier.tryParse(s);
			if(id != null)
				exact.add(id.toString());
		}
		naturalExactIds = exact;
		
		java.util.HashSet<String> forcedManMadeAlways =
			new java.util.HashSet<>();
		java.util.HashSet<String> forcedManMadeWhenDry =
			new java.util.HashSet<>();
		
		// User-requested base indicators that should never be treated as
		// natural.
		// (Includes typo corrections and grouped requests like all
		// beds/corals/terracottas.)
		java.util.Collections.addAll(forcedManMadeAlways,
			"minecraft:raw_iron_block", "minecraft:bee_nest",
			"minecraft:iron_chain", "minecraft:potted_dead_bush",
			"minecraft:cobbled_deepslate", "minecraft:red_candle",
			"minecraft:red_glazed_terracotta", "minecraft:chiseled_sandstone",
			"minecraft:dispenser", "minecraft:ladder", "minecraft:cactus",
			"minecraft:mossy_stone_bricks", "minecraft:cracked_stone_bricks",
			"minecraft:chiseled_stone_bricks", "minecraft:stone_brick_stairs",
			"minecraft:stone_bricks", "minecraft:iron_bars",
			"minecraft:stone_brick_slab", "minecraft:mossy_stone_brick_stairs",
			"minecraft:red_concrete", "minecraft:white_stained_glass",
			"minecraft:black_stained_glass", "minecraft:mud",
			"minecraft:stone_button", "minecraft:mangrove_wood",
			"minecraft:cut_sandstone", "minecraft:polished_granite",
			"minecraft:light_blue_terracotta", "minecraft:sandstone_stairs",
			"minecraft:stone_slab", "minecraft:tripwire",
			"minecraft:tripwire_hook", "minecraft:creaking_heart",
			"minecraft:dirt_path",
			
			// All bed colors
			"minecraft:white_bed", "minecraft:orange_bed",
			"minecraft:magenta_bed", "minecraft:light_blue_bed",
			"minecraft:yellow_bed", "minecraft:lime_bed", "minecraft:pink_bed",
			"minecraft:gray_bed", "minecraft:light_gray_bed",
			"minecraft:cyan_bed", "minecraft:purple_bed", "minecraft:blue_bed",
			"minecraft:brown_bed", "minecraft:green_bed", "minecraft:red_bed",
			"minecraft:black_bed",
			
			// All terracottas
			"minecraft:terracotta", "minecraft:white_terracotta",
			"minecraft:orange_terracotta", "minecraft:magenta_terracotta",
			"minecraft:light_blue_terracotta", "minecraft:yellow_terracotta",
			"minecraft:lime_terracotta", "minecraft:pink_terracotta",
			"minecraft:gray_terracotta", "minecraft:light_gray_terracotta",
			"minecraft:cyan_terracotta", "minecraft:purple_terracotta",
			"minecraft:blue_terracotta", "minecraft:brown_terracotta",
			"minecraft:green_terracotta", "minecraft:red_terracotta",
			"minecraft:black_terracotta",
			
			// All coral types (live + dead, blocks + corals + fans + wall fans)
			"minecraft:tube_coral_block", "minecraft:brain_coral_block",
			"minecraft:bubble_coral_block", "minecraft:fire_coral_block",
			"minecraft:horn_coral_block", "minecraft:tube_coral",
			"minecraft:brain_coral", "minecraft:bubble_coral",
			"minecraft:fire_coral", "minecraft:horn_coral",
			"minecraft:tube_coral_fan", "minecraft:brain_coral_fan",
			"minecraft:bubble_coral_fan", "minecraft:fire_coral_fan",
			"minecraft:horn_coral_fan", "minecraft:tube_coral_wall_fan",
			"minecraft:brain_coral_wall_fan", "minecraft:bubble_coral_wall_fan",
			"minecraft:fire_coral_wall_fan", "minecraft:horn_coral_wall_fan",
			"minecraft:dead_tube_coral_block",
			"minecraft:dead_brain_coral_block",
			"minecraft:dead_bubble_coral_block",
			"minecraft:dead_fire_coral_block",
			"minecraft:dead_horn_coral_block", "minecraft:dead_tube_coral",
			"minecraft:dead_brain_coral", "minecraft:dead_bubble_coral",
			"minecraft:dead_fire_coral", "minecraft:dead_horn_coral",
			"minecraft:dead_tube_coral_fan", "minecraft:dead_brain_coral_fan",
			"minecraft:dead_bubble_coral_fan", "minecraft:dead_fire_coral_fan",
			"minecraft:dead_horn_coral_fan",
			"minecraft:dead_tube_coral_wall_fan",
			"minecraft:dead_brain_coral_wall_fan",
			"minecraft:dead_bubble_coral_wall_fan",
			"minecraft:dead_fire_coral_wall_fan",
			"minecraft:dead_horn_coral_wall_fan");
		
		// Only suspicious when NOT underwater (your boat/ocean context).
		java.util.Collections.addAll(forcedManMadeWhenDry,
			// Sea lantern + all prismarine variants
			"minecraft:sea_lantern", "minecraft:prismarine",
			"minecraft:prismarine_slab", "minecraft:prismarine_stairs",
			"minecraft:prismarine_wall", "minecraft:prismarine_bricks",
			"minecraft:prismarine_brick_slab",
			"minecraft:prismarine_brick_stairs", "minecraft:dark_prismarine",
			"minecraft:dark_prismarine_slab",
			"minecraft:dark_prismarine_stairs",
			
			// Spruce set
			"minecraft:spruce_planks", "minecraft:spruce_stairs",
			"minecraft:spruce_slab", "minecraft:spruce_fence",
			"minecraft:spruce_fence_gate", "minecraft:spruce_door",
			"minecraft:spruce_trapdoor", "minecraft:spruce_button",
			"minecraft:spruce_pressure_plate", "minecraft:spruce_sign",
			"minecraft:spruce_wall_sign", "minecraft:spruce_hanging_sign",
			"minecraft:spruce_wall_hanging_sign",
			
			// Jungle set
			"minecraft:jungle_planks", "minecraft:jungle_stairs",
			"minecraft:jungle_slab", "minecraft:jungle_fence",
			"minecraft:jungle_fence_gate", "minecraft:jungle_door",
			"minecraft:jungle_trapdoor", "minecraft:jungle_button",
			"minecraft:jungle_pressure_plate", "minecraft:jungle_sign",
			"minecraft:jungle_wall_sign", "minecraft:jungle_hanging_sign",
			"minecraft:jungle_wall_hanging_sign");
		
		forcedManMadeAlwaysIds = forcedManMadeAlways;
		forcedManMadeUnderwaterIds = forcedManMadeWhenDry;
	}
	
	private void syncNaturalBlocksWithBuiltins()
	{
		for(String builtin : naturalBlocks.getDefaultBlockNames())
			if(!naturalBlocks.contains(builtin))
				naturalBlocks.addRawName(builtin);
	}
}
