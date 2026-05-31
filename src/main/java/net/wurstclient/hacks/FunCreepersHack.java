/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

public final class FunCreepersHack extends Hack
{
	private final SliderSetting excitement = new SliderSetting("Excitement",
		"Controls how intense and chaotic the celebration gets.", 100, 25, 300,
		25, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting creeperDetectionRange =
		new SliderSetting("Creeper Detection Range",
			"Only explosions near a creeper in this radius are transformed.", 4,
			1, 12, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting fireworks = new SliderSetting("Fireworks",
		"How many fireworks sparks burst out per transformed explosion.", 35, 5,
		200, 5, ValueDisplay.INTEGER);
	
	private final SliderSetting confetti = new SliderSetting("Confetti",
		"How much confetti-like villager happiness pops out.", 60, 10, 300, 10,
		ValueDisplay.INTEGER);
	
	private final SliderSetting sparkles = new SliderSetting("Sparkles",
		"How many colorful sparkles get sprayed around.", 90, 20, 400, 10,
		ValueDisplay.INTEGER);
	
	private final CheckboxSetting replaceSound =
		new CheckboxSetting("Replace Sound",
			"Replace explosion sound with a happy firework sound.", true);
	
	private final RandomSource random = RandomSource.create();
	
	public FunCreepersHack()
	{
		super("FunCreepers",
			"Turns creeper explosions into a colorful fireworks party.", false);
		setCategory(Category.FUN);
		addSetting(excitement);
		addSetting(creeperDetectionRange);
		addSetting(fireworks);
		addSetting(confetti);
		addSetting(sparkles);
		addSetting(replaceSound);
	}
	
	public boolean shouldPartyifyExplosion(Vec3 center)
	{
		if(!isEnabled() || MC.level == null || center == null)
			return false;
		
		double range = creeperDetectionRange.getValue();
		AABB box = new AABB(center, center).inflate(range);
		return MC.level.getEntitiesOfClass(Creeper.class, box).stream()
			.anyMatch(
				creeper -> creeper.distanceToSqr(center) <= range * range);
	}
	
	public void spawnPartyEffects(Vec3 center)
	{
		if(!isEnabled() || MC.level == null || center == null)
			return;
		
		int fireworksCount = scaledCount(fireworks.getValueI());
		int confettiCount = scaledCount(confetti.getValueI());
		int sparkleCount = scaledCount(sparkles.getValueI());
		
		for(int i = 0; i < fireworksCount; i++)
		{
			double dx = randomRange(0.8);
			double dy = randomRange(0.8) + 0.25;
			double dz = randomRange(0.8);
			MC.level.addParticle(ParticleTypes.FIREWORK, center.x,
				center.y + 0.2, center.z, dx, dy, dz);
		}
		
		for(int i = 0; i < confettiCount; i++)
		{
			double x = center.x + randomRange(2.5);
			double y = center.y + random.nextDouble() * 2.5;
			double z = center.z + randomRange(2.5);
			double dx = randomRange(0.15);
			double dy = random.nextDouble() * 0.2;
			double dz = randomRange(0.15);
			MC.level.addParticle(ParticleTypes.HAPPY_VILLAGER, x, y, z, dx, dy,
				dz);
		}
		
		for(int i = 0; i < sparkleCount; i++)
		{
			int color = random.nextInt(0x1000000);
			double x = center.x + randomRange(2.0);
			double y = center.y + random.nextDouble() * 2.0;
			double z = center.z + randomRange(2.0);
			double dx = randomRange(0.2);
			double dy = random.nextDouble() * 0.25;
			double dz = randomRange(0.2);
			MC.level.addParticle(new DustParticleOptions(color, 1.0F), x, y, z,
				dx, dy, dz);
			if(random.nextBoolean())
				MC.level.addParticle(ParticleTypes.END_ROD, x, y, z, dx * 0.7,
					dy * 0.7, dz * 0.7);
		}
	}
	
	public SoundEvent getReplacementExplosionSound()
	{
		if(!isEnabled() || !replaceSound.isChecked())
			return null;
		
		return random.nextBoolean() ? SoundEvents.FIREWORK_ROCKET_BLAST
			: SoundEvents.FIREWORK_ROCKET_TWINKLE;
	}
	
	public float getReplacementVolume()
	{
		return (float)Math.min(3.0, 1.2 * excitementMultiplier());
	}
	
	public float getReplacementPitch()
	{
		float base = 1.0F + (float)(random.nextDouble() * 0.5);
		return base * (float)Math.min(1.8, excitementMultiplier());
	}
	
	public SoundSource getReplacementSoundSource()
	{
		return SoundSource.PLAYERS;
	}
	
	private int scaledCount(int base)
	{
		return (int)Math.round(base * excitementMultiplier());
	}
	
	private double excitementMultiplier()
	{
		return excitement.getValue() / 100.0;
	}
	
	private double randomRange(double halfSpan)
	{
		return random.nextDouble() * halfSpan * 2 - halfSpan;
	}
}
