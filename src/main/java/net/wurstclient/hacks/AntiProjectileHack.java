/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.HandleInputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"anti projectile", "projectile puncher", "anti fireball",
	"anti shulker bullet"})
public final class AntiProjectileHack extends Hack
	implements UpdateListener, HandleInputListener
{
	private final SliderSetting range = new SliderSetting("Range", 4, 3, 6,
		0.25, SliderSetting.ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting cooldown = new SliderSetting("Cooldown", 4, 0,
		20, 1, SliderSetting.ValueDisplay.INTEGER.withSuffix(" ticks"));
	private final CheckboxSetting swing =
		new CheckboxSetting("Swing hand", true);
	private final CheckboxSetting ignoreInventory =
		new CheckboxSetting("Ignore inventory", true);
	private final CheckboxSetting silentAim = new CheckboxSetting("Silent aim",
		"Faces incoming projectiles server-side like Killaura, without turning your camera.",
		true);
	
	private int cooldownLeft;
	private Entity pendingTarget;
	
	public AntiProjectileHack()
	{
		super("AntiProjectile",
			"Punches incoming fireballs and shulker bullets back before they hit you.",
			false);
		setCategory(Category.COMBAT);
		addSetting(range);
		addSetting(cooldown);
		addSetting(swing);
		addSetting(ignoreInventory);
		addSetting(silentAim);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(HandleInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(HandleInputListener.class, this);
		cooldownLeft = 0;
		pendingTarget = null;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null || MC.player.isSpectator())
			return;
		if(!ignoreInventory.isChecked()
			&& MC.gui.screen() instanceof AbstractContainerScreen)
			return;
		if(cooldownLeft > 0)
		{
			cooldownLeft--;
			return;
		}
		
		Entity target = findTarget();
		if(target == null)
			return;
		
		Vec3 aimPos = target.getBoundingBox().getCenter();
		if(silentAim.isChecked())
			WURST.getRotationFaker().faceVectorPacket(aimPos);
		else
		{
			Rotation aim = RotationUtils.getNeededRotations(aimPos);
			aim.applyToClientPlayer();
			aim.sendPlayerLookPacket();
		}
		
		pendingTarget = target;
	}
	
	@Override
	public void onHandleInput()
	{
		if(pendingTarget == null || MC.player == null || MC.gameMode == null)
			return;
		
		MC.gameMode.attack(MC.player, pendingTarget);
		if(swing.isChecked())
			MC.player.swing(InteractionHand.MAIN_HAND);
		
		cooldownLeft = cooldown.getValueI();
		pendingTarget = null;
	}
	
	private Entity findTarget()
	{
		double rangeSq = range.getValue() * range.getValue();
		Entity best = null;
		double bestDist = Double.MAX_VALUE;
		
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(!isSupportedProjectile(entity) || !isIncoming(entity))
				continue;
			
			Vec3 nextPos = entity.position().add(entity.getDeltaMovement());
			AABB nextBox =
				entity.getDimensions(entity.getPose()).makeBoundingBox(nextPos);
			double distSq = nextBox.distanceToSqr(MC.player.getEyePosition());
			if(distSq > rangeSq || distSq >= bestDist)
				continue;
			
			best = entity;
			bestDist = distSq;
		}
		
		return best;
	}
	
	private boolean isSupportedProjectile(Entity entity)
	{
		return entity instanceof Fireball || entity instanceof ShulkerBullet;
	}
	
	private boolean isIncoming(Entity entity)
	{
		Vec3 velocity = entity.getDeltaMovement();
		if(velocity.lengthSqr() < 1.0E-6)
			return false;
		
		Vec3 toPlayer =
			MC.player.getBoundingBox().getCenter().subtract(entity.position());
		double dot = toPlayer.dot(velocity);
		boolean roughlyTowardsPlayer =
			dot > 0.5 * velocity.length() * toPlayer.length();
		
		AABB expanded =
			MC.player.getBoundingBox().inflate(entity.getBbWidth() / 2.0);
		boolean willHit = !expanded.contains(entity.position()) && expanded
			.clip(entity.position(), entity.position().add(velocity.scale(20)))
			.isPresent();
		
		return roughlyTowardsPlayer || willHit;
	}
}
