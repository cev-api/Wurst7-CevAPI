/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.PrimitiveIterator;
import java.util.Random;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.ShelfBlock;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.WurstClient;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.FileSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.ShelfTransferTargeting;
import net.wurstclient.util.chunk.ChunkUtils;
import net.wurstclient.other_features.packettools.BookContentSummaries;
import net.wurstclient.other_features.packettools.ItemStackForensics;

@SearchTags({"book bot", "book writer"})
public final class BookBotHack extends Hack implements UpdateListener
{
	private static final int BOOKBOT_EXPORT_VERSION = 1;
	private static final int MAX_BOOK_PAGES = 100;
	private static final int MAX_PAGE_JAVA_CHARS = 1024;
	private static final int MAX_TITLE_JAVA_CHARS = 32;
	private static final int MIN_THREE_BYTE_BMP = 0x0800;
	private static final int MAX_THREE_BYTE_BMP_EXCLUSIVE = 0xD800;
	
	private enum Mode
	{
		FILE,
		RANDOM
	}
	
	private enum RandomType
	{
		ASCII,
		UTF8,
		PAPER_MC
	}
	
	private enum AfterWrite
	{
		KEEP,
		DROP,
		PLACE_ON_SHELF
	}
	
	private enum ShelfTransferStrategy
	{
		IMMEDIATE,
		CONFIRMED_CONTENT
	}
	
