/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.ophack;

import net.wurstclient.Category;

import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.TextFieldSetting;

public final class NbtEditorHack extends Hack
{
	private final TextFieldSetting mode =
		new TextFieldSetting("Mode", "Entity, Item, or Command.", "Entity");
	private final TextFieldSetting entity =
		new TextFieldSetting("Entity", "Entity type to summon.", "wither");
	private final TextFieldSetting item =
		new TextFieldSetting("Item", "Item id to give.", "cod");
	private final TextFieldSetting nbt = new TextFieldSetting("NBT",
		"NBT/ components payload.", "{Invulnerable:1b}");
	private final TextFieldSetting effect = new TextFieldSetting("Effect",
		"Effect id for potion mode.", "strong_harming");
	private final CheckboxSetting copyStack = new CheckboxSetting("Copy stack",
		"Compatibility option for item mode.", false);
	
	public NbtEditorHack()
	{
		super("NbtEditor", "Creates configurable entity or item NBT payloads.",
			false);
		setCategory(Category.CREATIVE_OP);
		addSetting(mode);
		addSetting(entity);
		addSetting(item);
		addSetting(nbt);
		addSetting(effect);
		addSetting(copyStack);
	}
	
	@Override
	protected void onEnable()
	{
		if(MC.getConnection() == null)
		{
			setEnabled(false);
			return;
		}
		String value;
		if(mode.getValue().equalsIgnoreCase("item"))
			value = "give @s " + item.getValue() + nbt.getValue();
		else if(mode.getValue().equalsIgnoreCase("command"))
			value = nbt.getValue();
		else
			value = "execute at @s run summon " + entity.getValue() + " ~ ~ ~ "
				+ nbt.getValue();
		while(value.startsWith("/"))
			value = value.substring(1);
		if(value.length() <= 256)
			MC.getConnection().sendCommand(value);
		setEnabled(false);
	}
}
