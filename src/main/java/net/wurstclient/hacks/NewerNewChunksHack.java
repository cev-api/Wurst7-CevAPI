/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.io.IOException;
import java.io.BufferedReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.RenderUtils;

public final class NewerNewChunksHack extends Hack
	implements UpdateListener, RenderListener
{
	public enum DetectMode
	{
		Normal,
		IgnoreBlockExploit,
		BlockExploitMode
	}
	
	public enum ShapeMode
	{
		Sides,
		Lines,
		Both
	}
	
	private static final Direction[] SEARCH_DIRS =
		new Direction[]{Direction.EAST, Direction.NORTH, Direction.WEST,
			Direction.SOUTH, Direction.UP};
	
	private static final Set<Block> DEEPSLATE_BLOCKS = Set.of(Blocks.DEEPSLATE,
		Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_COPPER_ORE,
		Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_GOLD_ORE,
		Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
		Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DEEPSLATE_EMERALD_ORE);
	
	private static final Set<Block> ORE_BLOCKS = Set.of(Blocks.COAL_ORE,
		Blocks.DEEPSLATE_COAL_ORE, Blocks.COPPER_ORE,
		Blocks.DEEPSLATE_COPPER_ORE, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
		Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.LAPIS_ORE,
		Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DIAMOND_ORE,
		Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.REDSTONE_ORE,
		Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.EMERALD_ORE,
		Blocks.DEEPSLATE_EMERALD_ORE);
	
	private static final Set<Block> NEW_OVERWORLD_BLOCKS =
		Set.of(Blocks.DEEPSLATE, Blocks.AMETHYST_BLOCK, Blocks.BUDDING_AMETHYST,
			Blocks.AZALEA, Blocks.FLOWERING_AZALEA, Blocks.BIG_DRIPLEAF,
			Blocks.BIG_DRIPLEAF_STEM, Blocks.SMALL_DRIPLEAF, Blocks.CAVE_VINES,
			Blocks.CAVE_VINES_PLANT, Blocks.SPORE_BLOSSOM, Blocks.COPPER_ORE,
			Blocks.DEEPSLATE_COPPER_ORE, Blocks.DEEPSLATE_IRON_ORE,
			Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
			Blocks.DEEPSLATE_EMERALD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
			Blocks.DEEPSLATE_LAPIS_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
			Blocks.GLOW_LICHEN, Blocks.RAW_COPPER_BLOCK, Blocks.RAW_IRON_BLOCK,
			Blocks.DRIPSTONE_BLOCK, Blocks.MOSS_BLOCK, Blocks.MOSS_CARPET,
			Blocks.POINTED_DRIPSTONE, Blocks.SMOOTH_BASALT, Blocks.TUFF,
			Blocks.CALCITE, Blocks.HANGING_ROOTS, Blocks.ROOTED_DIRT,
			Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES,
			Blocks.POWDER_SNOW);
	
	private static final Set<Block> NEW_NETHER_BLOCKS = createNewNetherBlocks();
	
	private static final Field PAL_CONTAINER_DATA_FIELD =
		getField(PalettedContainer.class, "data");
	private static final Field PAL_DATA_PALETTE_FIELD = getPaletteField();
	private static final Class<?> PAL_STRATEGY_CLASS = getPalStrategyClass();
	private static final Pattern CHUNK_COORD_PATTERN =
		Pattern.compile("-?\\d+");
	
	private final EnumSetting<DetectMode> detectMode = new EnumSetting<>(
		"Chunk Detection Mode", DetectMode.values(), DetectMode.Normal);
	
	private final CheckboxSetting paletteExploit = new CheckboxSetting(
		"PaletteExploit",
		"Detects chunks using chunk-section palette characteristics.", true);
	
	private final CheckboxSetting beingUpdatedDetector = new CheckboxSetting(
		"Detection for chunks that haven't been explored since <=1.17",
		"Marks chunks that appear to be getting upgraded from older terrain.",
		true);
	
	private final CheckboxSetting overworldOldChunksDetector =
		new CheckboxSetting("Pre 1.17 Overworld OldChunk Detector",
			"Marks chunks as generated in an older overworld version.", true);
	
	private final CheckboxSetting netherOldChunksDetector =
		new CheckboxSetting("Pre 1.16 Nether OldChunk Detector",
			"Marks chunks as generated in an older nether version.", true);
	
	private final CheckboxSetting endOldChunksDetector =
		new CheckboxSetting("Pre 1.13 End OldChunk Detector",
			"Marks chunks as generated in an older end version.", true);
	
	private final CheckboxSetting liquidExploit =
		new CheckboxSetting("LiquidExploit",
			"Estimates new chunks based on flowing liquids.", false);
	
	private final CheckboxSetting blockUpdateExploit =
		new CheckboxSetting("BlockUpdateExploit",
			"Uses block updates as additional chunk activity signal.", false);
	
	private final CheckboxSetting removeOnDisable = new CheckboxSetting(
		"RemoveOnModuleDisabled", "Removes cached chunks on disable.", true);
	
	private final CheckboxSetting removeOnLeaveWorldOrChangeDimensions =
		new CheckboxSetting("RemoveOnLeaveWorldOrChangeDimensions",
			"Removes cached chunks when leaving the world or changing dimensions.",
			true);
	
	private final CheckboxSetting removeOutsideRenderDistance =
		new CheckboxSetting("RemoveOutsideRenderDistance",
			"Removes cached chunks once they leave render distance.", false);
	
	private final CheckboxSetting saveChunkData = new CheckboxSetting(
		"SaveChunkData", "Saves detected chunks to disk.", true);
	
	private final CheckboxSetting loadChunkData = new CheckboxSetting(
		"LoadChunkData", "Loads saved chunks when enabling this hack.", true);
	
	private final CheckboxSetting autoReloadChunks = new CheckboxSetting(
		"AutoReloadChunks", "Reloads chunk savefiles after a delay.", false);
	
	private final SliderSetting autoReloadDelaySeconds = new SliderSetting(
		"AutoReloadDelayInSeconds", 60, 1, 300, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting alarms =
		new CheckboxSetting("Enable Alarms for NewChunks",
			"Rings a sound when new chunks appear.", false);
	
	private final SliderSetting amountOfRings =
		new SliderSetting("Amount Of Rings", 1, 1, 10, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting ringDelay = new SliderSetting(
		"Delay Between Rings (ticks)", 20, 1, 100, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting volume =
		new SliderSetting("Volume", 1, 0, 1, 0.05, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting pitch =
		new SliderSetting("Pitch", 1, 0.5, 2, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting oldChunkAlarms =
		new CheckboxSetting("Enable Alarms for OldChunks",
			"Rings a sound when old chunks appear.", false);
	
	private final CheckboxSetting beingUpdatedChunkAlarms =
		new CheckboxSetting("Enable Alarms for BeingUpdatedChunks",
			"Rings a sound when being-updated chunks appear.", false);
	
	private final CheckboxSetting oldVersionChunkAlarms =
		new CheckboxSetting("Enable Alarms for OldVersionChunks",
			"Rings a sound when old-version chunks appear.", false);
	
	private final CheckboxSetting blockExploitChunkAlarms =
		new CheckboxSetting("Enable Alarms for BlockExploitChunks",
			"Rings a sound when block exploit chunks appear.", false);
	
	private final ButtonSetting deleteChunkData = new ButtonSetting(
		"Delete Chunk Data", "Deletes chunk data for current world/dimension.",
		this::confirmDeleteChunkData);
	
	private final SliderSetting renderDistance = new SliderSetting(
		"Render-Distance(Chunks)", 128, 6, 1024, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting renderHeight = new SliderSetting(
		"Render-Height", 0, -64, 319, 1, ValueDisplay.INTEGER);
	
	private final EnumSetting<ShapeMode> shapeMode =
		new EnumSetting<>("Shape Mode", ShapeMode.values(), ShapeMode.Both);
	
	private final ColorSetting newChunksSideColor =
		new ColorSetting("New Chunks Side Color", new Color(255, 0, 0));
	private final ColorSetting newChunksLineColor =
		new ColorSetting("New Chunks Line Color", new Color(255, 0, 0));
	
	private final ColorSetting blockExploitChunksSideColor = new ColorSetting(
		"BlockExploit Chunks Side Color", new Color(0, 0, 255));
	private final ColorSetting blockExploitChunksLineColor = new ColorSetting(
		"BlockExploit Chunks Line Color", new Color(0, 0, 255));
	
	private final ColorSetting oldChunksSideColor =
		new ColorSetting("Old Chunks Side Color", new Color(0, 255, 0));
	private final ColorSetting oldChunksLineColor =
		new ColorSetting("Old Chunks Line Color", new Color(0, 255, 0));
	
	private final ColorSetting beingUpdatedChunksSideColor = new ColorSetting(
		"BeingUpdated Chunks Side Color", new Color(255, 210, 0));
	private final ColorSetting beingUpdatedChunksLineColor = new ColorSetting(
		"BeingUpdated Chunks Line Color", new Color(255, 210, 0));
	
	private final ColorSetting oldVersionChunksSideColor = new ColorSetting(
		"OldVersion Chunks Side Color", new Color(190, 255, 0));
	private final ColorSetting oldVersionChunksLineColor = new ColorSetting(
		"OldVersion Chunks Line Color", new Color(190, 255, 0));
	
	// IO/window tuning (user-configurable)
	private final SliderSetting persistedWindowRadiusChunks =
		new SliderSetting("Persisted-Window-Radius(Chunks)", 128, 16, 256, 1,
			ValueDisplay.INTEGER);
	private final SliderSetting windowRefreshDistanceChunks = new SliderSetting(
		"Window-Refresh-Distance(Chunks)", 48, 4, 128, 1, ValueDisplay.INTEGER);
	
	private final Set<ChunkPos> newChunks = ConcurrentHashMap.newKeySet();
	private final Set<ChunkPos> oldChunks = ConcurrentHashMap.newKeySet();
	private final Set<ChunkPos> tickExploitChunks =
		ConcurrentHashMap.newKeySet();
	private final Set<ChunkPos> beingUpdatedOldChunks =
		ConcurrentHashMap.newKeySet();
	private final Set<ChunkPos> oldGenerationChunks =
		ConcurrentHashMap.newKeySet();
	
	// Region-bucket indices (32x32 chunk tiles) for O(visible) queries
	private static final int REGION_SHIFT = 5; // 32x32 tiles
	private final Map<Long, Set<ChunkPos>> idxNew = new ConcurrentHashMap<>();
	private final Map<Long, Set<ChunkPos>> idxOld = new ConcurrentHashMap<>();
	private final Map<Long, Set<ChunkPos>> idxTick = new ConcurrentHashMap<>();
	private final Map<Long, Set<ChunkPos>> idxBeingUpdated =
		new ConcurrentHashMap<>();
	private final Map<Long, Set<ChunkPos>> idxOldGen =
		new ConcurrentHashMap<>();
	private final Set<ChunkPos> newChunksView =
		Collections.unmodifiableSet(newChunks);
	private final Set<ChunkPos> oldChunksView =
		Collections.unmodifiableSet(oldChunks);
	
	private final Set<Path> filePaths = Set.of(Paths.NewChunkData,
		Paths.OldChunkData, Paths.BlockExploitChunkData,
		Paths.BeingUpdatedChunkData, Paths.OldGenerationChunkData);
	
	private String serverKey = "unknown";
	private String dimensionKey = "unknown";
	private boolean loadedThisSession;
	private int autoReloadTicks;
	private long lastMapaRescanTick = Long.MIN_VALUE;
	private boolean autoFlyRenderSuppressed;
	
	// Windowed disk-load parameters (legacy defaults, superseded by settings)
	private static final int WINDOW_RADIUS_CHUNKS = 64; // fallback only
	private static final int REFRESH_DISTANCE_CHUNKS = 32; // fallback only
	private ChunkPos lastWindowCenter;
	private volatile ExecutorService windowLoader = createWindowLoader();
	private volatile boolean windowLoadInProgress;
	
	private static ExecutorService createWindowLoader()
	{
		return Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "nnc-window-loader");
			t.setDaemon(true);
			return t;
		});
	}
	
	private void ensureWindowLoader()
	{
		ExecutorService exec = windowLoader;
		if(exec == null || exec.isShutdown() || exec.isTerminated())
			windowLoader = createWindowLoader();
	}
	
	private int newChunkAlarmTicks;
	private int newChunkAlarmRingsLeft;
	private boolean newChunkAlarmPending;
	private int oldChunkAlarmTicks;
	private int oldChunkAlarmRingsLeft;
	private boolean oldChunkAlarmPending;
	private int beingUpdatedAlarmTicks;
	private int beingUpdatedAlarmRingsLeft;
	private boolean beingUpdatedAlarmPending;
	private int oldVersionAlarmTicks;
	private int oldVersionAlarmRingsLeft;
	private boolean oldVersionAlarmPending;
	private int blockExploitAlarmTicks;
	private int blockExploitAlarmRingsLeft;
	private boolean blockExploitAlarmPending;
	
	private int deleteWarningTicks = 200;
	private int deleteWarningPresses;
	private ClientLevel trackedLevel;
	private boolean mapaTrackingActiveLastTick;
	
	private enum AlarmType
	{
		NEW,
		OLD,
		BEING_UPDATED,
		OLD_VERSION,
		BLOCK_EXPLOIT
	}
	
	private record ChunkClassification(boolean isNewChunk,
		boolean isOldGeneration, boolean chunkIsBeingUpdated)
	{}
	
	private static Set<Block> createNewNetherBlocks()
	{
		HashSet<Block> blocks = new HashSet<>(Set.of(Blocks.ANCIENT_DEBRIS,
			Blocks.BASALT, Blocks.BLACKSTONE, Blocks.GILDED_BLACKSTONE,
			Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRIMSON_STEM,
			Blocks.CRIMSON_NYLIUM, Blocks.NETHER_GOLD_ORE, Blocks.WARPED_NYLIUM,
			Blocks.WARPED_STEM, Blocks.TWISTING_VINES, Blocks.WEEPING_VINES,
			Blocks.BONE_BLOCK, Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN,
			Blocks.SOUL_SOIL, Blocks.SOUL_FIRE));
		
		Block chain = BuiltInRegistries.BLOCK
			.getValue(Identifier.parse("minecraft:chain"));
		if(chain != null && chain != Blocks.AIR)
			blocks.add(chain);
		
		return Collections.unmodifiableSet(blocks);
	}
	
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
			Class<?> dataClass = Class.forName(
				"net.minecraft.world.level.chunk.PalettedContainer$Data");
			Field field = dataClass.getDeclaredField("palette");
			field.setAccessible(true);
			return field;
		}catch(ReflectiveOperationException e)
		{
			return null;
		}
	}
	
	private static Class<?> getPalStrategyClass()
	{
		try
		{
			return Class.forName(
				"net.minecraft.world.level.chunk.PalettedContainer$Strategy");
		}catch(ClassNotFoundException e)
		{
			return null;
		}
	}
	
	private static Object getPalStrategy(String name)
	{
		if(PAL_STRATEGY_CLASS == null)
			return null;
		
		try
		{
			Field field = PAL_STRATEGY_CLASS.getDeclaredField(name);
			field.setAccessible(true);
			return field.get(null);
		}catch(ReflectiveOperationException e)
		{
			return null;
		}
	}
	
	@SuppressWarnings("unchecked")
	private static <T> List<T> getRawPaletteEntries(Object paletteContainer)
	{
		if(paletteContainer == null || PAL_CONTAINER_DATA_FIELD == null
			|| PAL_DATA_PALETTE_FIELD == null)
			return List.of();
		
		try
		{
			Object data = PAL_CONTAINER_DATA_FIELD.get(paletteContainer);
			Object palette = PAL_DATA_PALETTE_FIELD.get(data);
			if(palette == null)
				return List.of();
			
			int size = getPaletteSize(palette);
			if(size <= 0)
				return List.of();
			
			var entries = new java.util.ArrayList<T>(size);
			for(int i = 0; i < size; i++)
			{
				T entry = (T)getPaletteEntry(palette, i);
				if(entry != null)
					entries.add(entry);
			}
			return entries;
		}catch(ReflectiveOperationException e)
		{
			return List.of();
		}
	}
	
	private static int getPaletteSize(Object palette)
	{
		try
		{
			return ((Number)palette.getClass().getMethod("getSize")
				.invoke(palette)).intValue();
		}catch(ReflectiveOperationException ignored)
		{
			try
			{
				return ((Number)palette.getClass().getMethod("size")
					.invoke(palette)).intValue();
			}catch(ReflectiveOperationException ignoredToo)
			{
				return 0;
			}
		}
	}
	
	private static Object getPaletteEntry(Object palette, int index)
	{
		try
		{
			return palette.getClass().getMethod("get", int.class)
				.invoke(palette, index);
		}catch(ReflectiveOperationException ignored)
		{
			try
			{
				return palette.getClass().getMethod("valueFor", int.class)
					.invoke(palette, index);
			}catch(ReflectiveOperationException ignoredToo)
			{
				return null;
			}
		}
	}
	
	private static final class Paths
	{
		private static final Path NewChunkData = Path.of("NewChunkData.txt");
		private static final Path OldChunkData = Path.of("OldChunkData.txt");
		private static final Path BlockExploitChunkData =
			Path.of("BlockExploitChunkData.txt");
		private static final Path BeingUpdatedChunkData =
			Path.of("BeingUpdatedChunkData.txt");
		private static final Path OldGenerationChunkData =
			Path.of("OldGenerationChunkData.txt");
	}
	
	public NewerNewChunksHack()
	{
		super("NewerNewChunks");
		setCategory(Category.INTEL);
		
		addSetting(detectMode);
		addSetting(paletteExploit);
		addSetting(beingUpdatedDetector);
		addSetting(overworldOldChunksDetector);
		addSetting(netherOldChunksDetector);
		addSetting(endOldChunksDetector);
		addSetting(liquidExploit);
		addSetting(blockUpdateExploit);
		addSetting(removeOnDisable);
		addSetting(removeOnLeaveWorldOrChangeDimensions);
		addSetting(removeOutsideRenderDistance);
		addSetting(saveChunkData);
		addSetting(loadChunkData);
		addSetting(autoReloadChunks);
		addSetting(autoReloadDelaySeconds);
		addSetting(persistedWindowRadiusChunks);
		addSetting(windowRefreshDistanceChunks);
		addSetting(deleteChunkData);
		addSetting(renderDistance);
		addSetting(renderHeight);
		addSetting(shapeMode);
		addSetting(newChunksSideColor);
		addSetting(newChunksLineColor);
		addSetting(blockExploitChunksSideColor);
		addSetting(blockExploitChunksLineColor);
		addSetting(oldChunksSideColor);
		addSetting(oldChunksLineColor);
		addSetting(beingUpdatedChunksSideColor);
		addSetting(beingUpdatedChunksLineColor);
		addSetting(oldVersionChunksSideColor);
		addSetting(oldVersionChunksLineColor);
		addSetting(alarms);
		addSetting(amountOfRings);
		addSetting(ringDelay);
		addSetting(volume);
		addSetting(pitch);
		addSetting(oldChunkAlarms);
		addSetting(beingUpdatedChunkAlarms);
		addSetting(oldVersionChunkAlarms);
		addSetting(blockExploitChunkAlarms);
	}
	
	@Override
	protected void onEnable()
	{
		resolveWorldKeys();
		ensureDataFiles();
		if(loadChunkData.isChecked())
		{
			if(MC.player != null)
			{
				ChunkPos c = MC.player.chunkPosition();
				requestWindowLoad(c.x(), c.z(), getPrefetchRadius());
				lastWindowCenter = c;
			}else
			{
				// No player yet; defer until update tick
			}
		}
		
		loadedThisSession = loadChunkData.isChecked();
		autoReloadTicks = 0;
		newChunkAlarmTicks = 0;
		newChunkAlarmRingsLeft = 0;
		newChunkAlarmPending = false;
		oldChunkAlarmTicks = 0;
		oldChunkAlarmRingsLeft = 0;
		oldChunkAlarmPending = false;
		beingUpdatedAlarmTicks = 0;
		beingUpdatedAlarmRingsLeft = 0;
		beingUpdatedAlarmPending = false;
		oldVersionAlarmTicks = 0;
		oldVersionAlarmRingsLeft = 0;
		oldVersionAlarmPending = false;
		blockExploitAlarmTicks = 0;
		blockExploitAlarmRingsLeft = 0;
		blockExploitAlarmPending = false;
		deleteWarningTicks = 200;
		deleteWarningPresses = 0;
		trackedLevel = MC.level;
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		if(removeOnDisable.isChecked() && !isMapaTrackingActive())
			clearChunkData();
		try
		{
			windowLoader.shutdownNow();
		}catch(Exception ignored)
		{}
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null)
		{
			if(trackedLevel != null
				&& removeOnLeaveWorldOrChangeDimensions.isChecked())
				clearChunkData();
			trackedLevel = null;
			loadedThisSession = false;
			return;
		}
		
		if(MC.player == null)
			return;
		
		resolveWorldKeys();
		ensureDataFiles();
		ensureTrackingWorld();
		
		if(deleteWarningTicks <= 100)
			deleteWarningTicks++;
		else
			deleteWarningPresses = 0;
		
		if(!loadedThisSession && loadChunkData.isChecked())
		{
			if(MC.player != null)
			{
				ChunkPos c = MC.player.chunkPosition();
				requestWindowLoad(c.x(), c.z(), getPrefetchRadius());
				lastWindowCenter = c;
				loadedThisSession = true;
			}
		}
		
		if(autoReloadChunks.isChecked())
		{
			autoReloadTicks++;
			if(autoReloadTicks >= autoReloadDelaySeconds.getValueI() * 20)
			{
				// Soft refresh: clear and windowed reload near current pos
				clearChunkData();
				if(loadChunkData.isChecked() && MC.player != null)
				{
					ChunkPos c = MC.player.chunkPosition();
					requestWindowLoad(c.x(), c.z(), getPrefetchRadius());
					lastWindowCenter = c;
				}
				autoReloadTicks = 0;
			}
		}
		
		if(removeOutsideRenderDistance.isChecked())
			removeChunksOutsideRenderDistance();
		
		processAlarmState(AlarmType.NEW);
		processAlarmState(AlarmType.OLD);
		processAlarmState(AlarmType.BEING_UPDATED);
		processAlarmState(AlarmType.OLD_VERSION);
		processAlarmState(AlarmType.BLOCK_EXPLOIT);
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		if(autoFlyRenderSuppressed)
			return;
		
		if(MC.player == null)
			return;
		
		double y = renderHeight.getValue();
		double maxDist = renderDistance.getValue() * 16;
		BlockPos playerPos = MC.player.blockPosition();
		
		ChunkPos pc = MC.player.chunkPosition();
		int visR = (int)Math.ceil(maxDist / 16.0);
		Set<ChunkPos> visOld = getOldChunksInRange(pc.x(), pc.z(), visR);
		Set<ChunkPos> visNew = getNewChunksInRange(pc.x(), pc.z(), visR);
		Set<ChunkPos> visTick =
			getBlockExploitChunksInRange(pc.x(), pc.z(), visR);
		Set<ChunkPos> visBeing =
			getBeingUpdatedChunksInRange(pc.x(), pc.z(), visR);
		Set<ChunkPos> visOldGen =
			getOldGenerationChunksInRange(pc.x(), pc.z(), visR);
		List<AABB> oldBoxes = collectBoxes(visOld, y, playerPos, maxDist);
		List<AABB> newBoxes = collectBoxes(visNew, y, playerPos, maxDist);
		List<AABB> tickBoxes = collectBoxes(visTick, y, playerPos, maxDist);
		List<AABB> beingUpdatedBoxes =
			collectBoxes(visBeing, y, playerPos, maxDist);
		List<AABB> oldVersionBoxes =
			collectBoxes(visOldGen, y, playerPos, maxDist);
		
		if(!oldBoxes.isEmpty())
		{
			renderBoxes(matrices, oldBoxes, oldChunksSideColor.getColorI(40),
				oldChunksLineColor.getColorI(80));
		}
		
		if(!newBoxes.isEmpty())
		{
			renderBoxes(matrices, newBoxes, newChunksSideColor.getColorI(95),
				newChunksLineColor.getColorI(205));
		}
		
		if(!beingUpdatedBoxes.isEmpty())
		{
			renderBoxes(matrices, beingUpdatedBoxes,
				beingUpdatedChunksSideColor.getColorI(60),
				beingUpdatedChunksLineColor.getColorI(100));
		}
		
		if(!oldVersionBoxes.isEmpty())
		{
			renderBoxes(matrices, oldVersionBoxes,
				oldVersionChunksSideColor.getColorI(40),
				oldVersionChunksLineColor.getColorI(80));
		}
		
		if(!tickBoxes.isEmpty())
		{
			int side;
			int line;
			DetectMode mode = detectMode.getSelected();
			if(!blockUpdateExploit.isChecked()
				|| mode == DetectMode.IgnoreBlockExploit)
			{
				side = oldChunksSideColor.getColorI(40);
				line = oldChunksLineColor.getColorI(80);
			}else if(mode == DetectMode.Normal)
			{
				side = newChunksSideColor.getColorI(95);
				line = newChunksLineColor.getColorI(205);
			}else
			{
				side = blockExploitChunksSideColor.getColorI(75);
				line = blockExploitChunksLineColor.getColorI(170);
			}
			renderBoxes(matrices, tickBoxes, side, line);
		}
	}
	
	public void setAutoFlyRenderSuppressed(boolean suppressed)
	{
		autoFlyRenderSuppressed = suppressed;
	}
	
	public void afterLoadChunk(int x, int z)
	{
		if(!isTrackingActive() || MC.level == null)
			return;
		ensureTrackingWorld();
		
		ChunkPos chunkPos = new ChunkPos(x, z);
		if(containsAny(chunkPos))
			return;
		
		LevelChunk chunk =
			MC.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
		if(chunk == null || chunk.isEmpty())
			return;
		
		if(paletteExploit.isChecked())
		{
			ChunkClassification classification = classifyChunk(chunk);
			boolean isNewChunk = classification.isNewChunk();
			boolean isOldGeneration = classification.isOldGeneration();
			boolean chunkIsBeingUpdated = classification.chunkIsBeingUpdated();
			boolean allowNew = isEnd() ? isNewChunk : !isOldGeneration;
			
			if(isNewChunk && !chunkIsBeingUpdated && allowNew)
			{
				markNew(chunkPos);
				return;
			}
			
			if(!isNewChunk && !chunkIsBeingUpdated && isOldGeneration)
			{
				markOldGeneration(chunkPos);
				return;
			}
			
			if(chunkIsBeingUpdated)
			{
				markBeingUpdated(chunkPos);
				return;
			}
			
			if(!isNewChunk)
			{
				markOld(chunkPos);
				return;
			}
		}
		
		if(liquidExploit.isChecked() && hasFlowingFluid(chunk))
			markOld(chunkPos);
	}
	
	private ChunkClassification classifyChunk(LevelChunk chunk)
	{
		boolean isNewChunk = false;
		boolean isOldGeneration = false;
		boolean chunkIsBeingUpdated = false;
		LevelChunkSection[] sections = chunk.getSections();
		
		if(overworldOldChunksDetector.isChecked() && isOverworld())
			isOldGeneration = isOverworldOldGeneration(chunk);
		
		if(!isOldGeneration && netherOldChunksDetector.isChecked()
			&& isNether())
			isOldGeneration = isNetherOldGeneration(chunk);
		
		if(!isOldGeneration && endOldChunksDetector.isChecked() && isEnd())
			isOldGeneration = hasEndBiomeFromPalette(sections);
		
		if(paletteExploit.isChecked())
		{
			boolean firstChunkAppearsNew = false;
			int loops = 0;
			int newChunkQuantifier = 0;
			int oldChunkQuantifier = 0;
			
			for(LevelChunkSection section : sections)
			{
				if(section == null)
					continue;
				
				int isNewSection = 0;
				int isBeingUpdatedSection = 0;
				
				if(!section.hasOnlyAir())
				{
					PalettedContainer<BlockState> blockStates =
						section.getStates();
					List<BlockState> paletteEntries =
						getRawPaletteEntries(blockStates);
					int blockPaletteLength = paletteEntries.size();
					
					if(isHashMapPalette(blockStates))
					{
						int bstatesSize = countDistinctSectionStates(section);
						if(bstatesSize <= 1)
							bstatesSize = blockPaletteLength;
						if(bstatesSize < blockPaletteLength)
							isNewSection = 2;
					}
					
					for(int i = 0; i < blockPaletteLength; i++)
					{
						Block block = paletteEntries.get(i).getBlock();
						if(i == 0 && loops == 0 && block == Blocks.AIR
							&& !isEnd())
							firstChunkAppearsNew = true;
						
						if(i == 0 && block == Blocks.AIR && !isNether()
							&& !isEnd())
							isNewSection++;
						
						if(i == 1
							&& (block == Blocks.WATER || block == Blocks.STONE
								|| block == Blocks.GRASS_BLOCK
								|| block == Blocks.SNOW_BLOCK)
							&& !isNether() && !isEnd())
							isNewSection++;
						
						if(i == 2
							&& (block == Blocks.SNOW_BLOCK
								|| block == Blocks.DIRT
								|| block == Blocks.POWDER_SNOW)
							&& !isNether() && !isEnd())
							isNewSection++;
						
						if(loops == 4 && block == Blocks.BEDROCK && !isNether()
							&& !isEnd() && beingUpdatedDetector.isChecked())
							chunkIsBeingUpdated = true;
						
						if(block == Blocks.AIR && (isNether() || isEnd()))
							isBeingUpdatedSection++;
					}
					
					if(isBeingUpdatedSection >= 2)
						oldChunkQuantifier++;
					if(isNewSection >= 2)
						newChunkQuantifier++;
				}
				
				if(isEnd() && isEndNewChunkByPalette(section))
					isNewChunk = true;
				
				if(!section.hasOnlyAir())
					loops++;
			}
			
			if(loops > 0)
			{
				if(beingUpdatedDetector.isChecked() && (isNether() || isEnd()))
				{
					double oldPercentage =
						((double)oldChunkQuantifier / loops) * 100;
					if(oldPercentage >= 25)
						chunkIsBeingUpdated = true;
				}else if(!isNether() && !isEnd())
				{
					double percentage =
						((double)newChunkQuantifier / loops) * 100;
					if(percentage >= 51)
						isNewChunk = true;
				}
			}
			
			if(firstChunkAppearsNew)
				isNewChunk = true;
		}
		
		return new ChunkClassification(isNewChunk, isOldGeneration,
			chunkIsBeingUpdated);
	}
	
	public void afterUpdateBlock(BlockPos pos)
	{
		if(!isTrackingActive() || MC.level == null)
			return;
		ensureTrackingWorld();
		
		handleBlockLikeUpdate(pos, MC.level.getBlockState(pos), true);
	}
	
	public void afterChunkDeltaUpdate(BlockPos pos, BlockState state)
	{
		if(!isTrackingActive() || MC.level == null)
			return;
		ensureTrackingWorld();
		handleBlockLikeUpdate(pos, state, false);
	}
	
	private void handleBlockLikeUpdate(BlockPos pos, BlockState state,
		boolean allowTickExploit)
	{
		ChunkPos chunkPos = ChunkPos.containing(pos);
		if(allowTickExploit && blockUpdateExploit.isChecked()
			&& !containsAny(chunkPos))
			markTickExploit(chunkPos);
		
		FluidState stateFluid = state.getFluidState();
		if(!liquidExploit.isChecked() || stateFluid.isEmpty()
			|| stateFluid.isSource() || containsFinalChunkType(chunkPos))
			return;
		
		for(Direction dir : SEARCH_DIRS)
		{
			BlockPos neighbor = pos.relative(dir);
			FluidState neighborFluid =
				MC.level.getBlockState(neighbor).getFluidState();
			if(!neighborFluid.isEmpty() && neighborFluid.isSource())
			{
				tickExploitChunks.remove(chunkPos);
				markNew(chunkPos);
				return;
			}
		}
	}
	
	public Set<ChunkPos> getNewChunks()
	{
		return Set.copyOf(newChunks);
	}
	
	public Set<ChunkPos> getNewChunksLiveView()
	{
		return newChunksView;
	}
	
	public Set<ChunkPos> getOldChunks()
	{
		return Set.copyOf(oldChunks);
	}
	
	// Visible-range queries using region buckets
	public Set<ChunkPos> getNewChunksInRange(int cx, int cz, int radius)
	{
		return getChunksInRange(idxNew, cx, cz, radius);
	}
	
	public Set<ChunkPos> getOldChunksInRange(int cx, int cz, int radius)
	{
		return getChunksInRange(idxOld, cx, cz, radius);
	}
	
	public Set<ChunkPos> getBlockExploitChunksInRange(int cx, int cz,
		int radius)
	{
		return getChunksInRange(idxTick, cx, cz, radius);
	}
	
	public Set<ChunkPos> getBeingUpdatedChunksInRange(int cx, int cz,
		int radius)
	{
		return getChunksInRange(idxBeingUpdated, cx, cz, radius);
	}
	
	public Set<ChunkPos> getOldGenerationChunksInRange(int cx, int cz,
		int radius)
	{
		return getChunksInRange(idxOldGen, cx, cz, radius);
	}
	
	public Set<ChunkPos> getOldChunksLiveView()
	{
		return oldChunksView;
	}
	
	public boolean isNewChunk(ChunkPos chunkPos)
	{
		return chunkPos != null && newChunks.contains(chunkPos);
	}
	
	public boolean isOldChunk(ChunkPos chunkPos)
	{
		return chunkPos != null && oldChunks.contains(chunkPos);
	}
	
	public Set<ChunkPos> getBlockExploitChunks()
	{
		return Set.copyOf(tickExploitChunks);
	}
	
	public Set<ChunkPos> getBeingUpdatedChunks()
	{
		return Set.copyOf(beingUpdatedOldChunks);
	}
	
	public Set<ChunkPos> getOldGenerationChunks()
	{
		return Set.copyOf(oldGenerationChunks);
	}
	
	public int getNewChunksColorI()
	{
		return newChunksLineColor.getColorI();
	}
	
	public int getOldChunksColorI()
	{
		return oldChunksLineColor.getColorI();
	}
	
	public int getBlockExploitChunksColorI()
	{
		return blockExploitChunksLineColor.getColorI();
	}
	
	public int getBeingUpdatedChunksColorI()
	{
		return beingUpdatedChunksLineColor.getColorI();
	}
	
	public int getOldGenerationChunksColorI()
	{
		return oldVersionChunksLineColor.getColorI();
	}
	
	public void syncMapaTrackingState(boolean mapaTrackingActive)
	{
		if(!mapaTrackingActive)
		{
			mapaTrackingActiveLastTick = false;
			lastMapaRescanTick = Long.MIN_VALUE;
			return;
		}
		
		if(MC.level == null || MC.player == null)
			return;
		
		boolean activatedNow = !mapaTrackingActiveLastTick;
		mapaTrackingActiveLastTick = true;
		
		ensureTrackingWorld();
		
		long tick = MC.level.getGameTime();
		if(activatedNow || tick != lastMapaRescanTick && tick % 40L == 0L)
		{
			scanLoadedChunksAroundPlayer();
			// Refresh windowed persisted-data load when player moved enough
			refreshWindowedLoadIfNeeded();
			lastMapaRescanTick = tick;
		}
	}
	
	private boolean containsAny(ChunkPos chunkPos)
	{
		return newChunks.contains(chunkPos) || oldChunks.contains(chunkPos)
			|| tickExploitChunks.contains(chunkPos)
			|| beingUpdatedOldChunks.contains(chunkPos)
			|| oldGenerationChunks.contains(chunkPos);
	}
	
	private boolean containsFinalChunkType(ChunkPos chunkPos)
	{
		return newChunks.contains(chunkPos) || oldChunks.contains(chunkPos)
			|| beingUpdatedOldChunks.contains(chunkPos)
			|| oldGenerationChunks.contains(chunkPos);
	}
	
	private boolean isTrackingActive()
	{
		return isEnabled() || isMapaTrackingActive();
	}
	
	private boolean isMapaTrackingActive()
	{
		return WURST != null && WURST.getHax().mapaHack != null
			&& WURST.getHax().mapaHack.isNewerNewChunksMapModeActive();
	}
	
	private void ensureTrackingWorld()
	{
		if(MC.level == null)
			return;
		if(MC.level != trackedLevel)
		{
			if(removeOnLeaveWorldOrChangeDimensions.isChecked())
				clearChunkData();
			trackedLevel = MC.level;
			loadedThisSession = false;
			resolveWorldKeys();
			ensureDataFiles();
			if(loadChunkData.isChecked())
			{
				if(MC.player != null)
				{
					ChunkPos c = MC.player.chunkPosition();
					requestWindowLoad(c.x(), c.z(), getPrefetchRadius());
					lastWindowCenter = c;
					loadedThisSession = true;
				}
			}
		}
	}
	
	private void scanLoadedChunksAroundPlayer()
	{
		if(MC.level == null || MC.player == null)
			return;
		
		int px = MC.player.chunkPosition().x();
		int pz = MC.player.chunkPosition().z();
		int radius = Math.max(2, MC.options.getEffectiveRenderDistance()) + 1;
		for(int x = px - radius; x <= px + radius; x++)
			for(int z = pz - radius; z <= pz + radius; z++)
			{
				if(!MC.level.hasChunk(x, z))
					continue;
				
				LevelChunk chunk = MC.level.getChunkSource().getChunk(x, z,
					ChunkStatus.FULL, false);
				if(chunk == null || chunk.isEmpty())
					continue;
				
				afterLoadChunk(x, z);
			}
	}
	
	private void markNew(ChunkPos chunkPos)
	{
		if(!newChunks.add(chunkPos))
			return;
		indexAdd(idxNew, chunkPos);
		oldChunks.remove(chunkPos);
		indexRemove(idxOld, chunkPos);
		tickExploitChunks.remove(chunkPos);
		indexRemove(idxTick, chunkPos);
		beingUpdatedOldChunks.remove(chunkPos);
		indexRemove(idxBeingUpdated, chunkPos);
		oldGenerationChunks.remove(chunkPos);
		indexRemove(idxOldGen, chunkPos);
		if(saveChunkData.isChecked())
			saveChunk(Paths.NewChunkData, chunkPos);
		WURST.getHax().webhookAlertHack.onChunkDetected("new chunk", chunkPos);
		triggerAlarm(AlarmType.NEW);
	}
	
	private void markOld(ChunkPos chunkPos)
	{
		if(!oldChunks.add(chunkPos))
			return;
		indexAdd(idxOld, chunkPos);
		newChunks.remove(chunkPos);
		indexRemove(idxNew, chunkPos);
		tickExploitChunks.remove(chunkPos);
		indexRemove(idxTick, chunkPos);
		beingUpdatedOldChunks.remove(chunkPos);
		indexRemove(idxBeingUpdated, chunkPos);
		oldGenerationChunks.remove(chunkPos);
		indexRemove(idxOldGen, chunkPos);
		if(saveChunkData.isChecked())
			saveChunk(Paths.OldChunkData, chunkPos);
		WURST.getHax().webhookAlertHack.onChunkDetected("old chunk", chunkPos);
		triggerAlarm(AlarmType.OLD);
	}
	
	private void markTickExploit(ChunkPos chunkPos)
	{
		if(!tickExploitChunks.add(chunkPos))
			return;
		indexAdd(idxTick, chunkPos);
		if(saveChunkData.isChecked())
			saveChunk(Paths.BlockExploitChunkData, chunkPos);
		triggerAlarm(AlarmType.BLOCK_EXPLOIT);
	}
	
	private void markBeingUpdated(ChunkPos chunkPos)
	{
		if(!beingUpdatedOldChunks.add(chunkPos))
			return;
		indexAdd(idxBeingUpdated, chunkPos);
		newChunks.remove(chunkPos);
		indexRemove(idxNew, chunkPos);
		oldChunks.remove(chunkPos);
		indexRemove(idxOld, chunkPos);
		tickExploitChunks.remove(chunkPos);
		indexRemove(idxTick, chunkPos);
		oldGenerationChunks.remove(chunkPos);
		indexRemove(idxOldGen, chunkPos);
		if(saveChunkData.isChecked())
			saveChunk(Paths.BeingUpdatedChunkData, chunkPos);
		triggerAlarm(AlarmType.BEING_UPDATED);
	}
	
	private void markOldGeneration(ChunkPos chunkPos)
	{
		if(!oldGenerationChunks.add(chunkPos))
			return;
		indexAdd(idxOldGen, chunkPos);
		newChunks.remove(chunkPos);
		indexRemove(idxNew, chunkPos);
		oldChunks.remove(chunkPos);
		indexRemove(idxOld, chunkPos);
		tickExploitChunks.remove(chunkPos);
		indexRemove(idxTick, chunkPos);
		beingUpdatedOldChunks.remove(chunkPos);
		indexRemove(idxBeingUpdated, chunkPos);
		if(saveChunkData.isChecked())
			saveChunk(Paths.OldGenerationChunkData, chunkPos);
		triggerAlarm(AlarmType.OLD_VERSION);
	}
	
	private void triggerAlarm(AlarmType type)
	{
		boolean enabled = switch(type)
		{
			case NEW -> alarms.isChecked();
			case OLD -> oldChunkAlarms.isChecked();
			case BEING_UPDATED -> beingUpdatedChunkAlarms.isChecked();
			case OLD_VERSION -> oldVersionChunkAlarms.isChecked();
			case BLOCK_EXPLOIT -> blockExploitChunkAlarms.isChecked();
		};
		
		if(!enabled)
			return;
		
		switch(type)
		{
			case NEW ->
			{
				newChunkAlarmPending = true;
				newChunkAlarmRingsLeft = amountOfRings.getValueI();
				newChunkAlarmTicks = 0;
			}
			case OLD ->
			{
				oldChunkAlarmPending = true;
				oldChunkAlarmRingsLeft = amountOfRings.getValueI();
				oldChunkAlarmTicks = 0;
			}
			case BEING_UPDATED ->
			{
				beingUpdatedAlarmPending = true;
				beingUpdatedAlarmRingsLeft = amountOfRings.getValueI();
				beingUpdatedAlarmTicks = 0;
			}
			case OLD_VERSION ->
			{
				oldVersionAlarmPending = true;
				oldVersionAlarmRingsLeft = amountOfRings.getValueI();
				oldVersionAlarmTicks = 0;
			}
			case BLOCK_EXPLOIT ->
			{
				blockExploitAlarmPending = true;
				blockExploitAlarmRingsLeft = amountOfRings.getValueI();
				blockExploitAlarmTicks = 0;
			}
		}
	}
	
	private void processAlarmState(AlarmType type)
	{
		switch(type)
		{
			case NEW ->
			{
				if(!alarms.isChecked() || !newChunkAlarmPending
					|| newChunkAlarmRingsLeft <= 0)
					return;
				if(newChunkAlarmTicks <= 0)
				{
					playAlarmSound(type);
					newChunkAlarmRingsLeft--;
					newChunkAlarmTicks = ringDelay.getValueI();
					if(newChunkAlarmRingsLeft <= 0)
						newChunkAlarmPending = false;
				}else
					newChunkAlarmTicks--;
			}
			case OLD ->
			{
				if(!oldChunkAlarms.isChecked() || !oldChunkAlarmPending
					|| oldChunkAlarmRingsLeft <= 0)
					return;
				if(oldChunkAlarmTicks <= 0)
				{
					playAlarmSound(type);
					oldChunkAlarmRingsLeft--;
					oldChunkAlarmTicks = ringDelay.getValueI();
					if(oldChunkAlarmRingsLeft <= 0)
						oldChunkAlarmPending = false;
				}else
					oldChunkAlarmTicks--;
			}
			case BEING_UPDATED ->
			{
				if(!beingUpdatedChunkAlarms.isChecked()
					|| !beingUpdatedAlarmPending
					|| beingUpdatedAlarmRingsLeft <= 0)
					return;
				if(beingUpdatedAlarmTicks <= 0)
				{
					playAlarmSound(type);
					beingUpdatedAlarmRingsLeft--;
					beingUpdatedAlarmTicks = ringDelay.getValueI();
					if(beingUpdatedAlarmRingsLeft <= 0)
						beingUpdatedAlarmPending = false;
				}else
					beingUpdatedAlarmTicks--;
			}
			case OLD_VERSION ->
			{
				if(!oldVersionChunkAlarms.isChecked() || !oldVersionAlarmPending
					|| oldVersionAlarmRingsLeft <= 0)
					return;
				if(oldVersionAlarmTicks <= 0)
				{
					playAlarmSound(type);
					oldVersionAlarmRingsLeft--;
					oldVersionAlarmTicks = ringDelay.getValueI();
					if(oldVersionAlarmRingsLeft <= 0)
						oldVersionAlarmPending = false;
				}else
					oldVersionAlarmTicks--;
			}
			case BLOCK_EXPLOIT ->
			{
				if(!blockExploitChunkAlarms.isChecked()
					|| !blockExploitAlarmPending
					|| blockExploitAlarmRingsLeft <= 0)
					return;
				if(blockExploitAlarmTicks <= 0)
				{
					playAlarmSound(type);
					blockExploitAlarmRingsLeft--;
					blockExploitAlarmTicks = ringDelay.getValueI();
					if(blockExploitAlarmRingsLeft <= 0)
						blockExploitAlarmPending = false;
				}else
					blockExploitAlarmTicks--;
			}
		}
	}
	
	private void playAlarmSound(AlarmType type)
	{
		if(MC.level == null || MC.player == null)
			return;
		
		MC.level.playLocalSound(MC.player.getX(), MC.player.getY(),
			MC.player.getZ(), SoundEvents.NOTE_BLOCK_CHIME.value(),
			SoundSource.PLAYERS, volume.getValueF(), pitch.getValueF(), false);
	}
	
	private void clearChunkData()
	{
		newChunks.clear();
		oldChunks.clear();
		tickExploitChunks.clear();
		beingUpdatedOldChunks.clear();
		oldGenerationChunks.clear();
		idxNew.clear();
		idxOld.clear();
		idxTick.clear();
		idxBeingUpdated.clear();
		idxOldGen.clear();
	}
	
	private List<AABB> collectBoxes(Set<ChunkPos> chunks, double y,
		BlockPos playerPos, double maxDist)
	{
		var boxes = new java.util.ArrayList<AABB>();
		for(ChunkPos chunk : chunks)
		{
			double centerX = chunk.getMiddleBlockX();
			double centerZ = chunk.getMiddleBlockZ();
			if(playerPos.closerThan(
				new BlockPos((int)centerX, playerPos.getY(), (int)centerZ),
				maxDist))
			{
				double minX = chunk.getMinBlockX();
				double minZ = chunk.getMinBlockZ();
				boxes.add(new AABB(minX, y, minZ, minX + 16, y + 1, minZ + 16));
			}
		}
		return boxes;
	}
	
	private void renderBoxes(PoseStack matrices, List<AABB> boxes,
		int sideColor, int lineColor)
	{
		ShapeMode mode = shapeMode.getSelected();
		if(mode == ShapeMode.Sides || mode == ShapeMode.Both)
			RenderUtils.drawSolidBoxes(matrices, boxes, sideColor, true);
		
		if(mode == ShapeMode.Lines || mode == ShapeMode.Both)
			RenderUtils.drawOutlinedBoxes(matrices, boxes, lineColor, true);
	}
	
	private void removeChunksOutsideRenderDistance()
	{
		if(MC.player == null)
			return;
		
		double maxDist = renderDistance.getValue() * 16;
		BlockPos playerPos = MC.player.blockPosition();
		removeOutside(newChunks, playerPos, maxDist);
		removeOutside(oldChunks, playerPos, maxDist);
		removeOutside(tickExploitChunks, playerPos, maxDist);
		removeOutside(beingUpdatedOldChunks, playerPos, maxDist);
		removeOutside(oldGenerationChunks, playerPos, maxDist);
	}
	
	private void removeOutside(Set<ChunkPos> chunks, BlockPos playerPos,
		double maxDist)
	{
		var it = chunks.iterator();
		while(it.hasNext())
		{
			ChunkPos chunk = it.next();
			boolean keep =
				playerPos.closerThan(new BlockPos(chunk.getMiddleBlockX(),
					playerPos.getY(), chunk.getMiddleBlockZ()), maxDist);
			if(!keep)
			{
				it.remove();
				// Remove from all indices (chunk could be in multiple transient
				// sets)
				indexRemove(idxNew, chunk);
				indexRemove(idxOld, chunk);
				indexRemove(idxTick, chunk);
				indexRemove(idxBeingUpdated, chunk);
				indexRemove(idxOldGen, chunk);
			}
		}
	}
	
	private void resolveWorldKeys()
	{
		if(MC.level != null)
			dimensionKey =
				MC.level.dimension().identifier().toString().replace(':', '_');
		
		ServerData server = MC.getCurrentServer();
		if(server != null && server.ip != null && !server.ip.isBlank())
			serverKey = server.ip.replace(':', '_');
		else if(MC.hasSingleplayerServer())
			serverKey = "singleplayer";
		else
			serverKey = "unknown";
	}
	
	private Path getBaseDir()
	{
		return WURST.getWurstFolder().resolve("TrouserStreak")
			.resolve("NewChunks").resolve(serverKey).resolve(dimensionKey);
	}
	
	private void ensureDataFiles()
	{
		if(!saveChunkData.isChecked() && !loadChunkData.isChecked())
			return;
		
		Path baseDir = getBaseDir();
		try
		{
			Files.createDirectories(baseDir);
			for(Path fileName : filePaths)
			{
				Path file = baseDir.resolve(fileName);
				if(Files.notExists(file))
					Files.createFile(file);
			}
		}catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private void loadDataWindowed(int px, int pz, int radiusChunks)
	{
		// Merge-only windowed load from disk, respecting existing
		// classifications
		loadChunkDataWindowed(Paths.BlockExploitChunkData, tickExploitChunks,
			px, pz, radiusChunks);
		loadChunkDataWindowed(Paths.OldChunkData, oldChunks, px, pz,
			radiusChunks);
		loadChunkDataWindowed(Paths.NewChunkData, newChunks, px, pz,
			radiusChunks);
		loadChunkDataWindowed(Paths.BeingUpdatedChunkData,
			beingUpdatedOldChunks, px, pz, radiusChunks);
		loadChunkDataWindowed(Paths.OldGenerationChunkData, oldGenerationChunks,
			px, pz, radiusChunks);
	}
	
	private void requestWindowLoad(int px, int pz, int radiusChunks)
	{
		if(windowLoadInProgress)
			return;
		windowLoadInProgress = true;
		ensureWindowLoader();
		try
		{
			windowLoader.submit(() -> {
				try
				{
					loadDataWindowed(px, pz, radiusChunks);
				}finally
				{
					windowLoadInProgress = false;
				}
			});
		}catch(java.util.concurrent.RejectedExecutionException ex)
		{
			// Executor was shut down between ensure and submit; recreate and
			// retry once.
			try
			{
				windowLoader = createWindowLoader();
				windowLoader.submit(() -> {
					try
					{
						loadDataWindowed(px, pz, radiusChunks);
					}finally
					{
						windowLoadInProgress = false;
					}
				});
			}catch(Exception ignored)
			{
				windowLoadInProgress = false;
			}
		}
	}
	
	private int getWindowRadius()
	{
		int v = persistedWindowRadiusChunks.getValueI();
		if(v <= 0)
			v = WINDOW_RADIUS_CHUNKS; // fallback
		return Math.max(16, Math.min(256, v));
	}
	
	private int getRefreshDistance()
	{
		int v = windowRefreshDistanceChunks.getValueI();
		if(v <= 0)
			v = REFRESH_DISTANCE_CHUNKS; // fallback
		return Math.max(4, Math.min(128, v));
	}
	
	private int getPrefetchRadius()
	{
		int win = getWindowRadius();
		// Prefetch slightly larger than active window for smoothness
		return Math.min(256, win + 32);
	}
	
	private void loadChunkDataWindowed(Path fileName, Set<ChunkPos> target,
		int px, int pz, int radiusChunks)
	{
		Path path = getBaseDir().resolve(fileName);
		if(Files.notExists(path))
			return;
		
		try(BufferedReader br =
			Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			String line;
			while((line = br.readLine()) != null)
			{
				ChunkPos chunkPos = parseChunkPos(line);
				if(chunkPos == null)
					continue;
				if(Math.abs(chunkPos.x() - px) > radiusChunks
					|| Math.abs(chunkPos.z() - pz) > radiusChunks)
					continue;
				if(!containsAny(chunkPos))
				{
					target.add(chunkPos);
					if(target == newChunks)
						indexAdd(idxNew, chunkPos);
					else if(target == oldChunks)
						indexAdd(idxOld, chunkPos);
					else if(target == tickExploitChunks)
						indexAdd(idxTick, chunkPos);
					else if(target == beingUpdatedOldChunks)
						indexAdd(idxBeingUpdated, chunkPos);
					else if(target == oldGenerationChunks)
						indexAdd(idxOldGen, chunkPos);
				}
			}
		}catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	private void refreshWindowedLoadIfNeeded()
	{
		if(!loadChunkData.isChecked() || MC.player == null)
			return;
		ChunkPos c = MC.player.chunkPosition();
		if(lastWindowCenter == null
			|| Math.max(Math.abs(c.x() - lastWindowCenter.x()),
				Math.abs(c.z() - lastWindowCenter.z())) >= getRefreshDistance())
		{
			requestWindowLoad(c.x(), c.z(), getPrefetchRadius());
			lastWindowCenter = c;
		}
	}
	
	private ChunkPos parseChunkPos(String line)
	{
		if(line == null || line.isBlank())
			return null;
			
		// Supports both current format "x,z" and legacy formats like:
		// "[-44933, 1204]" or "ChunkPos{x=-44933, z=1204}".
		Matcher matcher = CHUNK_COORD_PATTERN.matcher(line);
		if(!matcher.find())
			return null;
		
		int x = Integer.parseInt(matcher.group());
		if(!matcher.find())
			return null;
		
		int z = Integer.parseInt(matcher.group());
		return new ChunkPos(x, z);
	}
	
	private void saveChunk(Path fileName, ChunkPos chunkPos)
	{
		ensureDataFiles();
		Path file = getBaseDir().resolve(fileName);
		String line =
			chunkPos.x() + "," + chunkPos.z() + System.lineSeparator();
		try
		{
			Files.writeString(file, line, StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}catch(IOException e)
		{
			e.printStackTrace();
		}
	}
	
	// --- Region index helpers and queries ---
	private static long regionKey(int x, int z)
	{
		int rx = x >> REGION_SHIFT;
		int rz = z >> REGION_SHIFT;
		return (((long)rx) << 32) ^ (rz & 0xFFFFFFFFL);
	}
	
	private static long regionKeyFromRegion(int rx, int rz)
	{
		return (((long)rx) << 32) ^ (rz & 0xFFFFFFFFL);
	}
	
	private void indexAdd(Map<Long, Set<ChunkPos>> index, ChunkPos pos)
	{
		long key = regionKey(pos.x(), pos.z());
		Set<ChunkPos> bucket =
			index.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
		bucket.add(pos);
	}
	
	private void indexRemove(Map<Long, Set<ChunkPos>> index, ChunkPos pos)
	{
		long key = regionKey(pos.x(), pos.z());
		Set<ChunkPos> bucket = index.get(key);
		if(bucket != null)
		{
			bucket.remove(pos);
			if(bucket.isEmpty())
				index.remove(key);
		}
	}
	
	private Set<ChunkPos> getChunksInRange(Map<Long, Set<ChunkPos>> index,
		int cx, int cz, int radius)
	{
		int minRx = (cx - radius) >> REGION_SHIFT;
		int maxRx = (cx + radius) >> REGION_SHIFT;
		int minRz = (cz - radius) >> REGION_SHIFT;
		int maxRz = (cz + radius) >> REGION_SHIFT;
		Set<ChunkPos> result = new HashSet<>();
		for(int rx = minRx; rx <= maxRx; rx++)
			for(int rz = minRz; rz <= maxRz; rz++)
			{
				Set<ChunkPos> bucket = index.get(regionKeyFromRegion(rx, rz));
				if(bucket == null || bucket.isEmpty())
					continue;
				for(ChunkPos cp : bucket)
					if(Math.abs(cp.x() - cx) <= radius
						&& Math.abs(cp.z() - cz) <= radius)
						result.add(cp);
			}
		return result;
	}
	
	private void confirmDeleteChunkData()
	{
		if(deleteWarningPresses == 0)
		{
			ChatUtils.error(
				"Press again within 5 seconds to delete all chunk data for this dimension.");
			deleteWarningTicks = 0;
			deleteWarningPresses = 1;
			return;
		}
		
		deleteWarningPresses++;
		deleteWarningTicks = 200;
		clearChunkData();
		
		Path baseDir = getBaseDir();
		try
		{
			Files.deleteIfExists(baseDir.resolve(Paths.NewChunkData));
			Files.deleteIfExists(baseDir.resolve(Paths.OldChunkData));
			Files.deleteIfExists(baseDir.resolve(Paths.BlockExploitChunkData));
			Files.deleteIfExists(baseDir.resolve(Paths.BeingUpdatedChunkData));
			Files.deleteIfExists(baseDir.resolve(Paths.OldGenerationChunkData));
		}catch(IOException e)
		{
			e.printStackTrace();
		}
		
		ensureDataFiles();
		ChatUtils.message("Chunk data deleted for this dimension.");
	}
	
	private boolean hasFlowingFluid(LevelChunk chunk)
	{
		int minY = chunk.getMinY();
		int maxY = chunk.getMaxY();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for(int cx = 0; cx < 16; cx++)
			for(int y = minY; y <= maxY; y++)
				for(int cz = 0; cz < 16; cz++)
				{
					pos.set(chunk.getPos().getMinBlockX() + cx, y,
						chunk.getPos().getMinBlockZ() + cz);
					FluidState fluid = chunk.getFluidState(pos);
					if(!fluid.isEmpty() && !fluid.isSource())
						return true;
				}
		return false;
	}
	
	private int countDistinctSectionStates(LevelChunkSection section)
	{
		HashSet<BlockState> distinct = new HashSet<>();
		for(int x = 0; x < 16; x++)
			for(int y = 0; y < 16; y++)
				for(int z = 0; z < 16; z++)
					distinct.add(section.getBlockState(x, y, z));
		return distinct.size();
	}
	
	private boolean detectOldGenerationChunk(LevelChunk chunk)
	{
		if(MC.level == null)
			return false;
		
		if(isOverworld() && overworldOldChunksDetector.isChecked())
			return isOverworldOldGeneration(chunk);
		
		if(isNether() && netherOldChunksDetector.isChecked())
			return isNetherOldGeneration(chunk);
		
		if(isEnd() && endOldChunksDetector.isChecked())
			return hasEndBiome(chunk);
		
		return false;
	}
	
	@SuppressWarnings("unchecked")
	private static boolean isHashMapPalette(
		PalettedContainer<BlockState> states)
	{
		if(PAL_CONTAINER_DATA_FIELD == null || PAL_DATA_PALETTE_FIELD == null)
			return false;
		
		try
		{
			Object data = PAL_CONTAINER_DATA_FIELD.get(states);
			Object palette = PAL_DATA_PALETTE_FIELD.get(data);
			return palette instanceof HashMapPalette<?>;
		}catch(ReflectiveOperationException e)
		{
			return false;
		}
	}
	
	private boolean hasEndBiomeFromPalette(LevelChunkSection[] sections)
	{
		if(MC.level == null || sections.length == 0 || sections[0] == null)
			return false;
		
		List<Holder<Biome>> paletteEntries =
			getRawPaletteEntries(sections[0].getBiomes());
		
		return !paletteEntries.isEmpty()
			&& paletteEntries.get(0).is(Biomes.THE_END);
	}
	
	private boolean isEndNewChunkByPalette(LevelChunkSection section)
	{
		if(MC.level == null)
			return false;
		
		List<Holder<Biome>> paletteEntries =
			getRawPaletteEntries(section.getBiomes());
		
		return !paletteEntries.isEmpty()
			&& paletteEntries.get(0).is(Biomes.PLAINS);
	}
	
	private boolean isOverworldOldGeneration(LevelChunk chunk)
	{
		LevelChunkSection[] sections = chunk.getSections();
		int safeSections = Math.min(17, sections.length);
		boolean foundAnyOre = false;
		boolean hasNewOverworldGeneration = false;
		
		for(int i = 0; i < safeSections; i++)
		{
			LevelChunkSection section = sections[i];
			if(section == null || section.hasOnlyAir())
				continue;
			
			for(int x = 0; x < 16; x++)
				for(int y = 0; y < 16; y++)
					for(int z = 0; z < 16; z++)
					{
						Block block = section.getBlockState(x, y, z).getBlock();
						if(!foundAnyOre && ORE_BLOCKS.contains(block))
							foundAnyOre = true;
						
						if(hasNewOverworldGeneration)
							continue;
						
						boolean inModernRange = (i == 4 && y >= 5) || i > 4;
						if(inModernRange
							&& (NEW_OVERWORLD_BLOCKS.contains(block)
								|| DEEPSLATE_BLOCKS.contains(block)))
							hasNewOverworldGeneration = true;
					}
		}
		
		// Mirrors Trouser: avoid false positives in flat/no-ore chunks.
		return foundAnyOre && !hasNewOverworldGeneration;
	}
	
	private boolean isNetherOldGeneration(LevelChunk chunk)
	{
		LevelChunkSection[] sections = chunk.getSections();
		int safeSections = Math.min(8, sections.length);
		
		for(int i = 0; i < safeSections; i++)
		{
			LevelChunkSection section = sections[i];
			if(section == null || section.hasOnlyAir())
				continue;
			
			for(int x = 0; x < 16; x++)
				for(int y = 0; y < 16; y++)
					for(int z = 0; z < 16; z++)
					{
						Block block = section.getBlockState(x, y, z).getBlock();
						if(NEW_NETHER_BLOCKS.contains(block))
							return false;
					}
		}
		
		return true;
	}
	
	private boolean hasEndBiome(LevelChunk chunk)
	{
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		int y = Math.max(MC.level.getMinY() + 1, 64);
		for(int x = 0; x < 16; x += 4)
			for(int z = 0; z < 16; z += 4)
			{
				pos.set(minX + x, y, minZ + z);
				Holder<Biome> biome = MC.level.getBiome(pos);
				if(biome.is(Biomes.THE_END))
					return true;
			}
		return false;
	}
	
	private boolean hasAnyBlockInChunk(LevelChunk chunk, Set<Block> blocks,
		int minY, int maxY)
	{
		if(MC.level == null)
			return false;
		
		int actualMinY = Math.max(chunk.getMinY(), minY);
		int actualMaxY = Math.min(chunk.getMaxY(), maxY);
		if(actualMinY > actualMaxY)
			return false;
		
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		for(int x = 0; x < 16; x++)
			for(int y = actualMinY; y <= actualMaxY; y++)
				for(int z = 0; z < 16; z++)
				{
					pos.set(minX + x, y, minZ + z);
					if(blocks.contains(chunk.getBlockState(pos).getBlock()))
						return true;
				}
		return false;
	}
	
	private boolean hasBlockInYRange(LevelChunk chunk, Block block, int minY,
		int maxY)
	{
		if(MC.level == null)
			return false;
		
		int actualMinY = Math.max(chunk.getMinY(), minY);
		int actualMaxY = Math.min(chunk.getMaxY(), maxY);
		if(actualMinY > actualMaxY)
			return false;
		
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int minX = chunk.getPos().getMinBlockX();
		int minZ = chunk.getPos().getMinBlockZ();
		for(int x = 0; x < 16; x++)
			for(int y = actualMinY; y <= actualMaxY; y++)
				for(int z = 0; z < 16; z++)
				{
					pos.set(minX + x, y, minZ + z);
					if(chunk.getBlockState(pos).getBlock() == block)
						return true;
				}
		return false;
	}
	
	private boolean isOverworld()
	{
		return MC.level != null && "minecraft:overworld"
			.equals(MC.level.dimension().identifier().toString());
	}
	
	private boolean isNether()
	{
		return MC.level != null && "minecraft:the_nether"
			.equals(MC.level.dimension().identifier().toString());
	}
	
	private boolean isEnd()
	{
		return MC.level != null && "minecraft:the_end"
			.equals(MC.level.dimension().identifier().toString());
	}
}
