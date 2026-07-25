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
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.LeftClickListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.nukers.CommonNukerSettings;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.util.BlockBreaker;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RotationUtils;

@SearchTags({"speed nuker", "FastNuker", "fast nuker"})
@DontSaveState
public final class SpeedNukerHack extends Hack implements UpdateListener
{
	private final SliderSetting range =
		new SliderSetting("Range", 5, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final CommonNukerSettings commonSettings =
		new CommonNukerSettings(true);
	
	private final SwingHandSetting swingHand = new SwingHandSetting(
		SwingHandSetting.genericMiningDescription(this), SwingHand.OFF);
	
	private final CheckboxSetting autoSwitchTool = new CheckboxSetting(
		"Auto switch tool",
		"Automatically switch to the best tool in your inventory for the current"
			+ " block even if the AutoTool hack is disabled.",
		false);
	
	private final CheckboxSetting onlyOnLeftClick = new CheckboxSetting(
		"Only activate on left click",
		"Only breaks blocks while the left mouse button is held down.", false);
	
	private final CheckboxSetting preserveTools = new CheckboxSetting(
		"Preserve Tools",
		"Stops using damageable hotbar items when they reach 1% durability,"
			+ " switches to another item above 1% from the entire inventory, and"
			+ " disables SpeedNuker when none remain. AntiBreak takes priority when"
			+ " it is enabled.",
		false);
	
	private final Set<Integer> preservedToolSlots = new HashSet<>();
	
	// Remember whether AutoTool was enabled before this hack enabled it
	private boolean prevAutoToolEnabled;
	
	public SpeedNukerHack()
	{
		super("SpeedNuker");
		setCategory(Category.BLOCKS);
		addSetting(range);
		commonSettings.getSettings().forEach(this::addSetting);
		addSetting(swingHand);
		addSetting(autoSwitchTool);
		addSetting(onlyOnLeftClick);
		addSetting(preserveTools);
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
		WURST.getHax().nukerHack.setEnabled(false);
		WURST.getHax().nukerLegitHack.setEnabled(false);
		WURST.getHax().tunnellerHack.setEnabled(false);
		WURST.getHax().veinMinerHack.setEnabled(false);
		
		// Auto-enable AutoTool if requested by per-hack setting
		preservedToolSlots.clear();
		prevAutoToolEnabled = WURST.getHax().autoToolHack.isEnabled();
		if(autoSwitchTool.isChecked() && !prevAutoToolEnabled)
		{
			WURST.getHax().autoToolHack.setEnabled(true);
		}
		
		EVENTS.add(LeftClickListener.class, commonSettings);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(LeftClickListener.class, commonSettings);
		EVENTS.remove(UpdateListener.class, this);
		
		commonSettings.reset();
		preservedToolSlots.clear();
		
		// Restore AutoTool previous state if we enabled it
		if(!prevAutoToolEnabled && WURST.getHax().autoToolHack.isEnabled())
		{
			WURST.getHax().autoToolHack.setEnabled(false);
		}
	}
	
	@Override
	public void onUpdate()
	{
		if(commonSettings.isIdModeWithAir())
			return;
		
		if(onlyOnLeftClick.isChecked() && !MC.options.keyAttack.isDown())
			return;
		
		boolean antiBreakEnabled = WURST.getHax().antiBreakHack.isEnabled();
		if(antiBreakEnabled && WURST.getHax().antiBreakHack
			.shouldReplace(MC.player.getMainHandItem()))
			return;
		
		boolean preserveToolsActive = preserveTools.isChecked()
			&& !MC.player.getAbilities().instabuild && !antiBreakEnabled;
		
		if(preserveToolsActive)
		{
			updatePreservedToolSlots();
			if(findAvailableToolSlot() == -1)
			{
				setEnabled(false);
				return;
			}
		}
		
		Vec3 eyesVec = RotationUtils.getEyesPos();
		BlockPos eyesBlock = BlockPos.containing(eyesVec);
		double rangeSq = range.getValueSq();
		int blockRange = range.getValueCeil();
		
		Stream<BlockPos> stream;
		if(commonSettings.isTunnelMode())
		{
			Direction direction = MC.player.getDirection();
			BlockPos start =
				BlockPos.containing(MC.player.position()).relative(direction);
			BlockPos end = start.relative(direction, blockRange - 1).above();
			stream = BlockUtils.getAllInBoxStream(start, end);
		}else
		{
			stream = BlockUtils.getAllInBoxStream(eyesBlock, blockRange);
			if(commonSettings.isSphereShape())
				stream = stream
					.filter(pos -> pos.distToCenterSqr(eyesVec) <= rangeSq);
		}
		
		stream = stream.filter(BlockUtils::canBeClicked)
			.filter(pos -> !BlockUtils.isUnbreakable(pos))
			.filter(commonSettings::shouldBreakBlock);
		
		ArrayList<BlockPos> blocks = stream
			.sorted(
				Comparator.comparingDouble(pos -> pos.distToCenterSqr(eyesVec)))
			.collect(Collectors.toCollection(ArrayList::new));
		
		if(blocks.isEmpty())
		{
			attackOneEntity(eyesVec, rangeSq);
			return;
		}
		
		if(preserveToolsActive && !preparePreservedTool(blocks))
			return;
			
		// Preserve Tools already selected the allowed tool. Otherwise, prefer
		// global AutoTool when enabled, followed by per-hack auto switch.
		if(!preserveToolsActive)
		{
			if(WURST.getHax().autoToolHack.isEnabled())
			{
				WURST.getHax().autoToolHack
					.equipIfEnabledFromInventory(blocks.get(0));
			}else if(autoSwitchTool.isChecked())
			{
				WURST.getHax().autoToolHack.equipBestToolFromInventory(
					blocks.get(0), true, true, 0, slot -> true);
			}
		}
		
		BlockBreaker.breakBlocksWithPacketSpam(blocks);
		swingHand.swing(InteractionHand.MAIN_HAND);
	}
	
	private boolean preparePreservedTool(ArrayList<BlockPos> blocks)
	{
		while(true)
		{
			updatePreservedToolSlots();
			if(findAvailableToolSlot() == -1)
			{
				setEnabled(false);
				return false;
			}
			
			int selectedSlot = MC.player.getInventory().getSelectedSlot();
			ItemStack selectedItem = MC.player.getMainHandItem();
			if(isAtPreserveThreshold(selectedItem))
			{
				String oldDescription = describeDurability(selectedItem);
				preservedToolSlots.add(selectedSlot);
				boolean switched = WURST.getHax().autoToolHack
					.equipBestToolFromInventory(blocks.get(0), true, false, 0,
						this::isAllowedPreserveSlot);
				ItemStack replacement = MC.player.getMainHandItem();
				if(!switched || !isAvailableTool(replacement))
				{
					if(WURST.getHax().antiBreakHack.isEnabled())
						return false;
					
					ChatUtils.warning("Preserve Tools: " + oldDescription
						+ "; unable to swap to another usable tool. SpeedNuker"
						+ " disabled.");
					setEnabled(false);
					return false;
				}
				
				ChatUtils.message("Preserve Tools: swapped " + oldDescription
					+ " for " + describeDurability(replacement) + ".");
				continue;
			}
			
			WURST.getHax().autoToolHack.equipBestToolFromInventory(
				blocks.get(0), true, false, 0, this::isAllowedPreserveSlot);
			selectedItem = MC.player.getMainHandItem();
			if(!isAvailableTool(selectedItem))
			{
				if(WURST.getHax().antiBreakHack.isEnabled())
					return false;
				
				ChatUtils
					.warning("Preserve Tools: unable to equip a usable tool."
						+ " SpeedNuker disabled.");
				setEnabled(false);
				return false;
			}
			
			int maxBlocks = getRemainingUses(selectedItem)
				- getPreserveThreshold(selectedItem);
			if(maxBlocks <= 0)
				continue;
			
			if(maxBlocks < blocks.size())
				blocks.subList(maxBlocks, blocks.size()).clear();
			
			return true;
		}
	}
	
	private void updatePreservedToolSlots()
	{
		for(int slot = 0; slot < 9; slot++)
		{
			ItemStack stack = MC.player.getInventory().getItem(slot);
			if(isAtPreserveThreshold(stack))
				preservedToolSlots.add(slot);
			else
				preservedToolSlots.remove(slot);
		}
	}
	
	private int findAvailableToolSlot()
	{
		for(int slot = 0; slot < 36; slot++)
		{
			if(preservedToolSlots.contains(slot))
				continue;
			
			if(isAvailableTool(MC.player.getInventory().getItem(slot)))
				return slot;
		}
		
		return -1;
	}
	
	private boolean isAllowedPreserveSlot(int slot)
	{
		return !preservedToolSlots.contains(slot)
			&& !isAtPreserveThreshold(MC.player.getInventory().getItem(slot));
	}
	
	private boolean isAvailableTool(ItemStack stack)
	{
		return !stack.isEmpty() && stack.isDamageableItem()
			&& !isAtPreserveThreshold(stack);
	}
	
	private boolean isAtPreserveThreshold(ItemStack stack)
	{
		if(!stack.isDamageableItem())
			return false;
		
		return getRemainingUses(stack) <= getPreserveThreshold(stack);
	}
	
	private int getRemainingUses(ItemStack stack)
	{
		return stack.getMaxDamage() - stack.getDamageValue();
	}
	
	private int getPreserveThreshold(ItemStack stack)
	{
		return Math.max(1, (stack.getMaxDamage() + 99) / 100);
	}
	
	private String describeDurability(ItemStack stack)
	{
		return stack.getHoverName().getString() + " at "
			+ getDurabilityPercent(stack) + "% durability ("
			+ getRemainingUses(stack) + "/" + stack.getMaxDamage() + " uses)";
	}
	
	private int getDurabilityPercent(ItemStack stack)
	{
		if(!stack.isDamageableItem() || stack.getMaxDamage() <= 0)
			return 100;
		
		return Math.max(0, Math.min(100,
			getRemainingUses(stack) * 100 / stack.getMaxDamage()));
	}
	
	private boolean attackOneEntity(Vec3 eyesVec, double rangeSq)
	{
		if(MC.level == null)
			return false;
		
		Entity target = MC.level
			.getEntities(MC.player,
				MC.player.getBoundingBox().inflate(range.getValue()),
				commonSettings::shouldAttackEntity)
			.stream().filter(e -> e.distanceToSqr(eyesVec) <= rangeSq)
			.min(Comparator.comparingDouble(e -> e.distanceToSqr(eyesVec)))
			.orElse(null);
		
		if(target == null)
			return false;
		
		WURST.getRotationFaker()
			.faceVectorPacket(target.getBoundingBox().getCenter());
		MC.gameMode.attack(MC.player, target);
		swingHand.swing(InteractionHand.MAIN_HAND);
		return true;
	}
}
