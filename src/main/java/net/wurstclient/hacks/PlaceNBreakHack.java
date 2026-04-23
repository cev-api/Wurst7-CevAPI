/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.BlockBreaker.BlockBreakingParams;
import net.wurstclient.util.BlockPlacer;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;

@SearchTags({"place n break", "place and break", "placenbreak"})
public final class PlaceNBreakHack extends Hack implements UpdateListener
{
	private final TextFieldSetting blockKeyword = new TextFieldSetting("Block",
		"Block keyword (examples: hopper, anvil, shulker).\n"
			+ "Exact IDs like minecraft:hopper also work.",
		"hopper", v -> v != null && v.length() <= 64);
	private final SliderSetting actionDelay = new SliderSetting("Action delay",
		"Ticks to wait between place/break actions.\n"
			+ "Increase this if the server desyncs or stutters.",
		1, 0, 10, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	private final SliderSetting breakAttemptsPerTick = new SliderSetting(
		"Break attempts/tick",
		"How many break progress attempts to send each tick.\n"
			+ "Higher can be faster, but may be less stable on some servers.",
		2, 1, 8, 1, ValueDisplay.INTEGER);
	
	private boolean enabledFastBreak;
	private boolean enabledFastPlace;
	private int nextActionTick;
	private Block selectedBlock = Blocks.HOPPER;
	private String lastBlockKeyword = "";
	private int unresolvedWarnCooldown;
	private BlockPos pendingCyclePos;
	
	public PlaceNBreakHack()
	{
		super("PlaceNBreak");
		// No category -> Navigator-only
		addSetting(blockKeyword);
		addSetting(actionDelay);
		addSetting(breakAttemptsPerTick);
	}
	
	@Override
	protected void onEnable()
	{
		enabledFastBreak = false;
		enabledFastPlace = false;
		nextActionTick = 0;
		unresolvedWarnCooldown = 0;
		lastBlockKeyword = "";
		pendingCyclePos = null;
		resolveSelectedBlock(true);
		
		if(!WURST.getHax().fastBreakHack.isEnabled())
		{
			WURST.getHax().fastBreakHack.setEnabled(true);
			enabledFastBreak = true;
		}
		
		if(!WURST.getHax().fastPlaceHack.isEnabled())
		{
			WURST.getHax().fastPlaceHack.setEnabled(true);
			enabledFastPlace = true;
		}
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		MC.gameMode.stopDestroyBlock();
		nextActionTick = 0;
		pendingCyclePos = null;
		
		if(enabledFastBreak && WURST.getHax().fastBreakHack.isEnabled())
			WURST.getHax().fastBreakHack.setEnabled(false);
		
		if(enabledFastPlace && WURST.getHax().fastPlaceHack.isEnabled())
			WURST.getHax().fastPlaceHack.setEnabled(false);
		
		enabledFastBreak = false;
		enabledFastPlace = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null || MC.gameMode == null)
			return;
		if(MC.player.isHandsBusy())
			return;
		if(unresolvedWarnCooldown > 0)
			unresolvedWarnCooldown--;
		
		resolveSelectedBlock(false);
		
		BlockHitResult hit = getCrosshairBlockHit();
		if(hit == null)
			return;
		
		Block targetBlock = selectedBlock;
		Item targetPlaceItem = targetBlock.asItem();
		if(!(targetPlaceItem instanceof BlockItem))
			return;
		
		if(pendingCyclePos != null)
		{
			BlockState pendingState = MC.level.getBlockState(pendingCyclePos);
			
			if(pendingState.getBlock() == targetBlock)
			{
				if(!canActThisTick())
					return;
				
				WURST.getHax().autoToolHack.equipBestTool(pendingCyclePos, true,
					true, 0);
				breakAt(pendingCyclePos, breakAttemptsPerTick.getValueI());
				consumeActionTick();
				return;
			}
			
			// Wait until the cycle position is empty again before placing
			// elsewhere.
			if(!pendingState.canBeReplaced())
				return;
			
			pendingCyclePos = null;
		}
		
		int slot = findHotbarSlot(targetPlaceItem);
		if(slot == -1)
			return;
		
		MC.player.getInventory().setSelectedSlot(slot);
		
		BlockPos cyclePos = getCyclePos(hit, targetBlock);
		if(cyclePos == null)
			return;
		
		BlockState state = MC.level.getBlockState(cyclePos);
		if(state.canBeReplaced())
		{
			if(!canActThisTick())
				return;
			
			if(placeAt(cyclePos))
			{
				pendingCyclePos = cyclePos.immutable();
				consumeActionTick();
				return;
			}
		}
		
		// Only break our configured block, never arbitrary pointed blocks.
		if(state.getBlock() == targetBlock)
		{
			if(!canActThisTick())
				return;
			
			// Always pick a proper tool to avoid "half-break forever" desync.
			WURST.getHax().autoToolHack.equipBestTool(cyclePos, true, true, 0);
			breakAt(cyclePos, breakAttemptsPerTick.getValueI());
			pendingCyclePos = cyclePos.immutable();
			consumeActionTick();
		}
	}
	
	private BlockHitResult getCrosshairBlockHit()
	{
		HitResult hit = MC.hitResult;
		if(hit == null || hit.getType() != HitResult.Type.BLOCK)
			hit = MC.player.pick(5.0, 0, false);
		
		if(hit instanceof BlockHitResult blockHit)
			return blockHit;
		
		return null;
	}
	
	private BlockPos getCyclePos(BlockHitResult hit, Block targetBlock)
	{
		BlockPos lookedPos = hit.getBlockPos();
		BlockState lookedState = MC.level.getBlockState(lookedPos);
		
		// If the player is looking at the configured block, break/place there.
		if(lookedState.getBlock() == targetBlock)
			return lookedPos;
		
		// Never use interactive blocks as placement anchors.
		if(BlockUtils.isInteractive(lookedState))
			return null;
		
		if(lookedState.canBeReplaced())
			return lookedPos;
		
		return lookedPos.relative(hit.getDirection());
	}
	
