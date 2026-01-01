/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.wurstclient.WurstClient;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.util.RenderUtils;

public final class StringDropdownPopup extends Popup
{
	private static final ClickGui GUI = WurstClient.INSTANCE.getGui();
	private static final Font TR = WurstClient.MC.font;
	private static final int ROW_HEIGHT = 11;
	private static final int MAX_VISIBLE_ROWS = 8;
	
	private final StringDropdownSetting setting;
	private final int popupWidth;
	private final int totalRows;
	private final int visibleRows;
	private int scrollOffset;
	
	public StringDropdownPopup(Component owner, StringDropdownSetting setting,
		int popupWidth)
	{
		super(owner);
		this.setting = setting;
		this.popupWidth = popupWidth;
		
		totalRows = Math.max(0, setting.getValues().size() - 1);
		visibleRows = Math.min(MAX_VISIBLE_ROWS, totalRows);
		
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
		
		setX(owner.getWidth() - getWidth());
		setY(owner.getHeight());
	}
	
	@Override
	public void handleMouseClick(int mouseX, int mouseY, int mouseButton)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT || visibleRows <= 0)
			return;
		
		int localX = mouseX - getX();
		int localY = mouseY - getY();
		if(localX < 0 || localX >= getWidth() || localY < 0
			|| localY >= getHeight())
			return;
		
		int row = localY / ROW_HEIGHT;
		String value = getValueAt(row + scrollOffset);
		if(value == null)
			return;
		
		setting.setSelected(value);
		close();
	}
	
	@Override
	public boolean handleMouseScroll(int mouseX, int mouseY, double delta)
	{
		if(totalRows <= visibleRows || visibleRows <= 0)
			return false;
		
		int direction = (int)Math.signum(delta);
		if(direction == 0)
			return false;
		
		scrollOffset -= direction;
		clampScroll();
		return true;
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY)
	{
		if(visibleRows <= 0)
			return;
		
		clampScroll();
		
		int x1 = getX();
		int x2 = x1 + getWidth();
		int y1 = getY();
		int y2 = y1 + getHeight();
		
		boolean hovering = isHovering(mouseX, mouseY, x1, x2, y1, y2);
		
		if(hovering)
			GUI.setTooltip("");
		
		RenderUtils.drawBorder2D(context, x1, y1, x2, y2,
			RenderUtils.toIntColor(GUI.getAcColor(), 0.5F));
		
		int drawn = 0;
		int skipped = 0;
		for(String value : setting.getValues())
		{
			if(value.equals(setting.getSelected()))
				continue;
			
			if(skipped++ < scrollOffset)
				continue;
			
			if(drawn >= visibleRows)
				break;
			
			int yi1 = y1 + drawn * ROW_HEIGHT;
			int yi2 = yi1 + ROW_HEIGHT;
			
			boolean hValue = hovering && mouseY >= yi1 && mouseY < yi2;
			context.fill(x1, yi1, x2, yi2, RenderUtils.toIntColor(
				GUI.getBgColor(), GUI.getOpacity() * (hValue ? 1.5F : 1)));
			
			context.guiRenderState.up();
			context.drawString(TR, value, x1 + 2, yi1 + 2, GUI.getTxtColor(),
				false);
			
			drawn++;
		}
	}
	
	private void clampScroll()
	{
		int maxOffset = Math.max(0, totalRows - visibleRows);
		if(scrollOffset < 0)
			scrollOffset = 0;
		else if(scrollOffset > maxOffset)
			scrollOffset = maxOffset;
	}
	
	private String getValueAt(int index)
	{
		if(index < 0)
			return null;
		
		int skipped = 0;
		for(String value : setting.getValues())
		{
			if(value.equals(setting.getSelected()))
				continue;
			
			if(skipped == index)
				return value;
			
			skipped++;
		}
		
		return null;
	}
	
	private boolean isHovering(int mouseX, int mouseY, int x1, int x2, int y1,
		int y2)
	{
		return mouseX >= x1 && mouseY >= y1 && mouseX < x2 && mouseY < y2;
	}
	
	@Override
	public int getDefaultWidth()
	{
		return popupWidth + 15;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return visibleRows * ROW_HEIGHT;
	}
}
