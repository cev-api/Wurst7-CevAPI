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

public final class HandOfGodHack extends Hack implements UpdateListener
{
	private final TextFieldSetting block = new TextFieldSetting("Block",
		"Block placed around the target.", "lava");
	private final TextFieldSetting replaceBlock = new TextFieldSetting(
		"Replace block", "Optional replace target.", "grass_block");
	private final SliderSetting width = new SliderSetting("Width",
		"Fill width.", 17, 1, 90, 1, ValueDisplay.INTEGER);
	private final SliderSetting height = new SliderSetting("Height",
		"Fill height.", 11, 1, 90, 1, ValueDisplay.INTEGER);
	private final SliderSetting depth = new SliderSetting("Depth",
		"Fill depth.", 17, 1, 90, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting replace = new CheckboxSetting("Replace",
		"Replace only the configured block.", false);
	private final CheckboxSetting lightning = new CheckboxSetting("Lightning",
		"Summon lightning at the target.", true);
	private final CheckboxSetting automatic =
		new CheckboxSetting("Automatic", "Repeat the action.", false);
	private final SliderSetting delay = new SliderSetting("Delay",
		"Ticks between actions.", 3, 3, 20, 1, ValueDisplay.INTEGER);
	private int ticks;
	
	public HandOfGodHack()
	{
		super("HandOfGod",
			"Fills a target area and optionally strikes it with lightning.",
			false);
		setCategory(Category.CREATIVE_OP);
		addSetting(block);
		addSetting(replaceBlock);
		addSetting(width);
		addSetting(height);
		addSetting(depth);
		addSetting(replace);
		addSetting(lightning);
		addSetting(automatic);
		addSetting(delay);
	}
	
	@Override
	protected void onEnable()
	{
		ticks = delay.getValueI();
		EVENTS.add(UpdateListener.class, this);
		invoke();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(automatic.isChecked() && ticks++ >= delay.getValueI())
		{
			ticks = 0;
			invoke();
		}
	}
	
	private void invoke()
	{
		if(MC.getConnection() == null)
			return;
		String fill = "execute at @s run fill ~-" + width.getValueI() + " ~-"
			+ height.getValueI() + " ~-" + depth.getValueI() + " ~"
			+ width.getValueI() + " ~" + height.getValueI() + " ~"
			+ depth.getValueI() + " " + block.getValue();
		if(replace.isChecked())
			fill += " replace " + replaceBlock.getValue();
		if(fill.length() <= 256)
			MC.getConnection().sendCommand(fill);
		if(lightning.isChecked())
			MC.getConnection()
				.sendCommand("execute at @s run summon lightning_bolt ~ ~ ~");
	}
}
