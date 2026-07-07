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
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.network.chat.Component;
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

@SearchTags({"command spam", "commandspam", "spam command", "packet spam"})
@DontSaveState
public final class CommandSpamHack extends Hack implements UpdateListener
{
	private static final int COMMAND_LIMIT = 256;
	
	private final TextFieldSetting command = new TextFieldSetting("Command",
		"Command to send repeatedly when file mode is disabled.", "",
		s -> s != null && s.length() <= COMMAND_LIMIT);
	
	private final SliderSetting amount =
		new SliderSetting("Amount", "How many times to send the command.", 10,
			1, 10000, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting pause =
		new SliderSetting("Pause", "Seconds to wait between commands.", 1, 0.05,
			5, 0.01, ValueDisplay.DECIMAL.withSuffix("s"));
	
	private final SliderSetting jitter = new SliderSetting("Jitter",
		"Random seconds added to or removed from each pause. Helps avoid exact repeated timing.",
		0.08, 0, 2, 0.01, ValueDisplay.DECIMAL.withSuffix("s"));
	
	private final CheckboxSetting useFile = new CheckboxSetting("Use file",
		"Send each line from the selected text file instead of the Command field.",
		false);
	
	private final CheckboxSetting reconnectOnSpamKick = new CheckboxSetting(
		"Reconnect on spam kick",
		"Instantly reconnects if the disconnect reason looks like a spam kick.",
		true);
	
	private final FileSetting textFile = new FileSetting("File",
		"Select a text file to read line by line.", "commandspam", folder -> {
			try
			{
				Files.createDirectories(folder);
				Path file = folder.resolve("commands.txt");
				if(Files.notExists(file))
				{
					Files.writeString(file, "welcome\n" + "help\n",
						StandardCharsets.UTF_8);
				}
			}catch(IOException e)
			{
				throw new RuntimeException(e);
			}
		});
	
	private final ArrayList<String> pendingCommands = new ArrayList<>();
	private int currentIndex;
	private int timer;
	private boolean fileMode;
	
	public CommandSpamHack()
	{
		super("CommandSpam");
		setCategory(Category.CHAT);
		addSetting(command);
		addSetting(amount);
		addSetting(pause);
		addSetting(jitter);
		addSetting(useFile);
		addSetting(reconnectOnSpamKick);
		addSetting(textFile);
	}
	
	@Override
	public String getRenderName()
	{
		if(!isEnabled())
			return getName();
		
		int total = getTotalCommands();
		return getName() + " [" + Math.min(currentIndex, total) + "/" + total
			+ "]";
	}
	
	@Override
	public String getStatusText()
	{
		if(!isEnabled())
			return null;
		
		int total = getTotalCommands();
		return Math.min(currentIndex, total) + "/" + total;
	}
	
	@Override
	protected void onEnable()
	{
		timer = 0;
		currentIndex = 0;
		fileMode = useFile.isChecked();
		pendingCommands.clear();
		
		if(fileMode)
		{
			if(!loadCommandsFromFile())
			{
				setEnabled(false);
				return;
			}
		}else
		{
			String configuredCommand = normalizeCommand(command.getValue());
			if(configuredCommand.isEmpty())
			{
				ChatUtils.error("CommandSpam: enter a command first.");
				setEnabled(false);
				return;
			}
			
			pendingCommands.add(configuredCommand);
		}
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		pendingCommands.clear();
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
		
		int total = getTotalCommands();
		if(currentIndex >= total)
		{
			setEnabled(false);
			return;
		}
		
		String currentCommand = fileMode ? pendingCommands.get(currentIndex)
			: pendingCommands.get(0);
		if(currentCommand == null || currentCommand.isBlank())
		{
			currentIndex++;
			return;
		}
		
		MC.getConnection().sendCommand(currentCommand);
		currentIndex++;
		
		if(currentIndex >= total)
		{
			setEnabled(false);
			return;
		}
		
		timer = getPauseTicks();
	}
	
	private int getTotalCommands()
	{
		if(fileMode)
			return pendingCommands.size();
		
		return amount.getValueI();
	}
	
	private int getPauseTicks()
	{
		double pauseSeconds = pause.getValue();
		double jitterSeconds = jitter.getValue();
		
		if(jitterSeconds > 0)
			pauseSeconds += ThreadLocalRandom.current()
				.nextDouble(-jitterSeconds, jitterSeconds);
		
		return Math.max(1, (int)Math.round(pauseSeconds * 20));
	}
	
	private boolean loadCommandsFromFile()
	{
		Path selectedFile = textFile.getSelectedFile();
		if(selectedFile == null)
		{
			ChatUtils.error("CommandSpam: no file selected.");
			return false;
		}
		
		List<String> lines;
		try
		{
			lines = Files.readAllLines(selectedFile, StandardCharsets.UTF_8);
		}catch(IOException e)
		{
			ChatUtils.error("CommandSpam: couldn't read "
				+ selectedFile.getFileName() + ".");
			return false;
		}
		
		for(String line : lines)
		{
			String normalized = normalizeCommand(line);
			if(!normalized.isEmpty())
				pendingCommands.add(normalized);
		}
		
		if(pendingCommands.isEmpty())
		{
			ChatUtils.error("CommandSpam: the selected file has no commands.");
			return false;
		}
		
		return true;
	}
	
	public boolean shouldInstantReconnect(Component reason)
	{
		if(!isEnabled() || !reconnectOnSpamKick.isChecked() || reason == null)
			return false;
		
		String text = reason.getString().toLowerCase(Locale.ROOT);
		return text.contains("spam") || text.contains("too many messages");
	}
	
	private static String normalizeCommand(String command)
	{
		if(command == null)
			return "";
		
		String trimmed = command.replace("\r", "").trim();
		while(trimmed.startsWith("/"))
			trimmed = trimmed.substring(1).trim();
		
		if(trimmed.length() > COMMAND_LIMIT)
			trimmed = trimmed.substring(0, COMMAND_LIMIT);
		
		return trimmed;
	}
}
