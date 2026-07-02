/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.keymap;

import com.mojang.blaze3d.platform.InputConstants;

public record VisualKey(String label, InputConstants.Key key, float x, float y,
	float width, float height, VisualKeyCategory category)
{
	public boolean contains(double mouseX, double mouseY)
	{
		return mouseX >= x && mouseX <= x + width && mouseY >= y
			&& mouseY <= y + height;
	}
	
	public boolean isMouse()
	{
		return key.getType() == InputConstants.Type.MOUSE;
	}
	
	public enum VisualKeyCategory
	{
		FUNCTION,
		MAIN,
		NAVIGATION,
		ARROW,
		NUMPAD,
		MOUSE
	}
}
