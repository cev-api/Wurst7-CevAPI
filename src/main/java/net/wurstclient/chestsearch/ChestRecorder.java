/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.chestsearch;

import com.google.gson.JsonPrimitive;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerListener;
import net.minecraft.util.math.BlockPos;
import net.wurstclient.clickgui.screens.ChestSearchScreen;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.registry.entry.RegistryEntry;
import java.util.Set;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.util.Identifier;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

public class ChestRecorder
{
	private final ChestManager manager;
	private final ChestConfig config;
	
	// buffer of the latest snapshot per handler
	private final Map<Integer, List<ItemStack>> buffers = new HashMap<>();
	private final Map<Integer, TimerTask> pendingSnapshots = new HashMap<>();
	
	public static class Bounds
	{
		public final int minX, minY, minZ;
		public final int maxX, maxY, maxZ;
		public final String facing;
		
		public Bounds(int minX, int minY, int minZ, int maxX, int maxY,
			int maxZ, String facing)
		{
			this.minX = minX;
			this.minY = minY;
			this.minZ = minZ;
			this.maxX = maxX;
			this.maxY = maxY;
			this.maxZ = maxZ;
			this.facing = facing;
		}
		
		public static Bounds of(BlockPos min, BlockPos max, String facing)
		{
			if(min == null && max == null)
				return null;
			if(min == null)
				min = max;
			if(max == null)
				max = min;
			return new Bounds(min.getX(), min.getY(), min.getZ(), max.getX(),
				max.getY(), max.getZ(), facing);
		}
	}
	
	public ChestRecorder(File storageFile, ChestConfig config)
	{
		this.manager = new ChestManager(storageFile, config);
		this.config = config;
	}
	
	public ChestRecorder()
	{
		this.config = new ChestConfig();
		this.manager =
			new ChestManager(new File(this.config.dbPath), this.config);
	}
	
	/**
	 * Starts recording updates for the given handler.
	 */
	public void startListening(final String serverIp, final String dimension,
		final int x, final int y, final int z, final ScreenHandler handler,
		final int chestSlots, final List<Integer> chestSlotIndices,
		final Bounds bounds)
	{
		if(config != null && !config.enabled)
			return;
		
		final int syncId = handler.syncId;
		final List<ItemStack> buf = new ArrayList<>(handler.slots.size());
		for(int i = 0; i < handler.slots.size(); i++)
			buf.add(ItemStack.EMPTY);
		buffers.put(syncId, buf);
		
		// Build sorted list of slots that belong to the chest
		final List<Integer> slotOrder = new ArrayList<>();
		if(chestSlotIndices != null && !chestSlotIndices.isEmpty())
		{
			slotOrder.addAll(chestSlotIndices);
			java.util.Collections.sort(slotOrder);
		}else
		{
			int limit = Math.min(chestSlots, handler.slots.size());
			for(int i = 0; i < limit; i++)
				slotOrder.add(i);
		}
		for(Integer idx : slotOrder)
		{
			if(idx >= 0 && idx < handler.slots.size())
			{
				ItemStack st = handler.slots.get(idx).getStack();
				buf.set(idx, st == null ? ItemStack.EMPTY : st.copy());
			}
		}
		
		final Runnable scheduleSnapshot = () -> {
			TimerTask old = pendingSnapshots.remove(syncId);
			if(old != null)
			{
				try
				{
					old.cancel();
				}catch(Throwable ignored)
				{}
			}
			TimerTask task = new TimerTask()
			{
				@Override
				public void run()
				{
					try
					{
						List<ItemStack> snapshot = new ArrayList<>(buf.size());
						for(int idx : slotOrder)
						{
							if(idx < 0 || idx >= buf.size())
							{
								snapshot.add(ItemStack.EMPTY);
								continue;
							}
							ItemStack st = buf.get(idx);
							snapshot
								.add(st == null ? ItemStack.EMPTY : st.copy());
						}
						
						recordFromStacksWithSlotOrder(serverIp, dimension, x, y,
							z, snapshot, slotOrder, bounds);
					}catch(Throwable t)
					{
						t.printStackTrace();
					}
				}
			};
			pendingSnapshots.put(syncId, task);
			new Timer(true).schedule(task, 40);
		};
		
		ScreenHandlerListener listener = new ScreenHandlerListener()
		{
			@Override
			public void onSlotUpdate(ScreenHandler sh, int slotId,
				ItemStack stack)
			{
				if(sh != handler)
					return;
				if(slotId >= 0 && slotId < buf.size())
				{
					buf.set(slotId,
						stack == null ? ItemStack.EMPTY : stack.copy());
					scheduleSnapshot.run();
				}
			}
			
			@Override
			public void onPropertyUpdate(ScreenHandler handler, int property,
				int value)
			{}
		};
		
		try
		{
			handler.addListener(listener);
		}catch(Throwable t)
		{
			t.printStackTrace();
		}
		
		// ensure at least one snapshot is captured soon after opening
		new Timer(true).schedule(new TimerTask()
		{
			@Override
			public void run()
			{
				scheduleSnapshot.run();
			}
		}, 40);
		
		// clean up after some time to avoid leaking listeners
		new Timer(true).schedule(new TimerTask()
		{
			@Override
			public void run()
			{
				try
				{
					if(buffers.containsKey(syncId))
					{
						TimerTask pending = pendingSnapshots.remove(syncId);
						if(pending != null)
						{
							try
							{
								pending.cancel();
							}catch(Throwable ignored)
							{}
						}
						try
						{
							handler.removeListener(listener);
						}catch(Throwable ignored)
						{}
						buffers.remove(syncId);
					}
				}catch(Throwable t)
				{
					t.printStackTrace();
				}
			}
		}, 30_000);
	}
	
