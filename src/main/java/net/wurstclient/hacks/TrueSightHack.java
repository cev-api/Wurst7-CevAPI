/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.settings.filters.*;

@SearchTags({"true sight"})
public final class TrueSightHack extends Hack
{
	private final EntityFilterList entityFilters =
		new EntityFilterList(FilterHostileSetting.genericVision(false),
			FilterNeutralSetting
				.genericVision(AttackDetectingEntityFilter.Mode.OFF),
			FilterPassiveSetting.genericVision(false),
			FilterPassiveWaterSetting.genericVision(false),
			FilterBatsSetting.genericVision(false),
			FilterSlimesSetting.genericVision(false),
			FilterPetsSetting.genericVision(false),
			FilterVillagersSetting.genericVision(false),
			FilterZombieVillagersSetting.genericVision(false),
			FilterGolemsSetting.genericVision(false),
			FilterPiglinsSetting
				.genericVision(AttackDetectingEntityFilter.Mode.OFF),
			FilterZombiePiglinsSetting
				.genericVision(AttackDetectingEntityFilter.Mode.OFF),
			FilterEndermenSetting
				.genericVision(AttackDetectingEntityFilter.Mode.OFF),
			FilterShulkersSetting.genericVision(false),
			FilterAllaysSetting.genericVision(false),
			FilterNamedSetting.genericVision(false),
			FilterArmorStandsSetting.genericVision(false));
	private final CheckboxSetting glowInvisible = new CheckboxSetting(
		"Glow invisible entities",
		"Adds a glow outline around invisible entities that pass your filters.",
		false);
	private final ColorSetting glowColor = new ColorSetting("Glow color",
		"Color used for the glow outline.", new Color(0, 255, 255));
	
	public TrueSightHack()
	{
		super("TrueSight");
		setCategory(Category.RENDER);
		addSetting(glowInvisible);
		addSetting(glowColor);
		entityFilters.forEach(this::addSetting);
	}
	
	public boolean shouldBeVisible(Entity entity)
	{
		return isEnabled() && entityFilters.testOne(entity);
	}
	
	public Integer getGlowColor(LivingEntity entity)
	{
		if(!glowInvisible.isChecked())
			return null;
		
		if(!isEnabled() || !shouldBeVisible(entity))
			return null;
		
		if(!entity.isInvisible())
			return null;
		
		return glowColor.getColorI();
	}
	
	// See EntityMixin.onIsInvisibleTo()
}
