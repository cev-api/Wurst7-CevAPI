/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui;

import java.util.ArrayList;
import net.minecraft.client.gui.Font;
import net.minecraft.util.Mth;
import net.wurstclient.WurstClient;

public class Window
{
	private String title;
	private int x;
	private int y;
	private int width;
	private int height;
	private boolean clampPosition = true;
	
	private boolean valid;
	private final ArrayList<Component> children = new ArrayList<>();
	
	private boolean dragging;
	private int dragOffsetX;
	private int dragOffsetY;
	
	private boolean minimized;
	private boolean minimizable = true;
	
	private boolean pinned;
	private boolean pinnable = true;
	
	private boolean closable;
	private boolean closing;
	
	private boolean invisible;
	private boolean positionClampingEnabled = true;
	
	private boolean fixedWidth;
	private int innerHeight;
	private int maxInnerHeight;
	private int scrollOffset;
	private boolean scrollingEnabled;
	
	private boolean resizable;
	private boolean resizing;
	private int resizeOffsetX;
	
	private boolean draggingScrollbar;
	private int scrollbarDragOffsetY;
	
	public Window(String title)
	{
		this.title = title;
	}
	
	public final String getTitle()
	{
		return title;
	}
	
	public final void setTitle(String title)
	{
		this.title = title;
	}
	
	/**
	 * Returns the X position of the window, adjusted to fit inside the screen.
	 */
	public final int getX()
	{
		if(!positionClampingEnabled)
			return x;
		
		int scaledWidth = WurstClient.MC.getWindow().getGuiScaledWidth();
		return Mth.clamp(x, -width + 1, scaledWidth - 1);
	}
	
	/**
	 * Returns the actual X position of the window, without any adjustments.
	 * This should only be used for saving the window's position to the config
	 * file.
	 */
	public final int getActualX()
	{
		return x;
	}
	
	public final void setX(int x)
	{
		this.x = x;
	}
	
	/**
	 * Returns the Y position of the window, adjusted to fit inside the screen.
	 */
	public final int getY()
	{
		if(!positionClampingEnabled)
			return y;
		
		int scaledHeight = WurstClient.MC.getWindow().getGuiScaledHeight();
		return Mth.clamp(y, -12, scaledHeight - 1);
	}
	
	/**
	 * Returns the actual Y position of the window, without any adjustments.
	 * This should only be used for saving the window's position to the config
	 * file.
	 */
	public final int getActualY()
	{
		return y;
	}
	
	public final void setY(int y)
	{
		this.y = y;
	}
	
	public final void setClampPosition(boolean clampPosition)
	{
		this.clampPosition = clampPosition;
	}
	
	public final int getWidth()
	{
		return width;
	}
	
	public final void setWidth(int width)
	{
		if(fixedWidth)
			return;
		
		if(this.width != width)
			invalidate();
		
		this.width = width;
	}
	
	public final int getHeight()
	{
		return height;
	}
	
	public final void setHeight(int height)
	{
		if(this.height != height)
			invalidate();
		
		this.height = height;
	}
	
	public final void pack()
	{
		int maxChildWidth = 0;
		for(Component c : children)
			if(c.getDefaultWidth() > maxChildWidth)
				maxChildWidth = c.getDefaultWidth();
		maxChildWidth += 4;
		
		Font tr = WurstClient.MC.font;
		int titleBarWidth = tr.width(title) + 4;
		if(minimizable)
			titleBarWidth += 11;
		if(pinnable)
			titleBarWidth += 11;
		if(closable)
			titleBarWidth += 11;
		
		int headerHeight = getHeaderHeight();
		int childrenHeight = headerHeight;
		for(Component c : children)
			childrenHeight += c.getHeight() + 2;
		childrenHeight += 2;
		
		if(maxInnerHeight > 0 && childrenHeight > maxInnerHeight + headerHeight)
		{
			setWidth(Math.max(maxChildWidth + 3, titleBarWidth));
			setHeight(maxInnerHeight + headerHeight);
			
		}else
		{
			setWidth(Math.max(maxChildWidth, titleBarWidth));
			setHeight(childrenHeight);
		}
		
		validate();
	}
	
