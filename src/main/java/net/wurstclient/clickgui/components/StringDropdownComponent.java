/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.ClickGuiIcons;
import net.wurstclient.clickgui.Component;
import net.wurstclient.clickgui.StringDropdownPopup;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.util.GuiRenderStateHelper;
import net.wurstclient.util.RenderUtils;

public final class StringDropdownComponent extends Component
{
	private static final ClickGui GUI = WurstClient.INSTANCE.getGui();
	private static final Font TR = WurstClient.MC.font;
	private static final int ARROW_SIZE = 11;
	private static final int LABEL_HEIGHT = 11;
	
	private final StringDropdownSetting setting;
	private StringDropdownPopup popup;
	
	public StringDropdownComponent(StringDropdownSetting setting)
	{
		this.setting = setting;
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton)
	{
		double localX = mouseX - getX();
		double localY = mouseY - getY();
		if(localX < 0 || localX >= getWidth())
			return;
		
		if(localY < LABEL_HEIGHT)
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
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		int popupWidth = computePopupWidth();
		int x1 = getX();
		int x2 = x1 + getWidth();
		int boxY1 = getY() + LABEL_HEIGHT;
		int boxY2 = boxY1 + ARROW_SIZE;
		int arrowX1 = x2 - ARROW_SIZE;
		int arrowX2 = x2;
		
		boolean hovering = isHovering(mouseX, mouseY);
		boolean hText = hovering && mouseY < boxY1;
		boolean hBox = hovering && mouseY >= boxY1;
		
		if(hText)
			GUI.setTooltip(setting.getWrappedDescription(200));
		
		context.fill(x1, getY(), x2, boxY1, getFillColor(false));
		context.fill(x1, boxY1, x2, boxY2, getFillColor(hBox));
		
		GuiRenderStateHelper.up(context);
		
		int outlineColor = RenderUtils.toIntColor(GUI.getAcColor(), 0.5F);
		RenderUtils.drawBorder2D(context, x1, boxY1, x2, boxY2, outlineColor);
		RenderUtils.drawLine2D(context, arrowX1, boxY1, arrowX1, boxY2,
			outlineColor);
		
		ClickGuiIcons.drawMinimizeArrow(context, arrowX1, boxY1 + 0.5F, arrowX2,
			boxY2 - 0.5F, hBox, !isPopupOpen());
		
		String name = setting.getName();
		String value = setting.getSelected();
		int txtColor = GUI.getTxtColor();
		context.drawString(TR, name, x1, getY() + 2, txtColor, false);
		context.drawString(TR, value, x1 + 2, boxY1 + 2, txtColor, false);
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
		int boxWidth = popupWidth + ARROW_SIZE + 6;
		int labelWidth = TR.width(setting.getName()) + 4;
		return Math.max(labelWidth, boxWidth);
	}
	
	@Override
	public int getDefaultHeight()
	{
		return LABEL_HEIGHT + ARROW_SIZE;
	}
}
