/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui;

/**
 * Minimal interface for ClickGUI components that want to capture keyboard
 * input. Components can request focus through {@link ClickGui} and receive key
 * and char events without opening a fullscreen screen.
 */
public interface KeyboardInput
{
	boolean onKeyPressed(int keyCode, int scanCode, int modifiers);
	
	boolean onCharTyped(char chr, int modifiers);
	
	void onKeyboardFocusLost();
}
