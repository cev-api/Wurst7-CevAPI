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

public final class ExplosionAuraHack extends Hack implements UpdateListener
{
	private final SliderSetting delay = new SliderSetting("Explosion delay",
		"Ticks between explosion commands.", 5, 0, 100, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting power = new SliderSetting("Power",
		"Explosion power.", 10, 1, 127, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting attackClick = new CheckboxSetting(
		"Attack click", "Only trigger while attacking.", false);
	private final CheckboxSetting repeat =
		new CheckboxSetting("Automatic", "Repeat around the player.", false);
	private int ticks;
	
	public ExplosionAuraHack()
	{
		super("ExplosionAura",
			"Summons explosive entities around the player or target.", false);
		setCategory(Category.CREATIVE_OP);
		addSetting(delay);
		addSetting(power);
		addSetting(attackClick);
		addSetting(repeat);
	}
	
	@Override
	protected void onEnable()
	{
		ticks = delay.getValueI();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.getConnection() == null)
			return;
		boolean attacking = MC.options.keyAttack.isDown();
		if(!repeat.isChecked() && attackClick.isChecked() && !attacking)
			return;
		if(attackClick.isChecked() && !attacking)
			return;
		if(ticks++ < delay.getValueI())
			return;
		ticks = 0;
		MC.getConnection().sendCommand(
			"execute at @s run summon creeper ~ ~ ~ {powered:1b,ExplosionRadius:"
				+ power.getValueI() + ",Fuse:0}");
	}
}
