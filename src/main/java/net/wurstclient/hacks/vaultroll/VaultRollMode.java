/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.vaultroll;

import java.util.Locale;

public enum VaultRollMode
{
	NORMAL("normal", "Normal", "minecraft:chests/trial_chambers/reward"),
	OMINOUS("ominous", "Ominous",
		"minecraft:chests/trial_chambers/reward_ominous");
	
	private final String id;
	private final String displayName;
	private final String sequenceId;
	
	VaultRollMode(String id, String displayName, String sequenceId)
	{
		this.id = id;
		this.displayName = displayName;
		this.sequenceId = sequenceId;
	}
	
	public String id()
	{
		return id;
	}
	
	public String displayName()
	{
		return displayName;
	}
	
	public String sequenceId()
	{
		return sequenceId;
	}
	
	public static VaultRollMode parse(String input)
	{
		if(input == null)
			return null;
		String value = input.trim().toLowerCase(Locale.ROOT);
		for(VaultRollMode mode : values())
			if(mode.id.equals(value))
				return mode;
		return null;
	}
}
