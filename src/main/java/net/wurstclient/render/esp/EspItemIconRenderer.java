/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.render.esp;

import java.util.HashMap;
import org.joml.Matrix3x2f;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.item.TrackingItemStackRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.WurstClient;

/** Deduplicates model resolution, not icons. Nothing survives a GUI frame. */
public final class EspItemIconRenderer
{
	private final HashMap<Key, TrackingItemStackRenderState> models =
		new HashMap<>();
	
	private record Key(Item item, int count, DataComponentPatch components,
		int popTime)
	{}
	
	public void clear()
	{
		models.clear();
	}
	
	public void draw(GuiGraphicsExtractor context, ItemStack stack, int x,
		int y)
	{
		if(stack.isEmpty())
			return;
		Key key = new Key(stack.getItem(), stack.getCount(),
			stack.getComponentsPatch(), stack.getPopTime());
		TrackingItemStackRenderState state = models.get(key);
		if(state == null)
		{
			state = new TrackingItemStackRenderState();
			var mc = WurstClient.MC;
			mc.getItemModelResolver().updateForTopItem(state, stack,
				ItemDisplayContext.GUI, mc.level, mc.player, 0);
			if(!state.isAnimated())
				models.put(key, state);
		}
		// This is the same state submission as GuiGraphicsExtractor.item():
		// a distinct pose and screen bounds for EVERY icon, in original order.
		context.guiRenderState
			.addItem(new GuiItemRenderState(new Matrix3x2f(context.pose()),
				state, x, y, context.scissorStack.peek()));
	}
}
