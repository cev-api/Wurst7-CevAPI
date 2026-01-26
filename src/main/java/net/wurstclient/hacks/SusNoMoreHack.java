/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.Iterator;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.events.RightClickListener.RightClickEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ItemListSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"susnomore", "suspicious sand", "brush", "archaeology"})
public final class SusNoMoreHack extends Hack
	implements RightClickListener, PacketInputListener, UpdateListener
{
	private static final long TARGET_TTL_MS = 2000L;
	private static final int MAX_BREAK_TICKS = 20 * 5;
	
	private final ItemListSetting autoBreakList = new ItemListSetting(
		"Auto-break list",
		"When SusNoMore reveals one of these items, the suspicious sand is broken automatically.");
	
	private final ArrayDeque<DustTarget> dustTargets = new ArrayDeque<>();
	
	private DustTarget autoBreakTarget;
	private int previousHotbarSlot = -1;
	private int autoBreakTicks;
	
	public SusNoMoreHack()
	{
		super("SusNoMore");
		setCategory(Category.ITEMS);
		addSetting(autoBreakList);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(RightClickListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(RightClickListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		dustTargets.clear();
		finishAutoBreak();
	}
	
	@Override
	public void onRightClick(RightClickEvent event)
	{
		if(MC.player == null || MC.level == null)
			return;
		
		HitResult hitResult = MC.hitResult;
		if(!(hitResult instanceof BlockHitResult blockHit))
			return;
		
		ItemStack mainHand = MC.player.getMainHandItem();
		if(mainHand.isEmpty() || mainHand.getItem() != Items.BRUSH)
			return;
		
		BlockState state = MC.level.getBlockState(blockHit.getBlockPos());
		if(!isSuspicious(state))
			return;
		
		addDustTarget(blockHit.getBlockPos(), blockHit.getDirection());
	}
	
	private static boolean isSuspicious(BlockState state)
	{
		return state.is(Blocks.SUSPICIOUS_SAND)
			|| state.is(Blocks.SUSPICIOUS_GRAVEL);
	}
	
	private void addDustTarget(BlockPos pos, Direction side)
	{
		long now = System.currentTimeMillis();
		dustTargets.addLast(new DustTarget(pos, side, now));
		while(dustTargets.size() > 8)
			dustTargets.removeFirst();
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		Packet<?> packet = event.getPacket();
		if(packet instanceof ClientboundBlockEntityDataPacket bePacket)
			handleBlockEntityPacket(bePacket);
	}
	
	private void handleBlockEntityPacket(
		ClientboundBlockEntityDataPacket packet)
	{
		if(packet.getType() != BlockEntityType.BRUSHABLE_BLOCK)
			return;
		
		CompoundTag tag = packet.getTag();
		if(tag == null)
			return;
		
		ItemStack stack = ItemStack.EMPTY;
		CompoundTag itemTag = tag.getCompound("item").orElse(null);
		if(itemTag != null)
		{
			stack = ItemStack.CODEC.decode(NbtOps.INSTANCE, itemTag).result()
				.map(Pair::getFirst).orElse(ItemStack.EMPTY);
		}
		
		DustTarget target = findDustTarget(packet.getPos());
		if(target == null)
			return;
		
		if(stack.isEmpty())
		{
			String fallbackId = extractItemIdentifier(itemTag);
			if(fallbackId == null || fallbackId.isEmpty())
				reportEmptyDust(target);
			else
				reportUnknownDust(fallbackId, extractItemCount(itemTag));
		}else
		{
			reportDustResult(stack, target);
		}
	}
	
	private DustTarget findDustTarget(BlockPos pos)
	{
		long now = System.currentTimeMillis();
		Iterator<DustTarget> iterator = dustTargets.iterator();
		while(iterator.hasNext())
		{
			DustTarget candidate = iterator.next();
			if(now - candidate.timestamp() > TARGET_TTL_MS)
			{
				iterator.remove();
				continue;
			}
			
			if(candidate.pos().equals(pos))
			{
				iterator.remove();
				return candidate;
			}
		}
		
		return null;
	}
	
	private void reportDustResult(ItemStack stack, DustTarget target)
	{
		var identifier = BuiltInRegistries.ITEM.getKey(stack.getItem());
		if(identifier == null)
			return;
		
		boolean shouldBreak =
			autoBreakList.getItemNames().contains(identifier.toString());
		MutableComponent message = Component.literal("SusNoMore: Dusted ")
			.append(stack.getHoverName().copy());
		
		if(stack.getCount() > 1)
			message =
				message.append(Component.literal(" x" + stack.getCount()));
		
		if(shouldBreak)
			message = message.append(Component.literal(" -> auto breaking"));
		
		ChatUtils.component(message);
		
		if(shouldBreak)
			startAutoBreak(target);
	}
	
	private void reportUnknownDust(String identifier, int count)
	{
		String message = "SusNoMore: Dusted unknown item " + identifier;
		if(count > 1)
			message += " x" + count;
		ChatUtils.warning(message);
	}
	
	private void reportEmptyDust(DustTarget target)
	{
		ChatUtils.warning("SusNoMore: Dusted nothing (no item returned).");
	}
	
	@Override
	public void onUpdate()
	{
		dustTargets.removeIf(target -> System.currentTimeMillis()
			- target.timestamp() > TARGET_TTL_MS);
		
		if(autoBreakTarget != null)
			performAutoBreak();
	}
	
	private void startAutoBreak(DustTarget target)
	{
		if(autoBreakTarget != null || MC.player == null)
			return;
		
		var inventory = MC.player.getInventory();
		int slot = findShovelSlot(inventory);
		if(slot < 0)
		{
			ChatUtils.warning(
				"SusNoMore: No shovel in the hotbar to break suspicious sand.");
			return;
		}
		
		previousHotbarSlot = inventory.getSelectedSlot();
		if(slot != previousHotbarSlot)
			inventory.setSelectedSlot(slot);
		
		autoBreakTarget = target;
		autoBreakTicks = 0;
	}
	
	private int findShovelSlot(
		net.minecraft.world.entity.player.Inventory inventory)
	{
		for(int i = 0; i < 9; i++)
		{
			ItemStack stack = inventory.getItem(i);
			if(!stack.isEmpty() && stack.getItem() instanceof ShovelItem)
				return i;
		}
		
		return -1;
	}
	
	private void performAutoBreak()
	{
		if(autoBreakTarget == null || MC.player == null || MC.level == null)
			return;
		
		BlockPos pos = autoBreakTarget.pos();
		Direction side = autoBreakTarget.side();
		BlockState state = MC.level.getBlockState(pos);
		
		if(state.isAir())
		{
			finishAutoBreak();
			return;
		}
		
		if(!MC.gameMode.startDestroyBlock(pos, side))
		{
			finishAutoBreak();
			return;
		}
		
		MC.gameMode.continueDestroyBlock(pos, side);
		MC.player.swing(InteractionHand.MAIN_HAND);
		autoBreakTicks++;
		
		if(autoBreakTicks > MAX_BREAK_TICKS)
			finishAutoBreak();
	}
	
	private void finishAutoBreak()
	{
		if(autoBreakTarget == null)
			return;
		
		if(MC.player != null && previousHotbarSlot >= 0)
		{
			var inventory = MC.player.getInventory();
			if(inventory.getSelectedSlot() != previousHotbarSlot)
				inventory.setSelectedSlot(previousHotbarSlot);
		}
		
		autoBreakTarget = null;
		previousHotbarSlot = -1;
		autoBreakTicks = 0;
	}
	
	private static String extractItemIdentifier(CompoundTag itemTag)
	{
		if(itemTag == null)
			return null;
		return itemTag.getString("id").orElse(null);
	}
	
	private static int extractItemCount(CompoundTag itemTag)
	{
		if(itemTag == null)
			return 0;
		return itemTag.getByte("Count").map(Byte::toUnsignedInt).orElse(1);
	}
	
	private record DustTarget(BlockPos pos, Direction side, long timestamp)
	{}
}
