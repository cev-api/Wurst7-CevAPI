/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.Optional;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder.Reference;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.ItemUtils;

@SearchTags({"elytra info", "elytra hud", "flight info", "glide info"})
public final class ElytraInfoHack extends Hack implements UpdateListener
{
	private final CheckboxSetting showYaw =
		new CheckboxSetting("Show yaw", true);
	private final CheckboxSetting showPitch =
		new CheckboxSetting("Show pitch", true);
	private final CheckboxSetting showAltitude =
		new CheckboxSetting("Show altitude", true);
	private final CheckboxSetting showSpeed =
		new CheckboxSetting("Show speed", true);
	private final CheckboxSetting showDirection =
		new CheckboxSetting("Show direction", true);
	private final CheckboxSetting showDurability =
		new CheckboxSetting("Show durability", true);
	private final CheckboxSetting durabilityGradient =
		new CheckboxSetting("Durability gradient",
			"Changes durability text from green to red.", true);
	
	private final ColorSetting textColor =
		new ColorSetting("Text color", new Color(0xFF, 0xFF, 0xFF));
	private final SliderSetting textOpacity =
		new SliderSetting("Text opacity", 255, 0, 255, 1, ValueDisplay.INTEGER);
	private final SliderSetting fontScale = new SliderSetting("Font size", 1.0,
		0.5, 3.0, 0.05, ValueDisplay.DECIMAL);
	private final CheckboxSetting hidePrefixes =
		new CheckboxSetting("Hide prefixes",
			"Shows values only (without labels like Yaw/Speed).", false);
	
