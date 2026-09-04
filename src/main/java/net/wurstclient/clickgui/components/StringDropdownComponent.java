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
		
		boolean modern =
			getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow;
		if(modern && (localY < 2 || localY >= getHeight() - 2))
			return;
		
		if(localY < getLabelHeight())
			return;
		
		int popupWidth = getPopupWidth();
		
		switch(mouseButton)
		{
			case GLFW.GLFW_MOUSE_BUTTON_LEFT:
			handleLeftClick(popupWidth);
			break;
			
			case GLFW.GLFW_MOUSE_BUTTON_MIDDLE:
			handleMiddleClick();
			break;
			
			case GLFW.GLFW_MOUSE_BUTTON_RIGHT:
			handleRightClick();
			break;
		}
	}
	
	private void handleLeftClick(int popupWidth)
	{
		if(popup != null && GUI.hasPopup(popup))
		{
			popup.close();
			popup = null;
			return;
		}
		popup = null;
		
		popup = new StringDropdownPopup(this, setting, popupWidth);
		GUI.addPopup(popup);
	}
	
	private void handleMiddleClick()
	{
		if(isPopupOpen())
			popup.close();
		setting.setSelected(setting.getValues().get(0));
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
		int popupWidth = getPopupWidth();
		int x1 = getX();
		int x2 = x1 + getWidth();
		int labelHeight = getLabelHeight();
		int boxHeight = getBoxHeight();
		int boxY1 = getY() + labelHeight;
		int boxY2 = boxY1 + boxHeight;
		int arrowX1 = x2 - getArrowWidth();
		int arrowX2 = x2;
		String name = setting.getName();
		String value = setting.getSelected();
		
		boolean modern =
			getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow;
		int controlY1 = modern ? boxY1 + 2 : boxY1;
		int controlY2 = modern ? boxY2 - 2 : boxY2;
		int dropdownX1 = getDropdownStart();
		boolean hovering = isHovering(mouseX, mouseY);
		boolean hText =
			hovering && (modern ? mouseX < dropdownX1 : mouseY < boxY1);
		boolean hBox = hovering && (modern
			? mouseX >= dropdownX1 && mouseY >= controlY1 && mouseY < controlY2
			: mouseY >= boxY1);
		
		if(hText)
			GUI.setTooltip(setting.getWrappedDescription(200));
		
		if(modern)
		{
			context.fill(x1, getY(), x2, boxY2, getRowFillColor());
			context.fill(dropdownX1, controlY1, x2, controlY2,
				getDropdownFillColor(hBox));
		}else
		{
			context.fill(x1, getY(), x2, boxY1, getRowFillColor());
			context.fill(x1, boxY1, x2, boxY2, getDropdownFillColor(hBox));
		}
		
		context.guiRenderState.up();
		
		int outlineColor = RenderUtils.toIntColor(GUI.getAcColor(), 0.5F);
		int outlineX1 = modern ? dropdownX1 : x1;
		int outlineY1 = modern ? controlY1 : boxY1;
		int outlineY2 = modern ? controlY2 : boxY2;
		RenderUtils.drawBorder2D(context, outlineX1, outlineY1, x2, outlineY2,
			outlineColor);
		RenderUtils.drawLine2D(context, arrowX1, outlineY1, arrowX1, outlineY2,
			outlineColor);
		
		ClickGuiIcons.drawMinimizeArrow(context, arrowX1, outlineY1 + 0.5F,
			arrowX2, outlineY2 - 0.5F, hBox, !isPopupOpen());
		
		int txtColor = GUI.getTxtColor();
		if(modern)
		{
			int textY = Math.round(getY() + (getHeight() - TR.lineHeight) / 2F);
			int nameX = x1 + 8;
			int valueRight = arrowX1 - 6;
			int valueLeft = dropdownX1 + 6;
			String visibleValue =
				trimToWidth(value, Math.max(0, valueRight - valueLeft));
			context.text(TR, name, nameX, textY, txtColor, false);
			context.text(TR, visibleValue, valueRight - TR.width(visibleValue),
				textY, txtColor, false);
		}else
		{
			int nameY = getY() + (labelHeight - TR.lineHeight) / 2;
			int valueY = boxY1 + (boxHeight - TR.lineHeight) / 2;
			context.text(TR, name, x1, nameY, txtColor, false);
			context.text(TR, value, x1 + 2, valueY, txtColor, false);
		}
	}
	
	private int computePopupWidth()
	{
		return setting.getValues().stream().mapToInt(s -> TR.width(s)).max()
			.orElse(TR.width(setting.getName()));
	}
	
	private int getPopupWidth()
	{
		int width = computePopupWidth();
		if(getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow)
			width = Math.max(0, getX() + getWidth() - getDropdownStart() - 15);
		return width;
	}
	
	private int getDropdownStart()
	{
		int x1 = getX();
		int arrowX1 = x1 + getWidth() - getArrowWidth();
		if(getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow)
			return arrowX1 - getVisibleValueWidth() - 4;
		return x1;
	}
	
	private int getVisibleValueWidth()
	{
		int maxWidth = getWidth() - getArrowWidth() - 4;
		return Math.max(24, Math.min(computePopupWidth(), maxWidth));
	}
	
	private int getRowFillColor()
	{
		return RenderUtils.toIntColor(GUI.getBgColor(), GUI.getOpacity());
	}
	
	private int getDropdownFillColor(boolean hovering)
	{
		if(getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow)
		{
			float shade = hovering ? 0.82F : 0.70F;
			float[] base = GUI.getBgColor();
			float[] dropdown =
				{base[0] * shade, base[1] * shade, base[2] * shade};
			return RenderUtils.toIntColor(dropdown, GUI.getOpacity());
		}
		float opacity = GUI.getOpacity() * (hovering ? 1.5F : 1);
		return RenderUtils.toIntColor(GUI.getBgColor(), opacity);
	}
	
	private static String trimToWidth(String text, int width)
	{
		if(width <= 0)
			return "";
		if(TR.width(text) <= width)
			return text;
		if(width < TR.width("..."))
			return TR.plainSubstrByWidth(text, width);
		return TR.plainSubstrByWidth(text, width - TR.width("...")) + "...";
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
	
	private int getLabelHeight()
	{
		return getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow
			? 0 : Math.max(11, TR.lineHeight + 2);
	}
	
	private int getArrowWidth()
	{
		return getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow
			? 14 : getBoxHeight();
	}
	
	private int getBoxHeight()
	{
		return getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow
			? getHeight() : Math.max(11, TR.lineHeight + 2);
	}
}
