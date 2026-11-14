/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.events;

import java.util.ArrayList;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.wurstclient.event.Event;
import net.wurstclient.event.Listener;
import net.wurstclient.util.HackPerformanceTracker;

public interface GUIRenderListener extends Listener
{
	public void onRenderGUI(GuiGraphicsExtractor extractor, float partialTicks);
	
	public static class GUIRenderEvent extends Event<GUIRenderListener>
	{
		private final float partialTicks;
		private final GuiGraphicsExtractor extractor;
		
		public GUIRenderEvent(GuiGraphicsExtractor extractor,
			float partialTicks)
		{
			this.extractor = extractor;
			this.partialTicks = partialTicks;
		}
		
		@Override
		public void fire(ArrayList<GUIRenderListener> listeners)
		{
			boolean profile = HackPerformanceTracker.shouldProfile();
			for(GUIRenderListener listener : listeners)
			{
				if(!profile)
				{
					listener.onRenderGUI(extractor, partialTicks);
					continue;
				}
				
				long start = System.nanoTime();
				try
				{
					listener.onRenderGUI(extractor, partialTicks);
					
				}finally
				{
					HackPerformanceTracker.record(listener,
						HackPerformanceTracker.Phase.GUI,
						System.nanoTime() - start);
				}
			}
		}
		
		@Override
		public Class<GUIRenderListener> getListenerType()
		{
			return GUIRenderListener.class;
		}
	}
}
