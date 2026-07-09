/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.util.CommonColors;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
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
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"area nuker", "AreaNuker", "speed excavator", "SpeedExcavator",
	"area speed nuker"})
@DontSaveState
public final class AreaNukerHack extends Hack
	implements UpdateListener, RenderListener, GUIRenderListener
{
	private final SliderSetting range =
		new SliderSetting("Range", 5, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting autoSwitchTool = new CheckboxSetting(
		"Auto switch tool",
		"Automatically switch to the best tool in your hotbar for the current"
			+ " block even if the AutoTool hack is disabled.",
		false);
	
	private final SwingHandSetting swingHand = new SwingHandSetting(
		SwingHandSetting.genericMiningDescription(this), SwingHand.OFF);
	
	private Step step;
	private BlockPos posStart;
	private BlockPos posEnd;
	private BlockPos posLookingAt;
	private Area area;
	private BlockPos currentBlock;
	
	private boolean prevAutoToolEnabled;
	
	public AreaNukerHack()
	{
		super("AreaNuker");
		setCategory(Category.BLOCKS);
		addSetting(range);
		addSetting(autoSwitchTool);
		addSetting(swingHand);
	}
	
	@Override
	public String getRenderName()
	{
		if(step == Step.NUKE && area != null && area.blocksList.size() > 0)
		{
			double broken = area.blocksList.size() - area.remainingBlocks;
			int pct = (int)(broken / area.blocksList.size() * 100);
			return getName() + " " + pct + "%";
		}
		return getName();
	}
	
	@Override
	protected void onEnable()
	{
		// disable conflicting block-breaking hacks
		WURST.getHax().autoMineHack.setEnabled(false);
		WURST.getHax().excavatorHack.setEnabled(false);
		WURST.getHax().nukerHack.setEnabled(false);
		WURST.getHax().nukerLegitHack.setEnabled(false);
		WURST.getHax().speedNukerHack.setEnabled(false);
		WURST.getHax().tunnellerHack.setEnabled(false);
		WURST.getHax().veinMinerHack.setEnabled(false);
		
		prevAutoToolEnabled = WURST.getHax().autoToolHack.isEnabled();
		
		step = Step.START_POS;
		posStart = null;
		posEnd = null;
		posLookingAt = null;
		area = null;
		currentBlock = null;
		
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
		area = null;
		currentBlock = null;
		
		if(!prevAutoToolEnabled && WURST.getHax().autoToolHack.isEnabled())
			WURST.getHax().autoToolHack.setEnabled(false);
	}
	
	@Override
	public void onUpdate()
	{
		if(step.selectPos)
			handlePositionSelection();
		else if(step == Step.SCAN_AREA)
			scanArea();
		else if(step == Step.NUKE)
			nuke();
	}
	
	private void handlePositionSelection()
	{
		if(getStepPos() != null
			&& InputConstants.isKeyDown(MC.getWindow(), GLFW.GLFW_KEY_ENTER))
		{
			step = Step.values()[step.ordinal() + 1];
			if(!step.selectPos)
				posLookingAt = null;
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
			setStepPos(posLookingAt);
	}
	
	private BlockPos getStepPos()
	{
		return step == Step.START_POS ? posStart : posEnd;
	}
	
	private void setStepPos(BlockPos pos)
	{
		if(step == Step.START_POS)
			posStart = pos;
		else
			posEnd = pos;
	}
	
	private void scanArea()
	{
		if(area == null)
			area = new Area(posStart, posEnd);
		
		// scan in chunks per tick so huge areas don't freeze the game
		for(int i = 0; i < area.scanSpeed && area.iterator.hasNext(); i++)
		{
			area.scannedBlocks++;
			BlockPos pos = area.iterator.next();
			if(BlockUtils.canBeClicked(pos))
			{
				area.blocksList.add(pos);
				area.blocksSet.add(pos);
			}
		}
		
		area.progress = (float)area.scannedBlocks / area.totalBlocks;
		
		if(!area.iterator.hasNext())
		{
			area.remainingBlocks = area.blocksList.size();
			step = Step.NUKE;
		}
	}
	
	private void nuke()
	{
		// wait for AutoEat to finish eating
		if(WURST.getHax().autoEatHack.isEating())
			return;
		
		ArrayList<BlockPos> blocks = getValidBlocksInRange();
		currentBlock = blocks.isEmpty() ? null : blocks.get(0);
		
		if(!blocks.isEmpty())
		{
			// equip best tool for the closest block
			if(WURST.getHax().autoToolHack.isEnabled())
				WURST.getHax().autoToolHack.equipIfEnabled(blocks.get(0));
			else if(autoSwitchTool.isChecked())
				WURST.getHax().autoToolHack.equipBestTool(blocks.get(0), true,
					true, 0);
			
			// the speed: break every in-range block at once via packet spam
			BlockBreaker.breakBlocksWithPacketSpam(blocks);
			swingHand.swing(InteractionHand.MAIN_HAND);
		}else
			MC.gameMode.stopDestroyBlock();
		
		// recount remaining blocks; finish when the box is clear
		Predicate<BlockPos> breakable = MC.player.getAbilities().instabuild
			? BlockUtils::canBeClicked : pos -> BlockUtils.canBeClicked(pos)
				&& !BlockUtils.isUnbreakable(pos);
		area.remainingBlocks =
			(int)area.blocksList.parallelStream().filter(breakable).count();
		
		if(area.remainingBlocks == 0)
			setEnabled(false);
	}
	
	private ArrayList<BlockPos> getValidBlocksInRange()
	{
		Vec3 eyesVec = RotationUtils.getEyesPos();
		BlockPos eyesBlock = BlockPos.containing(eyesVec);
		double rangeSq = range.getValueSq();
		int blockRange = range.getValueCeil();
		
		return BlockUtils.getAllInBoxStream(eyesBlock, blockRange)
			.filter(pos -> pos.distToCenterSqr(eyesVec) <= rangeSq)
			.filter(area.blocksSet::contains).filter(BlockUtils::canBeClicked)
			.filter(pos -> !BlockUtils.isUnbreakable(pos))
			.sorted(
				Comparator.comparingDouble(pos -> pos.distToCenterSqr(eyesVec)))
			.collect(Collectors.toCollection(ArrayList::new));
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		int black = 0x80000000;
		int gray = 0x26404040;
		int green1 = 0x2600FF00;
		int green2 = 0x4D00FF00;
		
		// confirmed area box
		if(area != null)
		{
			AABB areaBox = new AABB(area.minX, area.minY, area.minZ,
				area.minX + area.sizeX + 1, area.minY + area.sizeY + 1,
				area.minZ + area.sizeZ + 1).deflate(1 / 16.0);
			RenderUtils.drawOutlinedBox(matrixStack, areaBox, black, true);
			
			// scanning sweep highlight
			if(area.progress < 1)
			{
				double scanX =
					Mth.lerp(area.progress, areaBox.minX, areaBox.maxX);
				AABB scanner = areaBox.setMinX(scanX).setMaxX(scanX);
				RenderUtils.drawOutlinedBox(matrixStack, scanner, black, true);
				RenderUtils.drawSolidBox(matrixStack, scanner, green2, true);
			}
		}
		
		// live preview between start and the position being chosen for end
		if(area == null && step == Step.END_POS && posStart != null
			&& getStepPos() != null)
		{
			AABB preview = AABB.encapsulatingFullBlocks(posStart, getStepPos())
				.deflate(1 / 16.0);
			RenderUtils.drawOutlinedBox(matrixStack, preview, black, true);
		}
		
		// already-selected corner positions
		ArrayList<AABB> selectedBoxes = new ArrayList<>();
		if(posStart != null)
			selectedBoxes.add(new AABB(posStart).deflate(1 / 16.0));
		if(posEnd != null)
			selectedBoxes.add(new AABB(posEnd).deflate(1 / 16.0));
		RenderUtils.drawOutlinedBoxes(matrixStack, selectedBoxes, black, false);
		RenderUtils.drawSolidBoxes(matrixStack, selectedBoxes, green1, false);
		
		// block currently under the crosshair during selection
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
		String message;
		if(step.selectPos && getStepPos() != null)
			message = "Press enter to confirm, or select a different position.";
		else
			message = step.message;
		
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
		SCAN_AREA("Scanning area...", false),
		NUKE("Nuking area...", false);
		
		private final String message;
		private final boolean selectPos;
		
		Step(String message, boolean selectPos)
		{
			this.message = message;
			this.selectPos = selectPos;
		}
	}
	
	private static final class Area
	{
		private final int minX, minY, minZ;
		private final int sizeX, sizeY, sizeZ;
		
		private final int totalBlocks, scanSpeed;
		private final Iterator<BlockPos> iterator;
		
		private int scannedBlocks, remainingBlocks;
		private float progress;
		
		private final ArrayList<BlockPos> blocksList = new ArrayList<>();
		private final HashSet<BlockPos> blocksSet = new HashSet<>();
		
		private Area(BlockPos start, BlockPos end)
		{
			minX = Math.min(start.getX(), end.getX());
			minY = Math.min(start.getY(), end.getY());
			minZ = Math.min(start.getZ(), end.getZ());
			
			sizeX = Math.abs(start.getX() - end.getX());
			sizeY = Math.abs(start.getY() - end.getY());
			sizeZ = Math.abs(start.getZ() - end.getZ());
			
			totalBlocks = (sizeX + 1) * (sizeY + 1) * (sizeZ + 1);
			scanSpeed = Mth.clamp(totalBlocks / 30, 1, 16384);
			iterator = BlockUtils.getAllInBox(start, end).iterator();
		}
	}
}
