/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/**
 * Integer slider drawn in the same colors as UiUtilsColoredButton.
 */
public final class UiUtilsColoredSlider extends AbstractSliderButton
{
	@FunctionalInterface
	public interface ValueChangeAction
	{
		void onValueChanged(int value);
	}
	
	private final String label;
	private final String unit;
	private final int minValue;
	private final int maxValue;
	private final ValueChangeAction onChange;
	private int lastValue;
	
	public UiUtilsColoredSlider(int x, int y, int width, int height,
		String label, String unit, int minValue, int maxValue, int currentValue,
		ValueChangeAction onChange)
	{
		super(x, y, width, height, Component.empty(),
			toSliderValue(minValue, maxValue, currentValue));
		this.label = label;
		this.unit = unit;
		this.minValue = minValue;
		this.maxValue = maxValue;
		this.onChange = onChange;
		this.lastValue = clamp(currentValue);
		updateMessage();
	}
	
	public int getIntValue()
	{
		return minValue + (int)Math.round(value * (maxValue - minValue));
	}
	
	@Override
	protected void updateMessage()
	{
		if(label == null)
		{
			setMessage(Component.empty());
			return;
		}
		setMessage(Component.literal(label + getIntValue() + unit));
	}
	
	@Override
	protected void applyValue()
	{
		if(onChange == null)
			return;
		int current = getIntValue();
		if(current == lastValue)
			return;
		lastValue = current;
		onChange.onValueChanged(current);
	}
	
	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor graphics,
		int mouseX, int mouseY, float partialTicks)
	{
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		
		int baseRgb = UiUtilsColoredButton.getEnabledHackRgb();
		float mult = !active ? 0.45F : (isHoveredOrFocused() ? 1.15F : 1.0F);
		int track =
			0xFF000000 | UiUtilsColoredButton.scaleRgb(baseRgb, mult * 0.45F);
		int border = 0xFF000000
			| UiUtilsColoredButton.scaleRgb(baseRgb, active ? 0.60F : 0.35F);
		int handle = 0xFF000000
			| UiUtilsColoredButton.scaleRgb(baseRgb, active ? 1.30F : 0.90F);
		
		graphics.fill(x, y, x + w, y + h, track);
		graphics.outline(x, y, w, h, border);
		
		int handleWidth = Math.max(4, Math.min(HANDLE_WIDTH, w / 3));
		int handleX = x + (int)Math.round(value * (w - handleWidth));
		graphics.fill(handleX, y + 1, handleX + handleWidth, y + h - 1, handle);
		graphics.outline(handleX, y, handleWidth, h, border);
		
		int textColor =
			0xFF000000 | (UiUtilsSettings.get().uiButtonTextColor & 0xFFFFFF);
		int textY =
			y + Math.max(1, (h - Minecraft.getInstance().font.lineHeight) / 2);
		UiUtils.renderScaledCenteredText(graphics, Minecraft.getInstance().font,
			getMessage(), x + w / 2, textY, w - 6, h - 2, textColor, 0.35F);
	}
	
	private static double toSliderValue(int minValue, int maxValue,
		int currentValue)
	{
		int range = Math.max(1, maxValue - minValue);
		double ratio = (currentValue - minValue) / (double)range;
		return Math.max(0, Math.min(1, ratio));
	}
	
	private int clamp(int value)
	{
		return Math.max(minValue, Math.min(maxValue, value));
	}
}
