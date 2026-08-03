/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.gametest.tests;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.hacks.lootsorter.BuiltInItemFilter;
import net.wurstclient.hacks.lootsorter.CustomItemFilterPreset;
import net.wurstclient.hacks.lootsorter.DestinationRule;
import net.wurstclient.hacks.lootsorter.ExactItemFilter;
import net.wurstclient.hacks.lootsorter.ItemFilterModifiers;
import net.wurstclient.hacks.lootsorter.ItemFilterCodec;
import net.wurstclient.hacks.lootsorter.ModifiedItemFilter;
import net.wurstclient.hacks.lootsorter.ItemStackEquivalenceKey;
import net.wurstclient.hacks.lootsorter.ItemStackSnapshotCodec;
import net.wurstclient.hacks.lootsorter.InventoryProvenanceLedger;
import net.wurstclient.hacks.lootsorter.LogicalContainer;
import net.wurstclient.hacks.lootsorter.LootSorterProfile;
import net.wurstclient.hacks.lootsorter.LootSorterSelectionPresetStore.DestinationPreset;
import net.wurstclient.hacks.lootsorter.LootSorterSelectionPresetStore.Presets;
import net.wurstclient.hacks.lootsorter.LootSorterSelectionPresetStore.SourcePreset;
import net.wurstclient.hacks.lootsorter.LootSorterSourceChestManager;
import net.wurstclient.hacks.lootsorter.SortPlanner;
import net.wurstclient.hacks.lootsorter.SortRoute;
import net.wurstclient.hacks.lootsorter.SourceContentsSnapshot;

/** Fast deterministic checks for routing and component-aware filter models. */
public final class LootSorterModelTest
{
	private final ClientGameTestContext context;
	
	public LootSorterModelTest(ClientGameTestContext context)
	{
		this.context = context;
	}
	
	public void run()
	{
		testComponentEquivalence();
		testExactProfileCodec();
		testSourceSnapshotCodec();
		testSourceChestSearchData();
		testEnchantedBookLevel();
		testFilters();
		testLedgerDeferral();
		testSelectionPresetData();
		testPlannerLargestGroupAndSpecificity();
		testPlannerBatchesMatchingItems();
	}
	
	private void testComponentEquivalence()
	{
		ItemStack original = new ItemStack(Items.DIAMOND_PICKAXE);
		ItemStack damaged = original.copy();
		damaged.setDamageValue(1);
		ItemStack named = original.copy();
		named.set(DataComponents.CUSTOM_NAME, Component.literal("Keep"));
		check(
			!ItemStackEquivalenceKey.of(original)
				.equals(ItemStackEquivalenceKey.of(damaged)),
			"damage must be part of provenance identity");
		check(
			!ItemStackEquivalenceKey.of(original)
				.equals(ItemStackEquivalenceKey.of(named)),
			"custom names must be part of provenance identity");
	}
	
	private void testFilters()
	{
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		ItemStack bread = new ItemStack(Items.BREAD);
		ItemStack dirt = new ItemStack(Items.DIRT);
		ItemStack totem = new ItemStack(Items.TOTEM_OF_UNDYING);
		check(
			BuiltInItemFilter.ALL.matches(sword)
				&& BuiltInItemFilter.ALL.matches(dirt),
			"everything filter must move every non-empty item stack");
		check(BuiltInItemFilter.SWORDS.matches(sword),
			"sword subtype must match a sword");
		check(BuiltInItemFilter.FOOD.matches(bread),
			"food category must match food");
		check(BuiltInItemFilter.MOB_DROPS.matches(totem),
			"mob drops must include Totems of Undying");
		check(
			BuiltInItemFilter.TOTEMS_OF_UNDYING.matches(totem)
				&& !BuiltInItemFilter.TOTEMS_OF_UNDYING.matches(bread),
			"Totems of Undying must be independently selectable");
		check(!BuiltInItemFilter.TOOLS_UNENCHANTED.matches(bread),
			"tool subtype must not match food");
		
		CustomItemFilterPreset preset = new CustomItemFilterPreset("Diamonds",
			java.util.Set.of("minecraft:diamond"), java.util.Set.of(),
			java.util.Set.of(),
			new ItemFilterModifiers(null, null, null, null, null));
		check(preset.matches(new ItemStack(Items.DIAMOND)),
			"custom include list must match its item");
		check(!preset.matches(new ItemStack(Items.DIRT)),
			"custom include list must exclude other items");
		var netheriteOnly = new ModifiedItemFilter(BuiltInItemFilter.ALL,
			new ItemFilterModifiers(null, null, null, null, null, null,
				"netherite", null, null));
		check(
			netheriteOnly.matches(new ItemStack(Items.NETHERITE_SWORD))
				&& !netheriteOnly.matches(new ItemStack(Items.IRON_SWORD)),
			"material rule must distinguish item material");
		check(
			ItemFilterCodec.decode(ItemFilterCodec.encode(netheriteOnly))
				.matches(new ItemStack(Items.NETHERITE_SWORD)),
			"material rule must survive profile encoding");
	}
	
