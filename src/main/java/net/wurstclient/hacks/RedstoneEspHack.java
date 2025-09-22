/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

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
			+ "Higher values require a faster computer.",
		ChunkAreaSetting.ChunkArea.A33);
	private final Color defaultColor = Color.RED;
	// Grouped categories
	private final MultiBlockEspGroup buttonsGroup = new MultiBlockEspGroup(
		blocks(Blocks.OAK_BUTTON, Blocks.SPRUCE_BUTTON, Blocks.BIRCH_BUTTON,
			Blocks.JUNGLE_BUTTON, Blocks.ACACIA_BUTTON, Blocks.DARK_OAK_BUTTON,
			Blocks.MANGROVE_BUTTON, Blocks.BAMBOO_BUTTON, Blocks.CHERRY_BUTTON,
			Blocks.CRIMSON_BUTTON, Blocks.WARPED_BUTTON, Blocks.STONE_BUTTON,
			Blocks.POLISHED_BLACKSTONE_BUTTON),
		new ColorSetting("Buttons color",
			"All button types will be highlighted in this color.",
			defaultColor),
		new CheckboxSetting("Include buttons", true));
	private final MultiBlockEspGroup platesGroup = new MultiBlockEspGroup(
		blocks(Blocks.OAK_PRESSURE_PLATE, Blocks.SPRUCE_PRESSURE_PLATE,
			Blocks.BIRCH_PRESSURE_PLATE, Blocks.JUNGLE_PRESSURE_PLATE,
			Blocks.ACACIA_PRESSURE_PLATE, Blocks.DARK_OAK_PRESSURE_PLATE,
			Blocks.MANGROVE_PRESSURE_PLATE, Blocks.BAMBOO_PRESSURE_PLATE,
			Blocks.CHERRY_PRESSURE_PLATE, Blocks.CRIMSON_PRESSURE_PLATE,
			Blocks.WARPED_PRESSURE_PLATE, Blocks.STONE_PRESSURE_PLATE,
			Blocks.POLISHED_BLACKSTONE_PRESSURE_PLATE,
			Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE,
			Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE),
		new ColorSetting("Pressure plates color",
			"All pressure plate types will be highlighted in this color.",
			defaultColor),
		new CheckboxSetting("Include pressure plates", true));
	// Single components (wrapped)
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
	
	private final java.util.List<RenderGroup> renderGroups =
		java.util.Arrays.asList(
			// grouped first
			buttonsGroup, platesGroup,
			// singles via adapters
			wrap(redstoneTorch), wrap(redstoneBlock), wrap(lever),
			wrap(tripwireHook), wrap(target), wrap(dust), wrap(repeater),
			wrap(comparator), wrap(observer), wrap(daylight), wrap(sculk),
			wrap(cSculk), wrap(piston), wrap(stickyPiston), wrap(dispenser),
			wrap(dropper), wrap(hopper), wrap(trappedChest), wrap(noteBlock),
			wrap(jukebox), wrap(bell), wrap(lectern), wrap(poweredRail),
			wrap(detectorRail), wrap(activatorRail));
	
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
		renderGroups.stream().flatMap(RenderGroup::getSettings)
			.forEach(this::addSetting);
		addSetting(area);
		addSetting(stickyArea);
	}
	
	private static Set<Block> blocks(Block... bs)
	{
		return new HashSet<>(java.util.Arrays.asList(bs));
	}
	
	private boolean isEnabledTarget(Block b)
	{
		for(RenderGroup g : renderGroups)
			if(g.isEnabled() && g.matches(b))
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
		renderGroups.forEach(RenderGroup::clear);
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
		for(RenderGroup group : renderGroups)
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
		for(RenderGroup group : renderGroups)
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
		renderGroups.forEach(RenderGroup::clear);
		coordinator.getMatches().forEach(this::addToGroupBoxes);
		groupsUpToDate = true;
	}
	
	private void addToGroupBoxes(Result result)
	{
		Block b = result.state().getBlock();
		for(RenderGroup group : renderGroups)
			if(group.matches(b))
			{
				group.add(result.pos());
				break;
			}
	}
	
	// Common interface for rendering groups
	private static interface RenderGroup
	{
		boolean isEnabled();
		
		boolean matches(Block b);
		
		void add(BlockPos pos);
		
		void clear();
		
		List<Box> getBoxes();
		
		int getColorI(int alpha);
		
		Stream<net.wurstclient.settings.Setting> getSettings();
	}
	
	// Adapter for single-block groups
	private static RenderGroup wrap(PortalEspBlockGroup g)
	{
		return new RenderGroup()
		{
			@Override
			public boolean isEnabled()
			{
				return g.isEnabled();
			}
			
			@Override
			public boolean matches(Block b)
			{
				return g.getBlock() == b;
			}
			
			@Override
			public void add(BlockPos pos)
			{
				g.add(pos);
			}
			
			@Override
			public void clear()
			{
				g.clear();
			}
			
			@Override
			public List<Box> getBoxes()
			{
				return g.getBoxes();
			}
			
			@Override
			public int getColorI(int alpha)
			{
				return g.getColorI(alpha);
			}
			
			@Override
			public Stream<net.wurstclient.settings.Setting> getSettings()
			{
				return g.getSettings();
			}
		};
	}
	
	// Group that matches any of multiple blocks
	private static final class MultiBlockEspGroup implements RenderGroup
	{
		private final ArrayList<Box> boxes = new ArrayList<>();
		private final Set<Block> blocks;
		private final ColorSetting color;
		private final CheckboxSetting enabled;
		
		private MultiBlockEspGroup(Set<Block> blocks, ColorSetting color,
			CheckboxSetting enabled)
		{
			this.blocks = new HashSet<>(blocks);
			this.color = java.util.Objects.requireNonNull(color);
			this.enabled = enabled;
		}
		
		@Override
		public boolean isEnabled()
		{
			return enabled == null || enabled.isChecked();
		}
		
		@Override
		public boolean matches(Block b)
		{
			return blocks.contains(b);
		}
		
		@Override
		public void add(BlockPos pos)
		{
			if(!isEnabled())
				return;
			if(!net.wurstclient.util.BlockUtils.canBeClicked(pos))
				return;
			Box box = net.wurstclient.util.BlockUtils.getBoundingBox(pos);
			if(box.getAverageSideLength() == 0)
				return;
			boxes.add(box);
		}
		
		@Override
		public void clear()
		{
			boxes.clear();
		}
		
		@Override
		public List<Box> getBoxes()
		{
			return java.util.Collections.unmodifiableList(boxes);
		}
		
		@Override
		public int getColorI(int alpha)
		{
			return color.getColorI(alpha);
		}
		
		@Override
		public Stream<net.wurstclient.settings.Setting> getSettings()
		{
			return Stream.of(enabled, color).filter(java.util.Objects::nonNull);
		}
	}
}
