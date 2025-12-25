/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstRenderLayers;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.BlockVertexCompiler;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.EasyVertexBuffer;
import net.wurstclient.util.RegionPos;
import net.wurstclient.util.RenderUtils;

@SearchTags({"base finder", "factions"})
public final class BaseFinderHack extends Hack implements UpdateListener,
	RenderListener, CameraTransformViewBobbingListener
{
	private final BlockListSetting naturalBlocks = new BlockListSetting(
		"Natural Blocks",
		"These blocks will be considered part of natural generation.\n\n"
			+ "They will NOT be highlighted as player bases.",
		// Air/fluids/gases
		"minecraft:air", "minecraft:cave_air", "minecraft:bubble_column",
		"minecraft:water", "minecraft:lava",
		
		// Terrain (Overworld stone & dirt family)
		"minecraft:stone", "minecraft:dirt", "minecraft:coarse_dirt",
		"minecraft:rooted_dirt", "minecraft:podzol", "minecraft:mycelium",
		"minecraft:grass_block", "minecraft:gravel", "minecraft:clay",
		"minecraft:sand", "minecraft:red_sand", "minecraft:sandstone",
		"minecraft:red_sandstone", "minecraft:granite", "minecraft:diorite",
		"minecraft:andesite", "minecraft:tuff", "minecraft:calcite",
		"minecraft:smooth_basalt", "minecraft:bedrock",
		"minecraft:infested_stone", "minecraft:mossy_cobblestone",
		
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
		"minecraft:trial_spawner",
		
		// Natural obsidian (e.g., lava-water, ruined portals) & world spawners
		"minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:spawner",
		
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
	
	// Static area (sticky) option
	private final CheckboxSetting stickyArea = new CheckboxSetting(
		"Sticky area",
		"Off: Re-centers every scan around your position.\nOn: Keeps results anchored so you can path back to them.",
		false);
	private BlockPos scanCenter;
	private boolean lastSticky;
	
	private ArrayList<String> blockNames;
	private java.util.Set<String> naturalExactIds;
	private String[] naturalKeywords;
	
	private final HashSet<BlockPos> matchingBlocks = new HashSet<>();
	private ArrayList<int[]> vertices = new ArrayList<>();
	private java.util.ArrayList<net.minecraft.world.phys.Vec3> tracerEnds =
		new java.util.ArrayList<>();
	private EasyVertexBuffer vertexBuffer;
	
	private int messageTimer = 0;
	private int counter;
	
	private RegionPos lastRegion;
	
	public BaseFinderHack()
	{
		super("BaseFinder");
		setCategory(Category.RENDER);
		addSetting(naturalBlocks);
		addSetting(color);
		addSetting(minY);
		addSetting(maxY);
		addSetting(showTracers);
		addSetting(stickyArea);
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
		// reset timer
		messageTimer = 0;
		blockNames = new ArrayList<>(naturalBlocks.getBlockNames());
		rebuildNaturalCaches();
		scanCenter = BlockPos.containing(MC.player.getX(), 0, MC.player.getZ());
		lastSticky = stickyArea.isChecked();
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		matchingBlocks.clear();
		vertices.clear();
		tracerEnds.clear();
		
		if(vertexBuffer != null)
			vertexBuffer.close();
		vertexBuffer = null;
		lastRegion = null;
		scanCenter = null;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		RegionPos region = RenderUtils.getCameraRegion();
		if(!region.equals(lastRegion))
			onUpdate();
		
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
			RenderUtils.drawTracers(matrixStack, partialTicks, tracerEnds,
				lineColor, false);
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
		// Detect block list changes without toggling the hack
		java.util.ArrayList<String> current =
			new java.util.ArrayList<>(naturalBlocks.getBlockNames());
		if(!current.equals(blockNames))
		{
			blockNames = current;
			rebuildNaturalCaches();
			matchingBlocks.clear();
			vertices.clear();
			tracerEnds.clear();
			if(vertexBuffer != null)
			{
				vertexBuffer.close();
				vertexBuffer = null;
			}
			lastRegion = null;
		}
		
		// Update scan center based on sticky toggle
		boolean sticky = stickyArea.isChecked();
		if(sticky != lastSticky)
		{
			matchingBlocks.clear();
			vertices.clear();
			tracerEnds.clear();
			if(vertexBuffer != null)
			{
				vertexBuffer.close();
				vertexBuffer = null;
			}
			lastRegion = null;
			lastSticky = sticky;
		}
		if(!sticky || scanCenter == null)
			scanCenter =
				BlockPos.containing(MC.player.getX(), 0, MC.player.getZ());
		
		int modulo = MC.player.tickCount % 64;
		RegionPos region = RenderUtils.getCameraRegion();
		
		if(modulo == 0 || !region.equals(lastRegion))
		{
			if(vertexBuffer != null)
				vertexBuffer.close();
			
			vertexBuffer = EasyVertexBuffer.createAndUpload(Mode.QUADS,
				DefaultVertexFormat.POSITION_COLOR, buffer -> {
					for(int[] vertex : vertices)
						buffer.addVertex(vertex[0] - region.x(), vertex[1],
							vertex[2] - region.z()).setColor(0xFFFFFFFF);
				});
			
			lastRegion = region;
		}
		
		// reset matching blocks
		if(modulo == 0)
			matchingBlocks.clear();
		
		int cfgMin = (int)Math.min(minY.getValue(), maxY.getValue());
		int cfgMax = (int)Math.max(minY.getValue(), maxY.getValue());
		int worldMin = MC.level.getMinY();
		int worldMax = MC.level.getMaxY() - 1;
		int scanMin = Math.max(worldMin, cfgMin);
		int scanMax = Math.min(worldMax, cfgMax);
		int heightRange = Math.max(1, scanMax - scanMin + 1);
		int stepSize = Math.max(1, heightRange / 64);
		int startY = scanMax - modulo * stepSize;
		int endY = Math.max(scanMin, startY - stepSize);
		
		BlockPos playerPos = scanCenter;
		
		// search matching blocks
		loop: for(int y = startY; y > endY; y--)
			for(int x = 64; x > -64; x--)
				for(int z = 64; z > -64; z--)
				{
					if(matchingBlocks.size() >= 10000)
						break loop;
					
					BlockPos pos = new BlockPos(playerPos.getX() + x, y,
						playerPos.getZ() + z);
					
					String idFull = BlockUtils.getName(pos);
					boolean isNatural = naturalExactIds != null
						&& naturalExactIds.contains(idFull);
					if(!isNatural && naturalKeywords != null
						&& naturalKeywords.length > 0)
					{
						String localId = idFull.contains(":")
							? idFull.substring(idFull.indexOf(":") + 1)
							: idFull;
						String localSpaced = localId.replace('_', ' ');
						net.minecraft.world.level.block.Block b =
							BlockUtils.getBlock(pos);
						String transKey = b.getDescriptionId();
						String display = b.getName().getString();
						for(String term : naturalKeywords)
							if(containsNormalized(idFull, term)
								|| containsNormalized(localId, term)
								|| containsNormalized(localSpaced, term)
								|| containsNormalized(transKey, term)
								|| containsNormalized(display, term))
							{
								isNatural = true;
								break;
							}
					}
					if(isNatural)
						continue;
					
					matchingBlocks.add(pos);
				}
			
		if(modulo != 63)
			return;
		
		// update timer
		if(matchingBlocks.size() < 10000)
			messageTimer--;
		else
		{
			// show message
			if(messageTimer <= 0)
			{
				ChatUtils
					.warning("BaseFinder found \u00a7lA LOT\u00a7r of blocks.");
				ChatUtils.message(
					"To prevent lag, it will only show the first 10000 blocks.");
			}
			
			// reset timer
			messageTimer = 3;
		}
		
		// update counter
		counter = matchingBlocks.size();
		
		// calculate vertices
		vertices = BlockVertexCompiler.compile(matchingBlocks);
		// update stable tracer end points until next compile
		tracerEnds = new java.util.ArrayList<>(matchingBlocks.size());
		for(BlockPos p : matchingBlocks)
			tracerEnds.add(new net.minecraft.world.phys.Vec3(p.getX() + 0.5,
				p.getY() + 0.5, p.getZ() + 0.5));
	}
	
	private void rebuildNaturalCaches()
	{
		java.util.HashSet<String> exact = new java.util.HashSet<>();
		java.util.ArrayList<String> kw = new java.util.ArrayList<>();
		for(String s : blockNames)
		{
			net.minecraft.resources.Identifier id =
				net.minecraft.resources.Identifier.tryParse(s);
			if(id != null)
				exact.add(id.toString());
			else if(s != null && !s.isBlank())
				kw.add(s.toLowerCase(java.util.Locale.ROOT));
		}
		naturalExactIds = exact;
		naturalKeywords = kw.toArray(new String[0]);
	}
	
	private static boolean containsNormalized(String haystack, String needle)
	{
		return haystack != null
			&& haystack.toLowerCase(java.util.Locale.ROOT).contains(needle);
	}
}
