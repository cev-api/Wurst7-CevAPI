/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.ophack;

import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public abstract class OpHack extends Hack implements UpdateListener
{
	private final TextFieldSetting command;
	private final SliderSetting repeat;
	private final CheckboxSetting repeatEnabled;
	private int ticks;
	
	protected OpHack(String name, String description, String defaultCommand)
	{
		super(name, description, false);
		setCategory("Creative/Op (CevAPI)");
		command = new TextFieldSetting("Command",
			"Command to send when this module is enabled. Leading slash is optional.",
			defaultCommand);
		repeat = new SliderSetting("Delay", "Ticks between repeated commands.",
			20, 1, 200, 1, ValueDisplay.INTEGER);
		repeatEnabled = new CheckboxSetting("Repeat",
			"Repeat the command while this module is enabled.", false);
		addSetting(command);
		addSetting(repeat);
		addSetting(repeatEnabled);
	}
	
	@Override
	protected void onEnable()
	{
		ticks = 0;
		EVENTS.add(UpdateListener.class, this);
		send();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.getConnection() == null
			|| !repeatEnabled.isChecked())
			return;
		if(++ticks >= repeat.getValueI())
		{
			ticks = 0;
			send();
		}
	}
	
	protected final void send()
	{
		if(MC.getConnection() == null)
			return;
		String value = command.getValue();
		if(value == null)
			return;
		value = value.trim();
		while(value.startsWith("/"))
			value = value.substring(1).trim();
		if(!value.isBlank() && value.length() <= 256)
			MC.getConnection().sendCommand(value);
	}
}
