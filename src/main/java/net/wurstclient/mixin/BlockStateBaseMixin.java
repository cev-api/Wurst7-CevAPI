/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.wurstclient.WurstClient;
import net.wurstclient.event.EventManager;
import net.wurstclient.events.IsNormalCubeListener.IsNormalCubeEvent;
import net.wurstclient.hack.HackList;
import net.wurstclient.hacks.HandNoClipHack;

@Mixin(BlockStateBase.class)
public abstract class BlockStateBaseMixin extends StateHolder<Block, BlockState>
{
	private BlockStateBaseMixin(WurstClient wurst, Block owner,
		Property<?>[] properties, Comparable<?>[] values)
	{
		super(owner, properties, values);
	}
	
	@Inject(
		method = "isCollisionShapeFullBlock(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z",
		at = @At("TAIL"),
		cancellable = true)
	private void onIsFullCube(BlockGetter world, BlockPos pos,
		CallbackInfoReturnable<Boolean> cir)
	{
		IsNormalCubeEvent event = new IsNormalCubeEvent();
		EventManager.fire(event);
		
		cir.setReturnValue(cir.getReturnValue() && !event.isCancelled());
	}
	
	// Prevent the "inside block" screen overlay while legacy Freecam is active
	@Inject(at = @At("HEAD"),
		method = "isViewBlocking(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Z",
		cancellable = true)
	private void onIsViewBlocking(BlockGetter world, BlockPos pos,
		CallbackInfoReturnable<Boolean> cir)
	{
		if(WurstClient.INSTANCE.getHax() != null
			&& WurstClient.INSTANCE.getHax().freecamHack.isLegacyModeActive())
		{
			cir.setReturnValue(false);
		}
	}
	
	@Inject(at = @At("HEAD"),
		method = "getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		cancellable = true)
	private void onGetOutlineShape(BlockGetter view, BlockPos pos,
		CollisionContext context, CallbackInfoReturnable<VoxelShape> cir)
	{
		if(context == CollisionContext.empty())
			return;
		
		HackList hax = WurstClient.INSTANCE.getHax();
		if(hax == null)
			return;
		
		HandNoClipHack handNoClipHack = hax.handNoClipHack;
		if(!handNoClipHack.isEnabled() || !handNoClipHack.shouldClearBlock(pos))
			return;
		
		cir.setReturnValue(Shapes.empty());
	}
	
	@Inject(
		method = "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
		at = @At("HEAD"),
		cancellable = true)
	private void onGetCollisionShape(BlockGetter world, BlockPos pos,
		CollisionContext context, CallbackInfoReturnable<VoxelShape> cir)
	{
		HackList hax = WurstClient.INSTANCE.getHax();
		BlockState state = (BlockState)(Object)this;
		if(hax != null
			&& (hax.autoFlyHack.isEnabled() || hax.flightHack.isEnabled()
				|| hax.creativeFlightHack.isEnabled()))
		{
			boolean lava = (hax.autoFlyHack.shouldMakeLavaSolid()
				|| hax.flightHack.shouldMakeLavaSolid()
				|| hax.creativeFlightHack.shouldMakeLavaSolid())
				&& getFluidState().is(FluidTags.LAVA);
			boolean fire = hax.autoFlyHack.shouldMakeFireSolid()
				&& (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE));
			
			// Fire can damage an entity whose feet are one block above it.
			// Make that air block solid too, leaving a full block of clearance.
			boolean clearance = false;
			boolean solidLava = hax.autoFlyHack.shouldMakeLavaSolid()
				|| hax.flightHack.shouldMakeLavaSolid()
				|| hax.creativeFlightHack.shouldMakeLavaSolid();
			if(!lava && !fire
				&& (solidLava || hax.autoFlyHack.shouldMakeFireSolid()))
			{
				BlockState below = world.getBlockState(pos.below());
				clearance =
					(solidLava && below.getFluidState().is(FluidTags.LAVA))
						|| (hax.autoFlyHack.shouldMakeFireSolid()
							&& (below.is(Blocks.FIRE)
								|| below.is(Blocks.SOUL_FIRE)));
			}
			
			if(lava || fire || clearance)
			{
				cir.setReturnValue(Shapes.block());
				cir.cancel();
				return;
			}
		}
		
		if(hax != null && hax.antiVoidHack.shouldMakeFalseFloor(pos, state))
		{
			cir.setReturnValue(Shapes.block());
			cir.cancel();
			return;
		}
		
		if(hax != null
			&& hax.antiVoidHack.shouldMakeLavaFloor(world, pos, state))
		{
			cir.setReturnValue(Shapes.block());
			cir.cancel();
			return;
		}
		
		if(getFluidState().isEmpty())
			return;
		
		if(hax == null || !hax.jesusHack.shouldBeSolid())
			return;
		
		cir.setReturnValue(Shapes.block());
		cir.cancel();
	}
	
	@Shadow
	public abstract FluidState getFluidState();
}
