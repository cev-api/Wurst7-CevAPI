/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.BlockBreaker.BlockBreakingParams;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;

@SearchTags({"bed break aura", "bedbreakaura", "bed breaker", "bed aura"})
public final class BedBreakAuraHack extends Hack
	implements UpdateListener, RightClickListener, PacketOutputListener
{
	private final SliderSetting range = new SliderSetting("Range",
		"Maximum distance to search for beds, in blocks.", 6, 1, 6, 0.05,
		ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final CheckboxSetting autoSwitchTool =
		new CheckboxSetting("Auto switch tool",
			"Switches to the best hotbar tool for the bed before breaking it.",
			true);
	private final CheckboxSetting switchBack = new CheckboxSetting(
		"Switch back",
		"Returns to your previous hotbar slot after the bed is broken.", true);
	private final CheckboxSetting protectRespawnBed = new CheckboxSetting(
		"Protect own respawn bed",
		"Remembers the bed used as your respawn point per server and never breaks it unless it is no longer there.",
		true);
	
	private final ChunkAreaSetting searchArea = new ChunkAreaSetting(
		"Internal search area", "", ChunkAreaSetting.ChunkArea.A65);
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator((pos, state) -> state != null
			&& state.getBlock() instanceof BedBlock, searchArea);
	
	private BlockPos currentTarget;
	private BlockPos pausedTarget;
	private Set<BlockPos> occludingBlocks = Collections.emptySet();
	private double pausedDistanceSq = Double.NaN;
	private int restoreSlot = -1;
	private final RespawnBedStore respawnBeds = new RespawnBedStore();
	private final Map<BlockPos, Integer> pendingPlacedBeds = new HashMap<>();
	
	public BedBreakAuraHack()
	{
		super("BedBreakAura");
		setCategory(Category.BLOCKS);
		addSetting(range);
		addSetting(autoSwitchTool);
		addSetting(switchBack);
		addSetting(protectRespawnBed);
		// Respawn beds must be remembered even when BedBreakAura is disabled.
		// Otherwise enabling the aura after sleeping in a bed would miss the
		// right-click that established the respawn point.
		EVENTS.add(RightClickListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().autoMineHack.setEnabled(false);
		WURST.getHax().excavatorHack.setEnabled(false);
		WURST.getHax().nukerHack.setEnabled(false);
		WURST.getHax().nukerLegitHack.setEnabled(false);
		WURST.getHax().speedNukerHack.setEnabled(false);
		WURST.getHax().tunnellerHack.setEnabled(false);
		WURST.getHax().veinMinerHack.setEnabled(false);
		
		EVENTS.add(PacketInputListener.class, coordinator);
		coordinator.reset();
		currentTarget = null;
		pausedTarget = null;
		occludingBlocks = Collections.emptySet();
		pausedDistanceSq = Double.NaN;
		restoreSlot = -1;
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketInputListener.class, coordinator);
		stopBreaking();
		coordinator.reset();
		pausedTarget = null;
		occludingBlocks = Collections.emptySet();
		pausedDistanceSq = Double.NaN;
	}
	
	@Override
	public void onUpdate()
	{
		if(!isEnabled())
		{
			updateRespawnBedMemory();
			confirmPlacedBeds();
			return;
		}
		if(MC.player == null || MC.level == null || MC.gameMode == null)
		{
			setEnabled(false);
			return;
		}
		
		if(MC.player.isHandsBusy())
		{
			stopBreaking();
			return;
		}
		
		coordinator.update();
		updateRespawnBedMemory();
		confirmPlacedBeds();
		BlockBreakingParams target = findTarget();
		if(target == null)
		{
			stopBreaking();
			return;
		}
		
		if(pausedTarget != null && pausedTarget.equals(target.pos()))
		{
			if(target.distanceSq() >= pausedDistanceSq - 0.25)
			{
				stopBreaking();
				return;
			}
			
			pausedTarget = null;
			pausedDistanceSq = Double.NaN;
		}
		
		if(currentTarget == null || !currentTarget.equals(target.pos()))
		{
			currentTarget = target.pos();
			occludingBlocks = findOccludingBlocks(currentTarget);
		}
		
		ensureBestTool(target.pos());
		BlockBreaker
			.breakBlocksWithPacketSpam(Collections.singleton(target.pos()));
		MC.player.swing(InteractionHand.MAIN_HAND);
		
		pausedTarget = target.pos();
		pausedDistanceSq = target.distanceSq();
	}
	
	/**
	 * Used by the interaction and collision hooks without enabling other hacks.
	 */
	public double getInteractionRange()
	{
		return range.getValue();
	}
	
	public boolean shouldClearBlock(BlockPos pos)
	{
		return isEnabled() && occludingBlocks.contains(pos);
	}
	
	private Set<BlockPos> findOccludingBlocks(BlockPos target)
	{
		if(target == null || MC.player == null)
			return Collections.emptySet();
		
		Vec3 eyes = RotationUtils.getEyesPos();
		Vec3 end = Vec3.atCenterOf(target);
		Vec3 delta = end.subtract(eyes);
		double length = delta.length();
		if(length <= 0)
			return Collections.emptySet();
		
		Set<BlockPos> result = new HashSet<>();
		for(double distance = 0; distance < length; distance += 0.1)
		{
			BlockPos pos =
				BlockPos.containing(eyes.add(delta.scale(distance / length)));
			if(!pos.equals(target) && !MC.level.isEmptyBlock(pos))
				result.add(pos);
		}
		return result.isEmpty() ? Collections.emptySet()
			: Collections.unmodifiableSet(result);
	}
	
	private BlockBreakingParams findTarget()
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		double rangeSq = range.getValueSq();
		
		return coordinator.getReadyMatches()
			.map(result -> BlockBreaker.getBlockBreakingParams(eyes,
				result.pos()))
			.filter(Objects::nonNull)
			.filter(params -> !isProtectedRespawnBed(params.pos()))
			.filter(params -> params.distanceSq() <= rangeSq)
			.sorted(BlockBreaker.comparingParams()).findFirst().orElse(null);
	}
	
	private void updateRespawnBedMemory()
	{
		if(!protectRespawnBed.isChecked() || MC.player == null
			|| MC.level == null)
			return;
		String server = getServerKey();
		RespawnBedStore.Bed remembered = respawnBeds.get(server);
		if(remembered != null && remembered.dimension.equals(currentDimension())
			&& MC.level.hasChunkAt(
				new BlockPos(remembered.x, remembered.y, remembered.z))
			&& !(MC.level
				.getBlockState(
					new BlockPos(remembered.x, remembered.y, remembered.z))
				.getBlock() instanceof BedBlock))
			respawnBeds.remove(server);
		
	}
	
	@Override
	public void onRightClick(RightClickListener.RightClickEvent event)
	{
		if(!protectRespawnBed.isChecked() || MC.level == null
			|| !(MC.hitResult instanceof BlockHitResult hit))
			return;
		if(MC.level.dimension() == Level.NETHER)
			return;
		BlockPos pos = hit.getBlockPos();
		var state = MC.level.getBlockState(pos);
		if(!(state.getBlock() instanceof BedBlock))
			return;
		if(state.getValue(BedBlock.PART) == BedPart.HEAD)
			pos = pos.relative(state.getValue(BedBlock.FACING).getOpposite());
		respawnBeds.put(getServerKey(), currentDimension(), pos.getX(),
			pos.getY(), pos.getZ());
	}
	
	@Override
	public void onSentPacket(PacketOutputListener.PacketOutputEvent event)
	{
		if(!protectRespawnBed.isChecked() || MC.player == null
			|| MC.level == null || MC.level.dimension() == Level.NETHER
			|| !(event
				.getPacket() instanceof ServerboundUseItemOnPacket packet))
			return;
		ItemStack held = MC.player.getItemInHand(packet.getHand());
		if(!(held.getItem() instanceof BlockItem blockItem)
			|| !(blockItem.getBlock() instanceof BedBlock))
			return;
		BlockHitResult hit = packet.getHitResult();
		pendingPlacedBeds.put(hit.getBlockPos().relative(hit.getDirection()),
			20);
	}
	
	private void confirmPlacedBeds()
	{
		if(!protectRespawnBed.isChecked() || MC.level == null)
			return;
		var iterator = pendingPlacedBeds.entrySet().iterator();
		while(iterator.hasNext())
		{
			var pending = iterator.next();
			BlockPos pos = pending.getKey();
			if(MC.level.getBlockState(pos).getBlock() instanceof BedBlock)
			{
				var state = MC.level.getBlockState(pos);
				if(state.getValue(BedBlock.PART) == BedPart.HEAD)
					pos = pos.relative(
						state.getValue(BedBlock.FACING).getOpposite());
				respawnBeds.put(getServerKey(), currentDimension(), pos.getX(),
					pos.getY(), pos.getZ());
				iterator.remove();
				continue;
			}
			int ticksLeft = pending.getValue() - 1;
			if(ticksLeft <= 0)
				iterator.remove();
			else
				pending.setValue(ticksLeft);
		}
	}
	
	private boolean isProtectedRespawnBed(BlockPos pos)
	{
		if(!protectRespawnBed.isChecked())
			return false;
		RespawnBedStore.Bed bed = respawnBeds.get(getServerKey());
		if(bed == null || !bed.dimension.equals(currentDimension()))
			return false;
		BlockPos saved = new BlockPos(bed.x, bed.y, bed.z);
		if(pos.equals(saved))
			return true;
		if(!(MC.level.getBlockState(saved).getBlock() instanceof BedBlock))
			return false;
		var state = MC.level.getBlockState(saved);
		Direction facing = state.getValue(BedBlock.FACING);
		BlockPos other = state.getValue(BedBlock.PART) == BedPart.FOOT
			? saved.relative(facing) : saved.relative(facing.getOpposite());
		return pos.equals(other);
	}
	
	private String getServerKey()
	{
		if(MC.getCurrentServer() != null && MC.getCurrentServer().ip != null
			&& !MC.getCurrentServer().ip.isBlank())
			return MC.getCurrentServer().ip.trim()
				.toLowerCase(java.util.Locale.ROOT);
		return "singleplayer";
	}
	
	private String currentDimension()
	{
		return MC.level.dimension().identifier().toString();
	}
	
	private void ensureBestTool(BlockPos pos)
	{
		if(!autoSwitchTool.isChecked() || MC.player == null)
			return;
		
		Inventory inv = MC.player.getInventory();
		int before = inv.getSelectedSlot();
		WURST.getHax().autoToolHack.equipBestTool(pos, true, true, 0);
		int after = inv.getSelectedSlot();
		if(before != after && switchBack.isChecked() && restoreSlot == -1)
			restoreSlot = before;
	}
	
	private void stopBreaking()
	{
		MC.gameMode.stopDestroyBlock();
		currentTarget = null;
		occludingBlocks = Collections.emptySet();
		
		if(!switchBack.isChecked() || restoreSlot == -1 || MC.player == null)
			return;
		
		MC.player.getInventory().setSelectedSlot(restoreSlot);
		restoreSlot = -1;
	}
}
