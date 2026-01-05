/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.MobEspHack.RenderShape;
import net.wurstclient.hacks.MobEspHack.RenderStyleInfo;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;

@SearchTags({"spear assist", "spear", "spear damage"})
public final class SpearAssistHack extends Hack
	implements UpdateListener, RenderListener, RightClickListener
{
	private final EnumSetting<BoostMode> boostMode = new EnumSetting<>(
		"Boost mode", BoostMode.values(), BoostMode.HOLD_AND_DASH);
	
	private final SliderSetting boostSpeed = new SliderSetting("Boost speed",
		"Matches Flight's horizontal speed slider. Values are in blocks per second.",
		1, 0.05, 10, 0.05, ValueDisplay.DECIMAL.withSuffix(" blocks/s"));
	
	private final SliderSetting dashDistance =
		new SliderSetting("Dash distance",
			"Extra distance to travel each time you start holding attack.", 4,
			0, 10, 0.1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final CheckboxSetting allowReverse = new CheckboxSetting(
		"Allow reverse",
		"Hold the Back key (default: S) while attacking to boost backwards instead of forwards.",
		false);
	
	private final SliderSetting nearHighlightRange =
		new SliderSetting("Near highlight range",
			"Distance where targets switch to the near color.", 7, 4, 10, 0.5,
			ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting farHighlightRange =
		new SliderSetting("Far highlight range",
			"Distance where targets switch to the far color.", 8.5, 5, 20, 0.5,
			ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final ColorSetting nearHighlightColor =
		new ColorSetting("Near highlight color",
			"Color used when targets are nearby.", Color.YELLOW);
	private final ColorSetting farHighlightColor =
		new ColorSetting("Far highlight color",
			"Color used when targets are far away.", new Color(0, 255, 0));
	
	private final CheckboxSetting stayGrounded = new CheckboxSetting(
		"Stay grounded",
		"Removes the vertical component from boosts to keep you from leaving the ground.",
		false);
	
	private final CheckboxSetting autoResumeCharge = new CheckboxSetting(
		"Auto resume charge",
		"Automatically re-presses use to keep the spear raised when it drops.",
		true);
	
	private final CheckboxSetting allowAimAssistWhileCharging =
		new CheckboxSetting("Allow AimAssist while charging",
			"Temporarily lets AimAssist run while you hold right-click.", true);
	
	private final SliderSetting aimAssistRangeOverride = new SliderSetting(
		"AimAssist range", "Overrides AimAssist range while holding a spear.",
		6, 1, 100, 0.05, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final CheckboxSetting aimAssistLockOnOverride = new CheckboxSetting(
		"AimAssist lock-on",
		"Overrides AimAssist's lock-on setting while holding a spear.", false);
	
	private final CheckboxSetting autoAlignment = new CheckboxSetting(
		"Auto alignment",
		"While flying and charging a spear, aligns your Y-level to the AimAssist target.",
		false);
	
	private final CheckboxSetting autoAttackWhenReady = new CheckboxSetting(
		"Auto attack when ready",
		"Automatically swings again as soon as the attack indicator refills while you hold attack.",
		false);
	
	private boolean attackKeyDown;
	private double dashDistanceRemaining;
	private boolean aimAssistTemporarilyAllowed;
	private final ArrayList<TargetHighlight> highlightTargets =
		new ArrayList<>();
	private boolean useGlowFallback = true;
	private RenderStyleInfo currentStyle;
	private boolean autoAttackPrimed;
	private boolean reverseDashActive;
	private boolean aimAssistRangeOverridden;
	private boolean aimAssistLockOnOverridden;
	
	public SpearAssistHack()
	{
		super("SpearAssist");
		setCategory(Category.COMBAT);
		addSetting(boostMode);
		addSetting(boostSpeed);
		addSetting(dashDistance);
		addSetting(allowReverse);
		addSetting(stayGrounded);
		addSetting(nearHighlightRange);
		addSetting(farHighlightRange);
		addSetting(nearHighlightColor);
		addSetting(farHighlightColor);
		addSetting(autoResumeCharge);
		addSetting(allowAimAssistWhileCharging);
		addSetting(aimAssistRangeOverride);
		addSetting(aimAssistLockOnOverride);
		addSetting(autoAlignment);
		addSetting(autoAttackWhenReady);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(RightClickListener.class, this);
		resetState();
		updateAimAssist(false);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(RightClickListener.class, this);
		clearAimAssistRangeOverride();
		clearAimAssistLockOnOverride();
		resetState();
		updateAimAssist(false);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.options == null)
			return;
		
		ItemStack main = MC.player.getMainHandItem();
		boolean holdingSpear = isSpear(main);
		
		handleAutoResumeCharge(holdingSpear);
		
		ItemStack useStack = MC.player.getUseItem();
		boolean charging =
			holdingSpear && MC.player.isUsingItem() && isSpear(useStack);
		boolean attackHeld = MC.options.keyAttack.isDown();
		boolean attackPressed = attackHeld && !attackKeyDown;
		
		if(charging)
		{
			Vec3 direction = MC.player.getLookAngle();
			if(direction.lengthSqr() > 1.0E-4)
			{
				if(attackPressed)
				{
					updateReverseDashState();
					startDash();
				}
				
				Vec3 reversed = direction.scale(-1);
				Vec3 holdDirection =
					shouldReverseHoldBoost(attackHeld) ? reversed : direction;
				Vec3 dashDirection =
					shouldReverseDashBoost() ? reversed : direction;
				
				if(attackHeld)
					applyHoldVelocity(holdDirection);
				
				continueDash(dashDirection);
			}
		}else
			resetDash();
		
		attackKeyDown = attackHeld;
		
		updateHighlights(holdingSpear);
		updateHighlightMode();
		updateAimAssistRangeOverride(holdingSpear);
		updateAimAssistLockOnOverride(holdingSpear);
		updateAimAssist(charging);
		updateAutoAlignment(charging, holdingSpear);
		handleAutoAttack(holdingSpear, attackHeld);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(highlightTargets.isEmpty())
			return;
		
		var style = currentStyle;
		if(style == null || useGlowFallback)
			return;
		
		boolean drawShape = style.shape == RenderShape.BOX
			|| style.shape == RenderShape.OCTAHEDRON;
		ArrayList<ColoredBox> outlineShapes =
			drawShape ? new ArrayList<>(highlightTargets.size()) : null;
		ArrayList<ColoredBox> filledShapes = drawShape && style.fillShapes
			? new ArrayList<>(highlightTargets.size()) : null;
		ArrayList<ColoredPoint> ends =
			style.drawLines ? new ArrayList<>(highlightTargets.size()) : null;
		
		for(TargetHighlight target : highlightTargets)
		{
			LivingEntity entity = target.entity;
			AABB box = EntityUtils.getLerpedBox(entity, partialTicks);
			int outlineColor = RenderUtils.toIntColor(target.color, 0.8F);
			int fillColor = RenderUtils.toIntColor(target.color, 0.2F);
			
			if(drawShape)
			{
				AABB expanded =
					box.move(0, style.extraSize, 0).inflate(style.extraSize);
				outlineShapes.add(new ColoredBox(expanded, outlineColor));
				
				if(filledShapes != null)
					filledShapes.add(new ColoredBox(expanded, fillColor));
			}
			
			if(ends != null)
				ends.add(new ColoredPoint(box.getCenter(), outlineColor));
		}
		
		if(filledShapes != null && !filledShapes.isEmpty())
		{
			switch(style.shape)
			{
				case BOX -> RenderUtils.drawSolidBoxes(matrixStack,
					filledShapes, false);
				case OCTAHEDRON -> RenderUtils.drawSolidOctahedrons(matrixStack,
					filledShapes, false);
				default ->
					{
					}
			}
		}
		
		if(outlineShapes != null && !outlineShapes.isEmpty())
		{
			switch(style.shape)
			{
				case BOX -> RenderUtils.drawOutlinedBoxes(matrixStack,
					outlineShapes, false);
				case OCTAHEDRON -> RenderUtils
					.drawOutlinedOctahedrons(matrixStack, outlineShapes, false);
				default ->
					{
					}
			}
		}
		
		if(ends != null && !ends.isEmpty())
			RenderUtils.drawTracers(matrixStack, partialTicks, ends, false);
	}
	
	@Override
	public void onRightClick(RightClickEvent event)
	{
		if(!autoResumeCharge.isChecked() || MC.player == null
			|| MC.gameMode == null)
			return;
		
		if(!isSpear(MC.player.getMainHandItem()))
			return;
		
		MC.rightClickDelay = 4;
		MC.gameMode.useItem(MC.player, InteractionHand.MAIN_HAND);
		event.cancel();
	}
	
	private void handleAutoResumeCharge(boolean holdingSpear)
	{
		if(MC.player == null || MC.options == null)
			return;
		
		boolean maintainCharge = holdingSpear && autoResumeCharge.isChecked()
			&& MC.options.keyUse.isDown();
		
		if(!maintainCharge)
			return;
		
		if(!MC.player.isUsingItem())
		{
			silentlyStartUseItem();
			return;
		}
		
		silentlyStartUseItem();
	}
	
	private void handleAutoAttack(boolean holdingSpear, boolean attackHeld)
	{
		if(MC.player == null || !autoAttackWhenReady.isChecked())
		{
			autoAttackPrimed = false;
			return;
		}
		
		if(!holdingSpear || !attackHeld)
		{
			autoAttackPrimed = false;
			return;
		}
		
		float strength = MC.player.getAttackStrengthScale(0);
		if(strength >= 0.999F)
		{
			if(autoAttackPrimed)
				return;
			
			if(performAutoAttack())
				autoAttackPrimed = true;
			
			return;
		}
		
		if(strength <= 0.1F)
			autoAttackPrimed = false;
	}
	
	private boolean performAutoAttack()
	{
		if(MC.player == null || MC.gameMode == null)
			return false;
		
		if(!(MC.hitResult instanceof EntityHitResult entityHit))
			return false;
		
		MC.gameMode.attack(MC.player, entityHit.getEntity());
		MC.player.swing(InteractionHand.MAIN_HAND);
		return true;
	}
	
	private void startDash()
	{
		if(!getBoostMode().useDash())
			return;
		
		double distance = Math.max(0, dashDistance.getValue());
		if(distance <= 0 || boostSpeed.getValue() <= 0)
		{
			dashDistanceRemaining = 0;
			return;
		}
		
		dashDistanceRemaining = distance;
	}
	
	private void continueDash(Vec3 direction)
	{
		if(dashDistanceRemaining <= 0 || !getBoostMode().useDash())
		{
			reverseDashActive = false;
			return;
		}
		
		double dashSpeed = Math.max(0, boostSpeed.getValue());
		if(dashSpeed <= 0)
		{
			dashDistanceRemaining = 0;
			reverseDashActive = false;
			return;
		}
		
		double step = Math.min(dashSpeed, dashDistanceRemaining);
		Vec3 boost = direction.normalize().scale(step);
		if(stayGrounded.isChecked())
			boost = new Vec3(boost.x, 0, boost.z);
		MC.player.setDeltaMovement(MC.player.getDeltaMovement().add(boost));
		if(!stayGrounded.isChecked())
			MC.player.setOnGround(false);
		MC.player.fallDistance = 0;
		dashDistanceRemaining -= step;
		
		if(dashDistanceRemaining <= 0)
			reverseDashActive = false;
	}
	
	private void applyHoldVelocity(Vec3 direction)
	{
		if(!getBoostMode().useHold())
			return;
		
		double perTick = boostSpeed.getValue();
		if(perTick <= 0)
			return;
		
		Vec3 target = direction.normalize().scale(perTick);
		double newY = stayGrounded.isChecked() ? 0 : target.y;
		MC.player.setDeltaMovement(target.x, newY, target.z);
		MC.player.fallDistance = 0;
		MC.player.setOnGround(false);
	}
	
	private void resetDash()
	{
		dashDistanceRemaining = 0;
		attackKeyDown = false;
		reverseDashActive = false;
	}
	
	private void resetState()
	{
		resetDash();
		highlightTargets.clear();
		useGlowFallback = true;
		currentStyle = null;
		aimAssistTemporarilyAllowed = false;
		autoAttackPrimed = false;
		reverseDashActive = false;
		aimAssistRangeOverridden = false;
		aimAssistLockOnOverridden = false;
	}
	
	private void updateHighlights(boolean holdingSpear)
	{
		highlightTargets.clear();
		useGlowFallback = true;
		currentStyle = null;
		
		if(!holdingSpear || MC.level == null || MC.player == null)
			return;
		
		double nearDist = nearHighlightRange.getValue();
		double farSetting =
			Math.max(farHighlightRange.getValue(), nearDist + 0.5);
		double nearDistSq = nearDist * nearDist;
		double farDistSq = farSetting * farSetting;
		
		EntityUtils.getAttackableEntities().sequential()
			.filter(LivingEntity.class::isInstance)
			.map(LivingEntity.class::cast).forEach(entity -> {
				if(entity == MC.player)
					return;
				
				double verticalOffset = entity.getY() - MC.player.getY();
				if(verticalOffset < 0 || verticalOffset > 8)
					return;
				
				if(!hasLineOfSight(entity))
					return;
				
				double distSq = MC.player.distanceToSqr(entity);
				float[] color = null;
				if(distSq <= nearDistSq)
					color = nearHighlightColor.getColorF();
				else if(distSq >= farDistSq)
					color = farHighlightColor.getColorF();
				else
					return;
				
				highlightTargets.add(new TargetHighlight(entity, color));
			});
	}
	
	private boolean hasLineOfSight(LivingEntity entity)
	{
		if(MC.player == null || MC.level == null)
			return false;
		
		Vec3 eyePos = MC.player.getEyePosition(1F);
		Vec3 target = entity.getBoundingBox().getCenter();
		ClipContext ctx = new ClipContext(eyePos, target,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player);
		HitResult result = MC.level.clip(ctx);
		return result == null || result.getType() == HitResult.Type.MISS;
	}
	
	private void updateHighlightMode()
	{
		currentStyle = null;
		useGlowFallback = !highlightTargets.isEmpty();
	}
	
	private void silentlyStartUseItem()
	{
		if(MC.player == null)
			return;
		
		boolean wasSilent = MC.player.isSilent();
		if(!wasSilent)
			MC.player.setSilent(true);
		
		MC.startUseItem();
		
		if(!wasSilent)
			MC.player.setSilent(false);
	}
	
	private void updateAimAssist(boolean charging)
	{
		var aimAssist = WURST.getHax().aimAssistHack;
		if(aimAssist == null || !aimAssist.isEnabled()
			|| !allowAimAssistWhileCharging.isChecked())
		{
			if(aimAssistTemporarilyAllowed && aimAssist != null)
				aimAssist.setTemporarilyAllowBlocking(false);
			
			aimAssistTemporarilyAllowed = false;
			return;
		}
		
		if(charging && !aimAssistTemporarilyAllowed)
		{
			aimAssist.setTemporarilyAllowBlocking(true);
			aimAssistTemporarilyAllowed = true;
		}else if(!charging && aimAssistTemporarilyAllowed)
		{
			aimAssist.setTemporarilyAllowBlocking(false);
			aimAssistTemporarilyAllowed = false;
		}
	}
	
	private void updateAutoAlignment(boolean charging, boolean holdingSpear)
	{
		if(WURST.getHax().flightHack.isEnabled())
			return;
		
		Double step =
			getAutoAlignmentStepInternal(charging, holdingSpear, null);
		if(step == null || MC.player == null)
			return;
		
		Vec3 motion = MC.player.getDeltaMovement();
		MC.player.setDeltaMovement(motion.x, motion.y + step, motion.z);
	}
	
	public Double getAutoAlignmentStepForFlight()
	{
		return getAutoAlignmentStepInternal(null, null,
			WURST.getHax().flightHack.verticalSpeed.getValue());
	}
	
	private Double getAutoAlignmentStepInternal(Boolean chargingOverride,
		Boolean holdingSpearOverride, Double maxStepOverride)
	{
		if(MC.player == null || !autoAlignment.isChecked())
			return null;
		
		boolean holdingSpear = holdingSpearOverride != null
			? holdingSpearOverride : isSpear(MC.player.getMainHandItem());
		if(!holdingSpear)
			return null;
		
		boolean charging = chargingOverride != null ? chargingOverride
			: MC.player.isUsingItem() && isSpear(MC.player.getUseItem());
		if(!charging)
			return null;
		
		boolean flying = MC.player.getAbilities().flying
			|| WURST.getHax().flightHack.isEnabled();
		if(!flying)
			return null;
		
		var aimAssist = WURST.getHax().aimAssistHack;
		if(aimAssist == null || !aimAssist.isEnabled())
			return null;
		
		var target = aimAssist.getCurrentTarget();
		if(target == null)
			return null;
		
		double maxStep = maxStepOverride != null ? maxStepOverride
			: MC.player.getAbilities().getFlyingSpeed();
		if(maxStep <= 0)
			return null;
		
		double targetY = target.getY();
		double playerY = MC.player.getY();
		double delta = targetY - playerY;
		if(Math.abs(delta) < 0.02)
			return null;
		
		return Math.max(-maxStep, Math.min(maxStep, delta));
	}
	
	private void updateAimAssistRangeOverride(boolean holdingSpear)
	{
		var aimAssist = WURST.getHax().aimAssistHack;
		if(aimAssist == null || !aimAssist.isEnabled())
		{
			clearAimAssistRangeOverride();
			return;
		}
		
		if(holdingSpear)
		{
			aimAssist.setRangeOverride(aimAssistRangeOverride.getValue());
			aimAssistRangeOverridden = true;
			return;
		}
		
		clearAimAssistRangeOverride();
	}
	
	private void updateAimAssistLockOnOverride(boolean holdingSpear)
	{
		var aimAssist = WURST.getHax().aimAssistHack;
		if(aimAssist == null || !aimAssist.isEnabled())
		{
			clearAimAssistLockOnOverride();
			return;
		}
		
		if(holdingSpear)
		{
			aimAssist.setLockOnOverride(aimAssistLockOnOverride.isChecked());
			aimAssistLockOnOverridden = true;
			return;
		}
		
		clearAimAssistLockOnOverride();
	}
	
	private void clearAimAssistRangeOverride()
	{
		if(!aimAssistRangeOverridden)
			return;
		
		var aimAssist = WURST.getHax().aimAssistHack;
		if(aimAssist != null)
			aimAssist.setRangeOverride(null);
		
		aimAssistRangeOverridden = false;
	}
	
	private void clearAimAssistLockOnOverride()
	{
		if(!aimAssistLockOnOverridden)
			return;
		
		var aimAssist = WURST.getHax().aimAssistHack;
		if(aimAssist != null)
			aimAssist.setLockOnOverride(null);
		
		aimAssistLockOnOverridden = false;
	}
	
	private BoostMode getBoostMode()
	{
		return boostMode.getSelected();
	}
	
	public Integer getGlowColor(LivingEntity entity)
	{
		if(!isEnabled() || !useGlowFallback)
			return null;
		
		for(TargetHighlight target : highlightTargets)
			if(target.entity == entity)
				return RenderUtils.toIntColor(target.color, 1F);
			
		return null;
	}
	
	private boolean isSpear(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		if(id == null)
			return false;
		
		return id.getPath().toLowerCase(Locale.ROOT).contains("spear");
	}
	
	private void updateReverseDashState()
	{
		if(MC.options == null || !allowReverse.isChecked())
		{
			reverseDashActive = false;
			return;
		}
		
		reverseDashActive = MC.options.keyDown.isDown();
	}
	
	private boolean shouldReverseHoldBoost(boolean attackHeld)
	{
		return allowReverse.isChecked() && attackHeld && MC.options != null
			&& MC.options.keyDown.isDown();
	}
	
	private boolean shouldReverseDashBoost()
	{
		return allowReverse.isChecked() && reverseDashActive;
	}
	
	private enum BoostMode
	{
		HOLD_ONLY("Hold only", true, false),
		DASH_ONLY("Dash only", false, true),
		HOLD_AND_DASH("Hold + dash", true, true);
		
		private final String name;
		private final boolean hold;
		private final boolean dash;
		
		private BoostMode(String name, boolean hold, boolean dash)
		{
			this.name = name;
			this.hold = hold;
			this.dash = dash;
		}
		
		public boolean useHold()
		{
			return hold;
		}
		
		public boolean useDash()
		{
			return dash;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private static final class TargetHighlight
	{
		private final LivingEntity entity;
		private final float[] color;
		
		private TargetHighlight(LivingEntity entity, float[] color)
		{
			this.entity = entity;
			this.color = color;
		}
	}
}
