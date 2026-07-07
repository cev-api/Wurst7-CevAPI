/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.FileSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;

@SearchTags({"chat spam", "chatspam", "spam chat", "message spam"})
@DontSaveState
public final class ChatSpamHack extends Hack implements UpdateListener
{
	private static final int CHAT_LIMIT = 256;
	
	private final TextFieldSetting message = new TextFieldSetting("Message",
		"Chat message to send repeatedly when file mode is disabled.", "",
		s -> s != null && s.length() <= CHAT_LIMIT);
	
	private final SliderSetting amount =
		new SliderSetting("Amount", "How many times to send the message.", 10,
			1, 10000, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting pause =
		new SliderSetting("Pause", "Seconds to wait between messages.", 1, 0,
			60, 0.05, ValueDisplay.DECIMAL.withSuffix("s"));
	
	private final CheckboxSetting useFile = new CheckboxSetting("Use file",
		"Send each line from the selected text file instead of the Message field.",
		false);
	
	private final FileSetting textFile = new FileSetting("File",
		"Select a text file to read line by line.", "chatspam", folder -> {
			try
			{
				Files.createDirectories(folder);
				Path file = folder.resolve("messages.txt");
				if(Files.notExists(file))
				{
					Files.writeString(file,
						"Hello from ChatSpam!\n" + "This is the second line.\n",
						StandardCharsets.UTF_8);
				}
			}catch(IOException e)
			{
				throw new RuntimeException(e);
			}
		});
	
	private final ArrayList<String> pendingMessages = new ArrayList<>();
	private int currentIndex;
	private int timer;
	private boolean fileMode;
	
	public ChatSpamHack()
	{
		super("ChatSpam");
		setCategory(Category.CHAT);
		addSetting(message);
		addSetting(amount);
		addSetting(pause);
		addSetting(useFile);
		addSetting(textFile);
	}
	
	@Override
	public String getRenderName()
	{
		if(!isEnabled())
			return getName();
		
		int total = getTotalMessages();
		return getName() + " [" + Math.min(currentIndex, total) + "/" + total
			+ "]";
	}
	
	@Override
	public String getStatusText()
	{
		if(!isEnabled())
			return null;
		
		int total = getTotalMessages();
		return Math.min(currentIndex, total) + "/" + total;
	}
	
	@Override
	protected void onEnable()
	{
		timer = 0;
		currentIndex = 0;
		fileMode = useFile.isChecked();
		pendingMessages.clear();
		
		if(fileMode)
		{
			if(!loadMessagesFromFile())
			{
				setEnabled(false);
				return;
			}
		}else
		{
			String configuredMessage = normalizeMessage(message.getValue());
			if(configuredMessage.isEmpty())
			{
				ChatUtils.error("ChatSpam: enter a message first.");
				setEnabled(false);
				return;
			}
			
			pendingMessages.add(configuredMessage);
		}
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		pendingMessages.clear();
		currentIndex = 0;
		timer = 0;
		fileMode = false;
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.getConnection() == null)
			return;
		
		if(timer > 0)
		{
			timer--;
			return;
		}
		
		int total = getTotalMessages();
		if(currentIndex >= total)
		{
			setEnabled(false);
			return;
		}
		
		String chatMessage = fileMode ? pendingMessages.get(currentIndex)
			: pendingMessages.get(0);
		if(chatMessage == null || chatMessage.isBlank())
		{
			currentIndex++;
			return;
		}
		
		MC.getConnection().sendChat(chatMessage);
		currentIndex++;
		
		if(currentIndex >= total)
		{
			setEnabled(false);
			return;
		}
		
		timer = getPauseTicks();
	}
	
	private int getTotalMessages()
	{
		if(fileMode)
			return pendingMessages.size();
		
		return amount.getValueI();
	}
	
	private int getPauseTicks()
	{
		return Math.max(0, (int)Math.round(pause.getValue() * 20));
	}
	
	private boolean loadMessagesFromFile()
	{
		Path selectedFile = textFile.getSelectedFile();
		if(selectedFile == null)
		{
			ChatUtils.error("ChatSpam: no file selected.");
			return false;
		}
		
		List<String> lines;
		try
		{
			lines = Files.readAllLines(selectedFile, StandardCharsets.UTF_8);
		}catch(IOException e)
		{
			ChatUtils.error(
				"ChatSpam: couldn't read " + selectedFile.getFileName() + ".");
			return false;
		}
		
		for(String line : lines)
		{
			String normalized = normalizeMessage(line);
			if(!normalized.isEmpty())
				pendingMessages.add(normalized);
		}
		
		if(pendingMessages.isEmpty())
		{
			ChatUtils.error("ChatSpam: the selected file has no messages.");
			return false;
		}
		
		return true;
	}
	
	private static String normalizeMessage(String message)
	{
		if(message == null)
			return "";
		
		String trimmed = message.replace("\r", "");
		if(trimmed.length() > CHAT_LIMIT)
			trimmed = trimmed.substring(0, CHAT_LIMIT);
		
		return trimmed;
	}
}
