/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.tags.ItemTags;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.ItemUtils;

@SearchTags({"anti break", "durability saver", "item saver"})
public final class AntiBreakHack extends Hack implements UpdateListener
{
	private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD,
		EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
	
	private final SliderSetting threshold = new SliderSetting(
		"Durability threshold",
		"Items at or below this percentage of their durability will be replaced.",
		1, 1, 100, 1, ValueDisplay.INTEGER.withSuffix("%"));
	
	private final Set<String> warnedFamilies = new HashSet<>();
	private int swapTimer;
	
	public AntiBreakHack()
	{
		super("AntiBreak");
		setCategory(Category.ITEMS);
		addSetting(threshold);
	}
	
	@Override
	protected void onEnable()
	{
		warnedFamilies.clear();
		swapTimer = 0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		warnedFamilies.clear();
		swapTimer = 0;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		if(swapTimer > 0)
		{
			swapTimer--;
			return;
		}
		
		if(MC.gui.screen() instanceof AbstractContainerScreen
			&& !(MC.gui.screen() instanceof InventoryScreen))
			return;
		
		for(EquipmentSlot slot : ARMOR_SLOTS)
			if(handleEquipmentSlot(slot))
				return;
			
		if(handleEquipmentSlot(EquipmentSlot.OFFHAND))
			return;
		
		handleEquipmentSlot(EquipmentSlot.MAINHAND);
	}
	
	private boolean handleEquipmentSlot(EquipmentSlot slot)
	{
		ItemStack current = MC.player.getItemBySlot(slot);
		if(!needsReplacement(current))
			return false;
		
		String family = getFamily(current);
		int replacement = findBestReplacement(current, family, slot);
		if(replacement == -1)
		{
			String currentDescription = describeDurability(current);
			warnNoReplacement(family, current);
			boolean moved = moveToEmptySlot(slot);
			if(moved)
				ChatUtils.message("Moved " + currentDescription
					+ " out of active use because no replacement was available.");
			return moved;
		}
		
		ItemStack replacementStack =
			MC.player.getInventory().getItem(replacement);
		String currentDescription = describeDurability(current);
		String replacementDescription = describeDurability(replacementStack);
		if(!swapWithEquipmentSlot(replacement, slot))
			return false;
		
		warnedFamilies.remove(family);
		ChatUtils.message("Swapped out " + currentDescription + " for "
			+ replacementDescription + ".");
		return true;
	}
	
	private int findBestReplacement(ItemStack current, String family,
		EquipmentSlot target)
	{
		Inventory inventory = MC.player.getInventory();
		int bestSlot = -1;
		
		for(int slot = 0; slot < 36; slot++)
		{
			if(target == EquipmentSlot.MAINHAND
				&& slot == inventory.getSelectedSlot())
				continue;
			
			ItemStack candidate = inventory.getItem(slot);
			if(!isReplacementCandidate(candidate, current, family, target))
				continue;
			
			if(bestSlot == -1
				|| isBetter(candidate, inventory.getItem(bestSlot)))
				bestSlot = slot;
		}
		
		return bestSlot;
	}
	
	private boolean isReplacementCandidate(ItemStack candidate,
		ItemStack current, String family, EquipmentSlot target)
	{
		if(candidate.isEmpty() || !candidate.isDamageableItem()
			|| needsReplacement(candidate))
			return false;
		
		if(!family.equals(getFamily(candidate)))
			return false;
		
		if((target == EquipmentSlot.MAINHAND || target == EquipmentSlot.OFFHAND)
			&& (family.startsWith("armor:") || family.equals("elytra")))
			return false;
		
		if(target == EquipmentSlot.HEAD || target == EquipmentSlot.CHEST
			|| target == EquipmentSlot.LEGS || target == EquipmentSlot.FEET)
			return ItemUtils.getArmorSlot(candidate.getItem()) == target;
		
		return candidate != current;
	}
	
	private boolean isBetter(ItemStack candidate, ItemStack currentBest)
	{
		int enchantments = getEnchantmentScore(candidate);
		int bestEnchantments = getEnchantmentScore(currentBest);
		if(enchantments != bestEnchantments)
			return enchantments > bestEnchantments;
		
		int enchantmentCount = getEnchantmentCount(candidate);
		int bestEnchantmentCount = getEnchantmentCount(currentBest);
		if(enchantmentCount != bestEnchantmentCount)
			return enchantmentCount > bestEnchantmentCount;
		
		double durability = getDurabilityFraction(candidate);
		double bestDurability = getDurabilityFraction(currentBest);
		if(Double.compare(durability, bestDurability) != 0)
			return durability > bestDurability;
		
		return getRemainingUses(candidate) > getRemainingUses(currentBest);
	}
	
