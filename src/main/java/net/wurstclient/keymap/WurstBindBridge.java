/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.keymap;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.wurstclient.WurstClient;
import net.wurstclient.keybinds.Keybind;

public final class WurstBindBridge
{
	public String getCommandForKey(InputConstants.Key key)
	{
		if(key == null)
			return null;
		
		return WurstClient.INSTANCE.getKeybinds().getCommands(key.getName());
	}
	
	public void setCommandForKey(InputConstants.Key key, String commands)
	{
		if(key == null)
			return;
		
		WurstClient.INSTANCE.getKeybinds().add(key.getName(), commands);
	}
	
	public void clearCommandForKey(InputConstants.Key key)
	{
		if(key == null)
			return;
		
		WurstClient.INSTANCE.getKeybinds().remove(key.getName());
	}
	
	public List<Keybind> getAllWurstBinds()
	{
		return WurstClient.INSTANCE.getKeybinds().getAllKeybinds();
	}
}