	private int findHotbarSlot(Item item)
	{
		for(int slot = 0; slot < 9; slot++)
		{
			ItemStack stack = MC.player.getInventory().getItem(slot);
			if(!stack.isEmpty() && stack.getItem() == item)
				return slot;
		}
		
		return -1;
	}
	
	private boolean placeAt(BlockPos pos)
	{
		BlockPlacer.BlockPlacingParams params =
			BlockPlacer.getBlockPlacingParams(pos);
		if(params != null && !params.requiresSneaking() && !BlockUtils
			.isInteractive(MC.level.getBlockState(params.neighbor())))
		{
			WURST.getRotationFaker().faceVectorPacket(params.hitVec());
			MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND,
				params.toHitResult());
			SwingHand.SERVER.swing(InteractionHand.MAIN_HAND);
			return true;
		}
		
		// Fallback: place only against explicitly non-interactive neighbors,
		// so blocks like anvils/shulkers can still be placed reliably.
		for(Direction side : Direction.values())
		{
			BlockPos neighbor = pos.relative(side);
			BlockState neighborState = MC.level.getBlockState(neighbor);
			if(neighborState.canBeReplaced())
				continue;
			if(BlockUtils.isInteractive(neighborState))
				continue;
			
			Direction placeSide = side.getOpposite();
			Vec3 hitVec = Vec3.atCenterOf(neighbor)
				.add(Vec3.atLowerCornerOf(placeSide.getUnitVec3i()).scale(0.5));
			
			WURST.getRotationFaker().faceVectorPacket(hitVec);
			MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND,
				new BlockHitResult(hitVec, placeSide, neighbor, false));
			SwingHand.SERVER.swing(InteractionHand.MAIN_HAND);
			return true;
		}
		
		return false;
	}
	
	private void breakAt(BlockPos pos, int attempts)
	{
		BlockBreakingParams params = BlockBreaker.getBlockBreakingParams(pos);
		if(params == null)
			return;
		
		WURST.getRotationFaker().faceVectorPacket(params.hitVec());
		if(!MC.gameMode.isDestroying())
			MC.gameMode.startDestroyBlock(params.pos(), params.side());
		
		for(int i = 0; i < attempts; i++)
			if(MC.gameMode.continueDestroyBlock(params.pos(), params.side()))
				SwingHand.SERVER.swing(InteractionHand.MAIN_HAND);
	}
	
	private boolean canActThisTick()
	{
		return MC.player.tickCount >= nextActionTick;
	}
	
	private void consumeActionTick()
	{
		nextActionTick = MC.player.tickCount + actionDelay.getValueI();
	}
	
	private void resolveSelectedBlock(boolean force)
	{
		String keyword = blockKeyword.getValue();
		if(keyword == null)
			keyword = "";
		keyword = keyword.trim();
		
		if(!force && keyword.equals(lastBlockKeyword))
			return;
		
		lastBlockKeyword = keyword;
		Block resolved = resolveBlockKeyword(keyword);
		if(resolved == null || resolved.defaultBlockState().isAir())
		{
			if(unresolvedWarnCooldown <= 0)
			{
				ChatUtils.warning("PlaceNBreak: couldn't match block keyword '"
					+ keyword + "'.");
				unresolvedWarnCooldown = 40;
			}
			return;
		}
		
		selectedBlock = resolved;
	}
	
	private Block resolveBlockKeyword(String keyword)
	{
		if(keyword == null || keyword.isBlank())
			return null;
		
		String raw = keyword.trim();
		String norm = raw.toLowerCase(Locale.ROOT);
		
		// Exact ID (with namespace).
		Identifier exact = Identifier.tryParse(norm);
		if(exact != null && BuiltInRegistries.BLOCK.containsKey(exact))
			return BuiltInRegistries.BLOCK.getValue(exact);
		
		// Exact ID with minecraft namespace shorthand.
		Identifier mcId =
			Identifier.tryParse("minecraft:" + norm.replace(' ', '_'));
		if(mcId != null && BuiltInRegistries.BLOCK.containsKey(mcId))
			return BuiltInRegistries.BLOCK.getValue(mcId);
		
		Block best = null;
		int bestScore = Integer.MAX_VALUE;
		
		for(Identifier id : BuiltInRegistries.BLOCK.keySet())
		{
			Block block = BuiltInRegistries.BLOCK.getValue(id);
			if(block == null || block.defaultBlockState().isAir())
				continue;
			
			String fullId = id.toString().toLowerCase(Locale.ROOT);
			String path = id.getPath().toLowerCase(Locale.ROOT);
			String spacedPath = path.replace('_', ' ');
			String display =
				block.getName().getString().toLowerCase(Locale.ROOT);
			
			int score = matchScore(norm, fullId, path, spacedPath, display);
			if(score < 0 || score >= bestScore)
				continue;
			
			best = block;
			bestScore = score;
		}
		
		return best;
	}
	
	private int matchScore(String query, String fullId, String path,
		String spacedPath, String display)
	{
		if(fullId.equals(query) || path.equals(query)
			|| spacedPath.equals(query))
			return 0;
		if(path.startsWith(query) || spacedPath.startsWith(query))
			return 10 + path.length();
		if(path.contains(query) || spacedPath.contains(query))
			return 30 + path.length();
		if(display.startsWith(query))
			return 50 + display.length();
		if(display.contains(query))
			return 70 + display.length();
		if(fullId.contains(query))
			return 90 + fullId.length();
		return -1;
	}
}