	private boolean swapWithEquipmentSlot(int inventorySlot,
		EquipmentSlot equipmentSlot)
	{
		int targetSlot = getNetworkSlot(equipmentSlot);
		int sourceSlot = InventoryUtils.toNetworkSlot(inventorySlot);
		if(sourceSlot == targetSlot
			|| !MC.player.inventoryMenu.getCarried().isEmpty())
			return false;
		
		IMC.getInteractionManager().windowClick_PICKUP(sourceSlot);
		IMC.getInteractionManager().windowClick_PICKUP(targetSlot);
		IMC.getInteractionManager().windowClick_PICKUP(sourceSlot);
		swapTimer = 2;
		return true;
	}
	
	private boolean moveToEmptySlot(EquipmentSlot equipmentSlot)
	{
		Inventory inventory = MC.player.getInventory();
		int emptySlot = -1;
		for(int slot = 0; slot < 36; slot++)
		{
			if(inventory.getItem(slot).isEmpty())
			{
				emptySlot = slot;
				break;
			}
		}
		
		if(emptySlot == -1)
		{
			if(equipmentSlot != EquipmentSlot.MAINHAND)
				return false;
			
			int selected = inventory.getSelectedSlot();
			for(int slot = 0; slot < 9; slot++)
			{
				if(slot == selected)
					continue;
				
				ItemStack stack = inventory.getItem(slot);
				if(stack.isEmpty() || !stack.isDamageableItem())
				{
					inventory.setSelectedSlot(slot);
					return true;
				}
			}
			
			return false;
		}
		
		return swapWithEquipmentSlot(emptySlot, equipmentSlot);
	}
	
	private int getNetworkSlot(EquipmentSlot slot)
	{
		if(slot == EquipmentSlot.MAINHAND)
			return InventoryUtils
				.toNetworkSlot(MC.player.getInventory().getSelectedSlot());
		
		if(slot == EquipmentSlot.OFFHAND)
			return InventoryUtils.toNetworkSlot(40);
		
		return 8 - slot.getIndex();
	}
	
	private boolean needsReplacement(ItemStack stack)
	{
		if(stack.isEmpty() || !stack.isDamageableItem()
			|| stack.getMaxDamage() <= 0)
			return false;
		
		return (long)getRemainingUses(stack) * 100 <= (long)stack.getMaxDamage()
			* threshold.getValueI();
	}
	
	public boolean shouldReplace(ItemStack stack)
	{
		return needsReplacement(stack);
	}
	
	private int getRemainingUses(ItemStack stack)
	{
		return stack.getMaxDamage() - stack.getDamageValue();
	}
	
	private double getDurabilityFraction(ItemStack stack)
	{
		return getRemainingUses(stack)
			/ (double)Math.max(1, stack.getMaxDamage());
	}
	
	private int getEnchantmentScore(ItemStack stack)
	{
		return EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()
			.stream().mapToInt(entry -> entry.getIntValue()).sum();
	}
	
	private int getEnchantmentCount(ItemStack stack)
	{
		return EnchantmentHelper.getEnchantmentsForCrafting(stack).size();
	}
	
	private String getFamily(ItemStack stack)
	{
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if(path.equals("elytra"))
			return "elytra";
		
		EquipmentSlot armorSlot = ItemUtils.getArmorSlot(stack.getItem());
		if(armorSlot != null)
			return "armor:" + armorSlot.name().toLowerCase();
		
		if(stack.is(ItemTags.SWORDS))
			return "sword";
		if(stack.is(ItemTags.AXES))
			return "axe";
		if(stack.is(ItemTags.PICKAXES))
			return "pickaxe";
		if(stack.is(ItemTags.SHOVELS))
			return "shovel";
		if(stack.is(ItemTags.HOES))
			return "hoe";
		
		if(path.equals("bow") || path.equals("crossbow")
			|| path.equals("trident") || path.equals("mace")
			|| path.equals("shield") || path.equals("shears")
			|| path.equals("fishing_rod") || path.equals("flint_and_steel")
			|| path.equals("brush") || path.endsWith("_on_a_stick"))
			return path;
		
		return "item:" + path;
	}
	
	private String getFamilyLabel(String family)
	{
		return switch(family)
		{
			case "elytra" -> "Elytra";
			case "sword" -> "sword";
			case "axe" -> "axe";
			case "pickaxe" -> "pickaxe";
			case "shovel" -> "shovel";
			case "hoe" -> "hoe";
			case "bow" -> "bow";
			case "crossbow" -> "crossbow";
			case "trident" -> "trident";
			case "mace" -> "mace";
			case "shield" -> "shield";
			default -> family.startsWith("armor:")
				? switch(family.substring("armor:".length()))
				{
					case "head" -> "helmet";
					case "chest" -> "chest armor";
					case "legs" -> "leggings";
					case "feet" -> "boots";
					default -> "armor";
				}
				: family.startsWith("item:")
					? family.substring("item:".length()).replace('_', ' ')
					: family.replace('_', ' ');
		};
	}
	
	private void warnNoReplacement(String family, ItemStack current)
	{
		if(!warnedFamilies.add(family))
			return;
		
		String label = getFamilyLabel(family);
		ChatUtils.warning("No " + label + " replacement available for "
			+ describeDurability(current) + ". Turn off AntiBreak to use "
			+ label + " to breaking point.");
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
}
