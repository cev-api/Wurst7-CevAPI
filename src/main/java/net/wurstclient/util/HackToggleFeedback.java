/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.wurstclient.WurstClient;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.ClickGuiHack;
import net.wurstclient.hacks.NavigatorHack;

public enum HackToggleFeedback implements UpdateListener
{
	INSTANCE;
	
	private final Set<String> enabled = new LinkedHashSet<>();
	private final Set<String> disabled = new LinkedHashSet<>();
	private int delayTicks;
	
	public void queue(Hack hack, boolean isEnabled)
	{
		if(hack == null || !shouldReport(hack))
			return;
		
		if(!isChatFeedbackEnabled())
			return;
		
		String name = hack.getName();
		if(isEnabled)
		{
			disabled.remove(name);
			enabled.add(name);
		}else
		{
			enabled.remove(name);
			disabled.add(name);
		}
		
		delayTicks = 1;
	}
	
	@Override
	public void onUpdate()
	{
		if(delayTicks > 0)
		{
			delayTicks--;
			return;
		}
		
		if(enabled.isEmpty() && disabled.isEmpty())
			return;
		
		if(!isChatFeedbackEnabled() || !isPlayerReady())
		{
			enabled.clear();
			disabled.clear();
			return;
		}
		
		StringBuilder message = new StringBuilder();
		if(!enabled.isEmpty())
		{
			message.append("\u00a7aEnabled: ")
				.append(String.join(", ", enabled)).append("\u00a7r");
		}
		
		if(!disabled.isEmpty())
		{
			if(message.length() > 0)
				message.append(" | ");
			message.append("\u00a7cDisabled: ")
				.append(String.join(", ", disabled)).append("\u00a7r");
		}
		
		ChatUtils.message(message.toString());
		enabled.clear();
		disabled.clear();
	}
	
	private boolean shouldReport(Hack hack)
	{
		return !(hack instanceof NavigatorHack || hack instanceof ClickGuiHack);
	}
	
	private boolean isChatFeedbackEnabled()
	{
		WurstClient wurst = WurstClient.INSTANCE;
		if(wurst == null || wurst.getHax() == null)
			return false;
		
		if(wurst.getHax().hideWurstHack != null
			&& wurst.getHax().hideWurstHack.shouldHideToggleChatFeedback())
			return false;
		
		ClickGuiHack clickGuiHack = wurst.getHax().clickGuiHack;
		return clickGuiHack != null
			&& clickGuiHack.isHackToggleChatFeedbackEnabled();
	}
	
	private boolean isPlayerReady()
	{
		Minecraft mc = WurstClient.MC;
		return mc != null && mc.player != null && mc.gui != null;
	}
}
