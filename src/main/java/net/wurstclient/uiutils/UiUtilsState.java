/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.UiUtilsHack;

public final class UiUtilsState
{
	public static boolean sendUiPackets = true;
	public static boolean delayUiPackets = false;
	public static boolean shouldEditSign = true;
	
	public static final List<Packet<?>> delayedUiPackets = new ArrayList<>();
	
	public static Screen storedScreen;
	public static AbstractContainerMenu storedMenu;
	public static final Map<String, Screen> savedScreens = new HashMap<>();
	public static final Map<String, AbstractContainerMenu> savedMenus =
		new HashMap<>();
	
	public static boolean enabled = true;
	public static boolean skipNextContainerRemoval = false;
	public static boolean fabricateOverlayOpen = false;
	public static int fabricateOverlayX = -1;
	public static int fabricateOverlayY = -1;
	public static int spamCount = 1;
	public static String commandPrefix = ",";
	
	private UiUtilsState()
	{
		
	}
	
	public static boolean isUiEnabled()
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return false;
		
		UiUtilsHack hack;
		try
		{
			hack = WurstClient.INSTANCE.getHax().uiUtilsHack;
		}catch(Throwable t)
		{
			return false;
		}
		
		return hack != null && hack.isEnabled() && enabled;
	}
}
