/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
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

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.SequencedPacketCreator;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket.Action;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.BlockBreakingProgressListener.BlockBreakingProgressEvent;
import net.wurstclient.events.PlayerAttacksEntityListener.PlayerAttacksEntityEvent;
import net.wurstclient.events.StopUsingItemListener.StopUsingItemEvent;
import net.wurstclient.hacks.AntiDropHack;
import net.wurstclient.mixinterface.IClientPlayerInteractionManager;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin
	implements IClientPlayerInteractionManager
{
	@Shadow
	@Final
	private MinecraftClient client;
	
	private boolean antiDropBypassingPlacement;
	
	@Inject(at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/network/ClientPlayerEntity;getId()I",
		ordinal = 0),
		method = "updateBlockBreakingProgress(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/Direction;)Z")
	private void onPlayerDamageBlock(BlockPos pos, Direction direction,
		CallbackInfoReturnable<Boolean> cir)
	{
		EventManager.fire(new BlockBreakingProgressEvent(pos, direction));
	}
	
	@Inject(at = @At("HEAD"),
		method = "stopUsingItem(Lnet/minecraft/entity/player/PlayerEntity;)V")
	private void onStopUsingItem(PlayerEntity player, CallbackInfo ci)
	{
		EventManager.fire(StopUsingItemEvent.INSTANCE);
	}
	
	@Inject(at = @At("HEAD"),
		method = "clickSlot(IIILnet/minecraft/screen/slot/SlotActionType;Lnet/minecraft/entity/player/PlayerEntity;)V",
		cancellable = true)
	private void onClickSlotHEAD(int syncId, int slotId, int button,
		SlotActionType actionType, PlayerEntity player, CallbackInfo ci)
	{
		if(actionType != SlotActionType.THROW)
			return;
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		AntiDropHack antiDrop = WurstClient.INSTANCE.getHax().antiDropHack;
		if(!antiDrop.isEnabled())
			return;
		
		ItemStack stack = ItemStack.EMPTY;
		
		if(slotId == -999 && player.currentScreenHandler != null
			&& player.currentScreenHandler.syncId == syncId)
		{
			stack = player.currentScreenHandler.getCursorStack();
			
		}else if(slotId >= 0)
		{
			if(player.currentScreenHandler != null
				&& player.currentScreenHandler.syncId == syncId
				&& slotId < player.currentScreenHandler.slots.size())
			{
				Slot slot = player.currentScreenHandler.getSlot(slotId);
				if(slot != null)
					stack = slot.getStack();
				
			}else if(player.playerScreenHandler != null
				&& player.playerScreenHandler.syncId == syncId
				&& slotId < player.playerScreenHandler.slots.size())
			{
				Slot slot = player.playerScreenHandler.getSlot(slotId);
				if(slot != null)
					stack = slot.getStack();
			}
		}
		
		if(!antiDrop.shouldBlock(stack))
			return;
		
		ci.cancel();
	}
	
	@Inject(at = @At("HEAD"),
		method = "attackEntity(Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/entity/Entity;)V")
	private void onAttackEntity(PlayerEntity player, Entity target,
		CallbackInfo ci)
	{
		if(player != client.player)
			return;
		
		EventManager.fire(new PlayerAttacksEntityEvent(target));
	}
	
	@Override
	public void windowClick_PICKUP(int slot)
	{
		clickSlot(0, slot, 0, SlotActionType.PICKUP, client.player);
	}
	
	@Override
	public void windowClick_QUICK_MOVE(int slot)
	{
		clickSlot(0, slot, 0, SlotActionType.QUICK_MOVE, client.player);
	}
	
	@Override
	public void windowClick_THROW(int slot)
	{
		clickSlot(0, slot, 1, SlotActionType.THROW, client.player);
	}
	
	@Override
	public void windowClick_SWAP(int from, int to)
	{
		clickSlot(0, from, to, SlotActionType.SWAP, client.player);
	}
	
	@Override
	public void rightClickItem()
	{
		interactItem(client.player, Hand.MAIN_HAND);
	}
	
	@Override
	public void rightClickBlock(BlockPos pos, Direction side, Vec3d hitVec)
	{
		BlockHitResult hitResult = new BlockHitResult(hitVec, side, pos, false);
		Hand hand = Hand.MAIN_HAND;
		interactBlock(client.player, hand, hitResult);
		interactItem(client.player, hand);
	}
	
	@Override
	public void sendPlayerActionC2SPacket(Action action, BlockPos blockPos,
		Direction direction)
	{
		sendSequencedPacket(client.world,
			i -> new PlayerActionC2SPacket(action, blockPos, direction, i));
	}
	
	@Override
	public void sendPlayerInteractBlockPacket(Hand hand,
		BlockHitResult blockHitResult)
	{
		sendSequencedPacket(client.world,
			i -> new PlayerInteractBlockC2SPacket(hand, blockHitResult, i));
	}
	
	@Inject(method = "interactBlock", at = @At(value = "HEAD"))
	private void wurst$allowBlockPlacementBypass(ClientPlayerEntity player,
		Hand hand, BlockHitResult hitResult,
		CallbackInfoReturnable<ActionResult> cir)
	{
		if(player == null || !WurstClient.INSTANCE.isEnabled())
			return;
		
		AntiDropHack antiDrop = WurstClient.INSTANCE.getHax().antiDropHack;
		if(antiDrop == null || !antiDrop.isEnabled())
			return;
		
		ItemStack stack = player.getStackInHand(hand);
		if(stack == null || stack.isEmpty()
			|| !(stack.getItem() instanceof BlockItem))
			return;
		
		if(!antiDrop.isProtectedItem(stack))
			return;
		antiDrop.setTemporarilyBypass(true);
		antiDropBypassingPlacement = true;
	}
	
	@Inject(method = "interactBlock", at = @At(value = "RETURN"))
	private void wurst$resetBlockPlacementBypass(ClientPlayerEntity player,
		Hand hand, BlockHitResult hitResult,
		CallbackInfoReturnable<ActionResult> cir)
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
	private void sendSequencedPacket(ClientWorld world,
		SequencedPacketCreator packetCreator)
	{
		
	}
	
	@Shadow
	public abstract ActionResult interactBlock(ClientPlayerEntity player,
		Hand hand, BlockHitResult hitResult);
	
	@Shadow
	public abstract ActionResult interactItem(PlayerEntity player, Hand hand);
	
	@Shadow
	public abstract void clickSlot(int syncId, int slotId, int button,
		SlotActionType actionType, PlayerEntity player);
}
