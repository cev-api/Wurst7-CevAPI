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
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Themed slider with a thin Wurst-style track: the label sits on the left, the
 * value on the right and the bar runs underneath.
 */
public class UiSlider extends AbstractSliderButton implements UiScalable
{
	private final UiWidgetTooltip tooltips = new UiWidgetTooltip();
	private final String label;
	private final int min;
	private final int max;
	private java.util.function.IntConsumer onChange;
	private String valueSuffix = "";
	private float uiScale = 1F;
	
	public UiSlider(String label, int min, int max, int initial,
		java.util.function.IntConsumer onChange)
	{
		super(0, 0, 10, 10, Component.empty(), normalize(initial, min, max));
		this.label = label;
		this.min = min;
		this.max = max;
		this.onChange = onChange;
		updateMessage();
	}
	
	public UiSlider onValue(java.util.function.IntConsumer consumer)
	{
		this.onChange = consumer;
		return this;
	}
	
	public UiSlider suffix(String suffix)
	{
		this.valueSuffix = suffix == null ? "" : suffix;
		updateMessage();
		return this;
	}
	
	public UiSlider uiScale(float value)
	{
		this.uiScale = value <= 0F ? 1F : value;
		return this;
	}
	
	@Override
	public void applyUiScale(float value)
	{
		uiScale(value);
	}
	
	public int intValue()
	{
		return Mth.clamp((int)Math.round(min + (max - min) * this.value), min,
			max);
	}
	
	public void setIntValue(int value)
	{
		this.value = normalize(Mth.clamp(value, min, max), min, max);
		updateMessage();
	}
	
	@Override
	protected void updateMessage()
	{
		setMessage(Component.literal(label + ": " + intValue() + valueSuffix));
	}
	
	@Override
	protected void applyValue()
	{
		if(onChange != null)
			onChange.accept(intValue());
	}
	
	@Override
	public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
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
		
		boolean hot = isHoveredOrFocused() && active;
		graphics.fill(x, y, x + w, y + h,
			hot ? UiTheme.SURFACE_ROW_HOVER : UiTheme.SURFACE_ROW);
		UiTheme.border(graphics, x, y, w, h, UiTheme.borderColor());
		
		int barLeft = x + 4;
		int barRight = x + w - 4;
		int barY = y + h - 5;
		graphics.fill(barLeft, barY, barRight, barY + 2, UiTheme.offTrack());
		int knob = barLeft + (int)Math.round((barRight - barLeft) * this.value);
		graphics.fill(barLeft, barY, Math.max(barLeft + 1, knob), barY + 2,
			UiTheme.accent());
		graphics.fill(knob - 1, barY - 2, knob + 1, barY + 4,
			UiTheme.textColor());
		
		int textY = UiTheme.textY(font, y, h - 3);
		String value = intValue() + valueSuffix;
		int valueWidth = font.width(value);
		UiTheme.text(graphics, font,
			UiTheme.ellipsize(font, label, w - 12 - valueWidth), x + 4, textY,
			UiTheme.textColor());
		UiTheme.textRight(graphics, font, value, x + w - 4, textY,
			UiTheme.dimTextColor());
		UiTheme.popWidgetScale(graphics, scaled);
	}
	
	private static double normalize(int value, int min, int max)
	{
		if(max <= min)
			return 0.0D;
		return Mth.clamp((value - min) / (double)(max - min), 0.0D, 1.0D);
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
