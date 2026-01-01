/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import java.util.ArrayList;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.clickgui.ClickGuiIcons;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.Component;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.clickgui.Window;

public final class SettingGroupComponent extends Component
{
	private static final int HEADER_HEIGHT = 13;
	private final SettingGroup group;
	private final ArrayList<Component> childComponents = new ArrayList<>();
	private final boolean popoutEnabled;
	private boolean expanded;
	
	public SettingGroupComponent(SettingGroup group)
	{
		this(group, true);
	}
	
	public SettingGroupComponent(SettingGroup group, boolean allowPopout)
	{
		this.group = group;
		popoutEnabled = allowPopout && group.isPopout();
		expanded = group.isDefaultExpanded();
		rebuildChildren();
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	private void rebuildChildren()
	{
		childComponents.clear();
		for(Setting child : group.getChildren())
		{
			Component comp = child.getComponent();
			if(comp != null)
			{
				comp.setWidth(comp.getDefaultWidth());
				comp.setHeight(comp.getDefaultHeight());
				if(getParent() != null)
					comp.setParent(getParent());
				childComponents.add(comp);
			}
		}
		updateHeight();
	}
	
	private void updateHeight()
	{
		int height = HEADER_HEIGHT;
		if(expanded)
			for(Component child : childComponents)
				height += child.getHeight() + 2;
		setHeight(height);
	}
	
	private boolean togglePopoutWindow()
	{
		if(!popoutEnabled)
			return false;
		
		ClickGui gui = WURST.getGui();
		Window existing = gui.findWindowByTitle(group.getName());
		if(existing != null)
		{
			existing.close();
			return true;
		}
		
		Window parent = getParent();
		if(parent == null)
			return false;
		
		Window popupWin = new Window(group.getName());
		for(Setting s : group.getChildren())
		{
			Component c = s.getComponent();
			if(c != null)
			{
				c.setWidth(c.getDefaultWidth());
				c.setHeight(c.getDefaultHeight());
				popupWin.add(c);
			}
		}
		popupWin.pack();
		popupWin.setPinnable(true);
		popupWin.setClosable(true);
		popupWin.setX(parent.getX() + getX() + getWidth() + 5);
		popupWin.setY(parent.getY() + 13 + parent.getScrollOffset() + getY());
		gui.addWindow(popupWin);
		return true;
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
		{
			if(expanded)
				for(Component child : childComponents)
					child.handleMouseClick(mouseX, mouseY, mouseButton,
						context);
			return;
		}
		
		int x1 = getX();
		int y1 = getY();
		int y2 = y1 + HEADER_HEIGHT;
		int x2 = x1 + getWidth();
		int arrowX1 = x2 - 11;
		if(mouseX >= x1 && mouseX <= x1 + getWidth() && mouseY >= y1
			&& mouseY <= y2)
		{
			boolean clickedArrow = mouseX >= arrowX1 && mouseX <= x2
				&& mouseY >= y1 && mouseY <= y2;
			
			if(popoutEnabled && clickedArrow)
			{
				if(togglePopoutWindow())
					return;
			}
			
			if(popoutEnabled)
			{
				if(togglePopoutWindow())
					return;
			}
			
			expanded = !expanded;
			updateHeight();
			return;
		}
		
		if(!expanded)
			return;
		
		for(Component child : childComponents)
		{
			int cx1 = child.getX();
			int cy1 = child.getY();
			int cx2 = cx1 + child.getWidth();
			int cy2 = cy1 + child.getHeight();
			
			if(mouseX < cx1 || mouseX > cx2 || mouseY < cy1 || mouseY > cy2)
				continue;
			
			child.handleMouseClick(mouseX, mouseY, mouseButton, context);
			break;
		}
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		int x1 = getX();
		int y1 = getY();
		int x2 = x1 + getWidth();
		int y2 = y1 + HEADER_HEIGHT;
		
		boolean hoverHeader =
			mouseX >= x1 && mouseX <= x2 && mouseY >= y1 && mouseY <= y2;
		float opacity = WURST.getGui().getOpacity() * (hoverHeader ? 1.2F : 1F);
		int bgColor =
			RenderUtils.toIntColor(WURST.getGui().getBgColor(), opacity);
		context.fill(x1, y1, x2, y2, bgColor);
		
		int txtColor = WURST.getGui().getTxtColor();
		context.drawString(MC.font, group.getName(), x1 + 4, y1 + 2, txtColor,
			false);
		ClickGuiIcons.drawMinimizeArrow(context, x2 - 11, y1 + 1, x2 - 1,
			y2 - 1, hoverHeader, !expanded);
		
		if(!expanded)
			return;
		
		int sectionTop = y2;
		int sectionBottom = sectionTop;
		for(Component child : childComponents)
			sectionBottom += child.getHeight() + 2;
		
		float sectionOpacity = popoutEnabled ? opacity * 0.9F : 1.0F;
		int sectionColor =
			RenderUtils.toIntColor(WURST.getGui().getBgColor(), sectionOpacity);
		context.fill(x1, sectionTop, x2, sectionBottom, sectionColor);
		
		int outlineColor =
			RenderUtils.toIntColor(WURST.getGui().getAcColor(), opacity * 0.5F);
		RenderUtils.drawLine2D(context, x1, sectionTop, x2, sectionTop,
			outlineColor);
		RenderUtils.drawLine2D(context, x1, sectionBottom, x2, sectionBottom,
			outlineColor);
		
		int childY = sectionTop + 1;
		for(Component child : childComponents)
		{
			child.setX(x1 + 2);
			child.setY(childY);
			child.setWidth(getWidth() - 4);
			
			child.render(context, mouseX, mouseY, partialTicks);
			childY += child.getHeight() + 2;
		}
	}
	
	@Override
	public void setParent(Window parent)
	{
		super.setParent(parent);
		for(Component child : childComponents)
			child.setParent(parent);
	}
	
	@Override
	public int getDefaultWidth()
	{
		return 200;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return HEADER_HEIGHT;
	}
}
