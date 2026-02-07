/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.FileSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.*;
import net.wurstclient.util.BlockPlacer.BlockPlacingParams;
import net.wurstclient.util.json.JsonException;

public final class AutoBuildHack extends Hack implements UpdateListener,
	RightClickListener, RenderListener, GUIRenderListener
{
	private static final AABB BLOCK_BOX =
		new AABB(1 / 16.0, 1 / 16.0, 1 / 16.0, 15 / 16.0, 15 / 16.0, 15 / 16.0);
	
	private final FileSetting templateSetting = new FileSetting("Template",
		"Determines what to build.\n\n"
			+ "Templates are just JSON files. Feel free to add your own or to edit / delete the default templates.\n\n"
			+ "If you mess up, simply press the 'Reset to Defaults' button or delete the folder.",
		"autobuild", DefaultAutoBuildTemplates::createFiles);
	
	private final SliderSetting range = new SliderSetting("Range",
		"How far to reach when placing blocks.\n" + "Recommended values:\n"
			+ "6.0 for vanilla\n" + "4.25 for NoCheat+",
		6, 1, 10, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting checkLOS = new CheckboxSetting(
		"Check line of sight",
		"Makes sure that you don't reach through walls when placing blocks. Can help with AntiCheat plugins but slows down building.",
		false);
	
	private final CheckboxSetting useSavedBlocks = new CheckboxSetting(
		"Use saved blocks",
		"Tries to place the same blocks that were saved in the template.\n\n"
			+ "If the template does not specify block types, it will be built"
			+ " from whatever block you are holding.",
		true);
	
	private final CheckboxSetting fastPlace =
		new CheckboxSetting("Always FastPlace",
			"Builds as if FastPlace was enabled, even if it's not.", true);
	
	private final CheckboxSetting strictBuildOrder = new CheckboxSetting(
		"Strict build order",
		"Places blocks in exactly the same order that they appear in the"
			+ " template. This is slower, but provides more consistent results.",
		false);
	
	private final CheckboxSetting previewTemplate =
		new CheckboxSetting("Preview template",
			"Shows a visual preview before starting the build.", true);
	
	private final SliderSetting confirmTicks =
		new SliderSetting("Confirm ticks",
			"How many ticks a placed block must stay visible before it is"
				+ " removed from the remaining list.",
			2, 1, 10, 1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	
	private Status status = Status.NO_TEMPLATE;
	private AutoBuildTemplate template;
	private LinkedHashMap<BlockPos, Item> remainingBlocks =
		new LinkedHashMap<>();
	private LinkedHashMap<BlockPos, Item> previewBlocks = new LinkedHashMap<>();
	private BlockPos previewStartPos;
	private Direction previewDirection;
	private long lastProgressMs;
	private final Map<BlockPos, Integer> placedConfirmations = new HashMap<>();
	
	private static final long STUCK_TIMEOUT_MS = 1250L;
	
	public AutoBuildHack()
	{
		super("AutoBuild");
		setCategory(Category.BLOCKS);
		addSetting(templateSetting);
		addSetting(range);
		addSetting(checkLOS);
		addSetting(useSavedBlocks);
		addSetting(fastPlace);
		addSetting(strictBuildOrder);
		addSetting(previewTemplate);
		addSetting(confirmTicks);
	}
	
	@Override
	public String getRenderName()
	{
		String name = getName();
		
		switch(status)
		{
			case NO_TEMPLATE:
			break;
			
			case LOADING:
			name += " [Loading...]";
			break;
			
			case IDLE:
			name += " [" + template.getName() + "]";
			break;
			
			case BUILDING:
			double total = template.size();
			double placed = total - remainingBlocks.size();
			double progress = Math.round(placed / total * 1e4) / 1e2;
			name += " [" + template.getName() + "] " + progress + "%";
			break;
		}
		
		return name;
	}
	
	@Override
	protected void onEnable()
	{
		WURST.getHax().instaBuildHack.setEnabled(false);
		WURST.getHax().templateToolHack.setEnabled(false);
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RightClickListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(GUIRenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RightClickListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(GUIRenderListener.class, this);
		
		remainingBlocks.clear();
		previewBlocks.clear();
		placedConfirmations.clear();
		
		if(template == null)
			status = Status.NO_TEMPLATE;
		else
			status = Status.IDLE;
	}
	
	@Override
	public void onRenderGUI(GuiGraphics context, float partialTicks)
	{
		if(MC.player == null)
			return;
		
		int count = getCrosshairBlockCount();
		if(count <= 0)
			return;
		
		String text = Integer.toString(count);
		Font font = MC.font;
		int centerX = context.guiWidth() / 2;
		int y = context.guiHeight() / 2 + 10;
		int textWidth = font.width(text);
		int x = centerX - textWidth / 2;
		context.drawString(font, text, x, y, 0xFFFFFFFF, true);
	}
	
	private int getCrosshairBlockCount()
	{
		switch(status)
		{
			case BUILDING:
			return remainingBlocks.size();
			
			case IDLE:
			if(previewTemplate.isChecked() && !previewBlocks.isEmpty())
			{
				int needed = 0;
				for(Map.Entry<BlockPos, Item> e : previewBlocks.entrySet())
				{
					if(!isBlockPlaced(e.getKey(), e.getValue()))
						needed++;
				}
				return needed;
			}
			return 0;
			
			default:
			return 0;
		}
	}
	
	@Override
	public void onRightClick(RightClickEvent event)
	{
		if(status == Status.NO_TEMPLATE || status == Status.LOADING)
			return;
		
		if(MC.options.keySprint.isDown())
			return;
		
		if(status != Status.IDLE)
			return;
		
		BlockHitResult blockHitResult = getStartHitResult();
		if(blockHitResult == null)
			return;
		
		boolean airStart = blockHitResult.getType() == HitResult.Type.MISS;
		BlockPos hitResultPos = blockHitResult.getBlockPos();
		boolean clickable = BlockUtils.canBeClicked(hitResultPos);
		if(!airStart && !clickable)
			return;
		
		event.cancel();
		
		BlockPos startPos = airStart ? hitResultPos
			: hitResultPos.relative(blockHitResult.getDirection());
		Direction direction = MC.player.getDirection();
		remainingBlocks = template.getBlocksToPlace(startPos, direction);
		lastProgressMs = System.currentTimeMillis();
		
		status = Status.BUILDING;
	}
	
	@Override
	public void onUpdate()
	{
		switch(status)
		{
			case NO_TEMPLATE:
			loadSelectedTemplate();
			break;
			
			case LOADING:
			break;
			
			case IDLE:
			if(!template.isSelected(templateSetting))
				loadSelectedTemplate();
			updatePreview();
			break;
			
			case BUILDING:
			buildNormally();
			break;
		}
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(status == Status.BUILDING)
		{
			renderBlocks(matrixStack, remainingBlocks, 0x2600FF00);
			return;
		}
		
		if(status == Status.IDLE && previewTemplate.isChecked())
			renderBlocks(matrixStack, previewBlocks, 0x2600A0FF);
	}
	
	private void buildNormally()
	{
		int beforeSize = remainingBlocks.size();
		remainingBlocks.entrySet().removeIf(entry -> {
			BlockPos pos = entry.getKey();
			if(!isBlockPlaced(pos, entry.getValue()))
			{
				placedConfirmations.remove(pos);
				return false;
			}
			
			int count = placedConfirmations.getOrDefault(pos, 0) + 1;
			int required = Math.max(1, confirmTicks.getValueI());
			if(count < required)
			{
				placedConfirmations.put(pos, count);
				return false;
			}
			
			placedConfirmations.remove(pos);
			return true;
		});
		if(remainingBlocks.size() != beforeSize)
			lastProgressMs = System.currentTimeMillis();
		
		if(!placedConfirmations.isEmpty())
			return;
		
		if(remainingBlocks.isEmpty())
		{
			status = Status.IDLE;
			return;
		}
		
		if(!fastPlace.isChecked() && MC.rightClickDelay > 0)
			return;
		
		double rangeSq = range.getValueSq();
		boolean stuck = isStuck();
		for(Map.Entry<BlockPos, Item> entry : remainingBlocks.entrySet())
		{
			BlockPos pos = entry.getKey();
			Item item = entry.getValue();
			
			BlockPlacingParams params = getPlacingParams(pos);
			if(params == null || params.distanceSq() > rangeSq
				|| checkLOS.isChecked() && !params.lineOfSight())
				if(strictBuildOrder.isChecked() && !stuck)
					return;
				else
					continue;
				
			if(useSavedBlocks.isChecked() && item != Items.AIR
				&& !MC.player.getMainHandItem().is(item))
			{
				giveOrSelectItem(item);
				return;
			}
			
			MC.rightClickDelay = 4;
			RotationUtils.getNeededRotations(params.hitVec())
				.sendPlayerLookPacket();
			InteractionSimulator.rightClickBlock(params.toHitResult());
			return;
		}
	}
	
	private void giveOrSelectItem(Item item)
	{
		if(InventoryUtils.selectItem(item, 36, true))
			return;
		
		if(!MC.player.hasInfiniteMaterials())
			return;
		
		Inventory inventory = MC.player.getInventory();
		int slot = inventory.getFreeSlot();
		if(slot < 0)
			slot = inventory.getSelectedSlot();
		
		ItemStack stack = new ItemStack(item);
		InventoryUtils.setCreativeStack(slot, stack);
	}
	
	private void loadSelectedTemplate()
	{
		status = Status.LOADING;
		Path path = templateSetting.getSelectedFile();
		
		try
		{
			template = AutoBuildTemplate.load(path);
			status = Status.IDLE;
			previewBlocks.clear();
			previewStartPos = null;
			previewDirection = null;
			placedConfirmations.clear();
			lastProgressMs = System.currentTimeMillis();
			
		}catch(IOException | JsonException e)
		{
			Path fileName = path.getFileName();
			ChatUtils.error("Couldn't load template '" + fileName + "'.");
			
			String simpleClassName = e.getClass().getSimpleName();
			String message = e.getMessage();
			ChatUtils.message(simpleClassName + ": " + message);
			
			e.printStackTrace();
			setEnabled(false);
		}
	}
	
	public Path getFolder()
	{
		return templateSetting.getFolder();
	}
	
	private void updatePreview()
	{
		if(!previewTemplate.isChecked() || template == null)
		{
			previewBlocks.clear();
			return;
		}
		
		if(WURST.getHax().freecamHack.isEnabled() && !previewBlocks.isEmpty())
			return;
		
		BlockHitResult blockHitResult = getStartHitResult();
		if(blockHitResult == null)
		{
			if(!WURST.getHax().freecamHack.isEnabled())
				previewBlocks.clear();
			return;
		}
		
		boolean airStart = blockHitResult.getType() == HitResult.Type.MISS;
		BlockPos hitResultPos = blockHitResult.getBlockPos();
		if(!airStart && !BlockUtils.canBeClicked(hitResultPos))
		{
			previewBlocks.clear();
			return;
		}
		
		BlockPos startPos = airStart ? hitResultPos
			: hitResultPos.relative(blockHitResult.getDirection());
		Direction direction = MC.player.getDirection();
		
		if(startPos.equals(previewStartPos) && direction == previewDirection
			&& !previewBlocks.isEmpty())
			return;
		
		previewStartPos = startPos;
		previewDirection = direction;
		previewBlocks = template.getBlocksToPlace(startPos, direction);
	}
	
	private void renderBlocks(PoseStack matrixStack,
		LinkedHashMap<BlockPos, Item> blocks, int solidColor)
	{
		if(blocks.isEmpty())
			return;
		
		List<BlockPos> blocksToDraw = blocks.keySet().stream()
			.filter(pos -> BlockUtils.getState(pos).canBeReplaced()).limit(1024)
			.toList();
		
		int black = 0x80000000;
		List<AABB> outlineBoxes =
			blocksToDraw.stream().map(pos -> BLOCK_BOX.move(pos)).toList();
		RenderUtils.drawOutlinedBoxes(matrixStack, outlineBoxes, black, true);
		
		Vec3 eyesPos = RotationUtils.getEyesPos();
		double rangeSq = range.getValueSq();
		List<AABB> solidBoxes = blocksToDraw.stream()
			.filter(pos -> pos.distToCenterSqr(eyesPos) <= rangeSq)
			.map(pos -> BLOCK_BOX.move(pos)).toList();
		RenderUtils.drawSolidBoxes(matrixStack, solidBoxes, solidColor, true);
	}
	
	private BlockPlacingParams getPlacingParams(BlockPos pos)
	{
		BlockPlacingParams params = BlockPlacer.getBlockPlacingParams(pos);
		if(params != null)
			return params;
		
		Vec3 hitVec = Vec3.atCenterOf(pos);
		double distanceSq = RotationUtils.getEyesPos().distanceToSqr(hitVec);
		boolean lineOfSight =
			BlockUtils.hasLineOfSight(RotationUtils.getEyesPos(), hitVec);
		
		return new BlockPlacingParams(pos, Direction.UP, hitVec, distanceSq,
			lineOfSight);
	}
	
	private boolean isStuck()
	{
		if(lastProgressMs <= 0)
			return false;
		
		return System.currentTimeMillis() - lastProgressMs > STUCK_TIMEOUT_MS;
	}
	
	private BlockHitResult getStartHitResult()
	{
		HitResult hitResult = MC.hitResult;
		if(hitResult instanceof BlockHitResult blockHitResult
			&& hitResult.getType() == HitResult.Type.BLOCK)
			return blockHitResult;
		
		HitResult airResult = MC.player.pick(range.getValue(), 0, false);
		if(airResult instanceof BlockHitResult airBlock
			&& airResult.getType() == HitResult.Type.MISS)
			return airBlock;
		
		return null;
	}
	
	private boolean isBlockPlaced(BlockPos pos, Item item)
	{
		BlockState state = BlockUtils.getState(pos);
		if(state.canBeReplaced())
			return false;
		
		if(!useSavedBlocks.isChecked() || item == Items.AIR)
			return true;
		
		if(!(item instanceof BlockItem blockItem))
			return true;
		
		return state.is(blockItem.getBlock());
	}
	
	private enum Status
	{
		NO_TEMPLATE,
		LOADING,
		IDLE,
		BUILDING;
	}
}
