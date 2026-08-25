/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.ophack;

import net.wurstclient.Category;

import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public final class MultiverseAnnihilatorHack extends Hack
	implements UpdateListener
{
	private final TextFieldSetting worlds = new TextFieldSetting("Worlds",
		"Semicolon-separated Multiverse world names.", "");
	private final SliderSetting delay = new SliderSetting("Delay",
		"Ticks between delete commands.", 10, 0, 100, 1, ValueDisplay.INTEGER);
	private final java.util.ArrayDeque<String> queue =
		new java.util.ArrayDeque<>();
	private int ticks;
	
	public MultiverseAnnihilatorHack()
	{
		super("MultiverseAnnihilator",
			"Deletes configured Multiverse worlds through a queued workflow.",
			false);
		setCategory(Category.CREATIVE_OP);
		addSetting(worlds);
		addSetting(delay);
	}
	
	@Override
	protected void onEnable()
	{
		queue.clear();
		queue.add("mv list");
		for(String world : worlds.getValue().split(";"))
			if(!world.trim().isBlank())
				queue.add("mv delete " + world.trim());
		ticks = delay.getValueI();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		queue.clear();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.getConnection() == null)
			return;
		if(queue.isEmpty())
		{
			setEnabled(false);
			return;
		}
		if(ticks++ < delay.getValueI())
			return;
		ticks = 0;
		MC.getConnection().sendCommand(queue.poll());
	}
}