	private enum ShelfTransferState
	{
		IDLE,
		WAITING_CONTENT,
		TRANSFER_REQUESTED,
		VERIFYING_STORAGE,
		STORED,
		FAILED,
		INDETERMINATE
	}
	
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"What kind of text to write.", Mode.values(), Mode.RANDOM);
	
	private final SliderSetting pages = new SliderSetting("Pages",
		"Number of pages to write per book (Random mode).", 50, 1, 100, 1,
		ValueDisplay.INTEGER);
	
	private final SliderSetting characters = new SliderSetting("Characters",
		"Number of characters to write per page (Random mode).", 128, 1, 1024,
		1, ValueDisplay.INTEGER);
	
	private final EnumSetting<RandomType> randomType = new EnumSetting<>(
		"Random type", "Character profile used in Random mode.",
		RandomType.values(), RandomType.UTF8);
	
	private final SliderSetting delay = new SliderSetting("Delay (ticks)",
		"Delay between writing books in ticks.", 20, 1, 200, 1,
		ValueDisplay.INTEGER);
	
	private final CheckboxSetting sign =
		new CheckboxSetting("Sign", "Whether to sign the book.", true);
	
	// ### MODIFIED ### Keep and Drop remain available alongside shelf
	// placement.
	private final EnumSetting<AfterWrite> afterWrite = new EnumSetting<>(
		"After writing", "What to do with each completed book.",
		AfterWrite.values(), AfterWrite.KEEP);
	private final EnumSetting<ShelfTransferStrategy> shelfTransferStrategy =
		new EnumSetting<>("Shelf transfer strategy",
			"Immediate attempts placement after submitting the edit. Confirmed waits for matching server-supplied content.",
			ShelfTransferStrategy.values(),
			ShelfTransferStrategy.CONFIRMED_CONTENT);
	
	private final CheckboxSetting autoCraft = new CheckboxSetting("Auto-craft",
		"When you run out of book & quills, craft one from a book, a feather"
			+ " and an ink sac in your inventory.",
		false);
	
	private final TextFieldSetting name = new TextFieldSetting("Name",
		"The name you want to give your books.", "I Love Cevapi!");
	
	private final CheckboxSetting appendCount = new CheckboxSetting(
		"Append count", "Append sequential number to the title.", true);
	
	private final CheckboxSetting highByteSignedName = new CheckboxSetting(
		"High-byte signed name",
		"Uses a 32 character 3-byte UTF-8 signed book name in Random UTF8 mode.",
		true);
	
	private final CheckboxSetting wordWrap = new CheckboxSetting("Word wrap",
		"Prevents words from being cut in the middle of lines.", true);
	
	private final CheckboxSetting saveToFile = new CheckboxSetting(
		"Save to file",
		"Also saves each written book as an importable file under wurst/bookbot.",
		false);
	
	private final FileSetting fileSetting =
		new FileSetting("File", "", "bookbot", folder -> {
			try
			{
				java.nio.file.Files.createDirectories(folder);
				java.nio.file.Path p = folder.resolve("bookbot.txt");
				if(!java.nio.file.Files.exists(p))
					java.nio.file.Files.writeString(p,
						"Mix 500g beef mince + 500g lamb/pork mince with 3–4 minced garlic cloves, 1 grated onion (squeezed dry), 2 tsp sweet paprika, 1 tsp baking soda, 2 tsp salt, pepper, and a splash of sparkling water, then chill the mixture overnight and shape into finger-length sausages. Grill or pan-sear over medium-high heat until deeply browned and cooked through, then serve in lepinja with chopped onion, ajvar and kajmak.\n");
			}catch(IOException e)
			{
				throw new RuntimeException(e);
			}
		});
	
	private int delayTimer;
	private int bookCount;
	private int inventoryCooldown;
	private Random random;
	private final Set<Integer> unsignedWrittenSlots = new HashSet<>();
	private ShelfTransferState shelfTransferState = ShelfTransferState.IDLE;
	private String submittedContentHash;
	private int submittedSlot = -1;
	private BlockPos transferShelfPos;
	private int transferShelfSlot = -1;
	private long editSubmittedNanos;
	private String forensicTransactionId;
	private long forensicPayloadBytes;
	private boolean transferAttempted;
	private int submittedPageCount;
	private boolean submittedSigned;
	private ShelfTarget preparedShelfTarget;
	private long interactionRequestedNanos;
	private String lastShelfRejection = "No nearby shelf was loaded.";
	private ShelfTransferStrategy activeShelfStrategy;
	
	private record BookFileData(List<String> pages, String title,
		boolean signed)
	{}
	
	public BookBotHack()
	{
		super("BookBot");
		setCategory(Category.ITEMS);
		
		addSetting(mode);
		addSetting(pages);
		addSetting(characters);
		addSetting(randomType);
		addSetting(delay);
		addSetting(sign);
		addSetting(afterWrite);
		addSetting(shelfTransferStrategy);
		addSetting(autoCraft);
		addSetting(name);
		addSetting(appendCount);
		addSetting(highByteSignedName);
		addSetting(wordWrap);
		addSetting(saveToFile);
		addSetting(fileSetting);
	}
	
	@Override
	protected void onEnable()
	{
		if(mode.getSelected() == Mode.FILE)
		{
			File f = fileSetting.getSelectedFile().toFile();
			if(f == null || !f.exists())
			{
				ChatUtils.message("No file selected or file missing; disable.");
				setEnabled(false);
				return;
			}
		}
		
		random = new Random();
		delayTimer = delay.getValueI();
		inventoryCooldown = 0;
		bookCount = 0;
		unsignedWrittenSlots.clear();
		clearShelfTransfer();
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		unsignedWrittenSlots.clear();
		// ### ADDED ### Disabling cancels pending automation without retrying.
		clearShelfTransfer();
	}
	
	@Override
	public void onUpdate()
	{
		if(shelfTransferState != ShelfTransferState.IDLE)
		{
			progressShelfTransfer();
			return;
		}
		// Find an empty writable book in inventory
		if(inventoryCooldown > 0)
		{
			inventoryCooldown--;
			return;
		}
		
		int slot = findWritableBookSlot();
		if(slot == -1)
		{
			if(autoCraft.isChecked() && tryCraftBookAndQuill())
				return;
			
			setEnabled(false);
			return;
		}
		
		// Move to main hand if needed
		if(!isInMainHand(slot))
		{
			moveToMainHand(slot);
			return;
		}
		
		// Delay
		if(delayTimer > 0)
		{
			delayTimer--;
			return;
		}
		delayTimer = delay.getValueI();
		
		// Produce pages
		if(mode.getSelected() == Mode.RANDOM)
		{
			List<String> generatedPages;
			switch(randomType.getSelected())
			{
				case PAPER_MC:
				generatedPages = paperMcPages();
				break;
				case ASCII:
				{
					PrimitiveIterator.OfInt chars = random.ints(0x21, 0x80)
						.filter(i -> !Character.isWhitespace(i) && i != '\r'
							&& i != '\n')
						.iterator();
					generatedPages = randomPages(chars);
					break;
				}
				case UTF8:
				default:
				{
					PrimitiveIterator.OfInt chars = random
						.ints(MIN_THREE_BYTE_BMP, MAX_THREE_BYTE_BMP_EXCLUSIVE)
						.filter(i -> !Character.isWhitespace(i) && i != '\r'
							&& i != '\n')
						.iterator();
					generatedPages = randomPages(chars);
					break;
				}
			}
			writeBook(generatedPages);
			
		}else
		{
			File f = fileSetting.getSelectedFile().toFile();
			if(f == null || !f.exists())
			{
				ChatUtils.error("No file selected.");
				setEnabled(false);
				return;
			}
			if(f.length() == 0)
			{
				ChatUtils.error("Selected file is empty.");
				setEnabled(false);
				return;
			}
			
			try
			{
				BookFileData importedBook = loadBookFile(f);
				writeBook(importedBook.pages(), importedBook.title(),
					importedBook.signed());
				
			}catch(IOException e)
			{
				ChatUtils.error("Failed to read file.");
			}
		}
	}
	
	private int findWritableBookSlot()
	{
		for(int i = 0; i < MC.player.getInventory().getContainerSize(); i++)
		{
			ItemStack s = MC.player.getInventory().getItem(i);
			if(s.is(Items.WRITABLE_BOOK) && !unsignedWrittenSlots.contains(i))
			{
				// Accept any writable book; server will accept updates
				return i;
			}
		}
		return -1;
	}
	
	private boolean isInMainHand(int slot)
	{
		return slot == MC.player.getInventory().getSelectedSlot();
	}
	
	private void moveToMainHand(int slot)
	{
		if(slot < 9)
			MC.player.getInventory().setSelectedSlot(slot);
		else
			swapWithMainHand(slot);
	}
	
	private void swapWithMainHand(int slot)
	{
		if(slot < 0 || slot == MC.player.getInventory().getSelectedSlot())
			return;
		
		IMC.getGameMode().windowClick_SWAP(InventoryUtils.toNetworkSlot(slot),
			MC.player.getInventory().getSelectedSlot());
		inventoryCooldown = 2;
	}
	
	private boolean tryCraftBookAndQuill()
	{
		if(MC.player == null)
			return false;
		
		if(MC.player.containerMenu != MC.player.inventoryMenu)
			return false;
		
		if(!MC.player.inventoryMenu.getCarried().isEmpty())
			return false;
		
		var im = IMC.getGameMode();
		
		for(int gridSlot = 1; gridSlot <= 4; gridSlot++)
			if(!MC.player.inventoryMenu.getSlot(gridSlot).getItem().isEmpty())
				im.windowClick_QUICK_MOVE(gridSlot);
			
		int bookSlot = InventoryUtils.indexOf(Items.BOOK, 36);
		int featherSlot = InventoryUtils.indexOf(Items.FEATHER, 36);
		int inkSlot = InventoryUtils.indexOf(Items.INK_SAC, 36);
		if(bookSlot == -1 || featherSlot == -1 || inkSlot == -1)
			return false;
		
		placeStackInGrid(im, bookSlot, 1);
		placeStackInGrid(im, featherSlot, 2);
		placeStackInGrid(im, inkSlot, 3);
		
		im.windowClick_PICKUP(0);
		
		int freeSlot = MC.player.getInventory().getFreeSlot();
		if(freeSlot != -1)
			im.windowClick_PICKUP(InventoryUtils.toNetworkSlot(freeSlot));
		
		for(int gridSlot = 1; gridSlot <= 3; gridSlot++)
			im.windowClick_QUICK_MOVE(gridSlot);
		
		inventoryCooldown = 2;
		return true;
	}
	
	private void placeStackInGrid(
		net.wurstclient.mixinterface.IMultiPlayerGameMode im, int inventorySlot,
		int gridSlot)
	{
		im.windowClick_PICKUP(InventoryUtils.toNetworkSlot(inventorySlot));
		im.windowClick_PICKUP(gridSlot);
	}
	
	private List<String> randomPages(PrimitiveIterator.OfInt chars)
	{
		ArrayList<String> outPages = new ArrayList<>();
		StringBuilder page = new StringBuilder(MAX_PAGE_JAVA_CHARS);
		int maxPages = Math.min(pages.getValueI(), MAX_BOOK_PAGES);
		int charsPerPage =
			Math.min(characters.getValueI(), MAX_PAGE_JAVA_CHARS);
		int pageIndex = 0;
		
		while(pageIndex != maxPages)
		{
			for(int i = 0; i < charsPerPage && chars.hasNext(); i++)
				page.appendCodePoint(chars.nextInt());
			
			if(!page.isEmpty())
			{
				outPages.add(page.toString());
				page.setLength(0);
			}
			
			pageIndex++;
		}
		
		return outPages;
	}
	
	private List<String> paperMcPages()
	{
		ArrayList<String> outPages = new ArrayList<>(100);
		PrimitiveIterator.OfInt oneByte = random.ints(0x21, 0x80).iterator();
		PrimitiveIterator.OfInt twoBytes =
			random.ints(0x0080, 0x0800).iterator();
		PrimitiveIterator.OfInt threeBytes =
			random.ints(0x0800, 0xD800).iterator();
		
		for(int page = 0; page < 50; page++)
		{
			StringBuilder sb = new StringBuilder(1024 * 2);
			appendCodePoints(sb, threeBytes, 1);
			appendCodePoints(sb, oneByte, 1023);
			outPages.add(sb.toString());
		}
		
		{
			StringBuilder sb = new StringBuilder(1024 * 2);
			appendCodePoints(sb, threeBytes, 110);
			appendCodePoints(sb, twoBytes, 1);
			appendCodePoints(sb, oneByte, 913);
			outPages.add(sb.toString());
		}
		
		for(int page = 51; page < 100; page++)
		{
			StringBuilder sb = new StringBuilder(1024 * 2);
			appendCodePoints(sb, threeBytes, 1024);
			outPages.add(sb.toString());
		}
		
		return outPages;
	}
	
	private void appendCodePoints(StringBuilder sb,
		PrimitiveIterator.OfInt chars, int count)
	{
		for(int i = 0; i < count; i++)
			sb.appendCodePoint(chars.nextInt());
	}
	
	private String randomMaxPayloadTitle()
	{
		StringBuilder sb = new StringBuilder(MAX_TITLE_JAVA_CHARS);
		PrimitiveIterator.OfInt chars =
			random.ints(MIN_THREE_BYTE_BMP, MAX_THREE_BYTE_BMP_EXCLUSIVE)
				.filter(
					i -> !Character.isWhitespace(i) && i != '\r' && i != '\n')
				.iterator();
		
		appendCodePoints(sb, chars, MAX_TITLE_JAVA_CHARS);
		return sb.toString();
	}
	
	private List<String> clampPages(List<String> pages)
	{
		ArrayList<String> outPages = new ArrayList<>();
		int maxPages = Math.min(pages.size(), MAX_BOOK_PAGES);
		
		for(int i = 0; i < maxPages; i++)
			outPages.add(clampToJavaChars(pages.get(i), MAX_PAGE_JAVA_CHARS));
		
		return outPages;
	}
	
	private String clampToJavaChars(String s, int maxJavaChars)
	{
		if(s.length() <= maxJavaChars)
			return s;
		
		int end = maxJavaChars;
		if(end > 0 && Character.isHighSurrogate(s.charAt(end - 1)))
			end--;
		
		return s.substring(0, end);
	}
	
	private List<String> filePages(String text)
	{
		ArrayList<String> pages = new ArrayList<>();
		ArrayList<String> lines;
		if(wordWrap.isChecked())
		{
			lines = new ArrayList<>();
			// Use MC font to split by width 114px
			for(var seq : MC.font.split(Component.literal(text), 114))
				lines.add(net.wurstclient.util.ChatUtils.getAsString(seq));
		}else
		{
			lines = new ArrayList<>();
			for(String s : text.split("\n"))
				lines.add(s);
		}
		
		StringBuilder cur = new StringBuilder();
		int lineIdx = 0;
		int pageIdx = 0;
		for(String l : lines)
		{
			if(cur.length() > 0)
				cur.append('\n');
			cur.append(l);
			lineIdx++;
			if(lineIdx >= 14)
			{
				pages.add(cur.toString());
				cur.setLength(0);
				lineIdx = 0;
				pageIdx++;
				if(pageIdx >= 100)
					break;
			}
		}
		if(cur.length() > 0 && pages.size() < 100)
			pages.add(cur.toString());
		return pages;
	}
	
	private void writeBook(List<String> pages)
	{
		writeBook(pages, null, sign.isChecked());
	}
	
	private void writeBook(List<String> pages, String titleOverride,
		boolean signedOverride)
	{
		if(MC.getConnection() == null)
			return;
		
		pages = clampPages(pages);
		long generatedObservedNs = System.nanoTime();
		forensicTransactionId = WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.beginBookBotForensicAttempt();
		forensicPayloadBytes = 0;
		if(WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.getItemForensicsEnabledSetting().isChecked()
			&& WurstClient.INSTANCE.getOtfs().packetToolsOtf
				.getLoggingEnabledSetting().isChecked())
			for(String page : pages)
				forensicPayloadBytes +=
					page.getBytes(StandardCharsets.UTF_8).length;
			
		// Title
		String title = titleOverride != null ? titleOverride : name.getValue();
		if(title == null || title.isBlank())
			title = "Untitled";
		if(titleOverride == null && appendCount.isChecked() && bookCount != 0)
			title += " #" + bookCount;
		
		if(titleOverride == null && signedOverride
			&& highByteSignedName.isChecked()
			&& mode.getSelected() == Mode.RANDOM
			&& randomType.getSelected() == RandomType.UTF8)
			title = randomMaxPayloadTitle();
		
		title = clampToJavaChars(title, MAX_TITLE_JAVA_CHARS);
		
		// ### MODIFIED ### Do not predict server-confirmed written-book content
		// locally.
		int writtenSlot = MC.player.getInventory().getSelectedSlot();
		
		logBookPayloadEstimate(pages, title, signedOverride);
		
		saveBookToFile(pages, title, signedOverride);
		
		boolean shelfOutput =
			afterWrite.getSelected() == AfterWrite.PLACE_ON_SHELF;
		// Prepare the target and content fingerprint before submitting the
		// edit.
		if(shelfOutput
			&& !prepareShelfTransfer(pages, title, writtenSlot, signedOverride))
		{
			setEnabled(false);
			return;
		}
		ServerboundEditBookPacket editPacket =
			new ServerboundEditBookPacket(writtenSlot, pages,
				signedOverride ? Optional.of(title) : Optional.empty());
		if(shelfOutput)
			editSubmittedNanos = System.nanoTime();
		forensicTransactionId = WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.recordForensicActionRequested(editPacket, "BookBot.edit",
				forensicTransactionId);
		if(forensicTransactionId != null)
			logShelfTransferEvent("book_generated",
				Map.of("bookGeneratedObservedNs", generatedObservedNs,
					"pageCount", pages.size(), "shelfTransferRequested",
					shelfOutput));
		MC.getConnection().send(editPacket);
		
		if(shelfOutput)
		{
			bookCount++;
			if(activeShelfStrategy == ShelfTransferStrategy.IMMEDIATE)
				attemptShelfTransfer();
			logShelfTransferEvent("book_edit_submitted",
				Map.of("contentSha256", submittedContentHash, "inventorySlot",
					writtenSlot, "pageCount", submittedPageCount, "signed",
					submittedSigned, "strategy", activeShelfStrategy.name(),
					"editSubmittedNs", editSubmittedNanos,
					"packetObjectCorrelationId",
					Integer.toHexString(System.identityHashCode(editPacket))));
			ChatUtils.message("[BookBot] Book edit submitted."
				+ (activeShelfStrategy == ShelfTransferStrategy.CONFIRMED_CONTENT
					? " Waiting for server content." : ""));
			return;
		}else if(afterWrite.getSelected() == AfterWrite.DROP)
		{
			dropMainHand();
		}else if(!signedOverride)
		{
			int selectedSlot = MC.player.getInventory().getSelectedSlot();
			unsignedWrittenSlots.add(selectedSlot);
			
			int nextSlot = findWritableBookSlot();
			if(nextSlot != -1)
			{
				swapWithMainHand(nextSlot);
				unsignedWrittenSlots.add(nextSlot);
				unsignedWrittenSlots.remove(selectedSlot);
			}
		}
		
		bookCount++;
	}
	
	// ### ADDED ### Non-blocking transfer flow; never reports a send as
	// success.
	private boolean prepareShelfTransfer(List<String> pages, String title,
		int slot, boolean signed)
	{
		submittedContentHash = (String)BookContentSummaries
			.fromStrings(pages, title, MC.player.getGameProfile().name(),
				signed ? "SIGNED" : "UNSIGNED", true)
			.get("combinedContentSha256");
		submittedPageCount = pages.size();
		submittedSigned = signed;
		activeShelfStrategy = shelfTransferStrategy.getSelected();
		submittedSlot = slot;
		editSubmittedNanos = 0;
		transferAttempted = false;
		transferShelfPos = null;
		transferShelfSlot = -1;
		preparedShelfTarget = findShelfTarget();
		if(preparedShelfTarget == null)
		{
			ChatUtils.message(
				"[BookBot] Shelf transfer stopped: " + lastShelfRejection);
			logShelfTransferEvent("shelf_transfer_failed",
				Map.of("reason", lastShelfRejection));
			return false;
		}
		shelfTransferState = ShelfTransferState.WAITING_CONTENT;
		return true;
	}
	
	private void progressShelfTransfer()
	{
		if(MC.getConnection() == null || MC.player == null || MC.level == null)
		{
			ChatUtils.message(
				"[BookBot] Shelf transfer stopped; server outcome is indeterminate.");
			clearShelfTransfer();
			return;
		}
		switch(shelfTransferState)
		{
			case WAITING_CONTENT ->
			{
				if(System.nanoTime() - editSubmittedNanos > 10_000_000_000L)
				{
					ChatUtils.message(
						"[BookBot] No matching book confirmation received.");
					logShelfTransferEvent("no_matching_book_response",
						Map.of("reason", "CONFIRMATION_TIMEOUT"));
					shelfTransferState = ShelfTransferState.FAILED;
				}
			}
			case VERIFYING_STORAGE -> verifyShelfTransfer();
			case FAILED, INDETERMINATE -> setEnabled(false);
			case STORED -> clearShelfTransfer();
			default -> clearShelfTransfer();
		}
	}
	
	/** Runs on the client thread before the next queued inventory packet. */
	public void onInventoryPacketApplied(Packet<?> packet)
	{
		if(!isEnabled() || !MC.isSameThread() || MC.player == null
			|| shelfTransferState != ShelfTransferState.WAITING_CONTENT
			|| activeShelfStrategy != ShelfTransferStrategy.CONFIRMED_CONTENT)
			return;
		ItemStack supplied = updatedBookSlot(packet);
		if(supplied == null)
			return;
		if(supplied.isEmpty())
		{
			ChatUtils
				.message("[BookBot] Server removed the book before transfer.");
			logShelfTransferEvent("book_removed_before_transfer",
				Map.of("inventorySlot", submittedSlot, "packetClass",
					packet.getClass().getName()));
			shelfTransferState = ShelfTransferState.FAILED;
			return;
		}
		if(!BookContentSummaries.matchesStack(supplied, submittedContentHash,
			submittedPageCount, submittedSigned))
		{
			logShelfTransferEvent("book_confirmation_mismatch",
				Map.of("inventorySlot", submittedSlot, "expectedPageCount",
					submittedPageCount, "expectedContentSha256",
					submittedContentHash, "serverContent",
					String.valueOf(BookContentSummaries.fromStack(supplied))));
			return;
		}
		ItemStack applied = MC.player.getInventory().getItem(submittedSlot);
		if(applied != supplied && !BookContentSummaries.matchesStack(applied,
			submittedContentHash, submittedPageCount, submittedSigned))
			return;
		long confirmedNs = System.nanoTime();
		// Dispatch immediately at handler tail, before a subsequent clear
		// packet.
		attemptShelfTransfer(true);
		logShelfTransferEvent("book_content_confirmed",
			Map.of("contentConfirmedNs", confirmedNs, "inventorySlot",
				submittedSlot, "contentSha256", submittedContentHash,
				"packetObjectCorrelationId",
				Integer.toHexString(System.identityHashCode(packet))));
	}
	
	private ItemStack updatedBookSlot(Packet<?> packet)
	{
		if(packet instanceof ClientboundSetPlayerInventoryPacket p)
			return p.slot() == submittedSlot ? p.contents() : null;
		int containerId;
		int rawSlot;
		if(packet instanceof ClientboundContainerSetSlotPacket p)
		{
			containerId = p.getContainerId();
			rawSlot = p.getSlot();
			if(containerId == -2)
				return rawSlot == submittedSlot ? p.getItem() : null;
			var menu = containerId == 0 ? MC.player.inventoryMenu
				: MC.player.containerMenu;
			if(menu.containerId != containerId || rawSlot < 0
				|| rawSlot >= menu.slots.size())
				return null;
			var slot = menu.getSlot(rawSlot);
			return slot.container == MC.player.getInventory()
				&& slot.getContainerSlot() == submittedSlot ? p.getItem()
					: null;
		}
		if(packet instanceof ClientboundContainerSetContentPacket p)
		{
			var menu = p.containerId() == 0 ? MC.player.inventoryMenu
				: MC.player.containerMenu;
			if(menu.containerId != p.containerId())
				return null;
			for(var slot : menu.slots)
				if(slot.container == MC.player.getInventory()
					&& slot.getContainerSlot() == submittedSlot
					&& slot.index < p.items().size())
					return p.items().get(slot.index);
		}
		return null;
	}
	
	private void attemptShelfTransfer()
	{
		attemptShelfTransfer(false);
	}
	
	private void attemptShelfTransfer(boolean confirmedContent)
	{
		if(transferAttempted)
			return;
		ShelfTarget target = preparedShelfTarget;
		if(target != null && MC.level
			.getBlockEntity(target.position) instanceof ShelfBlockEntity shelf)
			target = resolveShelfTarget(shelf, target.slot, false);
		else
			target = null;
		if(target == null)
		{
			ChatUtils.message("[BookBot] Prepared shelf is no longer usable: "
				+ lastShelfRejection);
			logShelfTransferEvent("shelf_transfer_failed",
				Map.of("reason", "NO_REACHABLE_EMPTY_UNPOWERED_SHELF"));
			shelfTransferState = ShelfTransferState.FAILED;
			return;
		}
		if(submittedSlot < 0
			|| submittedSlot >= MC.player.getInventory().getContainerSize())
		{
			ChatUtils
				.message("[BookBot] Book is no longer in the expected slot.");
			shelfTransferState = ShelfTransferState.FAILED;
			return;
		}
		ItemStack hand = MC.player.getInventory().getItem(submittedSlot);
		if(hand.isEmpty()
			|| (!hand.is(Items.WRITABLE_BOOK) && !hand.is(Items.WRITTEN_BOOK))
			|| (activeShelfStrategy == ShelfTransferStrategy.CONFIRMED_CONTENT
				&& !confirmedContent))
		{
			ChatUtils.message(
				"[BookBot] Confirmed book was removed before transfer.");
			shelfTransferState = ShelfTransferState.FAILED;
			return;
		}
		int previousSelectedSlot = MC.player.getInventory().getSelectedSlot();
		if(submittedSlot != previousSelectedSlot)
		{
			if(submittedSlot < 9)
				MC.player.getInventory().setSelectedSlot(submittedSlot);
			else
			{
				ChatUtils.message(
					"[BookBot] Confirmed book is outside the hotbar; transfer cancelled.");
				shelfTransferState = ShelfTransferState.FAILED;
				return;
			}
		}
		transferShelfPos = target.position;
		transferShelfSlot = target.slot;
		transferAttempted = true;
		shelfTransferState = ShelfTransferState.TRANSFER_REQUESTED;
		interactionRequestedNanos = System.nanoTime();
		// Block interaction only: do not fall back to opening/using the book.
		var diagnosticTools = WurstClient.INSTANCE.getOtfs().packetToolsOtf;
		var previousDiagnostic =
			diagnosticTools.beginForensicTransaction(forensicTransactionId);
		net.minecraft.world.InteractionResult result;
		try
		{
			result = MC.gameMode.useItemOn(MC.player, InteractionHand.MAIN_HAND,
				target.hit);
		}finally
		{
			diagnosticTools.endForensicTransaction(previousDiagnostic);
		}
		if(previousSelectedSlot != submittedSlot)
			MC.player.getInventory().setSelectedSlot(previousSelectedSlot);
		shelfTransferState = ShelfTransferState.VERIFYING_STORAGE;
		logShelfTransferEvent("shelf_interaction_requested",
			Map.of("contentSha256", submittedContentHash, "inventorySlot",
				submittedSlot, "shelfPosition", target.position.toShortString(),
				"shelfSlot", target.slot, "strategy",
				activeShelfStrategy.name(), "editToInteractionNanos",
				interactionRequestedNanos - editSubmittedNanos,
				"interactionRequestedNs", interactionRequestedNanos,
				"clientInteractionResult", result.toString()));
		ChatUtils.message("[BookBot] Shelf located at "
			+ target.position.toShortString() + "; interaction requested.");
	}
	
	private ShelfTarget findShelfTarget()
	{
		Vec3 eye = MC.player.getEyePosition();
		double reach = MC.player.blockInteractionRange();
		Map<BlockPos, ShelfBlockEntity> shelves = new LinkedHashMap<>();
		lastShelfRejection = "No nearby Shelf is loaded.";
		if(MC.hitResult instanceof BlockHitResult hit && MC.level
			.getBlockState(hit.getBlockPos()).getBlock() instanceof ShelfBlock)
		{
			if(MC.level.getBlockEntity(
				hit.getBlockPos()) instanceof ShelfBlockEntity shelf)
				shelves.put(shelf.getBlockPos(), shelf);
			else
			{
				lastShelfRejection =
					"The targeted Shelf has no client-visible block entity.";
				logShelfTransferEvent("shelf_candidate",
					Map.of("position", hit.getBlockPos().toShortString(),
						"rejectionReason", "BLOCK_ENTITY_UNAVAILABLE"));
			}
		}
		ChunkUtils.getLoadedBlockEntities().forEach(blockEntity -> {
			if(blockEntity instanceof ShelfBlockEntity shelf
				&& eye.distanceToSqr(
					Vec3.atCenterOf(shelf.getBlockPos())) <= (reach + 2)
						* (reach + 2))
				shelves.putIfAbsent(shelf.getBlockPos(), shelf);
		});
		List<ShelfTarget> candidates = new ArrayList<>();
		for(ShelfBlockEntity shelf : shelves.values())
			for(int slot = 0; slot < shelf.getItems().size(); slot++)
			{
				ShelfTarget target = resolveShelfTarget(shelf, slot, true);
				if(target != null)
					candidates.add(target);
			}
		return candidates.stream().sorted((a, b) -> {
			if(a.crosshair != b.crosshair)
				return a.crosshair ? -1 : 1;
			return Double.compare(a.distanceSquared, b.distanceSquared);
		}).findFirst().orElse(null);
	}
	
	private ShelfTarget resolveShelfTarget(ShelfBlockEntity shelf, int slot,
		boolean emitDiagnostics)
	{
		var state = MC.level.getBlockState(shelf.getBlockPos());
		Map<String, Object> diagnostic = new LinkedHashMap<>();
		diagnostic.put("position", shelf.getBlockPos().toShortString());
		diagnostic.put("blockId",
			BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
		diagnostic.put("slot", slot);
		diagnostic.put("occupancy",
			shelf.getItems().stream().map(item -> item.getCount()).toList());
		Vec3 eye = MC.player.getEyePosition();
		double reach = MC.player.blockInteractionRange();
		diagnostic.put("eyePosition",
			Map.of("x", eye.x, "y", eye.y, "z", eye.z));
		diagnostic.put("interactionRange", reach);
		diagnostic.put("distanceToBlockCentre",
			eye.distanceTo(Vec3.atCenterOf(shelf.getBlockPos())));
		if(!(state.getBlock() instanceof ShelfBlock block))
			return rejectShelfCandidate(diagnostic, "SHELF_BLOCK_REPLACED");
		Direction facing = state.getValue(ShelfBlock.FACING);
		diagnostic.put("facing", facing.getName());
		diagnostic.put("powered", state.getValue(ShelfBlock.POWERED));
		if(state.getValue(ShelfBlock.POWERED))
			return rejectShelfCandidate(diagnostic, "POWERED_MULTI_SLOT_SWAP");
		if(slot < 0 || slot >= shelf.getItems().size()
			|| !shelf.getItem(slot).isEmpty())
			return rejectShelfCandidate(diagnostic,
				"DESTINATION_SLOT_OCCUPIED");
		BlockHitResult hit = null;
		boolean crosshair = MC.hitResult instanceof BlockHitResult current
			&& current.getType() == HitResult.Type.BLOCK
			&& current.getBlockPos().equals(shelf.getBlockPos());
		if(crosshair)
		{
			BlockHitResult current = (BlockHitResult)MC.hitResult;
			if(block.getHitSlot(current, facing).orElse(-1) == slot)
				hit = current;
		}
		if(hit == null)
		{
			Vec3 aim = ShelfTransferTargeting.aimPoint(state,
				shelf.getBlockPos(), slot, MC.level);
			if(aim == null)
				return rejectShelfCandidate(diagnostic,
					"NO_SLOT_INTERACTION_SURFACE");
			diagnostic.put("aimPosition",
				Map.of("x", aim.x, "y", aim.y, "z", aim.z));
			hit = MC.level.clip(new ClipContext(eye, aim,
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
		}else
		{
			// Recheck the crosshair ray against the current world and eye
			// position.
			Vec3 end = hit.getLocation().add(-facing.getStepX() * 0.0001, 0,
				-facing.getStepZ() * 0.0001);
			hit = MC.level.clip(new ClipContext(eye, end,
				ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, MC.player));
		}
		diagnostic.put("raycastType", hit.getType().name());
		diagnostic.put("raycastBlock", hit.getBlockPos().toShortString());
		diagnostic.put("hitFace", hit.getDirection().getName());
		diagnostic.put("hitInside", hit.isInside());
		diagnostic.put("hitPosition", Map.of("x", hit.getLocation().x, "y",
			hit.getLocation().y, "z", hit.getLocation().z));
		double distanceSquared = eye.distanceToSqr(hit.getLocation());
		diagnostic.put("hitDistance", Math.sqrt(distanceSquared));
		if(hit.getType() != HitResult.Type.BLOCK)
			return rejectShelfCandidate(diagnostic,
				"RAY_MISSED_SHELF_GEOMETRY");
		if(!hit.getBlockPos().equals(shelf.getBlockPos()))
			return rejectShelfCandidate(diagnostic, "LINE_OF_SIGHT_BLOCKED");
		if(distanceSquared > reach * reach)
			return rejectShelfCandidate(diagnostic,
				"OUTSIDE_INTERACTION_RANGE");
		int hitSlot = block.getHitSlot(hit, facing).orElse(-1);
		diagnostic.put("vanillaHitSlot", hitSlot);
		if(hit.getDirection() != facing)
			return rejectShelfCandidate(diagnostic,
				"HIT_IS_NOT_SHELF_FRONT_FACE");
		if(hitSlot != slot)
			return rejectShelfCandidate(diagnostic,
				"RAY_SELECTED_DIFFERENT_SLOT");
		diagnostic.put("rejectionReason", "ACCEPTED");
		if(emitDiagnostics)
			logShelfTransferEvent("shelf_candidate", diagnostic);
		return new ShelfTarget(shelf.getBlockPos().immutable(), slot, hit,
			distanceSquared, crosshair);
	}
	
	private ShelfTarget rejectShelfCandidate(Map<String, Object> diagnostic,
		String reason)
	{
		diagnostic.put("rejectionReason", reason);
		lastShelfRejection =
			"Shelf at " + diagnostic.get("position") + ": " + reason;
		logShelfTransferEvent("shelf_candidate", diagnostic);
		return null;
	}
	
	public void onShelfDataApplied(ClientboundBlockEntityDataPacket packet)
	{
		if(!isEnabled() || !MC.isSameThread() || MC.level == null
			|| shelfTransferState != ShelfTransferState.VERIFYING_STORAGE
			|| !packet.getPos().equals(transferShelfPos)
			|| !(MC.level.getBlockEntity(
				transferShelfPos) instanceof ShelfBlockEntity shelf)
			|| transferShelfSlot < 0
			|| transferShelfSlot >= shelf.getItems().size())
			return;
		ItemStack stored = shelf.getItem(transferShelfSlot);
		Map<String, Object> observation = new LinkedHashMap<>();
		observation.put("shelfPosition", transferShelfPos.toShortString());
		observation.put("shelfSlot", transferShelfSlot);
		observation.put("submittedContentSha256", submittedContentHash);
		observation.put("serverBlockEntityTagPresent", packet.getTag() != null);
		observation.put("packetObjectCorrelationId",
			Integer.toHexString(System.identityHashCode(packet)));
		observation.put("captureStage", "SERVER_BLOCK_ENTITY_HANDLER_TAIL");
		observation.put("storedItem",
			ItemStackForensics.capture(stored, true, false, 0).details());
		if(stored.isEmpty())
			observation.put("outcome", "SHELF_SLOT_STILL_EMPTY");
		else if(BookContentSummaries.matchesStack(stored, submittedContentHash,
			submittedPageCount, submittedSigned))
		{
			observation.put("outcome", "SERVER_CONFIRMED_MATCHING_CONTENT");
			shelfTransferState = ShelfTransferState.STORED;
			ChatUtils.message(
				"[BookBot] Server confirmed shelf storage with matching "
					+ submittedPageCount + " pages.");
		}else
		{
			Map<String, Object> summary =
				BookContentSummaries.fromStack(stored);
			boolean emptyBook = summary != null
				&& Integer.valueOf(0).equals(summary.get("pageCount"));
			observation.put("outcome", emptyBook ? "EMPTY_BOOK_STORED"
				: "STORED_WITH_DIFFERENT_CONTENT");
			shelfTransferState = ShelfTransferState.INDETERMINATE;
			ChatUtils.message(emptyBook
				? "[BookBot] Server stored an empty book on the shelf."
				: "[BookBot] Server shelf contents differ from the submitted book.");
		}
		logShelfTransferEvent("shelf_server_update", observation);
	}
	
	private void verifyShelfTransfer()
	{
		if(System.nanoTime() - interactionRequestedNanos > 5_000_000_000L)
		{
			ChatUtils.message(
				"[BookBot] No matching server shelf update received; storage is unverified.");
			logShelfTransferEvent("shelf_transfer_unverified",
				Map.of("shelfPosition", transferShelfPos.toShortString(),
					"shelfSlot", transferShelfSlot, "inventoryItem",
					ItemStackForensics.capture(
						MC.player.getInventory().getItem(submittedSlot), true,
						false, 0).details()));
			shelfTransferState = ShelfTransferState.INDETERMINATE;
		}
	}
	
	private void clearShelfTransfer()
	{
		shelfTransferState = ShelfTransferState.IDLE;
		submittedContentHash = null;
		submittedSlot = -1;
		transferShelfPos = null;
		transferShelfSlot = -1;
		editSubmittedNanos = 0;
		transferAttempted = false;
		submittedPageCount = 0;
		submittedSigned = false;
		preparedShelfTarget = null;
		activeShelfStrategy = null;
		interactionRequestedNanos = 0;
	}
	
	private record ShelfTarget(BlockPos position, int slot, BlockHitResult hit,
		double distanceSquared, boolean crosshair)
	{}
	
	private void logShelfTransferEvent(String event, Map<String, Object> fields)
	{
		Map<String, Object> diagnostic = new java.util.LinkedHashMap<>(fields);
		if(forensicTransactionId != null)
			diagnostic.put("transactionId", forensicTransactionId);
		diagnostic.put("payloadBytes", forensicPayloadBytes);
		if(submittedContentHash != null)
			diagnostic.putIfAbsent("contentSha256", submittedContentHash);
		if(MC.level != null)
			diagnostic.put("dimension",
				MC.level.dimension().identifier().toString());
		if(MC.player != null)
			diagnostic.put("playerPosition", Map.of("x", MC.player.getX(), "y",
				MC.player.getY(), "z", MC.player.getZ()));
		WurstClient.INSTANCE.getOtfs().packetToolsOtf
			.recordForensicsExternalEvent("BookBot." + event, diagnostic);
	}
	
	private void saveBookToFile(List<String> pages, String title,
		boolean signed)
	{
		if(!saveToFile.isChecked())
			return;
		
		try
		{
			Path folder = fileSetting.getFolder();
			Files.createDirectories(folder);
			
			String fileName = AutoChatPromptManager.sanitizeFileName(title);
			fileName = String.format("%s_%03d", fileName, bookCount + 1);
			
			JsonObject json = new JsonObject();
			json.addProperty("version", BOOKBOT_EXPORT_VERSION);
			json.addProperty("title", title);
			json.addProperty("signed", signed);
			JsonArray jsonPages = new JsonArray();
			for(String page : pages)
				jsonPages.add(page);
			json.add("pages", jsonPages);
			
			Path file = folder.resolve(fileName + ".bookbot.json");
			Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
			
		}catch(IOException e)
		{
			ChatUtils.error("Failed to save book to file.");
		}
	}
	
	private BookFileData loadBookFile(File file) throws IOException
	{
		String content =
			Files.readString(file.toPath(), StandardCharsets.UTF_8);
		String trimmed = content.stripLeading();
		if(trimmed.startsWith("{"))
		{
			try
			{
				JsonObject json =
					JsonParser.parseString(content).getAsJsonObject();
				if(json.has("version")
					&& json.get("version").getAsInt() == BOOKBOT_EXPORT_VERSION
					&& json.has("pages"))
				{
					ArrayList<String> pages = new ArrayList<>();
					for(var element : json.getAsJsonArray("pages"))
						pages.add(element.getAsString());
					
					String title = json.has("title")
						? json.get("title").getAsString() : name.getValue();
					boolean signed =
						json.has("signed") && json.get("signed").getAsBoolean();
					return new BookFileData(pages, title, signed);
				}
				
			}catch(JsonSyntaxException | IllegalStateException e)
			{
				// Fall back to plain text import below.
			}
		}
		
		return new BookFileData(filePages(content), null, sign.isChecked());
	}
	
	private void dropMainHand()
	{
		if(MC.getConnection() == null)
			return;
		
		int selectedSlot = MC.player.getInventory().getSelectedSlot();
		
		MC.getConnection()
			.send(new ServerboundPlayerActionPacket(
				ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS,
				BlockPos.ZERO, Direction.DOWN));
		
		MC.player.getInventory().setItem(selectedSlot, ItemStack.EMPTY);
		unsignedWrittenSlots.remove(selectedSlot);
		inventoryCooldown = 2;
	}
	
	private void logBookPayloadEstimate(List<String> pages, String title,
		boolean signed)
	{
		long totalJavaChars = 0;
		long totalCodePoints = 0;
		long pageUtf8Bytes = 0;
		for(String page : pages)
		{
			totalJavaChars += page.length();
			totalCodePoints += page.codePointCount(0, page.length());
			pageUtf8Bytes += page.getBytes(StandardCharsets.UTF_8).length;
		}
		
		long titleUtf8Bytes =
			signed ? title.getBytes(StandardCharsets.UTF_8).length : 0;
		double totalKb = (pageUtf8Bytes + titleUtf8Bytes) / 1024D;
		ChatUtils.message("Book payload estimate: pages=" + pages.size()
			+ ", javaChars=" + totalJavaChars + ", codePoints="
			+ totalCodePoints + ", pagesUtf8Bytes=" + pageUtf8Bytes
			+ ", titleUtf8Bytes=" + titleUtf8Bytes + ", totalKB="
			+ String.format("%.2f", totalKb) + " (estimate)");
	}
}
