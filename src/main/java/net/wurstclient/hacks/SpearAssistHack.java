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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
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
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.MobEspHack.RenderShape;
import net.wurstclient.hacks.MobEspHack.RenderStyleInfo;
import net.wurstclient.clickgui.SettingsWindow;
import net.wurstclient.clickgui.Window;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EntityTypeListSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;

@SearchTags({"spear assist", "spear", "spear damage"})
public final class SpearAssistHack extends Hack
	implements UpdateListener, RenderListener, RightClickListener,
	PacketOutputListener, PacketInputListener
{
	private final EnumSetting<AssistMode> assistMode =
		new EnumSetting<>("Mode", AssistMode.values(), AssistMode.ASSIST)
		{
			@Override
			public void update()
			{
				super.update();
				updateModeVisibility();
			}
		};
	
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
	
	private final EnumSetting<AimAssistMode> aimAssistMode = new EnumSetting<>(
		"AimAssist mode", AimAssistMode.values(), AimAssistMode.OFF);
	
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
	
	private final EnumSetting<SpearKillMode> spearKillMode = new EnumSetting<>(
		"SK mode", SpearKillMode.values(), SpearKillMode.LUNGE);
	
	private final SliderSetting spearKillMaxRange = new SliderSetting(
		"SK max range", "How far away entities can still be targeted.", 256, 0,
		512, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	
	private final EnumSetting<TargetListMode> spearKillTargetListMode =
		new EnumSetting<>("SK target list mode", TargetListMode.values(),
			TargetListMode.BLACKLIST);
	
	private final EntityTypeListSetting spearKillTargetEntities =
		new EntityTypeListSetting("SK target entities",
			"Entities to whitelist or blacklist for SpearKill.");
	
	private final CheckboxSetting spearKillBlinkLunge =
		new CheckboxSetting("SK blink + lunge",
			"Combine Blink mode with velocity based lunging.", false);
	
	private final SliderSetting spearKillBlinkLungeStrength = new SliderSetting(
		"SK blink lunge strength", "Velocity applied towards target.", 1.0, 0.1,
		2.0, 0.1, ValueDisplay.DECIMAL);
	
	private final SliderSetting spearKillBlinkLungeTicks = new SliderSetting(
		"SK blink lunge delay", "Ticks to charge before lunging.", 15, 1, 30, 1,
		ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private final SliderSetting spearKillFlushRange = new SliderSetting(
		"SK flush range", "Distance to target when flush occurs.", 3.0, 1.0,
		10.0, 0.1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting spearKillMaxFlushRange = new SliderSetting(
		"SK force flush distance", "Distance to force a flush.", 9.5, 1.0, 20.0,
		0.1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final CheckboxSetting spearKillBlinkAimbot =
		new CheckboxSetting("SK blink aimbot", "Lock on to target.", true);
	
	private final SliderSetting spearKillBlinkDistanceBoost =
		new SliderSetting("SK blink distance boost",
			"Extra blocks added to start position when flushing blink.", 0, 0,
			10, 0.1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting spearKillLungeStrength = new SliderSetting(
		"SK lunge strength", "Velocity applied towards target.", 5, 0, 10, 0.1,
		ValueDisplay.DECIMAL);
	
	private final CheckboxSetting spearKillStopOnTarget =
		new CheckboxSetting("SK stop on target",
			"Stops the lunge when you reach the target.", true);
	
	private final SliderSetting spearKillStopDistance = new SliderSetting(
		"SK stop distance", "Distance between hitboxes to attempt to stop at.",
		2, 0, 10, 0.1, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	
	private final SliderSetting spearKillLungeDelayModifier =
		new SliderSetting("SK lunge delay modifier",
			"Percent of charge time to wait before lunging.", 100, 0, 100, 1,
			ValueDisplay.INTEGER.withSuffix("%"));
	
	private final CheckboxSetting spearKillChatFeedback = new CheckboxSetting(
		"SK chat feedback", "Prints blink flush distance in chat.", true);
	
	private boolean attackKeyDown;
	private double dashDistanceRemaining;
	private boolean aimAssistTemporarilyAllowed;
	private boolean aimAssistTemporarilyEnabled;
	private final ArrayList<TargetHighlight> highlightTargets =
		new ArrayList<>();
	private boolean useGlowFallback = true;
	private RenderStyleInfo currentStyle;
	private boolean autoAttackPrimed;
	private boolean reverseDashActive;
	private boolean aimAssistRangeOverridden;
	private boolean aimAssistLockOnOverridden;
	private final ArrayDeque<ServerboundMovePlayerPacket> spearKillPackets =
		new ArrayDeque<>();
	private boolean spearKillBlinking;
	private boolean spearKillFlushing;
	private Vec3 spearKillStartPos;
	private boolean spearKillWasCharging;
	private double spearKillLastTargetDistance = Double.MAX_VALUE;
	private boolean spearKillWasApproaching;
	private Entity spearKillTarget;
	private int spearKillBlinkChargeTicks;
	private int spearKillFlushCooldown;
	private AssistMode lastAssistMode;
	
	public SpearAssistHack()
	{
		super("SpearAssist");
		setCategory(Category.COMBAT);
		addSetting(assistMode);
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
		addSetting(aimAssistMode);
		addSetting(aimAssistRangeOverride);
		addSetting(aimAssistLockOnOverride);
		addSetting(autoAlignment);
		addSetting(autoAttackWhenReady);
		addSetting(spearKillMode);
		addSetting(spearKillMaxRange);
		addSetting(spearKillTargetListMode);
		addSetting(spearKillTargetEntities);
		addSetting(spearKillBlinkLunge);
		addSetting(spearKillBlinkLungeStrength);
		addSetting(spearKillBlinkLungeTicks);
		addSetting(spearKillFlushRange);
		addSetting(spearKillMaxFlushRange);
		addSetting(spearKillBlinkAimbot);
		addSetting(spearKillBlinkDistanceBoost);
		addSetting(spearKillLungeStrength);
		addSetting(spearKillStopOnTarget);
		addSetting(spearKillStopDistance);
		addSetting(spearKillLungeDelayModifier);
		addSetting(spearKillChatFeedback);
		
		spearKillTargetEntities.clear();
		updateModeVisibility();
	}
	
	@Override
	public String getRenderName()
	{
		return assistMode.getSelected() == AssistMode.SPEARKILL
			? getName() + " [Kill]" : getName();
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(RightClickListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		resetState();
		updateModeVisibility();
		updateAimAssist(false, false);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(RightClickListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		stopSpearKillBlink(true);
		clearAimAssistRangeOverride();
		clearAimAssistLockOnOverride();
		updateAimAssist(false, false);
		resetState();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.options == null)
			return;
		
		updateModeVisibility();
		
		if(assistMode.getSelected() == AssistMode.SPEARKILL)
		{
			updateSpearKill();
			return;
		}
		
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
		updateAimAssist(charging, holdingSpear);
		updateAimAssistRangeOverride(holdingSpear);
		updateAimAssistLockOnOverride(holdingSpear);
		updateAutoAlignment(charging, holdingSpear);
		handleAutoAttack(holdingSpear, attackHeld);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(assistMode.getSelected() == AssistMode.SPEARKILL)
			return;
		
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
		if(assistMode.getSelected() == AssistMode.SPEARKILL)
			return;
		
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
		aimAssistTemporarilyEnabled = false;
		autoAttackPrimed = false;
		reverseDashActive = false;
		aimAssistRangeOverridden = false;
		aimAssistLockOnOverridden = false;
		spearKillPackets.clear();
		spearKillBlinking = false;
		spearKillFlushing = false;
		spearKillStartPos = null;
		spearKillWasCharging = false;
		spearKillLastTargetDistance = Double.MAX_VALUE;
		spearKillWasApproaching = false;
		spearKillTarget = null;
		spearKillBlinkChargeTicks = 0;
		spearKillFlushCooldown = 0;
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
	
	private void updateAimAssist(boolean charging, boolean holdingSpear)
	{
		var aimAssist = WURST.getHax().aimAssistHack;
		if(aimAssist == null)
			return;
		
		boolean shouldEnable = switch(aimAssistMode.getSelected())
		{
			case OFF -> false;
			case WHILE_HOLDING_SPEAR -> holdingSpear;
			case WHILE_CHARGING -> charging;
		};
		
		if(shouldEnable)
		{
			if(!aimAssist.isEnabled())
			{
				aimAssist.setEnabled(true);
				aimAssistTemporarilyEnabled = true;
			}
		}else if(aimAssistTemporarilyEnabled)
		{
			if(aimAssist.isEnabled())
				aimAssist.setEnabled(false);
			
			aimAssistTemporarilyEnabled = false;
		}
		
		if(!aimAssist.isEnabled())
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
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(assistMode.getSelected() == AssistMode.SPEARKILL)
		{
			onSpearKillPacketOutput(event);
			return;
		}
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(assistMode.getSelected() != AssistMode.SPEARKILL)
			return;
		
		if(!(event.getPacket() instanceof ClientboundPlayerPositionPacket))
			return;
		
		if(!spearKillBlinking || MC.player == null)
			return;
		
		spearKillPackets.clear();
		spearKillStartPos = MC.player.position();
		spearKillLastTargetDistance = spearKillTarget != null
			? MC.player.distanceTo(spearKillTarget) : Double.MAX_VALUE;
		spearKillWasApproaching = false;
	}
	
	private void updateModeVisibility()
	{
		AssistMode currentMode = assistMode.getSelected();
		boolean spearKill = currentMode == AssistMode.SPEARKILL;
		boolean assist = !spearKill;
		
		boostMode.setVisibleInGui(assist);
		boostSpeed.setVisibleInGui(assist);
		dashDistance.setVisibleInGui(assist);
		allowReverse.setVisibleInGui(assist);
		stayGrounded.setVisibleInGui(assist);
		nearHighlightRange.setVisibleInGui(assist);
		farHighlightRange.setVisibleInGui(assist);
		nearHighlightColor.setVisibleInGui(assist);
		farHighlightColor.setVisibleInGui(assist);
		autoResumeCharge.setVisibleInGui(assist);
		aimAssistMode.setVisibleInGui(assist);
		aimAssistRangeOverride.setVisibleInGui(assist);
		aimAssistLockOnOverride.setVisibleInGui(assist);
		autoAlignment.setVisibleInGui(assist);
		autoAttackWhenReady.setVisibleInGui(assist);
		
		spearKillMode.setVisibleInGui(spearKill);
		spearKillMaxRange.setVisibleInGui(spearKill);
		spearKillTargetListMode.setVisibleInGui(spearKill);
		spearKillTargetEntities.setVisibleInGui(spearKill);
		spearKillBlinkLunge.setVisibleInGui(spearKill);
		spearKillBlinkLungeStrength.setVisibleInGui(spearKill);
		spearKillBlinkLungeTicks.setVisibleInGui(spearKill);
		spearKillFlushRange.setVisibleInGui(spearKill);
		spearKillMaxFlushRange.setVisibleInGui(spearKill);
		spearKillBlinkAimbot.setVisibleInGui(spearKill);
		spearKillBlinkDistanceBoost.setVisibleInGui(spearKill);
		spearKillLungeStrength.setVisibleInGui(spearKill);
		spearKillStopOnTarget.setVisibleInGui(spearKill);
		spearKillStopDistance.setVisibleInGui(spearKill);
		spearKillLungeDelayModifier.setVisibleInGui(spearKill);
		spearKillChatFeedback.setVisibleInGui(spearKill);
		
		if(currentMode != lastAssistMode)
		{
			lastAssistMode = currentMode;
			refreshSettingsWindow();
		}
	}
	
	private void refreshSettingsWindow()
	{
		String title = getName() + " Settings";
		var gui = WURST.getGuiIfInitialized();
		if(gui == null)
			return;
		
		Window window = gui.findWindowByTitle(title);
		if(window instanceof SettingsWindow settingsWindow)
			settingsWindow.rebuild();
	}
	
	private enum AimAssistMode
	{
		OFF("Off"),
		WHILE_HOLDING_SPEAR("While holding spear"),
		WHILE_CHARGING("While charging");
		
		private final String name;
		
		private AimAssistMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum AssistMode
	{
		ASSIST("Assist"),
		SPEARKILL("SpearKill");
		
		private final String name;
		
		private AssistMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum SpearKillMode
	{
		LUNGE("Lunge"),
		BLINK("Blink");
		
		private final String name;
		
		private SpearKillMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum TargetListMode
	{
		WHITELIST("Whitelist"),
		BLACKLIST("Blacklist");
		
		private final String name;
		
		private TargetListMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private void updateSpearKill()
	{
		if(MC.player == null || MC.level == null || MC.options == null)
			return;
		
		boolean currentlyCharging =
			isSpear(MC.player.getMainHandItem()) && MC.player.isUsingItem();
		
		if(spearKillMode.getSelected() == SpearKillMode.LUNGE)
		{
			if(spearKillBlinking)
				stopSpearKillBlink(true);
			spearKillBlinkChargeTicks = 0;
			spearKillFlushCooldown = 0;
			spearKillWasCharging = currentlyCharging;
			
			if(currentlyCharging)
			{
				if(spearKillTarget == null)
					spearKillTarget = findSpearKillTarget();
				if(spearKillTarget != null && !spearKillTarget.isAlive())
					spearKillTarget = null;
				if(!(spearKillTarget instanceof LivingEntity))
					return;
				if(!isSpearKillValidTarget(spearKillTarget))
					return;
				
				runSpearKillLunge();
			}else
				spearKillTarget = null;
			
			return;
		}
		
		if(currentlyCharging)
		{
			spearKillBlinkChargeTicks++;
			if(spearKillTarget == null || !spearKillTarget.isAlive()
				|| !canSeeSpearKillTarget(spearKillTarget))
			{
				spearKillTarget = findSpearKillTarget();
				spearKillLastTargetDistance = spearKillTarget != null
					? MC.player.distanceTo(spearKillTarget) : Double.MAX_VALUE;
				spearKillWasApproaching = false;
			}
		}else
		{
			spearKillBlinkChargeTicks = 0;
			spearKillTarget = null;
			spearKillLastTargetDistance = Double.MAX_VALUE;
			spearKillWasApproaching = false;
		}
		
		if(currentlyCharging && spearKillBlinkAimbot.isChecked()
			&& spearKillTarget != null)
		{
			rotateToSpearKillTarget(spearKillTarget);
		}
		
		if(currentlyCharging && spearKillBlinkLunge.isChecked()
			&& spearKillTarget != null && spearKillFlushCooldown == 0)
		{
			if(spearKillBlinkChargeTicks >= spearKillBlinkLungeTicks
				.getValueI())
			{
				rotateToSpearKillTarget(spearKillTarget);
				Vec3 viewDir = Vec3.directionFromRotation(MC.player.getXRot(),
					MC.player.getYRot());
				MC.player.setSprinting(true);
				MC.player.setDeltaMovement(
					viewDir.scale(spearKillBlinkLungeStrength.getValue()));
			}
		}
		
		if(spearKillFlushCooldown > 0)
			spearKillFlushCooldown--;
		
		if(currentlyCharging && !spearKillWasCharging)
			startSpearKillBlink();
		
		if(!currentlyCharging && spearKillWasCharging)
		{
			if(spearKillBlinking)
			{
				if(spearKillTarget != null)
					rotateToSpearKillTarget(spearKillTarget);
				
				flushSpearKillPackets();
				spearKillBlinking = false;
				spearKillStartPos = null;
			}
		}
		
		spearKillWasCharging = currentlyCharging;
		
		if(spearKillBlinking && spearKillTarget != null && currentlyCharging)
		{
			double currentDistance = MC.player.distanceTo(spearKillTarget);
			boolean isApproaching =
				currentDistance < spearKillLastTargetDistance;
			boolean shouldFlush = false;
			
			if(currentDistance <= spearKillFlushRange.getValue())
				shouldFlush = true;
			else if(spearKillWasApproaching && !isApproaching
				&& currentDistance < 8.0)
				shouldFlush = true;
			else if(!spearKillBlinkLunge.isChecked()
				&& spearKillStartPos != null && MC.player.position().distanceTo(
					spearKillStartPos) >= spearKillMaxFlushRange.getValue())
			{
				flushSpearKillPackets();
				startSpearKillBlink();
			}
			
			if(shouldFlush)
			{
				rotateToSpearKillTarget(spearKillTarget);
				flushSpearKillPackets();
				
				if(spearKillBlinkLunge.isChecked())
					spearKillFlushCooldown =
						spearKillBlinkLungeTicks.getValueI();
				
				spearKillBlinking = true;
				spearKillStartPos = MC.player.position();
				spearKillPackets.clear();
				spearKillLastTargetDistance =
					MC.player.distanceTo(spearKillTarget);
				spearKillWasApproaching = false;
			}else
			{
				spearKillLastTargetDistance = currentDistance;
				spearKillWasApproaching = isApproaching;
			}
		}
	}
	
	private void runSpearKillLunge()
	{
		if(MC.player == null || spearKillTarget == null)
			return;
		
		int readyTicks = getSpearKillReadyTicks(MC.player.getMainHandItem());
		rotateToSpearKillTarget(spearKillTarget);
		
		ItemStack useStack = MC.player.getUseItem();
		int useTicks = useStack.getUseDuration(MC.player)
			- MC.player.getUseItemRemainingTicks();
		if(useTicks <= readyTicks)
			return;
		
		if(spearKillStopOnTarget.isChecked())
		{
			AABB playerBox = MC.player.getBoundingBox()
				.inflate(spearKillStopDistance.getValue());
			AABB targetBox = spearKillTarget.getBoundingBox();
			if(!playerBox.intersects(targetBox))
			{
				applySpearKillLungeVelocity();
			}else
			{
				spearKillTarget = null;
				MC.player.setDeltaMovement(Vec3.ZERO);
				MC.player.setSprinting(false);
			}
		}else
			applySpearKillLungeVelocity();
	}
	
	private void applySpearKillLungeVelocity()
	{
		double lungeSpeed = spearKillLungeStrength.getValue();
		Vec3 viewDir = Vec3.directionFromRotation(MC.player.getXRot(),
			MC.player.getYRot());
		MC.player.setSprinting(true);
		MC.player.setDeltaMovement(viewDir.scale(lungeSpeed));
	}
	
	private void rotateToSpearKillTarget(Entity target)
	{
		if(MC.player == null || target == null)
			return;
		
		Vec3 playerPos = MC.player.getEyePosition(1F);
		AABB box = target.getBoundingBox();
		double targetCenterY = box.getCenter().y;
		double heightDiff = targetCenterY - playerPos.y;
		double targetY;
		double boxHeight = box.maxY - box.minY;
		
		if(Math.abs(heightDiff) < 1.0)
			targetY = targetCenterY;
		else if(heightDiff > 0)
		{
			double offset = Math.min(heightDiff / 5.0, 0.4);
			targetY = targetCenterY - (boxHeight * offset);
		}else
		{
			double offset = Math.min(-heightDiff / 5.0, 0.4);
			targetY = targetCenterY + (boxHeight * offset);
		}
		
		Vec3 targetPos =
			new Vec3(box.getCenter().x, targetY, box.getCenter().z);
		Vec3 toTarget = targetPos.subtract(playerPos).normalize();
		float yaw =
			(float)(Math.toDegrees(Math.atan2(toTarget.z, toTarget.x)) - 90.0);
		float pitch = (float)-Math.toDegrees(Math.asin(toTarget.y));
		MC.player.setYRot(yaw);
		MC.player.setYHeadRot(yaw);
		MC.player.setXRot(pitch);
	}
	
	private void startSpearKillBlink()
	{
		spearKillBlinking = true;
		spearKillStartPos = MC.player.position();
		spearKillPackets.clear();
		spearKillLastTargetDistance = spearKillTarget != null
			? MC.player.distanceTo(spearKillTarget) : Double.MAX_VALUE;
		spearKillWasApproaching = false;
	}
	
	private void stopSpearKillBlink(boolean force)
	{
		if(!spearKillBlinking && !force)
			return;
		
		flushSpearKillPackets();
		spearKillBlinking = false;
		spearKillStartPos = null;
	}
	
	private void flushSpearKillPackets()
	{
		if(MC.player == null || MC.level == null
			|| MC.player.connection == null)
		{
			spearKillPackets.clear();
			return;
		}
		
		if(spearKillPackets.isEmpty())
			return;
		
		Vec3 currentPos = MC.player.position();
		double distance = spearKillStartPos != null
			? spearKillStartPos.distanceTo(currentPos) : 0;
		if(distance < spearKillFlushRange.getValue())
		{
			spearKillPackets.clear();
			return;
		}
		
		Vec3 sendStartPos = spearKillStartPos;
		double boost = spearKillBlinkDistanceBoost.getValue();
		if(boost > 0 && spearKillStartPos != null)
		{
			Vec3 direction = currentPos.subtract(spearKillStartPos);
			Vec3 horizontalDir = new Vec3(direction.x, 0, direction.z);
			if(horizontalDir.lengthSqr() > 1.0E-4)
			{
				Vec3 norm = horizontalDir.normalize();
				Vec3 targetPos = spearKillStartPos.subtract(norm.scale(boost));
				
				HitResult hit = MC.level.clip(new ClipContext(spearKillStartPos,
					targetPos, ClipContext.Block.COLLIDER,
					ClipContext.Fluid.NONE, MC.player));
				
				if(hit == null || hit.getType() == HitResult.Type.MISS)
					sendStartPos = targetPos;
				else
					sendStartPos = hit.getLocation().add(norm.scale(0.5));
			}
		}
		
		if(sendStartPos != null)
		{
			spearKillFlushing = true;
			try
			{
				ServerboundMovePlayerPacket startPacket =
					new ServerboundMovePlayerPacket.PosRot(sendStartPos.x,
						sendStartPos.y, sendStartPos.z, MC.player.getYRot(),
						MC.player.getXRot(), false, false);
				MC.player.connection.send(startPacket);
				
				if(spearKillChatFeedback.isChecked())
				{
					double totalDist = sendStartPos.distanceTo(currentPos);
					ChatUtils.message(String.format(Locale.ROOT,
						"SK flush: %.1f blocks (actual=%.1f, boost=%.1f)",
						totalDist, distance, boost));
				}
				
				ServerboundMovePlayerPacket endPacket =
					new ServerboundMovePlayerPacket.PosRot(currentPos.x,
						currentPos.y, currentPos.z, MC.player.getYRot(),
						MC.player.getXRot(), MC.player.onGround(),
						MC.player.horizontalCollision);
				MC.player.connection.send(endPacket);
			}finally
			{
				spearKillFlushing = false;
			}
		}
		
		spearKillPackets.clear();
	}
	
	private void onSpearKillPacketOutput(PacketOutputEvent event)
	{
		if(spearKillFlushing || !spearKillBlinking)
			return;
		
		if(!(event.getPacket() instanceof ServerboundMovePlayerPacket packet))
			return;
		
		event.cancel();
		
		ServerboundMovePlayerPacket prevPacket = spearKillPackets.peekLast();
		if(prevPacket != null && packet.isOnGround() == prevPacket.isOnGround()
			&& packet.getYRot(-1) == prevPacket.getYRot(-1)
			&& packet.getXRot(-1) == prevPacket.getXRot(-1)
			&& packet.getX(-1) == prevPacket.getX(-1)
			&& packet.getY(-1) == prevPacket.getY(-1)
			&& packet.getZ(-1) == prevPacket.getZ(-1))
			return;
		
		spearKillPackets.addLast(packet);
	}
	
	private Entity findSpearKillTarget()
	{
		if(MC.player == null || MC.level == null)
			return null;
		
		if(MC.hitResult instanceof EntityHitResult hit)
			if(isSpearKillValidTarget(hit.getEntity()))
				return hit.getEntity();
			
		double maxRange = spearKillMaxRange.getValue();
		Vec3 eyePos = MC.player.getEyePosition(1F);
		Vec3 lookVec = MC.player.getLookAngle();
		HitResult blockHit = MC.level
			.clip(new ClipContext(eyePos, eyePos.add(lookVec.scale(maxRange)),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player));
		double rayLength = blockHit.getType() == HitResult.Type.MISS ? maxRange
			: eyePos.distanceTo(blockHit.getLocation());
		
		AABB searchBox =
			MC.player.getBoundingBox().expandTowards(lookVec.scale(rayLength));
		List<LivingEntity> candidates = MC.level.getEntitiesOfClass(
			LivingEntity.class, searchBox, e -> e.isAlive() && e != MC.player);
		
		candidates.sort(Comparator.comparingDouble(
			e -> eyePos.distanceToSqr(e.getBoundingBox().getCenter())));
		
		double coneAngle = 0.999;
		for(LivingEntity e : candidates)
		{
			double dist = eyePos.distanceTo(e.getBoundingBox().getCenter());
			if(dist > maxRange)
				break;
			if(!isSpearKillValidTarget(e))
				continue;
			if(!canSeeSpearKillTarget(e))
				continue;
			Vec3 toEntity =
				e.getBoundingBox().getCenter().subtract(eyePos).normalize();
			if(lookVec.dot(toEntity) > coneAngle)
				return e;
		}
		
		return null;
	}
	
	private boolean canSeeSpearKillTarget(Entity target)
	{
		if(MC.player == null || MC.level == null)
			return false;
		
		Vec3 eyePos = MC.player.getEyePosition(1F);
		Vec3 targetCenter = target.getBoundingBox().getCenter();
		HitResult result = MC.level.clip(new ClipContext(eyePos, targetCenter,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, MC.player));
		if(result.getType() == HitResult.Type.MISS)
			return true;
		return eyePos.distanceTo(
			result.getLocation()) >= eyePos.distanceTo(targetCenter) - 0.5;
	}
	
	private boolean isSpearKillValidTarget(Entity entity)
	{
		if(entity == null)
			return false;
		EntityType<?> type = entity.getType();
		Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
		if(id == null)
			return false;
		boolean inList =
			spearKillTargetEntities.getTypeNames().contains(id.toString());
		if(spearKillTargetListMode.getSelected() == TargetListMode.WHITELIST)
			return inList || spearKillTargetEntities.getTypeNames().isEmpty();
		return !inList;
	}
	
	private int getSpearKillReadyTicks(ItemStack stack)
	{
		String name = stack.getItem().toString().toLowerCase(Locale.ROOT);
		int value = 14;
		if(name.contains("wooden"))
			value = 14;
		else if(name.contains("stone") || name.contains("golden"))
			value = 13;
		else if(name.contains("copper"))
			value = 12;
		else if(name.contains("iron"))
			value = 11;
		else if(name.contains("diamond"))
			value = 9;
		else if(name.contains("netherite"))
			value = 7;
		
		return Math
			.round(value * (spearKillLungeDelayModifier.getValueI() / 100F));
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
