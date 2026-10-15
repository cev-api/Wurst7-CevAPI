/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gson.JsonParser;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.other_features.packettools.BookContentSummaries;
import net.wurstclient.other_features.packettools.InventorySlotMapping;
import net.wurstclient.other_features.packettools.PacketDumper;
import net.wurstclient.other_features.packettools.PacketDecodeCoverage;
import net.wurstclient.other_features.packettools.PacketFilter;
import net.wurstclient.other_features.packettools.EntityLifecycleTracker;

/**
 * Uses vanilla shelf geometry and actual book components without a client/GPU.
 */
public final class BookBotShelfRegressionTest
{
	public static void main(String[] args)
	{
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		var lookup = VanillaRegistries.createWorldLookup();
		net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
			.build(lookup).forEach(pending -> pending.apply());
		testActualMenuSlots();
		testShelfGeometry();
		testBooksAndNestedPackets();
		System.out.println(
			"BookBot shelf geometry, actual menu indexes, and 100-page content checks passed.");
	}
	
	private static void testActualMenuSlots()
	{
		Inventory inventory = new Inventory(null, new EntityEquipment());
		InventoryMenu menu = new InventoryMenu(inventory, false, null);
		check(menu.getSlot(37).getContainerSlot() == 1,
			"Vanilla protocol slot 37 has backing inventory slot 1");
		check(
			InventorySlotMapping.describe(menu, menu.getSlot(37), inventory)
				.equals("hotbar[1]"),
			"Actual slot 37 must not become crafting input");
		check(
			InventorySlotMapping.describe(menu, menu.getSlot(1), inventory)
				.equals("crafting_input[0]"),
			"Actual crafting slot remains distinct");
		ChestMenu chest = ChestMenu.threeRows(3, inventory);
		check(
			InventorySlotMapping.describe(chest, chest.getSlot(55), inventory)
				.equals("hotbar[1]"),
			"Chest protocol slot 55 maps via backing player index 1");
	}
	
	private static void testShelfGeometry()
	{
		BlockPos pos = new BlockPos(-340603, 129, 457473);
		for(Direction facing : List.of(Direction.NORTH, Direction.SOUTH,
			Direction.WEST, Direction.EAST))
		{
			var state = Blocks.ACACIA_SHELF.defaultBlockState()
				.setValue(ShelfBlock.FACING, facing);
			Vec3 eye = Vec3.atCenterOf(pos).add(facing.getStepX() * 2, 0.12,
				facing.getStepZ() * 2);
			for(int slot = 0; slot < 3; slot++)
				checkShelfRay(state, pos, eye, slot, facing);
		}
		var west = Blocks.ACACIA_SHELF.defaultBlockState()
			.setValue(ShelfBlock.FACING, Direction.WEST);
		Vec3 reportedEye = new Vec3(-340602.910, 129.62, 457473.673);
		for(int slot = 0; slot < 3; slot++)
			checkShelfRay(west, pos, reportedEye, slot, Direction.WEST);
		ShelfBlockEntity shelf = new ShelfBlockEntity(pos, west);
		check(
			west.getShape(EmptyBlockGetter.INSTANCE, pos).clip(reportedEye,
				ShelfUtils.getItemPosition(shelf, 1), pos) == null,
			"Old rendered-item ray reproduces the reported hollow-shelf miss");
		Vec3 behind = Vec3.atCenterOf(pos).add(2, 0, 0);
		Vec3 aim = ShelfTransferTargeting.aimPoint(west, pos, 1,
			EmptyBlockGetter.INSTANCE);
		var hit = west.getShape(EmptyBlockGetter.INSTANCE, pos).clip(behind,
			aim, pos);
		check(
			hit == null || ((ShelfBlock)west.getBlock())
				.getHitSlot(hit, Direction.WEST).isEmpty(),
			"Back-face rays must never be accepted as shelf-front interactions");
	}
	
	private static void checkShelfRay(
		net.minecraft.world.level.block.state.BlockState state, BlockPos pos,
		Vec3 eye, int slot, Direction facing)
	{
		Vec3 aim = ShelfTransferTargeting.aimPoint(state, pos, slot,
			EmptyBlockGetter.INSTANCE);
		check(aim != null, "Every shelf slot has a real interaction surface");
		var hit =
			state.getShape(EmptyBlockGetter.INSTANCE, pos).clip(eye, aim, pos);
		check(hit != null && hit.getDirection() == facing,
			"Ray must hit the actual front face for " + facing + " slot "
				+ slot);
		check(
			((ShelfBlock)state.getBlock()).getHitSlot(hit, facing)
				.orElse(-1) == slot,
			"Vanilla slot selection must agree with the intended empty slot");
	}
	
