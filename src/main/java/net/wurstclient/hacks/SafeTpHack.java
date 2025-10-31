/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"safe tp", "safe teleport"})
public final class SafeTpHack extends Hack implements UpdateListener
{
	private final TextFieldSetting command = new TextFieldSetting("Command",
		"description.wurst.setting.safetp.command", "/t spawn",
		value -> value != null && !value.trim().isEmpty());
	private final SliderSetting waitTime = new SliderSetting("Wait time",
		"description.wurst.setting.safetp.wait_time", 5.5, 1, 15, 0.25,
		ValueDisplay.DECIMAL.withSuffix("s"));
	
	private int ticksRemaining;
	private boolean weActivatedBlink;
	
	public SafeTpHack()
	{
		super("SafeTP");
		setCategory(Category.OTHER);
		addSetting(command);
		addSetting(waitTime);
	}
	
	@Override
	public String getRenderName()
	{
		if(isEnabled())
		{
			int secondsLeft =
				Math.max((int)Math.ceil(ticksRemaining / 20.0), 0);
			return getName() + " [" + secondsLeft + "s]";
		}
		
		return super.getRenderName();
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player == null)
		{
			setEnabled(false);
			return;
		}
		
		ClientPlayNetworkHandler netHandler = MC.getNetworkHandler();
		if(netHandler == null)
		{
			setEnabled(false);
			return;
		}
		
		String value = command.getValue().trim();
		if(value.isEmpty())
		{
			ChatUtils.error("SafeTP command cannot be empty.");
			setEnabled(false);
			return;
		}
		
		BlinkHack blinkHack = WURST.getHax().blinkHack;
		boolean blinkAlreadyEnabled = blinkHack.isEnabled();
		weActivatedBlink = false;
		
		if(!blinkAlreadyEnabled)
		{
			blinkHack.setEnabled(true);
			if(!blinkHack.isEnabled())
			{
				ChatUtils.error("SafeTP could not enable Blink.");
				setEnabled(false);
				return;
			}
			
			weActivatedBlink = true;
		}
		
		ticksRemaining = Math.max((int)Math.ceil(waitTime.getValue() * 20), 1);
		
		String commandToSend =
			value.startsWith("/") ? value.substring(1) : value;
		commandToSend = commandToSend.trim();
		if(commandToSend.isEmpty())
		{
			ChatUtils.error("SafeTP command cannot be empty.");
			setEnabled(false);
			return;
		}
		
		netHandler.sendChatCommand(commandToSend);
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		ticksRemaining = 0;
		
		BlinkHack blinkHack = WURST.getHax().blinkHack;
		if(weActivatedBlink && blinkHack.isEnabled())
			blinkHack.cancel();
		
		weActivatedBlink = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.getNetworkHandler() == null)
		{
			setEnabled(false);
			return;
		}
		
		BlinkHack blinkHack = WURST.getHax().blinkHack;
		if(!blinkHack.isEnabled())
		{
			setEnabled(false);
			return;
		}
		
		if(ticksRemaining <= 0)
		{
			setEnabled(false);
			return;
		}
		
		ticksRemaining--;
		
		if(ticksRemaining <= 0)
			setEnabled(false);
	}
}
