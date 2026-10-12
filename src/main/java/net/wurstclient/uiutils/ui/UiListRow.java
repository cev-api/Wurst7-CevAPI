/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Selectable list row: a label on the left, an optional detail on the right and
 * an accent bar while it is the current selection.
 */
public class UiListRow extends AbstractWidget implements UiScalable
{
	private final UiWidgetTooltip tooltips = new UiWidgetTooltip();
	private String label;
	private String detail = "";
	private Runnable action;
	private Runnable doubleAction;
	private boolean selected;
	private int detailColor = UiTheme.dimTextColor();
	private int accent = -1;
	private float uiScale = 1F;
	
	public UiListRow(String label, Runnable action)
	{
		super(0, 0, 10, 10, Component.literal(label));
		this.label = label;
		this.action = action;
	}
	
	public UiListRow detail(String value)
	{
		this.detail = value == null ? "" : value;
		return this;
	}
	
	public UiListRow selected(boolean value)
	{
		this.selected = value;
		return this;
	}
	
	public UiListRow accent(int argb)
	{
		this.accent = argb;
		return this;
	}
	
	public UiListRow onClick(Runnable value)
	{
		this.action = value;
		return this;
	}
	
	/** Action for a double click, such as opening the selected entry. */
	public UiListRow doubleRun(Runnable value)
	{
		this.doubleAction = value;
		return this;
	}
	
	public UiListRow label(String value)
	{
		this.label = value == null ? "" : value;
		return this;
	}
	
	public String label()
	{
		return label;
	}
	
	public UiListRow detailColor(int argb)
	{
		this.detailColor = argb;
		return this;
	}
	
	public UiListRow uiScale(float value)
	{
		this.uiScale = value <= 0F ? 1F : value;
		return this;
	}
	
	@Override
	public void applyUiScale(float value)
	{
		uiScale(value);
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
	{
		if(!active || !visible || event
			.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
			|| !isMouseOver(event.x(), event.y()))
			return false;
		Runnable run =
			doubleClick && doubleAction != null ? doubleAction : action;
		if(run != null)
			run.run();
		return true;
	}
	
	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
		float partialTicks)
	{
		isHovered = tooltips.render(this, graphics, mouseX, mouseY);
		Font font = Minecraft.getInstance().font;
		boolean scaled =
			UiTheme.pushWidgetScale(graphics, getX(), getY(), uiScale);
		int x = scaled ? 0 : getX();
		int y = scaled ? 0 : getY();
		int w = UiTheme.localSize(getWidth(), uiScale, scaled);
		int h = UiTheme.localSize(getHeight(), uiScale, scaled);
		int accentColor = accent >= 0 ? accent : UiTheme.accent();
		
		int fill = selected ? UiTheme.accentTint(0x70) : UiTheme.SURFACE_ROW;
		if(isHoveredOrFocused() && active && !selected)
			fill = UiTheme.SURFACE_ROW_HOVER;
		if(!active)
			fill = 0x18FFFFFF;
		
		graphics.fill(x, y, x + w, y + h, fill);
		UiTheme.border(graphics, x, y, w, h,
			selected ? UiTheme.mix(UiTheme.borderColor(), accentColor, 0.7F)
				: UiTheme.borderColor());
		if(selected)
			graphics.fill(x, y, x + 2, y + h, accentColor);
		
		int textY = UiTheme.textY(font, y, h);
		int detailWidth = detail.isEmpty() ? 0 : font.width(detail) + 6;
		int labelSpace = w - 12 - detailWidth;
		UiTheme.text(graphics, font,
			UiTheme.ellipsize(font, label, Math.max(10, labelSpace)), x + 6,
			textY, active ? UiTheme.textColor() : UiTheme.mutedTextColor());
		if(!detail.isEmpty())
			UiTheme.textRight(graphics, font,
				UiTheme.ellipsize(font, detail, Math.max(10, w / 2)), x + w - 5,
				textY, active ? detailColor : UiTheme.mutedTextColor());
		UiTheme.popWidgetScale(graphics, scaled);
	}
	
	@Override
	protected void updateWidgetNarration(NarrationElementOutput narration)
	{
		tooltips.narrate(narration);
		defaultButtonNarrationText(narration);
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
