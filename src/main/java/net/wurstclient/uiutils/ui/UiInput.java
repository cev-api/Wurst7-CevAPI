/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * Text field with the modern frame. It keeps every vanilla editing behaviour
 * and
 * only replaces the background, so scaled canvases render its text like any
 * other label.
 */
public class UiInput extends EditBox implements UiScalable
{
	private final UiWidgetTooltip tooltips = new UiWidgetTooltip();
	/** Horizontal and vertical inset vanilla uses for a bordered field. */
	private static final int INNER_X = 4;
	
	private boolean focusedFrame;
	private boolean renderingText;
	private float uiScale = 1F;
	private int accent = UiTheme.ACCENT;
	
	public UiInput(Font font, int width, String value, Component hint)
	{
		super(font, 0, 0, width, UiTheme.ROW_HEIGHT, hint);
		setBordered(false);
		setTextColor(UiTheme.textColor());
		setTextColorUneditable(UiTheme.mutedTextColor());
		setTextShadow(false);
		// EditBox defaults to 32 characters. Callers often set their own limit
		// only
		// after construction; applying the initial value first silently
		// truncated it.
		setMaxLength(32767);
		setValue(value == null ? "" : value);
	}
	
	public UiInput accent(int argb)
	{
		this.accent = argb;
		return this;
	}
	
	public UiInput uiScale(float value)
	{
		float next = value <= 0F ? 1F : value;
		if(next != this.uiScale)
		{
			this.uiScale = next;
			refreshVisibleText();
		}
		return this;
	}
	
	@Override
	public void applyUiScale(float value)
	{
		uiScale(value);
	}
	
	@Override
	public int getInnerWidth()
	{
		if(renderingText)
			return super.getInnerWidth();
		return Math.max(4,
			Math.round(getWidth() / (uiScale > 0F ? uiScale : 1F))
				- INNER_X * 2);
	}
	
	private MouseButtonEvent textEvent(MouseButtonEvent event)
	{
		return new MouseButtonEvent(
			getX() + (event.x() - getX()) / uiScale - INNER_X, event.y(),
			event.buttonInfo());
	}
	
	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick)
	{
		super.onClick(textEvent(event), doubleClick);
	}
	
	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy)
	{
		super.onDrag(textEvent(event), dx / uiScale, dy / uiScale);
	}
	
	/**
	 * Recompute the visible text window after the layout assigns its real
	 * width.
	 */
	public void refreshVisibleText()
	{
		int cursor = getCursorPosition();
		setCursorPosition(cursor);
	}
	
	@Override
	public void setFocused(boolean focused)
	{
		super.setFocused(focused);
		focusedFrame = focused;
	}
	
	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor graphics,
		int mouseX, int mouseY, float partialTicks)
	{
		isHovered = tooltips.render(this, graphics, mouseX, mouseY);
		setTextColor(UiTheme.textColor());
		setTextColorUneditable(UiTheme.mutedTextColor());
		int savedX = getX();
		int savedY = getY();
		int savedWidth = getWidth();
		int savedHeight = getHeight();
		boolean scaled =
			UiTheme.pushWidgetScale(graphics, savedX, savedY, uiScale);
		int x = scaled ? 0 : savedX;
		int y = scaled ? 0 : savedY;
		int w = UiTheme.localSize(savedWidth, uiScale, scaled);
		int h = UiTheme.localSize(savedHeight, uiScale, scaled);
		boolean hot = focusedFrame || isHoveredOrFocused();
		
		graphics.fill(x, y, x + w, y + h, UiTheme.inputColor());
		UiTheme.border(graphics, x, y, w, h,
			hot ? UiTheme.mix(UiTheme.borderColor(), accent, 0.6F)
				: UiTheme.borderColor());
		
		// An unbordered EditBox draws its text at the widget origin, which
		// would put
		// it on top of the frame, so the field is temporarily inset while
		// vanilla
		// draws the text, the hint and the caret.
		int insetY = Math.max(0, (h - 8) / 2);
		setX(x + INNER_X);
		setY(y + insetY);
		setWidth(Math.max(4, w - INNER_X * 2));
		setHeight(Math.max(8, h - insetY));
		renderingText = true;
		try
		{
			super.extractWidgetRenderState(graphics, mouseX, mouseY,
				partialTicks);
		}finally
		{
			renderingText = false;
			setX(savedX);
			setY(savedY);
			setWidth(savedWidth);
			setHeight(savedHeight);
			UiTheme.popWidgetScale(graphics, scaled);
		}
	}
	
	@Override
	public void updateWidgetNarration(
		net.minecraft.client.gui.narration.NarrationElementOutput narration)
	{
		super.updateWidgetNarration(narration);
		tooltips.narrate(narration);
	}
	
	@Override
	public void setTooltip(net.minecraft.client.gui.components.Tooltip tooltip)
	{
		tooltips.set(tooltip);
		// Defer only the screen-coordinate tooltip; native widget hover still
		// uses design units.
		super.setTooltip(null);
	}
	
	@Override
	public void setTooltipDelay(java.time.Duration delay)
	{
		tooltips.setDelay(delay);
	}
	
}
