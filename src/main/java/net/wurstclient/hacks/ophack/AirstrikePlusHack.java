/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.ophack;

import net.wurstclient.Category;

import java.util.Random;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

public final class AirstrikePlusHack extends Hack implements UpdateListener
{
	private final TextFieldSetting entity =
		new TextFieldSetting("Entity", "Entity to spawn.", "fireball");
	private final TextFieldSetting customName =
		new TextFieldSetting("Custom name", "Entity name.", "Cevapi");
	private final SliderSetting minRange = new SliderSetting("Minimum range",
		"Minimum horizontal offset.", 0, 0, 100, 1, ValueDisplay.INTEGER);
	private final SliderSetting maxRange = new SliderSetting("Maximum range",
		"Maximum horizontal offset.", 30, 1, 100, 1, ValueDisplay.INTEGER);
	private final SliderSetting height = new SliderSetting("Height above head",
		"Spawn height.", 20, -63, 319, 1, ValueDisplay.INTEGER);
	private final SliderSetting delay = new SliderSetting("Delay",
		"Ticks between strikes.", 3, 3, 20, 1, ValueDisplay.INTEGER);
	private final SliderSetting concurrent =
		new SliderSetting("Concurrent spawns", "Entities per strike.", 1, 1,
			100, 1, ValueDisplay.INTEGER);
	private final SliderSetting power = new SliderSetting("Explosion power",
		"Fireball/creeper power.", 10, 1, 127, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting everyone = new CheckboxSetting(
		"Airstrike everyone", "Run the summon at every player.", false);
	private final CheckboxSetting automatic =
		new CheckboxSetting("Automatic", "Repeat while enabled.", true);
	private int ticks;
	private final Random random = new Random();
	
	public AirstrikePlusHack()
	{
		super("Airstrike+",
			"Summons configurable entities above the player or every player.",
			false);
		setCategory(Category.CREATIVE_OP);
		addSetting(entity);
		addSetting(customName);
		addSetting(minRange);
		addSetting(maxRange);
		addSetting(height);
		addSetting(delay);
		addSetting(concurrent);
		addSetting(power);
		addSetting(everyone);
		addSetting(automatic);
	}
	
	@Override
	protected void onEnable()
	{
		ticks = delay.getValueI();
		EVENTS.add(UpdateListener.class, this);
		strike();
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
			strike();
		}
	}
	
	private void strike()
	{
		if(MC.player == null || MC.getConnection() == null)
			return;
		String prefix =
			everyone.isChecked() ? "execute at @a run " : "execute at @s run ";
		for(int i = 0; i < concurrent.getValueI(); i++)
		{
			int range = minRange.getValueI();
			if(maxRange.getValueI() > range)
				range += random.nextInt(maxRange.getValueI() - range + 1);
			int x = random.nextInt(range * 2 + 1) - range;
			int z = random.nextInt(range * 2 + 1) - range;
			String command = prefix + "summon " + entity.getValue() + " ~" + x
				+ " ~" + height.getValueI() + " ~" + z + " {CustomName:'"
				+ customName.getValue() + "',ExplosionPower:"
				+ power.getValueI() + "}";
			if(command.length() <= 256)
				MC.getConnection().sendCommand(command);
		}
	}
}
