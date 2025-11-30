/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.components;

import net.minecraft.client.gui.GuiGraphics;
import net.wurstclient.clickgui.Component;

/**
 * Simple blank component that only adds vertical spacing between other
 * components.
 */
public final class SpacerComponent extends Component
{
	private final int height;
	private final int width;
	
	public SpacerComponent(int height, int width)
	{
		this.height = height;
		this.width = width;
	}
	
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY,
		float partialTicks)
	{
		// intentionally blank
	}
	
	@Override
	public int getDefaultWidth()
	{
		return width;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return height;
	}
}
