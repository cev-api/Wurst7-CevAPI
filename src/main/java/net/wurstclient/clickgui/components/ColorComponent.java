/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.screens.EditColorScreen;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.util.ColorUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.text.WText;

public final class ColorComponent extends Component
{
	private static final ClickGui GUI = WURST.getGui();
	private static final Font TR = MC.font;
	private static final int TEXT_HEIGHT = 11;
	
	private final ColorSetting setting;
	
	public ColorComponent(ColorSetting setting)
	{
		this.setting = setting;
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		if(!(getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow)
			&& mouseY < getY() + TEXT_HEIGHT)
			return;
		
		switch(mouseButton)
		{
			case GLFW.GLFW_MOUSE_BUTTON_LEFT:
			MC.gui.setScreen(new EditColorScreen(MC.gui.screen(), setting));
			break;
			
			case GLFW.GLFW_MOUSE_BUTTON_RIGHT:
			setting.resetToDefault();
			break;
		}
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		int x1 = getX();
		int x2 = x1 + getWidth();
		int y1 = getY();
		int y2 = y1 + getHeight();
		
		if(getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow)
		{
			boolean hovering = isHovering(mouseX, mouseY);
			if(hovering)
				GUI.setTooltip(getColorTooltip());
			int rowBg =
				RenderUtils.toIntColor(GUI.getBgColor(), GUI.getOpacity());
			context.fill(x1, y1, x2, y2, rowBg);
			int swatchX2 = x2 - 8;
			int swatchX1 = swatchX2 - 40;
			int swatchHeight = Math.min(7, Math.max(4, getHeight() - 10));
			int swatchY1 = y1 + (getHeight() - swatchHeight) / 2;
			int swatchY2 = swatchY1 + swatchHeight;
			context.fill(swatchX1, swatchY1, swatchX2, swatchY2,
				setting.getColorI(hovering ? 1F : GUI.getOpacity()));
			RenderUtils.drawBorder2D(context, swatchX1, swatchY1, swatchX2,
				swatchY2, RenderUtils.toIntColor(GUI.getAcColor(), 0.75F));
			context.text(TR, setting.getName(), x1 + 8,
				Math.round(y1 + (getHeight() - TR.lineHeight) / 2F),
				GUI.getTxtColor(), false);
			return;
		}
		int y3 = y1 + TEXT_HEIGHT;
		
		boolean hovering = isHovering(mouseX, mouseY);
		boolean hText = hovering && mouseY < y3;
		boolean hColor = hovering && mouseY >= y3;
		
		if(hText)
			GUI.setTooltip(setting.getWrappedDescription(200));
		else if(hColor)
			GUI.setTooltip(getColorTooltip());
		
		// background
		float opacity = GUI.getOpacity();
		int bgColor = RenderUtils.toIntColor(GUI.getBgColor(), opacity);
		context.fill(x1, y1, x2, y3, bgColor);
		
		// box
		context.fill(x1, y3, x2, y2,
			setting.getColorI(hovering ? 1F : opacity));
		int outlineColor = RenderUtils.toIntColor(GUI.getAcColor(), 0.5F);
		RenderUtils.drawBorder2D(context, x1, y3, x2, y2, outlineColor);
		
		// text
		String name = setting.getName();
		String value = ColorUtils.toHex(setting.getColor());
		int valueWidth = TR.width(value);
		int txtColor = GUI.getTxtColor();
		context.guiRenderState.up();
		context.text(TR, name, x1, y1 + 2, txtColor, false);
		context.text(TR, value, x2 - valueWidth, y1 + 2, txtColor, false);
	}
	
	private String getColorTooltip()
	{
		return WText.literal("\u00a7cR:\u00a7r" + setting.getRed())
			.append(WText.literal(" \u00a7aG:\u00a7r" + setting.getGreen()))
			.append(WText.literal(" \u00a79B:\u00a7r" + setting.getBlue()))
			.append(WText.literal("\n\n"))
			.append(WText.translated("gui.wurst.generic.left_click_to_edit"))
			.append(WText.literal("\n"))
			.append(WText.translated("gui.wurst.generic.right_click_to_reset"))
			.toString();
	}
	
	@Override
	public int getDefaultWidth()
	{
		return TR.width(setting.getName() + "#FFFFFF") + 6;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return TEXT_HEIGHT * 2;
	}
}