	private void testExactProfileCodec()
	{
		boolean restored = context.computeOnClient(mc -> {
			ItemStack named = new ItemStack(Items.DIAMOND);
			named.set(DataComponents.CUSTOM_NAME,
				Component.literal("Profile exact"));
			String token = ItemFilterCodec.encode(new ExactItemFilter(named),
				mc.level.registryAccess());
			return ItemFilterCodec.decode(token, mc.level.registryAccess())
				.matches(named);
		});
		check(restored, "exact profile filter must preserve stack components");
	}
	
	private void testSelectionPresetData()
	{
		LootSorterProfile.ContainerPos sourcePos =
			new LootSorterProfile.ContainerPos(1, 64, 2);
		LootSorterProfile.DestinationProfile destination =
			new LootSorterProfile.DestinationProfile(sourcePos, 0,
				List.of("builtin:ALL"));
		Presets presets = new Presets(
			List.of(new SourcePreset("Sources", "server", "dimension", "type",
				List.of(sourcePos), List.of())),
			List.of(new DestinationPreset("Destinations", "server", "dimension",
				"type", List.of(destination))));
		check(
			presets.sources().getFirst().sources().equals(List.of(sourcePos))
				&& presets.destinations().getFirst().destinations().getFirst()
					.filters().equals(List.of("builtin:ALL")),
			"selection presets must retain positions and encoded filters");
	}
	
	private void testSourceSnapshotCodec()
	{
		boolean restored = context.computeOnClient(mc -> {
			ItemStack original = new ItemStack(Items.DIAMOND_PICKAXE);
			original.setDamageValue(7);
			original.set(DataComponents.CUSTOM_NAME,
				Component.literal("Cached source stack"));
			String token = ItemStackSnapshotCodec.encode(original,
				mc.level.registryAccess());
			ItemStack decoded =
				ItemStackSnapshotCodec.decode(token, mc.level.registryAccess());
			return ItemStack.isSameItemSameComponents(original, decoded)
				&& original.getCount() == decoded.getCount();
		});
		check(restored,
			"source scans must preserve stack components across persistence");
		SourceContentsSnapshot empty = new SourceContentsSnapshot(
			new LootSorterProfile.ContainerPos(1, 64, 2), null);
		check(empty.items().isEmpty(),
			"empty scanned source snapshots must remain valid cache entries");
	}
	
	private void testSourceChestSearchData()
	{
		boolean found = context.computeOnClient(mc -> {
			LootSorterProfile.ContainerPos source =
				new LootSorterProfile.ContainerPos(4, 64, 8);
			ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
			String token =
				ItemStackSnapshotCodec.encode(sword, mc.level.registryAccess());
			LootSorterSourceChestManager manager =
				new LootSorterSourceChestManager(List.of(source),
					List.of(new SourceContentsSnapshot(source, List.of(token))),
					"server", "minecraft:overworld", mc.level.registryAccess());
			return manager.all().size() == 1
				&& manager.search("diamond sword").size() == 1
				&& manager.search("not present").isEmpty();
		});
		check(found,
			"source ChestSearch data must include only matching selected sources");
	}
	
