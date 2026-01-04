/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.wurstclient.WurstClient;
import net.wurstclient.chestsearch.ChestConfig;
import net.wurstclient.chestsearch.ChestRecorder;
import net.wurstclient.clickgui.screens.ChestSearchScreen;
import net.wurstclient.hacks.AutoStealHack;
import net.wurstclient.hacks.ChestSearchHack;
import net.wurstclient.hacks.QuickShulkerHack;

@Mixin(ShulkerBoxScreen.class)
public abstract class ShulkerBoxScreenMixin
	extends AbstractContainerScreen<ShulkerBoxMenu>
{
	@Unique
	private final AutoStealHack autoSteal =
		WurstClient.INSTANCE.getHax().autoStealHack;
	@Unique
	private final QuickShulkerHack quickShulker =
		WurstClient.INSTANCE.getHax().quickShulkerHack;
	@Unique
	private ChestRecorder chestRecorder;
	@Unique
	private BlockPos shulkerPos;
	@Unique
	private BlockPos shulkerClickedPos;
	@Unique
	private String shulkerServerIp;
	@Unique
	private String shulkerDimension;
	@Unique
	private boolean shulkerSnapshotFinalized = false;
	
	private ShulkerBoxScreenMixin(WurstClient wurst, ShulkerBoxMenu handler,
		Inventory inventory, Component title)
	{
		super(handler, inventory, title);
	}
	
	@Override
	public void init()
	{
		super.init();
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		boolean autoButtonsPlaced = false;
		final int autoButtonHeight = 12;
		final int autoButtonY = topPos - autoButtonHeight - 4;
		if(autoSteal.areButtonsVisible())
		{
			autoButtonsPlaced = true;
			final int buttonWidth = 44;
			final int buttonSpacing = 3;
			final int rightMargin = 6;
			int dumpX = leftPos + imageWidth - rightMargin - buttonWidth;
			int storeX = dumpX - buttonSpacing - buttonWidth;
			int stealX = storeX - buttonSpacing - buttonWidth;
			
			addRenderableWidget(Button
				.builder(Component.literal("Steal"),
					b -> autoSteal.steal(this, 3))
				.bounds(stealX, autoButtonY, buttonWidth, autoButtonHeight)
				.build());
			
			addRenderableWidget(Button
				.builder(Component.literal("Store"),
					b -> autoSteal.store(this, 3))
				.bounds(storeX, autoButtonY, buttonWidth, autoButtonHeight)
				.build());
			
			addRenderableWidget(Button
				.builder(Component.literal("Dump"),
					b -> autoSteal.dump(this, 3))
				.bounds(dumpX, autoButtonY, buttonWidth, autoButtonHeight)
				.build());
		}
		
		if(autoSteal.isEnabled())
			autoSteal.steal(this, 3);
		
		if(quickShulker != null && quickShulker.isEnabled()
			&& quickShulker.hasUsableShulker())
		{
			// place the QuickShulker button outside the shulker UI so it
			// doesn't overlap the container background, matching the
			// inventory screen placement
			int quickButtonY =
				autoButtonsPlaced ? autoButtonY - 20 : topPos - 20;
			Button quickButton = Button
				.builder(Component.literal("QuickShulker"),
					b -> quickShulker.triggerFromGui())
				.bounds(leftPos + imageWidth - 90, quickButtonY, 80, 16)
				.build();
			quickButton.active = !quickShulker.isBusy();
			addRenderableWidget(quickButton);
		}
		
		try
		{
			ChestSearchHack chestSearchHack =
				WurstClient.INSTANCE.getHax().chestSearchHack;
			if(chestSearchHack != null && chestSearchHack.isOffMode())
				return;
			
			ChestConfig cfg = new ChestConfig();
			if(!cfg.enabled)
				return;
			
			this.chestRecorder =
				new ChestRecorder(new java.io.File(cfg.dbPath), cfg);
			
			String serverIp = null;
			try
			{
				if(WurstClient.MC != null
					&& WurstClient.MC.getCurrentServer() != null)
					serverIp = WurstClient.MC.getCurrentServer().ip;
			}catch(Throwable ignored)
			{}
			String dimension = null;
			try
			{
				if(WurstClient.MC != null && WurstClient.MC.level != null)
					dimension = WurstClient.MC.level.dimension().identifier()
						.toString();
			}catch(Throwable ignored)
			{}
			shulkerServerIp = serverIp;
			shulkerDimension = dimension;
			
			shulkerPos = wurst$resolveShulkerPos();
			if(shulkerPos == null)
				return;
			
			try
			{
				if(shulkerClickedPos != null)
				{
					boolean cleared = ChestSearchScreen
						.clearDecorations(dimension, shulkerClickedPos);
					if(!cleared)
						ChestSearchScreen.clearDecorations(dimension,
							shulkerPos);
				}else
				{
					ChestSearchScreen.clearDecorations(dimension, shulkerPos);
				}
			}catch(Throwable ignored)
			{}
			
			ChestRecorder.Bounds bounds =
				ChestRecorder.Bounds.of(shulkerPos, shulkerPos, null);
			chestRecorder.onChestOpened(serverIp, dimension, shulkerPos.getX(),
				shulkerPos.getY(), shulkerPos.getZ(), this.menu, 27,
				java.util.Collections.emptyList(), bounds);
		}catch(Throwable ignored)
		{}
	}
	
	@Override
	public void removed()
	{
		wurst$finalizeShulkerSnapshot();
		super.removed();
	}
	
	@Unique
	private void wurst$finalizeShulkerSnapshot()
	{
		if(shulkerSnapshotFinalized)
			return;
		shulkerSnapshotFinalized = true;
		if(chestRecorder == null || shulkerPos == null || this.menu == null)
			return;
		
		try
		{
			int total = this.menu.slots.size();
			int window = Math.min(27, total);
			java.util.List<Integer> slotOrder =
				new java.util.ArrayList<>(window);
			java.util.List<net.minecraft.world.item.ItemStack> region =
				new java.util.ArrayList<>(window);
			boolean any = false;
			for(int i = 0; i < window; i++)
			{
				slotOrder.add(i);
				var st = this.menu.slots.get(i).getItem();
				region.add(st);
				if(st != null && !st.isEmpty())
					any = true;
			}
			if(any && !region.isEmpty())
			{
				ChestRecorder.Bounds bounds =
					ChestRecorder.Bounds.of(shulkerPos, shulkerPos, null);
				chestRecorder.recordFromStacksWithSlotOrder(shulkerServerIp,
					shulkerDimension, shulkerPos.getX(), shulkerPos.getY(),
					shulkerPos.getZ(), region, slotOrder, bounds);
			}
		}catch(Throwable ignored)
		{}
	}
	
	@Unique
	private BlockPos wurst$resolveShulkerPos()
	{
		BlockPos resolvedPos = null;
		try
		{
			java.lang.reflect.Method gb =
				this.menu.getClass().getMethod("getBlockEntity");
			Object be = gb.invoke(this.menu);
			if(be instanceof BlockEntity)
				resolvedPos = ((BlockEntity)be).getBlockPos();
		}catch(Throwable ignored)
		{}
		
		if(resolvedPos == null)
		{
			try
			{
				Object ctxObj = null;
				try
				{
					java.lang.reflect.Method m =
						this.menu.getClass().getMethod("getContext");
					ctxObj = m.invoke(this.menu);
				}catch(Throwable ignored)
				{}
				if(ctxObj instanceof net.minecraft.world.inventory.ContainerLevelAccess ctx)
				{
					BlockPos[] holder = new BlockPos[1];
					ctx.execute((world, pos) -> holder[0] = pos);
					resolvedPos = holder[0];
				}
			}catch(Throwable ignored)
			{}
		}
		
		if(resolvedPos == null)
		{
			try
			{
				HitResult hr = WurstClient.MC.hitResult;
				if(hr instanceof BlockHitResult bhr)
				{
					shulkerClickedPos = bhr.getBlockPos();
					resolvedPos = shulkerClickedPos;
				}
			}catch(Throwable ignored)
			{}
		}
		
		if(resolvedPos == null)
			return null;
		
		BlockPos normalized = wurst$normalizeShulkerPos(resolvedPos);
		if(shulkerClickedPos == null && resolvedPos != null)
			shulkerClickedPos = resolvedPos;
		return normalized;
	}
	
	@Unique
	private BlockPos wurst$normalizeShulkerPos(BlockPos pos)
	{
		if(pos == null || WurstClient.MC == null
			|| WurstClient.MC.level == null)
			return pos;
		
		BlockPos current = pos;
		BlockState state = WurstClient.MC.level.getBlockState(current);
		if(!(state.getBlock() instanceof ShulkerBoxBlock))
		{
			BlockPos below = current.below();
			BlockState belowState = WurstClient.MC.level.getBlockState(below);
			if(belowState.getBlock() instanceof ShulkerBoxBlock)
				current = below;
		}
		
		return current;
	}
}
