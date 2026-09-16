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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.wurstclient.WurstClient;
import net.wurstclient.chestsearch.ChestConfig;
import net.wurstclient.chestsearch.ChestRecorder;
import net.wurstclient.chestsearch.SlotHighlighter;
import net.wurstclient.hacks.AutoStealHack;
import net.wurstclient.hacks.ChestSearchHack;
import net.wurstclient.hacks.QuickShulkerHack;
import net.wurstclient.util.ChatUtils;

import java.util.ArrayList;
import java.util.List;

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
	private ChestRecorder chestSearchRecorder;
	@Unique
	private BlockPos chestSearchPosition;
	@Unique
	private String chestSearchServer;
	@Unique
	private String chestSearchDimension;
	@Unique
	private boolean chestSearchNotificationSent;
	@Unique
	private boolean chestSearchSnapshotFinalized;
	@Unique
	private static String lastShulkerRecordedKey;
	@Unique
	private static long lastShulkerRecordedTimestamp;
	
	private ShulkerBoxScreenMixin(WurstClient wurst, ShulkerBoxMenu handler,
		Inventory inventory, Component title)
	{
		super(handler, inventory, title);
	}
	
	@Override
	public void init()
	{
		super.init();
		chestSearchNotificationSent = false;
		chestSearchSnapshotFinalized = false;
		
		if(!WurstClient.INSTANCE.isEnabled()
			|| WurstClient.INSTANCE.shouldHideWurstUiMixins())
			return;
		
		try
		{
			if(WurstClient.MC.hitResult instanceof BlockHitResult blockHit
				&& WurstClient.MC.level != null)
			{
				String dimension =
					WurstClient.MC.level.dimension().identifier().toString();
				SlotHighlighter.INSTANCE.tryActivate(dimension,
					blockHit.getBlockPos());
			}
		}catch(Throwable ignored)
		{}
		
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
				.builder(Component.literal("Steal"), b -> autoSteal.steal(this))
				.bounds(stealX, autoButtonY, buttonWidth, autoButtonHeight)
				.build());
			addRenderableWidget(Button
				.builder(Component.literal("Store"), b -> autoSteal.store(this))
				.bounds(storeX, autoButtonY, buttonWidth, autoButtonHeight)
				.build());
			addRenderableWidget(Button
				.builder(Component.literal("Dump"), b -> autoSteal.dump(this))
				.bounds(dumpX, autoButtonY, buttonWidth, autoButtonHeight)
				.build());
		}
		
		if(quickShulker != null && quickShulker.isEnabled()
			&& quickShulker.hasUsableShulker())
		{
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
		
		wurst$startChestSearchRecording();
	}
	
	@Override
	public void removed()
	{
		wurst$finalizeChestSearchSnapshot();
		SlotHighlighter.INSTANCE.clearActive();
		super.removed();
	}
	
	@Unique
	private void wurst$startChestSearchRecording()
	{
		try
		{
			ChestSearchHack chestSearchHack =
				WurstClient.INSTANCE.getHax().chestSearchHack;
			if(chestSearchHack != null && !chestSearchHack.isAutomaticMode())
				return;
			
			ChestConfig config = new ChestConfig();
			if(!config.enabled)
				return;
			
			BlockPos position = wurst$resolveShulkerPosition();
			if(position == null)
				return;
			
			this.chestSearchRecorder =
				new ChestRecorder(new java.io.File(config.dbPath), config);
			this.chestSearchPosition = position;
			this.chestSearchServer = wurst$getServerIp();
			this.chestSearchDimension = wurst$getDimension();
			
			List<Integer> slotOrder = new ArrayList<>();
			int slotCount = Math.min(27, this.menu.slots.size());
			for(int i = 0; i < slotCount; i++)
				slotOrder.add(i);
			
			List<ItemStack> contents = wurst$snapshotShulker(slotOrder);
			chestSearchRecorder.recordFromStacksWithSlotOrder(chestSearchServer,
				chestSearchDimension, position.getX(), position.getY(),
				position.getZ(), contents, slotOrder, null);
			chestSearchRecorder.onChestOpened(chestSearchServer,
				chestSearchDimension, position.getX(), position.getY(),
				position.getZ(), this.menu, slotCount, slotOrder, null);
		}catch(Throwable ignored)
		{}
	}
	
	@Unique
	private void wurst$finalizeChestSearchSnapshot()
	{
		try
		{
			if(chestSearchSnapshotFinalized || chestSearchRecorder == null
				|| chestSearchPosition == null)
				return;
			chestSearchSnapshotFinalized = true;
			
			List<Integer> slotOrder = new ArrayList<>();
			int slotCount = Math.min(27, this.menu.slots.size());
			for(int i = 0; i < slotCount; i++)
				slotOrder.add(i);
			List<ItemStack> contents = wurst$snapshotShulker(slotOrder);
			boolean hasItems = contents.stream()
				.anyMatch(stack -> stack != null && !stack.isEmpty());
			if(!hasItems)
				return;
			
			int x = chestSearchPosition.getX();
			int y = chestSearchPosition.getY();
			int z = chestSearchPosition.getZ();
			chestSearchRecorder.recordFromStacksWithSlotOrder(chestSearchServer,
				chestSearchDimension, x, y, z, contents, slotOrder, null);
			
			ChestSearchHack chestSearchHack =
				WurstClient.INSTANCE.getHax().chestSearchHack;
			if(!chestSearchNotificationSent
				&& (chestSearchHack == null
					|| chestSearchHack.isRecordNotificationEnabled())
				&& wurst$shouldNotifyShulker(chestSearchServer,
					chestSearchDimension, x, y, z))
			{
				chestSearchNotificationSent = true;
				ChatUtils.message(
					"Chest recorded at position " + x + "," + y + "," + z);
			}
		}catch(Throwable ignored)
		{}
	}
	
	@Unique
	private List<ItemStack> wurst$snapshotShulker(List<Integer> slotOrder)
	{
		List<ItemStack> contents = new ArrayList<>(slotOrder.size());
		for(int index : slotOrder)
		{
			ItemStack stack = index >= 0 && index < this.menu.slots.size()
				? this.menu.slots.get(index).getItem() : ItemStack.EMPTY;
			contents.add(stack == null ? ItemStack.EMPTY : stack.copy());
		}
		return contents;
	}
	
	@Unique
	private BlockPos wurst$resolveShulkerPosition()
	{
		if(WurstClient.MC.hitResult instanceof BlockHitResult blockHit)
			return blockHit.getBlockPos();
		
		try
		{
			for(Class<?> type = this.menu.getClass(); type != null; type =
				type.getSuperclass())
				for(java.lang.reflect.Field field : type.getDeclaredFields())
				{
					field.setAccessible(true);
					Object value = field.get(this.menu);
					if(value instanceof BlockEntity blockEntity)
						return blockEntity.getBlockPos();
				}
		}catch(Throwable ignored)
		{}
		
		if(WurstClient.MC.level == null || WurstClient.MC.player == null)
			return null;
		BlockPos origin = WurstClient.MC.player.blockPosition();
		BlockPos closest = null;
		double closestDistance = Double.MAX_VALUE;
		for(int dx = -4; dx <= 4; dx++)
			for(int dy = -4; dy <= 4; dy++)
				for(int dz = -4; dz <= 4; dz++)
				{
					BlockPos candidate = origin.offset(dx, dy, dz);
					if(!(WurstClient.MC.level.getBlockState(candidate)
						.getBlock() instanceof ShulkerBoxBlock))
						continue;
					double distance = WurstClient.MC.player.distanceToSqr(
						candidate.getX() + 0.5, candidate.getY() + 0.5,
						candidate.getZ() + 0.5);
					if(distance < closestDistance)
					{
						closest = candidate;
						closestDistance = distance;
					}
				}
		return closest;
	}
	
	@Unique
	private String wurst$getServerIp()
	{
		try
		{
			return WurstClient.MC.getCurrentServer() == null ? null
				: WurstClient.MC.getCurrentServer().ip;
		}catch(Throwable ignored)
		{
			return null;
		}
	}
	
	@Unique
	private String wurst$getDimension()
	{
		try
		{
			return WurstClient.MC.level == null ? null
				: WurstClient.MC.level.dimension().identifier().toString();
		}catch(Throwable ignored)
		{
			return null;
		}
	}
	
	@Unique
	private boolean wurst$shouldNotifyShulker(String serverIp, String dimension,
		int x, int y, int z)
	{
		String key = String.valueOf(serverIp) + "|" + String.valueOf(dimension)
			+ "|" + x + "," + y + "," + z;
		long now = System.currentTimeMillis();
		if(key.equals(lastShulkerRecordedKey)
			&& now - lastShulkerRecordedTimestamp < 500)
			return false;
		lastShulkerRecordedKey = key;
		lastShulkerRecordedTimestamp = now;
		return true;
	}
}
