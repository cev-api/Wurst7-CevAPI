/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.lootsearch;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.wurstclient.chestsearch.ChestEntry;
import net.wurstclient.util.ItemNameUtils;
import net.wurstclient.WurstClient;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LootSearchUtil
{
	private static final Pattern TRANSLATE_KEY_PATTERN = Pattern
		.compile("item[-_]name=\\{\\\"translate\\\":\\\"([^\\\"]+)\\\"\\}");
	private static final Pattern TEXT_KEY_PATTERN =
		Pattern.compile("item[-_]name=\\{\\\"text\\\":\\\"([^\\\"]+)\\\"\\}");
	private static final Set<String> OVERWORLD_STRUCTURE_TYPES =
		setOf("ancient_city", "buried_treasure", "desert_pyramid", "igloo",
			"jungle_temple", "mansion", "mineshaft", "monument", "ocean_ruin",
			"pillager_outpost", "shipwreck", "stronghold", "swamp_hut",
			"trail_ruins", "trial_chambers", "village");
	private static final Set<String> NETHER_STRUCTURE_TYPES =
		setOf("bastion_remnant", "nether_fortress");
	private static final Set<String> END_STRUCTURE_TYPES = setOf("end_city");
	
	private LootSearchUtil()
	{}
	
	public static File getSeedmapperLootDir()
	{
		File located = tryLocateSeedmapperLootDir();
		if(located != null)
			return located;
		
		try
		{
			if(WurstClient.MC != null && WurstClient.MC.gameDirectory != null)
			{
				File resolved =
					resolveSeedmapperLoot(WurstClient.MC.gameDirectory);
				if(resolved != null)
					return resolved;
				return new File(WurstClient.MC.gameDirectory,
					"seedmapper/loot");
			}
		}catch(Throwable ignored)
		{}
		String os = System.getProperty("os.name").toLowerCase();
		// On Windows, Minecraft is usually in %APPDATA%\.minecraft
		if(os.contains("win"))
		{
			String appdata = System.getenv("APPDATA");
			if(appdata != null && !appdata.isBlank())
			{
				File f =
					new File(new File(appdata), ".minecraft/seedmapper/loot");
				if(f.exists())
					return f;
			}
		}
		String user = System.getProperty("user.home");
		return new File(new File(user, ".minecraft"), "seedmapper/loot");
	}
	
	private static File tryLocateSeedmapperLootDir()
	{
		Set<File> searchRoots = new LinkedHashSet<>();
		try
		{
			if(WurstClient.MC != null && WurstClient.MC.gameDirectory != null)
				addRootAndParents(searchRoots, WurstClient.MC.gameDirectory, 4);
		}catch(Throwable ignored)
		{}
		
		String user = System.getProperty("user.home");
		if(user != null && !user.isBlank())
		{
			File home = new File(user);
			addRootAndParents(searchRoots, home, 0);
			addRootAndParents(searchRoots, new File(home, ".minecraft"), 0);
		}
		
		String appdata = System.getenv("APPDATA");
		if(appdata != null && !appdata.isBlank())
			addRootAndParents(searchRoots, new File(appdata, ".minecraft"), 0);
		
		// Also check common locations used by flatpak / other launchers
		if(user != null && !user.isBlank())
		{
			File varApp = new File(user, ".var/app");
			if(varApp.exists() && varApp.isDirectory())
			{
				File[] vendors = varApp.listFiles(File::isDirectory);
				if(vendors != null)
				{
					for(File vendor : vendors)
					{
						// look inside vendor/data and vendor/data/* for
						// SeedMapper folders
						File data = new File(vendor, "data");
						if(data.exists() && data.isDirectory())
						{
							File found = searchTreeForSeedmapper(data, 4);
							if(found != null)
								return found;
						}
					}
				}
			}
			
			// common user-level locations used by some launchers
			File localShare = new File(user, ".local/share");
			if(localShare.exists() && localShare.isDirectory())
			{
				File found = searchTreeForSeedmapper(localShare, 3);
				if(found != null)
					return found;
			}
			
			File config = new File(user, ".config");
			if(config.exists() && config.isDirectory())
			{
				File found = searchTreeForSeedmapper(config, 3);
				if(found != null)
					return found;
			}
			
		}
		
		for(File root : searchRoots)
		{
			File resolved = resolveSeedmapperLoot(root);
			if(resolved != null)
				return resolved;
		}
		
		// No matches under known roots.
		return null;
	}
	
	private static void addRootAndParents(Set<File> set, File start,
		int maxDepth)
	{
		File current = start;
		for(int depth = 0; current != null && depth <= maxDepth; depth++)
		{
			set.add(current);
			current = current.getParentFile();
		}
	}
	
	private static File resolveSeedmapperLoot(File root)
	{
		if(root == null || !root.exists() || !root.isDirectory())
			return null;
		
		if(root.getName().equalsIgnoreCase("loot"))
			return root;
		
		if(root.getName().equalsIgnoreCase("seedmapper"))
		{
			File loot = new File(root, "loot");
			if(loot.exists() && loot.isDirectory())
				return loot;
		}
		
		String[] folderNames = {"seedmapper", "SeedMapper", "Seedmapper"};
		for(String folder : folderNames)
		{
			File loot = new File(new File(root, folder), "loot");
			if(loot.exists() && loot.isDirectory())
				return loot;
		}
		
		File[] matches = root.listFiles(file -> file.isDirectory()
			&& file.getName().equalsIgnoreCase("seedmapper"));
		if(matches != null)
		{
			for(File match : matches)
			{
				File loot = new File(match, "loot");
				if(loot.exists() && loot.isDirectory())
					return loot;
			}
		}
		
		String[] mcFolderNames = {"minecraft", ".minecraft"};
		for(String mcFolder : mcFolderNames)
		{
			File mc = new File(root, mcFolder);
			if(mc.exists() && mc.isDirectory())
			{
				File resolved = resolveSeedmapperLoot(mc);
				if(resolved != null)
					return resolved;
			}
		}
		
		return null;
	}
	
	/**
	 * Search a directory tree up to the given depth for a SeedMapper loot
	 * folder.
	 */
	private static File searchTreeForSeedmapper(File base, int maxDepth)
	{
		if(base == null || !base.exists() || !base.isDirectory())
			return null;
		java.util.ArrayDeque<File> dq = new java.util.ArrayDeque<>();
		java.util.ArrayDeque<Integer> depth = new java.util.ArrayDeque<>();
		dq.add(base);
		depth.add(0);
		while(!dq.isEmpty())
		{
			File cur = dq.removeFirst();
			int d = depth.removeFirst();
			File resolved = resolveSeedmapperLoot(cur);
			if(resolved != null)
				return resolved;
			if(d >= maxDepth)
				continue;
			File[] children = cur.listFiles(File::isDirectory);
			if(children == null)
				continue;
			for(File c : children)
			{
				dq.addLast(c);
				depth.addLast(d + 1);
			}
		}
		return null;
	}
	
	public static File findFileForServer(String serverIp)
	{
		if(serverIp == null)
			serverIp = "";
		File dir = getSeedmapperLootDir();
		if(dir == null || !dir.exists() || !dir.isDirectory())
			return null;
		File best = null;
		File[] files =
			dir.listFiles((d, name) -> name.toLowerCase().endsWith(".json"));
		if(files == null)
			return null;
		// Build candidate tokens based on SeedMapper's serverId generation
		// serverId = serverIp with non-alnum/dot/dash/underscore -> '_',
		// collapse '_' repeats,
		// trim leading/trailing '-' or '_' and fallback to "local" when blank.
		java.util.List<String> candidates = new java.util.ArrayList<>();
		try
		{
			String full = serverIp;
			if(full == null)
				full = "";
			String sanitizedFull = full.replaceAll("[^A-Za-z0-9._-]", "_")
				.replaceAll("_+", "_").replaceAll("^[-_]+|[-_]+$", "");
			if(sanitizedFull.isBlank())
				sanitizedFull = "local";
			candidates.add(sanitizedFull);
			
			// Also add host-only sanitized (strip port) for extra tolerance
			if(full.contains(":"))
			{
				String host = full.split(":", 2)[0];
				String sanitizedHost = host.replaceAll("[^A-Za-z0-9._-]", "_")
					.replaceAll("_+", "_").replaceAll("^[-_]+|[-_]+$", "");
				if(sanitizedHost.isBlank())
					sanitizedHost = "local";
				if(!sanitizedHost.equals(sanitizedFull))
					candidates.add(sanitizedHost);
			}
		}catch(Throwable ignored)
		{}
		for(File f : files)
		{
			String name = f.getName();
			String lower = name.toLowerCase();
			for(String cand : candidates)
			{
				if(cand == null || cand.isBlank())
					continue;
				String c = cand.toLowerCase();
				if(lower.startsWith(c + "_") || lower.contains(c + "_")
					|| lower.startsWith(c))
				{
					if(best == null || f.lastModified() > best.lastModified())
						best = f;
					break;
				}
			}
		}
		// Fallback: if nothing matched, use the most recently modified file.
		if(best == null && files.length > 0)
		{
			for(File f : files)
			{
				if(best == null || f.lastModified() > best.lastModified())
					best = f;
			}
		}
		return best;
	}
	
	public static File normalizeJsonFile(File file)
	{
		if(file == null)
			return null;
		if(file.exists() && file.isFile()
			&& file.getName().toLowerCase().endsWith(".json"))
			return file;
		return null;
	}
	
	public static boolean isMixedDimensionFile(File file)
	{
		if(file == null || !file.exists() || !file.isFile())
			return false;
		try(FileReader r = new FileReader(file))
		{
			JsonElement root = JsonParser.parseReader(r);
			if(root == null || !root.isJsonObject())
				return false;
			JsonObject obj = root.getAsJsonObject();
			if(!obj.has("dimension"))
				return false;
			String dim = obj.get("dimension").getAsString();
			return dim != null && dim.equalsIgnoreCase("mixed");
		}catch(Throwable ignored)
		{
			return false;
		}
	}
	
	public static List<ChestEntry> parseLootFile(File file, String serverIp)
	{
		List<ChestEntry> out = new ArrayList<>();
		if(file == null || !file.exists())
			return out;
		try(FileReader r = new FileReader(file))
		{
			JsonElement root = JsonParser.parseReader(r);
			if(root == null || !root.isJsonObject())
				return out;
			JsonObject obj = root.getAsJsonObject();
			String dimension = obj.has("dimension")
				? obj.get("dimension").getAsString() : null;
			JsonArray structs =
				obj.has("structures") ? obj.getAsJsonArray("structures") : null;
			if(structs == null && obj.has("items"))
			{
				structs = new JsonArray();
				structs.add(obj);
			}
			if(structs == null)
				return out;
			for(JsonElement se : structs)
			{
				if(!se.isJsonObject())
					continue;
				ChestEntry ce = parseStructureEntry(se.getAsJsonObject(),
					serverIp, dimension);
				if(ce != null)
					out.add(ce);
			}
		}catch(Throwable t)
		{
			t.printStackTrace();
		}
		return out;
	}
	
	private static ChestEntry parseStructureEntry(JsonObject s, String serverIp,
		String dimension)
	{
		int x = s.has("x") ? s.get("x").getAsInt() : 0;
		int y = s.has("y") ? s.get("y").getAsInt() : 0;
		int z = s.has("z") ? s.get("z").getAsInt() : 0;
		ChestEntry ce = new ChestEntry();
		ce.serverIp = serverIp;
		String structureType = s.has("type") ? s.get("type").getAsString() : "";
		String structureDimension =
			s.has("dimension") ? s.get("dimension").getAsString() : dimension;
		ce.dimension =
			normalizeLootDimension(structureDimension, structureType);
		ce.x = x;
		ce.y = y;
		ce.z = z;
		ce.maxX = x;
		ce.maxY = y;
		ce.maxZ = z;
		ce.items = new ArrayList<>();
		
		JsonArray items = s.has("items") ? s.getAsJsonArray("items") : null;
		if(items == null)
			return ce;
		
		boolean detailedItems = false;
		for(JsonElement ie : items)
		{
			if(!ie.isJsonObject())
				continue;
			JsonObject it = ie.getAsJsonObject();
			if(it.has("slot") || it.has("displayName") || it.has("nbt")
				|| it.has("enchantments") || it.has("enchantmentLevels")
				|| it.has("itemId"))
			{
				detailedItems = true;
				break;
			}
		}
		
		if(detailedItems)
		{
			int fallbackSlot = 0;
			for(JsonElement ie : items)
			{
				if(!ie.isJsonObject())
					continue;
				JsonObject it = ie.getAsJsonObject();
				String id = null;
				if(it.has("itemId"))
					id = it.get("itemId").getAsString();
				else if(it.has("id"))
					id = it.get("id").getAsString();
				if(id == null)
					continue;
				
				ChestEntry.ItemEntry item = new ChestEntry.ItemEntry();
				item.slot =
					it.has("slot") ? it.get("slot").getAsInt() : fallbackSlot++;
				item.count = it.has("count") ? it.get("count").getAsInt() : 1;
				item.itemId = id;
				String rawDisplayName = it.has("displayName")
					? it.get("displayName").getAsString() : null;
				if(it.has("nbt"))
				{
					JsonElement nbt = it.get("nbt");
					item.nbt = nbt.isJsonPrimitive() ? nbt : nbt.deepCopy();
				}
				item.displayName = normalizeItemDisplayName(item.itemId,
					rawDisplayName, item.nbt);
				
				if(it.has("enchantments")
					&& it.get("enchantments").isJsonArray())
				{
					JsonArray ench = it.getAsJsonArray("enchantments");
					java.util.List<String> list = new java.util.ArrayList<>();
					for(JsonElement e : ench)
					{
						if(e != null && e.isJsonPrimitive())
							list.add(e.getAsString());
					}
					if(!list.isEmpty())
						item.enchantments = list;
				}
				
				if(it.has("enchantmentLevels")
					&& it.get("enchantmentLevels").isJsonArray())
				{
					JsonArray lv = it.getAsJsonArray("enchantmentLevels");
					java.util.List<Integer> list = new java.util.ArrayList<>();
					for(JsonElement e : lv)
					{
						if(e != null && e.isJsonPrimitive())
							list.add(Integer.valueOf(e.getAsInt()));
					}
					if(!list.isEmpty())
						item.enchantmentLevels = list;
				}
				
				ce.items.add(item);
			}
		}else
		{
			Map<String, Integer> sums = new HashMap<>();
			for(JsonElement ie : items)
			{
				if(!ie.isJsonObject())
					continue;
				JsonObject it = ie.getAsJsonObject();
				String id = it.has("id") ? it.get("id").getAsString() : null;
				int count = it.has("count") ? it.get("count").getAsInt() : 1;
				if(id == null)
					continue;
				sums.put(id, sums.getOrDefault(id, 0) + count);
			}
			int slot = 0;
			for(Map.Entry<String, Integer> e : sums.entrySet())
			{
				ChestEntry.ItemEntry ie = new ChestEntry.ItemEntry();
				ie.slot = slot++;
				ie.count = e.getValue();
				ie.itemId = e.getKey();
				ie.displayName = null;
				ie.nbt = null;
				ce.items.add(ie);
			}
		}
		
		return ce;
	}
	
	private static String normalizeItemDisplayName(String itemId,
		String rawDisplayName, JsonElement nbt)
	{
		String fromNbt = parseDisplayNameFromNbt(nbt);
		if(fromNbt != null && !fromNbt.isBlank())
			return fromNbt;
		
		String displayName = normalizeBasicName(rawDisplayName);
		if(displayName != null && !displayName.isBlank())
			return displayName;
		
		return normalizeBasicName(itemId);
	}
	
	private static String parseDisplayNameFromNbt(JsonElement nbt)
	{
		if(nbt == null)
			return null;
		String nbtText = nbt.toString();
		if(nbtText == null || nbtText.isBlank())
			return null;
		
		Matcher textMatcher = TEXT_KEY_PATTERN.matcher(nbtText);
		if(textMatcher.find())
		{
			String text = textMatcher.group(1);
			if(text != null && !text.isBlank())
				return text;
		}
		
		Matcher translateMatcher = TRANSLATE_KEY_PATTERN.matcher(nbtText);
		if(!translateMatcher.find())
			return null;
		
		String translateKey = translateMatcher.group(1);
		if(translateKey == null || translateKey.isBlank())
			return null;
		
		String key = translateKey;
		int colon = key.lastIndexOf(':');
		if(colon >= 0 && colon + 1 < key.length())
			key = key.substring(colon + 1);
		int dot = key.lastIndexOf('.');
		if(dot >= 0 && dot + 1 < key.length())
			key = key.substring(dot + 1);
		
		return ItemNameUtils.humanize(key);
	}
	
	private static String normalizeBasicName(String raw)
	{
		if(raw == null || raw.isBlank())
			return null;
		String value = raw.trim();
		int colon = value.indexOf(':');
		if(colon >= 0 && colon + 1 < value.length())
			value = value.substring(colon + 1);
		if(value.contains("_"))
			return ItemNameUtils.humanize(value.toLowerCase());
		return value;
	}
	
	private static String normalizeLootDimension(String rawDimension,
		String structureType)
	{
		if(rawDimension == null || rawDimension.isBlank())
			return inferDimensionFromStructureType(structureType);
		
		String dim = rawDimension.trim().toLowerCase();
		if(dim.equals("minecraft:overworld") || dim.equals("overworld"))
			return "minecraft:overworld";
		if(dim.equals("minecraft:the_nether") || dim.equals("the_nether")
			|| dim.equals("nether"))
			return "minecraft:the_nether";
		if(dim.equals("minecraft:the_end") || dim.equals("the_end")
			|| dim.equals("end"))
			return "minecraft:the_end";
		if(dim.equals("mixed"))
		{
			String inferred = inferDimensionFromStructureType(structureType);
			return inferred != null ? inferred : "mixed";
		}
		
		return rawDimension;
	}
	
	private static String inferDimensionFromStructureType(String structureType)
	{
		if(structureType == null || structureType.isBlank())
			return null;
		String type = structureType.trim().toLowerCase();
		int colon = type.indexOf(':');
		if(colon >= 0 && colon + 1 < type.length())
			type = type.substring(colon + 1);
		
		if(OVERWORLD_STRUCTURE_TYPES.contains(type))
			return "minecraft:overworld";
		if(NETHER_STRUCTURE_TYPES.contains(type))
			return "minecraft:the_nether";
		if(END_STRUCTURE_TYPES.contains(type))
			return "minecraft:the_end";
		
		return null;
	}
	
	private static Set<String> setOf(String... values)
	{
		HashSet<String> set = new HashSet<>();
		Collections.addAll(set, values);
		return Collections.unmodifiableSet(set);
	}
	
	public static ChestEntry findEntryByPos(List<ChestEntry> entries, int x,
		int y, int z)
	{
		if(entries == null)
			return null;
		for(ChestEntry e : entries)
		{
			if(e == null)
				continue;
			if(e.x == x && e.y == y && e.z == z)
				return e;
		}
		return null;
	}
	
	public static boolean compareStacksWithLoot(String serverIp,
		String dimension, int x, int y, int z, List<ItemStack> stacks)
	{
		try
		{
			File f = findFileForServer(serverIp);
			if(f == null)
				return true; // no data available -> don't warn
			List<ChestEntry> entries = parseLootFile(f, serverIp);
			ChestEntry expected = findEntryByPos(entries, x, y, z);
			if(expected == null)
				return true;
			Map<String, Integer> exp = new HashMap<>();
			if(expected.items != null)
			{
				for(ChestEntry.ItemEntry it : expected.items)
				{
					if(it == null || it.itemId == null)
						continue;
					exp.put(it.itemId,
						exp.getOrDefault(it.itemId, 0) + it.count);
				}
			}
			Map<String, Integer> actual = new HashMap<>();
			if(stacks != null)
			{
				for(ItemStack st : stacks)
				{
					if(st == null || st.isEmpty())
						continue;
					String id;
					try
					{
						id = BuiltInRegistries.ITEM.getKey(st.getItem())
							.toString();
					}catch(Throwable t)
					{
						id = st.getItem().toString();
					}
					actual.put(id, actual.getOrDefault(id, 0) + st.getCount());
				}
			}
			if(exp.size() != actual.size())
				return false;
			for(Map.Entry<String, Integer> e : exp.entrySet())
			{
				Integer got = actual.get(e.getKey());
				if(got == null || !got.equals(e.getValue()))
					return false;
			}
			return true;
		}catch(Throwable t)
		{
			t.printStackTrace();
			return true;
		}
	}
}