	private final CheckboxSetting backgroundEnabled =
		new CheckboxSetting("Background", true);
	private final ColorSetting backgroundColor =
		new ColorSetting("Background color", new Color(0x00, 0x00, 0x00));
	private final SliderSetting backgroundOpacity = new SliderSetting(
		"Background opacity", 120, 0, 255, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting hudOffsetX = new SliderSetting("HUD X offset",
		0, -2000, 2000, 1, ValueDisplay.INTEGER);
	private final SliderSetting hudOffsetY = new SliderSetting("HUD Y offset",
		18, -2000, 2000, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting autoSwitchOnLanding = new CheckboxSetting(
		"Auto switch on landing",
		"When you stop gliding, automatically swaps from Elytra to your best chestplate.",
		false);
	private final CheckboxSetting autoSwapLowDurability = new CheckboxSetting(
		"Auto swap low durability",
		"Automatically swaps to a better Elytra from your inventory when the equipped Elytra drops to 5% durability or lower.",
		false);
	
	private boolean wasFallFlying;
	
	public ElytraInfoHack()
	{
		super("ElytraInfo");
		setCategory(Category.RENDER);
		addPossibleKeybind(".elytrainfo swap",
			"Swap Elytra and best chestplate (if not cursed with Binding)");
		addSetting(showYaw);
		addSetting(showPitch);
		addSetting(showAltitude);
		addSetting(showSpeed);
		addSetting(showDirection);
		addSetting(showDurability);
		addSetting(durabilityGradient);
		addSetting(textColor);
		addSetting(textOpacity);
		addSetting(fontScale);
		addSetting(hidePrefixes);
		addSetting(backgroundEnabled);
		addSetting(backgroundColor);
		addSetting(backgroundOpacity);
		addSetting(hudOffsetX);
		addSetting(hudOffsetY);
		addSetting(autoSwitchOnLanding);
		addSetting(autoSwapLowDurability);
	}
	
	@Override
	protected void onEnable()
	{
		wasFallFlying = MC.player != null && MC.player.isFallFlying();
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
		if(MC.player == null || MC.level == null)
			return;
		
		if(autoSwapLowDurability.isChecked())
			autoSwapElytraIfLowDurability();
		
		boolean nowFallFlying = MC.player.isFallFlying();
		if(autoSwitchOnLanding.isChecked() && wasFallFlying && !nowFallFlying
			&& MC.player.onGround())
			swapToBestChestplate();
		
		wasFallFlying = nowFallFlying;
	}
	
	public boolean showYaw()
	{
		return showYaw.isChecked();
	}
	
	public boolean showAltitude()
	{
		return showAltitude.isChecked();
	}
	
	public boolean showPitch()
	{
		return showPitch.isChecked();
	}
	
	public boolean showSpeed()
	{
		return showSpeed.isChecked();
	}
	
	public boolean showDirection()
	{
		return showDirection.isChecked();
	}
	
	public boolean showDurability()
	{
		return showDurability.isChecked();
	}
	
	public boolean useDurabilityGradient()
	{
		return durabilityGradient.isChecked();
	}
	
	public int getTextColorI()
	{
		return textColor.getColorI();
	}
	
	public int getTextOpacity()
	{
		return textOpacity.getValueI();
	}
	
	public double getFontScale()
	{
		return fontScale.getValue();
	}
	
	public boolean hidePrefixes()
	{
		return hidePrefixes.isChecked();
	}
	
	public boolean hasBackground()
	{
		return backgroundEnabled.isChecked();
	}
	
	public int getBackgroundColorI()
	{
		return backgroundColor.getColorI();
	}
	
	public int getBackgroundOpacity()
	{
		return backgroundOpacity.getValueI();
	}
	
	public int getHudOffsetX()
	{
		return hudOffsetX.getValueI();
	}
	
	public int getHudOffsetY()
	{
		return hudOffsetY.getValueI();
	}
	
	public int getHudOffsetMinX()
	{
		return (int)hudOffsetX.getMinimum();
	}
	
	public int getHudOffsetMaxX()
	{
		return (int)hudOffsetX.getMaximum();
	}
	
	public int getHudOffsetMinY()
	{
		return (int)hudOffsetY.getMinimum();
	}
	
	public int getHudOffsetMaxY()
	{
		return (int)hudOffsetY.getMaximum();
	}
	
	public void setHudOffsets(int x, int y)
	{
		hudOffsetX.setValue(x);
		hudOffsetY.setValue(y);
	}
	
	public boolean autoSwitchOnLanding()
	{
		return autoSwitchOnLanding.isChecked();
	}
	
	public boolean autoSwapLowDurability()
	{
		return autoSwapLowDurability.isChecked();
	}
	
	public void swapChestItemFromKeybind()
	{
		if(MC.player == null || MC.level == null)
			return;
		
		if(MC.screen instanceof AbstractContainerScreen
			&& !(MC.screen instanceof InventoryScreen))
			return;
		
		ItemStack chest = MC.player.getItemBySlot(EquipmentSlot.CHEST);
		if(isBindingCursed(chest))
			return;
		
		if(chest.getItem() == net.minecraft.world.item.Items.ELYTRA)
			swapToBestChestplate();
		else
			swapToBestElytra();
	}
	
	private void swapToBestChestplate()
	{
		LocalPlayer player = MC.player;
		if(player == null)
			return;
		
		Inventory inv = player.getInventory();
		int bestSlot = -1;
		double bestScore = Double.NEGATIVE_INFINITY;
		for(int slot = 0; slot < 36; slot++)
		{
			ItemStack stack = inv.getItem(slot);
			if(stack.isEmpty() || isBindingCursed(stack))
				continue;
			
			Item item = stack.getItem();
			EquipmentSlot armorSlot = ItemUtils.getArmorSlot(item);
			if(armorSlot != EquipmentSlot.CHEST
				|| item == net.minecraft.world.item.Items.ELYTRA)
				continue;
			
			double score = getChestplateScore(stack);
			if(score > bestScore)
			{
				bestScore = score;
				bestSlot = slot;
			}
		}
		
		if(bestSlot == -1)
			return;
		
		swapWithChestSlot(bestSlot);
	}
	
	private void swapToBestElytra()
	{
		LocalPlayer player = MC.player;
		if(player == null)
			return;
		
		Inventory inv = player.getInventory();
		int bestSlot = -1;
		double bestScore = Double.NEGATIVE_INFINITY;
		for(int slot = 0; slot < 36; slot++)
		{
			ItemStack stack = inv.getItem(slot);
			if(stack.isEmpty()
				|| stack.getItem() != net.minecraft.world.item.Items.ELYTRA
				|| isBindingCursed(stack))
				continue;
			
			int max = stack.getMaxDamage();
			int remaining = max - stack.getDamageValue();
			double score = max > 0 ? remaining / (double)max : 0;
			if(score > bestScore)
			{
				bestScore = score;
				bestSlot = slot;
			}
		}
		
		if(bestSlot == -1)
			return;
		
		swapWithChestSlot(bestSlot);
	}
	
	private void autoSwapElytraIfLowDurability()
	{
		ItemStack chest = MC.player.getItemBySlot(EquipmentSlot.CHEST);
		if(chest.isEmpty()
			|| chest.getItem() != net.minecraft.world.item.Items.ELYTRA
			|| isBindingCursed(chest))
			return;
		
		int max = chest.getMaxDamage();
		if(max <= 0)
			return;
		
		int remaining = max - chest.getDamageValue();
		if(remaining * 100 > max * 5)
			return;
		
		int slot = findBestReplacementElytraSlot(remaining, max);
		if(slot != -1)
			swapWithChestSlot(slot);
	}
	
	private int findBestReplacementElytraSlot(int currentRemaining,
		int currentMax)
	{
		LocalPlayer player = MC.player;
		if(player == null)
			return -1;
		
		Inventory inv = player.getInventory();
		int bestSlot = -1;
		double bestFraction = -1;
		int bestRemaining = -1;
		
		for(int slot = 0; slot < 36; slot++)
		{
			ItemStack stack = inv.getItem(slot);
			if(stack.isEmpty()
				|| stack.getItem() != net.minecraft.world.item.Items.ELYTRA
				|| isBindingCursed(stack))
				continue;
			
			int max = stack.getMaxDamage();
			if(max <= 0)
				continue;
			
			int remaining = max - stack.getDamageValue();
			double fraction = remaining / (double)max;
			if(fraction > bestFraction
				|| (fraction == bestFraction && remaining > bestRemaining))
			{
				bestFraction = fraction;
				bestRemaining = remaining;
				bestSlot = slot;
			}
		}
		
		if(bestSlot == -1)
			return -1;
		
		double currentFraction =
			currentMax > 0 ? currentRemaining / (double)currentMax : 0;
		if(bestFraction <= currentFraction && bestRemaining <= currentRemaining)
			return -1;
		
		return bestSlot;
	}
	
	private void swapWithChestSlot(int inventorySlot)
	{
		if(MC.player == null || inventorySlot < 0 || inventorySlot >= 36)
			return;
		if(!MC.player.inventoryMenu.getCarried().isEmpty())
			return;
		
		int source = InventoryUtils.toNetworkSlot(inventorySlot);
		final int chestArmorSlot = 6;
		
		IMC.getInteractionManager().windowClick_PICKUP(source);
		IMC.getInteractionManager().windowClick_PICKUP(chestArmorSlot);
		IMC.getInteractionManager().windowClick_PICKUP(source);
	}
	
	private double getChestplateScore(ItemStack stack)
	{
		Item item = stack.getItem();
		int armorPoints = (int)ItemUtils.getArmorPoints(item);
		int armorToughness = (int)ItemUtils.getToughness(item);
		int protection = getProtectionLevel(stack);
		int max = stack.getMaxDamage();
		int remaining = max - stack.getDamageValue();
		double durabilityFraction = max > 0 ? remaining / (double)max : 1.0;
		return armorPoints * 5 + armorToughness * 2 + protection * 3
			+ durabilityFraction;
	}
	
	private int getProtectionLevel(ItemStack stack)
	{
		if(MC.level == null)
			return 0;
		
		RegistryAccess registryAccess = MC.level.registryAccess();
		Registry<Enchantment> registry =
			registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
		Optional<Reference<Enchantment>> protection =
			registry.get(Enchantments.PROTECTION);
		return protection
			.map(e -> EnchantmentHelper.getItemEnchantmentLevel(e, stack))
			.orElse(0);
	}
	
	private boolean isBindingCursed(ItemStack stack)
	{
		if(stack == null || stack.isEmpty() || MC.level == null)
			return false;
		
		RegistryAccess registryAccess = MC.level.registryAccess();
		Registry<Enchantment> registry =
			registryAccess.lookupOrThrow(Registries.ENCHANTMENT);
		Optional<Reference<Enchantment>> binding =
			registry.get(Enchantments.BINDING_CURSE);
		return binding
			.map(e -> EnchantmentHelper.getItemEnchantmentLevel(e, stack) > 0)
			.orElse(false);
	}
}
