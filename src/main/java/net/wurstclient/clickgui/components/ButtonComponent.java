/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.MouseButtonEvent;
import net.wurstclient.clickgui.Component;
import net.wurstclient.util.RenderUtils;

public final class ButtonComponent extends Component
{
	private final String text;
	private final Runnable action;
	
	public ButtonComponent(String text, Runnable action)
	{
		this.text = text;
		this.action = action;
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int mouseButton,
		MouseButtonEvent context)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		if(isHovering((int)mouseX, (int)mouseY))
			action.run();
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		int x1 = getX();
		int y1 = getY();
		int x2 = x1 + getWidth();
		int y2 = y1 + getHeight();
		boolean hover = isHovering(mouseX, mouseY);
		int color = RenderUtils.toIntColor(WURST.getGui().getBgColor(),
			WURST.getGui().getOpacity() * (hover ? 1.2F : 1.0F));
		context.fill(x1, y1, x2, y2, color);
		context.drawCenteredString(MC.font, text, (x1 + x2) / 2, y1 + 2,
			WURST.getGui().getTxtColor());
	}
	
	@Override
	public int getDefaultWidth()
	{
		return MC.font.width(text) + 8;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return 11;
	}
}
