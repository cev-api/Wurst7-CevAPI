/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiPredicate;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.portalesp.PortalEspBlockGroup;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkSearcher.Result;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

@SearchTags({"redstone esp", "RedstoneESP"})
public final class RedstoneEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	private final CheckboxSetting stickyArea =
		new CheckboxSetting("Sticky area",
			"Off: Re-centers every chunk to match ESP drop-off.\n"
				+ "On: Keeps results anchored so you can path back to them.",
			false);
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.");
	private final Color defaultColor = Color.RED;
	// Per-block groups (default enabled)
	private final PortalEspBlockGroup redstoneTorch =
		new PortalEspBlockGroup(Blocks.REDSTONE_TORCH,
			new ColorSetting("Redstone torch color",
				"Redstone torches will be highlighted in this color.",
				defaultColor),
			new CheckboxSetting("Include redstone torches", true));
	private final PortalEspBlockGroup redstoneBlock = new PortalEspBlockGroup(
		Blocks.REDSTONE_BLOCK,
		new ColorSetting("Redstone block color",
			"Redstone blocks will be highlighted in this color.", defaultColor),
		new CheckboxSetting("Include redstone blocks", true));
	private final PortalEspBlockGroup lever =
		new PortalEspBlockGroup(Blocks.LEVER,
			new ColorSetting("Lever color",
				"Levers will be highlighted in this color.", defaultColor),
			new CheckboxSetting("Include levers", true));
	// Buttons
	private final PortalEspBlockGroup[] buttons = new PortalEspBlockGroup[]{
		new PortalEspBlockGroup(
			Blocks.OAK_BUTTON,
			new ColorSetting("Oak button color", "", defaultColor),
			new CheckboxSetting("Include oak buttons", true)),
		new PortalEspBlockGroup(Blocks.SPRUCE_BUTTON,
			new ColorSetting("Spruce button color", "", defaultColor),
			new CheckboxSetting("Include spruce buttons", true)),
		new PortalEspBlockGroup(Blocks.BIRCH_BUTTON,
			new ColorSetting("Birch button color", "", defaultColor),
			new CheckboxSetting("Include birch buttons", true)),
		new PortalEspBlockGroup(Blocks.JUNGLE_BUTTON,
			new ColorSetting("Jungle button color", "", defaultColor),
			new CheckboxSetting("Include jungle buttons", true)),
		new PortalEspBlockGroup(Blocks.ACACIA_BUTTON,
			new ColorSetting("Acacia button color", "", defaultColor),
			new CheckboxSetting("Include acacia buttons", true)),
		new PortalEspBlockGroup(Blocks.DARK_OAK_BUTTON,
			new ColorSetting("Dark oak button color", "", defaultColor),
			new CheckboxSetting("Include dark oak buttons", true)),
		new PortalEspBlockGroup(Blocks.MANGROVE_BUTTON,
			new ColorSetting("Mangrove button color", "", defaultColor),
			new CheckboxSetting("Include mangrove buttons", true)),
		new PortalEspBlockGroup(Blocks.BAMBOO_BUTTON,
			new ColorSetting("Bamboo button color", "", defaultColor),
			new CheckboxSetting("Include bamboo buttons", true)),
		new PortalEspBlockGroup(Blocks.CHERRY_BUTTON,
			new ColorSetting("Cherry button color", "", defaultColor),
			new CheckboxSetting("Include cherry buttons", true)),
		new PortalEspBlockGroup(Blocks.CRIMSON_BUTTON,
			new ColorSetting("Crimson button color", "", defaultColor),
			new CheckboxSetting("Include crimson buttons", true)),
		new PortalEspBlockGroup(Blocks.WARPED_BUTTON,
			new ColorSetting("Warped button color", "", defaultColor),
			new CheckboxSetting("Include warped buttons", true)),
		new PortalEspBlockGroup(Blocks.STONE_BUTTON,
			new ColorSetting("Stone button color", "", defaultColor),
			new CheckboxSetting("Include stone buttons", true)),
		new PortalEspBlockGroup(Blocks.POLISHED_BLACKSTONE_BUTTON,
			new ColorSetting("Polished blackstone button color", "",
				defaultColor),
			new CheckboxSetting("Include polished blackstone buttons", true))};
	// Plates
	private final PortalEspBlockGroup[] plates = new PortalEspBlockGroup[]{
		new PortalEspBlockGroup(Blocks.OAK_PRESSURE_PLATE,
			new ColorSetting("Oak pressure plate color", "", defaultColor),
			new CheckboxSetting("Include oak plates", true)),
		new PortalEspBlockGroup(Blocks.SPRUCE_PRESSURE_PLATE,
			new ColorSetting("Spruce pressure plate color", "", defaultColor),
			new CheckboxSetting("Include spruce plates", true)),
		new PortalEspBlockGroup(Blocks.BIRCH_PRESSURE_PLATE,
			new ColorSetting("Birch pressure plate color", "", defaultColor),
			new CheckboxSetting("Include birch plates", true)),
		new PortalEspBlockGroup(Blocks.JUNGLE_PRESSURE_PLATE,
			new ColorSetting("Jungle pressure plate color", "", defaultColor),
			new CheckboxSetting("Include jungle plates", true)),
		new PortalEspBlockGroup(Blocks.ACACIA_PRESSURE_PLATE,
			new ColorSetting("Acacia pressure plate color", "", defaultColor),
			new CheckboxSetting("Include acacia plates", true)),
		new PortalEspBlockGroup(Blocks.DARK_OAK_PRESSURE_PLATE,
			new ColorSetting("Dark oak pressure plate color", "", defaultColor),
			new CheckboxSetting("Include dark oak plates", true)),
		new PortalEspBlockGroup(Blocks.MANGROVE_PRESSURE_PLATE,
			new ColorSetting("Mangrove pressure plate color", "", defaultColor),
			new CheckboxSetting("Include mangrove plates", true)),
		new PortalEspBlockGroup(Blocks.BAMBOO_PRESSURE_PLATE,
			new ColorSetting("Bamboo pressure plate color", "", defaultColor),
			new CheckboxSetting("Include bamboo plates", true)),
		new PortalEspBlockGroup(Blocks.CHERRY_PRESSURE_PLATE,
			new ColorSetting("Cherry pressure plate color", "", defaultColor),
			new CheckboxSetting("Include cherry plates", true)),
		new PortalEspBlockGroup(Blocks.CRIMSON_PRESSURE_PLATE,
			new ColorSetting("Crimson pressure plate color", "", defaultColor),
			new CheckboxSetting("Include crimson plates", true)),
		new PortalEspBlockGroup(Blocks.WARPED_PRESSURE_PLATE,
			new ColorSetting("Warped pressure plate color", "", defaultColor),
			new CheckboxSetting("Include warped plates", true)),
		new PortalEspBlockGroup(Blocks.STONE_PRESSURE_PLATE,
			new ColorSetting("Stone pressure plate color", "", defaultColor),
			new CheckboxSetting("Include stone plates", true)),
		new PortalEspBlockGroup(Blocks.POLISHED_BLACKSTONE_PRESSURE_PLATE,
			new ColorSetting("Polished blackstone pressure plate color", "",
				defaultColor),
			new CheckboxSetting("Include polished blackstone plates", true)),
		new PortalEspBlockGroup(Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE,
			new ColorSetting("Light weighted plate color", "", defaultColor),
			new CheckboxSetting("Include light weighted plates", true)),
		new PortalEspBlockGroup(Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE,
			new ColorSetting("Heavy weighted plate color", "", defaultColor),
			new CheckboxSetting("Include heavy weighted plates", true))};
	// Remaining components
	private final PortalEspBlockGroup tripwireHook =
		new PortalEspBlockGroup(Blocks.TRIPWIRE_HOOK,
			new ColorSetting("Tripwire hook color", "", defaultColor),
			new CheckboxSetting("Include tripwire hooks", true));
	private final PortalEspBlockGroup target = new PortalEspBlockGroup(
		Blocks.TARGET, new ColorSetting("Target block color", "", defaultColor),
		new CheckboxSetting("Include target blocks", true));
	private final PortalEspBlockGroup dust =
		new PortalEspBlockGroup(Blocks.REDSTONE_WIRE,
			new ColorSetting("Redstone dust color", "", defaultColor),
			new CheckboxSetting("Include redstone dust", true));
	private final PortalEspBlockGroup repeater = new PortalEspBlockGroup(
		Blocks.REPEATER, new ColorSetting("Repeater color", "", defaultColor),
		new CheckboxSetting("Include repeaters", true));
	private final PortalEspBlockGroup comparator =
		new PortalEspBlockGroup(Blocks.COMPARATOR,
			new ColorSetting("Comparator color", "", defaultColor),
			new CheckboxSetting("Include comparators", true));
	private final PortalEspBlockGroup observer = new PortalEspBlockGroup(
		Blocks.OBSERVER, new ColorSetting("Observer color", "", defaultColor),
		new CheckboxSetting("Include observers", true));
	private final PortalEspBlockGroup daylight =
		new PortalEspBlockGroup(Blocks.DAYLIGHT_DETECTOR,
			new ColorSetting("Daylight detector color", "", defaultColor),
			new CheckboxSetting("Include daylight detectors", true));
	private final PortalEspBlockGroup sculk =
		new PortalEspBlockGroup(Blocks.SCULK_SENSOR,
			new ColorSetting("Sculk sensor color", "", defaultColor),
			new CheckboxSetting("Include sculk sensors", true));
	private final PortalEspBlockGroup cSculk =
		new PortalEspBlockGroup(Blocks.CALIBRATED_SCULK_SENSOR,
			new ColorSetting("Calibrated sculk sensor color", "", defaultColor),
			new CheckboxSetting("Include calibrated sculk sensors", true));
	private final PortalEspBlockGroup piston = new PortalEspBlockGroup(
		Blocks.PISTON, new ColorSetting("Piston color", "", defaultColor),
		new CheckboxSetting("Include pistons", true));
	private final PortalEspBlockGroup stickyPiston =
		new PortalEspBlockGroup(Blocks.STICKY_PISTON,
			new ColorSetting("Sticky piston color", "", defaultColor),
			new CheckboxSetting("Include sticky pistons", true));
	private final PortalEspBlockGroup dispenser = new PortalEspBlockGroup(
		Blocks.DISPENSER, new ColorSetting("Dispenser color", "", defaultColor),
		new CheckboxSetting("Include dispensers", true));
	private final PortalEspBlockGroup dropper = new PortalEspBlockGroup(
		Blocks.DROPPER, new ColorSetting("Dropper color", "", defaultColor),
		new CheckboxSetting("Include droppers", true));
	private final PortalEspBlockGroup hopper = new PortalEspBlockGroup(
		Blocks.HOPPER, new ColorSetting("Hopper color", "", defaultColor),
		new CheckboxSetting("Include hoppers", true));
	private final PortalEspBlockGroup trappedChest =
		new PortalEspBlockGroup(Blocks.TRAPPED_CHEST,
			new ColorSetting("Trapped chest color", "", defaultColor),
			new CheckboxSetting("Include trapped chests", true));
	private final PortalEspBlockGroup noteBlock =
		new PortalEspBlockGroup(Blocks.NOTE_BLOCK,
			new ColorSetting("Note block color", "", defaultColor),
			new CheckboxSetting("Include note blocks", true));
	private final PortalEspBlockGroup jukebox = new PortalEspBlockGroup(
		Blocks.JUKEBOX, new ColorSetting("Jukebox color", "", defaultColor),
		new CheckboxSetting("Include jukeboxes", true));
	private final PortalEspBlockGroup bell = new PortalEspBlockGroup(
		Blocks.BELL, new ColorSetting("Bell color", "", defaultColor),
		new CheckboxSetting("Include bells", true));
	private final PortalEspBlockGroup lectern =
		new PortalEspBlockGroup(Blocks.LECTERN,
			new ColorSetting("Lectern (redstone) color", "", defaultColor),
			new CheckboxSetting("Include lecterns (redstone)", true));
	private final PortalEspBlockGroup poweredRail =
		new PortalEspBlockGroup(Blocks.POWERED_RAIL,
			new ColorSetting("Powered rail color", "", defaultColor),
			new CheckboxSetting("Include powered rails", true));
	private final PortalEspBlockGroup detectorRail =
		new PortalEspBlockGroup(Blocks.DETECTOR_RAIL,
			new ColorSetting("Detector rail color", "", defaultColor),
			new CheckboxSetting("Include detector rails", true));
	private final PortalEspBlockGroup activatorRail =
		new PortalEspBlockGroup(Blocks.ACTIVATOR_RAIL,
			new ColorSetting("Activator rail color", "", defaultColor),
			new CheckboxSetting("Include activator rails", true));
	private final List<PortalEspBlockGroup> allGroups =
		Arrays.asList(redstoneTorch, redstoneBlock, lever,
			// buttons
			buttons[0], buttons[1], buttons[2], buttons[3], buttons[4],
			buttons[5], buttons[6], buttons[7], buttons[8], buttons[9],
			buttons[10], buttons[11],
			// plates
			plates[0], plates[1], plates[2], plates[3], plates[4], plates[5],
			plates[6], plates[7], plates[8], plates[9], plates[10], plates[11],
			plates[12], plates[13], plates[14],
			// rest
			tripwireHook, target, dust, repeater, comparator, observer,
			daylight, sculk, cSculk, piston, stickyPiston, dispenser, dropper,
			hopper, trappedChest, noteBlock, jukebox, bell, lectern,
			poweredRail, detectorRail, activatorRail);
	private final BiPredicate<BlockPos, BlockState> query =
		(pos, state) -> isEnabledTarget(state.getBlock());
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(query, area);
	private boolean groupsUpToDate;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	private ChunkPos lastPlayerChunk;
	
	public RedstoneEspHack()
	{
		super("RedstoneESP");
		setCategory(Category.RENDER);
		addSetting(style);
		allGroups.stream().flatMap(PortalEspBlockGroup::getSettings)
			.forEach(this::addSetting);
		addSetting(area);
		addSetting(stickyArea);
	}
	
	private boolean isEnabledTarget(Block b)
	{
		for(PortalEspBlockGroup g : allGroups)
			if(g.getBlock() == b && g.isEnabled())
				return true;
		return false;
	}
	
	@Override
	protected void onEnable()
	{
		groupsUpToDate = false;
		lastAreaSelection = area.getSelected();
		lastPlayerChunk = new ChunkPos(MC.player.getBlockPos());
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
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(net.wurstclient.events.PacketInputListener.class,
			coordinator);
		coordinator.reset();
		allGroups.forEach(PortalEspBlockGroup::clear);
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
		ChunkPos currentChunk = new ChunkPos(MC.player.getBlockPos());
		if(!stickyArea.isChecked() && !currentChunk.equals(lastPlayerChunk))
		{
			lastPlayerChunk = currentChunk;
			coordinator.reset();
			groupsUpToDate = false;
		}
		boolean searchersChanged = coordinator.update();
		if(searchersChanged)
			groupsUpToDate = false;
		if(!groupsUpToDate && coordinator.isDone())
			updateGroupBoxes();
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.getSelected().hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		if(style.getSelected().hasBoxes())
			renderBoxes(matrixStack);
		if(style.getSelected().hasLines())
			renderTracers(matrixStack, partialTicks);
	}
	
	private void renderBoxes(MatrixStack matrixStack)
	{
		for(PortalEspBlockGroup group : allGroups)
		{
			if(!group.isEnabled())
				continue;
			List<Box> boxes = group.getBoxes();
			int quadsColor = group.getColorI(0x40);
			int linesColor = group.getColorI(0x80);
			RenderUtils.drawSolidBoxes(matrixStack, boxes, quadsColor, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, linesColor,
				false);
		}
	}
	
	private void renderTracers(MatrixStack matrixStack, float partialTicks)
	{
		for(PortalEspBlockGroup group : allGroups)
		{
			if(!group.isEnabled())
				continue;
			List<Box> boxes = group.getBoxes();
			List<Vec3d> ends = boxes.stream().map(Box::getCenter).toList();
			int color = group.getColorI(0x80);
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, color,
				false);
		}
	}
	
	private void updateGroupBoxes()
	{
		allGroups.forEach(PortalEspBlockGroup::clear);
		coordinator.getMatches().forEach(this::addToGroupBoxes);
		groupsUpToDate = true;
	}
	
	private void addToGroupBoxes(Result result)
	{
		for(PortalEspBlockGroup group : allGroups)
			if(result.state().getBlock() == group.getBlock())
			{
				group.add(result.pos());
				break;
			}
	}
}
