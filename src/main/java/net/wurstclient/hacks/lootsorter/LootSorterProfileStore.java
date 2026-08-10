/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks.lootsorter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.wurstclient.WurstClient;

/** Global JSON store, separate from mutable hack settings. */
public final class LootSorterProfileStore
{
	private static final Type LIST_TYPE =
		new TypeToken<List<LootSorterProfile>>()
		{}.getType();
	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final Path file = WurstClient.INSTANCE.getWurstFolder()
		.resolve("lootsorter").resolve("profiles.json");
	
	public List<LootSorterProfile> load()
	{
		if(!Files.exists(file))
			return new ArrayList<>();
		try
		{
			List<LootSorterProfile> profiles = gson.fromJson(
				Files.readString(file, StandardCharsets.UTF_8), LIST_TYPE);
			return profiles == null ? new ArrayList<>()
				: new ArrayList<>(profiles);
		}catch(IOException | RuntimeException e)
		{
			return new ArrayList<>();
		}
	}
	
	public void save(List<LootSorterProfile> profiles) throws IOException
	{
		Files.createDirectories(file.getParent());
		Files.writeString(file, gson.toJson(profiles, LIST_TYPE),
			StandardCharsets.UTF_8);
	}
}
