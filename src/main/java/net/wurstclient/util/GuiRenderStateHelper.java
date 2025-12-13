/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Provides no-op shims for {@code GuiGraphics.guiRenderState} calls that are
 * not available in Minecraft 1.21.1 yet.
 */
public final class GuiRenderStateHelper
{
	private GuiRenderStateHelper()
	{}
	
	public static void up(GuiGraphics context)
	{
		// 1.21.1 doesn't expose guiRenderState. We don't need to adjust any
		// render states here, so this is a no-op.
	}
	
	public static void down(GuiGraphics context)
	{
		// No-op shim for older versions.
	}
}
