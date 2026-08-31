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
import net.wurstclient.settings.Setting;
import net.wurstclient.util.RenderUtils;

public abstract class AbstractListEditButton extends Component
{
	private static final ClickGui GUI = WURST.getGui();
	private static final Font TR = MC.font;
	
	private final String buttonText = "Edit...";
	private final int buttonWidth = TR.width(buttonText);
	
	protected abstract void openScreen();
	
	protected abstract String getText();
	
	protected abstract Setting getSetting();
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		
		if(mouseX < getX() + getWidth() - getButtonAreaWidth())
			return;
		
		openScreen();
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		int x1 = getX();
		int x2 = x1 + getWidth();
		int x3 = x2 - getButtonAreaWidth();
		int y1 = getY();
		int y2 = y1 + getHeight();
		
		boolean hovering = isHovering(mouseX, mouseY);
		boolean hText = hovering && mouseX < x3;
		boolean hBox = hovering && mouseX >= x3;
		
		if(hText)
			GUI.setTooltip(getSetting().getWrappedDescription(200));
		
		// background
		context.fill(x1, y1, x3, y2, getRowFillColor());
		
		// button
		context.fill(x3, y1, x2, y2, getButtonFillColor(hBox));
		int outlineColor = RenderUtils.toIntColor(GUI.getAcColor(), 0.5F);
		RenderUtils.drawBorder2D(context, x3, y1, x2, y2, outlineColor);
		
		// text
		int txtColor = GUI.getTxtColor();
		context.guiRenderState.up();
		boolean modern =
			getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow;
		int textY = modern ? Math.round(y1 + (getHeight() - TR.lineHeight) / 2F)
			: y1 + 2;
		context.text(TR, getText(), x1 + (modern ? 8 : 0), textY, txtColor,
			false);
		int buttonTextX = x3 + (x2 - x3 - buttonWidth) / 2;
		context.text(TR, buttonText, buttonTextX, textY, txtColor, false);
	}
	
	private int getButtonAreaWidth()
	{
		boolean modern =
			getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow;
		return modern ? Math.max(44, buttonWidth + 12) : buttonWidth + 4;
	}
	
	private int getRowFillColor()
	{
		return RenderUtils.toIntColor(GUI.getBgColor(), GUI.getOpacity());
	}
	
	private int getButtonFillColor(boolean hovering)
	{
		if(getParent() instanceof net.wurstclient.clickgui.modern.ModernWindow)
		{
			float shade = hovering ? 0.84F : 0.68F;
			float[] base = GUI.getBgColor();
			float[] button =
				{base[0] * shade, base[1] * shade, base[2] * shade};
			return RenderUtils.toIntColor(button, GUI.getOpacity());
		}
		float opacity = GUI.getOpacity() * (hovering ? 1.5F : 1);
		return RenderUtils.toIntColor(GUI.getBgColor(), opacity);
	}
	
	@Override
	public int getDefaultWidth()
	{
		return TR.width(getText()) + buttonWidth + 6;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return 11;
	}
}
