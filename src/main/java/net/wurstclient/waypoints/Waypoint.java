/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.waypoints;

import java.util.UUID;

import net.minecraft.util.math.BlockPos;

public final class Waypoint
{
	public enum ActionWhenNear
	{
		DISABLED,
		HIDE,
		DELETE
	}
	
	private final UUID uuid;
	private final long createdAt;
	
	private String name;
	private String icon;
	private int color; // ARGB
	private boolean visible;
	private int maxVisible;
	private double scale;
	private BlockPos pos;
	private WaypointDimension dimension;
	private boolean opposite;
	// Render lines (tracer/box) toggle
	private boolean lines;
	private ActionWhenNear actionWhenNear;
	private int actionWhenNearDistance;
	
	public Waypoint(UUID uuid, long createdAt)
	{
		this.uuid = uuid;
		this.createdAt = createdAt;
		this.name = "Home";
		this.icon = "star";
		this.color = 0xFFFFFFFF;
		this.visible = true;
		this.maxVisible = 5000;
		this.scale = 1.5;
		this.pos = BlockPos.ORIGIN;
		this.dimension = WaypointDimension.OVERWORLD;
		this.opposite = false;
		this.lines = true;
		this.actionWhenNear = ActionWhenNear.DISABLED;
		this.actionWhenNearDistance = 8;
	}
	
	public UUID getUuid()
	{
		return uuid;
	}
	
	public long getCreatedAt()
	{
		return createdAt;
	}
	
	public String getName()
	{
		return name;
	}
	
	public void setName(String name)
	{
		this.name = name;
	}
	
	public String getIcon()
	{
		return icon;
	}
	
	public void setIcon(String icon)
	{
		this.icon = icon;
	}
	
	public int getColor()
	{
		return color;
	}
	
	public void setColor(int color)
	{
		this.color = color;
	}
	
	public boolean isVisible()
	{
		return visible;
	}
	
	public void setVisible(boolean visible)
	{
		this.visible = visible;
	}
	
	public int getMaxVisible()
	{
		return maxVisible;
	}
	
	public void setMaxVisible(int maxVisible)
	{
		this.maxVisible = maxVisible;
	}
	
	public double getScale()
	{
		return scale;
	}
	
	public void setScale(double scale)
	{
		this.scale = scale;
	}
	
	public BlockPos getPos()
	{
		return pos;
	}
	
	public void setPos(BlockPos pos)
	{
		this.pos = pos;
	}
	
	public WaypointDimension getDimension()
	{
		return dimension;
	}
	
	public void setDimension(WaypointDimension dimension)
	{
		this.dimension = dimension;
	}
	
	public boolean isOpposite()
	{
		return opposite;
	}
	
	public void setOpposite(boolean opposite)
	{
		this.opposite = opposite;
	}
	
	public boolean isLines()
	{
		return lines;
	}
	
	public void setLines(boolean lines)
	{
		this.lines = lines;
	}
	
	public ActionWhenNear getActionWhenNear()
	{
		return actionWhenNear;
	}
	
	public void setActionWhenNear(ActionWhenNear actionWhenNear)
	{
		this.actionWhenNear = actionWhenNear;
	}
	
	public int getActionWhenNearDistance()
	{
		return actionWhenNearDistance;
	}
	
	public void setActionWhenNearDistance(int d)
	{
		this.actionWhenNearDistance = d;
	}
}