	public final void validate()
	{
		if(valid)
			return;
		
		int headerHeight = getHeaderHeight();
		int offsetY =
			this instanceof net.wurstclient.clickgui.modern.ModernWindow ? 4
				: 2;
		int cWidth = width - 4;
		for(Component c : children)
		{
			c.setX(2);
			c.setY(offsetY);
			c.setWidth(cWidth);
			offsetY += c.getHeight() + 2;
		}
		
		innerHeight = offsetY;
		
		if(maxInnerHeight == 0 || innerHeight < maxInnerHeight)
			setHeight(innerHeight + headerHeight);
		else
			setHeight(maxInnerHeight + headerHeight);
		
		scrollingEnabled = innerHeight + headerHeight > height;
		if(scrollingEnabled)
			cWidth -= 8;
		
		scrollOffset = Math.min(scrollOffset, 0);
		scrollOffset =
			Math.max(scrollOffset, -innerHeight + height - getHeaderHeight());
		
		for(Component c : children)
			c.setWidth(cWidth);
		
		valid = true;
	}
	
	private int getHeaderHeight()
	{
		if(this instanceof net.wurstclient.clickgui.modern.ModernWindow)
		{
			net.wurstclient.clickgui.ClickGui gui =
				WurstClient.INSTANCE.getGuiIfInitialized();
			return gui == null ? 24 : gui.getModernHeaderHeight();
		}
		return 13;
	}
	
	public final void invalidate()
	{
		valid = false;
	}
	
	public final int countChildren()
	{
		return children.size();
	}
	
	public final Component getChild(int index)
	{
		return children.get(index);
	}
	
	public final void add(Component component)
	{
		children.add(component);
		component.setParent(this);
		invalidate();
	}
	
	public final void clearChildren()
	{
		for(Component child : children)
			child.setParent(null);
		children.clear();
		invalidate();
	}
	
	public final void remove(int index)
	{
		children.get(index).setParent(null);
		children.remove(index);
		invalidate();
	}
	
	public final void remove(Component component)
	{
		children.remove(component);
		component.setParent(null);
		invalidate();
	}
	
	public final boolean isDragging()
	{
		return dragging;
	}
	
	public final void startDragging(int mouseX, int mouseY)
	{
		dragging = true;
		dragOffsetX = getX() - mouseX;
		dragOffsetY = getY() - mouseY;
	}
	
	public final void dragTo(int mouseX, int mouseY)
	{
		x = mouseX + dragOffsetX;
		y = mouseY + dragOffsetY;
	}
	
	public final void stopDragging()
	{
		dragging = false;
		dragOffsetX = 0;
		dragOffsetY = 0;
	}
	
	public final boolean isMinimized()
	{
		return minimized;
	}
	
	public final void setMinimized(boolean minimized)
	{
		this.minimized = minimized;
	}
	
	public final boolean isMinimizable()
	{
		return minimizable;
	}
	
	public final void setMinimizable(boolean minimizable)
	{
		this.minimizable = minimizable;
	}
	
	public final boolean isPinned()
	{
		return pinned;
	}
	
	public final void setPinned(boolean pinned)
	{
		this.pinned = pinned;
	}
	
	public final boolean isPinnable()
	{
		return pinnable;
	}
	
	public final void setPinnable(boolean pinnable)
	{
		this.pinnable = pinnable;
	}
	
	public final boolean isClosable()
	{
		return closable;
	}
	
	public final void setClosable(boolean closable)
	{
		this.closable = closable;
	}
	
	public final boolean isClosing()
	{
		return closing;
	}
	
	public final void close()
	{
		closing = true;
	}
	
	/** Reuses a previously closed Modern window without losing layout state. */
	public final void reopen()
	{
		closing = false;
	}
	
	public final boolean isInvisible()
	{
		return invisible;
	}
	
	public final void setInvisible(boolean invisible)
	{
		this.invisible = invisible;
	}
	
	public final boolean isPositionClampingEnabled()
	{
		return positionClampingEnabled;
	}
	
	public final void setPositionClampingEnabled(
		boolean positionClampingEnabled)
	{
		this.positionClampingEnabled = positionClampingEnabled;
	}
	
	public final boolean isFixedWidth()
	{
		return fixedWidth;
	}
	
	public final void setFixedWidth(boolean fixedWidth)
	{
		this.fixedWidth = fixedWidth;
	}
	
	public final int getInnerHeight()
	{
		return innerHeight;
	}
	
	public final void setMaxInnerHeight(int maxInnerHeight)
	{
		if(maxInnerHeight < 0)
			maxInnerHeight = 0;
		
		if(this.maxInnerHeight != maxInnerHeight)
			invalidate();
		
		this.maxInnerHeight = maxInnerHeight;
	}
	
