/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import java.util.Arrays;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.ClickGuiIcons;
import net.wurstclient.clickgui.ComboBoxPopup;
import net.wurstclient.clickgui.Component;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.util.RenderUtils;

public final class ComboBoxComponent<T extends Enum<T>> extends Component
{
	private static final ClickGui GUI = WURST.getGui();
	private static final Font TR = MC.font;
	private static final int VALUE_PADDING = 8;
	
	private final EnumSetting<T> setting;
	private final int popupWidth;
	
	private ComboBoxPopup<T> popup;
	
	public ComboBoxComponent(EnumSetting<T> setting)
	{
		this.setting = setting;
		popupWidth = Arrays.stream(setting.getValues()).map(T::toString)
			.mapToInt(s -> TR.width(s)).max().getAsInt() + VALUE_PADDING;
		
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		boolean modern =
			getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow;
		if(modern
			&& (mouseY < getY() + 2 || mouseY >= getY() + getHeight() - 2))
			return;
		
		if(mouseX < getDropdownStart())
			return;
		
		switch(mouseButton)
		{
			case GLFW.GLFW_MOUSE_BUTTON_LEFT:
			handleLeftClick();
			break;
			
			case GLFW.GLFW_MOUSE_BUTTON_RIGHT:
			handleRightClick();
			break;
		}
	}
	
	private void handleLeftClick()
	{
		if(popup != null && GUI.hasPopup(popup))
		{
			popup.close();
			popup = null;
			return;
		}
		popup = null;
		
		int popupContentWidth = popupWidth;
		if(getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow)
			popupContentWidth =
				Math.max(0, getX() + getWidth() - getDropdownStart() - 15);
		popup = new ComboBoxPopup<>(this, setting, popupContentWidth);
		GUI.addPopup(popup);
	}
	
	private void handleRightClick()
	{
		if(isPopupOpen())
			return;
		
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
		int x1 = getX();
		int x2 = x1 + getWidth();
		int boxHeight = getBoxHeight();
		int x3 = x2 - getArrowWidth();
		String name = setting.getName();
		boolean modern =
			getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow;
		int x4 = getDropdownStart();
		int y1 = getY();
		int y2 = y1 + getHeight();
		int controlY1 = modern ? y1 + 2 : y1;
		int controlY2 = modern ? y2 - 2 : y2;
		
		boolean hovering = isHovering(mouseX, mouseY);
		boolean hText = hovering && mouseX < x4;
		boolean hBox = hovering && mouseX >= x4 && mouseY >= controlY1
			&& mouseY < controlY2;
		
		// tooltip
		if(hText)
			GUI.setTooltip(setting.getWrappedDescription(200));
		
		// background
		context.fill(x1, y1, x4, y2, getRowFillColor());
		
		// box
		context.fill(x4, controlY1, x2, controlY2, getDropdownFillColor(hBox));
		
		context.guiRenderState.up();
		
		// outlines
		int outlineColor = RenderUtils.toIntColor(GUI.getAcColor(), 0.5F);
		RenderUtils.drawBorder2D(context, x4, controlY1, x2, controlY2,
			outlineColor);
		RenderUtils.drawLine2D(context, x3, controlY1, x3, controlY2,
			outlineColor);
		
		// arrow
		ClickGuiIcons.drawMinimizeArrow(context, x3, controlY1 + 0.5F, x2,
			controlY2 - 0.5F, hBox, !isPopupOpen());
		
		// text
		String value =
			trimToWidth("" + setting.getSelected(), Math.max(0, x3 - x4 - 10));
		int textY = modern ? Math.round(y1 + (getHeight() - TR.lineHeight) / 2F)
			: y1 + (getHeight() - TR.lineHeight) / 2;
		int txtColor = GUI.getTxtColor();
		context.text(TR, name, x1 + (modern ? 8 : 0), textY, txtColor, false);
		context.text(TR, value, x4 + (modern ? 6 : 2), textY, txtColor, false);
	}
	
	private int getVisibleValueWidth()
	{
		int maxWidth = getWidth() - getArrowWidth() - 4;
		return Math.max(24, Math.min(popupWidth, maxWidth));
	}
	
	private int getDropdownStart()
	{
		int x1 = getX();
		int x2 = x1 + getWidth();
		int arrowX = x2 - getArrowWidth();
		
		return arrowX - getVisibleValueWidth() - 4;
	}
	
	private static String trimToWidth(String text, int width)
	{
		if(TR.width(text) <= width)
			return text;
		
		return TR.plainSubstrByWidth(text, Math.max(0, width - TR.width("...")))
			+ "...";
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
	
	@Override
	public int getDefaultWidth()
	{
		return TR.width(setting.getName()) + popupWidth + getBoxHeight() + 6;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return getBoxHeight();
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
