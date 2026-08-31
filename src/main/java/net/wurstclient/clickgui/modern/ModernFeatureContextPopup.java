/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.modern;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.wurstclient.WurstClient;
import net.wurstclient.clickgui.ClickGui;
import net.wurstclient.clickgui.Popup;
import net.wurstclient.util.RenderUtils;
import org.lwjgl.glfw.GLFW;

/** Small Phase 6 context menu containing only safe, existing actions. */
public final class ModernFeatureContextPopup extends Popup
{
	private static final ClickGui GUI = WurstClient.INSTANCE.getGui();
	private static final String[] ACTIONS =
		{"Open Settings", "Toggle", "Favorite"};
	private final ModernFeatureButton button;
	
	public ModernFeatureContextPopup(ModernFeatureButton button)
	{
		super(button);
		this.button = button;
		setWidth(getDefaultWidth());
		setHeight(getDefaultHeight());
		setX(button.getWidth() - getWidth());
		setY(button.getHeight());
	}
	
	@Override
	public void handleMouseClick(int mouseX, int mouseY, int mouseButton)
	{
		if(mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT)
			return;
		int row = (mouseY - getY()) / 13;
		if(row == 0)
			button.openSettingsWindow();
		else if(row == 1)
			button.toggleFeature();
		else if(row == 2)
			button.toggleFavorite();
		close();
	}
	
	@Override
	public void render(GuiGraphicsExtractor context, int mouseX, int mouseY)
	{
		int x1 = getX();
		int y1 = getY();
		float opacity = GUI.getOpacity();
		context.fill(x1, y1, x1 + getWidth(), y1 + getHeight(),
			RenderUtils.toIntColor(GUI.getBgColor(), opacity));
		for(int i = 0; i < ACTIONS.length; i++)
		{
			int y = y1 + i * 13;
			boolean hovering = mouseX >= x1 && mouseX < x1 + getWidth()
				&& mouseY >= y && mouseY < y + 13;
			if(hovering)
				context.fill(x1, y, x1 + getWidth(), y + 13,
					RenderUtils.toIntColor(GUI.getAcColor(), opacity * 0.65F));
			int textY =
				Math.round(y + (13 - WurstClient.MC.font.lineHeight) / 2F);
			context.text(WurstClient.MC.font, ACTIONS[i], x1 + 5, textY,
				GUI.getTxtColor(), false);
		}
	}
	
	@Override
	public int getDefaultWidth()
	{
		return 88;
	}
	
	@Override
	public int getDefaultHeight()
	{
		return ACTIONS.length * 13;
	}
}
