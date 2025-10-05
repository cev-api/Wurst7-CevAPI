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

import net.minecraft.block.BlockState;
import net.minecraft.block.BedBlock;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
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
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkSearcher.Result;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

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
	
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.");
	
	private final BiPredicate<BlockPos, BlockState> query =
		(pos, state) -> state.getBlock() instanceof BedBlock;
	
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(query, area);
	
	private boolean groupsUpToDate;
	private ChunkPos lastPlayerChunk;
	
	public BedEspHack()
	{
		super("BedESP");
		setCategory(Category.RENDER);
		addSetting(style);
		groups.stream().flatMap(BedEspBlockGroup::getSettings)
			.forEach(this::addSetting);
		addSetting(area);
		addSetting(stickyArea);
	}
	
	@Override
	protected void onEnable()
	{
		groupsUpToDate = false;
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, coordinator);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
		lastPlayerChunk = new ChunkPos(MC.player.getBlockPos());
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, coordinator);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		
		coordinator.reset();
		groups.forEach(BedEspBlockGroup::clear);
	}
	
	@Override
	public void onUpdate()
	{
		boolean searchersChanged = coordinator.update();
		if(searchersChanged)
			groupsUpToDate = false;
		// Recenter per chunk when sticky is off
		ChunkPos currentChunk = new ChunkPos(MC.player.getBlockPos());
		if(!stickyArea.isChecked() && !currentChunk.equals(lastPlayerChunk))
		{
			lastPlayerChunk = currentChunk;
			coordinator.reset();
			groupsUpToDate = false;
		}
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
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		if(style.hasBoxes())
			renderBoxes(matrixStack);
		
		if(style.hasLines())
			renderTracers(matrixStack, partialTicks);
	}
	
	private void renderBoxes(MatrixStack matrixStack)
	{
		for(BedEspBlockGroup group : groups)
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
		for(BedEspBlockGroup group : groups)
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
		groups.forEach(BedEspBlockGroup::clear);
		coordinator.getMatches().forEach(this::addToGroupBoxes);
		groupsUpToDate = true;
	}
	
	private void addToGroupBoxes(Result result)
	{
		for(BedEspBlockGroup group : groups)
		{
			group.add(result);
			break;
		}
	}
}
