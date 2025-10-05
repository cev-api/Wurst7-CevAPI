/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.bedesp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.chunk.ChunkSearcher.Result;

public final class BedEspBlockGroup
{
	private final ArrayList<Box> boxes = new ArrayList<>();
	private final ColorSetting color;
	private final CheckboxSetting enabled = null;
	
	public BedEspBlockGroup(ColorSetting color)
	{
		this.color = Objects.requireNonNull(color);
	}
	
	public void add(Result result)
	{
		if(!isEnabled())
			return;
		
		BlockState state = result.state();
		if(!(state.getBlock() instanceof BedBlock))
			return;
		
		if(state.get(BedBlock.PART) == BedPart.FOOT)
			return;
		
		BlockPos headPos = result.pos();
		if(!BlockUtils.canBeClicked(headPos))
			return;
		
		Box box = BlockUtils.getBoundingBox(headPos);
		Direction facing = state.get(BedBlock.FACING);
		BlockPos footPos = headPos.offset(facing.getOpposite());
		
		if(BlockUtils.canBeClicked(footPos))
		{
			BlockState otherState = BlockUtils.getState(footPos);
			if(otherState.getBlock() instanceof BedBlock
				&& otherState.get(BedBlock.PART) == BedPart.FOOT)
			{
				Box footBox = BlockUtils.getBoundingBox(footPos);
				box = box.union(footBox);
			}
		}
		
		boxes.add(box);
	}
	
	public void clear()
	{
		boxes.clear();
	}
	
	public boolean isEnabled()
	{
		return enabled == null || enabled.isChecked();
	}
	
	public Stream<Setting> getSettings()
	{
		return Stream.of(enabled, color).filter(Objects::nonNull);
	}
	
	public int getColorI(int alpha)
	{
		return color.getColorI(alpha);
	}
	
	public List<Box> getBoxes()
	{
		return Collections.unmodifiableList(boxes);
	}
}
