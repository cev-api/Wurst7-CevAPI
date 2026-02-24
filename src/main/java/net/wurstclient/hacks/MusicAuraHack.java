/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.NoteBlock;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.BlockBreaker.BlockBreakingParams;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.InteractionSimulator;
import net.wurstclient.util.RotationUtils;

@SearchTags({"music aura", "noteblock", "note block", "random notes"})
public final class MusicAuraHack extends Hack implements UpdateListener
{
	private static final int RANGE = 6;
	private static final double RANGE_SQ = RANGE * RANGE;
	
	private final SliderSetting delay = new SliderSetting("Delay",
		"Ticks between note-block interactions.", 4, 1, 20, 1,
		ValueDisplay.INTEGER.withSuffix(" ticks").withLabel(1, "1 tick"));
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.CLIENT);
	
	private int cooldown;
	
	public MusicAuraHack()
	{
		super("MusicAura");
		setCategory(Category.BLOCKS);
		addSetting(delay);
		addSetting(swingHand);
	}
	
	@Override
	protected void onEnable()
	{
		cooldown = 0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null || MC.gameMode == null)
			return;
		
		if(cooldown > 0)
		{
			cooldown--;
			return;
		}
		
		if(MC.gameMode.isDestroying() || MC.player.isHandsBusy())
			return;
		
		if(MC.rightClickDelay > 0)
			return;
		
		List<BlockBreakingParams> noteBlocks = getNearbyNoteBlocks();
		if(noteBlocks.isEmpty())
			return;
		
		BlockBreakingParams target = noteBlocks
			.get(ThreadLocalRandom.current().nextInt(noteBlocks.size()));
		
		WURST.getRotationFaker().faceVectorPacket(target.hitVec());
		MC.rightClickDelay = 4;
		InteractionSimulator.rightClickBlock(target.toHitResult(),
			InteractionHand.MAIN_HAND, swingHand.getSelected());
		
		cooldown = delay.getValueI();
	}
	
	private List<BlockBreakingParams> getNearbyNoteBlocks()
	{
		BlockPos eyesBlock = BlockPos.containing(RotationUtils.getEyesPos());
		
		return BlockUtils.getAllInBoxStream(eyesBlock, RANGE)
			.filter(pos -> BlockUtils.getBlock(pos) instanceof NoteBlock)
			.map(BlockBreaker::getBlockBreakingParams).filter(Objects::nonNull)
			.filter(params -> params.distanceSq() <= RANGE_SQ).toList();
	}
}
