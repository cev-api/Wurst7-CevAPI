/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.modern;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import org.lwjgl.glfw.GLFW;
import net.wurstclient.clickgui.Component;
import net.wurstclient.util.RenderUtils;

/** Compact in-window navigation for real SettingGroup metadata. */
public final class ModernSectionTabs extends Component
{
	private final ModernSettingsWindow window;
	
	public ModernSectionTabs(ModernSettingsWindow window)
	{
		this.window = window;
		setHeight(getDefaultHeight());
	}
	
	@Override
	public void handleMouseClick(double mouseX, double mouseY, int button,
		MouseButtonEvent context)
	{
		if(button != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		int x = getX();
		for(String label : labels())
		{
			int width = MC.font.width(label) + 9;
			if(mouseX >= x && mouseX < x + width)
			{
				if(!label.equals(window.getSelectedSection()))
					window.selectSection(label);
				return;
			}
			x += width + 2;
		}
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX,
		int mouseY, float partialTicks)
	{
		int x = getX();
		for(String label : labels())
		{
			int width = MC.font.width(label) + 9;
			boolean selected = label.equals(window.getSelectedSection());
			boolean hovering = mouseX >= x && mouseX < x + width
				&& mouseY >= getY() && mouseY < getY() + getHeight();
			int tabColor =
				RenderUtils.toIntColor(WURST.getGui().getDropdownButtonColor(),
					WURST.getGui().getOpacity() * (hovering ? 1.15F : 1F));
			context.fill(x, getY(), x + width, getY() + getHeight(), tabColor);
			if(selected)
				context.fill(x + 2, getY() + getHeight() - 2, x + width - 2,
					getY() + getHeight(),
					RenderUtils.toIntColor(WURST.getGui().getAcColor(),
						WURST.getGui().getOpacity()));
			int textY =
				Math.round(getY() + (getHeight() - MC.font.lineHeight) / 2F);
			context.text(MC.font, label, x + 4, textY,
				WURST.getGui().getTxtColor(), false);
			x += width + 2;
		}
	}
	
	private String[] labels()
	{
		String[] labels = new String[window.getSections().size() + 1];
		labels[0] = "General";
		for(int i = 0; i < window.getSections().size(); i++)
			labels[i + 1] = window.getSections().get(i).getName();
		return labels;
	}
	
	@Override
	public int getDefaultWidth()
	{
		int width = 0;
		for(String label : labels())
			width += MC.font.width(label) + 11;
		return Math.max(140, width);
	}
	
	@Override
	public int getDefaultHeight()
	{
		return 14;
	}
}
