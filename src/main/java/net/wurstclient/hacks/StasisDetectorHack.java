/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
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
import java.util.stream.Collectors;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.PacketInputListener;
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
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.chunk.ChunkSearcher;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

@SearchTags({"stasis chamber", "stasis detector", "pearl stasis",
	"statis chamber", "statis detector"})
public class StasisDetectorHack extends Hack implements UpdateListener,
	RenderListener, CameraTransformViewBobbingListener, PacketInputListener
{
	private static final int MIN_BUBBLE_COLUMNS = 4;
	
	private final EspStyleSetting style = new EspStyleSetting();
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.",
		ChunkAreaSetting.ChunkArea.A33);
	private final SliderSetting limit = new SliderSetting("Limit",
		"The maximum number of chambers to display.\n"
			+ "Higher values require a faster computer.",
		3, 1, 5, 1, ValueDisplay.LOGARITHMIC);
	private final ColorSetting color = new ColorSetting("Color",
		"Highlight color for stasis chambers.", new Color(0x9932CC));
	private final CheckboxSetting chatAlerts =
		new CheckboxSetting("Chat alerts",
			"Show a chat alert when a new stasis chamber is detected.", true);
	private final CheckboxSetting soundAlerts =
		new CheckboxSetting("Sound alerts",
			"Play a sound alert when a new stasis chamber is detected.", true);
	private final EnumSetting<AlertSound> alertSound = new EnumSetting<>(
		"Alert sound", "Sound used for stasis chamber alerts.",
		AlertSound.values(), AlertSound.NOTE_BLOCK_CHIME);
	private final CheckboxSetting overworld =
		new CheckboxSetting("Overworld", true);
	private final CheckboxSetting end = new CheckboxSetting("End", true);
	
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(area);
	
	private HashSet<BlockPos> matchedChambers = new HashSet<>();
	private ArrayList<AABB> renderBoxes = new ArrayList<>();
	private List<Vec3> tracerEnds;
	private int foundCount;
	private boolean notify;
	
	private final HashSet<BlockPos> alertedPositions = new HashSet<>();
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	
	public StasisDetectorHack()
	{
		super("StasisDetector");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(area);
		addSetting(limit);
		addSetting(color);
		addSetting(chatAlerts);
		addSetting(soundAlerts);
		addSetting(alertSound);
		addSetting(overworld);
		addSetting(end);
	}
	
	@Override
	protected void onEnable()
	{
		lastAreaSelection = area.getSelected();
		notify = true;
		coordinator.setQuery((pos, state) -> {
			if(MC.level == null)
				return false;
			if(!isInCorrectDimension())
				return false;
			return state.getBlock() == Blocks.SOUL_SAND
				|| state.getBlock() == Blocks.SOUL_SOIL;
		});
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(PacketInputListener.class, coordinator);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(PacketInputListener.class, coordinator);
		coordinator.reset();
		matchedChambers.clear();
		renderBoxes.clear();
		tracerEnds = null;
		foundCount = 0;
	}
	
	private boolean isInCorrectDimension()
	{
		if(MC.level == null)
			return false;
		if(MC.level.dimension() == Level.OVERWORLD && overworld.isChecked())
			return true;
		if(MC.level.dimension() == Level.END && end.isChecked())
			return true;
		return false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null || MC.player == null)
		{
			matchedChambers.clear();
			renderBoxes.clear();
			tracerEnds = null;
			foundCount = 0;
			return;
		}
		
		if(!isInCorrectDimension())
		{
			matchedChambers.clear();
			renderBoxes.clear();
			tracerEnds = null;
			foundCount = 0;
			return;
		}
		
		// Handle area changes
		ChunkAreaSetting.ChunkArea currentArea = area.getSelected();
		if(currentArea != lastAreaSelection)
		{
			lastAreaSelection = currentArea;
			coordinator.reset();
			notify = true;
		}
		
		coordinator.update();
		
		if(!coordinator.hasReadyMatches())
			return;
		
		// Collect matches from coordinator
		BlockPos eyesPos = BlockPos.containing(RotationUtils.getEyesPos());
		int limitCount = limit.getValueLog();
		ArrayList<ChunkSearcher.Result> matches = coordinator.getReadyMatches()
			.collect(Collectors.toCollection(ArrayList::new));
		
		// Filter: must have at least MIN_BUBBLE_COLUMNS bubble columns above
		HashSet<BlockPos> chambers = new HashSet<>();
		for(ChunkSearcher.Result result : matches)
		{
			BlockPos pos = result.pos();
			if(!hasBubbleColumnsAbove(pos))
				continue;
			
			chambers.add(pos);
			if(chambers.size() >= limitCount)
				break;
		}
		
		// Check for limit notification
		if(chambers.size() < limitCount)
			notify = true;
		else if(notify)
		{
			ChatUtils.warning("StasisDetector found \u00a7lA LOT\u00a7r"
				+ " of chambers! To prevent lag, it will only show the"
				+ " closest \u00a76" + limitCount + "\u00a7r results.");
			notify = false;
		}
		
		// Check for new chambers and trigger alerts
		for(BlockPos pos : chambers)
		{
			if(!matchedChambers.contains(pos))
			{
				// New chamber detected
				if(alertedPositions.add(pos.immutable()))
					triggerAlert(pos);
			}
		}
		
		matchedChambers = chambers;
		foundCount = chambers.size();
		
		// Build render boxes (cover base + bubble column)
		renderBoxes.clear();
		for(BlockPos pos : chambers)
			renderBoxes.add(
				new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1,
					pos.getY() + 1 + MIN_BUBBLE_COLUMNS, pos.getZ() + 1));
		
		// Build tracer endpoints (center of the full chamber)
		tracerEnds = chambers.stream().map(pos -> {
			double centerY = pos.getY() + 0.5 + MIN_BUBBLE_COLUMNS / 2.0;
			return new Vec3(pos.getX() + 0.5, centerY, pos.getZ() + 0.5);
		}).collect(Collectors.toList());
	}
	
	private boolean hasBubbleColumnsAbove(BlockPos basePos)
	{
		if(MC.level == null)
			return false;
		
		for(int dy = 1; dy <= MIN_BUBBLE_COLUMNS; dy++)
		{
			BlockPos above = basePos.above(dy);
			if(!(BlockUtils.getState(above)
				.getBlock() instanceof BubbleColumnBlock))
				return false;
		}
		return true;
	}
	
	private void triggerAlert(BlockPos pos)
	{
		if(chatAlerts.isChecked())
		{
			ChatUtils.message("Stasis chamber found at " + pos.getX() + ", "
				+ pos.getY() + ", " + pos.getZ() + ".");
		}
		
		if(!soundAlerts.isChecked() || MC.level == null || MC.player == null)
			return;
		
		SoundEvent sound = alertSound.getSelected().resolve();
		if(sound == null)
			return;
		
		MC.level.playLocalSound(MC.player.getX(), MC.player.getY(),
			MC.player.getZ(), sound, SoundSource.PLAYERS, 1.0F, 1.0F, false);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(renderBoxes.isEmpty())
			return;
		
		int lineColor = color.getColorI();
		int fillColor = color.getColorI(0x30);
		
		// Draw filled boxes
		RenderUtils.drawSolidBoxes(matrixStack, renderBoxes, fillColor, false);
		
		// Draw outlines
		if(style.hasBoxes())
			RenderUtils.drawOutlinedBoxes(matrixStack, renderBoxes, lineColor,
				false);
		
		// Draw tracers
		if(style.hasLines() && tracerEnds != null && !tracerEnds.isEmpty())
			RenderUtils.drawTracers("StasisChamber ESP", matrixStack,
				partialTicks, tracerEnds, lineColor, false);
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		// Coordinator handles its own packet events via the event system
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public String getRenderName()
	{
		int count = foundCount;
		if(count == 0)
			return getName();
		
		return getName() + " [" + count + "]";
	}
	
	private enum AlertSound
	{
		NOTE_BLOCK_HARP("Note Block Harp", "minecraft:block.note_block.harp"),
		NOTE_BLOCK_BASS("Note Block Bass", "minecraft:block.note_block.bass"),
		NOTE_BLOCK_BASEDRUM("Note Block Basedrum",
			"minecraft:block.note_block.basedrum"),
		NOTE_BLOCK_SNARE("Note Block Snare",
			"minecraft:block.note_block.snare"),
		NOTE_BLOCK_HAT("Note Block Hat", "minecraft:block.note_block.hat"),
		NOTE_BLOCK_GUITAR("Note Block Guitar",
			"minecraft:block.note_block.guitar"),
		NOTE_BLOCK_FLUTE("Note Block Flute",
			"minecraft:block.note_block.flute"),
		NOTE_BLOCK_BELL("Note Block Bell", "minecraft:block.note_block.bell"),
		NOTE_BLOCK_CHIME("Note Block Chime",
			"minecraft:block.note_block.chime"),
		NOTE_BLOCK_XYLOPHONE("Note Block Xylophone",
			"minecraft:block.note_block.xylophone"),
		NOTE_BLOCK_IRON_XYLOPHONE("Note Block Iron Xylophone",
			"minecraft:block.note_block.iron_xylophone"),
		NOTE_BLOCK_COW_BELL("Note Block Cow Bell",
			"minecraft:block.note_block.cow_bell"),
		NOTE_BLOCK_DIDGERIDOO("Note Block Didgeridoo",
			"minecraft:block.note_block.didgeridoo"),
		NOTE_BLOCK_BIT("Note Block Bit", "minecraft:block.note_block.bit"),
		NOTE_BLOCK_BANJO("Note Block Banjo",
			"minecraft:block.note_block.banjo"),
		NOTE_BLOCK_PLING("Note Block Pling",
			"minecraft:block.note_block.pling"),
		XP_PICKUP("XP Pickup", "minecraft:entity.experience_orb.pickup"),
		UI_BUTTON_CLICK("UI Button Click", "minecraft:ui.button.click"),
		AMETHYST_CHIME("Amethyst Chime",
			"minecraft:block.amethyst_block.chime"),
		BELL_USE("Bell Use", "minecraft:block.bell.use"),
		ITEM_PICKUP("Item Pickup", "minecraft:entity.item.pickup");
		
		private final String displayName;
		private final String id;
		
		private AlertSound(String displayName, String id)
		{
			this.displayName = displayName;
			this.id = id;
		}
		
		private SoundEvent resolve()
		{
			try
			{
				return BuiltInRegistries.SOUND_EVENT
					.getValue(Identifier.parse(id));
			}catch(Exception e)
			{
				return null;
			}
		}
		
		@Override
		public String toString()
		{
			return displayName;
		}
	}
}
