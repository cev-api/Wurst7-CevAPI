/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;

@SearchTags({"entity control", "horse control", "ride control",
	"control rideables"})
public final class EntityControlHack extends Hack
{
	private final CheckboxSetting enforceSaddled = new CheckboxSetting(
		"Enforce saddled",
		"Lets you control rideable entities as if they had a saddle.", true);
	private final CheckboxSetting enforceJumpStrength =
		new CheckboxSetting("Enforce jump strength",
			"Gives controllable horses a minimum jump strength.", true);
	private final CheckboxSetting enforceMobControlled =
		new CheckboxSetting("Enforce control",
			"Forces mounted horses to accept rider movement input.", true);
	
	public EntityControlHack()
	{
		super("EntityControl",
			"Allows you to control rideable entities without a saddle.", false);
		setCategory(Category.MOVEMENT);
		addSetting(enforceSaddled);
		addSetting(enforceJumpStrength);
		addSetting(enforceMobControlled);
	}
	
	public boolean shouldEnforceSaddled()
	{
		return isEnabled() && enforceSaddled.isChecked();
	}
	
	public boolean shouldEnforceJumpStrength()
	{
		return isEnabled() && enforceJumpStrength.isChecked();
	}
	
	public boolean shouldEnforceMobControlled()
	{
		return isEnabled() && enforceMobControlled.isChecked();
	}
}
