/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.List;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.Category;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.chestesp.ChestEspBlockGroup;
import net.wurstclient.hacks.chestesp.ChestEspEntityGroup;
import net.wurstclient.hacks.chestesp.ChestEspGroup;
import net.wurstclient.hacks.chestesp.ChestEspGroupManager;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.chunk.ChunkUtils;

public class ChestEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final EspStyleSetting style = new EspStyleSetting();
	private final CheckboxSetting stickyArea = new CheckboxSetting(
		"Sticky area",
		"Off: ESP drop-off follows you as chunks change.\n"
			+ "On: Keeps results anchored (useful for pathing back).\n"
			+ "Note: ChestESP tracks loaded block entities; visibility is still limited by server view distance.",
		false);
	private final CheckboxSetting onlyAboveGround =
		new CheckboxSetting("Above ground only",
			"Only show chests/containers at or above the configured Y level.",
			false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	private final CheckboxSetting showCountInHackList = new CheckboxSetting(
		"HackList count",
		"Appends the number of detected chests/containers to this hack's entry in the HackList.",
		false);
	private final ChestEspGroupManager groups = new ChestEspGroupManager();
	private final LongOpenHashSet stickyPositions = new LongOpenHashSet();
	private int foundCount;
	
	public ChestEspHack()
	{
		super("ChestESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(stickyArea);
		groups.allGroups.stream().flatMap(ChestEspGroup::getSettings)
			.forEach(this::addSetting);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
		addSetting(showCountInHackList);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		clearBlockGroups();
		groups.entityGroups.forEach(ChestEspGroup::clear);
		foundCount = 0;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.world == null)
			return;
		
		if(!stickyArea.isChecked())
			clearBlockGroups();
		groups.entityGroups.forEach(ChestEspGroup::clear);
		
		ChunkUtils.getLoadedBlockEntities().forEach(this::handleBlockEntity);
		MC.world.getEntities().forEach(this::handleEntity);
		
		int total =
			groups.allGroups.stream().mapToInt(g -> g.getBoxes().size()).sum();
		foundCount = Math.min(total, 999);
	}
	
	private void handleBlockEntity(BlockEntity blockEntity)
	{
		if(blockEntity == null)
			return;
		
		if(onlyAboveGround.isChecked()
			&& blockEntity.getPos().getY() < aboveGroundY.getValue())
			return;
		
		if(stickyArea.isChecked())
		{
			long key = blockEntity.getPos().asLong();
			if(stickyPositions.contains(key))
				return;
			
			if(addBlockEntity(blockEntity))
				stickyPositions.add(key);
			
			return;
		}
		
		addBlockEntity(blockEntity);
	}
	
	private boolean addBlockEntity(BlockEntity blockEntity)
	{
		boolean matched = false;
		for(ChestEspBlockGroup group : groups.blockGroups)
		{
			int before = group.getBoxes().size();
			group.addIfMatches(blockEntity);
			if(group.getBoxes().size() != before)
				matched = true;
		}
		
		return matched;
	}
	
	private void handleEntity(Entity entity)
	{
		if(entity == null)
			return;
		
		if(onlyAboveGround.isChecked()
			&& entity.getY() < aboveGroundY.getValue())
			return;
		
		for(ChestEspEntityGroup group : groups.entityGroups)
			group.addIfMatches(entity);
	}
	
	private void clearBlockGroups()
	{
		groups.blockGroups.forEach(ChestEspGroup::clear);
		stickyPositions.clear();
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
		groups.entityGroups.stream().filter(ChestEspGroup::isEnabled)
			.forEach(g -> g.updateBoxes(partialTicks));
		
		if(style.hasBoxes())
			renderBoxes(matrixStack);
		
		if(style.hasLines())
			renderTracers(matrixStack, partialTicks);
	}
	
	private void renderBoxes(MatrixStack matrixStack)
	{
		for(ChestEspGroup group : groups.allGroups)
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
		for(ChestEspGroup group : groups.allGroups)
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
	
	@Override
	public String getRenderName()
	{
		if(showCountInHackList.isChecked() && foundCount > 0)
			return getName() + " [" + foundCount + "]";
		return getName();
	}
}
