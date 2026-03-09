/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.events;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import net.wurstclient.event.Event;
import net.wurstclient.event.Listener;
import net.wurstclient.util.HackPerformanceTracker;

public interface RenderListener extends Listener
{
	public void onRender(PoseStack matrixStack, float partialTicks);
	
	public static class RenderEvent extends Event<RenderListener>
	{
		private final PoseStack matrixStack;
		private final float partialTicks;
		
		public RenderEvent(PoseStack matrixStack, float partialTicks)
		{
			this.matrixStack = matrixStack;
			this.partialTicks = partialTicks;
		}
		
		@Override
		public void fire(ArrayList<RenderListener> listeners)
		{
			boolean profile = HackPerformanceTracker.shouldProfile();
			for(RenderListener listener : listeners)
			{
				if(!profile)
				{
					listener.onRender(matrixStack, partialTicks);
					continue;
				}
				
				long start = System.nanoTime();
				try
				{
					listener.onRender(matrixStack, partialTicks);
					
				}finally
				{
					HackPerformanceTracker.record(listener,
						HackPerformanceTracker.Phase.RENDER,
						System.nanoTime() - start);
				}
			}
		}
		
		@Override
		public Class<RenderListener> getListenerType()
		{
			return RenderListener.class;
		}
	}
}
