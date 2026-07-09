/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.CommonColors;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.BlockPlacer;
import net.wurstclient.util.BlockPlacer.BlockPlacingParams;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.InteractionSimulator;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"fast fill", "FastFill", "area fill", "AreaFill", "printer",
	"builder", "fill", "auto build"})
@DontSaveState
public final class FastFillHack extends Hack
	implements UpdateListener, RenderListener, GUIRenderListener
{
	private final SliderSetting range =
		new SliderSetting("Range", 5, 1, 7, 0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting perTick = new SliderSetting("Per tick",
		"How many blocks to place each tick. Higher is faster but sends more"
			+ " packets.",
		64, 1, 512, 1, ValueDisplay.INTEGER);
	
	private final BlockListSetting blocks = new BlockListSetting("Blocks",
		"The blocks FastFill is allowed to place.", "minecraft:stone");
	
	private final CheckboxSetting replace = new CheckboxSetting("Replace",
		"Break any block that isn't one of the chosen blocks before placing.\n"
			+ "When off, FastFill only fills air and other replaceable spots.",
		true);
	
	private final CheckboxSetting airPlace = new CheckboxSetting("Airplace",
		"Place blocks even when there's nothing to place them against (mid-air),"
			+ " and through walls. Needs a server that allows it.",
		true);
	
	private final CheckboxSetting autoSwitchTool = new CheckboxSetting(
		"Auto switch tool",
		"Switch to the best tool to break blocks even if the AutoTool hack is"
			+ " disabled. Only matters in survival; creative breaks instantly.",
		false);
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.SERVER);
	
	private Step step;
	private BlockPos posStart;
	private BlockPos posEnd;
	private BlockPos posLookingAt;
	private BlockPos min;
	private BlockPos max;
	
	public FastFillHack()
	{
		super("FastFill");
		setCategory(Category.BLOCKS);
		addSetting(range);
		addSetting(perTick);
		addSetting(blocks);
		addSetting(replace);
		addSetting(airPlace);
		addSetting(autoSwitchTool);
		addSetting(swingHand);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().nukerHack.setEnabled(false);
		WURST.getHax().speedNukerHack.setEnabled(false);
		WURST.getHax().excavatorHack.setEnabled(false);
		
		step = Step.START_POS;
		posStart = null;
		posEnd = null;
		posLookingAt = null;
		min = null;
		max = null;
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(GUIRenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(GUIRenderListener.class, this);
		
		MC.gameMode.stopDestroyBlock();
		
		posStart = null;
		posEnd = null;
		posLookingAt = null;
		min = null;
		max = null;
	}
	
	@Override
	public void onUpdate()
	{
		if(step.selectPos)
		{
			handlePositionSelection();
			return;
		}
		
		fill();
	}
	
	private void handlePositionSelection()
	{
		BlockPos current = step == Step.START_POS ? posStart : posEnd;
		
		if(current != null
			&& InputConstants.isKeyDown(MC.getWindow(), GLFW.GLFW_KEY_ENTER))
		{
			if(step == Step.START_POS)
				step = Step.END_POS;
			else
			{
				min = new BlockPos(Math.min(posStart.getX(), posEnd.getX()),
					Math.min(posStart.getY(), posEnd.getY()),
					Math.min(posStart.getZ(), posEnd.getZ()));
				max = new BlockPos(Math.max(posStart.getX(), posEnd.getX()),
					Math.max(posStart.getY(), posEnd.getY()),
					Math.max(posStart.getZ(), posEnd.getZ()));
				step = Step.FILL;
				posLookingAt = null;
			}
			return;
		}
		
		if(MC.hitResult instanceof BlockHitResult bhr)
		{
			posLookingAt = bhr.getBlockPos();
			if(MC.options.keyShift.isDown())
				posLookingAt = posLookingAt.relative(bhr.getDirection());
		}else
			posLookingAt = null;
		
		if(posLookingAt != null && MC.options.keyUse.isDown())
		{
			if(step == Step.START_POS)
				posStart = posLookingAt;
			else
				posEnd = posLookingAt;
		}
	}
	
	private void fill()
	{
		Vec3 eyesVec = RotationUtils.getEyesPos();
		BlockPos eyesBlock = BlockPos.containing(eyesVec);
		double rangeSq = range.getValueSq();
		int blockRange = range.getValueCeil();
		
		ArrayList<BlockPos> inReach = BlockUtils
			.getAllInBoxStream(eyesBlock, blockRange).filter(this::inArea)
			.filter(pos -> pos.distToCenterSqr(eyesVec) <= rangeSq)
			.sorted(
				Comparator.comparingDouble(pos -> pos.distToCenterSqr(eyesVec)))
			.collect(Collectors.toCollection(ArrayList::new));
		
		ArrayList<BlockPos> toBreak =
			replace.isChecked()
				? inReach.stream().filter(this::needsBreak)
					.collect(Collectors.toCollection(ArrayList::new))
				: new ArrayList<>();

		boolean creative = MC.player.getAbilities().instabuild;
		if(!creative && !toBreak.isEmpty())
		{
			equipTool(toBreak.get(0));
			BlockBreaker.breakBlocksWithPacketSpam(toBreak);
			swingHand.swing(InteractionHand.MAIN_HAND);
			return;
		}
		
		if(creative && !toBreak.isEmpty())
			BlockBreaker.breakBlocksWithPacketSpam(toBreak);
		
		if(!selectFillBlock())
			return;
		
		int budget = perTick.getValueI();
		for(BlockPos pos : inReach)
		{
			if(budget <= 0)
				break;
			if(!needsFill(pos))
				continue;
			
			if(!isFillBlock(MC.player.getMainHandItem()) && !selectFillBlock())
				break;
			if(!isFillBlock(MC.player.getMainHandItem()))
				break;
			
			if(placeBlock(pos, rangeSq))
				budget--;
		}
		
		swingHand.swing(InteractionHand.MAIN_HAND);
	}
	
	private void equipTool(BlockPos pos)
	{
		if(WURST.getHax().autoToolHack.isEnabled())
			WURST.getHax().autoToolHack.equipIfEnabled(pos);
		else if(autoSwitchTool.isChecked())
			WURST.getHax().autoToolHack.equipBestTool(pos, true, true, 0);
	}
	
	private boolean placeBlock(BlockPos pos, double rangeSq)
	{
		BlockPlacingParams params = BlockPlacer.getBlockPlacingParams(pos);
		BlockHitResult hitResult;
		Vec3 hitVec;
		
		if(params != null && !params.requiresSneaking())
		{
			if(params.distanceSq() > rangeSq)
				return false;
			hitResult = params.toHitResult();
			hitVec = params.hitVec();
		}else
		{
			// no supporting neighbor: airplace / through-wall with a faked hit
			if(!airPlace.isChecked())
				return false;
			hitVec = Vec3.atCenterOf(pos);
			if(RotationUtils.getEyesPos().distanceToSqr(hitVec) > rangeSq)
				return false;
			hitResult = new BlockHitResult(hitVec, Direction.UP, pos, false);
		}
		
		WURST.getRotationFaker().faceVectorPacket(hitVec);
		InteractionSimulator.rightClickBlock(hitResult,
			InteractionHand.MAIN_HAND, swingHand.getSelected());
		return true;
	}
	
	private boolean inArea(BlockPos pos)
	{
		return min != null && pos.getX() >= min.getX()
			&& pos.getX() <= max.getX() && pos.getY() >= min.getY()
			&& pos.getY() <= max.getY() && pos.getZ() >= min.getZ()
			&& pos.getZ() <= max.getZ();
	}
	
	/**
	 * A replaceable spot (air, water, grass, ...) we can place a block into.
	 */
	private boolean needsFill(BlockPos pos)
	{
		return BlockUtils.getState(pos).canBeReplaced();
	}
	
	/** A solid block that isn't one of the chosen blocks and can be broken. */
	private boolean needsBreak(BlockPos pos)
	{
		BlockState state = BlockUtils.getState(pos);
		if(state.canBeReplaced() || BlockUtils.isUnbreakable(pos))
			return false;
		return !blocks.matchesBlock(state.getBlock());
	}
	
	private boolean selectFillBlock()
	{
		if(isFillBlock(MC.player.getMainHandItem()))
			return true;
		
		return InventoryUtils.selectItem(this::isFillBlock);
	}
	
	private boolean isFillBlock(ItemStack stack)
	{
		if(!(stack.getItem() instanceof BlockItem blockItem))
			return false;
		
		Block block = blockItem.getBlock();
		if(!blocks.matchesBlock(block))
			return false;
		
		Inventory inventory = MC.player.getInventory();
		return !stack.isEmpty() && (MC.player.getAbilities().instabuild
			|| inventory.countItem(stack.getItem()) > 0);
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		int black = 0x80000000;
		int gray = 0x26404040;
		int green1 = 0x2600FF00;
		
		if(min != null)
		{
			AABB box =
				new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1,
					max.getY() + 1, max.getZ() + 1).deflate(1 / 16.0);
			RenderUtils.drawOutlinedBox(matrixStack, box, black, true);
		}else if(step == Step.END_POS && posStart != null
			&& (posEnd != null || posLookingAt != null))
		{
			BlockPos other = posEnd != null ? posEnd : posLookingAt;
			AABB preview =
				AABB.encapsulatingFullBlocks(posStart, other).deflate(1 / 16.0);
			RenderUtils.drawOutlinedBox(matrixStack, preview, black, true);
		}
		
		ArrayList<AABB> selectedBoxes = new ArrayList<>();
		if(posStart != null)
			selectedBoxes.add(new AABB(posStart).deflate(1 / 16.0));
		if(posEnd != null)
			selectedBoxes.add(new AABB(posEnd).deflate(1 / 16.0));
		RenderUtils.drawOutlinedBoxes(matrixStack, selectedBoxes, black, false);
		RenderUtils.drawSolidBoxes(matrixStack, selectedBoxes, green1, false);
		
		if(posLookingAt != null)
		{
			AABB box = new AABB(posLookingAt).deflate(1 / 16.0);
			RenderUtils.drawOutlinedBox(matrixStack, box, black, false);
			RenderUtils.drawSolidBox(matrixStack, box, gray, false);
		}
	}
	
	@Override
	public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
	{
		if(!step.selectPos)
			return;
		
		BlockPos current = step == Step.START_POS ? posStart : posEnd;
		String message = current != null
			? "Press enter to confirm, or select a different position."
			: step.message;
		
		Font tr = MC.font;
		int msgWidth = tr.width(message);
		int msgX1 = context.guiWidth() / 2 - msgWidth / 2;
		int msgX2 = msgX1 + msgWidth + 2;
		int msgY1 = context.guiHeight() / 2 + 1;
		int msgY2 = msgY1 + 10;
		
		context.fill(msgX1, msgY1, msgX2, msgY2, 0x80000000);
		context.text(tr, message, msgX1 + 2, msgY1 + 1, CommonColors.WHITE,
			false);
	}
	
	private enum Step
	{
		START_POS("Select start position.", true),
		END_POS("Select end position.", true),
		FILL("Filling area...", false);
		
		private final String message;
		private final boolean selectPos;
		
		Step(String message, boolean selectPos)
		{
			this.message = message;
			this.selectPos = selectPos;
		}
	}
}
