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
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

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
import net.wurstclient.hacks.portalesp.LiquidEspBlockGroup;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkSearcher.Result;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

@SearchTags({"lavaesp", "wateresp", "lava water", "LavaWaterESP"})
public final class LavaWaterEspHack extends Hack implements UpdateListener,
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
		ChunkAreaSetting.ChunkArea.A3);
	
	// Default colors: lava orange, water blue
	private final LiquidEspBlockGroup lavaGroup =
		new LiquidEspBlockGroup(Blocks.LAVA,
			new ColorSetting("Lava color",
				"Lava will be highlighted in this color.", new Color(0xFF8C00)),
			new CheckboxSetting("Include lava", true));
	private final LiquidEspBlockGroup waterGroup = new LiquidEspBlockGroup(
		Blocks.WATER,
		new ColorSetting("Water color",
			"Water will be highlighted in this color.", new Color(0x3F76E4)),
		new CheckboxSetting("Include water", true));
	private final List<LiquidEspBlockGroup> groups =
		Arrays.asList(lavaGroup, waterGroup);
	
	// Transparency sliders per type (0-255)
	private final SliderSetting lavaAlpha =
		new SliderSetting("Lava transparency",
			"Transparency for lava (0 = fully transparent, 255 = opaque).", 64,
			0, 255, 1, ValueDisplay.INTEGER);
	private final SliderSetting waterAlpha =
		new SliderSetting("Water transparency",
			"Transparency for water (0 = fully transparent, 255 = opaque).", 64,
			0, 255, 1, ValueDisplay.INTEGER);
	
	// How many blocks to render (100 - 1000)
	private final SliderSetting renderAmount = new SliderSetting(
		"Render amount", "Maximum number of blocks to render at once.", 100,
		100, 1000, 10, ValueDisplay.INTEGER);
	
	private final BiPredicate<BlockPos, BlockState> query =
		(pos, state) -> isTargetBlock(state.getBlock());
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(query, area);
	private boolean groupsUpToDate;
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	private ChunkPos lastPlayerChunk;
	
	public LavaWaterEspHack()
	{
		super("LavaWaterESP");
		setCategory(Category.RENDER);
		addSetting(style);
		groups.stream().flatMap(LiquidEspBlockGroup::getSettings)
			.forEach(this::addSetting);
		addSetting(area);
		addSetting(stickyArea);
		// add transparency and render amount settings
		addSetting(lavaAlpha);
		addSetting(waterAlpha);
		addSetting(renderAmount);
	}
	
	private boolean isTargetBlock(Block b)
	{
		for(LiquidEspBlockGroup g : groups)
			if(g.getBlock() == b)
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
		groups.forEach(LiquidEspBlockGroup::clear);
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
		for(LiquidEspBlockGroup group : groups)
		{
			if(!group.isEnabled())
				continue;
			List<Box> boxes = group.getBoxes();
			int alpha = group == lavaGroup ? lavaAlpha.getValueI()
				: waterAlpha.getValueI();
			int quadsColor = group.getColorI(alpha);
			int linesColor = group.getColorI(alpha);
			RenderUtils.drawSolidBoxes(matrixStack, boxes, quadsColor, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, linesColor,
				false);
		}
	}
	
	private void renderTracers(MatrixStack matrixStack, float partialTicks)
	{
		for(LiquidEspBlockGroup group : groups)
		{
			if(!group.isEnabled())
				continue;
			List<Box> boxes = group.getBoxes();
			List<Vec3d> ends = boxes.stream().map(Box::getCenter).toList();
			int alpha = group == lavaGroup ? lavaAlpha.getValueI()
				: waterAlpha.getValueI();
			int color = group.getColorI(alpha);
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, color,
				false);
		}
	}
	
	private void updateGroupBoxes()
	{
		groups.forEach(LiquidEspBlockGroup::clear);
		int limit = renderAmount.getValueI();
		java.util.List<Result> matches = coordinator.getMatches()
			.sorted(Comparator.comparingDouble(
				r -> r.pos().getSquaredDistance(RotationUtils.getEyesPos())))
			.limit(limit).collect(Collectors.toList());
		matches.forEach(this::addToGroupBoxes);
		groupsUpToDate = true;
	}
	
	private void addToGroupBoxes(Result result)
	{
		for(LiquidEspBlockGroup group : groups)
			if(result.state().getBlock() == group.getBlock())
			{
				group.add(result.pos());
				break;
			}
	}
}
