/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.hack.Hack;

@SearchTags({"anti fov", "NoFov", "no fov", "NoFovChange", "no fov change",
	"field of view", "no slowness zoom"})
public final class AntiFovHack extends Hack
{
	public AntiFovHack()
	{
		super("AntiFov");
		setCategory(Category.RENDER);
	}
	
	/**
	 * Recalculates the movement speed that the field of view is based on,
	 * leaving out the modifiers that potion effects added to it. This stops
	 * Slowness from zooming the camera in and Speed from zooming it out.
	 */
	public double adjustFovMovementSpeed(AbstractClientPlayer player,
		double original)
	{
		if(!isEnabled())
			return original;
		
		AttributeInstance instance =
			player.getAttribute(Attributes.MOVEMENT_SPEED);
		if(instance == null)
			return original;
		
		Set<Identifier> effectModifiers = getEffectModifiers(player);
		if(effectModifiers.isEmpty())
			return original;
			
		// Same order as AttributeInstance.calculateValue(), just without the
		// modifiers that the effects are responsible for.
		double base = instance.getBaseValue();
		double multipliedBase = 0;
		double multipliedTotal = 1;
		for(AttributeModifier modifier : instance.getModifiers())
		{
			if(effectModifiers.contains(modifier.id()))
				continue;
			
			switch(modifier.operation())
			{
				case ADD_VALUE -> base += modifier.amount();
				case ADD_MULTIPLIED_BASE -> multipliedBase += modifier.amount();
				case ADD_MULTIPLIED_TOTAL -> multipliedTotal *=
					1 + modifier.amount();
			}
		}
		
		// MOVEMENT_SPEED is clamped at zero in vanilla as well.
		return Math.max(0, (base + base * multipliedBase) * multipliedTotal);
	}
	
	private Set<Identifier> getEffectModifiers(AbstractClientPlayer player)
	{
		Set<Identifier> ids = new HashSet<>();
		
		for(MobEffectInstance effect : player.getActiveEffects())
			effect.getEffect().value().createModifiers(effect.getAmplifier(),
				(attribute, modifier) -> {
					if(attribute.is(Attributes.MOVEMENT_SPEED))
						ids.add(modifier.id());
				});
		
		return ids;
	}
	
	// See AbstractClientPlayerMixin.wurst$ignoreEffectFov()
}