	private static void testBooksAndNestedPackets()
	{
		List<String> pages = new ArrayList<>();
		for(int i = 0; i < 100; i++)
			pages.add("a".repeat(3071) + (char)('A' + i % 26));
		String hash = (String)BookContentSummaries
			.fromStrings(pages, "Test", "Player", "SIGNED", true)
			.get("combinedContentSha256");
		List<String> submittedPages = List.copyOf(pages);
		ItemStack signed = new ItemStack(Items.WRITTEN_BOOK);
		signed.set(DataComponents.WRITTEN_BOOK_CONTENT,
			new WrittenBookContent(Filterable.passThrough("Test"), "Player", 0,
				pages.stream()
					.map(page -> Filterable
						.<Component> passThrough(Component.literal(page)))
					.toList(),
				true));
		ItemStack unsigned = new ItemStack(Items.WRITABLE_BOOK);
		unsigned.set(DataComponents.WRITABLE_BOOK_CONTENT,
			new WritableBookContent(
				pages.stream().map(Filterable::passThrough).toList()));
		check(BookContentSummaries.matchesStack(signed, hash, 100, true),
			"Signed server book matches raw submitted pages without JSON codec conversions");
		check(BookContentSummaries.matchesStack(unsigned, hash, 100, false),
			"Unsigned confirmation uses writable content");
		check(!BookContentSummaries.matchesStack(unsigned, hash, 100, true),
			"Wrong signed item identity cannot confirm a transfer");
		check(!BookContentSummaries.matchesStack(signed, hash, 99, true),
			"Page counts must match exactly");
		pages.set(99, "changed final page");
		ItemStack changed = signed.copy();
		changed.set(DataComponents.WRITTEN_BOOK_CONTENT,
			new WrittenBookContent(Filterable.passThrough("Test"), "Player", 0,
				pages.stream()
					.map(page -> Filterable
						.<Component> passThrough(Component.literal(page)))
					.toList(),
				true));
		check(!BookContentSummaries.matchesStack(changed, hash, 100, true),
			"Changes beyond the old 64-page preview must be detected");
		unsigned.set(DataComponents.WRITTEN_BOOK_CONTENT,
			changed.get(DataComponents.WRITTEN_BOOK_CONTENT));
		check(
			BookContentSummaries.matchesStack(unsigned, hash, 100, false)
				&& BookContentSummaries.fromStack(unsigned).get("signedState")
					.equals("UNSIGNED"),
			"A stale written-book component must not override writable book content");
		PacketDumper dumper = new PacketDumper(new PacketDecodeCoverage(),
			new PacketFilter(), new EntityLifecycleTracker());
		var json = JsonParser.parseString(dumper.dumpForensicsRecord(
			new ClientboundContainerSetSlotPacket(0, 19, 37, signed), false, 0))
			.getAsJsonObject();
		var summary =
			json.getAsJsonObject("fields").getAsJsonObject("_decodedItem")
				.getAsJsonObject("bookContentSummary");
		check(
			summary.get("pageCount").getAsInt() == 100
				&& summary.getAsJsonArray("pages").size() == 100,
			"Incoming nested stack logs retain all per-page hashes even with full content disabled");
		check(summary.get("totalUtf8ByteCount").getAsLong() == 307200,
			"Incoming nested stack metadata covers all 300 KiB");
		List<ItemStack> slots =
			new ArrayList<>(java.util.Collections.nCopies(46, ItemStack.EMPTY));
		slots.set(37, signed);
		var container = JsonParser
			.parseString(dumper
				.dumpForensicsRecord(new ClientboundContainerSetContentPacket(0,
					20, slots, ItemStack.EMPTY), false, 0))
			.getAsJsonObject().getAsJsonObject("fields");
		check(
			container.getAsJsonArray("_decodedItems").get(37).getAsJsonObject()
				.getAsJsonObject("bookContentSummary").getAsJsonArray("pages")
				.size() == 100,
			"Full-container updates preserve the book's complete structural metadata");
		var outgoing = JsonParser
			.parseString(
				dumper.dumpForensicsRecord(new ServerboundEditBookPacket(1,
					submittedPages, Optional.of("Test")), false, 0))
			.getAsJsonObject().getAsJsonObject("fields")
			.getAsJsonObject("_bookContentSummary");
		check(
			outgoing.get("titlePresent").getAsBoolean() && outgoing
				.get("signedState").getAsString().equals("SIGNED_PACKET_TYPE"),
			"Optional packet titles mark signed edits correctly");
		check(outgoing.get("combinedContentSha256").getAsString().equals(hash),
			"Outgoing and incoming book summaries use the same complete content hash");
	}
	
	private static void check(boolean condition, String message)
	{
		if(!condition)
			throw new AssertionError(message);
	}
}
