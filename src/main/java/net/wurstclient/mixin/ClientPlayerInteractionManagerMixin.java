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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.BlockBreakingProgressListener.BlockBreakingProgressEvent;
import net.wurstclient.events.PlayerAttacksEntityListener.PlayerAttacksEntityEvent;
import net.wurstclient.events.StopUsingItemListener.StopUsingItemEvent;
import net.wurstclient.hacks.AntiDropHack;
import net.wurstclient.mixinterface.IClientPlayerInteractionManager;

@Mixin(MultiPlayerGameMode.class)
public abstract class ClientPlayerInteractionManagerMixin
	implements IClientPlayerInteractionManager
{
	@Shadow
	@Final
	private Minecraft minecraft;
	
	private boolean antiDropBypassingPlacement;
	
	@Inject(
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/player/LocalPlayer;getId()I",
			ordinal = 0),
		method = "continueDestroyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;)Z")
	private void onPlayerDamageBlock(BlockPos pos, Direction direction,
		CallbackInfoReturnable<Boolean> cir)
	{
		EventManager.fire(new BlockBreakingProgressEvent(pos, direction));
	}
	
	@Inject(at = @At("HEAD"),
		method = "releaseUsingItem(Lnet/minecraft/world/entity/player/Player;)V")
	private void onStopUsingItem(Player player, CallbackInfo ci)
	{
		EventManager.fire(StopUsingItemEvent.INSTANCE);
	}
	
	@Inject(at = @At("HEAD"),
		method = "handleInventoryMouseClick(IIILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
		cancellable = true)
	private void onClickSlotHEAD(int syncId, int slotId, int button,
		ClickType actionType, Player player, CallbackInfo ci)
	{
		if(actionType != ClickType.THROW)
			return;
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		AntiDropHack antiDrop = WurstClient.INSTANCE.getHax().antiDropHack;
		if(!antiDrop.isEnabled())
			return;
		
		ItemStack stack = ItemStack.EMPTY;
		
		if(slotId == -999 && player.containerMenu != null
			&& player.containerMenu.containerId == syncId)
		{
			stack = player.containerMenu.getCarried();
			
		}else if(slotId >= 0)
		{
			if(player.containerMenu != null
				&& player.containerMenu.containerId == syncId
				&& slotId < player.containerMenu.slots.size())
			{
				Slot slot = player.containerMenu.getSlot(slotId);
				if(slot != null)
					stack = slot.getItem();
				
			}else if(player.inventoryMenu != null
				&& player.inventoryMenu.containerId == syncId
				&& slotId < player.inventoryMenu.slots.size())
			{
				Slot slot = player.inventoryMenu.getSlot(slotId);
				if(slot != null)
					stack = slot.getItem();
			}
		}
		
		if(!antiDrop.shouldBlock(stack))
			return;
		
		ci.cancel();
	}
	
	@Inject(at = @At("HEAD"),
		method = "attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V")
	private void onAttackEntity(Player player, Entity target, CallbackInfo ci)
	{
		if(player != minecraft.player)
			return;
		
		EventManager.fire(new PlayerAttacksEntityEvent(target));
	}
	
	@Override
	public void windowClick_PICKUP(int slot)
	{
		handleInventoryMouseClick(0, slot, 0, ClickType.PICKUP,
			minecraft.player);
	}
	
	@Override
	public void windowClick_QUICK_MOVE(int slot)
	{
		handleInventoryMouseClick(0, slot, 0, ClickType.QUICK_MOVE,
			minecraft.player);
	}
	
	@Override
	public void windowClick_THROW(int slot)
	{
		handleInventoryMouseClick(0, slot, 1, ClickType.THROW,
			minecraft.player);
	}
	
	@Override
	public void windowClick_SWAP(int from, int to)
	{
		handleInventoryMouseClick(0, from, to, ClickType.SWAP,
			minecraft.player);
	}
	
	@Override
	public void rightClickItem()
	{
		useItem(minecraft.player, InteractionHand.MAIN_HAND);
	}
	
	@Override
	public void rightClickBlock(BlockPos pos, Direction side, Vec3 hitVec)
	{
		BlockHitResult hitResult = new BlockHitResult(hitVec, side, pos, false);
		InteractionHand hand = InteractionHand.MAIN_HAND;
		useItemOn(minecraft.player, hand, hitResult);
		useItem(minecraft.player, hand);
	}
	
	@Override
	public void sendPlayerActionC2SPacket(Action action, BlockPos blockPos,
		Direction direction)
	{
		startPrediction(minecraft.level,
			i -> new ServerboundPlayerActionPacket(action, blockPos, direction,
				i));
	}
	
	@Override
	public void sendPlayerInteractBlockPacket(InteractionHand hand,
		BlockHitResult blockHitResult)
	{
		startPrediction(minecraft.level,
			i -> new ServerboundUseItemOnPacket(hand, blockHitResult, i));
	}
	
	@Inject(method = "useItemOn", at = @At(value = "HEAD"))
	private void wurst$allowBlockPlacementBypass(LocalPlayer player,
		InteractionHand hand, BlockHitResult hitResult,
		CallbackInfoReturnable<InteractionResult> cir)
	{
		if(player == null || !WurstClient.INSTANCE.isEnabled())
			return;
		
		AntiDropHack antiDrop = WurstClient.INSTANCE.getHax().antiDropHack;
		if(antiDrop == null || !antiDrop.isEnabled())
			return;
		
		ItemStack stack = player.getItemInHand(hand);
		if(stack == null || stack.isEmpty()
			|| !(stack.getItem() instanceof BlockItem))
			return;
		
		if(!antiDrop.isProtectedItem(stack))
			return;
		antiDrop.setTemporarilyBypass(true);
		antiDropBypassingPlacement = true;
	}
	
	@Inject(method = "useItemOn", at = @At(value = "RETURN"))
	private void wurst$resetBlockPlacementBypass(LocalPlayer player,
		InteractionHand hand, BlockHitResult hitResult,
		CallbackInfoReturnable<InteractionResult> cir)
	{
		AntiDropHack antiDrop = WurstClient.INSTANCE.getHax().antiDropHack;
		if(antiDrop == null)
		{
			antiDropBypassingPlacement = false;
			return;
		}
		
		if(!antiDropBypassingPlacement && !antiDrop.isTemporarilyBypassing())
			return;
		
		antiDropBypassingPlacement = false;
		antiDrop.setTemporarilyBypass(false);
	}
	
	@Shadow
	private void startPrediction(ClientLevel world,
		PredictiveAction packetCreator)
	{
		
	}
	
	@Shadow
	public abstract InteractionResult useItemOn(LocalPlayer player,
		InteractionHand hand, BlockHitResult hitResult);
	
	@Shadow
	public abstract InteractionResult useItem(Player player,
		InteractionHand hand);
	
	@Shadow
	public abstract void handleInventoryMouseClick(int syncId, int slotId,
		int button, ClickType actionType, Player player);
}