	public final void setMaxHeight(int maxHeight)
	{
		setMaxInnerHeight(maxHeight - getHeaderHeight());
	}
	
	public final int getScrollOffset()
	{
		return scrollOffset;
	}
	
	public final void setScrollOffset(int scrollOffset)
	{
		this.scrollOffset = scrollOffset;
	}
	
	public final boolean isScrollingEnabled()
	{
		return scrollingEnabled;
	}
	
	public final boolean isResizable()
	{
		return resizable;
	}
	
	public final void setResizable(boolean resizable)
	{
		this.resizable = resizable;
	}
	
	public final boolean isResizing()
	{
		return resizing;
	}
	
	public final void startResizing(int mouseX)
	{
		if(!resizable)
			return;
		resizing = true;
		resizeOffsetX = width - mouseX;
	}
	
	public final void resizeTo(int mouseX)
	{
		if(!resizing)
			return;
		setWidth(Math.max(100, mouseX + resizeOffsetX));
	}
	
	public final void stopResizing()
	{
		resizing = false;
		resizeOffsetX = 0;
	}
	
	public final int getScrollbarTrackTop()
	{
		return 2;
	}
	
	public final int getScrollbarTrackBottom()
	{
		return Math.max(2, height - getHeaderHeight() - 2);
	}
	
	public final int getScrollbarThumbHeight()
	{
		int outerHeight = Math.max(1, height - getHeaderHeight());
		int trackHeight =
			Math.max(1, getScrollbarTrackBottom() - getScrollbarTrackTop());
		int minimum =
			this instanceof net.wurstclient.clickgui.modern.ModernWindow ? 16
				: 8;
		int proportional = (int)Math.round(
			trackHeight * outerHeight / (double)Math.max(1, innerHeight));
		return Mth.clamp(Math.max(minimum, proportional), 1, trackHeight);
	}
	
	public final int getScrollbarThumbY()
	{
		int outerHeight = Math.max(1, height - getHeaderHeight());
		int maxScroll = Math.max(0, innerHeight - outerHeight);
		int travel = Math.max(0, getScrollbarTrackBottom()
			- getScrollbarTrackTop() - getScrollbarThumbHeight());
		if(maxScroll == 0 || travel == 0)
			return getScrollbarTrackTop();
		return getScrollbarTrackTop()
			+ (int)Math.round(travel * (-scrollOffset / (double)maxScroll));
	}
	
	public final boolean isDraggingScrollbar()
	{
		return draggingScrollbar;
	}
	
	public final void centerScrollbarOn(int mouseY)
	{
		int outerHeight = Math.max(1, height - getHeaderHeight());
		int maxScroll = Math.max(0, innerHeight - outerHeight);
		int travel = Math.max(0, getScrollbarTrackBottom()
			- getScrollbarTrackTop() - getScrollbarThumbHeight());
		if(maxScroll == 0 || travel == 0)
		{
			scrollOffset = 0;
			return;
		}
		int localMouseY = mouseY - getY() - getHeaderHeight();
		int thumbY = Mth.clamp(localMouseY - getScrollbarThumbHeight() / 2,
			getScrollbarTrackTop(), getScrollbarTrackTop() + travel);
		scrollOffset = -(int)Math.round(
			(thumbY - getScrollbarTrackTop()) / (double)travel * maxScroll);
	}
	
	public final void startDraggingScrollbar(int mouseY)
	{
		draggingScrollbar = true;
		int localMouseY = mouseY - getY() - getHeaderHeight();
		scrollbarDragOffsetY = getScrollbarThumbY() - localMouseY;
	}
	
	public final void dragScrollbarTo(int mouseY)
	{
		int outerHeight = Math.max(1, height - getHeaderHeight());
		int maxScroll = Math.max(0, innerHeight - outerHeight);
		int travel = Math.max(0, getScrollbarTrackBottom()
			- getScrollbarTrackTop() - getScrollbarThumbHeight());
		if(maxScroll == 0 || travel == 0)
		{
			scrollOffset = 0;
			return;
		}
		int localMouseY = mouseY - getY() - getHeaderHeight();
		int thumbY = Mth.clamp(localMouseY + scrollbarDragOffsetY,
			getScrollbarTrackTop(), getScrollbarTrackTop() + travel);
		scrollOffset = -(int)Math.round(
			(thumbY - getScrollbarTrackTop()) / (double)travel * maxScroll);
	}
	
	public final void stopDraggingScrollbar()
	{
		draggingScrollbar = false;
		scrollbarDragOffsetY = 0;
	}
}
