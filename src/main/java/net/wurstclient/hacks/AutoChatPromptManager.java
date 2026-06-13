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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.wurstclient.WurstClient;

/**
 * Manages AutoChat prompt files stored in the Wurst folder. Chat prompts and
 * system prompts are stored as separate .txt files under
 * {@code wurst/autochat/prompts/} and {@code wurst/autochat/system_prompts/}.
 */
public final class AutoChatPromptManager
{
	public static final String PROMPTS_DIR = "autochat/prompts";
	public static final String SYSTEM_PROMPTS_DIR = "autochat/system_prompts";
	
	private final Path promptsDir;
	private final Path systemPromptsDir;
	
	public AutoChatPromptManager()
	{
		Path wurstFolder = WurstClient.INSTANCE.getWurstFolder();
		promptsDir = wurstFolder.resolve(PROMPTS_DIR);
		systemPromptsDir = wurstFolder.resolve(SYSTEM_PROMPTS_DIR);
		ensureDirectories();
	}
	
	private void ensureDirectories()
	{
		try
		{
			Files.createDirectories(promptsDir);
			Files.createDirectories(systemPromptsDir);
		}catch(IOException e)
		{
			System.out
				.println("AutoChat: Could not create prompt directories.");
			e.printStackTrace();
		}
	}
	
	/**
	 * Lists available chat prompt file names (without .txt extension) sorted
	 * alphabetically. Returns a map of display-name → file-name.
	 */
	public Map<String, String> listChatPrompts()
	{
		return listFiles(promptsDir);
	}
	
	/**
	 * Lists available system prompt file names (without .txt extension) sorted
	 * alphabetically. Returns a map of display-name → file-name.
	 */
	public Map<String, String> listSystemPrompts()
	{
		return listFiles(systemPromptsDir);
	}
	
	private Map<String, String> listFiles(Path dir)
	{
		Map<String, String> result = new LinkedHashMap<>();
		if(!Files.isDirectory(dir))
			return result;
		
		List<String> names = new ArrayList<>();
		try(DirectoryStream<Path> stream =
			Files.newDirectoryStream(dir, "*.txt"))
		{
			for(Path entry : stream)
			{
				String fn = entry.getFileName().toString();
				if(fn.endsWith(".txt"))
					names.add(fn.substring(0, fn.length() - 4));
			}
		}catch(IOException e)
		{
			e.printStackTrace();
			return result;
		}
		
		names.sort(String.CASE_INSENSITIVE_ORDER);
		for(String name : names)
			result.put(name, name);
		return result;
	}
	
	/**
	 * Reads a chat prompt file.
	 *
	 * @param name
	 *            file name without .txt extension
	 * @return the prompt text, or empty string if not found
	 */
	public String loadChatPrompt(String name)
	{
		return loadFile(promptsDir, name);
	}
	
	/**
	 * Reads a system prompt file.
	 *
	 * @param name
	 *            file name without .txt extension
	 * @return the prompt text, or empty string if not found
	 */
	public String loadSystemPrompt(String name)
	{
		return loadFile(systemPromptsDir, name);
	}
	
	private String loadFile(Path dir, String name)
	{
		if(name == null || name.isBlank())
			return "";
		Path file = dir.resolve(sanitizeFileName(name) + ".txt");
		if(!Files.exists(file))
			return "";
		try
		{
			return Files.readString(file, StandardCharsets.UTF_8);
		}catch(IOException e)
		{
			e.printStackTrace();
			return "";
		}
	}
	
	/**
	 * Saves a chat prompt file. Creates or overwrites.
	 *
	 * @param name
	 *            file name without .txt extension
	 * @param content
	 *            the prompt text
	 */
	public void saveChatPrompt(String name, String content)
	{
		saveFile(promptsDir, name, content);
	}
	
	/**
	 * Saves a system prompt file. Creates or overwrites.
	 *
	 * @param name
	 *            file name without .txt extension
	 * @param content
	 *            the prompt text
	 */
	public void saveSystemPrompt(String name, String content)
	{
		saveFile(systemPromptsDir, name, content);
	}
	
	private void saveFile(Path dir, String name, String content)
	{
		if(name == null || name.isBlank())
			return;
		try
		{
			Files.createDirectories(dir);
			Path file = dir.resolve(sanitizeFileName(name) + ".txt");
			Files.writeString(file,
				content == null ? "" : content.replace("\r\n", "\n"),
				StandardCharsets.UTF_8);
		}catch(IOException e)
		{
			System.out.println("AutoChat: Could not save prompt file: " + name);
			e.printStackTrace();
		}
	}
	
	/**
	 * Deletes a chat prompt file.
	 *
	 * @param name
	 *            file name without .txt extension
	 */
	public void deleteChatPrompt(String name)
	{
		deleteFile(promptsDir, name);
	}
	
	/**
	 * Deletes a system prompt file.
	 *
	 * @param name
	 *            file name without .txt extension
	 */
	public void deleteSystemPrompt(String name)
	{
		deleteFile(systemPromptsDir, name);
	}
	
	private void deleteFile(Path dir, String name)
	{
		if(name == null || name.isBlank())
			return;
		Path file = dir.resolve(sanitizeFileName(name) + ".txt");
		try
		{
			Files.deleteIfExists(file);
		}catch(IOException e)
		{
			System.out
				.println("AutoChat: Could not delete prompt file: " + name);
			e.printStackTrace();
		}
	}
	
	/**
	 * Sanitizes a file name: strips path separators, limits length, ensures
	 * valid characters.
	 */
	public static String sanitizeFileName(String name)
	{
		if(name == null || name.isBlank())
			return "untitled";
		String safe = name.replaceAll("[/\\\\:*?\"<>|]", "_").strip();
		if(safe.length() > 60)
			safe = safe.substring(0, 60).strip();
		if(safe.isEmpty())
			return "untitled";
		return safe;
	}
}
