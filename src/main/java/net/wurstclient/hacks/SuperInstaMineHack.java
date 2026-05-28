/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShearsItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.EspStyleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.RenderUtils;

@SearchTags({"SuperInstaMine", "insta mine", "packet mine", "speed mine"})
public final class SuperInstaMineHack extends Hack implements UpdateListener,
	PacketOutputListener, RenderListener, CameraTransformViewBobbingListener
{
	private enum ListMode
	{
		WHITELIST("Whitelist"),
		BLACKLIST("Blacklist");
		
		private final String name;
		
		private ListMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum BreakDirectionMode
	{
		HORIZONTAL("Horizontal"),
		VERTICAL("Vertical");
		
		private final String name;
		
		private BreakDirectionMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private final CheckboxSetting toolChecker =
		new CheckboxSetting("Tool checker",
			"Only sends extra break packets when holding the correct tool.\n"
				+ "The center target is still processed.",
			true);
	private final EnumSetting<ListMode> listMode =
		new EnumSetting<>("List mode",
			"Whether the block list is treated as a whitelist or blacklist.",
			ListMode.values(), ListMode.BLACKLIST);
	private final BlockListSetting skippableBlocks =
		new BlockListSetting("Blocks to skip",
			"Skips packet-breaking for these blocks when in blacklist mode.");
	private final BlockListSetting nonSkippableBlocks =
		new BlockListSetting("Blocks to break",
			"Only packet-break these blocks when in whitelist mode.");
	private final SliderSetting range = new SliderSetting("Break mode (range)",
		"Pattern used for extra packet-breaking around the targeted block.\n"
			+ "-1..7 use the same pattern set as your reference module.",
		0, -1, 7, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting autoOrientBreakDirection =
		new CheckboxSetting("Auto orient break direction",
			"For break modes 3 and 4, auto-choose vertical or horizontal layout.",
			true);
	private final EnumSetting<BreakDirectionMode> breakDirectionMode =
		new EnumSetting<>("Break direction mode",
			"For break modes 3 and 4 when auto-orient is disabled.",
			BreakDirectionMode.values(), BreakDirectionMode.VERTICAL);
	private final SliderSetting tickDelay =
		new SliderSetting("Delay", "Delay between break cycles in ticks.", 0, 0,
			20, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting swingHand = new CheckboxSetting("Swing hand",
		"Swing the hand while packet-mining.", true);
	private final CheckboxSetting rotate = new CheckboxSetting("Rotate",
		"Face targeted blocks before sending break packets.", true);
	
	private final CheckboxSetting render = new CheckboxSetting("Render",
		"Render overlay on blocks being packet-mined.", true);
	private final EspStyleSetting renderStyle = new EspStyleSetting(
		"Render style", "Choose whether to draw boxes, tracers, or both.",
		EspStyleSetting.EspStyle.BOXES);
	private final ColorSetting sideColor = new ColorSetting("Side color",
		"Color of rendered box fills.", new Color(204, 0, 0, 10));
	private final ColorSetting lineColor = new ColorSetting("Line color",
		"Color of outlines/tracers.", new Color(204, 0, 0, 255));
	private final SliderSetting sideOpacity = new SliderSetting("Side opacity",
		"Opacity of rendered box fills.", 64, 0, 255, 1, ValueDisplay.INTEGER);
	private final SliderSetting lineOpacity =
		new SliderSetting("Line opacity", "Opacity of outlines and tracers.",
			160, 0, 255, 1, ValueDisplay.INTEGER);
	
	private final BlockPos.MutableBlockPos[] breakPositions =
		new BlockPos.MutableBlockPos[27];
	private boolean hasTarget;
	private int ticks;
	private Direction breakFace = Direction.DOWN;
	private Direction playerDir = Direction.NORTH;
	private int playerPitch;
	private boolean suppressCapture;
	
	public SuperInstaMineHack()
	{
		super("SuperInstaMine",
			"Attempts to instantly mine blocks with packet spam and optional multi-block patterns.",
			false);
		setCategory(Category.BLOCKS);
		addSetting(toolChecker);
		addSetting(listMode);
		addSetting(skippableBlocks);
		addSetting(nonSkippableBlocks);
		addSetting(range);
		addSetting(autoOrientBreakDirection);
		addSetting(breakDirectionMode);
		addSetting(tickDelay);
		addSetting(swingHand);
		addSetting(rotate);
		addSetting(render);
		addSetting(renderStyle);
		addSetting(sideColor);
		addSetting(lineColor);
		addSetting(sideOpacity);
		addSetting(lineOpacity);
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().autoMineHack.setEnabled(false);
		WURST.getHax().excavatorHack.setEnabled(false);
		WURST.getHax().nukerHack.setEnabled(false);
		WURST.getHax().nukerLegitHack.setEnabled(false);
		WURST.getHax().speedNukerHack.setEnabled(false);
		WURST.getHax().veinMinerHack.setEnabled(false);
		
		for(int i = 0; i < breakPositions.length; i++)
			breakPositions[i] = new BlockPos.MutableBlockPos(0, -2048, 0);
		
		hasTarget = false;
		ticks = 0;
		suppressCapture = false;
		
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		hasTarget = false;
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(MC.player == null || suppressCapture)
			return;
		
		if(!(event.getPacket() instanceof ServerboundPlayerActionPacket packet))
			return;
		
		if(packet
			.getAction() != ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)
			return;
		
		breakFace = packet.getDirection();
		playerDir = MC.player.getDirection();
		playerPitch = Math.round(MC.player.getXRot());
		updateBreakPositions(packet.getPos());
		hasTarget = true;
	}
	
	@Override
	public void onUpdate()
	{
		skippableBlocks
			.setVisibleInGui(listMode.getSelected() == ListMode.BLACKLIST);
		nonSkippableBlocks
			.setVisibleInGui(listMode.getSelected() == ListMode.WHITELIST);
		breakDirectionMode
			.setVisibleInGui(!autoOrientBreakDirection.isChecked());
		
		if(MC.player == null || MC.level == null || !hasTarget)
			return;
		
		if(ticks >= tickDelay.getValueI())
		{
			ticks = 0;
			performBreakPattern(range.getValueI());
		}else
			ticks++;
	}
	
	private void performBreakPattern(int rangeMode)
	{
		switch(rangeMode)
		{
			case -1:
			doRotatingBreakPacketWithSwing();
			switch(playerDir)
			{
				case NORTH -> doStartStopBreakPacket(breakPositions[2]);
				case SOUTH -> doStartStopBreakPacket(breakPositions[1]);
				case EAST -> doStartStopBreakPacket(breakPositions[4]);
				case WEST -> doStartStopBreakPacket(breakPositions[3]);
				default ->
					{
					}
			}
			break;
			
			case 0:
			doRotatingBreakPacketWithSwing();
			break;
			
			case 1:
			doRotatingBreakPacketWithSwing();
			switch(playerDir)
			{
				case NORTH -> doStartStopBreakPacket(breakPositions[1]);
				case SOUTH -> doStartStopBreakPacket(breakPositions[2]);
				case EAST -> doStartStopBreakPacket(breakPositions[3]);
				case WEST -> doStartStopBreakPacket(breakPositions[4]);
				default ->
					{
					}
			}
			break;
			
			case 2:
			doRotatingBreakPacketWithSwing();
			if(playerDir == Direction.NORTH || playerDir == Direction.SOUTH)
			{
				doStartStopBreakPacket(breakPositions[2]);
				doStartStopBreakPacket(breakPositions[1]);
			}
			if(playerDir == Direction.EAST || playerDir == Direction.WEST)
			{
				doStartStopBreakPacket(breakPositions[4]);
				doStartStopBreakPacket(breakPositions[3]);
			}
			break;
			
			case 3:
			doRotatingBreakPacketWithSwing();
			doMode3Pattern();
			break;
			
			case 4:
			doRotatingBreakPacketWithSwing();
			doMode4Pattern();
			break;
			
			case 5:
			doRotatingBreakPacketWithSwing();
			breakByIndices(1, 2, 3, 4, 5, 6, 7, 8, 9, 18);
			break;
			
			case 6:
			doRotatingBreakPacketWithSwing();
			breakByIndices(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 18, 19,
				20, 21, 22);
			break;
			
			case 7:
			doRotatingBreakPacketWithSwing();
			breakByIndices(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
				16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26);
			break;
			
			default:
			doRotatingBreakPacketWithSwing();
			break;
		}
	}
	
	private void doMode3Pattern()
	{
		boolean vertical = (autoOrientBreakDirection.isChecked()
			&& playerPitch <= 30 && playerPitch >= -30)
			|| (!autoOrientBreakDirection.isChecked() && breakDirectionMode
				.getSelected() == BreakDirectionMode.VERTICAL);
		
		if(vertical)
		{
			if(playerDir == Direction.NORTH || playerDir == Direction.SOUTH)
				breakByIndices(2, 1, 9, 18);
			if(playerDir == Direction.EAST || playerDir == Direction.WEST)
				breakByIndices(4, 3, 9, 18);
		}else
			breakByIndices(1, 2, 3, 4);
	}
	
	private void doMode4Pattern()
	{
		boolean vertical = (autoOrientBreakDirection.isChecked()
			&& playerPitch <= 30 && playerPitch >= -30)
			|| (!autoOrientBreakDirection.isChecked() && breakDirectionMode
				.getSelected() == BreakDirectionMode.VERTICAL);
		
		if(vertical)
		{
			if(playerDir == Direction.NORTH || playerDir == Direction.SOUTH)
				breakByIndices(2, 1, 9, 18, 10, 11, 19, 20);
			if(playerDir == Direction.EAST || playerDir == Direction.WEST)
				breakByIndices(4, 3, 9, 18, 12, 13, 21, 22);
		}else
			breakByIndices(1, 2, 3, 4, 5, 6, 7, 8);
	}
	
	private void breakByIndices(int... indices)
	{
		for(int i : indices)
			doStartStopBreakPacket(breakPositions[i]);
	}
	
	private boolean shouldBreak(BlockPos pos)
	{
		if(MC.level == null || MC.player == null || pos == null)
			return false;
		
		Block block = MC.level.getBlockState(pos).getBlock();
		boolean listCheck = listMode.getSelected() == ListMode.WHITELIST
			? nonSkippableBlocks.contains(block)
			: !skippableBlocks.contains(block);
		if(!listCheck)
			return false;
		
		if(!BlockUtils.canBeClicked(pos) || BlockUtils.isUnbreakable(pos))
			return false;
		
		boolean isCenter = pos.equals(breakPositions[0]);
		if(!isCenter && toolChecker.isChecked()
			&& !MC.player.getAbilities().instabuild
			&& isTool(MC.player.getMainHandItem()))
			return MC.player.getMainHandItem()
				.isCorrectToolForDrops(MC.level.getBlockState(pos));
		
		return true;
	}
	
	private boolean isTool(ItemStack itemStack)
	{
		return itemStack.is(ItemTags.AXES) || itemStack.is(ItemTags.HOES)
			|| itemStack.is(ItemTags.PICKAXES) || itemStack.is(ItemTags.SHOVELS)
			|| itemStack.getItem() instanceof ShearsItem;
	}
	
	private void doStartStopBreakPacket(BlockPos pos)
	{
		if(!shouldBreak(pos) || MC.getConnection() == null)
			return;
		
		Direction face = getPacketDirection(pos);
		suppressCapture = true;
		MC.getConnection()
			.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos,
				face));
		suppressCapture = false;
		MC.getConnection()
			.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos,
				face));
	}
	
	private void doRotatingBreakPacketWithSwing()
	{
		BlockPos center = breakPositions[0];
		if(!shouldBreak(center) || MC.getConnection() == null)
			return;
		
		if(swingHand.isChecked())
		{
			MC.getConnection()
				.send(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
			MC.player.swing(InteractionHand.MAIN_HAND);
		}
		
		if(rotate.isChecked())
			WURST.getRotationFaker().faceVectorPacket(Vec3.atCenterOf(center));
		MC.getConnection()
			.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, center,
				getPacketDirection(center)));
		
		if(rotate.isChecked())
			WURST.getRotationFaker().faceVectorPacket(Vec3.atCenterOf(center));
		suppressCapture = true;
		MC.getConnection()
			.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
				center, getPacketDirection(center)));
		suppressCapture = false;
	}
	
	private Direction getPacketDirection(BlockPos pos)
	{
		if(MC.player == null || pos == null)
			return breakFace;
		
		Vec3 delta = Vec3.atCenterOf(pos).subtract(MC.player.getEyePosition());
		if(delta.lengthSqr() < 1.0E-6)
			return breakFace;
		
		double ax = Math.abs(delta.x);
		double ay = Math.abs(delta.y);
		double az = Math.abs(delta.z);
		
		if(ay >= ax && ay >= az)
			return delta.y > 0 ? Direction.DOWN : Direction.UP;
		if(ax >= az)
			return delta.x > 0 ? Direction.WEST : Direction.EAST;
		return delta.z > 0 ? Direction.NORTH : Direction.SOUTH;
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(!render.isChecked() || MC.level == null || MC.player == null
			|| !hasTarget)
			return;
		
		List<BlockPos> targets = getCurrentPatternTargets(range.getValueI());
		if(targets.isEmpty())
			return;
		
		ArrayList<AABB> boxes = new ArrayList<>();
		ArrayList<Vec3> tracerEnds = new ArrayList<>();
		for(BlockPos pos : targets)
		{
			if(!shouldBreak(pos))
				continue;
			boxes.add(new AABB(pos));
			tracerEnds.add(Vec3.atCenterOf(pos));
		}
		
		if(boxes.isEmpty())
			return;
		
		if(renderStyle.hasBoxes())
		{
			int fillColor = sideColor.getColorI(sideOpacity.getValueI());
			int outlineColor = lineColor.getColorI(lineOpacity.getValueI());
			RenderUtils.drawSolidBoxes(matrixStack, boxes, fillColor, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, boxes, outlineColor,
				false);
		}
		
		if(renderStyle.hasLines())
		{
			int tracerColor = lineColor.getColorI(lineOpacity.getValueI());
			RenderUtils.drawTracers(matrixStack, partialTicks, tracerEnds,
				tracerColor, false);
		}
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(renderStyle.hasLines())
			event.cancel();
	}
	
	private List<BlockPos> getCurrentPatternTargets(int rangeMode)
	{
		ArrayList<BlockPos> targets = new ArrayList<>();
		targets.add(breakPositions[0].immutable());
		
		switch(rangeMode)
		{
			case -1:
			switch(playerDir)
			{
				case SOUTH -> targets.add(breakPositions[1].immutable());
				case NORTH -> targets.add(breakPositions[2].immutable());
				case WEST -> targets.add(breakPositions[3].immutable());
				case EAST -> targets.add(breakPositions[4].immutable());
				default ->
					{
					}
			}
			break;
			
			case 0:
			break;
			
			case 1:
			switch(playerDir)
			{
				case NORTH -> targets.add(breakPositions[1].immutable());
				case SOUTH -> targets.add(breakPositions[2].immutable());
				case EAST -> targets.add(breakPositions[3].immutable());
				case WEST -> targets.add(breakPositions[4].immutable());
				default ->
					{
					}
			}
			break;
			
			case 2:
			if(playerDir == Direction.NORTH || playerDir == Direction.SOUTH)
			{
				targets.add(breakPositions[1].immutable());
				targets.add(breakPositions[2].immutable());
			}
			if(playerDir == Direction.EAST || playerDir == Direction.WEST)
			{
				targets.add(breakPositions[3].immutable());
				targets.add(breakPositions[4].immutable());
			}
			break;
			
			case 3:
			addMode3Targets(targets);
			break;
			
			case 4:
			addMode4Targets(targets);
			break;
			
			case 5:
			addTargetsByIndices(targets, 1, 2, 3, 4, 5, 6, 7, 8, 9, 18);
			break;
			
			case 6:
			addTargetsByIndices(targets, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
				13, 18, 19, 20, 21, 22);
			break;
			
			case 7:
			addTargetsByIndices(targets, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
				13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26);
			break;
			
			default:
			break;
		}
		
		return targets;
	}
	
	private void addMode3Targets(ArrayList<BlockPos> targets)
	{
		boolean vertical = (autoOrientBreakDirection.isChecked()
			&& playerPitch <= 30 && playerPitch >= -30)
			|| (!autoOrientBreakDirection.isChecked() && breakDirectionMode
				.getSelected() == BreakDirectionMode.VERTICAL);
		
		if(vertical)
		{
			if(playerDir == Direction.NORTH || playerDir == Direction.SOUTH)
				addTargetsByIndices(targets, 1, 2, 9, 18);
			if(playerDir == Direction.EAST || playerDir == Direction.WEST)
				addTargetsByIndices(targets, 9, 18, 3, 4);
		}else
			addTargetsByIndices(targets, 1, 2, 3, 4);
	}
	
	private void addMode4Targets(ArrayList<BlockPos> targets)
	{
		boolean vertical = (autoOrientBreakDirection.isChecked()
			&& playerPitch <= 30 && playerPitch >= -30)
			|| (!autoOrientBreakDirection.isChecked() && breakDirectionMode
				.getSelected() == BreakDirectionMode.VERTICAL);
		
		if(vertical)
		{
			if(playerDir == Direction.NORTH || playerDir == Direction.SOUTH)
				addTargetsByIndices(targets, 1, 2, 9, 18, 10, 11, 19, 20);
			if(playerDir == Direction.EAST || playerDir == Direction.WEST)
				addTargetsByIndices(targets, 9, 18, 3, 4, 12, 13, 21, 22);
		}else
			addTargetsByIndices(targets, 1, 2, 3, 4, 5, 6, 7, 8);
	}
	
	private void addTargetsByIndices(ArrayList<BlockPos> targets,
		int... indices)
	{
		for(int i : indices)
			targets.add(breakPositions[i].immutable());
	}
	
	private void updateBreakPositions(BlockPos center)
	{
		// y = 0 layer
		breakPositions[0].set(center);
		breakPositions[1].set(center.getX() + 1, center.getY(), center.getZ());
		breakPositions[2].set(center.getX() - 1, center.getY(), center.getZ());
		breakPositions[3].set(center.getX(), center.getY(), center.getZ() + 1);
		breakPositions[4].set(center.getX(), center.getY(), center.getZ() - 1);
		breakPositions[5].set(center.getX() + 1, center.getY(),
			center.getZ() + 1);
		breakPositions[6].set(center.getX() - 1, center.getY(),
			center.getZ() - 1);
		breakPositions[7].set(center.getX() + 1, center.getY(),
			center.getZ() - 1);
		breakPositions[8].set(center.getX() - 1, center.getY(),
			center.getZ() + 1);
		
		// y = +1 layer
		breakPositions[9].set(center.getX(), center.getY() + 1, center.getZ());
		breakPositions[10].set(center.getX() + 1, center.getY() + 1,
			center.getZ());
		breakPositions[11].set(center.getX() - 1, center.getY() + 1,
			center.getZ());
		breakPositions[12].set(center.getX(), center.getY() + 1,
			center.getZ() + 1);
		breakPositions[13].set(center.getX(), center.getY() + 1,
			center.getZ() - 1);
		breakPositions[14].set(center.getX() + 1, center.getY() + 1,
			center.getZ() + 1);
		breakPositions[15].set(center.getX() - 1, center.getY() + 1,
			center.getZ() - 1);
		breakPositions[16].set(center.getX() + 1, center.getY() + 1,
			center.getZ() - 1);
		breakPositions[17].set(center.getX() - 1, center.getY() + 1,
			center.getZ() + 1);
		
		// y = -1 layer
		breakPositions[18].set(center.getX(), center.getY() - 1, center.getZ());
		breakPositions[19].set(center.getX() + 1, center.getY() - 1,
			center.getZ());
		breakPositions[20].set(center.getX() - 1, center.getY() - 1,
			center.getZ());
		breakPositions[21].set(center.getX(), center.getY() - 1,
			center.getZ() + 1);
		breakPositions[22].set(center.getX(), center.getY() - 1,
			center.getZ() - 1);
		breakPositions[23].set(center.getX() + 1, center.getY() - 1,
			center.getZ() + 1);
		breakPositions[24].set(center.getX() - 1, center.getY() - 1,
			center.getZ() - 1);
		breakPositions[25].set(center.getX() + 1, center.getY() - 1,
			center.getZ() - 1);
		breakPositions[26].set(center.getX() - 1, center.getY() - 1,
			center.getZ() + 1);
	}
	
}
