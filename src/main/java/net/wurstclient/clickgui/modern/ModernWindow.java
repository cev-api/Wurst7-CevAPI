/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.modern;

import net.wurstclient.clickgui.Window;

/**
 * Marker for Phase 1 category windows rendered by the Modern ClickGUI style.
 * Window continues to supply all movement, scrolling and title-bar behavior.
 */
public class ModernWindow extends Window
{
	public ModernWindow(String title)
	{
		super(title);
		setResizable(true);
		setMinimizable(false);
	}
}
