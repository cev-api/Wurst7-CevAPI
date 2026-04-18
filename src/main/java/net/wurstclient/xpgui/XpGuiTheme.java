/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.xpgui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public enum XpGuiTheme
{
	;
	
	public static final int TASKBAR_TOP = 0xFF3F95EA;
	public static final int TASKBAR_BOTTOM = 0xFF245EDB;
	public static final int TASKBAR_BORDER = 0xFF0D3177;
	public static final int TASKBAR_HIGHLIGHT = 0xAA9DCCFF;
	
	public static final int START_TOP = 0xFF4BD463;
	public static final int START_BOTTOM = 0xFF138A1A;
	public static final int START_BORDER = 0xFF0A5C12;
	
	public static final int WINDOW_BORDER = 0xFF184A93;
	public static final int WINDOW_LIGHT = 0xFF7BB2FF;
	public static final int WINDOW_BODY = 0xFFF2F7FF;
	public static final int WINDOW_BODY_ALT = 0xFFE5EEF9;
	public static final int TITLE_ACTIVE_TOP = 0xFF2F80E5;
	public static final int TITLE_ACTIVE_BOTTOM = 0xFF0A3EAA;
	public static final int TITLE_INACTIVE_TOP = 0xFF8DB1E8;
	public static final int TITLE_INACTIVE_BOTTOM = 0xFF4F7CC8;
	
	public static final int MENU_LEFT = 0xFFFDFDFE;
	public static final int MENU_RIGHT = 0xFFDCEAF9;
	public static final int MENU_HEADER_TOP = 0xFF2D80E4;
	public static final int MENU_HEADER_BOTTOM = 0xFF0D47B0;
	
	public static void drawBevelRect(GuiGraphicsExtractor context, int x1,
		int y1, int x2, int y2, int fill, int light, int dark)
	{
		context.fill(x1, y1, x2, y2, fill);
		context.fill(x1, y1, x2, y1 + 1, light);
		context.fill(x1, y1, x1 + 1, y2, light);
		context.fill(x1, y2 - 1, x2, y2, dark);
		context.fill(x2 - 1, y1, x2, y2, dark);
	}
	
	public static void drawWindowFrame(GuiGraphicsExtractor context, int x1,
		int y1, int x2, int y2)
	{
		context.fill(x1 - 2, y1 - 2, x2 + 2, y2 + 2, 0xB0000000);
		drawBevelRect(context, x1, y1, x2, y2, WINDOW_BODY, WINDOW_LIGHT,
			WINDOW_BORDER);
	}
	
	public static void drawTitleBar(GuiGraphicsExtractor context, int x1,
		int y1, int x2, int y2, boolean focused)
	{
		int top = focused ? TITLE_ACTIVE_TOP : TITLE_INACTIVE_TOP;
		int bottom = focused ? TITLE_ACTIVE_BOTTOM : TITLE_INACTIVE_BOTTOM;
		context.fillGradient(x1 + 1, y1 + 1, x2 - 1, y2 - 1, top, bottom);
		context.fill(x1 + 1, y1 + 1, x2 - 1, y1 + 2, 0x80FFFFFF);
	}
	
	public static void drawXpButton(GuiGraphicsExtractor context, Font font,
		String text, int x1, int y1, int x2, int y2, boolean hovered,
		boolean pressed)
	{
		int fill = pressed ? 0xFFC3D9F3 : hovered ? 0xFFD9E9FB : 0xFFECF4FF;
		drawBevelRect(context, x1, y1, x2, y2, fill, 0xFFFFFFFF, 0xFF6E8BB4);
		
		int textW = font.width(text);
		int textX = x1 + (x2 - x1 - textW) / 2;
		int textY = y1 + (y2 - y1 - 8) / 2;
		context.text(font, text, textX, textY, 0xFF10315F, false);
	}
}
