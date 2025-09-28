/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.portalesp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import net.minecraft.block.Block;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.util.BlockUtils;

public final class LiquidEspBlockGroup
{
	protected final ArrayList<Box> boxes = new ArrayList<>();
	private final Block block;
	private final ColorSetting color;
	private final CheckboxSetting enabled;
	
	public LiquidEspBlockGroup(Block block, ColorSetting color,
		CheckboxSetting enabled)
	{
		this.block = block;
		this.color = Objects.requireNonNull(color);
		this.enabled = enabled;
	}
	
	public void add(BlockPos pos)
	{
		Box box = getBox(pos);
		if(box == null)
			return;
		boxes.add(box);
	}
	
	private Box getBox(BlockPos pos)
	{
		// fluids often have empty outline shapes; fall back to full block box
		if(BlockUtils.canBeClicked(pos))
			return BlockUtils.getBoundingBox(pos);
		
		return new Box(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1.0,
			pos.getY() + 1.0, pos.getZ() + 1.0);
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
	
	public Block getBlock()
	{
		return block;
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
