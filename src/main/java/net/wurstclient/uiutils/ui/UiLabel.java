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
import net.minecraft.network.chat.Component;

/** Static label used as a column next to a control inside a content row. */
public class UiLabel extends AbstractWidget implements UiScalable
{
	private final UiWidgetTooltip tooltips = new UiWidgetTooltip();
	private int color = UiTheme.textColor();
	private boolean dim;
	private boolean centered;
	private float uiScale = 1F;
	
	public UiLabel(String text)
	{
		super(0, 0, 10, 10, Component.literal(text == null ? "" : text));
		this.active = false;
	}
	
	public UiLabel color(int argb)
	{
		this.color = argb;
		return this;
	}
	
	public UiLabel dim()
	{
		this.dim = true;
		return this;
	}
	
	public UiLabel text(String value)
	{
		setMessage(Component.literal(value == null ? "" : value));
		return this;
	}
	
	public UiLabel centered()
	{
		this.centered = true;
		return this;
	}
	
	public UiLabel size(int width, int height)
	{
		setWidth(width);
		setHeight(height);
		return this;
	}
	
	public UiLabel uiScale(float value)
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
		String shown = UiTheme.ellipsize(font, getMessage().getString(), w - 2);
		int textY = UiTheme.textY(font, y, h);
		if(centered)
			UiTheme.textCentered(graphics, font, shown, x + w / 2, textY,
				dim ? UiTheme.dimTextColor() : color);
		else
			UiTheme.text(graphics, font, shown, x, textY,
				dim ? UiTheme.dimTextColor() : color);
		UiTheme.popWidgetScale(graphics, scaled);
	}
	
	@Override
	protected void updateWidgetNarration(NarrationElementOutput narration)
	{
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
