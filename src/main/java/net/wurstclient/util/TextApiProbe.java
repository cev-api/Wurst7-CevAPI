/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.client.gui.Font;

/**
 * Temporary compile-time probe to discover Font.PreparedText API in 26.2.
 */
class TextApiProbe
{
	static void probe(Font font)
	{
		Font.PreparedText pt =
			font.prepareText("test", 0f, 0f, -1, false, 0xF000F0);
		// Compiler will reveal all methods on pt here
	}
}
