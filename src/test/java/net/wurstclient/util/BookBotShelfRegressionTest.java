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
		testShelfWindow();
		testSingleShotActivation();
		testCraftingTransactions();
		testActualMenuSlots();
		testShelfGeometry();
		testBooksAndNestedPackets();
		System.out.println(
			"BookBot timing, single-shot, acknowledged crafting, shelf geometry, slot mapping and 100-page content checks passed.");
	}
	
	private static void testShelfWindow()
	{
		BookBotShelfWindow timing = new BookBotShelfWindow();
		long second = 1_000_000_000L;
		check(timing.shouldSubmit(false, second),
			"OFF must not require timing history");
		check(!timing.shouldSubmit(true, second),
			"ON must wait for a reliable cadence");
		timing.update(second);
		timing.update(2 * second);
		timing.update(3 * second);
		check(
			timing.latency() == second / 2
				&& timing.uncertainty() >= 300_000_000L,
			"Cold start uses 500 ms with low latency confidence");
		check(timing.shouldSubmit(true, 3 * second),
			"Predicted arrival at 500 ms proceeds");
		check(!timing.shouldSubmit(true, 3 * second + 300_000_000L),
			"Submission at 300 ms must defer when arrival is predicted at 800 ms");
		check(timing.shouldSubmit(true, 3 * second + 800_000_000L),
			"Prediction wraps into the next cycle at 300 ms");
		check(
			BookBotShelfWindow.inWindow(250_000_000L)
				&& BookBotShelfWindow.inWindow(700_000_000L)
				&& !BookBotShelfWindow.inWindow(249_999_999L)
				&& !BookBotShelfWindow.inWindow(700_000_001L),
			"Window edges are inclusive");
		timing.observe(436_000_000L);
		timing.observe(1_060_000_000L);
		timing.observe(500_000_000L);
		check(
			timing.latency() > 660_000_000L && timing.latency() < 670_000_000L,
			"Both successful and failed response observations contribute equally");
		check(timing.period() == second && timing.uncertainty() > 250_000_000L,
			"Perfect time cadence does not imply low response uncertainty");
		check(!timing.shouldSubmit(true, 3 * second + 100_000_000L),
			"Changed response latency changes the eligible submission phase");
		check(timing.shouldSubmit(true, 3 * second + 700_000_000L),
			"Latency compensation targets eventual interaction, not fixed submission phase");
		check(timing.phase(8 * second + 325_000_000L) == 325_000_000L,
			"Phase wraps across multiple successive cycles");
		check(!timing.usable(6 * second),
			"Stale cadence cannot leave an operation pending");
		timing.update(6 * second);
		check(!timing.usable(6 * second),
			"A large cadence discontinuity invalidates reliability");
		timing.update(7 * second);
		timing.update(8 * second);
		check(timing.usable(8 * second),
			"A recovered cadence needs no success lock");
		for(int i = 0; i < 12; i++)
			timing.observe(800_000_000L);
		check(timing.latency() == 800_000_000L && timing.uncertainty() == 0,
			"Bounded rolling history forgets old latency observations");
	}
	
	private static void testSingleShotActivation()
	{
		BookBotActivation activation = new BookBotActivation();
		int edits = 0;
		int transfers = 0;
		if(activation.submit())
			edits++;
		if(activation.transfer())
			transfers++;
		// Server confirms storage and consumes the first book. A second
		// writable
		// book is now available on every subsequent tick and inventory
		// callback.
		ItemStack nextBook = new ItemStack(Items.WRITABLE_BOOK);
		for(int callback = 0; callback < 100; callback++)
		{
			if(nextBook.is(Items.WRITABLE_BOOK) && activation.submit())
				edits++;
			if(activation.transfer())
				transfers++;
		}
		check(edits == 1 && transfers == 1,
			"Successful Shelf storage followed by another writable book cannot repeat the activation");
		activation.cancel();
		check(!activation.submit() && !activation.transfer(),
			"Disable invalidates queued execution");
		BookBotActivation reconnected = new BookBotActivation();
		check(
			!reconnected.id().equals(activation.id()) && reconnected.submit()
				&& !activation.submit(),
			"A reconnect/new command cannot revive an older activation");
		BookBotActivation cancelledBeforeEdit = new BookBotActivation();
		cancelledBeforeEdit.cancel();
		check(!cancelledBeforeEdit.submit(),
			"Cancellation during ShelfWindow prevents an edit");
	}
	
	private static List<ItemStack> craftingInventory()
	{
		List<ItemStack> slots =
			new ArrayList<>(java.util.Collections.nCopies(46, ItemStack.EMPTY));
		slots.set(10, new ItemStack(Items.BOOK, 2));
		slots.set(11, new ItemStack(Items.FEATHER, 2));
		slots.set(12, new ItemStack(Items.INK_SAC, 2));
		return slots;
	}
	
	private static void testCraftingTransactions()
	{
		List<ItemStack> slots = craftingInventory();
		BookBotInventoryPlan plan =
			BookBotInventoryPlan.craft(slots, ItemStack.EMPTY, 10);
		int destination = plan.destination;
		check(destination == 9,
			"Destination is reserved before the first ingredient click");
		ItemStack cursor = ItemStack.EMPTY;
		int revision = 10;
		int outputPickups = 0;
		while(!plan.complete())
		{
			var click = plan.next(slots, cursor);
			check(click != null, "Valid craft advances");
			check(plan.next(slots, cursor) == null,
				"No next click before server acknowledgement");
			check(click.slot() >= 0,
				"Crafting never clicks outside or drops the cursor");
			if(click.slot() == 0)
			{
				check(slots.get(destination).isEmpty() && cursor.isEmpty(),
					"Never pick up the output without an empty reserved destination/cursor");
				outputPickups++;
				cursor = slots.get(0).copy();
				for(int i = 0; i <= 3; i++)
					slots.set(i, ItemStack.EMPTY);
			}else if(cursor.isEmpty())
			{
				cursor = slots.get(click.slot()).copy();
				slots.set(click.slot(), ItemStack.EMPTY);
			}else if(click.button() == 1)
			{
				slots.set(click.slot(), cursor.copyWithCount(1));
				cursor = cursor.copy();
				cursor.shrink(1);
			}else
			{
				check(slots.get(click.slot()).isEmpty(),
					"No destination overwrite");
				slots.set(click.slot(), cursor.copy());
				cursor = ItemStack.EMPTY;
			}
			if(slots.get(1).is(Items.BOOK) && slots.get(2).is(Items.FEATHER)
				&& slots.get(3).is(Items.INK_SAC))
				slots.set(0, new ItemStack(Items.WRITABLE_BOOK));
			check(!plan.acknowledge(revision, slots, cursor),
				"Duplicate state cannot acknowledge the next click");
			check(!plan.acknowledge(revision - 1, slots, cursor),
				"Older state cannot acknowledge the next click");
			check(plan.waiting(),
				"Ignored packets cannot release pending clicks");
			check(plan.acknowledge(++revision, slots, cursor),
				"New full server state acknowledges one click");
			if(!cursor.isEmpty())
				check(!plan.complete(),
					"Cursor-held book/ingredient can never become READY_TO_WRITE");
		}
		check(
			outputPickups == 1 && cursor.isEmpty()
				&& slots.get(destination).is(Items.WRITABLE_BOOK),
			"Exactly one crafted book reaches the reserved inventory slot with an empty cursor");
		for(int i = 10; i <= 12; i++)
			check(slots.get(i).getCount() == 1,
				"Ingredient remainders return to their original slots");
		check(plan.next(slots, cursor) == null,
			"Completed craft cannot craft another book");
		List<ItemStack> full = craftingInventory();
		for(int i = 9; i < 45; i++)
			if(full.get(i).isEmpty())
				full.set(i, new ItemStack(Items.STONE));
		expectCraftRejected(full, ItemStack.EMPTY);
		expectCraftRejected(craftingInventory(),
			new ItemStack(Items.WRITABLE_BOOK));
		var interrupted =
			BookBotInventoryPlan.craft(craftingInventory(), ItemStack.EMPTY, 1);
		interrupted.next(craftingInventory(), ItemStack.EMPTY);
		interrupted.cancel();
		check(
			!interrupted.acknowledge(2, slots,
				new ItemStack(Items.WRITABLE_BOOK))
				&& interrupted.next(slots, cursor) == null
				&& !interrupted.complete(),
			"Interrupted transactions preserve the cursor and never resume on delayed acknowledgement");
		var occupied = craftingInventory();
		var blocked = BookBotInventoryPlan.craft(occupied, ItemStack.EMPTY, 1);
		occupied.set(10, new ItemStack(Items.STONE));
		check(
			blocked.next(occupied, ItemStack.EMPTY) == null && blocked.failed(),
			"A changed ingredient source is never clicked");
		var rejected =
			BookBotInventoryPlan.craft(craftingInventory(), ItemStack.EMPTY, 1);
		rejected.next(craftingInventory(), ItemStack.EMPTY);
		rejected.acknowledge(2, craftingInventory(), ItemStack.EMPTY);
		check(rejected.failed() && rejected.next(slots, cursor) == null,
			"Rejected click stops safely without dispatching subsequent steps");
		var outputSlots = craftingInventory();
		var outputPlan = planAtCraftingOutput(outputSlots);
		outputSlots.set(outputPlan.destination, new ItemStack(Items.STONE));
		check(
			outputPlan.next(outputSlots, ItemStack.EMPTY) == null
				&& outputPlan.failed(),
			"A destination filled during crafting prevents output pickup");
		var carriedSlots = craftingInventory();
		var carriedPlan = planAtCraftingOutput(carriedSlots);
		check(carriedPlan.next(carriedSlots, ItemStack.EMPTY).slot() == 0,
			"Confirmed output is picked up once");
		for(int i = 0; i <= 3; i++)
			carriedSlots.set(i, ItemStack.EMPTY);
		ItemStack craftedCursor = new ItemStack(Items.WRITABLE_BOOK);
		carriedPlan.acknowledge(7, carriedSlots, craftedCursor);
		check(!carriedPlan.complete(),
			"Output on the cursor never completes crafting");
		carriedSlots.set(carriedPlan.destination, new ItemStack(Items.STONE));
		check(carriedPlan.next(carriedSlots, craftedCursor) == null
			&& carriedPlan.failed() && craftedCursor.is(Items.WRITABLE_BOOK),
			"Interrupted placement leaves the book on the cursor without clicking the occupied destination");
		var moveSlots = craftingInventory();
		moveSlots.set(10, new ItemStack(Items.WRITABLE_BOOK));
		var move = BookBotInventoryPlan.move(moveSlots, 10, 1, 32767);
		check(move.next(moveSlots, ItemStack.EMPTY).slot() == 10,
			"Move uses the protocol source slot");
		moveSlots.set(37, moveSlots.get(10));
		moveSlots.set(10, ItemStack.EMPTY);
		check(
			move.acknowledge(0, moveSlots, ItemStack.EMPTY) && move.complete(),
			"Hotbar move is confirmed across inventory state-ID wrap");
	}
	
	private static BookBotInventoryPlan planAtCraftingOutput(
		List<ItemStack> slots)
	{
		var ingredients = List.of(Items.BOOK, Items.FEATHER, Items.INK_SAC);
		for(int i = 0; i < 3; i++)
			slots.set(10 + i, new ItemStack(ingredients.get(i)));
		var plan = BookBotInventoryPlan.craft(slots, ItemStack.EMPTY, 0);
		int revision = 0;
		for(int grid = 1; grid <= 3; grid++)
		{
			check(plan.next(slots, ItemStack.EMPTY).slot() == 9 + grid,
				"Confirmed ingredient source is used");
			ItemStack ingredient = slots.get(9 + grid).copy();
			slots.set(9 + grid, ItemStack.EMPTY);
			plan.acknowledge(++revision, slots, ingredient);
			var place = plan.next(slots, ingredient);
			check(place.slot() == grid && place.button() == 1,
				"Exactly one ingredient is placed into the empty grid slot");
			slots.set(grid, ingredient);
			if(grid == 3)
				slots.set(0, new ItemStack(Items.WRITABLE_BOOK));
			plan.acknowledge(++revision, slots, ItemStack.EMPTY);
		}
		return plan;
	}
	
	private static void expectCraftRejected(List<ItemStack> slots,
		ItemStack cursor)
	{
		try
		{
			BookBotInventoryPlan.craft(slots, cursor, 1);
			throw new AssertionError(
				"Unsafe crafting must be rejected before any click");
		}catch(IllegalArgumentException expected)
		{}
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
			pages.add("\u0800".repeat(1023) + (char)(0x0900 + i));
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
