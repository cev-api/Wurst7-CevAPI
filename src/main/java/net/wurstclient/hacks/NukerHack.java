/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Objects;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.LeftClickListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.nukers.CommonNukerSettings;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.BlockBreaker.BlockBreakingParams;
import net.wurstclient.util.BlockBreakingCache;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.OverlayRenderer;
import net.wurstclient.util.RotationUtils;

public final class NukerHack extends Hack
	implements UpdateListener, RenderListener
{
	private final SliderSetting range =
		new SliderSetting("Range", 5, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final CommonNukerSettings commonSettings =
		new CommonNukerSettings();
	
	private final SwingHandSetting swingHand = new SwingHandSetting(
		SwingHandSetting.genericMiningDescription(this), SwingHand.SERVER);
	
	private final CheckboxSetting autoSwitchTool = new CheckboxSetting(
		"Auto switch tool",
		"Automatically switch to the best tool in your hotbar for the current"
			+ " block even if the AutoTool hack is disabled.",
		false);
	
	private final BlockBreakingCache cache = new BlockBreakingCache();
	private final OverlayRenderer overlay = new OverlayRenderer();
	private BlockPos currentBlock;
	
	// Remember whether AutoTool was enabled before this hack enabled it
	private boolean prevAutoToolEnabled;
	
	public NukerHack()
	{
		super("Nuker");
		setCategory(Category.BLOCKS);
		addSetting(range);
		commonSettings.getSettings().forEach(this::addSetting);
		addSetting(swingHand);
		addSetting(autoSwitchTool);
	}
	
	@Override
	public String getRenderName()
	{
		return getName() + commonSettings.getRenderNameSuffix();
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().autoMineHack.setEnabled(false);
		WURST.getHax().excavatorHack.setEnabled(false);
		WURST.getHax().nukerLegitHack.setEnabled(false);
		WURST.getHax().speedNukerHack.setEnabled(false);
		WURST.getHax().tunnellerHack.setEnabled(false);
		WURST.getHax().veinMinerHack.setEnabled(false);
		
		// Auto-enable AutoTool if requested by per-hack setting
		prevAutoToolEnabled = WURST.getHax().autoToolHack.isEnabled();
		if(autoSwitchTool.isChecked() && !prevAutoToolEnabled)
		{
			WURST.getHax().autoToolHack.setEnabled(true);
		}
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(LeftClickListener.class, commonSettings);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(LeftClickListener.class, commonSettings);
		EVENTS.remove(RenderListener.class, this);
		
		if(currentBlock != null)
		{
			MC.gameMode.isDestroying = true;
			MC.gameMode.stopDestroyBlock();
			currentBlock = null;
		}
		
		cache.reset();
		overlay.resetProgress();
		commonSettings.reset();
		
		// Restore AutoTool previous state if we enabled it
		if(!prevAutoToolEnabled && WURST.getHax().autoToolHack.isEnabled())
		{
			WURST.getHax().autoToolHack.setEnabled(false);
		}
	}
	
	@Override
	public void onUpdate()
	{
		currentBlock = null;
		
		if(MC.options.keyAttack.isDown() || commonSettings.isIdModeWithAir())
			return;
		
		Vec3 eyesVec = RotationUtils.getEyesPos();
		BlockPos eyesBlock = BlockPos.containing(eyesVec);
		double rangeSq = range.getValueSq();
		int blockRange = range.getValueCeil();
		
		Stream<BlockBreakingParams> stream = BlockUtils
			.getAllInBoxStream(eyesBlock, blockRange)
			.filter(commonSettings::shouldBreakBlock)
			.map(BlockBreaker::getBlockBreakingParams).filter(Objects::nonNull);
		
		if(commonSettings.isSphereShape())
			stream = stream.filter(params -> params.distanceSq() <= rangeSq);
		
		stream = stream.sorted(BlockBreaker.comparingParams());
		
		// Break all blocks in creative mode
		if(MC.player.getAbilities().instabuild)
		{
			MC.gameMode.stopDestroyBlock();
			overlay.resetProgress();
			
			ArrayList<BlockPos> blocks = cache
				.filterOutRecentBlocks(stream.map(BlockBreakingParams::pos));
			if(blocks.isEmpty())
				return;
			
			currentBlock = blocks.get(0);
			BlockBreaker.breakBlocksWithPacketSpam(blocks);
			swingHand.swing(InteractionHand.MAIN_HAND);
			return;
		}
		
		// Break the first valid block in survival mode
		currentBlock = stream.filter(this::breakOneBlock)
			.map(BlockBreakingParams::pos).findFirst().orElse(null);
		
		if(currentBlock == null)
		{
			MC.gameMode.stopDestroyBlock();
			overlay.resetProgress();
			return;
		}
		
		overlay.updateProgress();
	}
	
	private boolean breakOneBlock(BlockBreakingParams params)
	{
		WURST.getRotationFaker().faceVectorPacket(params.hitVec());
		
		// Auto-switch tool behavior: prefer using the global AutoTool hack when
		// enabled,
		// otherwise use this per-hack setting to call equipBestTool directly.
		if(WURST.getHax().autoToolHack.isEnabled())
		{
			WURST.getHax().autoToolHack.equipIfEnabled(params.pos());
		}else if(autoSwitchTool.isChecked())
		{
			// use default options: allow swords, allow fallback to hands, no
			// repair mode
			WURST.getHax().autoToolHack.equipBestTool(params.pos(), true, true,
				0);
		}
		
		if(!MC.gameMode.continueDestroyBlock(params.pos(), params.side()))
			return false;
		
		swingHand.swing(InteractionHand.MAIN_HAND);
		return true;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		overlay.render(matrixStack, partialTicks, currentBlock);
	}
}
