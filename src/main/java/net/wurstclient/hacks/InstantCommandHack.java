/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public final class InstantCommandHack extends Hack implements UpdateListener
{
	private static final int RUN_TICKS = 100;
	
	private final TextFieldSetting command = new TextFieldSetting("Command",
		"Command to spam immediately on server join. Leading slash is optional.",
		"spawn");
	
	private final SliderSetting commandsPerTick =
		new SliderSetting("Commands per tick",
			"How many command packets to send each tick after joining.", 5, 1,
			100, 1, ValueDisplay.INTEGER);
	
	private int ticksLeft;
	private boolean listening;
	
	public InstantCommandHack()
	{
		super("InstantCommand",
			"Spams a configured command immediately on server join.", false);
		addSetting(command);
		addSetting(commandsPerTick);
	}
	
	public void onServerJoin()
	{
		if(!isEnabled())
			return;
		
		ticksLeft = RUN_TICKS;
		
		if(!listening)
		{
			EVENTS.add(UpdateListener.class, this);
			listening = true;
		}
		
		spamCommand();
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player != null)
			onServerJoin();
	}
	
	@Override
	protected void onDisable()
	{
		stop();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.getConnection() == null)
		{
			stop();
			return;
		}
		
		spamCommand();
		
		ticksLeft--;
		if(ticksLeft <= 0)
			stop();
	}
	
	private void spamCommand()
	{
		if(MC.getConnection() == null)
			return;
		
		String normalized = normalize(command.getValue());
		if(normalized.isBlank())
			return;
		
		for(int i = 0; i < commandsPerTick.getValueI(); i++)
			MC.getConnection().sendCommand(normalized);
	}
	
	private String normalize(String value)
	{
		String trimmed = value == null ? "" : value.trim();
		while(trimmed.startsWith("/"))
			trimmed = trimmed.substring(1).trim();
		
		return trimmed;
	}
	
	private void stop()
	{
		if(!listening)
			return;
		
		EVENTS.remove(UpdateListener.class, this);
		listening = false;
		ticksLeft = 0;
	}
}
