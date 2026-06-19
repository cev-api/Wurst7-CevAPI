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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.Category;
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
	
	private final CheckboxSetting dropAfterWrite =
		new CheckboxSetting("Drop after write",
			"Drops each finished book on the ground right after it's written.",
			false);
	
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
		addSetting(dropAfterWrite);
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
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		unsignedWrittenSlots.clear();
	}
	
	@Override
	public void onUpdate()
	{
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
		
		IMC.getInteractionManager().windowClick_SWAP(
			InventoryUtils.toNetworkSlot(slot),
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
		
		var im = IMC.getInteractionManager();
		
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
		
		// Build filtered pages for the component
		ArrayList<Filterable<Component>> filteredPages = new ArrayList<>();
		for(String page : pages)
			filteredPages.add(Filterable.passThrough(Component.literal(page)));
		
		// Write data to book (client-side component)
		MC.player.getMainHandItem().set(DataComponents.WRITTEN_BOOK_CONTENT,
			new WrittenBookContent(Filterable.passThrough(title),
				MC.player.getGameProfile().name(), 0, filteredPages, true));
		
		logBookPayloadEstimate(pages, title, signedOverride);
		
		saveBookToFile(pages, title, signedOverride);
		
		// Send packet
		MC.getConnection()
			.send(new ServerboundEditBookPacket(
				MC.player.getInventory().getSelectedSlot(), pages,
				signedOverride ? Optional.of(title) : Optional.empty()));
		
		if(dropAfterWrite.isChecked())
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
