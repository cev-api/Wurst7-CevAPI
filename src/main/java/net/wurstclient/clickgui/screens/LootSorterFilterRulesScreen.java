/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.clickgui.screens;

import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.wurstclient.hacks.lootsorter.ItemFilterModifiers;

/**
 * Optional, clearly labelled narrowing rules for a selected destination filter.
 */
public final class LootSorterFilterRulesScreen extends Screen
{
	private final Screen previous;
	private final Consumer<ItemFilterModifiers> save;
	private Boolean enchanted;
	private Boolean damaged;
	private Boolean customNamed;
	private Boolean curses;
	private Integer minimumDurability;
	private Integer minimumEnchantment;
	private String material;
	private EditBox requiredEnchantment;
	
	public LootSorterFilterRulesScreen(Screen previous,
		ItemFilterModifiers initial, Consumer<ItemFilterModifiers> save)
	{
		super(Component.literal("LootSorter matching rules"));
		this.previous = previous;
		this.save = save;
		enchanted = initial.enchanted();
		damaged = initial.damaged();
		customNamed = initial.customNamed();
		curses = initial.curse();
		minimumDurability = initial.minimumDurabilityPercent();
		minimumEnchantment = initial.minimumEnchantmentLevel();
		material = initial.material();
		requiredEnchantment = null;
		this.requiredEnchantmentText = initial.requiredEnchantmentId() == null
			? "" : initial.requiredEnchantmentId();
	}
	
	private final String requiredEnchantmentText;
	
	@Override
	public void init()
	{
		int x = width / 2 - 110;
		int y = Math.max(12, height / 2 - 150);
		Button heading = addRenderableWidget(
			Button.builder(title, button -> {}).bounds(x, y, 220, 20).build());
		heading.active = false;
		addRenderableWidget(Button.builder(enchantmentText(), button -> {
			enchanted = cycleBoolean(enchanted);
			button.setMessage(enchantmentText());
		}).bounds(x, y + 26, 220, 20).build());
		addRenderableWidget(Button.builder(damageText(), button -> {
			damaged = cycleBoolean(damaged);
			button.setMessage(damageText());
		}).bounds(x, y + 52, 220, 20).build());
		addRenderableWidget(Button.builder(durabilityText(), button -> {
			minimumDurability =
				cycle(minimumDurability, new Integer[]{null, 25, 50, 75});
			button.setMessage(durabilityText());
		}).bounds(x, y + 78, 220, 20).build());
		addRenderableWidget(Button.builder(enchantmentLevelText(), button -> {
			minimumEnchantment =
				cycle(minimumEnchantment, new Integer[]{null, 1, 2, 3, 4, 5});
			button.setMessage(enchantmentLevelText());
		}).bounds(x, y + 104, 220, 20).build());
		addRenderableWidget(Button.builder(customNameText(), button -> {
			customNamed = cycleBoolean(customNamed);
			button.setMessage(customNameText());
		}).bounds(x, y + 130, 220, 20).build());
		addRenderableWidget(Button.builder(materialText(), button -> {
			material = cycle(material,
				new String[]{null, "netherite", "diamond", "gold", "iron",
					"copper", "chainmail", "leather", "wood", "stone"});
			button.setMessage(materialText());
		}).bounds(x, y + 156, 220, 20).build());
		addRenderableWidget(Button.builder(cursesText(), button -> {
			curses = cycleBoolean(curses);
			button.setMessage(cursesText());
		}).bounds(x, y + 182, 220, 20).build());
		Button enchantmentLabel = addRenderableWidget(Button
			.builder(Component.literal("Required enchantment ID (optional)"),
				button -> {})
			.bounds(x, y + 208, 220, 20).build());
		enchantmentLabel.active = false;
		requiredEnchantment = new EditBox(minecraft.font, x, y + 232, 220, 20,
			Component.literal("e.g. minecraft:sharpness"));
		requiredEnchantment.setMaxLength(128);
		requiredEnchantment
			.setHint(Component.literal("e.g. minecraft:sharpness"));
		requiredEnchantment.setValue(requiredEnchantmentText);
		addRenderableWidget(requiredEnchantment);
		addRenderableWidget(
			Button.builder(Component.literal("Done"), button -> saveAndReturn())
				.bounds(x, y + 258, 108, 20).build());
		addRenderableWidget(Button
			.builder(Component.literal("Cancel"),
				button -> minecraft.gui.setScreen(previous))
			.bounds(x + 112, y + 258, 108, 20).build());
	}
	
	@Override
	public void onClose()
	{
		minecraft.gui.setScreen(previous);
	}
	
	private void saveAndReturn()
	{
		save.accept(new ItemFilterModifiers(enchanted, damaged,
			minimumDurability, minimumEnchantment, customNamed,
			requiredEnchantment.getValue().isBlank() ? null
				: requiredEnchantment.getValue().trim(),
			material, null, curses));
		minecraft.gui.setScreen(previous);
	}
	
	private Boolean cycleBoolean(Boolean value)
	{
		return value == null ? Boolean.TRUE : value ? Boolean.FALSE : null;
	}
	
	private <T> T cycle(T value, T[] values)
	{
		for(int i = 0; i < values.length; i++)
			if(Objects.equals(values[i], value))
				return values[(i + 1) % values.length];
		return values[0];
	}
	
	private Component enchantmentText()
	{
		return Component.literal("Enchantments: " + stateText(enchanted));
	}
	
	private Component damageText()
	{
		return Component.literal("Damage: " + stateText(damaged));
	}
	
	private Component customNameText()
	{
		return Component.literal("Custom names: " + stateText(customNamed));
	}
	
	private Component cursesText()
	{
		return Component.literal("Curses: " + stateText(curses));
	}
	
	private Component materialText()
	{
		return Component.literal(
			material == null ? "Material: any" : "Material: " + material);
	}
	
	private Component durabilityText()
	{
		return Component
			.literal(minimumDurability == null ? "Minimum durability: any"
				: "Minimum durability: " + minimumDurability + "%");
	}
	
	private Component enchantmentLevelText()
	{
		return Component.literal(
			minimumEnchantment == null ? "Minimum enchantment level: any"
				: "Minimum enchantment level: " + minimumEnchantment);
	}
	
	private String stateText(Boolean value)
	{
		return value == null ? "any" : value ? "only" : "exclude";
	}
}