	public void onChestOpened(String serverIp, String dimension, int x, int y,
		int z, ScreenHandler handler, int chestSlots, Bounds bounds)
	{
		startListening(serverIp, dimension, x, y, z, handler, chestSlots,
			new ArrayList<>(), bounds);
	}
	
	public void onChestOpened(String serverIp, String dimension, int x, int y,
		int z, ScreenHandler handler, int chestSlots,
		List<Integer> chestSlotIndices, Bounds bounds)
	{
		startListening(serverIp, dimension, x, y, z, handler, chestSlots,
			chestSlotIndices, bounds);
	}
	
	public List<ChestEntry> search(String q)
	{
		return manager.search(q);
	}
	
	public List<ChestEntry> all()
	{
		return manager.all();
	}
	
	public void removeChest(String serverIp, String dimension, int x, int y,
		int z)
	{
		manager.removeChest(serverIp, dimension, x, y, z);
		ChestSearchScreen.clearDecorations(dimension, new BlockPos(x, y, z));
	}
	
	public void recordFromStacks(String serverIp, String dimension, int x,
		int y, int z, List<ItemStack> stacks, int chestSlots)
	{
		recordFromStacksInternal(serverIp, dimension, x, y, z, stacks, null,
			chestSlots, null);
	}
	
	public void recordFromStacksWithSlotOrder(String serverIp, String dimension,
		int x, int y, int z, List<ItemStack> stacks, List<Integer> slotOrder,
		Bounds bounds)
	{
		int maxSlots = slotOrder == null ? -1 : slotOrder.size();
		recordFromStacksInternal(serverIp, dimension, x, y, z, stacks,
			slotOrder, maxSlots, bounds);
	}
	
