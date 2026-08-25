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
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public final class BoomPlusHack extends Hack implements UpdateListener
{
	private final TextFieldSetting entity =
		new TextFieldSetting("Entity", "Entity to summon.", "fireball");
	private final TextFieldSetting name =
		new TextFieldSetting("Custom name", "Entity custom name.", "Cevapi");
	private final SliderSetting power = new SliderSetting("Explosion power",
		"Explosion radius/power.", 10, 1, 127, 1, ValueDisplay.INTEGER);
	private final SliderSetting speed = new SliderSetting("Speed",
		"Motion magnitude.", 5, 0, 10, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting automatic =
		new CheckboxSetting("Automatic", "Repeat while enabled.", false);
	private final SliderSetting delay = new SliderSetting("Delay",
		"Ticks between spawns.", 3, 3, 20, 1, ValueDisplay.INTEGER);
	private int ticks;
	
	public BoomPlusHack()
	{
		super("Boom+", "Summons configurable explosive entities.", false);
		setCategory(Category.CREATIVE_OP);
		addSetting(entity);
		addSetting(name);
		addSetting(power);
		addSetting(speed);
		addSetting(automatic);
		addSetting(delay);
	}
	
	@Override
	protected void onEnable()
	{
		ticks = delay.getValueI();
		EVENTS.add(UpdateListener.class, this);
		summon();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(!automatic.isChecked())
			return;
		if(ticks++ >= delay.getValueI())
		{
			ticks = 0;
			summon();
		}
	}
	
	private void summon()
	{
		if(MC.getConnection() == null)
			return;
		String command = "execute at @s run summon " + entity.getValue()
			+ " ~ ~2 ~ {ExplosionPower:" + power.getValueI() + ",Motion:[0.0,-"
			+ speed.getValueI() + ".0,0.0]}";
		if(command.length() <= 256)
			MC.getConnection().sendCommand(command);
	}
}
