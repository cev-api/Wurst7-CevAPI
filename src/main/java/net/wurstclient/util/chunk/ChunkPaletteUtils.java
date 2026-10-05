/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util.chunk;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

/** Reads raw palette order separately from the states still used in storage. */
public final class ChunkPaletteUtils
{
	private static final Field DATA_FIELD =
		getField(PalettedContainer.class, "data");
	private static final Field PALETTE_FIELD = getPaletteField();
	
	private ChunkPaletteUtils()
	{}
	
	private static Field getField(Class<?> owner, String name)
	{
		try
		{
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		}catch(ReflectiveOperationException e)
		{
			return null;
		}
	}
	
	private static Field getPaletteField()
	{
		try
		{
			return getField(
				Class.forName(
					"net.minecraft.world.level.chunk.PalettedContainer$Data"),
				"palette");
		}catch(ClassNotFoundException e)
		{
			return null;
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T> Palette<T> getPalette(Object container)
	{
		if(container == null || DATA_FIELD == null || PALETTE_FIELD == null)
			return null;
		
		try
		{
			Object data = DATA_FIELD.get(container);
			return (Palette<T>)PALETTE_FIELD.get(data);
		}catch(ReflectiveOperationException e)
		{
			return null;
		}
	}
	
	public static <T> List<T> getRawEntries(Object container)
	{
		return getRawEntries(ChunkPaletteUtils.<T> getPalette(container));
	}
	
	public static <T> List<T> getRawEntries(Palette<T> palette)
	{
		int size = getSize(palette);
		if(size <= 0)
			return List.of();
		
		ArrayList<T> entries = new ArrayList<>(size);
		for(int i = 0; i < size; i++)
		{
			T entry = getEntry(palette, i);
			if(entry != null)
				entries.add(entry);
		}
		return entries;
	}
	
	public static <T> T getFirstRawEntry(Object container)
	{
		Palette<T> palette = getPalette(container);
		return getFirstRawEntry(palette);
	}
	
	public static <T> T getFirstRawEntry(Palette<T> palette)
	{
		int size = getSize(palette);
		for(int i = 0; i < size; i++)
		{
			T entry = getEntry(palette, i);
			if(entry != null)
				return entry;
		}
		return null;
	}
	
	private static int getSize(Palette<?> palette)
	{
		if(palette == null)
			return 0;
		
		try
		{
			return palette.getSize();
		}catch(RuntimeException e)
		{
			return 0;
		}
	}
	
	private static <T> T getEntry(Palette<T> palette, int index)
	{
		try
		{
			return palette.valueFor(index);
		}catch(RuntimeException e)
		{
			// Match the old reflective reader: skip unavailable entries.
			return null;
		}
	}
	
	public static int countUsedStates(PalettedContainer<BlockState> states)
	{
		// count() walks packed storage IDs and visits only used palette
		// entries.
		// Distinct IDs can hold the same state in palettes read from packets.
		StateCounter counter = new StateCounter();
		states.count(counter);
		return counter.distinct.size();
	}
	
	private static final class StateCounter
		implements PalettedContainer.CountConsumer<BlockState>
	{
		private final HashSet<BlockState> distinct = new HashSet<>();
		
		@Override
		public void accept(BlockState state, int occurrences)
		{
			distinct.add(state);
		}
	}
}
