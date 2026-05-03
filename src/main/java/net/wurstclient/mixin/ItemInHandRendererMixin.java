/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.WurstClient;

@Mixin(ItemInHandRenderer.class)
public abstract class ItemInHandRendererMixin
{
	/**
	 * Apply blocking offset from a guaranteed entry point. Some game versions
	 * change internal call order/ordinals for the BLOCK branch.
	 */
	@Inject(
		method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
		at = @At("HEAD"),
		cancellable = true)
	private void onApplyEquipOffsetBlocking(AbstractClientPlayer player,
		float tickProgress, float pitch, InteractionHand hand,
		float swingProgress, ItemStack item, float equipProgress,
		PoseStack matrices, SubmitNodeCollector entityRenderCommandQueue,
		int light, CallbackInfo ci)
	{
		if(WurstClient.INSTANCE.getHax().viewmodelHack.shouldHide(player, hand))
		{
			ci.cancel();
			return;
		}
		
		WurstClient.INSTANCE.getHax().viewmodelHack.applyTransform(player, hand,
			matrices);
		
		boolean blocking = player.isUsingItem()
			&& player.getUseItem().getItem() == Items.SHIELD;
		
		// lower shield using the live blocking state
		if(item.getItem() == Items.SHIELD)
			WurstClient.INSTANCE.getHax().noShieldOverlayHack
				.adjustShieldPosition(matrices, blocking);
	}
	
	/**
	 * This mixin is injected into the last `else` block of
	 * renderFirstPersonItem(), right after `else if(player.isUsingRiptide())`.
	 */
	@Inject(
		method = "renderArmWithItem(Lnet/minecraft/client/player/AbstractClientPlayer;FFLnet/minecraft/world/InteractionHand;FLnet/minecraft/world/item/ItemStack;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;I)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/ItemInHandRenderer;swingArm(FLcom/mojang/blaze3d/vertex/PoseStack;ILnet/minecraft/world/entity/HumanoidArm;)V",
			ordinal = 2))
	private void onApplySwingOffsetNotBlocking(AbstractClientPlayer player,
		float tickProgress, float pitch, InteractionHand hand,
		float swingProgress, ItemStack item, float equipProgress,
		PoseStack matrices, SubmitNodeCollector entityRenderCommandQueue,
		int light, CallbackInfo ci)
	{
		// Keep non-blocking adjustment near the original vanilla swing
		// transform.
		if(item.getItem() == Items.SHIELD)
		{
			boolean blocking = player.isUsingItem()
				&& player.getUseItem().getItem() == Items.SHIELD;
			if(!blocking)
				WurstClient.INSTANCE.getHax().noShieldOverlayHack
					.adjustShieldPosition(matrices, false);
		}
	}
}
