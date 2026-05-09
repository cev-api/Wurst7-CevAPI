/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import net.minecraft.world.item.ItemDisplayContext;

public final class ItemStackRenderStateContext
{
	private static final ThreadLocal<ItemDisplayContext> DISPLAY_CONTEXT =
		new ThreadLocal<>();
	
	private ItemStackRenderStateContext()
	{}
	
	public static ItemDisplayContext get()
	{
		return DISPLAY_CONTEXT.get();
	}
	
	public static void set(ItemDisplayContext context)
	{
		DISPLAY_CONTEXT.set(context);
	}
	
	public static void clear()
	{
		DISPLAY_CONTEXT.remove();
	}
}
