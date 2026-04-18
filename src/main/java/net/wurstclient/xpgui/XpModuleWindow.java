/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.xpgui;

import java.util.HashMap;
import java.util.Map;

import net.wurstclient.hack.Hack;

public final class XpModuleWindow
{
	private final Hack hack;
	private int x;
	private int y;
	private int width;
	private int height;
	private boolean minimized;
	private int scrollOffset;
	private final Map<String, Boolean> expandedGroups = new HashMap<>();
	
	public XpModuleWindow(Hack hack, int x, int y, int width, int height)
	{
		this.hack = hack;
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
	}
	
	public Hack getHack()
	{
		return hack;
	}
	
	public String getTitle()
	{
		return hack.getName();
	}
	
	public int getX()
	{
		return x;
	}
	
	public void setX(int x)
	{
		this.x = x;
	}
	
	public int getY()
	{
		return y;
	}
	
	public void setY(int y)
	{
		this.y = y;
	}
	
	public int getWidth()
	{
		return width;
	}
	
	public void setWidth(int width)
	{
		this.width = width;
	}
	
	public int getHeight()
	{
		return height;
	}
	
	public void setHeight(int height)
	{
		this.height = height;
	}
	
	public boolean isMinimized()
	{
		return minimized;
	}
	
	public void setMinimized(boolean minimized)
	{
		this.minimized = minimized;
	}
	
	public int getScrollOffset()
	{
		return scrollOffset;
	}
	
	public void setScrollOffset(int scrollOffset)
	{
		this.scrollOffset = scrollOffset;
	}
	
	public boolean isGroupExpanded(String key)
	{
		return expandedGroups.getOrDefault(key, false);
	}
	
	public void toggleGroup(String key)
	{
		expandedGroups.put(key, !isGroupExpanded(key));
	}
	
	public void setGroupExpanded(String key, boolean expanded)
	{
		expandedGroups.put(key, expanded);
	}
}
