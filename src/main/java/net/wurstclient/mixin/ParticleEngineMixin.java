/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SpellParticleOption;
import net.wurstclient.util.EffectParticleTracker;

@Mixin(ParticleEngine.class)
public class ParticleEngineMixin
{
	@Inject(
		method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)Lnet/minecraft/client/particle/Particle;",
		at = @At("RETURN"))
	private void onCreateParticle(ParticleOptions options, double x, double y,
		double z, double xSpeed, double ySpeed, double zSpeed,
		CallbackInfoReturnable<Particle> cir)
	{
		int color = getEffectParticleColor(options);
		if(color == 0)
			return;
		
		EffectParticleTracker.recordParticle(x, y, z, color);
	}
	
	@Unique
	private int getEffectParticleColor(ParticleOptions options)
	{
		if(options instanceof SpellParticleOption spell
			&& (spell.getType() == ParticleTypes.EFFECT
				|| spell.getType() == ParticleTypes.INSTANT_EFFECT))
			return rgb(spell.getRed(), spell.getGreen(), spell.getBlue());
		
		if(options instanceof ColorParticleOption color
			&& options.getType() == ParticleTypes.ENTITY_EFFECT)
			return rgb(color.getRed(), color.getGreen(), color.getBlue());
		
		return 0;
	}
	
	@Unique
	private int rgb(float red, float green, float blue)
	{
		int r = Math.round(red * 255F) & 0xFF;
		int g = Math.round(green * 255F) & 0xFF;
		int b = Math.round(blue * 255F) & 0xFF;
		return (r << 16) | (g << 8) | b;
	}
}
