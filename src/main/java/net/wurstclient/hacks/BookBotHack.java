/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.PrimitiveIterator;
import java.util.Random;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
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
	private enum Mode
	{
		FILE,
		RANDOM
	}
	
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"What kind of text to write.", Mode.values(), Mode.RANDOM);
	
	private final SliderSetting pages = new SliderSetting("Pages",
		"Number of pages to write per book (Random mode).", 50, 1, 100, 1,
		ValueDisplay.INTEGER);
	
	private final CheckboxSetting onlyAscii = new CheckboxSetting("ASCII only",
		"Only uses characters from the ASCII charset.", false);
	
	private final SliderSetting delay = new SliderSetting("Delay (ticks)",
		"Delay between writing books in ticks.", 20, 1, 200, 1,
		ValueDisplay.INTEGER);
	
	private final CheckboxSetting sign =
		new CheckboxSetting("Sign", "Whether to sign the book.", true);
	
	private final TextFieldSetting name = new TextFieldSetting("Name",
		"The name you want to give your books.", "I Love Cevapi!");
	
	private final CheckboxSetting appendCount = new CheckboxSetting(
		"Append count", "Append sequential number to the title.", true);
	
	private final CheckboxSetting wordWrap = new CheckboxSetting("Word wrap",
		"Prevents words from being cut in the middle of lines.", true);
	
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
	private Random random;
	
	public BookBotHack()
	{
		super("BookBot");
		setCategory(Category.ITEMS);
		
		addSetting(mode);
		addSetting(pages);
		addSetting(onlyAscii);
		addSetting(delay);
		addSetting(sign);
		addSetting(name);
		addSetting(appendCount);
		addSetting(wordWrap);
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
		bookCount = 0;
		
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
		// Find an empty writable book in inventory
		int slot = findWritableBookSlot();
		if(slot == -1)
		{
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
			int origin = onlyAscii.isChecked() ? 0x21 : 0x0800;
			int bound = onlyAscii.isChecked() ? 0x7E : 0x10FFFF;
			PrimitiveIterator.OfInt chars = random.ints(origin, bound)
				.filter(
					i -> !Character.isWhitespace(i) && i != '\r' && i != '\n')
				.iterator();
			writeBook(randomPages(chars));
			
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
			
			try(BufferedReader br = new BufferedReader(new FileReader(f)))
			{
				StringBuilder sb = new StringBuilder();
				String line;
				while((line = br.readLine()) != null)
					sb.append(line).append('\n');
				writeBook(filePages(sb.toString()));
				
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
			if(s.is(Items.WRITABLE_BOOK))
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
			InventoryUtils.selectItem(slot);
	}
	
	private List<String> randomPages(PrimitiveIterator.OfInt chars)
	{
		ArrayList<String> outPages = new ArrayList<>();
		StringBuilder page = new StringBuilder();
		StringBuilder line = new StringBuilder();
		int maxPages = pages.getValueI();
		int lineWidth = 0;
		int linesOnPage = 0;
		
		while(chars.hasNext() && outPages.size() < maxPages)
		{
			int cp = chars.nextInt();
			if(cp == '\r' || cp == '\n')
				continue; // skip explicit newlines in random mode
				
			// width of this code point in pixels
			String s = new String(Character.toChars(cp));
			int w = MC.font.width(s);
			
			if(lineWidth + w <= 114)
			{
				line.append(s);
				lineWidth += w;
			}else
			{
				// finish current line
				page.append(line).append('\n');
				line.setLength(0);
				lineWidth = 0;
				linesOnPage++;
				
				if(linesOnPage >= 14)
				{
					// finish page
					outPages.add(page.toString());
					page.setLength(0);
					linesOnPage = 0;
					
					if(outPages.size() >= maxPages)
						break;
				}
				
				// start next line with current char if it fits (it should,
				// unless glyph is wider than 114px)
				if(w <= 114)
				{
					line.append(s);
					lineWidth = w;
				}
			}
		}
		
		// flush last line
		if(line.length() > 0)
		{
			page.append(line);
			linesOnPage++;
		}
		
		// pad remaining lines with explicit newlines to close the page properly
		if(page.length() > 0 && outPages.size() < maxPages)
		{
			// ensure max 14 lines per page
			while(linesOnPage > 0 && linesOnPage < 14)
			{
				page.append('\n');
				linesOnPage++;
			}
			outPages.add(page.toString());
		}
		
		return outPages;
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
		if(MC.getConnection() == null)
			return;
		
		// Title
		String title = name.getValue();
		if(appendCount.isChecked() && bookCount != 0)
			title += " #" + bookCount;
		
		// Local visual signing skipped; server will apply on success.
		
		// Send packet
		MC.getConnection()
			.send(new ServerboundEditBookPacket(
				MC.player.getInventory().getSelectedSlot(), pages,
				sign.isChecked() ? Optional.of(title) : Optional.empty()));
		
		bookCount++;
	}
}