	private void recordFromStacksInternal(String serverIp, String dimension,
		int x, int y, int z, List<ItemStack> stacks, List<Integer> slotOrder,
		int maxSlots, Bounds bounds)
	{
		if(config != null && !config.enabled)
			return;
		if(stacks == null)
			return;
		int limit = stacks.size();
		if(maxSlots >= 0 && maxSlots < limit)
			limit = maxSlots;
		List<ChestEntry.ItemEntry> items = new ArrayList<>();
		for(int i = 0; i < limit; i++)
		{
			ItemStack st = stacks.get(i);
			if(st == null || st.isEmpty())
				continue;
			ItemStack copy = st.copy();
			ChestEntry.ItemEntry it = new ChestEntry.ItemEntry();
			int slotNumber = slotOrder != null && i < slotOrder.size()
				? slotOrder.get(i) : i;
			it.slot = slotNumber;
			it.count = copy.getCount();
			try
			{
				it.itemId = Registries.ITEM.getId(copy.getItem()).toString();
			}catch(Throwable t)
			{
				it.itemId = copy.getItem().toString();
			}
			try
			{
				it.displayName = copy.getName().getString();
			}catch(Throwable ignored)
			{}
			try
			{
				String n = copy.toString();
				if(n != null && !n.isBlank())
				{
					it.nbt = new JsonPrimitive(n);
					if(n.contains("Enchantments")
						|| n.contains("StoredEnchantments"))
					{
						String ench = n;
						if(ench.length() > 80)
							ench = ench.substring(0, 80) + "...";
						if(it.displayName == null)
							it.displayName = ench;
						else
							it.displayName = it.displayName + " " + ench;
					}
				}else if(config != null && config.storeFullItemNbt)
				{
					try
					{
						it.nbt = new JsonPrimitive(copy.toString());
					}catch(Throwable ignored)
					{
						it.nbt = null;
					}
				}
			}catch(Throwable ignored)
			{
				it.nbt = null;
			}
			// Extract enchantment ids and potion/effect ids from the actual
			// ItemStack where possible. This allows searching by enchantment
			// or potion even if the textual NBT is not easily searchable.
			try
			{
				// Enchantments (including enchanted books)
				try
				{
					Set<Object2IntMap.Entry<RegistryEntry<Enchantment>>> enchSet =
						EnchantmentHelper.getEnchantments(copy)
							.getEnchantmentEntries();
					if(enchSet != null && !enchSet.isEmpty())
					{
						it.enchantments = new java.util.ArrayList<>();
						it.enchantmentLevels = new java.util.ArrayList<>();
						for(Object2IntMap.Entry<RegistryEntry<Enchantment>> e : enchSet)
						{
							RegistryEntry<Enchantment> ren = e.getKey();
							if(ren == null)
								continue;
							Identifier id = ren.getKey().map(k -> k.getValue())
								.orElse(null);
							String idStr = id != null ? id.toString()
								: ren.getIdAsString();
							int lvl = e.getIntValue();
							if(idStr != null && !idStr.isBlank())
							{
								it.enchantments.add(idStr);
								it.enchantmentLevels.add(Integer.valueOf(lvl));
							}
						}
					}
				}catch(Throwable ignored)
				{}
				
				// Potions / effects
				try
				{
					PotionContentsComponent potionContents =
						copy.getComponents().getOrDefault(
							DataComponentTypes.POTION_CONTENTS,
							PotionContentsComponent.DEFAULT);
					if(potionContents != null)
					{
						java.util.List<String> pe = new java.util.ArrayList<>();
						for(StatusEffectInstance sei : potionContents
							.getEffects())
						{
							RegistryEntry<StatusEffect> effEntry =
								sei.getEffectType();
							Identifier id = effEntry.getKey()
								.map(k -> k.getValue()).orElse(null);
							String idStr = id != null ? id.toString()
								: effEntry.getIdAsString();
							if(idStr != null && !idStr.isBlank())
								pe.add(idStr);
						}
						if(!pe.isEmpty())
						{
							it.potionEffects = pe;
							it.primaryPotion = pe.get(0);
						}else
						{
							// fallback to base potion id
							java.util.Optional<net.minecraft.registry.entry.RegistryEntry<net.minecraft.potion.Potion>> basePotion =
								potionContents.potion();
							if(basePotion.isPresent())
							{
								Identifier id = basePotion.get().getKey()
									.map(k -> k.getValue()).orElse(null);
								String idStr = id != null ? id.toString()
									: basePotion.get().getIdAsString();
								it.primaryPotion = idStr;
							}
						}
					}
				}catch(Throwable ignored)
				{}
			}catch(Throwable ignored)
			{
				// ignore extraction errors
			}
			items.add(it);
		}
		
		int minX = x;
		int minY = y;
		int minZ = z;
		int maxX = x;
		int maxY = y;
		int maxZ = z;
		String facing = null;
		if(bounds != null)
		{
			minX = Math.min(bounds.minX, bounds.maxX);
			minY = Math.min(bounds.minY, bounds.maxY);
			minZ = Math.min(bounds.minZ, bounds.maxZ);
			maxX = Math.max(bounds.minX, bounds.maxX);
			maxY = Math.max(bounds.minY, bounds.maxY);
			maxZ = Math.max(bounds.minZ, bounds.maxZ);
			facing = bounds.facing;
		}
		// Pass the original resolved/clicked coordinates (x,y,z) so the
		// exact block the player interacted with is preserved separately
		// from the canonical min/max bounds.
		manager.upsertChest(serverIp, dimension, minX, minY, minZ, items, maxX,
			maxY, maxZ, facing, Integer.valueOf(x), Integer.valueOf(y),
			Integer.valueOf(z));
		
		// debug removed
		
		// Notify container screen that chest was saved so UI text appears
		final int nx = minX, ny = minY, nz = minZ;
		try
		{
			Object screen = net.wurstclient.WurstClient.MC == null ? null
				: net.wurstclient.WurstClient.MC.currentScreen;
			if(screen != null)
			{
				try
				{
					java.lang.reflect.Field fMsg =
						screen.getClass().getDeclaredField("lastRecordMessage");
					java.lang.reflect.Field fUntil =
						screen.getClass().getDeclaredField("lastRecordUntilMs");
					fMsg.setAccessible(true);
					fUntil.setAccessible(true);
					java.time.LocalTime t = java.time.LocalTime.now();
					String ts =
						t.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
							.toString();
					String msg = "Chest recorded, position " + nx + "," + ny
						+ "," + nz + " at " + ts;
					fMsg.set(screen, msg);
					fUntil.setLong(screen, System.currentTimeMillis() + 4000);
				}catch(NoSuchFieldException nsf)
				{
					// not the expected screen type, ignore
				}catch(Throwable ignored)
				{
					// reflection failed, ignore
				}
			}
		}catch(Throwable ignored)
		{
			ignored.printStackTrace();
		}
	}
	
	private static String sanitizePath(String raw)
	{
		if(raw == null || raw.isEmpty())
			return "";
		int colon = raw.indexOf(':');
		return colon >= 0 && colon + 1 < raw.length() ? raw.substring(colon + 1)
			: raw;
	}
	
}
