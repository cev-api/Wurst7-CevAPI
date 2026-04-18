/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.xpgui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.wurstclient.hack.Hack;

public final class XpWindowManager
{
	private final ArrayList<XpModuleWindow> windows = new ArrayList<>();
	private int spawnIndex;
	private XpModuleWindow focusedWindow;
	
	public List<XpModuleWindow> getWindows()
	{
		return Collections.unmodifiableList(windows);
	}
	
	public XpModuleWindow getFocusedWindow()
	{
		if(focusedWindow != null && windows.contains(focusedWindow))
			return focusedWindow;
		if(windows.isEmpty())
			return null;
		focusedWindow = windows.get(windows.size() - 1);
		return focusedWindow;
	}
	
	public XpModuleWindow getWindow(Hack hack)
	{
		for(XpModuleWindow window : windows)
			if(window.getHack() == hack)
				return window;
			
		return null;
	}
	
	public XpModuleWindow openWindow(Hack hack, int screenWidth,
		int screenHeight, int taskbarHeight)
	{
		XpModuleWindow existing = getWindow(hack);
		if(existing != null)
		{
			existing.setMinimized(false);
			focusWindow(existing);
			return existing;
		}
		
		int width = Math.min(360, Math.max(260, screenWidth - 32));
		int height =
			Math.min(300, Math.max(190, screenHeight - taskbarHeight - 56));
		
		int x = 24 + (spawnIndex % 7) * 24;
		int y = 20 + (spawnIndex % 6) * 20;
		spawnIndex++;
		
		int maxX = Math.max(4, screenWidth - width - 4);
		int maxY = Math.max(4, screenHeight - taskbarHeight - height - 4);
		x = Math.min(x, maxX);
		y = Math.min(y, maxY);
		
		XpModuleWindow window = new XpModuleWindow(hack, x, y, width, height);
		windows.add(window);
		focusedWindow = window;
		return window;
	}
	
	public void closeWindow(XpModuleWindow window)
	{
		windows.remove(window);
		if(focusedWindow == window)
			focusedWindow =
				windows.isEmpty() ? null : windows.get(windows.size() - 1);
	}
	
	public void focusWindow(XpModuleWindow window)
	{
		if(window == null)
			return;
		
		if(windows.contains(window))
			focusedWindow = window;
	}
	
	public void toggleFromTaskbar(XpModuleWindow window)
	{
		if(window == null)
			return;
		
		XpModuleWindow focused = getFocusedWindow();
		if(window == focused && !window.isMinimized())
		{
			window.setMinimized(true);
			return;
		}
		
		window.setMinimized(false);
		focusWindow(window);
	}
}
