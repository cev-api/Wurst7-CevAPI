/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.clickgui.screens.EditBlockListScreen;
import net.wurstclient.events.GetAmbientOcclusionLightLevelListener;
import net.wurstclient.events.GetAmbientOcclusionLightLevelListener.GetAmbientOcclusionLightLevelEvent;
import net.wurstclient.events.SetOpaqueCubeListener;
import net.wurstclient.events.SetOpaqueCubeListener.SetOpaqueCubeEvent;
import net.wurstclient.events.ShouldDrawSideListener;
import net.wurstclient.events.ShouldDrawSideListener.ShouldDrawSideEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"SurfaceXray", "Surface X-Ray", "SurfaceX-Ray", "surface xray",
	"surface x ray"})
public final class SurfaceXrayHack extends Hack
	implements ShouldDrawSideListener, UpdateListener, SetOpaqueCubeListener,
	GetAmbientOcclusionLightLevelListener
{
	private static final int MAX_COMPONENT_SIZE = 4096;
	private static final long CACHE_TTL = 200;
	private static final long CACHE_CLEAN_INTERVAL = 200;
	
	private final SliderSetting transparency =
		new SliderSetting("Surface opacity",
			"Controls how transparent the exposed surface should appear.", 0.5,
			0, 1, 0.01, ValueDisplay.PERCENTAGE)
		{
			@Override
			public void update()
			{
				super.update();
				onSettingsChanged(false);
			}
		};
	
	private final BlockListSetting targetBlocks =
		new BlockListSetting("Tracked blocks",
			"List of blocks that SurfaceXray will make semi-transparent.",
			"minecraft:lava", "minecraft:water")
		{
			@Override
			public void add(Block block)
			{
				int before = size();
				super.add(block);
				if(size() != before)
					onTrackedBlocksChanged();
			}
			
			@Override
			public void addRawName(String raw)
			{
				int before = size();
				super.addRawName(raw);
				if(size() != before)
					onTrackedBlocksChanged();
			}
			
			@Override
			public void remove(int index)
			{
				boolean valid = index >= 0 && index < size();
				super.remove(index);
				if(valid)
					onTrackedBlocksChanged();
			}
			
			@Override
			public void clear()
			{
				boolean changed = size() > 0;
				super.clear();
				if(changed)
					onTrackedBlocksChanged();
			}
			
			@Override
			public void resetToDefaults()
			{
				boolean changed = size() > 0;
				super.resetToDefaults();
				if(changed)
					onTrackedBlocksChanged();
			}
			
			@Override
			public void fromJson(com.google.gson.JsonElement json)
			{
				java.util.List<String> before =
					new java.util.ArrayList<>(getBlockNames());
				super.fromJson(json);
				if(!before.equals(getBlockNames()))
					onTrackedBlocksChanged();
			}
		};
	
	private final ConcurrentHashMap<Long, CacheEntry> visibilityCache =
		new ConcurrentHashMap<>();
	
	private ClientWorld cachedWorld;
	private long lastCleanupTick;
	
	public SurfaceXrayHack()
	{
		super("SurfaceXray");
		setCategory(Category.RENDER);
		addSetting(transparency);
		addSetting(targetBlocks);
	}
	
	@Override
	protected void onEnable()
	{
		clearCache();
		cachedWorld = null;
		lastCleanupTick = 0;
		
		EVENTS.add(ShouldDrawSideListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(SetOpaqueCubeListener.class, this);
		EVENTS.add(GetAmbientOcclusionLightLevelListener.class, this);
		
		onSettingsChanged(true);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(ShouldDrawSideListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(SetOpaqueCubeListener.class, this);
		EVENTS.remove(GetAmbientOcclusionLightLevelListener.class, this);
		
		clearCache();
		cachedWorld = null;
		onSettingsChanged(false);
	}
	
	@Override
	public void onUpdate()
	{
		ClientWorld world = MC.world;
		if(world == null || world != cachedWorld)
		{
			clearCache();
			cachedWorld = world;
			lastCleanupTick = 0;
			return;
		}
		
		long time = world.getTime();
		if(time - lastCleanupTick >= CACHE_CLEAN_INTERVAL)
		{
			pruneCache(time);
			lastCleanupTick = time;
		}
	}
	
	@Override
	public void onShouldDrawSide(ShouldDrawSideEvent event)
	{
		if(!isEnabled())
			return;
		
		BlockPos pos = event.getPos();
		if(pos == null)
			return;
		
		SurfaceState state = classifyBlock(event.getState(), pos);
		if(state == SurfaceState.INTERIOR)
			event.setRendered(false);
		else if(state == SurfaceState.SURFACE)
			event.setRendered(true);
	}
	
	@Override
	public void onSetOpaqueCube(SetOpaqueCubeEvent event)
	{
		if(isEnabled())
			event.cancel();
	}
	
	@Override
	public void onGetAmbientOcclusionLightLevel(
		GetAmbientOcclusionLightLevelEvent event)
	{
		if(isEnabled())
			event.setLightLevel(1);
	}
	
	public SurfaceState classifyBlock(BlockState state, BlockPos pos)
	{
		if(!isEnabled() || state == null || pos == null)
			return SurfaceState.NONE;
		
		if(!targetBlocks.matchesBlock(state.getBlock()))
			return SurfaceState.NONE;
		
		return classifyPos(pos, state.getBlock());
	}
	
	public SurfaceState classifyFluid(FluidState state, BlockPos pos)
	{
		if(!isEnabled() || state == null || pos == null)
			return SurfaceState.NONE;
		
		Block block = state.getBlockState().getBlock();
		if(!targetBlocks.matchesBlock(block))
			return SurfaceState.NONE;
		
		return classifyPos(pos, block);
	}
	
	public boolean isTarget(BlockState state)
	{
		return state != null && targetBlocks.matchesBlock(state.getBlock());
	}
	
	public boolean isTarget(Block block)
	{
		return block != null && targetBlocks.matchesBlock(block);
	}
	
	public float getSurfaceOpacity()
	{
		return transparency.getValueF();
	}
	
	public int getSurfaceOpacityMask()
	{
		int alpha = Math.max(0,
			Math.min(255, (int)Math.round(getSurfaceOpacity() * 255)));
		return alpha << 24 | 0x00FFFFFF;
	}
	
	public void openBlockListEditor(Screen prevScreen)
	{
		MC.setScreen(new EditBlockListScreen(prevScreen, targetBlocks));
	}
	
	private SurfaceState classifyPos(BlockPos pos, Block block)
	{
		ClientWorld world = MC.world;
		if(world == null)
			return SurfaceState.NONE;
		
		long key = pos.asLong();
		CacheEntry cached = visibilityCache.get(key);
		long time = world.getTime();
		
		if(cached != null)
		{
			if(cached.block == block && time - cached.lastUpdate <= CACHE_TTL)
				return cached.state;
			
			visibilityCache.remove(key);
		}
		
		computeComponent(world, pos, block, time);
		
		cached = visibilityCache.get(key);
		if(cached != null)
			return cached.state;
		
		SurfaceState fallback = classifyColumn(world, pos, block);
		visibilityCache.put(key, new CacheEntry(block, fallback, time));
		return fallback;
	}
	
	private void computeComponent(ClientWorld world, BlockPos start,
		Block block, long time)
	{
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		HashSet<Long> visited = new HashSet<>();
		ArrayList<BlockPos> component = new ArrayList<>();
		
		queue.add(start);
		visited.add(start.asLong());
		
		int processed = 0;
		
		while(!queue.isEmpty())
		{
			BlockPos current = queue.removeFirst();
			BlockState state = world.getBlockState(current);
			if(state.getBlock() != block)
				continue;
			
			component.add(current);
			processed++;
			
			if(processed >= MAX_COMPONENT_SIZE)
			{
				fillWithColumnFallback(world, component, block, time);
				return;
			}
			
			for(Direction dir : Direction.values())
			{
				BlockPos neighbor = current.offset(dir);
				long key = neighbor.asLong();
				if(!visited.add(key))
					continue;
				
				if(world.getBlockState(neighbor).getBlock() == block)
					queue.add(neighbor);
			}
		}
		
		if(component.isEmpty())
			return;
		
		HashMap<ColumnKey, Integer> topYByColumn = new HashMap<>();
		for(BlockPos pos : component)
		{
			ColumnKey columnKey = new ColumnKey(pos.getX(), pos.getZ(), block);
			topYByColumn.merge(columnKey, pos.getY(), Math::max);
		}
		
		for(BlockPos pos : component)
		{
			long posKey = pos.asLong();
			ColumnKey columnKey = new ColumnKey(pos.getX(), pos.getZ(), block);
			int topY = topYByColumn.get(columnKey);
			
			SurfaceState state = pos.getY() == topY ? SurfaceState.SURFACE
				: SurfaceState.INTERIOR;
			
			visibilityCache.put(posKey, new CacheEntry(block, state, time));
		}
	}
	
	private void fillWithColumnFallback(ClientWorld world,
		ArrayList<BlockPos> component, Block block, long time)
	{
		for(BlockPos pos : component)
		{
			long posKey = pos.asLong();
			SurfaceState state = classifyColumn(world, pos, block);
			visibilityCache.put(posKey, new CacheEntry(block, state, time));
		}
	}
	
	private SurfaceState classifyColumn(ClientWorld world, BlockPos pos,
		Block block)
	{
		BlockPos above = pos.up();
		if(world.getBlockState(above).getBlock() == block)
			return SurfaceState.INTERIOR;
		
		return SurfaceState.SURFACE;
	}
	
	private void pruneCache(long time)
	{
		ArrayList<Long> expired = new ArrayList<>();
		for(Map.Entry<Long, CacheEntry> entry : visibilityCache.entrySet())
			if(time - entry.getValue().lastUpdate > CACHE_TTL)
				expired.add(entry.getKey());
			
		for(Long key : expired)
			visibilityCache.remove(key);
	}
	
	private void clearCache()
	{
		visibilityCache.clear();
	}
	
	private void onTrackedBlocksChanged()
	{
		onSettingsChanged(true);
	}
	
	private void onSettingsChanged(boolean clearCache)
	{
		if(clearCache)
		{
			clearCache();
			lastCleanupTick = 0;
		}
		
		if(MC.worldRenderer != null)
			MC.worldRenderer.reload();
	}
	
	private static final class CacheEntry
	{
		private final Block block;
		private final SurfaceState state;
		private final long lastUpdate;
		
		private CacheEntry(Block block, SurfaceState state, long lastUpdate)
		{
			this.block = block;
			this.state = state;
			this.lastUpdate = lastUpdate;
		}
	}
	
	public static enum SurfaceState
	{
		NONE,
		SURFACE,
		INTERIOR;
	}
	
	private static final class ColumnKey
	{
		private final int x;
		private final int z;
		private final Block block;
		
		private ColumnKey(int x, int z, Block block)
		{
			this.x = x;
			this.z = z;
			this.block = block;
		}
		
		@Override
		public boolean equals(Object obj)
		{
			if(this == obj)
				return true;
			if(!(obj instanceof ColumnKey other))
				return false;
			return x == other.x && z == other.z && block == other.block;
		}
		
		@Override
		public int hashCode()
		{
			return Objects.hash(x, z, block);
		}
	}
}
