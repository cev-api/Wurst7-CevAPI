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

public final class VoiderPlusHack extends Hack implements UpdateListener
{
	private final CheckboxSetting permissionHold =
		new CheckboxSetting("Permission level hold",
			"Wait for operator access before sending.", false);
	private final TextFieldSetting block = new TextFieldSetting("Block",
		"Block used for the voiding layers.", "air");
	private final SliderSetting radius = new SliderSetting("Radius",
		"Half-width of each fill layer.", 45, 1, 90, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting usePlayerY = new CheckboxSetting(
		"Use player Y", "Start at the player's current Y level.", true);
	private final SliderSetting playerHeight =
		new SliderSetting("Player height", "Height offset from the player.", 0,
			-64, 128, 1, ValueDisplay.INTEGER);
	private final SliderSetting minHeight = new SliderSetting("Minimum height",
		"Lowest layer to fill.", -64, -64, 319, 1, ValueDisplay.INTEGER);
	private final SliderSetting maxHeight = new SliderSetting("Maximum height",
		"Highest layer to fill.", 128, -64, 319, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting teleportForward = new CheckboxSetting(
		"Teleport forward", "Move forward after each layer.", false);
	private final CheckboxSetting disableWhenDone = new CheckboxSetting(
		"Disable when done", "Disable after the final layer.", false);
	private int currentY;
	private int endY;
	private int direction;
	
	public VoiderPlusHack()
	{
		super("Voider+",
			"Fills horizontal layers using the original CevAPI voider workflow.",
			false);
		setCategory(Category.CREATIVE_OP);
		addSetting(permissionHold);
		addSetting(block);
		addSetting(radius);
		addSetting(usePlayerY);
		addSetting(playerHeight);
		addSetting(minHeight);
		addSetting(maxHeight);
		addSetting(teleportForward);
		addSetting(disableWhenDone);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.player == null)
			return;
		currentY = usePlayerY.isChecked()
			? MC.player.getBlockY() + playerHeight.getValueI()
			: maxHeight.getValueI();
		endY = minHeight.getValueI();
		direction = currentY >= endY ? -1 : 1;
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
		if((direction < 0 && currentY < endY)
			|| (direction > 0 && currentY > endY))
		{
			if(disableWhenDone.isChecked())
				setEnabled(false);
			return;
		}
		int x = MC.player.getBlockX();
		int z = MC.player.getBlockZ();
		String command = "fill " + (x - radius.getValueI()) + " " + currentY
			+ " " + (z - radius.getValueI()) + " " + (x + radius.getValueI())
			+ " " + currentY + " " + (z + radius.getValueI()) + " "
			+ block.getValue();
		if(command.length() <= 256)
			MC.getConnection().sendCommand(command);
		currentY += direction;
	}
}
