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
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.ClickGuiIcons;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.StringDropdownPopup;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.util.RenderUtils;

public final class StringDropdownComponent extends Component
{
	private static final ClickGui GUI = WurstClient.INSTANCE.getGui();
	private static final Font TR = WurstClient.MC.font;
	
	private final StringDropdownSetting setting;
	private StringDropdownPopup popup;
	
	public StringDropdownComponent(StringDropdownSetting setting)
	{
		this.setting = setting;
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		double localX = mouseX - getX();
		double localY = mouseY - getY();
		if(localX < 0 || localX >= getWidth())
			return;
		
		if(localY < getLabelHeight())
			return;
		
		int popupWidth = computePopupWidth();
		
		switch(mouseButton)
		{
			case GLFW.GLFW_MOUSE_BUTTON_LEFT:
			handleLeftClick(popupWidth);
			break;
			
			case GLFW.GLFW_MOUSE_BUTTON_RIGHT:
			handleRightClick();
			break;
		}
	}
	
	private void handleLeftClick(int popupWidth)
	{
		if(isPopupOpen())
		{
			popup.close();
			popup = null;
			return;
		}
		
		popup = new StringDropdownPopup(this, setting, popupWidth);
		GUI.addPopup(popup);
	}
	
	private void handleRightClick()
	{
		setting.resetToDefault();
	}
	
	private boolean isPopupOpen()
	{
		return popup != null && !popup.isClosing();
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		int popupWidth = computePopupWidth();
		int x1 = getX();
		int x2 = x1 + getWidth();
		int labelHeight = getLabelHeight();
		int boxHeight = getBoxHeight();
		int boxY1 = getY() + labelHeight;
		int boxY2 = boxY1 + boxHeight;
		int arrowX1 = x2 - boxHeight;
		int arrowX2 = x2;
		
		boolean hovering = isHovering(mouseX, mouseY);
		boolean hText = hovering && mouseY < boxY1;
		boolean hBox = hovering && mouseY >= boxY1;
		
		if(hText)
			GUI.setTooltip(setting.getWrappedDescription(200));
		
		context.fill(x1, getY(), x2, boxY1, getFillColor(false));
		context.fill(x1, boxY1, x2, boxY2, getFillColor(hBox));
		
		context.guiRenderState.up();
		
		int outlineColor = RenderUtils.toIntColor(GUI.getAcColor(), 0.5F);
		RenderUtils.drawBorder2D(context, x1, boxY1, x2, boxY2, outlineColor);
		RenderUtils.drawLine2D(context, arrowX1, boxY1, arrowX1, boxY2,
			outlineColor);
		
		ClickGuiIcons.drawMinimizeArrow(context, arrowX1, boxY1 + 0.5F, arrowX2,
			boxY2 - 0.5F, hBox, !isPopupOpen());
		
		String name = setting.getName();
		String value = setting.getSelected();
		int txtColor = GUI.getTxtColor();
		int nameY = getY() + (labelHeight - TR.lineHeight) / 2;
		int valueY = boxY1 + (boxHeight - TR.lineHeight) / 2;
		context.text(TR, name, x1, nameY, txtColor, false);
		context.text(TR, value, x1 + 2, valueY, txtColor, false);
	}
	
	private int computePopupWidth()
	{
		return setting.getValues().stream().mapToInt(s -> TR.width(s)).max()
			.orElse(TR.width(setting.getName()));
	}
	
	private int getFillColor(boolean hovering)
	{
		float opacity = GUI.getOpacity() * (hovering ? 1.5F : 1);
		return RenderUtils.toIntColor(GUI.getBgColor(), opacity);
	}
	
	@Override
	public int getDefaultWidth()
	{
		int popupWidth = computePopupWidth();
		int boxWidth = popupWidth + getBoxHeight() + 6;
		int labelWidth = TR.width(setting.getName()) + 4;
		return Math.max(labelWidth, boxWidth);
	}
	
	@Override
	public int getDefaultHeight()
	{
		return getLabelHeight() + getBoxHeight();
	}
	
	private static int getLabelHeight()
	{
		return Math.max(11, TR.lineHeight + 2);
	}
	
	private static int getBoxHeight()
	{
		return Math.max(11, TR.lineHeight + 2);
	}
}
