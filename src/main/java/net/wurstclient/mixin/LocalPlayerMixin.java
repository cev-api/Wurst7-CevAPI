/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.InputFaker;
import net.wurstclient.InputFaker.TempRealInput;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.FlyingSpeedListener.FlyingSpeedEvent;
import net.wurstclient.events.IsPlayerInLavaListener.IsPlayerInLavaEvent;
import net.wurstclient.events.IsPlayerInWaterListener.IsPlayerInWaterEvent;
import net.wurstclient.events.MobEffectListener.MobEffectEvent;
import net.wurstclient.events.PlayerMoveListener.PlayerMoveEvent;
import net.wurstclient.events.PostMotionListener.PostMotionEvent;
import net.wurstclient.events.PreMotionListener.PreMotionEvent;
import net.wurstclient.events.UpdateListener.UpdateEvent;
import net.wurstclient.hack.HackList;
import net.wurstclient.mixinterface.ILocalPlayer;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin extends AbstractClientPlayer
	implements ILocalPlayer
{
	@Shadow
	@Final
	protected Minecraft minecraft;
	
	@Shadow
	private int positionReminder;
	
	private LocalPlayerMixin(WurstClient wurst, ClientLevel world,
		GameProfile profile)
	{
		super(world, profile);
	}
	
	@Inject(method = "tick()V", at = @At("HEAD"))
	private void onTickHead(CallbackInfo ci)
	{
		InputFaker.swapIfNeeded();
	}
	
	@Inject(method = "tick()V", at = @At("RETURN"))
	private void onTickReturn(CallbackInfo ci)
	{
		InputFaker.restoreIfNeeded();
	}
	
	@Inject(method = "rideTick()V", at = @At("HEAD"))
	private void onRideTickHead(CallbackInfo ci)
	{
		InputFaker.swapIfNeeded();
	}
	
	@Inject(method = "rideTick()V", at = @At("RETURN"))
	private void onRideTickReturn(CallbackInfo ci)
	{
		InputFaker.restoreIfNeeded();
	}
	
	@Inject(method = "tick()V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/AbstractClientPlayer;tick()V",
			ordinal = 0))
	private void onTick(CallbackInfo ci)
	{
		try(TempRealInput _ = new TempRealInput())
		{
			EventManager.fire(UpdateEvent.INSTANCE);
		}
	}
	
	/**
	 * Makes you keep sprinting when using an item while NoSlowdown is enabled.
	 */
	@WrapOperation(method = "aiStep()V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;isSlowDueToUsingItem()Z",
			ordinal = 0))
	private boolean wrapTickMovementItemUse(LocalPlayer instance,
		Operation<Boolean> original)
	{
		if(WurstClient.INSTANCE.getHax().noSlowdownHack.isEnabled()
			|| WurstClient.INSTANCE.getHax().speedHackHack
				.shouldIgnoreSlowdownsForPotionMode())
			return false;
		
		return original.call(instance);
	}
	
	/**
	 * Prevents item-use movement slowdown while NoSlowdown is enabled.
	 */
	@WrapOperation(
		method = "modifyInput(Lnet/minecraft/world/phys/Vec2;)Lnet/minecraft/world/phys/Vec2;",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z",
			ordinal = 0))
	private boolean wrapModifyInputItemUse(LocalPlayer instance,
		Operation<Boolean> original)
	{
		if(WurstClient.INSTANCE.getHax().noSlowdownHack.isEnabled())
			return false;
		
		return original.call(instance);
	}
	
	/**
	 * Allows sprinting to start while using an item when NoSlowdown is enabled.
	 */
	@WrapOperation(method = "canStartSprinting()Z",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;isSlowDueToUsingItem()Z",
			ordinal = 0))
	private boolean wrapCanStartSprintingItemUse(LocalPlayer instance,
		Operation<Boolean> original)
	{
		if(WurstClient.INSTANCE.getHax().noSlowdownHack.isEnabled())
			return false;
		
		return original.call(instance);
	}
	
	@Override
	public void elytraWalk$setPositionReminder(int value)
	{
		positionReminder = value;
	}
	
	@Inject(method = "sendPosition()V", at = @At("HEAD"))
	private void onSendMovementPacketsHEAD(CallbackInfo ci)
	{
		EventManager.fire(PreMotionEvent.INSTANCE);
	}
	
	@Inject(method = "sendPosition()V", at = @At("TAIL"))
	private void onSendMovementPacketsTAIL(CallbackInfo ci)
	{
		EventManager.fire(PostMotionEvent.INSTANCE);
	}
	
	@ModifyVariable(
		method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
		at = @At("HEAD"),
		argsOnly = true,
		ordinal = 0)
	private Vec3 elytraWalk$groundSkating(Vec3 movement, MoverType type,
		Vec3 originalMovement)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		return hax == null ? movement
			: hax.elytraWalkHack.getGroundMovement(type, movement);
	}
	
	@Inject(
		method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
		at = @At("HEAD"))
	private void onMove(MoverType type, Vec3 offset, CallbackInfo ci)
	{
		EventManager.fire(PlayerMoveEvent.INSTANCE);
	}
	
	@Inject(method = "isAutoJumpEnabled()Z",
		at = @At("HEAD"),
		cancellable = true)
	private void onIsAutoJumpEnabled(CallbackInfoReturnable<Boolean> cir)
	{
		if(!WurstClient.INSTANCE.getHax().stepHack.isAutoJumpAllowed())
			cir.setReturnValue(false);
	}
	
	/**
	 * Prevents the portal nausea code from closing a GUI opened by PortalGUI.
	 * Returning null only for this check keeps the real screen untouched, which
	 * avoids resetting mouse capture while the player is in the portal.
	 */
	@WrapOperation(method = "handlePortalTransitionEffect(Z)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/Gui;screen()Lnet/minecraft/client/gui/screens/Screen;",
			ordinal = 0))
	private Screen wrapPortalGuiScreen(Gui instance, Operation<Screen> original)
	{
		if(WurstClient.INSTANCE.getHax().portalGuiHack.isEnabled())
			return null;
		
		return original.call(instance);
	}
	
	/**
	 * Getter method for what used to be airStrafingSpeed.
	 * Overridden to allow for the speed to be modified by hacks.
	 */
	/**
	 * Prevents flying up with Flight activating elytra.
	 */
	@Override
	public boolean canGlide()
	{
		return !WurstClient.INSTANCE.getHax().flightHack.isEnabled()
			&& super.canGlide();
	}
	
	/**
	 * Prevents Flight getting horizontally stuck if elytra is already active.
	 */
	@Override
	public boolean isFallFlying()
	{
		return !WurstClient.INSTANCE.getHax().flightHack.isEnabled()
			&& super.isFallFlying();
	}
	
	@Override
	protected float getFlyingSpeed()
	{
		FlyingSpeedEvent event = new FlyingSpeedEvent(super.getFlyingSpeed());
		EventManager.fire(event);
		return event.getSpeed();
	}
	
	@Override
	public void lerpMotion(Vec3 vec)
	{
		super.lerpMotion(WurstClient.INSTANCE.getHax().antiKnockbackHack
			.modifyKnockback(vec));
	}
	
	@Override
	public boolean isInWater()
	{
		boolean inWater = super.isInWater();
		IsPlayerInWaterEvent event = new IsPlayerInWaterEvent(inWater);
		EventManager.fire(event);
		
		return event.isInWater();
	}
	
	@Override
	public boolean isInLava()
	{
		boolean inLava = super.isInLava();
		IsPlayerInLavaEvent event = new IsPlayerInLavaEvent(inLava);
		EventManager.fire(event);
		
		return event.isInLava();
	}
	
	@Override
	public boolean isTouchingWaterBypass()
	{
		return super.isInWater();
	}
	
	@Override
	protected float getJumpPower()
	{
		return super.getJumpPower() + WurstClient.INSTANCE.getHax().highJumpHack
			.getAdditionalJumpMotion();
	}
	
	/**
	 * This is the part that makes SafeWalk work.
	 */
	@Override
	protected boolean isStayingOnGroundSurface()
	{
		return super.isStayingOnGroundSurface()
			|| WurstClient.INSTANCE.getHax().safeWalkHack.isEnabled();
	}
	
	/**
	 * This mixin allows SafeWalk to sneak visibly when the player is
	 * near a ledge.
	 */
	@Override
	protected Vec3 maybeBackOffFromEdge(Vec3 movement, MoverType type)
	{
		Vec3 result = super.maybeBackOffFromEdge(movement, type);
		
		if(movement != null)
			WurstClient.INSTANCE.getHax().safeWalkHack
				.onClipAtLedge(!movement.equals(result));
		
		return result;
	}
	
	@Override
	public boolean hasEffect(Holder<MobEffect> effect)
	{
		return getEffect(effect) != null;
	}
	
	@Override
	public MobEffectInstance getEffect(Holder<MobEffect> effect)
	{
		if(effect == MobEffects.SLOWNESS
			&& WurstClient.INSTANCE.getHax().speedHackHack
				.shouldIgnoreSlowdownsForPotionMode())
			return null;
		MobEffectEvent event =
			new MobEffectEvent(effect, super.getEffect(effect));
		EventManager.fire(event);
		return event.getInstance();
	}
	
	@Override
	public float maxUpStep()
	{
		return WurstClient.INSTANCE.getHax().stepHack
			.adjustStepHeight(super.maxUpStep());
	}
	
	@Override
	public double blockInteractionRange()
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax != null && hax.bedBreakAuraHack.isEnabled())
			return hax.bedBreakAuraHack.getInteractionRange();
		if(hax == null || !hax.reachHack.isEnabled())
			return super.blockInteractionRange();
		
		return hax.reachHack.getReachDistance();
	}
	
	@Override
	public double entityInteractionRange()
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax != null && hax.bedBreakAuraHack.isEnabled())
			return hax.bedBreakAuraHack.getInteractionRange();
		if(hax == null || !hax.reachHack.isEnabled())
			return super.entityInteractionRange();
		
		return hax.reachHack.getReachDistance();
	}
	
	/**
	 * Makes Liquids work. Must take priority over Freecam's raycast wrapper.
	 */
	@ModifyArg(
		method = "pick(Lnet/minecraft/world/entity/Entity;DDF)Lnet/minecraft/world/phys/HitResult;",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/Entity;pick(DFZ)Lnet/minecraft/world/phys/HitResult;",
			ordinal = 0),
		index = 2)
	private static boolean modifyIncludeFluidsForLiquids(boolean includeFluids)
	{
		return includeFluids
			|| WurstClient.INSTANCE.getHax().liquidsHack.isEnabled();
	}
}
