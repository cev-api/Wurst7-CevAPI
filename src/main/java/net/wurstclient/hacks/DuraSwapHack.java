/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.BlockBreakingProgressListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"dura swap", "durability swap", "durability saver", "tool saver",
	"mine swap", "mining swap", "efficiency transfer", "item saver",
	"attribute swap"})
public final class DuraSwapHack extends Hack
	implements BlockBreakingProgressListener, UpdateListener
{
	private static final float SERVER_THRESHOLD = 0.7F;
	private static final int MAX_SAMPLES = 8;
	
	private final SliderSetting safetyMargin = new SliderSetting(
		"Safety margin",
		"The server re-checks your mining progress against the item you are"
			+ " holding when the block breaks, and only accepts the break if"
			+ " that progress is at least 0.7.\n\n"
			+ "This setting multiplies the 0.7 requirement, so higher values"
			+ " only swap to items that clear the check by a wider margin."
			+ " Lower values swap more often, but a laggy server may reject"
			+ " the break and make you mine the block again.\n\n"
			+ "Doesn't apply to blocks that break instantly, since the server"
			+ " checks those without any timing involved.",
		1.4, 1, 3, 0.05, ValueDisplay.DECIMAL.withSuffix("x"));
	
	private final SliderSetting onlyBelow = new SliderSetting("Only below",
		"Only protects the held tool once its remaining durability has"
			+ " dropped to this percentage or lower.",
		100, 1, 100, 1,
		ValueDisplay.INTEGER.withSuffix("%").withLabel(100, "always"));
	
	private final SliderSetting minDurability =
		new SliderSetting("Min durability",
			"Won't sacrifice an item that has this little durability left, so"
				+ " your sacrificial tools never break in your hand.",
			5, 1, 100, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting preferFree = new CheckboxSetting(
		"Prefer free items",
		"Prefers items that lose no durability at all, like blocks or an empty"
			+ " slot. These only pass the server's check on blocks you were"
			+ " already mining slowly, so a cheap tool is used otherwise.",
		true);
	
	private final CheckboxSetting keepDrops = new CheckboxSetting("Keep drops",
		"Only swaps to items that produce the same drops, since the server"
			+ " decides drops from the item you hold when the block breaks."
			+ " Prevents losing Silk Touch, Fortune, and ore drops.",
		true);
	
	private BlockPos trackedPos;
	private int ticksBreaking;
	private int restoreSlot = -1;
	
	public DuraSwapHack()
	{
		super("DuraSwap");
		setCategory(Category.BLOCKS);
		addSetting(safetyMargin);
		addSetting(onlyBelow);
		addSetting(minDurability);
		addSetting(preferFree);
		addSetting(keepDrops);
	}
	
	@Override
	protected void onEnable()
	{
		trackedPos = null;
		ticksBreaking = 0;
		restoreSlot = -1;
		
		EVENTS.add(BlockBreakingProgressListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(BlockBreakingProgressListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		
		restoreSelectedSlot();
		trackedPos = null;
		ticksBreaking = 0;
	}
	
	@Override
	public void onBlockBreakingProgress(BlockBreakingProgressEvent event)
	{
		BlockPos pos = event.getBlockPos();
		
		if(!pos.equals(trackedPos))
		{
			trackedPos = pos;
			ticksBreaking = 0;
		}
		
		ticksBreaking++;
	}
	
	@Override
	public void onUpdate()
	{
		restoreSelectedSlot();
		
		if(MC.gameMode != null && !MC.gameMode.isDestroying())
		{
			trackedPos = null;
			ticksBreaking = 0;
		}
	}
	
	public void onBeforeBreakPacket(BlockPos pos)
	{
		BlockState state = getBreakableState(pos);
		if(state == null)
			return;
		
		float required = SERVER_THRESHOLD * (float)safetyMargin.getValue();
		swapTo(findSacrificeSlot(List.of(pos), new float[]{required},
			ticksBreaking + 1));
	}
	
	public void onBeforeInstaBreakPacket(BlockPos pos)
	{
		BlockState state = getBreakableState(pos);
		if(state == null)
			return;
		
		if(state.getDestroyProgress(MC.player, MC.player.level(), pos) < 1)
			return;
		
		swapTo(findSacrificeSlot(List.of(pos), new float[]{1}, 1));
	}
	
	public void onBeforePacketBreak(BlockPos pos)
	{
		if(getBreakableState(pos) == null)
			return;
		
		swapTo(findSacrificeSlot(List.of(pos),
			new float[]{getHeldProgress(pos)}, 1));
	}
	
	public void onBeforePacketBreak(Iterable<BlockPos> blocks)
	{
		if(!isEnabled() || restoreSlot != -1 || MC.player == null
			|| MC.level == null || MC.player.getAbilities().instabuild)
			return;
		
		ArrayList<BlockPos> samples = new ArrayList<>();
		HashSet<BlockState> distinctStates = new HashSet<>();
		
		for(BlockPos pos : blocks)
		{
			BlockState state = MC.level.getBlockState(pos);
			if(state.isAir() || !distinctStates.add(state))
				continue;
			
			samples.add(pos.immutable());
			if(samples.size() >= MAX_SAMPLES)
				break;
		}
		
		if(samples.isEmpty())
			return;
		
		float[] required = new float[samples.size()];
		for(int i = 0; i < required.length; i++)
			required[i] = getHeldProgress(samples.get(i));
		
		swapTo(findSacrificeSlot(samples, required, 1));
	}
	
	public void onAfterBreakPacket()
	{
		restoreSelectedSlot();
	}
	
	private float getHeldProgress(BlockPos pos)
	{
		return MC.level.getBlockState(pos).getDestroyProgress(MC.player,
			MC.player.level(), pos);
	}
	
	private BlockState getBreakableState(BlockPos pos)
	{
		if(!isEnabled() || restoreSlot != -1)
			return null;
		
		if(MC.player == null || MC.level == null
			|| MC.player.getAbilities().instabuild)
			return null;
		
		BlockState state = MC.level.getBlockState(pos);
		return state.isAir() ? null : state;
	}
	
	private void swapTo(int slot)
	{
		if(slot == -1)
			return;
		
		Inventory inventory = MC.player.getInventory();
		restoreSlot = inventory.getSelectedSlot();
		inventory.setSelectedSlot(slot);
		MC.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
	}
	
	private void restoreSelectedSlot()
	{
		if(restoreSlot == -1)
			return;
		
		int slot = restoreSlot;
		restoreSlot = -1;
		
		if(MC.player == null)
			return;
		
		MC.player.getInventory().setSelectedSlot(slot);
		MC.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
	}
	
	private int findSacrificeSlot(List<BlockPos> samples, float[] required,
		int multiplier)
	{
		Inventory inventory = MC.player.getInventory();
		int selected = inventory.getSelectedSlot();
		ItemStack held = inventory.getSelectedItem();
		
		if(!isWornEnough(held) || !takesDamageOnAny(held, samples))
			return -1;
		
		int bestSlot = -1;
		int bestScore = Integer.MIN_VALUE;
		
		for(int slot = 0; slot < 9; slot++)
		{
			if(slot == selected)
				continue;
			
			ItemStack stack = inventory.getItem(slot);
			boolean free = !takesDamageOnAny(stack, samples);
			
			if(!free && !survivesHit(stack))
				continue;
			
			if(keepDrops.isChecked() && !keepsSameDrops(held, stack, samples))
				continue;
			
			if(!passesServerCheck(slot, samples, required, multiplier))
				continue;
			
			int score = free && preferFree.isChecked() ? 1000000 : 0;
			if(stack.isDamageableItem())
				score -= stack.getMaxDamage();
			
			if(score > bestScore)
			{
				bestScore = score;
				bestSlot = slot;
			}
		}
		
		return bestSlot;
	}
	
	private boolean passesServerCheck(int slot, List<BlockPos> samples,
		float[] required, int multiplier)
	{
		Inventory inventory = MC.player.getInventory();
		int previous = inventory.getSelectedSlot();
		inventory.setSelectedSlot(slot);
		
		try
		{
			for(int i = 0; i < samples.size(); i++)
				if(getHeldProgress(samples.get(i)) * multiplier < required[i])
					return false;
				
			return true;
			
		}finally
		{
			inventory.setSelectedSlot(previous);
		}
	}
	
	private boolean takesDamageOnAny(ItemStack stack, List<BlockPos> samples)
	{
		for(BlockPos pos : samples)
			if(wouldTakeDamage(stack, MC.level.getBlockState(pos), pos))
				return true;
			
		return false;
	}
	
	private boolean wouldTakeDamage(ItemStack stack, BlockState state,
		BlockPos pos)
	{
		if(stack.isEmpty() || !stack.isDamageableItem())
			return false;
		
		Tool tool = stack.get(DataComponents.TOOL);
		if(tool == null || tool.damagePerBlock() <= 0)
			return false;
		
		return state.getDestroySpeed(MC.level, pos) != 0;
	}
	
	private boolean survivesHit(ItemStack stack)
	{
		return stack.getMaxDamage() - stack.getDamageValue() > minDurability
			.getValueI();
	}
	
	private boolean isWornEnough(ItemStack held)
	{
		int limit = onlyBelow.getValueI();
		if(limit >= 100)
			return true;
		
		int max = held.getMaxDamage();
		if(max <= 0)
			return true;
		
		return (max - held.getDamageValue()) * 100 <= limit * max;
	}
	
	private boolean keepsSameDrops(ItemStack held, ItemStack candidate,
		List<BlockPos> samples)
	{
		for(BlockPos pos : samples)
		{
			BlockState state = MC.level.getBlockState(pos);
			if(state.requiresCorrectToolForDrops()
				&& !candidate.isCorrectToolForDrops(state))
				return false;
		}
		
		return getEnchantLevel(candidate,
			Enchantments.SILK_TOUCH) >= getEnchantLevel(held,
				Enchantments.SILK_TOUCH)
			&& getEnchantLevel(candidate,
				Enchantments.FORTUNE) >= getEnchantLevel(held,
					Enchantments.FORTUNE);
	}
	
	private int getEnchantLevel(ItemStack stack, ResourceKey<Enchantment> key)
	{
		if(MC.level == null)
			return 0;
		
		return MC.level.registryAccess().lookup(Registries.ENCHANTMENT)
			.flatMap(reg -> reg.get(key)).map(holder -> EnchantmentHelper
				.getItemEnchantmentLevel(holder, stack))
			.orElse(0);
	}
}