	private void testLedgerDeferral()
	{
		InventoryProvenanceLedger ledger = new InventoryProvenanceLedger();
		ItemStack stack = new ItemStack(Items.DIAMOND, 12);
		ItemStackEquivalenceKey key = ItemStackEquivalenceKey.of(stack);
		ledger.confirmWithdrawal(stack, 12);
		check(ledger.deferMovable(key) == 12 && ledger.getMovable(key) == 0,
			"unsafe merged loot must be deferred instead of ending the run");
	}
	
	private void testEnchantedBookLevel()
	{
		boolean levelFourMatches = context.computeOnClient(mc -> {
			ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
			var enchantments =
				mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
			book.enchant(enchantments
				.get(Identifier.parse("minecraft:sharpness")).orElseThrow(), 4);
			return BuiltInItemFilter.ENCHANTED_BOOK_LEVEL_4.matches(book)
				&& !BuiltInItemFilter.ENCHANTED_BOOK_LEVEL_5.matches(book);
		});
		check(levelFourMatches,
			"level-4 enchanted-book filter must distinguish level five");
	}
	
	private void testPlannerLargestGroupAndSpecificity()
	{
		LogicalContainer sourceFirst =
			new LogicalContainer(new BlockPos(4, 64, 0));
		LogicalContainer sourceSecond =
			new LogicalContainer(new BlockPos(8, 64, 0));
		DestinationRule broad =
			destination(new BlockPos(12, 64, 0), 1, BuiltInItemFilter.ALL);
		ItemStack diamond = new ItemStack(Items.DIAMOND, 15);
		DestinationRule exact = destination(new BlockPos(10, 64, 0), 0,
			new ExactItemFilter(diamond));
		Map<LogicalContainer, List<ItemStack>> sources = new LinkedHashMap<>();
		sources.put(sourceFirst, List.of(diamond));
		sources.put(sourceSecond, List.of(new ItemStack(Items.DIRT, 27)));
		
		SortRoute largest = new SortPlanner().plan(Vec3.ZERO, sources,
			List.of(exact, broad), List.of(sourceFirst, sourceSecond));
		check(
			largest != null && largest.source().equals(sourceSecond)
				&& largest.destination().equals(broad),
			"planner must choose the largest destination group before travel order");
		
		sources.put(sourceSecond, List.of(new ItemStack(Items.DIRT, 15)));
		SortRoute specific = new SortPlanner().plan(Vec3.ZERO, sources,
			List.of(exact, broad), List.of(sourceFirst, sourceSecond));
		check(specific != null && specific.destination().equals(exact),
			"exact custom filter must win an equal-size broad-filter conflict");
	}
	
	private void testPlannerBatchesMatchingItems()
	{
		LogicalContainer source = new LogicalContainer(new BlockPos(4, 64, 0));
		LogicalContainer secondSource =
			new LogicalContainer(new BlockPos(6, 64, 0));
		DestinationRule weapons =
			destination(new BlockPos(8, 64, 0), 0, BuiltInItemFilter.WEAPONS);
		ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
		ItemStack bow = new ItemStack(Items.BOW);
		Map<LogicalContainer, List<ItemStack>> sources = new LinkedHashMap<>();
		sources.put(source, List.of(sword));
		sources.put(secondSource, List.of(bow));
		
		SortRoute route = new SortPlanner().plan(Vec3.ZERO, sources,
			List.of(weapons), List.of(source, secondSource));
		check(
			route != null && route.groupItemCount() == 2
				&& route.itemKeys().contains(ItemStackEquivalenceKey.of(sword))
				&& route.itemKeys().contains(ItemStackEquivalenceKey.of(bow))
				&& route.sourceItemKeys().size() == 2
				&& route.itemKeysFor(source)
					.contains(ItemStackEquivalenceKey.of(sword))
				&& route.itemKeysFor(secondSource)
					.contains(ItemStackEquivalenceKey.of(bow)),
			"planner must batch matching items from several sources for one destination");
	}
	
	private DestinationRule destination(BlockPos position, int priority,
		net.wurstclient.hacks.lootsorter.ItemFilter filter)
	{
		DestinationRule rule =
			new DestinationRule(new LogicalContainer(position), priority);
		rule.addFilter(filter);
		rule.setConfigured(true);
		return rule;
	}
	
	private void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(
				"LootSorter model test failed: " + message);
	}
}
