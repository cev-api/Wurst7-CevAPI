/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.settings.filters;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.cubemob.SulfurCube;

public final class FilterSulfurCubesSetting extends EntityFilterCheckbox
{
	public FilterSulfurCubesSetting(String description, boolean checked)
	{
		super("Filter sulfur cubes", description, checked);
	}
	
	@Override
	public boolean test(Entity e)
	{
		return !(e instanceof SulfurCube);
	}
	
	public static FilterSulfurCubesSetting genericCombat(boolean checked)
	{
		return new FilterSulfurCubesSetting(
			"description.wurst.setting.generic.filter_sulfur_cubes_combat",
			checked);
	}
	
	public static FilterSulfurCubesSetting genericVision(boolean checked)
	{
		return new FilterSulfurCubesSetting(
			"description.wurst.setting.generic.filter_sulfur_cubes_vision",
			checked);
	}
}
