/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.WidgetTooltipHolder;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/**
 * Uses native tooltip timing and narration with screen coordinates on scaled
 * canvases.
 */
final class UiWidgetTooltip
{
	private final WidgetTooltipHolder holder = new WidgetTooltipHolder();
	private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
	
	static final class Context
	{
		final AbstractWidget widget;
		final int mouseX, mouseY;
		final ScreenRectangle rectangle;
		
		Context(AbstractWidget widget, int mouseX, int mouseY,
			ScreenRectangle rectangle)
		{
			this.widget = widget;
			this.mouseX = mouseX;
			this.mouseY = mouseY;
			this.rectangle = rectangle;
		}
	}
	
	static Context push(AbstractWidget widget, int mouseX, int mouseY,
		ScreenRectangle rectangle)
	{
		Context previous = CURRENT.get();
		CURRENT.set(new Context(widget, mouseX, mouseY, rectangle));
		return previous;
	}
	
	static void pop(Context previous)
	{
		if(previous == null)
			CURRENT.remove();
		else
			CURRENT.set(previous);
	}
	
	void set(Tooltip tooltip)
	{
		holder.set(tooltip);
	}
	
	void setDelay(java.time.Duration delay)
	{
		holder.setDelay(delay);
	}
	
	void narrate(NarrationElementOutput narration)
	{
		holder.updateNarration(narration);
	}
	
	boolean render(AbstractWidget widget, GuiGraphicsExtractor graphics,
		int mouseX, int mouseY)
	{
		Context context = CURRENT.get();
		ScreenRectangle rectangle = widget.getRectangle();
		boolean hovered = widget.isHovered();
		if(context != null && context.widget == widget)
		{
			mouseX = context.mouseX;
			mouseY = context.mouseY;
			rectangle = context.rectangle;
			// Scissors are in screen coordinates, even while widgets render in
			// panel units.
			hovered = rectangle.containsPoint(mouseX, mouseY)
				&& graphics.containsPointInScissor(mouseX, mouseY);
		}
		holder.refreshTooltipForNextRenderPass(graphics, mouseX, mouseY,
			hovered, widget.isFocused(), rectangle);
		return hovered;
	}
}
