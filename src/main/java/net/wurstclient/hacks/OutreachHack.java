/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Comparator;
import java.util.stream.Stream;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.AttackSpeedSliderSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.Rotation;
import net.wurstclient.util.RotationUtils;

@SearchTags({"outreach", "wall hit", "wall attack"})
public final class OutreachHack extends Hack implements UpdateListener
{
	private final SliderSetting range = new SliderSetting("Range",
		"Determines how far Outreach will reach to attack entities.", 4.25, 1,
		10, 0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting fov = new SliderSetting("FOV",
		"Only entities within this angle from your crosshair will be hit.", 45,
		5, 360, 5, ValueDisplay.DEGREES);
	
	private final AttackSpeedSliderSetting swingSpeed =
		new AttackSpeedSliderSetting();
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.CLIENT);
	
	private final CheckboxSetting spoofBehind =
		new CheckboxSetting("Spoof from behind",
			"Pretends to attack from the opposite side of your target.", false);
	
	private final EntityFilterList entityFilters =
		EntityFilterList.genericCombat();
	
	private Entity target;
	
	public OutreachHack()
	{
		super("Outreach");
		setCategory(Category.COMBAT);
		
		addSetting(range);
		addSetting(fov);
		addSetting(swingHand);
		addSetting(spoofBehind);
		entityFilters.forEach(this::addSetting);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().crystalAuraHack.setEnabled(false);
		WURST.getHax().fightBotHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().killauraHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		
		target = null;
		swingSpeed.resetTimer();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		target = null;
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.gameMode == null)
		{
			target = null;
			swingSpeed.resetTimer();
			return;
		}
		
		swingSpeed.updateTimer();
		
		double rangeSq = range.getValueSq();
		double halfFov = fov.getValue() / 2.0;
		Stream<Entity> stream = EntityUtils.getAttackableEntities();
		stream = stream.filter(e -> MC.player.distanceToSqr(e) <= rangeSq);
		
		if(fov.getValue() < 360.0)
			stream = stream.filter(e -> RotationUtils
				.getAngleToLookVec(e.getBoundingBox().getCenter()) <= halfFov);
		
		stream = entityFilters.applyTo(stream);
		
		Comparator<Entity> comparator = Comparator
			.comparingDouble((Entity e) -> RotationUtils
				.getAngleToLookVec(e.getBoundingBox().getCenter()))
			.thenComparingDouble((Entity e) -> MC.player.distanceToSqr(e));
		
		target = stream.min(comparator).orElse(null);
		
		if(!(MC.hitResult instanceof EntityHitResult)
			&& MC.options.keyAttack.isDown() && target != null)
		{
			if(!swingSpeed.isTimeToAttack())
				return;
			
			WURST.getHax().autoSwordHack.setSlot(target);
			boolean attacked = false;
			if(spoofBehind.isChecked())
				attacked = attackWithBehindSpoof(target);
			
			if(!attacked)
				attacked = attackNormally(target);
			
			if(attacked)
				swingSpeed.resetTimer();
		}
	}
	
	private boolean attackNormally(Entity target)
	{
		MC.gameMode.attack(MC.player, target);
		swingHand.swing(InteractionHand.MAIN_HAND);
		return true;
	}
	
	private boolean attackWithBehindSpoof(Entity target)
	{
		if(MC.player == null || MC.player.connection == null)
			return false;
		
		Vec3 spoofPos = calculateSpoofPosition(target);
		if(spoofPos == null)
			return false;
		
		Vec3 spoofEyes =
			spoofPos.add(0, MC.player.getEyeHeight(MC.player.getPose()), 0);
		Vec3 targetCenter = target.getBoundingBox().getCenter();
		Rotation spoofRot = rotationFrom(spoofEyes, targetCenter);
		
		Vec3 originalPos = MC.player.position();
		Rotation originalRot =
			new Rotation(MC.player.getYRot(), MC.player.getXRot());
		
		sendSpoofedPosRot(spoofPos, spoofRot);
		MC.gameMode.attack(MC.player, target);
		swingHand.swing(InteractionHand.MAIN_HAND);
		sendSpoofedPosRot(originalPos, originalRot);
		return true;
	}
	
	private Vec3 calculateSpoofPosition(Entity target)
	{
		if(MC.player == null)
			return null;
		
		Vec3 center = target.getBoundingBox().getCenter();
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 direction = center.subtract(eyes);
		double lenSq = direction.lengthSqr();
		if(lenSq < 1.0e-6)
			return null;
		
		Vec3 normalized = direction.normalize();
		double extra = Math.max(0.75, target.getBbWidth() + 0.75);
		Vec3 behind = center.add(normalized.scale(extra));
		return new Vec3(behind.x, MC.player.getY(), behind.z);
	}
	
	private Rotation rotationFrom(Vec3 start, Vec3 end)
	{
		double diffX = end.x - start.x;
		double diffZ = end.z - start.z;
		double yaw = Math.toDegrees(Math.atan2(diffZ, diffX)) - 90F;
		
		double diffY = end.y - start.y;
		double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);
		double pitch = -Math.toDegrees(Math.atan2(diffY, diffXZ));
		
		return Rotation.wrapped((float)yaw, (float)pitch);
	}
	
	private void sendSpoofedPosRot(Vec3 pos, Rotation rotation)
	{
		if(MC.player == null || MC.player.connection == null)
			return;
		
		boolean onGround = MC.player.onGround();
		boolean collision = MC.player.horizontalCollision;
		MC.player.connection.send(new PosRot(pos.x, pos.y, pos.z,
			rotation.yaw(), rotation.pitch(), onGround, collision));
	}
}
