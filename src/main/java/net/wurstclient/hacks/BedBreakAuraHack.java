/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Objects;
import java.util.Collections;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
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

@SearchTags({"bed break aura", "bedbreakaura", "bed breaker", "bed aura"})
public final class BedBreakAuraHack extends Hack implements UpdateListener
{
	private final SliderSetting range = new SliderSetting("Range",
		"Maximum distance to search for beds, in blocks.", 6, 1, 6, 0.05,
		ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final CheckboxSetting autoSwitchTool =
		new CheckboxSetting("Auto switch tool",
			"Switches to the best hotbar tool for the bed before breaking it.",
			true);
	private final CheckboxSetting keepHandNoClipEnabled = new CheckboxSetting(
		"Keep HandNoClip enabled",
		"When enabled, BedBreakAura will re-enable HandNoClip if you disable it.\n"
			+ "Disable this if you want HandNoClip fully manual.",
		true);
	private final CheckboxSetting switchBack = new CheckboxSetting(
		"Switch back",
		"Returns to your previous hotbar slot after the bed is broken.", true);
	
	private final ChunkAreaSetting searchArea = new ChunkAreaSetting(
		"Internal search area", "", ChunkAreaSetting.ChunkArea.A65);
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator((pos, state) -> state != null
			&& state.getBlock() instanceof BedBlock, searchArea);
	
	private BlockPos currentTarget;
	private BlockPos pausedTarget;
	private double pausedDistanceSq = Double.NaN;
	private int restoreSlot = -1;
	private boolean prevReachEnabled;
	private boolean prevHandNoClipEnabled;
	private int handNoClipWarningCooldown;
	
	public BedBreakAuraHack()
	{
		super("BedBreakAura");
		setCategory(Category.BLOCKS);
		addSetting(range);
		addSetting(autoSwitchTool);
		addSetting(keepHandNoClipEnabled);
		addSetting(switchBack);
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
		
		prevReachEnabled = WURST.getHax().reachHack.isEnabled();
		if(!prevReachEnabled)
			WURST.getHax().reachHack.setEnabled(true);
		WURST.getHax().reachHack.setRangeOverride(range.getValue());
		
		prevHandNoClipEnabled = WURST.getHax().handNoClipHack.isEnabled();
		if(keepHandNoClipEnabled.isChecked() && !prevHandNoClipEnabled)
			WURST.getHax().handNoClipHack.setEnabled(true);
		
		handNoClipWarningCooldown = 0;
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, coordinator);
		coordinator.reset();
		currentTarget = null;
		pausedTarget = null;
		pausedDistanceSq = Double.NaN;
		restoreSlot = -1;
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, coordinator);
		stopBreaking();
		WURST.getHax().reachHack.setRangeOverride(null);
		if(!prevReachEnabled)
			WURST.getHax().reachHack.setEnabled(false);
		if(keepHandNoClipEnabled.isChecked() && !prevHandNoClipEnabled)
			WURST.getHax().handNoClipHack.setEnabled(false);
		coordinator.reset();
		pausedTarget = null;
		pausedDistanceSq = Double.NaN;
	}
	
	@Override
	public void onUpdate()
	{
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
		
		if(!WURST.getHax().reachHack.isEnabled())
			WURST.getHax().reachHack.setEnabled(true);
		WURST.getHax().reachHack.setRangeOverride(range.getValue());
		if(keepHandNoClipEnabled.isChecked()
			&& !WURST.getHax().handNoClipHack.isEnabled())
		{
			WURST.getHax().handNoClipHack.setEnabled(true);
			if(handNoClipWarningCooldown <= 0)
			{
				net.wurstclient.util.ChatUtils.warning(
					"HandNoClip was re-enabled by BedBreakAura. Disable \"Keep HandNoClip enabled\" in BedBreakAura settings if you want to turn HandNoClip off.");
				handNoClipWarningCooldown = 40;
			}
		}
		
		if(handNoClipWarningCooldown > 0)
			handNoClipWarningCooldown--;
		
		coordinator.update();
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
			currentTarget = target.pos();
		
		ensureBestTool(target.pos());
		BlockBreaker
			.breakBlocksWithPacketSpam(Collections.singleton(target.pos()));
		MC.player.swing(InteractionHand.MAIN_HAND);
		
		pausedTarget = target.pos();
		pausedDistanceSq = target.distanceSq();
	}
	
	private BlockBreakingParams findTarget()
	{
		Vec3 eyes = RotationUtils.getEyesPos();
		double rangeSq = range.getValueSq();
		
		return coordinator.getReadyMatches()
			.map(result -> BlockBreaker.getBlockBreakingParams(eyes,
				result.pos()))
			.filter(Objects::nonNull)
			.filter(params -> params.distanceSq() <= rangeSq)
			.sorted(BlockBreaker.comparingParams()).findFirst().orElse(null);
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
		
		if(!switchBack.isChecked() || restoreSlot == -1 || MC.player == null)
			return;
		
		MC.player.getInventory().setSelectedSlot(restoreSlot);
		restoreSlot = -1;
	}
}
