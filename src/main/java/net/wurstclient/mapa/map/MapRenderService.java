/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mapa.map;

import net.wurstclient.mapa.config.XMapConfig;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.net.SocketAddress;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MapRenderService
{
	private final BlockPos.MutableBlockPos probe =
		new BlockPos.MutableBlockPos();
	private final BlockPos.MutableBlockPos tintProbe =
		new BlockPos.MutableBlockPos();
	private static final Identifier MINIMAP_TEX_ID =
		Identifier.fromNamespaceAndPath("mapa", "dynamic/minimap");
	private static final int COLUMN_CACHE_LIMIT = 120_000;
	private static final int CACHE_MAGIC = 0x4D415041; // MAPA
	private static final int CACHE_VERSION = 25;
	private static final int MAX_BACKING_SAMPLES = 896;
	private static final double ROTATION_SCALE = 1.41421356237;
	private static final int SAMPLE_OVERSCAN = 48;
	private static final int SAMPLE_FLAG_WATER = 1;
	private static final int CHUNK_STATE_ABSENT = 0;
	private static final int CHUNK_STATE_LOADED = 1;
	private static final int CHUNK_STATE_READY = 2;
	private static final float STYLE_BRIGHTNESS = 0.92f;
	private static final float STYLE_CONTRAST = 1.06f;
	private static final float STYLE_SATURATION = 0.88f;
	// Keep terrain depth shading anchored to a fixed world direction
	// (VoxelMap-style stability).
	// (1, -1) selects the darker ridge orientation requested by players.
	private static final double SHADE_WORLD_DX = 1.0;
	private static final double SHADE_WORLD_DZ = -1.0;
	private int[] cachedPixels;
	private int[] cachedBaseColors;
	private int[] cachedHeights;
	private int[] cachedFlags;
	private boolean[] cachedUnknown;
	private int cachedSamples;
	private int cachedBackingSamples;
	private double cachedZoom;
	private int cachedSize;
	private double cachedCenterX;
	private double cachedCenterY;
	private double cachedCenterZ;
	private double pendingCenterX;
	private double pendingCenterZ;
	private float cachedYawDeg;
	private String cachedDimension = "";
	private boolean cachedUndergroundMode;
	private boolean cachedRotateWithPlayer;
	private boolean cachedInvertRotation;
	private double cachedWorldPerSample;
	private long lastBuildTick = Long.MIN_VALUE;
	private long lastUnknownRefreshTick = Long.MIN_VALUE;
	private long lastWorkTick = Long.MIN_VALUE;
	private long lastMotionSampleTick = Long.MIN_VALUE;
	private int unknownRefreshCursor = 0;
	private int unknownVisibleRefreshCursor = 0;
	private double lastMotionCenterX = Double.NaN;
	private double lastMotionCenterZ = Double.NaN;
	private DynamicTexture minimapTexture;
	private int textureSize;
	private final Long2IntLinkedOpenHashMap cachedColumnColor =
		new Long2IntLinkedOpenHashMap();
	private final Long2IntLinkedOpenHashMap cachedColumnY =
		new Long2IntLinkedOpenHashMap();
	private final Long2IntLinkedOpenHashMap cachedColumnFlags =
		new Long2IntLinkedOpenHashMap();
	private final Long2IntOpenHashMap visibleChunkStates =
		new Long2IntOpenHashMap();
	private final LongArrayList dirtyChunks = new LongArrayList();
	private final Int2IntOpenHashMap cachedStateBaseColor =
		new Int2IntOpenHashMap();
	private int[] regionScratchPixels = new int[0];
	private final int[] lightmapColors = new int[256];
	private float blockLightRedFlicker = 0.0f;
	private int lastKnownColor = 0xFF6A6A6A;
	private int lastKnownY = 64;
	private boolean showSmallPlants = false;
	private boolean showTreeCanopies = true;
	private boolean basicPaletteMode = false;
	private boolean surfaceDynamicLighting = true;
	private boolean undergroundDynamicLighting = true;
	private float textureSharpness = 1.0f;
	private float surfaceRelief = 1.0f;
	private float surfaceBrightness = 1.0f;
	private float surfaceContrast = 1.0f;
	private float surfaceSaturation = 1.0f;
	private float surfaceContourLimit = 0.28f;
	private float surfaceContourSoftness = 0.0f;
	private float grassTintStrength = 1.0f;
	private int grassTintColor = -1;
	private float foliageTintStrength = 1.0f;
	private int foliageTintColor = -1;
	private float waterTintStrength = 1.0f;
	private int waterTintColor = -1;
	private float waterDetail = 1.0f;
	private float waterOpacity = 1.0f;
	private float chunkRefreshAggression = 1.5f;
	private String activeCacheKey = "";
	private String activeServerKey = "";
	private Path activeCachePath;
	private long lastDiskSaveTick = Long.MIN_VALUE;
	private long lastChunkPollTick = Long.MIN_VALUE;
	private boolean dirtyDiskCache = false;
	private int cachedLightingSignature = Integer.MIN_VALUE;
	private int cachedColumnSamplingSignature = Integer.MIN_VALUE;
	private int cachedUndergroundBand = Integer.MIN_VALUE;
	private final ExecutorService ioExecutor =
		Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "mapa-cache-io");
			t.setDaemon(true);
			return t;
		});
	private final AtomicBoolean writeInFlight = new AtomicBoolean(false);
	private static final Field SPRITE_ORIGINAL_IMAGE_FIELD;
	private static final Field ABSTRACT_TEXTURE_SAMPLER_FIELD;
	
	static
	{
		Field f = null;
		Field samplerField = null;
		try
		{
			f = SpriteContents.class.getDeclaredField("originalImage");
			f.setAccessible(true);
		}catch(Throwable ignored)
		{}
		try
		{
			samplerField = DynamicTexture.class.getSuperclass()
				.getDeclaredField("sampler");
			samplerField.setAccessible(true);
		}catch(Throwable ignored)
		{}
		SPRITE_ORIGINAL_IMAGE_FIELD = f;
		ABSTRACT_TEXTURE_SAMPLER_FIELD = samplerField;
	}
	
	public MapRenderService()
	{
		cachedStateBaseColor.defaultReturnValue(-1);
		visibleChunkStates.defaultReturnValue(CHUNK_STATE_ABSENT);
	}
	
	public static double zoomToBlocksPerPixel(double zoomLevel)
	{
		double clamped = Mth.clamp(zoomLevel, 0.25, 64.0);
		return 0.65 * Math.pow(1.10, clamped - 1.0);
	}
	
	public WorldMapSnapshot snapshotWorldMap(Minecraft mc)
	{
		if(mc.level == null || mc.player == null)
			return new WorldMapSnapshot(0.0, 0.0, "", new long[0], new int[0]);
		
		ensureCacheContext(mc, mc.level);
		CacheSnapshot snapshot = snapshotCache(activeCachePath);
		return new WorldMapSnapshot(mc.player.getX(), mc.player.getZ(),
			activeCacheKey, snapshot.keys(), snapshot.colors());
	}
	
	public static int unpackColumnX(long key)
	{
		long packed = key >>> 1;
		return (int)(packed >> 32);
	}
	
	public static int unpackColumnZ(long key)
	{
		long packed = key >>> 1;
		return (int)packed;
	}
	
	public static boolean unpackColumnUnderground(long key)
	{
		return (key & 1L) != 0L;
	}
	
	public void drawMinimap(GuiGraphicsExtractor gfx, Minecraft mc,
		XMapConfig cfg)
	{
		if(mc.level == null || mc.player == null)
		{
			return;
		}
		if(!cfg.enabled)
		{
			return;
		}
		ensureCacheContext(mc, mc.level);
		
		int size = cfg.minimapSize;
		int x0 = cfg.minimapPosX;
		int y0 = cfg.minimapPosY;
		int visibleSamples = Mth.clamp(cfg.minimapSamples, 32, 512);
		applyConfig(cfg);
		int columnSamplingSignature = currentColumnSamplingSignature();
		boolean samplingModeChanged =
			cachedColumnSamplingSignature != Integer.MIN_VALUE
				&& columnSamplingSignature != cachedColumnSamplingSignature;
		if(samplingModeChanged)
		{
			clearColumnCache();
		}
		cachedColumnSamplingSignature = columnSamplingSignature;
		double zoomBlocks = zoomToBlocksPerPixel(cfg.minimapZoom);
		int backingSamples = computeBackingSamples(visibleSamples);
		// Use exact player position so the displayed crop can move smoothly
		// between rebuilds.
		double centerX = mc.player.getX();
		double centerY = mc.player.getY();
		double centerZ = mc.player.getZ();
		float yawDeg = cfg.rotateWithPlayer ? mc.player.getYRot() : 0.0f;
		// VoxelMap's rotating square map uses direction = yaw + 180.
		double yaw =
			cfg.rotateWithPlayer ? Math.toRadians(yawDeg + 180.0f) : 0.0;
		int playerY = mc.player.blockPosition().getY();
		boolean useUnderground = cfg.undergroundMode
			&& shouldUseUnderground(mc.level, mc.player.blockPosition());
		if(useUnderground && visibleSamples > 256)
		{
			// Underground mode is heavier due to per-column vertical probing.
			visibleSamples = 256;
			backingSamples = computeBackingSamples(visibleSamples);
		}
		int undergroundBand =
			useUnderground ? undergroundCacheBand(playerY) : Integer.MIN_VALUE;
		
		gfx.fill(x0 - 2, y0 - 2, x0 + size + 2, y0 + size + 2, 0xCC000000);
		gfx.fill(x0 - 1, y0 - 1, x0 + size + 1, y0 + size + 1, 0xFF6F6F6F);
		
		int effectiveSamples = visibleSamples;
		
		long tick = mc.level.getGameTime();
		if(tick != lastWorkTick)
		{
			double moveBlocks = tick == lastMotionSampleTick + 1
				&& !Double.isNaN(lastMotionCenterX)
				&& !Double.isNaN(lastMotionCenterZ)
					? Math.hypot(centerX - lastMotionCenterX,
						centerZ - lastMotionCenterZ)
					: 0.0;
			float movementPenalty =
				Mth.clamp((float)(moveBlocks / 6.0), 0.0f, 1.0f);
			float surfacePriorityRefreshScale = 1.0f - movementPenalty * 0.30f;
			float surfaceBackgroundRefreshScale =
				1.0f - movementPenalty * 0.85f;
			lastWorkTick = tick;
			int lightingSignature = currentLightingSignature(mc, mc.level,
				mc.player.blockPosition(), useUnderground);
			boolean lightingChanged =
				cachedLightingSignature != Integer.MIN_VALUE
					&& lightingSignature != cachedLightingSignature;
			boolean undergroundBandChanged =
				useUnderground && cachedUndergroundBand != Integer.MIN_VALUE
					&& undergroundBand != cachedUndergroundBand;
			if(lightingChanged)
			{
				clearColumnCache();
			}
			if(undergroundBandChanged)
			{
				clearColumnCache();
			}
			pollVisibleChunks(mc.level, centerX, centerZ, backingSamples,
				zoomBlocks * size / effectiveSamples, tick);
			int partialChunkLimit = Mth.clamp(
				(int)Math
					.ceil((4.0f - movementPenalty) * chunkRefreshAggression),
				6, 16);
			int unknownChunkQueueLimit = Math.max(partialChunkLimit * 2,
				partialChunkLimit * (movementPenalty >= 0.35f ? 4 : 8));
			if(hasUnknownColumns())
			{
				queueUnknownChunksForRetry(mc.level, unknownChunkQueueLimit);
			}
			if(needsRebuild(mc, cfg, effectiveSamples, backingSamples, centerX,
				centerY, centerZ, useUnderground, lightingSignature))
			{
				rebuildPixels(mc, mc.level, centerX, centerZ, backingSamples,
					effectiveSamples, zoomBlocks, size, 0.0, playerY,
					useUnderground, cfg.invertRotation, !samplingModeChanged
						&& !lightingChanged && !undergroundBandChanged);
				cachedSamples = effectiveSamples;
				cachedBackingSamples = backingSamples;
				cachedZoom = zoomBlocks;
				cachedSize = size;
				cachedCenterX = pendingCenterX;
				cachedCenterY = centerY;
				cachedCenterZ = pendingCenterZ;
				cachedYawDeg = yawDeg;
				cachedDimension = mc.level.dimension().identifier().toString();
				cachedUndergroundMode = useUnderground;
				cachedRotateWithPlayer = cfg.rotateWithPlayer;
				cachedInvertRotation = cfg.invertRotation;
				cachedWorldPerSample = zoomBlocks * size / effectiveSamples;
				cachedLightingSignature = lightingSignature;
				cachedUndergroundBand = undergroundBand;
				lastBuildTick = tick;
				lastUnknownRefreshTick = tick;
				unknownRefreshCursor = 0;
				unknownVisibleRefreshCursor = 0;
				dirtyChunks.clear();
				if(hasUnknownColumns())
				{
					queueUnknownChunksForRetry(mc.level,
						unknownChunkQueueLimit);
				}
			}else if(!dirtyChunks.isEmpty())
			{
				refreshDirtyChunks(mc, mc.level, playerY, useUnderground,
					partialChunkLimit);
			}
			if(hasUnknownColumns() && tick != lastUnknownRefreshTick)
			{
				refreshUnknownColumns(mc, mc.level, centerX, centerZ, playerY,
					useUnderground, tick,
					useUnderground ? 1.0f : surfacePriorityRefreshScale,
					useUnderground ? 1.0f : surfaceBackgroundRefreshScale);
			}
			maybeScheduleCacheSave(tick);
			lastMotionCenterX = centerX;
			lastMotionCenterZ = centerZ;
			lastMotionSampleTick = tick;
		}
		
		drawTexture(gfx, x0, y0, size, centerX, centerZ, cfg.rotateWithPlayer,
			yawDeg, cfg.invertRotation);
		
		if(cfg.showCenterCross)
		{
			int cx = x0 + size / 2;
			int cy = y0 + size / 2;
			gfx.fill(cx - 1, cy - 4, cx + 1, cy + 4, 0xFFFFFFFF);
			gfx.fill(cx - 4, cy - 1, cx + 4, cy + 1, 0xFFFFFFFF);
		}
	}
	
	public void drawWorldMap(GuiGraphicsExtractor gfx, Minecraft mc,
		XMapConfig cfg, int drawX, int drawY, int drawWidth, int drawHeight,
		double centerX, double centerZ, double blocksPerPixel)
	{
		if(mc.level == null || mc.player == null || !cfg.enabled)
			return;
		
		ensureCacheContext(mc, mc.level);
		applyConfig(cfg);
		
		int drawSize = Math.max(drawWidth, drawHeight);
		int visibleSamples = Mth.clamp(drawSize, 256, 768);
		int backingSamples = computeBackingSamples(visibleSamples);
		int columnSamplingSignature = currentColumnSamplingSignature();
		boolean samplingModeChanged =
			cachedColumnSamplingSignature != Integer.MIN_VALUE
				&& columnSamplingSignature != cachedColumnSamplingSignature;
		if(samplingModeChanged)
			clearColumnCache();
		cachedColumnSamplingSignature = columnSamplingSignature;
		
		double centerY = mc.player.getY();
		int playerY = mc.player.blockPosition().getY();
		boolean useUnderground = cfg.undergroundMode
			&& shouldUseUnderground(mc.level, mc.player.blockPosition());
		if(useUnderground && visibleSamples > 256)
		{
			visibleSamples = 256;
			backingSamples = computeBackingSamples(visibleSamples);
		}
		int undergroundBand =
			useUnderground ? undergroundCacheBand(playerY) : Integer.MIN_VALUE;
		long tick = mc.level.getGameTime();
		int lightingSignature = currentLightingSignature(mc, mc.level,
			mc.player.blockPosition(), useUnderground);
		boolean lightingChanged = cachedLightingSignature != Integer.MIN_VALUE
			&& lightingSignature != cachedLightingSignature;
		boolean undergroundBandChanged =
			useUnderground && cachedUndergroundBand != Integer.MIN_VALUE
				&& undergroundBand != cachedUndergroundBand;
		if(lightingChanged || undergroundBandChanged)
			clearColumnCache();
		
		pollVisibleChunks(mc.level, centerX, centerZ, backingSamples,
			blocksPerPixel * drawSize / visibleSamples, tick);
		if(hasUnknownColumns())
			queueUnknownChunksForRetry(mc.level, 32);
		
		if(needsWorldMapRebuild(mc, visibleSamples, backingSamples, centerX,
			centerY, centerZ, blocksPerPixel, drawSize, useUnderground,
			lightingSignature))
		{
			rebuildPixels(mc, mc.level, centerX, centerZ, backingSamples,
				visibleSamples, blocksPerPixel, drawSize, 0.0, playerY,
				useUnderground, false, false);
			cachedSamples = visibleSamples;
			cachedBackingSamples = backingSamples;
			cachedZoom = blocksPerPixel;
			cachedSize = drawSize;
			cachedCenterX = pendingCenterX;
			cachedCenterY = centerY;
			cachedCenterZ = pendingCenterZ;
			cachedYawDeg = 0.0f;
			cachedDimension = mc.level.dimension().identifier().toString();
			cachedUndergroundMode = useUnderground;
			cachedRotateWithPlayer = false;
			cachedInvertRotation = false;
			cachedWorldPerSample = blocksPerPixel * drawSize / visibleSamples;
			cachedLightingSignature = lightingSignature;
			cachedUndergroundBand = undergroundBand;
			lastBuildTick = tick;
			lastUnknownRefreshTick = tick;
			unknownRefreshCursor = 0;
			unknownVisibleRefreshCursor = 0;
			dirtyChunks.clear();
		}
		
		drawTextureRect(gfx, drawX, drawY, drawWidth, drawHeight, centerX,
			centerZ);
	}
	
	private void applyConfig(XMapConfig cfg)
	{
		showSmallPlants = cfg.showSmallPlants;
		showTreeCanopies = cfg.showTreeCanopies;
		basicPaletteMode = cfg.basicPaletteMode;
		surfaceDynamicLighting = cfg.surfaceDynamicLighting;
		undergroundDynamicLighting = cfg.undergroundDynamicLighting;
		textureSharpness = (float)cfg.textureSharpness;
		surfaceRelief = (float)cfg.surfaceRelief;
		surfaceBrightness = (float)cfg.surfaceBrightness;
		surfaceContrast = (float)cfg.surfaceContrast;
		surfaceSaturation = (float)cfg.surfaceSaturation;
		surfaceContourLimit = (float)cfg.surfaceContourLimit;
		surfaceContourSoftness = (float)cfg.surfaceContourSoftness;
		grassTintStrength = (float)cfg.grassTintStrength;
		grassTintColor = cfg.grassTintColor;
		foliageTintStrength = (float)cfg.foliageTintStrength;
		foliageTintColor = cfg.foliageTintColor;
		waterTintStrength = (float)cfg.waterTintStrength;
		waterTintColor = cfg.waterTintColor;
		waterDetail = (float)cfg.waterDetail;
		waterOpacity = (float)cfg.waterOpacity;
		chunkRefreshAggression = (float)cfg.chunkRefreshAggression;
	}
	
	private boolean needsWorldMapRebuild(Minecraft mc, int samples,
		int backingSamples, double centerX, double centerY, double centerZ,
		double blocksPerPixel, int size, boolean undergroundMode,
		int lightingSignature)
	{
		if(cachedPixels == null || cachedSamples != samples
			|| cachedBackingSamples != backingSamples
			|| Math.abs(cachedZoom - blocksPerPixel) > 0.0001
			|| cachedSize != size)
			return true;
		
		String dim = mc.level.dimension().identifier().toString();
		if(!dim.equals(cachedDimension))
			return true;
		if(cachedUndergroundMode != undergroundMode)
			return true;
		
		double dx = centerX - cachedCenterX;
		double dz = centerZ - cachedCenterZ;
		double sampleDeltaX = dx / Math.max(0.0001, cachedWorldPerSample);
		double sampleDeltaZ = dz / Math.max(0.0001, cachedWorldPerSample);
		int maxSampleDrift =
			Math.max(1, (cachedBackingSamples - cachedSamples) / 2 - 2);
		boolean movedBeyondBacking = Math.abs(sampleDeltaX) > maxSampleDrift
			|| Math.abs(sampleDeltaZ) > maxSampleDrift;
		if(movedBeyondBacking)
			return true;
		
		boolean movedY =
			undergroundMode && Math.abs(centerY - cachedCenterY) >= 4.0;
		if(movedY)
			return true;
		return lightingSignature != cachedLightingSignature;
	}
	
	private boolean needsRebuild(Minecraft mc, XMapConfig cfg, int samples,
		int backingSamples, double centerX, double centerY, double centerZ,
		boolean undergroundMode, int lightingSignature)
	{
		if(cachedPixels == null || cachedSamples != samples
			|| cachedBackingSamples != backingSamples
			|| Math.abs(
				cachedZoom - zoomToBlocksPerPixel(cfg.minimapZoom)) > 0.0001
			|| cachedSize != cfg.minimapSize)
		{
			return true;
		}
		String dim = mc.level.dimension().identifier().toString();
		if(!dim.equals(cachedDimension))
		{
			return true;
		}
		if(cachedUndergroundMode != undergroundMode)
		{
			return true;
		}
		double dx = centerX - cachedCenterX;
		double dz = centerZ - cachedCenterZ;
		double sampleDeltaX = dx / Math.max(0.0001, cachedWorldPerSample);
		double sampleDeltaZ = dz / Math.max(0.0001, cachedWorldPerSample);
		int prioritySamples =
			cachedRotateWithPlayer ? Math.min(cachedBackingSamples,
				rotatedSourceSamples(cachedSamples)) : cachedSamples;
		int maxSampleDrift =
			Math.max(1, (cachedBackingSamples - prioritySamples) / 2 - 2);
		boolean movedBeyondBacking = Math.abs(sampleDeltaX) > maxSampleDrift
			|| Math.abs(sampleDeltaZ) > maxSampleDrift;
		if(movedBeyondBacking)
		{
			return true;
		}
		boolean movedY =
			undergroundMode && Math.abs(centerY - cachedCenterY) >= 4.0;
		if(movedY)
		{
			return true;
		}
		return lightingSignature != cachedLightingSignature;
	}
	
	private void rebuildPixels(Minecraft mc, ClientLevel level, double centerX,
		double centerZ, int backingSamples, int samples, double zoom,
		int viewSize, double rotationYawRad, int playerY,
		boolean undergroundMode, boolean invertRotation, boolean allowReuse)
	{
		double rotationAngle = 0.0;
		double cos = 1.0;
		double sin = 0.0;
		double worldPerSample = zoom * viewSize / samples;
		if(!basicPaletteMode)
		{
			rebuildLightmap(mc, level);
		}
		int total = backingSamples * backingSamples;
		int[] colors;
		int[] heights;
		int[] flags;
		boolean[] unknown;
		double builtCenterX = centerX;
		double builtCenterZ = centerZ;
		boolean reused = false;
		int sampleStepX = 0;
		int sampleStepZ = 0;
		
		if(allowReuse && canShiftCachedWindow(backingSamples, samples,
			undergroundMode, rotationAngle, worldPerSample))
		{
			double dx = centerX - cachedCenterX;
			double dz = centerZ - cachedCenterZ;
			sampleStepX =
				wholeSampleShift(dx / Math.max(0.0001, cachedWorldPerSample));
			sampleStepZ =
				wholeSampleShift(dz / Math.max(0.0001, cachedWorldPerSample));
			reused = Math.abs(sampleStepX) < backingSamples
				&& Math.abs(sampleStepZ) < backingSamples;
		}
		
		if(reused)
		{
			if(sampleStepX == 0 && sampleStepZ == 0)
			{
				colors = cachedBaseColors;
				heights = cachedHeights;
				flags = cachedFlags;
				unknown = cachedUnknown;
				builtCenterX = cachedCenterX;
				builtCenterZ = cachedCenterZ;
			}else
			{
				colors = new int[total];
				heights = new int[total];
				flags = new int[total];
				unknown = new boolean[total];
				shiftCachedInts(cachedBaseColors, colors, backingSamples,
					sampleStepX, sampleStepZ);
				shiftCachedInts(cachedHeights, heights, backingSamples,
					sampleStepX, sampleStepZ);
				shiftCachedInts(cachedFlags, flags, backingSamples, sampleStepX,
					sampleStepZ);
				shiftCachedBooleans(cachedUnknown, unknown, backingSamples,
					sampleStepX, sampleStepZ);
				builtCenterX =
					cachedCenterX + sampleStepX * cachedWorldPerSample;
				builtCenterZ =
					cachedCenterZ + sampleStepZ * cachedWorldPerSample;
				int estimatedNewSamples =
					Math.max(1, (Math.abs(sampleStepX) + Math.abs(sampleStepZ))
						* backingSamples);
				Long2LongOpenHashMap frameSampleCache =
					new Long2LongOpenHashMap(
						Math.max(estimatedNewSamples, backingSamples));
				frameSampleCache.defaultReturnValue(Long.MIN_VALUE);
				refillExposedStrips(level, builtCenterX, builtCenterZ,
					backingSamples, samples, worldPerSample, rotationYawRad,
					cos, sin, playerY, undergroundMode, colors, heights, flags,
					unknown, sampleStepX, sampleStepZ, frameSampleCache);
			}
		}else
		{
			colors = new int[total];
			heights = new int[total];
			flags = new int[total];
			unknown = new boolean[total];
			Long2LongOpenHashMap frameSampleCache =
				new Long2LongOpenHashMap(total / 2);
			frameSampleCache.defaultReturnValue(Long.MIN_VALUE);
			sampleWindow(level, centerX, centerZ, backingSamples, samples,
				worldPerSample, rotationYawRad, cos, sin, playerY,
				undergroundMode, colors, heights, flags, unknown, 0,
				backingSamples, 0, backingSamples, frameSampleCache);
		}
		
		publishCachedWindow(mc, colors, heights, flags, unknown, backingSamples,
			playerY, undergroundMode);
		pendingCenterX = builtCenterX;
		pendingCenterZ = builtCenterZ;
	}
	
	private void publishCachedWindow(Minecraft mc, int[] colors, int[] heights,
		int[] flags, boolean[] unknown, int backingSamples, int playerY,
		boolean undergroundMode)
	{
		int total = backingSamples * backingSamples;
		int[] shaded = new int[total];
		int[] displayColors = colors.clone();
		int[] displayHeights = heights.clone();
		int[] displayFlags = flags.clone();
		boolean[] displayUnknown = unknown.clone();
		fillUnknownFromNeighbors(displayColors, displayHeights, displayFlags,
			displayUnknown, backingSamples, undergroundMode ? 0 : 3,
			undergroundMode);
		
		for(int sx = 0; sx < backingSamples; sx++)
		{
			for(int sz = 0; sz < backingSamples; sz++)
			{
				int idx = sx * backingSamples + sz;
				if(displayUnknown[idx])
				{
					shaded[idx] = 0xFF000000;
					continue;
				}
				int shadedColor;
				if(basicPaletteMode)
				{
					shadedColor = displayColors[idx];
				}else if(undergroundMode)
				{
					int h = displayHeights[idx];
					int neighborX = Math.max(0, sx - 1);
					int neighborZ = Math.min(backingSamples - 1, sz + 1);
					int hComp =
						displayHeights[neighborX * backingSamples + neighborZ];
					shadedColor =
						shade(displayColors[idx], hComp, h, playerY, true);
				}else
				{
					shadedColor = shadeSurface(displayColors[idx],
						displayHeights, displayFlags, backingSamples, sx, sz);
				}
				shaded[idx] = styleColor(shadedColor);
			}
		}
		if(basicPaletteMode)
		{
			// Pure palette mode intentionally skips contour, sharpen, and water
			// smoothing work.
		}else if(undergroundMode)
		{
			softenJaggedEdges(shaded, backingSamples, true);
		}else
		{
			applyContourSoftness(shaded, displayFlags, displayUnknown,
				backingSamples);
			applySurfaceSharpen(shaded, displayFlags, backingSamples);
			smoothWaterSurface(shaded, displayFlags, displayUnknown,
				backingSamples);
			smoothWaterSurface(shaded, displayFlags, displayUnknown,
				backingSamples);
		}
		
		cachedBaseColors = colors;
		cachedHeights = heights;
		cachedFlags = flags;
		cachedUnknown = unknown;
		cachedPixels = shaded;
		uploadTexture(mc, shaded, backingSamples, backingSamples,
			undergroundMode);
	}
	
	private void refreshUnknownColumns(Minecraft mc, ClientLevel level,
		double liveCenterX, double liveCenterZ, int playerY,
		boolean undergroundMode, long tick, float priorityScale,
		float backgroundScale)
	{
		if(cachedBaseColors == null || cachedHeights == null
			|| cachedUnknown == null || cachedBackingSamples <= 0)
		{
			lastUnknownRefreshTick = tick;
			return;
		}
		
		int total = cachedBackingSamples * cachedBackingSamples;
		if(total <= 0)
		{
			lastUnknownRefreshTick = tick;
			return;
		}
		
		int prioritySamples =
			cachedRotateWithPlayer ? Math.min(cachedBackingSamples,
				rotatedSourceSamples(cachedSamples)) : cachedSamples;
		double sampleDeltaX = (liveCenterX - cachedCenterX)
			/ Math.max(0.0001, cachedWorldPerSample);
		double sampleDeltaZ = (liveCenterZ - cachedCenterZ)
			/ Math.max(0.0001, cachedWorldPerSample);
		int cropShiftX = wholeSampleShift(sampleDeltaX);
		int cropShiftZ = wholeSampleShift(sampleDeltaZ);
		double cropStartX = Math.max(0.0,
			(cachedBackingSamples - prioritySamples) * 0.5 + cropShiftX);
		double cropStartZ = Math.max(0.0,
			(cachedBackingSamples - prioritySamples) * 0.5 + cropShiftZ);
		int priorityStartX = Math.max(0, Mth.floor(cropStartX));
		int priorityStartZ = Math.max(0, Mth.floor(cropStartZ));
		int priorityEndX = Math.min(cachedBackingSamples,
			Mth.ceil(cropStartX + prioritySamples));
		int priorityEndZ = Math.min(cachedBackingSamples,
			Mth.ceil(cropStartZ + prioritySamples));
		int priorityArea = Math.max(1,
			(priorityEndX - priorityStartX) * (priorityEndZ - priorityStartZ));
		int priorityBudget =
			undergroundMode
				? Mth
					.clamp(
						(int)((prioritySamples * 18.0f) * chunkRefreshAggression
							* priorityScale),
						1024, Math.max(priorityArea, 4096))
				: Mth.clamp((int)((prioritySamples * 6.0f)
					* chunkRefreshAggression * priorityScale), 128, 1536);
		int backgroundBudget =
			undergroundMode
				? Mth
					.clamp(
						(int)(cachedBackingSamples * 6.0f
							* chunkRefreshAggression * backgroundScale),
						256, 1536)
				: Mth
					.clamp(
						(int)(cachedBackingSamples * 1.5f
							* chunkRefreshAggression * backgroundScale),
						0, 256);
		Long2LongOpenHashMap frameSampleCache = new Long2LongOpenHashMap(
			Math.max(32, priorityBudget + backgroundBudget));
		frameSampleCache.defaultReturnValue(Long.MIN_VALUE);
		
		int[] dirtyBounds = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE,
			Integer.MIN_VALUE, Integer.MIN_VALUE};
		boolean changed = false;
		changed |= refreshUnknownRange(level, playerY, undergroundMode,
			frameSampleCache, priorityStartX, priorityEndX, priorityStartZ,
			priorityEndZ, priorityBudget, true, dirtyBounds);
		changed |= refreshUnknownRange(level, playerY, undergroundMode,
			frameSampleCache, 0, cachedBackingSamples, 0, cachedBackingSamples,
			backgroundBudget, false, dirtyBounds);
		lastUnknownRefreshTick = tick;
		
		if(changed)
		{
			publishDirtyWindow(mc, playerY, undergroundMode, dirtyBounds);
		}
	}
	
	private boolean refreshUnknownRange(ClientLevel level, int playerY,
		boolean undergroundMode, Long2LongOpenHashMap frameSampleCache,
		int startX, int endX, int startZ, int endZ, int budget,
		boolean priorityRange, int[] dirtyBounds)
	{
		int width = Math.max(0, endX - startX);
		int height = Math.max(0, endZ - startZ);
		int area = width * height;
		if(area <= 0 || budget <= 0)
		{
			return false;
		}
		
		int startCursor =
			priorityRange ? Math.floorMod(unknownVisibleRefreshCursor, area)
				: Math.floorMod(unknownRefreshCursor, area);
		int attempts = 0;
		int sampled = 0;
		boolean changed = false;
		
		while(attempts < area && sampled < budget)
		{
			int local = (startCursor + attempts) % area;
			attempts++;
			int sx = startX + (local / height);
			int sz = startZ + (local % height);
			int idx = sx * cachedBackingSamples + sz;
			if(!cachedUnknown[idx])
			{
				continue;
			}
			
			sampled++;
			double worldDx =
				(sx + 0.5 - cachedBackingSamples / 2.0) * cachedWorldPerSample;
			double worldDz =
				(sz + 0.5 - cachedBackingSamples / 2.0) * cachedWorldPerSample;
			int wx = Mth.floor(cachedCenterX + worldDx);
			int wz = Mth.floor(cachedCenterZ + worldDz);
			long cacheKey = columnKey(wx, wz, undergroundMode);
			long encoded = frameSampleCache.get(cacheKey);
			if(encoded == Long.MIN_VALUE)
			{
				encoded = packSample(
					sampleSurface(level, wx, wz, 0, undergroundMode, playerY));
				frameSampleCache.put(cacheKey, encoded);
			}
			
			if(unpackKnown(encoded))
			{
				cachedBaseColors[idx] = unpackColor(encoded);
				cachedHeights[idx] = unpackY(encoded);
				cachedFlags[idx] = unpackFlags(encoded);
				cachedUnknown[idx] = false;
				dirtyBounds[0] = Math.min(dirtyBounds[0], sx);
				dirtyBounds[1] = Math.min(dirtyBounds[1], sz);
				dirtyBounds[2] = Math.max(dirtyBounds[2], sx);
				dirtyBounds[3] = Math.max(dirtyBounds[3], sz);
				changed = true;
			}
		}
		
		int nextCursor = (startCursor + Math.max(1, attempts)) % area;
		if(priorityRange)
		{
			unknownVisibleRefreshCursor = nextCursor;
		}else
		{
			unknownRefreshCursor = nextCursor;
		}
		return changed;
	}
	
	private void publishDirtyWindow(Minecraft mc, int playerY,
		boolean undergroundMode, int[] dirtyBounds)
	{
		if(cachedBaseColors == null || cachedHeights == null
			|| cachedFlags == null || cachedUnknown == null
			|| cachedPixels == null)
		{
			return;
		}
		if(dirtyBounds[0] == Integer.MAX_VALUE)
		{
			return;
		}
		if(undergroundMode)
		{
			publishCachedWindow(mc, cachedBaseColors, cachedHeights,
				cachedFlags, cachedUnknown, cachedBackingSamples, playerY,
				true);
			return;
		}
		
		int minX = Math.max(0, dirtyBounds[0] - 2);
		int minZ = Math.max(0, dirtyBounds[1] - 2);
		int maxX = Math.min(cachedBackingSamples - 1, dirtyBounds[2] + 2);
		int maxZ = Math.min(cachedBackingSamples - 1, dirtyBounds[3] + 2);
		
		for(int sx = minX; sx <= maxX; sx++)
		{
			int row = sx * cachedBackingSamples;
			for(int sz = minZ; sz <= maxZ; sz++)
			{
				int idx = row + sz;
				if(cachedUnknown[idx])
				{
					cachedPixels[idx] = 0xFF000000;
					continue;
				}
				int shadedColor = basicPaletteMode ? cachedBaseColors[idx]
					: shadeSurface(cachedBaseColors[idx], cachedHeights,
						cachedFlags, cachedBackingSamples, sx, sz);
				cachedPixels[idx] = styleColor(shadedColor);
			}
		}
		
		if(!basicPaletteMode)
		{
			applyContourSoftnessRegion(cachedPixels, cachedFlags, cachedUnknown,
				cachedBackingSamples, minX, minZ, maxX, maxZ);
			applySurfaceSharpenRegion(cachedPixels, cachedFlags,
				cachedBackingSamples, minX, minZ, maxX, maxZ);
			int waterMinX = Math.max(0, dirtyBounds[0] - 8);
			int waterMinZ = Math.max(0, dirtyBounds[1] - 8);
			int waterMaxX =
				Math.min(cachedBackingSamples - 1, dirtyBounds[2] + 8);
			int waterMaxZ =
				Math.min(cachedBackingSamples - 1, dirtyBounds[3] + 8);
			smoothWaterSurfaceRegion(cachedPixels, cachedFlags, cachedUnknown,
				cachedBackingSamples, waterMinX, waterMinZ, waterMaxX,
				waterMaxZ);
			smoothWaterSurfaceRegion(cachedPixels, cachedFlags, cachedUnknown,
				cachedBackingSamples, waterMinX, waterMinZ, waterMaxX,
				waterMaxZ);
		}
		uploadTexture(mc, cachedPixels, cachedBackingSamples,
			cachedBackingSamples, false);
	}
	
	private void refreshDirtyChunks(Minecraft mc, ClientLevel level,
		int playerY, boolean undergroundMode, int chunkLimit)
	{
		if(cachedBaseColors == null || cachedHeights == null
			|| cachedFlags == null || cachedUnknown == null
			|| cachedBackingSamples <= 0)
		{
			return;
		}
		
		Long2LongOpenHashMap frameSampleCache = new Long2LongOpenHashMap(
			Math.max(64, Math.min(dirtyChunks.size(), chunkLimit) * 128));
		frameSampleCache.defaultReturnValue(Long.MIN_VALUE);
		int[] dirtyBounds = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE,
			Integer.MIN_VALUE, Integer.MIN_VALUE};
		boolean changed = false;
		int processed = 0;
		LongOpenHashSet seen =
			new LongOpenHashSet(Math.max(16, dirtyChunks.size()));
		LongArrayList remaining =
			new LongArrayList(Math.max(0, dirtyChunks.size() - chunkLimit));
		
		for(int i = 0; i < dirtyChunks.size(); i++)
		{
			long packedChunk = dirtyChunks.getLong(i);
			if(!seen.add(packedChunk))
			{
				continue;
			}
			int chunkX = unpackChunkX(packedChunk);
			int chunkZ = unpackChunkZ(packedChunk);
			if(processed >= chunkLimit)
			{
				remaining.add(packedChunk);
				continue;
			}
			if(!isChunkReady(level, chunkX, chunkZ))
			{
				continue;
			}
			
			int startX = sampleStartForBlock(chunkX << 4, cachedCenterX,
				cachedWorldPerSample, cachedBackingSamples);
			int endX = sampleEndForBlock((chunkX << 4) + 16, cachedCenterX,
				cachedWorldPerSample, cachedBackingSamples);
			int startZ = sampleStartForBlock(chunkZ << 4, cachedCenterZ,
				cachedWorldPerSample, cachedBackingSamples);
			int endZ = sampleEndForBlock((chunkZ << 4) + 16, cachedCenterZ,
				cachedWorldPerSample, cachedBackingSamples);
			if(startX >= endX || startZ >= endZ)
			{
				continue;
			}
			
			sampleWindow(level, cachedCenterX, cachedCenterZ,
				cachedBackingSamples, cachedSamples, cachedWorldPerSample, 0.0,
				1.0, 0.0, playerY, undergroundMode, cachedBaseColors,
				cachedHeights, cachedFlags, cachedUnknown, startX, endX, startZ,
				endZ, frameSampleCache);
			dirtyBounds[0] = Math.min(dirtyBounds[0], startX);
			dirtyBounds[1] = Math.min(dirtyBounds[1], startZ);
			dirtyBounds[2] = Math.max(dirtyBounds[2], endX - 1);
			dirtyBounds[3] = Math.max(dirtyBounds[3], endZ - 1);
			changed = true;
			processed++;
		}
		
		dirtyChunks.clear();
		for(int i = 0; i < remaining.size(); i++)
		{
			dirtyChunks.add(remaining.getLong(i));
		}
		
		if(changed)
		{
			publishDirtyWindow(mc, playerY, undergroundMode, dirtyBounds);
		}
	}
	
	private boolean canShiftCachedWindow(int backingSamples, int samples,
		boolean undergroundMode, double rotationAngle, double worldPerSample)
	{
		return cachedBaseColors != null && cachedHeights != null
			&& cachedFlags != null && cachedUnknown != null
			&& cachedBackingSamples == backingSamples
			&& cachedSamples == samples
			&& cachedUndergroundMode == undergroundMode
			&& Math.abs(cachedWorldPerSample - worldPerSample) < 0.0001;
	}
	
	private boolean hasUnknownColumns()
	{
		if(cachedUnknown == null)
		{
			return false;
		}
		for(boolean unknown : cachedUnknown)
		{
			if(unknown)
			{
				return true;
			}
		}
		return false;
	}
	
	private static void shiftCachedInts(int[] src, int[] dst, int samples,
		int shiftX, int shiftZ)
	{
		for(int x = 0; x < samples; x++)
		{
			int srcX = x + shiftX;
			if(srcX < 0 || srcX >= samples)
			{
				continue;
			}
			int dstRow = x * samples;
			int srcRow = srcX * samples;
			for(int z = 0; z < samples; z++)
			{
				int srcZ = z + shiftZ;
				if(srcZ < 0 || srcZ >= samples)
				{
					continue;
				}
				dst[dstRow + z] = src[srcRow + srcZ];
			}
		}
	}
	
	private static void shiftCachedBooleans(boolean[] src, boolean[] dst,
		int samples, int shiftX, int shiftZ)
	{
		for(int x = 0; x < samples; x++)
		{
			int srcX = x + shiftX;
			if(srcX < 0 || srcX >= samples)
			{
				continue;
			}
			int dstRow = x * samples;
			int srcRow = srcX * samples;
			for(int z = 0; z < samples; z++)
			{
				int srcZ = z + shiftZ;
				if(srcZ < 0 || srcZ >= samples)
				{
					continue;
				}
				dst[dstRow + z] = src[srcRow + srcZ];
			}
		}
	}
	
	private void refillExposedStrips(ClientLevel level, double centerX,
		double centerZ, int backingSamples, int samples, double worldPerSample,
		double rotationYawRad, double cos, double sin, int playerY,
		boolean undergroundMode, int[] colors, int[] heights, int[] flags,
		boolean[] unknown, int sampleStepX, int sampleStepZ,
		Long2LongOpenHashMap frameSampleCache)
	{
		if(sampleStepX > 0)
		{
			sampleWindow(level, centerX, centerZ, backingSamples, samples,
				worldPerSample, rotationYawRad, cos, sin, playerY,
				undergroundMode, colors, heights, flags, unknown,
				backingSamples - sampleStepX, backingSamples, 0, backingSamples,
				frameSampleCache);
		}else if(sampleStepX < 0)
		{
			sampleWindow(level, centerX, centerZ, backingSamples, samples,
				worldPerSample, rotationYawRad, cos, sin, playerY,
				undergroundMode, colors, heights, flags, unknown, 0,
				-sampleStepX, 0, backingSamples, frameSampleCache);
		}
		
		if(sampleStepZ > 0)
		{
			sampleWindow(level, centerX, centerZ, backingSamples, samples,
				worldPerSample, rotationYawRad, cos, sin, playerY,
				undergroundMode, colors, heights, flags, unknown, 0,
				backingSamples, backingSamples - sampleStepZ, backingSamples,
				frameSampleCache);
		}else if(sampleStepZ < 0)
		{
			sampleWindow(level, centerX, centerZ, backingSamples, samples,
				worldPerSample, rotationYawRad, cos, sin, playerY,
				undergroundMode, colors, heights, flags, unknown, 0,
				backingSamples, 0, -sampleStepZ, frameSampleCache);
		}
	}
	
	private void sampleWindow(ClientLevel level, double centerX, double centerZ,
		int backingSamples, int samples, double worldPerSample,
		double rotationYawRad, double cos, double sin, int playerY,
		boolean undergroundMode, int[] colors, int[] heights, int[] flags,
		boolean[] unknown, int startX, int endX, int startZ, int endZ,
		Long2LongOpenHashMap frameSampleCache)
	{
		for(int sx = startX; sx < endX; sx++)
		{
			double screenXBase =
				(sx + 0.5 - backingSamples / 2.0) * worldPerSample;
			for(int sz = startZ; sz < endZ; sz++)
			{
				double screenZ =
					(sz + 0.5 - backingSamples / 2.0) * worldPerSample;
				double worldDx = screenXBase;
				double worldDz = screenZ;
				if(rotationYawRad != 0.0)
				{
					worldDx = screenXBase * cos - screenZ * sin;
					worldDz = screenXBase * sin + screenZ * cos;
				}
				
				int wx = Mth.floor(centerX + worldDx);
				int wz = Mth.floor(centerZ + worldDz);
				int idx = sx * backingSamples + sz;
				long cacheKey = columnKey(wx, wz, undergroundMode);
				long encoded = frameSampleCache.get(cacheKey);
				if(encoded == Long.MIN_VALUE)
				{
					Sample sample = sampleSurface(level, wx, wz, 0,
						undergroundMode, playerY);
					encoded = packSample(sample);
					frameSampleCache.put(cacheKey, encoded);
				}
				colors[idx] = unpackColor(encoded);
				heights[idx] = unpackY(encoded);
				flags[idx] = unpackFlags(encoded);
				unknown[idx] = !unpackKnown(encoded);
			}
		}
	}
	
	private void uploadTexture(Minecraft mc, int[] pixels, int sourceSamples,
		int targetSize, boolean undergroundMode)
	{
		ensureTexture(mc, targetSize);
		NativeImage img = minimapTexture.getPixels();
		if(img == null)
		{
			return;
		}
		if(sourceSamples == targetSize)
		{
			for(int x = 0; x < targetSize; x++)
			{
				int row = x * sourceSamples;
				for(int y = 0; y < targetSize; y++)
				{
					img.setPixel(x, y, pixels[row + y]);
				}
			}
			minimapTexture.upload();
			return;
		}
		for(int x = 0; x < targetSize; x++)
		{
			float srcFx = ((x + 0.5f) * sourceSamples / targetSize) - 0.5f;
			int sx0 = Mth.clamp(Mth.floor(srcFx), 0, sourceSamples - 1);
			int sx1 = Mth.clamp(sx0 + 1, 0, sourceSamples - 1);
			float tx = srcFx - sx0;
			float wx0 = 1.0f - tx;
			float wx1 = tx;
			for(int y = 0; y < targetSize; y++)
			{
				float srcFy = ((y + 0.5f) * sourceSamples / targetSize) - 0.5f;
				int sy0 = Mth.clamp(Mth.floor(srcFy), 0, sourceSamples - 1);
				int sy1 = Mth.clamp(sy0 + 1, 0, sourceSamples - 1);
				float ty = srcFy - sy0;
				float wy0 = 1.0f - ty;
				float wy1 = ty;
				
				int c00 = pixels[sx0 * sourceSamples + sy0];
				int c10 = pixels[sx1 * sourceSamples + sy0];
				int c01 = pixels[sx0 * sourceSamples + sy1];
				int c11 = pixels[sx1 * sourceSamples + sy1];
				
				float w00 = wx0 * wy0;
				float w10 = wx1 * wy0;
				float w01 = wx0 * wy1;
				float w11 = wx1 * wy1;
				
				int r = (int)((((c00 >> 16) & 0xFF) * w00)
					+ (((c10 >> 16) & 0xFF) * w10)
					+ (((c01 >> 16) & 0xFF) * w01)
					+ (((c11 >> 16) & 0xFF) * w11));
				int g = (int)((((c00 >> 8) & 0xFF) * w00)
					+ (((c10 >> 8) & 0xFF) * w10) + (((c01 >> 8) & 0xFF) * w01)
					+ (((c11 >> 8) & 0xFF) * w11));
				int b = (int)(((c00 & 0xFF) * w00) + ((c10 & 0xFF) * w10)
					+ ((c01 & 0xFF) * w01) + ((c11 & 0xFF) * w11));
				img.setPixel(x, y, 0xFF000000 | (Mth.clamp(r, 0, 255) << 16)
					| (Mth.clamp(g, 0, 255) << 8) | Mth.clamp(b, 0, 255));
			}
		}
		minimapTexture.upload();
	}
	
	private void ensureTexture(Minecraft mc, int samples)
	{
		if(minimapTexture != null && textureSize == samples)
		{
			return;
		}
		if(minimapTexture != null)
		{
			minimapTexture.close();
		}
		minimapTexture =
			new DynamicTexture("mapa_minimap", samples, samples, false);
		if(ABSTRACT_TEXTURE_SAMPLER_FIELD != null)
		{
			try
			{
				ABSTRACT_TEXTURE_SAMPLER_FIELD.set(minimapTexture, RenderSystem
					.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
			}catch(Throwable ignored)
			{}
		}
		textureSize = samples;
		mc.getTextureManager().register(MINIMAP_TEX_ID, minimapTexture);
	}
	
	private static void fillUnknownFromNeighbors(int[] colors, int[] heights,
		int[] flags, boolean[] unknown, int samples, int passes,
		boolean undergroundMode)
	{
		for(int pass = 0; pass < passes; pass++)
		{
			boolean changed = false;
			boolean[] nextUnknown = unknown.clone();
			for(int sx = 0; sx < samples; sx++)
			{
				for(int sz = 0; sz < samples; sz++)
				{
					int idx = sx * samples + sz;
					if(!unknown[idx])
					{
						continue;
					}
					
					int sumR = 0;
					int sumG = 0;
					int sumB = 0;
					int sumH = 0;
					int waterVotes = 0;
					int count = 0;
					
					if(sx > 0)
					{
						int n = (sx - 1) * samples + sz;
						if(!unknown[n])
						{
							int c = colors[n];
							sumR += (c >> 16) & 0xFF;
							sumG += (c >> 8) & 0xFF;
							sumB += c & 0xFF;
							sumH += heights[n];
							waterVotes += flags[n] & SAMPLE_FLAG_WATER;
							count++;
						}
					}
					if(sx + 1 < samples)
					{
						int n = (sx + 1) * samples + sz;
						if(!unknown[n])
						{
							int c = colors[n];
							sumR += (c >> 16) & 0xFF;
							sumG += (c >> 8) & 0xFF;
							sumB += c & 0xFF;
							sumH += heights[n];
							waterVotes += flags[n] & SAMPLE_FLAG_WATER;
							count++;
						}
					}
					if(sz > 0)
					{
						int n = sx * samples + (sz - 1);
						if(!unknown[n])
						{
							int c = colors[n];
							sumR += (c >> 16) & 0xFF;
							sumG += (c >> 8) & 0xFF;
							sumB += c & 0xFF;
							sumH += heights[n];
							waterVotes += flags[n] & SAMPLE_FLAG_WATER;
							count++;
						}
					}
					if(sz + 1 < samples)
					{
						int n = sx * samples + (sz + 1);
						if(!unknown[n])
						{
							int c = colors[n];
							sumR += (c >> 16) & 0xFF;
							sumG += (c >> 8) & 0xFF;
							sumB += c & 0xFF;
							sumH += heights[n];
							waterVotes += flags[n] & SAMPLE_FLAG_WATER;
							count++;
						}
					}
					
					if(count > 0)
					{
						int r = sumR / count;
						int g = sumG / count;
						int b = sumB / count;
						colors[idx] = 0xFF000000 | (r << 16) | (g << 8) | b;
						heights[idx] = sumH / count;
						flags[idx] =
							waterVotes * 2 >= count ? SAMPLE_FLAG_WATER : 0;
						nextUnknown[idx] = false;
						changed = true;
					}
				}
			}
			System.arraycopy(nextUnknown, 0, unknown, 0, unknown.length);
			if(!changed)
			{
				break;
			}
		}
		
		if(!undergroundMode)
		{
			return;
		}
	}
	
	private static void despeckle(int[] pixels, int samples)
	{
		int[] src = pixels.clone();
		for(int x = 1; x < samples - 1; x++)
		{
			for(int y = 1; y < samples - 1; y++)
			{
				int idx = x * samples + y;
				int c = src[idx];
				int r = (c >> 16) & 0xFF;
				int g = (c >> 8) & 0xFF;
				int b = c & 0xFF;
				int l = (r * 30 + g * 59 + b * 11) / 100;
				if(l < 220)
				{
					continue;
				}
				
				int sumR = 0;
				int sumG = 0;
				int sumB = 0;
				int count = 0;
				for(int dx = -1; dx <= 1; dx++)
				{
					for(int dy = -1; dy <= 1; dy++)
					{
						if(dx == 0 && dy == 0)
						{
							continue;
						}
						int nc = src[(x + dx) * samples + (y + dy)];
						sumR += (nc >> 16) & 0xFF;
						sumG += (nc >> 8) & 0xFF;
						sumB += nc & 0xFF;
						count++;
					}
				}
				int avgR = sumR / count;
				int avgG = sumG / count;
				int avgB = sumB / count;
				int dist = Math.abs(r - avgR) + Math.abs(g - avgG)
					+ Math.abs(b - avgB);
				if(dist > 90)
				{
					pixels[idx] =
						0xFF000000 | (avgR << 16) | (avgG << 8) | avgB;
				}
			}
		}
	}
	
	private void drawTexture(GuiGraphicsExtractor gfx, int drawX, int drawY,
		int drawSize, double centerX, double centerZ, boolean rotateWithPlayer,
		float yawDeg, boolean invertRotation)
	{
		if(minimapTexture == null || textureSize <= 0 || cachedSamples <= 0)
		{
			return;
		}
		double liveRotationAngle =
			rotationAngleRad(rotateWithPlayer, yawDeg, invertRotation);
		float deltaRotation = (float)-liveRotationAngle;
		boolean rotating = Math.abs(deltaRotation) > 0.0001f;
		int sourceSamples = cachedSamples;
		int renderSize = drawSize;
		int renderX = drawX;
		int renderY = drawY;
		double sampleDeltaX =
			(centerX - cachedCenterX) / Math.max(0.0001, cachedWorldPerSample);
		double sampleDeltaZ =
			(centerZ - cachedCenterZ) / Math.max(0.0001, cachedWorldPerSample);
		if(rotating)
		{
			sourceSamples = Math.min(textureSize, Math.min(cachedBackingSamples,
				rotatedSourceSamples(cachedSamples)));
			renderSize =
				Math.max(drawSize, (int)Math.ceil(drawSize * ROTATION_SCALE));
			renderX = drawX - (renderSize - drawSize) / 2;
			renderY = drawY - (renderSize - drawSize) / 2;
		}
		float maxUv = Math.max(0.0f, textureSize - sourceSamples);
		float u = Mth.clamp(
			(textureSize - sourceSamples) * 0.5f + (float)sampleDeltaX, 0.0f,
			maxUv);
		float v = Mth.clamp(
			(textureSize - sourceSamples) * 0.5f + (float)sampleDeltaZ, 0.0f,
			maxUv);
		if(rotating)
		{
			float pivotX = drawX + drawSize * 0.5f;
			float pivotY = drawY + drawSize * 0.5f;
			gfx.enableScissor(drawX, drawY, drawX + drawSize, drawY + drawSize);
			gfx.pose().pushMatrix();
			gfx.pose().rotateAbout(deltaRotation, pivotX, pivotY);
			blitMinimap(gfx, renderX, renderY, renderSize, sourceSamples, u, v);
			gfx.pose().popMatrix();
			gfx.disableScissor();
			return;
		}
		blitMinimap(gfx, renderX, renderY, renderSize, sourceSamples, u, v);
	}
	
	private void drawTextureRect(GuiGraphicsExtractor gfx, int drawX, int drawY,
		int drawWidth, int drawHeight, double centerX, double centerZ)
	{
		if(minimapTexture == null || textureSize <= 0 || cachedSamples <= 0)
			return;
		
		double sampleDeltaX =
			(centerX - cachedCenterX) / Math.max(0.0001, cachedWorldPerSample);
		double sampleDeltaZ =
			(centerZ - cachedCenterZ) / Math.max(0.0001, cachedWorldPerSample);
		int sourceSamplesX = Mth.clamp(
			(int)Math.round(drawWidth * cachedZoom / cachedWorldPerSample), 1,
			textureSize);
		int sourceSamplesY = Mth.clamp(
			(int)Math.round(drawHeight * cachedZoom / cachedWorldPerSample), 1,
			textureSize);
		float maxU = Math.max(0.0f, textureSize - sourceSamplesX);
		float maxV = Math.max(0.0f, textureSize - sourceSamplesY);
		float u = Mth.clamp(
			(textureSize - sourceSamplesX) * 0.5f + (float)sampleDeltaX, 0.0f,
			maxU);
		float v = Mth.clamp(
			(textureSize - sourceSamplesY) * 0.5f + (float)sampleDeltaZ, 0.0f,
			maxV);
		gfx.blit(RenderPipelines.GUI_TEXTURED, MINIMAP_TEX_ID, drawX, drawY, u,
			v, drawWidth, drawHeight, sourceSamplesX, sourceSamplesY,
			textureSize, textureSize, -1);
	}
	
	private void blitMinimap(GuiGraphicsExtractor gfx, int renderX, int renderY,
		int renderSize, int sourceSamples, float u, float v)
	{
		gfx.blit(RenderPipelines.GUI_TEXTURED, MINIMAP_TEX_ID, renderX, renderY,
			u, v, renderSize, renderSize, sourceSamples, sourceSamples,
			textureSize, textureSize, -1);
	}
	
	private Sample sampleSurface(ClientLevel level, int x, int z,
		int fallbackArgb, boolean undergroundMode, int playerY)
	{
		int cx = x >> 4;
		int cz = z >> 4;
		long key = columnKey(x, z, undergroundMode);
		LevelChunk chunk = getSampledChunk(level, cx, cz);
		if(chunk == null)
		{
			if(!undergroundMode && cachedColumnColor.containsKey(key))
			{
				return new Sample(cachedColumnColor.get(key),
					cachedColumnY.get(key), true, cachedColumnFlags.get(key));
			}
			Sample nearby = undergroundMode ? null
				: sampleNearbyCached(x, z, undergroundMode);
			if(nearby != null)
			{
				return new Sample(nearby.argb(), nearby.y(), false,
					nearby.flags());
			}
			int fallback = fallbackArgb != 0 ? fallbackArgb : lastKnownColor;
			if(undergroundMode)
			{
				fallback = 0xFF000000;
			}
			return new Sample(fallback, lastKnownY, false, 0);
		}
		
		int minY = level.getMinY();
		int maxY = level.getMaxY();
		int surfaceHeight = Integer.MIN_VALUE;
		int seafloorHeight = Integer.MIN_VALUE;
		int transparentHeight = Integer.MIN_VALUE;
		int foliageHeight = Integer.MIN_VALUE;
		
		BlockState surfaceState = Blocks.AIR.defaultBlockState();
		BlockState transparentState = Blocks.AIR.defaultBlockState();
		BlockState foliageState = Blocks.AIR.defaultBlockState();
		BlockState seafloorState = Blocks.AIR.defaultBlockState();
		
		if(!undergroundMode)
		{
			int topHeight =
				chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15)
					+ 1;
			BlockState topState = blockStateAtHeight(level, x, z, topHeight);
			transparentHeight = topHeight;
			transparentState = topState;
			surfaceHeight = transparentHeight;
			surfaceState = transparentState;
			
			while(surfaceHeight > minY && !isOpaqueForMap(surfaceState))
			{
				foliageState = surfaceState;
				surfaceHeight--;
				surfaceState = blockStateAtHeight(level, x, z, surfaceHeight);
			}
			
			if(surfaceHeight == transparentHeight)
			{
				transparentHeight = Integer.MIN_VALUE;
				transparentState = Blocks.AIR.defaultBlockState();
				probe.set(x, surfaceHeight, z);
				foliageState = level.getBlockState(probe);
			}
			
			if(foliageState.is(Blocks.SNOW))
			{
				surfaceState = foliageState;
				foliageState = Blocks.AIR.defaultBlockState();
			}
			if(foliageState == transparentState)
			{
				foliageState = Blocks.AIR.defaultBlockState();
			}
			if(!showSmallPlants)
			{
				if(isSmallPlantOverlayState(transparentState))
				{
					transparentHeight = Integer.MIN_VALUE;
					transparentState = Blocks.AIR.defaultBlockState();
				}
				if(isSmallPlantOverlayState(foliageState))
				{
					foliageHeight = Integer.MIN_VALUE;
					foliageState = Blocks.AIR.defaultBlockState();
				}
			}
			if(!showTreeCanopies)
			{
				if(transparentState.getBlock() instanceof LeavesBlock)
				{
					transparentHeight = Integer.MIN_VALUE;
					transparentState = Blocks.AIR.defaultBlockState();
				}
				if(foliageState.getBlock() instanceof LeavesBlock)
				{
					foliageHeight = Integer.MIN_VALUE;
					foliageState = Blocks.AIR.defaultBlockState();
				}
				if(surfaceState.getBlock() instanceof LeavesBlock)
				{
					surfaceHeight = chunk.getHeight(
						Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15,
						z & 15) + 1;
					surfaceState =
						blockStateAtHeight(level, x, z, surfaceHeight);
				}
			}
			if(foliageHeight == Integer.MIN_VALUE
				&& !(foliageState.getBlock() instanceof AirBlock))
			{
				foliageHeight = surfaceHeight + 1;
			}
			
			Block material = surfaceState.getBlock();
			if(material == Blocks.WATER || material == Blocks.ICE)
			{
				seafloorHeight = surfaceHeight;
				seafloorState = blockStateAtHeight(level, x, z, seafloorHeight);
				while(seafloorHeight > minY + 1
					&& seafloorState.getLightDampening() < 5
					&& !(seafloorState.getBlock() instanceof LeavesBlock))
				{
					material = seafloorState.getBlock();
					if(transparentHeight == Integer.MIN_VALUE
						&& material != Blocks.ICE && material != Blocks.WATER
						&& Heightmap.Types.MOTION_BLOCKING.isOpaque()
							.test(seafloorState))
					{
						transparentHeight = seafloorHeight;
						transparentState = seafloorState;
					}
					if(foliageHeight == Integer.MIN_VALUE
						&& seafloorHeight != transparentHeight
						&& transparentState != seafloorState
						&& material != Blocks.ICE && material != Blocks.WATER
						&& !(material instanceof AirBlock)
						&& material != Blocks.BUBBLE_COLUMN)
					{
						foliageHeight = seafloorHeight;
						foliageState = seafloorState;
					}
					seafloorHeight--;
					seafloorState =
						blockStateAtHeight(level, x, z, seafloorHeight);
				}
				if(seafloorState.is(Blocks.WATER))
				{
					seafloorState = Blocks.AIR.defaultBlockState();
					seafloorHeight = Integer.MIN_VALUE;
				}
			}
		}else
		{
			surfaceHeight =
				getVoxelStyleBlockHeight(level, x, z, playerY, true);
			if(surfaceHeight == Integer.MIN_VALUE)
			{
				int argb = 0xFF000000;
				lastKnownColor = argb;
				lastKnownY = playerY;
				if(!undergroundMode)
				{
					putColumnCache(key, argb, playerY, 0);
				}
				return new Sample(argb, playerY, true, 0);
			}
			surfaceState = blockStateAtHeight(level, x, z, surfaceHeight);
			foliageHeight = surfaceHeight + 1;
			foliageState = blockStateAtHeight(level, x, z, foliageHeight);
			Block material = foliageState.getBlock();
			if(material == Blocks.SNOW || material instanceof AirBlock
				|| material == Blocks.LAVA || material == Blocks.WATER)
			{
				foliageHeight = Integer.MIN_VALUE;
				foliageState = Blocks.AIR.defaultBlockState();
			}
			if(!showTreeCanopies
				&& foliageState.getBlock() instanceof LeavesBlock)
			{
				foliageHeight = Integer.MIN_VALUE;
				foliageState = Blocks.AIR.defaultBlockState();
			}
			if(!showSmallPlants && isSmallPlantOverlayState(foliageState))
			{
				foliageHeight = Integer.MIN_VALUE;
				foliageState = Blocks.AIR.defaultBlockState();
			}
		}
		
		if(surfaceHeight == Integer.MIN_VALUE)
		{
			int argb = fallbackArgb != 0 ? fallbackArgb : 0xFF000000;
			lastKnownColor = argb;
			lastKnownY = playerY;
			if(!undergroundMode)
			{
				putColumnCache(key, argb, playerY, 0);
			}
			return new Sample(argb, playerY, true, 0);
		}
		
		int surfaceColor =
			argbFromState(level, surfaceState, x, surfaceHeight - 1, z, 0xFF);
		boolean useDynamicLighting = !basicPaletteMode && (undergroundMode
			? undergroundDynamicLighting : surfaceDynamicLighting);
		if(useDynamicLighting)
		{
			surfaceColor = applyDynamicLight(level, surfaceState, x, z,
				surfaceHeight, surfaceColor);
		}
		boolean surfaceIsWaterLike =
			surfaceState.is(Blocks.WATER) || surfaceState.is(Blocks.ICE)
				|| surfaceState.getFluidState().is(Fluids.WATER);
		int seafloorColor = 0;
		int transparentColor = 0;
		int foliageColor = 0;
		
		if(seafloorHeight != Integer.MIN_VALUE && !seafloorState.isAir())
		{
			seafloorColor = argbFromState(level, seafloorState, x,
				seafloorHeight - 1, z, 0xD0);
			if(useDynamicLighting)
			{
				seafloorColor = applyDynamicLight(level, seafloorState, x, z,
					seafloorHeight, seafloorColor);
			}
		}
		if(transparentHeight != Integer.MIN_VALUE && !transparentState.isAir())
		{
			transparentColor = argbFromState(level, transparentState, x,
				transparentHeight - 1, z, 0xB0);
			if(useDynamicLighting)
			{
				transparentColor = applyDynamicLight(level, transparentState, x,
					z, transparentHeight, transparentColor);
			}
		}
		if(foliageHeight != Integer.MIN_VALUE && !foliageState.isAir())
		{
			foliageColor = argbFromState(level, foliageState, x,
				foliageHeight - 1, z, 0xC0);
			if(useDynamicLighting)
			{
				foliageColor = applyDynamicLight(level, foliageState, x, z,
					foliageHeight, foliageColor);
			}
			if(foliageState.getBlock() instanceof LeavesBlock)
			{
				// Keep canopy readable without the aggressive dark speckling.
				int leafRgb = foliageColor & 0x00FFFFFF;
				int leafAlpha = 0xBC;
				if(foliageState.is(Blocks.CHERRY_LEAVES))
				{
					leafRgb =
						blendRgb(foliageColor & 0x00FFFFFF, 0xD997C1, 0.55f);
					leafAlpha = 0xB4;
				}else
				{
					leafRgb = blendRgb(leafRgb, multiplyRgb(leafRgb, 0x8A8A8A),
						0.55f);
				}
				foliageColor = (leafAlpha << 24) | leafRgb;
			}else
			{
				int foliageRgb = foliageColor & 0x00FFFFFF;
				int foliageAlpha = Math.min(0x7A, (foliageColor >>> 24) & 0xFF);
				foliageColor = (foliageAlpha << 24) | foliageRgb;
			}
		}
		if(surfaceIsWaterLike && seafloorColor != 0
			&& seafloorHeight != Integer.MIN_VALUE)
		{
			int depth = Math.max(1, surfaceHeight - seafloorHeight);
			// Keep the water surface flatter and push most visible detail into
			// the seafloor layer.
			int waterAlpha =
				Mth.clamp((int)((88 + depth * 3) * waterOpacity), 64, 168);
			int waterRgb = blendRgb(surfaceColor & 0x00FFFFFF, 0x5D90D8, 0.30f);
			int floorAlpha =
				Mth.clamp((int)((196 - depth * 9) * waterDetail), 84, 196);
			int floorRgb = multiplyRgb(seafloorColor & 0x00FFFFFF, 0x9AA3AE);
			seafloorColor = (floorAlpha << 24) | floorRgb;
			surfaceColor = (waterAlpha << 24) | waterRgb;
		}
		
		int color;
		int shadeHeight = surfaceHeight;
		if(seafloorColor != 0)
		{
			color = seafloorColor;
			shadeHeight = seafloorHeight;
			if(foliageColor != 0 && foliageHeight <= surfaceHeight)
			{
				color = colorAdder(foliageColor, color);
			}
			if(transparentColor != 0 && transparentHeight <= surfaceHeight)
			{
				color = colorAdder(transparentColor, color);
			}
			color = colorAdder(surfaceColor, color);
		}else
		{
			color = surfaceColor;
		}
		if(foliageColor != 0 && foliageHeight > surfaceHeight)
		{
			color = colorAdder(foliageColor, color);
		}
		if(transparentColor != 0 && transparentHeight > surfaceHeight)
		{
			color = colorAdder(transparentColor, color);
		}
		
		// Keep map pixels fully opaque to avoid rotational translucent shimmer
		// artifacts.
		color = 0xFF000000 | (color & 0x00FFFFFF);
		int flags = surfaceIsWaterLike ? SAMPLE_FLAG_WATER : 0;
		
		lastKnownColor = color;
		lastKnownY = shadeHeight;
		if(!undergroundMode)
		{
			putColumnCache(key, color, shadeHeight, flags);
		}
		return new Sample(color, shadeHeight, true, flags);
	}
	
	private BlockState blockStateAtHeight(ClientLevel level, int x, int z,
		int heightAboveBlock)
	{
		int y =
			Mth.clamp(heightAboveBlock - 1, level.getMinY(), level.getMaxY());
		probe.set(x, y, z);
		BlockState state = level.getBlockState(probe);
		FluidState fluid = state.getFluidState();
		if(fluid != Fluids.EMPTY.defaultFluidState())
		{
			state = fluid.createLegacyBlock();
		}
		return state;
	}
	
	private int argbFromState(ClientLevel level, BlockState state, int x, int y,
		int z, int alpha)
	{
		probe.set(x, Mth.clamp(y, level.getMinY(), level.getMaxY()), z);
		int rgb = basicPaletteMode ? basicPaletteRgb(level, state, probe)
			: blockColorRgb(level, state, probe);
		int a = alpha & 0xFF;
		if(state.is(Blocks.WATER) || state.is(Blocks.ICE))
		{
			a = Math.min(a, 0xB8);
		}else if(state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK))
		{
			a = Math.min(a, 0xD8);
		}
		return (a << 24) | (rgb & 0x00FFFFFF);
	}
	
	private int blockColorRgb(ClientLevel level, BlockState state, BlockPos pos)
	{
		if(state.isAir())
		{
			return 0x000000;
		}
		int base = baseColorRgb(level, state, pos);
		if(state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK))
		{
			return 0xFF6A00;
		}
		if(state.is(Blocks.WATER) || state.getFluidState().is(Fluids.WATER))
		{
			int neutralWater = 0x5F8FD8;
			int waterTint = averageBiomeWaterTint(level, pos);
			int tintedWater = blendRgb(waterTint, neutralWater, 0.40f);
			int rgb = applyTintStrength(neutralWater, tintedWater, waterTint,
				waterTintStrength);
			return applyOptionalColorOverride(rgb, waterTintColor,
				waterTintStrength);
		}
		if(state.getBlock() instanceof LeavesBlock)
		{
			int tint = level.getClientLeafTintColor(pos);
			int neutralLeaves = base;
			if(state.is(Blocks.CHERRY_LEAVES))
			{
				int cherryLeaves =
					blendRgb(multiplyRgb(base, tint), 0xD997C1, 0.35f);
				int rgb = applyTintStrength(neutralLeaves, cherryLeaves, tint,
					foliageTintStrength);
				return applyOptionalColorOverride(rgb, foliageTintColor,
					foliageTintStrength);
			}
			int rgb = applyTintStrength(neutralLeaves, multiplyRgb(base, tint),
				tint, foliageTintStrength);
			return applyOptionalColorOverride(rgb, foliageTintColor,
				foliageTintStrength);
		}
		if(isGrassTintState(state))
		{
			int tint = BiomeColors.getAverageGrassColor(level, pos);
			int grassBase = blendRgb(base, 0xFFFFFF, 0.38f);
			int tintedGrass = blendRgb(multiplyRgb(base, tint), tint, 0.52f);
			int rgb = applyTintStrength(grassBase, tintedGrass, tint,
				grassTintStrength);
			return applyOptionalColorOverride(rgb, grassTintColor,
				grassTintStrength);
		}
		List<BlockTintSource> tintSources =
			Minecraft.getInstance().getBlockColors().getTintSources(state);
		if(!tintSources.isEmpty() && prefersMapColorBase(state))
		{
			MapColor mapColor = state.getMapColor(level, pos);
			if(mapColor != null && mapColor.col != 0)
			{
				base = mapColor.col;
			}
		}
		if(!tintSources.isEmpty())
		{
			try
			{
				int tint = fastBiomeTint(level, state, pos, tintSources.get(0));
				if(tint != -1 && tint != 0)
				{
					if(state.is(Blocks.CHERRY_LEAVES))
					{
						// Keep cherry foliage readable and pink-toned.
						return blendRgb(tint & 0x00FFFFFF, 0xD997C1, 0.35f);
					}
					// VoxelMap-like behavior: apply biome tint through the
					// block's base color.
					base = multiplyRgb(base, tint);
				}
			}catch(Throwable ignored)
			{}
		}
		return base;
	}
	
	private int basicPaletteRgb(ClientLevel level, BlockState state,
		BlockPos pos)
	{
		if(state.isAir())
		{
			return 0x000000;
		}
		if(state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK))
		{
			return 0xFF6A00;
		}
		if(state.is(Blocks.WATER) || state.is(Blocks.ICE)
			|| state.getFluidState().is(Fluids.WATER))
		{
			return level.getBiome(pos).value().getWaterColor();
		}
		MapColor mapColor = state.getMapColor(level, pos);
		if(mapColor != null && mapColor.col != 0)
		{
			return mapColor.col;
		}
		return baseColorRgb(level, state, pos);
	}
	
	private static boolean isGrassTintState(BlockState state)
	{
		return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.SHORT_GRASS)
			|| state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN)
			|| state.is(Blocks.LARGE_FERN);
	}
	
	private static boolean prefersMapColorBase(BlockState state)
	{
		return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.SHORT_GRASS)
			|| state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN)
			|| state.is(Blocks.LARGE_FERN) || state.is(Blocks.VINE)
			|| state.is(Blocks.LILY_PAD)
			|| state.getBlock() instanceof LeavesBlock;
	}
	
	private int fastBiomeTint(ClientLevel level, BlockState state, BlockPos pos,
		BlockTintSource tintSource)
	{
		int radius = 1;
		int y = Mth.clamp(pos.getY(), level.getMinY(), level.getMaxY() - 1);
		int sumR = 0;
		int sumG = 0;
		int sumB = 0;
		int count = 0;
		for(int dx = -radius; dx <= radius; dx++)
		{
			for(int dz = -radius; dz <= radius; dz++)
			{
				tintProbe.set(pos.getX() + dx, y, pos.getZ() + dz);
				int tint = tintSource.colorInWorld(state, level, tintProbe);
				if(tint == 0)
				{
					continue;
				}
				sumR += (tint >> 16) & 0xFF;
				sumG += (tint >> 8) & 0xFF;
				sumB += tint & 0xFF;
				count++;
			}
		}
		if(count == 0)
		{
			return -1;
		}
		int r = sumR / count;
		int g = sumG / count;
		int b = sumB / count;
		return (r << 16) | (g << 8) | b;
	}
	
	private int averageBiomeWaterTint(ClientLevel level, BlockPos pos)
	{
		int radius = 1;
		int y = Mth.clamp(pos.getY(), level.getMinY(), level.getMaxY() - 1);
		int sumR = 0;
		int sumG = 0;
		int sumB = 0;
		int count = 0;
		for(int dx = -radius; dx <= radius; dx++)
		{
			for(int dz = -radius; dz <= radius; dz++)
			{
				tintProbe.set(pos.getX() + dx, y, pos.getZ() + dz);
				int tint = level.getBiome(tintProbe).value().getWaterColor();
				sumR += (tint >> 16) & 0xFF;
				sumG += (tint >> 8) & 0xFF;
				sumB += tint & 0xFF;
				count++;
			}
		}
		if(count == 0)
		{
			return level.getBiome(pos).value().getWaterColor();
		}
		int r = sumR / count;
		int g = sumG / count;
		int b = sumB / count;
		return (r << 16) | (g << 8) | b;
	}
	
	private static boolean isSmallPlantOverlayState(BlockState state)
	{
		if(state == null || state.isAir())
		{
			return false;
		}
		return state.is(Blocks.SHORT_GRASS) || state.is(Blocks.TALL_GRASS)
			|| state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)
			|| state.is(Blocks.DEAD_BUSH) || state.is(Blocks.SEAGRASS)
			|| state.is(Blocks.TALL_SEAGRASS) || state.is(Blocks.LILY_PAD)
			|| state.is(Blocks.SUGAR_CANE) || state.is(Blocks.VINE);
	}
	
	private int baseColorRgb(ClientLevel level, BlockState state, BlockPos pos)
	{
		int stateId = Block.getId(state);
		int cached = cachedStateBaseColor.get(stateId);
		if(cached != -1)
		{
			return cached;
		}
		
		int rgb = 0x7F7F7F;
		try
		{
			TextureAtlasSprite sprite = Minecraft.getInstance()
				.getModelManager().getBlockStateModelSet()
				.getParticleMaterial(state).sprite();
			if(sprite != null)
			{
				NativeImage image = spriteImage(sprite.contents());
				if(image != null)
				{
					int avg = averageSpriteColor(image, image.getWidth(),
						image.getHeight());
					if(avg != 0 && !looksLikeMissingTexture(avg))
					{
						rgb = avg;
					}
				}
			}
		}catch(Throwable ignored)
		{}
		if(rgb == 0x7F7F7F)
		{
			MapColor mapColor = state.getMapColor(level, pos);
			if(mapColor != null && mapColor.col != 0)
			{
				rgb = mapColor.col;
			}
		}
		cachedStateBaseColor.put(stateId, rgb);
		return rgb;
	}
	
	private static NativeImage spriteImage(SpriteContents contents)
	{
		if(SPRITE_ORIGINAL_IMAGE_FIELD == null)
		{
			return null;
		}
		try
		{
			return (NativeImage)SPRITE_ORIGINAL_IMAGE_FIELD.get(contents);
		}catch(Throwable ignored)
		{
			return null;
		}
	}
	
	private static int averageSpriteColor(NativeImage image, int width,
		int height)
	{
		long r = 0;
		long g = 0;
		long b = 0;
		long count = 0;
		for(int y = 0; y < height; y++)
		{
			for(int x = 0; x < width; x++)
			{
				int argb = image.getPixel(x, y);
				int a = (argb >>> 24) & 0xFF;
				if(a < 16)
				{
					continue;
				}
				r += (argb >> 16) & 0xFF;
				g += (argb >> 8) & 0xFF;
				b += argb & 0xFF;
				count++;
			}
		}
		if(count == 0)
		{
			return 0;
		}
		int rr = (int)(r / count);
		int gg = (int)(g / count);
		int bb = (int)(b / count);
		return (rr << 16) | (gg << 8) | bb;
	}
	
	private static boolean looksLikeMissingTexture(int rgb)
	{
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return r > 150 && b > 150 && g < 120;
	}
	
	private Sample sampleNearbyCached(int x, int z, boolean undergroundMode)
	{
		for(int r = 1; r <= 6; r++)
		{
			for(int dx = -r; dx <= r; dx++)
			{
				int dz1 = -r;
				int dz2 = r;
				long k1 = columnKey(x + dx, z + dz1, undergroundMode);
				if(cachedColumnColor.containsKey(k1))
				{
					return new Sample(cachedColumnColor.get(k1),
						cachedColumnY.get(k1), true, cachedColumnFlags.get(k1));
				}
				long k2 = columnKey(x + dx, z + dz2, undergroundMode);
				if(cachedColumnColor.containsKey(k2))
				{
					return new Sample(cachedColumnColor.get(k2),
						cachedColumnY.get(k2), true, cachedColumnFlags.get(k2));
				}
			}
			for(int dz = -r + 1; dz <= r - 1; dz++)
			{
				int dx1 = -r;
				int dx2 = r;
				long k1 = columnKey(x + dx1, z + dz, undergroundMode);
				if(cachedColumnColor.containsKey(k1))
				{
					return new Sample(cachedColumnColor.get(k1),
						cachedColumnY.get(k1), true, cachedColumnFlags.get(k1));
				}
				long k2 = columnKey(x + dx2, z + dz, undergroundMode);
				if(cachedColumnColor.containsKey(k2))
				{
					return new Sample(cachedColumnColor.get(k2),
						cachedColumnY.get(k2), true, cachedColumnFlags.get(k2));
				}
			}
		}
		return null;
	}
	
	private static boolean isOpaqueForMap(BlockState state)
	{
		if(state.is(Blocks.WATER) || state.is(Blocks.ICE)
			|| state.is(Blocks.LAVA))
		{
			// Treat fluids as top layer for minimap composition so underwater
			// terrain can blend in.
			return true;
		}
		if(state.getLightDampening() > 0)
		{
			return true;
		}
		if(!state.canOcclude() || !state.useShapeForLightOcclusion())
		{
			return false;
		}
		VoxelShape down = state.getFaceOcclusionShape(Direction.DOWN);
		if(Shapes.faceShapeOccludes(down, Shapes.empty()))
		{
			return true;
		}
		VoxelShape up = state.getFaceOcclusionShape(Direction.UP);
		return Shapes.faceShapeOccludes(Shapes.empty(), up);
	}
	
	private int getVoxelStyleBlockHeight(ClientLevel level, int x, int z,
		int playerY, boolean cavesMode)
	{
		int minY = level.getMinY();
		int maxY = level.getMaxY();
		int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
		probe.set(x, height - 1, z);
		BlockState state = level.getBlockState(probe);
		while(height > minY && isMapOpenBlock(state))
		{
			height--;
			probe.setY(height - 1);
			state = level.getBlockState(probe);
		}
		if(cavesMode && height > playerY)
		{
			int caveY = getVoxelStyleNetherHeight(level, x, z, playerY);
			if(caveY != Integer.MIN_VALUE)
			{
				return Mth.clamp(caveY, minY + 1, maxY - 1);
			}
			return Integer.MIN_VALUE;
		}
		return Mth.clamp(height, minY + 1, maxY - 1);
	}
	
	private int getVoxelStyleNetherHeight(ClientLevel level, int x, int z,
		int playerY)
	{
		int minY = level.getMinY();
		int maxY = level.getMaxY();
		int startY = Mth.clamp(playerY, minY + 1, maxY - 1);
		if(isMapOpenBlock(stateAt(level, x, startY, z)))
		{
			return descendToCavityFloor(level, x, z, startY, minY + 1);
		}
		
		int openBelow =
			findOpenBlockBelow(level, x, z, startY - 1, minY + 1, 24);
		if(openBelow != Integer.MIN_VALUE)
		{
			return descendToCavityFloor(level, x, z, openBelow, minY + 1);
		}
		
		int openAbove =
			findOpenBlockAbove(level, x, z, startY + 1, maxY - 1, 10);
		if(openAbove != Integer.MIN_VALUE)
		{
			return descendToCavityFloor(level, x, z, openAbove, minY + 1);
		}
		return Integer.MIN_VALUE;
	}
	
	private int findOpenBlockBelow(ClientLevel level, int x, int z, int startY,
		int minY, int maxDistance)
	{
		for(int distance = 0; distance <= maxDistance; distance++)
		{
			int y = startY - distance;
			if(y < minY)
			{
				break;
			}
			if(isMapOpenBlock(stateAt(level, x, y, z)))
			{
				return y;
			}
		}
		return Integer.MIN_VALUE;
	}
	
	private int findOpenBlockAbove(ClientLevel level, int x, int z, int startY,
		int maxY, int maxDistance)
	{
		for(int distance = 0; distance <= maxDistance; distance++)
		{
			int y = startY + distance;
			if(y > maxY)
			{
				break;
			}
			if(isMapOpenBlock(stateAt(level, x, y, z)))
			{
				return y;
			}
		}
		return Integer.MIN_VALUE;
	}
	
	private int descendToCavityFloor(ClientLevel level, int x, int z, int openY,
		int minY)
	{
		int y = openY;
		while(y > minY && isMapOpenBlock(stateAt(level, x, y - 1, z)))
		{
			y--;
		}
		return y;
	}
	
	private BlockState stateAt(ClientLevel level, int x, int y, int z)
	{
		probe.set(x, Mth.clamp(y, level.getMinY(), level.getMaxY()), z);
		return level.getBlockState(probe);
	}
	
	private static boolean isMapOpenBlock(BlockState state)
	{
		return state.getLightDampening() == 0 && !state.is(Blocks.LAVA);
	}
	
	private int applyDynamicLight(ClientLevel level, BlockState state, int x,
		int z, int height, int argb)
	{
		if((argb >>> 24) == 0)
		{
			return 0;
		}
		probe.set(x, Mth.clamp(height, level.getMinY(), level.getMaxY()), z);
		int blockLight = level.getBrightness(LightLayer.BLOCK, probe);
		int skyLight = level.getBrightness(LightLayer.SKY, probe);
		if(state.is(Blocks.LAVA) || state.is(Blocks.MAGMA_BLOCK))
		{
			blockLight = Math.max(blockLight, 14);
		}
		int light = lightmapColors[Mth.clamp(blockLight, 0, 15)
			+ Mth.clamp(skyLight, 0, 15) * 16];
		if(light == 0)
		{
			return 0;
		}
		int lr = (light >> 16) & 0xFF;
		int lg = (light >> 8) & 0xFF;
		int lb = light & 0xFF;
		int lightArgb = 0xFF000000 | (lr << 16) | (lg << 8) | lb;
		return colorMultiplier(argb, lightArgb);
	}
	
	private void rebuildLightmap(Minecraft mc, ClientLevel level)
	{
		float ambientLightFactor = level.dimensionType().ambientLight();
		float g = 1.0f - (level.getSkyDarken() / 15.0f);
		float skyFactor = g * 0.95f + 0.05f;
		
		blockLightRedFlicker =
			blockLightRedFlicker + (float)((Math.random() - Math.random())
				* Math.random() * Math.random() * 0.1);
		blockLightRedFlicker *= 0.9f;
		float blockFactor = blockLightRedFlicker + 1.5f;
		
		float skyLightColorR = g * 0.65f + 0.35f;
		float skyLightColorG = g * 0.65f + 0.35f;
		float skyLightColorB = 1.0f;
		float brightnessFactor = 0.0f;
		try
		{
			brightnessFactor =
				Math.max(0.0f, mc.options.gamma().get().floatValue());
		}catch(Throwable ignored)
		{}
		
		for(int blockLight = 0; blockLight < 16; blockLight++)
		{
			for(int skyLight = 0; skyLight < 16; skyLight++)
			{
				float blockBrightness =
					getBrightness(blockLight / 15.0f) * blockFactor;
				float skyBrightness =
					getBrightness(skyLight / 15.0f) * skyFactor;
				
				float colorR = blockBrightness;
				float colorG = blockBrightness
					* ((blockBrightness * 0.6f + 0.4f) * 0.6f + 0.4f);
				float colorB = blockBrightness
					* (blockBrightness * blockBrightness * 0.6f + 0.4f);
				
				colorR = mix(colorR, 1.0f, ambientLightFactor);
				colorG = mix(colorG, 1.0f, ambientLightFactor);
				colorB = mix(colorB, 1.0f, ambientLightFactor);
				
				colorR += skyLightColorR * skyBrightness;
				colorG += skyLightColorG * skyBrightness;
				colorB += skyLightColorB * skyBrightness;
				
				colorR = mix(colorR, 0.75f, 0.04f);
				colorG = mix(colorG, 0.75f, 0.04f);
				colorB = mix(colorB, 0.75f, 0.04f);
				
				colorR = Mth.clamp(colorR, 0.0f, 1.0f);
				colorG = Mth.clamp(colorG, 0.0f, 1.0f);
				colorB = Mth.clamp(colorB, 0.0f, 1.0f);
				
				colorR = mix(colorR, notGamma(colorR), brightnessFactor);
				colorG = mix(colorG, notGamma(colorG), brightnessFactor);
				colorB = mix(colorB, notGamma(colorB), brightnessFactor);
				
				colorR = mix(colorR, 0.75f, 0.04f);
				colorG = mix(colorG, 0.75f, 0.04f);
				colorB = mix(colorB, 0.75f, 0.04f);
				
				int rr =
					Mth.clamp((int)(Math.min(colorR, 1.0f) * 255.0f), 0, 255);
				int gg =
					Mth.clamp((int)(Math.min(colorG, 1.0f) * 255.0f), 0, 255);
				int bb =
					Mth.clamp((int)(Math.min(colorB, 1.0f) * 255.0f), 0, 255);
				lightmapColors[blockLight + skyLight * 16] =
					0xFF000000 | (rr << 16) | (gg << 8) | bb;
			}
		}
	}
	
	private static float mix(float a, float b, float w)
	{
		return a * (1.0f - w) + b * w;
	}
	
	private static float getBrightness(float level)
	{
		return level / (4.0f - 3.0f * level);
	}
	
	private static float notGamma(float x)
	{
		float nx = 1.0f - x;
		return 1.0f - nx * nx * nx * nx;
	}
	
	private boolean shouldUseUnderground(ClientLevel level, BlockPos playerPos)
	{
		int clampedY = Math.max(Math.min(playerPos.getY(), level.getMaxY() - 1),
			level.getMinY());
		int x = playerPos.getX();
		int z = playerPos.getZ();
		int motionBlocking = Integer.MIN_VALUE;
		for(int dx = -1; dx <= 1; dx++)
		{
			for(int dz = -1; dz <= 1; dz++)
			{
				int px = x + dx * 4;
				int pz = z + dz * 4;
				if(!level.hasChunk(px >> 4, pz >> 4))
				{
					continue;
				}
				LevelChunk chunk = level.getChunkSource().getChunk(px >> 4,
					pz >> 4, ChunkStatus.EMPTY, false);
				if(chunk != null)
				{
					int sampled = chunk.getHeight(
						Heightmap.Types.MOTION_BLOCKING, px & 15, pz & 15);
					motionBlocking = Math.max(motionBlocking, sampled);
				}
			}
		}
		if(motionBlocking == Integer.MIN_VALUE)
		{
			motionBlocking =
				level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		}
		if(level.dimensionType().hasCeiling())
		{
			boolean playerInOpen = motionBlocking <= clampedY;
			return clampedY < 126 || !playerInOpen;
		}
		if(level.dimensionType()
			.cardinalLightType() == CardinalLighting.Type.NETHER
			&& !level.dimensionType().hasSkyLight())
		{
			boolean playerInOpen = motionBlocking <= clampedY;
			return !playerInOpen;
		}
		int skyLight = level.getBrightness(LightLayer.SKY, playerPos);
		int depthBelowSurface = motionBlocking - clampedY;
		// Switch aggressively once the player is meaningfully below surrounding
		// terrain,
		// even if a vertical shaft still lets skylight reach them.
		boolean definitelyUnderground = depthBelowSurface >= 14;
		boolean likelyUnderground = depthBelowSurface >= 6 && skyLight <= 7;
		return definitelyUnderground || likelyUnderground;
	}
	
	private void ensureCacheContext(Minecraft mc, ClientLevel level)
	{
		String resolvedServerKey = resolveServerKey(mc);
		String serverKey = resolvedServerKey;
		if("unknown_server".equals(serverKey) && !activeServerKey.isEmpty())
		{
			serverKey = activeServerKey;
		}else if(!"unknown_server".equals(serverKey))
		{
			activeServerKey = serverKey;
		}
		String dim = sanitize(level.dimension().identifier().toString());
		String key = serverKey + "|" + dim;
		if(key.equals(activeCacheKey))
		{
			return;
		}
		
		flushCacheNow();
		cachedColumnColor.clear();
		cachedColumnY.clear();
		cachedColumnFlags.clear();
		visibleChunkStates.clear();
		dirtyChunks.clear();
		cachedLightingSignature = Integer.MIN_VALUE;
		cachedColumnSamplingSignature = Integer.MIN_VALUE;
		lastChunkPollTick = Long.MIN_VALUE;
		activeCacheKey = key;
		cachedUndergroundBand = Integer.MIN_VALUE;
		activeCachePath = FabricLoader.getInstance().getConfigDir()
			.resolve("mapa-cache").resolve(serverKey).resolve(dim + ".bin");
		loadCache(activeCachePath);
	}
	
	private static String resolveServerKey(Minecraft mc)
	{
		if(mc.hasSingleplayerServer())
		{
			return "singleplayer";
		}
		if(mc.getCurrentServer() != null && mc.getCurrentServer().ip != null
			&& !mc.getCurrentServer().ip.isBlank())
		{
			return sanitize(mc.getCurrentServer().ip.toLowerCase(Locale.ROOT));
		}
		if(mc.getConnection() != null
			&& mc.getConnection().getConnection() != null)
		{
			SocketAddress address =
				mc.getConnection().getConnection().getRemoteAddress();
			if(address != null)
			{
				String asText = address.toString().toLowerCase(Locale.ROOT);
				if(!asText.isBlank())
				{
					return sanitize(asText);
				}
			}
		}
		return "unknown_server";
	}
	
	private void clearColumnCache()
	{
		cachedColumnColor.clear();
		cachedColumnY.clear();
		cachedColumnFlags.clear();
		dirtyDiskCache = true;
	}
	
	private static String sanitize(String in)
	{
		StringBuilder sb = new StringBuilder(in.length());
		for(int i = 0; i < in.length(); i++)
		{
			char c = in.charAt(i);
			if((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_'
				|| c == '-' || c == '.')
			{
				sb.append(c);
			}else if(c >= 'A' && c <= 'Z')
			{
				sb.append((char)(c + 32));
			}else
			{
				sb.append('_');
			}
		}
		return sb.toString();
	}
	
	private void loadCache(Path path)
	{
		if(path == null || Files.notExists(path))
		{
			return;
		}
		try(DataInputStream in = new DataInputStream(
			new BufferedInputStream(Files.newInputStream(path))))
		{
			int magic = in.readInt();
			int version = in.readInt();
			if(magic != CACHE_MAGIC
				|| (version != 23 && version != CACHE_VERSION))
			{
				return;
			}
			int count = in.readInt();
			int limit = Math.min(count, COLUMN_CACHE_LIMIT);
			for(int i = 0; i < limit; i++)
			{
				long key = in.readLong();
				int color = in.readInt();
				int y = in.readInt();
				int flags = version >= CACHE_VERSION ? in.readInt() : 0;
				cachedColumnColor.putAndMoveToLast(key, color);
				cachedColumnY.putAndMoveToLast(key, y);
				cachedColumnFlags.putAndMoveToLast(key, flags);
			}
			for(int i = limit; i < count; i++)
			{
				in.readLong();
				in.readInt();
				in.readInt();
				if(version >= CACHE_VERSION)
				{
					in.readInt();
				}
			}
		}catch(IOException ignored)
		{}
	}
	
	private void maybeScheduleCacheSave(long tick)
	{
		if(!dirtyDiskCache || activeCachePath == null)
		{
			return;
		}
		if(tick - lastDiskSaveTick < 120)
		{
			return;
		}
		if(!writeInFlight.compareAndSet(false, true))
		{
			return;
		}
		
		CacheSnapshot snapshot = snapshotCache(activeCachePath);
		dirtyDiskCache = false;
		lastDiskSaveTick = tick;
		ioExecutor.submit(() -> {
			try
			{
				writeCache(snapshot);
			}finally
			{
				writeInFlight.set(false);
			}
		});
	}
	
	private void flushCacheNow()
	{
		if(!dirtyDiskCache || activeCachePath == null)
		{
			return;
		}
		writeCache(snapshotCache(activeCachePath));
		dirtyDiskCache = false;
	}
	
	private CacheSnapshot snapshotCache(Path path)
	{
		int size = cachedColumnColor.size();
		long[] keys = new long[size];
		int[] colors = new int[size];
		int[] ys = new int[size];
		int[] flags = new int[size];
		int i = 0;
		for(Long2IntMap.Entry entry : cachedColumnColor.long2IntEntrySet())
		{
			long key = entry.getLongKey();
			keys[i] = key;
			colors[i] = entry.getIntValue();
			ys[i] = cachedColumnY.get(key);
			flags[i] = cachedColumnFlags.get(key);
			i++;
		}
		return new CacheSnapshot(path, keys, colors, ys, flags);
	}
	
	private static void writeCache(CacheSnapshot snapshot)
	{
		Path path = snapshot.path();
		try
		{
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			try(DataOutputStream out = new DataOutputStream(
				new BufferedOutputStream(Files.newOutputStream(tmp))))
			{
				out.writeInt(CACHE_MAGIC);
				out.writeInt(CACHE_VERSION);
				out.writeInt(snapshot.keys().length);
				for(int i = 0; i < snapshot.keys().length; i++)
				{
					out.writeLong(snapshot.keys()[i]);
					out.writeInt(snapshot.colors()[i]);
					out.writeInt(snapshot.ys()[i]);
					out.writeInt(snapshot.flags()[i]);
				}
			}
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
				StandardCopyOption.ATOMIC_MOVE);
		}catch(IOException ignored)
		{}
	}
	
	private static long columnKey(int x, int z, boolean undergroundMode)
	{
		long packed = (((long)x) << 32) ^ (z & 0xFFFFFFFFL);
		return (packed << 1) ^ (undergroundMode ? 1L : 0L);
	}
	
	private static int undergroundCacheBand(int playerY)
	{
		return playerY;
	}
	
	private void queueUnknownChunksForRetry(ClientLevel level,
		int maxQueuedChunks)
	{
		if(cachedUnknown == null || cachedUnknown.length == 0
			|| cachedBackingSamples <= 0 || maxQueuedChunks <= 0)
		{
			return;
		}
		if(dirtyChunks.size() >= maxQueuedChunks)
		{
			return;
		}
		
		int total = cachedUnknown.length;
		int cursor = Math.floorMod(unknownRefreshCursor, total);
		int attempts = 0;
		while(attempts < total && dirtyChunks.size() < maxQueuedChunks)
		{
			int idx = (cursor + attempts) % total;
			attempts++;
			if(!cachedUnknown[idx])
			{
				continue;
			}
			
			int sx = idx / cachedBackingSamples;
			int sz = idx % cachedBackingSamples;
			double worldDx =
				(sx + 0.5 - cachedBackingSamples / 2.0) * cachedWorldPerSample;
			double worldDz =
				(sz + 0.5 - cachedBackingSamples / 2.0) * cachedWorldPerSample;
			int chunkX = Mth.floor(cachedCenterX + worldDx) >> 4;
			int chunkZ = Mth.floor(cachedCenterZ + worldDz) >> 4;
			if(queryChunkState(level, chunkX, chunkZ) != CHUNK_STATE_READY)
			{
				continue;
			}
			
			long packedChunk = packedChunkKey(chunkX, chunkZ);
			if(!dirtyChunksContains(packedChunk))
			{
				dirtyChunks.add(packedChunk);
			}
		}
		unknownRefreshCursor = (cursor + Math.max(1, attempts)) % total;
	}
	
	private void pollVisibleChunks(ClientLevel level, double centerX,
		double centerZ, int backingSamples, double worldPerSample, long tick)
	{
		if(tick == lastChunkPollTick)
		{
			return;
		}
		lastChunkPollTick = tick;
		
		int radiusBlocks = Mth.ceil(backingSamples * worldPerSample * 0.5) + 16;
		int minChunkX = Mth.floor((centerX - radiusBlocks) / 16.0) - 1;
		int maxChunkX = Mth.floor((centerX + radiusBlocks) / 16.0) + 1;
		int minChunkZ = Mth.floor((centerZ - radiusBlocks) / 16.0) - 1;
		int maxChunkZ = Mth.floor((centerZ + radiusBlocks) / 16.0) + 1;
		
		Long2IntOpenHashMap nextStates = new Long2IntOpenHashMap(Math.max(16,
			(maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1)));
		nextStates.defaultReturnValue(CHUNK_STATE_ABSENT);
		
		for(int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++)
		{
			for(int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++)
			{
				long key = packedChunkKey(chunkX, chunkZ);
				int state = queryChunkState(level, chunkX, chunkZ);
				nextStates.put(key, state);
				int previous = visibleChunkStates.get(key);
				if(state == CHUNK_STATE_READY && previous != CHUNK_STATE_READY)
				{
					dirtyChunks.add(key);
				}
			}
		}
		
		visibleChunkStates.clear();
		visibleChunkStates.putAll(nextStates);
	}
	
	private int queryChunkState(ClientLevel level, int chunkX, int chunkZ)
	{
		LevelChunk chunk = getSampledChunk(level, chunkX, chunkZ);
		if(chunk == null)
		{
			return CHUNK_STATE_ABSENT;
		}
		return CHUNK_STATE_READY;
	}
	
	private static boolean isChunkReady(ClientLevel level, int chunkX,
		int chunkZ)
	{
		return getSampledChunk(level, chunkX, chunkZ) != null;
	}
	
	private int currentLightingSignature(Minecraft mc, ClientLevel level,
		BlockPos playerPos, boolean undergroundMode)
	{
		if(basicPaletteMode)
		{
			int signature = level.getSkyDarken();
			signature = 31 * signature + (showSmallPlants ? 1 : 0);
			signature = 31 * signature + (showTreeCanopies ? 1 : 0);
			signature = 31 * signature + 1;
			return signature;
		}
		int gamma = 0;
		try
		{
			gamma = Math.round(mc.options.gamma().get().floatValue() * 1000.0f);
		}catch(Throwable ignored)
		{}
		int signature = level.getSkyDarken();
		signature = 31 * signature + gamma;
		if(undergroundMode)
		{
			signature = 31 * signature
				+ level.getBrightness(LightLayer.BLOCK, playerPos);
			signature =
				31 * signature + level.getBrightness(LightLayer.SKY, playerPos);
		}
		signature = 31 * signature + (surfaceDynamicLighting ? 1 : 0);
		signature = 31 * signature + (undergroundDynamicLighting ? 1 : 0);
		signature = 31 * signature + (showSmallPlants ? 1 : 0);
		signature = 31 * signature + (showTreeCanopies ? 1 : 0);
		signature = 31 * signature + (basicPaletteMode ? 1 : 0);
		signature = 31 * signature + Float.floatToIntBits(textureSharpness);
		signature = 31 * signature + Float.floatToIntBits(surfaceRelief);
		signature = 31 * signature + Float.floatToIntBits(surfaceBrightness);
		signature = 31 * signature + Float.floatToIntBits(surfaceContrast);
		signature = 31 * signature + Float.floatToIntBits(surfaceSaturation);
		signature = 31 * signature + Float.floatToIntBits(surfaceContourLimit);
		signature =
			31 * signature + Float.floatToIntBits(surfaceContourSoftness);
		signature = 31 * signature + Float.floatToIntBits(grassTintStrength);
		signature = 31 * signature + grassTintColor;
		signature = 31 * signature + Float.floatToIntBits(foliageTintStrength);
		signature = 31 * signature + foliageTintColor;
		signature = 31 * signature + Float.floatToIntBits(waterTintStrength);
		signature = 31 * signature + waterTintColor;
		signature = 31 * signature + Float.floatToIntBits(waterDetail);
		signature = 31 * signature + Float.floatToIntBits(waterOpacity);
		return signature;
	}
	
	private int currentColumnSamplingSignature()
	{
		int signature = 1;
		signature = 31 * signature + (showSmallPlants ? 1 : 0);
		signature = 31 * signature + (showTreeCanopies ? 1 : 0);
		signature = 31 * signature + (basicPaletteMode ? 1 : 0);
		signature = 31 * signature + (surfaceDynamicLighting ? 1 : 0);
		signature = 31 * signature + (undergroundDynamicLighting ? 1 : 0);
		signature = 31 * signature + Float.floatToIntBits(grassTintStrength);
		signature = 31 * signature + grassTintColor;
		signature = 31 * signature + Float.floatToIntBits(foliageTintStrength);
		signature = 31 * signature + foliageTintColor;
		signature = 31 * signature + Float.floatToIntBits(waterTintStrength);
		signature = 31 * signature + waterTintColor;
		signature = 31 * signature + Float.floatToIntBits(waterDetail);
		signature = 31 * signature + Float.floatToIntBits(waterOpacity);
		return signature;
	}
	
	private static long packedChunkKey(int chunkX, int chunkZ)
	{
		return (((long)chunkX) << 32) ^ (chunkZ & 0xFFFFFFFFL);
	}
	
	private static LevelChunk getSampledChunk(ClientLevel level, int chunkX,
		int chunkZ)
	{
		if(!level.hasChunk(chunkX, chunkZ))
		{
			return null;
		}
		
		LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ,
			ChunkStatus.FULL, false);
		if(chunk != null && !chunk.isEmpty())
		{
			return chunk;
		}
		
		// Don't fall back to partially-ready/empty client chunks here.
		// Their heightmaps can be incomplete and cause mountains to appear cut
		// open depending on renderer visibility and camera direction.
		return null;
	}
	
	private static int unpackChunkX(long packedChunk)
	{
		return (int)(packedChunk >> 32);
	}
	
	private static int unpackChunkZ(long packedChunk)
	{
		return (int)packedChunk;
	}
	
	private boolean dirtyChunksContains(long packedChunk)
	{
		for(int i = 0; i < dirtyChunks.size(); i++)
		{
			if(dirtyChunks.getLong(i) == packedChunk)
			{
				return true;
			}
		}
		return false;
	}
	
	private static int sampleStartForBlock(int blockMin, double center,
		double worldPerSample, int backingSamples)
	{
		double sample = (blockMin - center) / Math.max(0.0001, worldPerSample)
			+ backingSamples / 2.0 - 0.5;
		return Mth.clamp(Mth.floor(sample) - 2, 0, backingSamples);
	}
	
	private static int sampleEndForBlock(int blockMaxExclusive, double center,
		double worldPerSample, int backingSamples)
	{
		double sample =
			(blockMaxExclusive - center) / Math.max(0.0001, worldPerSample)
				+ backingSamples / 2.0 - 0.5;
		return Mth.clamp(Mth.ceil(sample) + 2, 0, backingSamples);
	}
	
	private void putColumnCache(long key, int color, int y, int flags)
	{
		boolean changed = true;
		if(cachedColumnColor.containsKey(key))
		{
			changed = cachedColumnColor.get(key) != color
				|| cachedColumnY.get(key) != y
				|| cachedColumnFlags.get(key) != flags;
		}
		cachedColumnColor.putAndMoveToLast(key, color);
		cachedColumnY.putAndMoveToLast(key, y);
		cachedColumnFlags.putAndMoveToLast(key, flags);
		if(changed)
		{
			dirtyDiskCache = true;
		}
		if(cachedColumnColor.size() > COLUMN_CACHE_LIMIT)
		{
			cachedColumnColor.removeFirstInt();
			cachedColumnY.removeFirstInt();
			cachedColumnFlags.removeFirstInt();
		}
	}
	
	private static int wholeSampleShift(double sampleDelta)
	{
		if(sampleDelta >= 0.0)
		{
			return (int)Math.floor(sampleDelta);
		}
		return (int)Math.ceil(sampleDelta);
	}
	
	private static int shade(int argb, int neighborHeight, int currentHeight,
		int playerY, boolean undergroundMode)
	{
		int slopeDiff = neighborHeight - currentHeight;
		double sc = 0.0;
		if(slopeDiff != 0)
		{
			sc = slopeDiff > 0 ? 0.125 : -0.125;
		}
		if(undergroundMode)
		{
			int playerDiff = currentHeight - playerY;
			double heightsc =
				Math.log10(Math.abs(playerDiff) / 8.0 + 1.0) / 3.0;
			sc = playerDiff > 0 ? sc + heightsc : sc - heightsc;
		}
		sc = Mth.clamp((float)sc, undergroundMode ? -0.35f : -0.22f,
			undergroundMode ? 0.35f : 0.22f);
		return applyShade(argb, sc);
	}
	
	private int shadeSurface(int argb, int[] heights, int[] flags, int samples,
		int sx, int sz)
	{
		int idx = sx * samples + sz;
		if((flags[idx] & SAMPLE_FLAG_WATER) != 0)
		{
			return shadeWaterSurface(argb, heights, flags, samples, sx, sz);
		}
		return shadeLandSurface(argb, heights, flags, samples, sx, sz);
	}
	
	private int shadeLandSurface(int argb, int[] heights, int[] flags,
		int samples, int sx, int sz)
	{
		int currentHeight = heights[sx * samples + sz];
		int compHeight =
			comparableHeight(heights, flags, samples, sx, sz, false);
		int diff = compHeight - currentHeight;
		double sc = 0.0;
		if(diff != 0)
		{
			sc = diff > 0 ? 0.085 : -0.16;
		}
		
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		float brightness = (r * 0.299f + g * 0.587f + b * 0.114f) / 255.0f;
		float desaturation = 1.0f - ((max - min) / 255.0f);
		float reliefBoost = 1.0f + Math.max(0.0f, brightness - 0.46f) * 0.18f
			+ desaturation * 0.08f;
		sc *= Math.min(reliefBoost, 1.14f);
		sc *= surfaceRelief;
		sc = Mth.clamp((float)sc, -surfaceContourLimit * 1.15f,
			surfaceContourLimit * 0.50f);
		return applyLandShade(argb, sc);
	}
	
	private int shadeWaterSurface(int argb, int[] heights, int[] flags,
		int samples, int sx, int sz)
	{
		int currentHeight = heights[sx * samples + sz];
		int compHeight = comparableWaterHeight(heights, flags, samples, sx, sz);
		int diff = compHeight - currentHeight;
		double sc = 0.0;
		if(diff != 0)
		{
			sc = Math.log10(Math.abs(diff) / 8.0 + 1.0) / 3.8;
			if(diff < 0)
			{
				sc = -sc;
			}
		}
		sc *= Math.min(surfaceRelief, 1.15f) * 0.24f;
		sc = Mth.clamp((float)sc, -surfaceContourLimit * 0.16f,
			surfaceContourLimit * 0.16f);
		int shaded = applyShade(argb, sc);
		int blended = blendRgb(shaded & 0x00FFFFFF, argb & 0x00FFFFFF, 0.45f);
		return (argb & 0xFF000000) | blended;
	}
	
	private static int comparableHeight(int[] heights, int[] flags, int samples,
		int sx, int sz, boolean water)
	{
		int current = heights[sx * samples + sz];
		int nx = sx - 1;
		int nz = sz + 1;
		if(nx < 0 || nz >= samples)
		{
			return current;
		}
		int idx = nx * samples + nz;
		boolean sameWaterClass =
			((flags[idx] & SAMPLE_FLAG_WATER) != 0) == water;
		return sameWaterClass ? heights[idx] : current;
	}
	
	private static int comparableWaterHeight(int[] heights, int[] flags,
		int samples, int sx, int sz)
	{
		int current = heights[sx * samples + sz];
		int sum = current * 4;
		int weight = 4;
		for(int dx = -1; dx <= 1; dx++)
		{
			for(int dz = -1; dz <= 1; dz++)
			{
				if(dx == 0 && dz == 0)
				{
					continue;
				}
				int nx = sx + dx;
				int nz = sz + dz;
				if(nx < 0 || nz < 0 || nx >= samples || nz >= samples)
				{
					continue;
				}
				int idx = nx * samples + nz;
				if((flags[idx] & SAMPLE_FLAG_WATER) == 0)
				{
					continue;
				}
				int neighborWeight = (dx == 0 || dz == 0) ? 2 : 1;
				sum += heights[idx] * neighborWeight;
				weight += neighborWeight;
			}
		}
		return weight > 0 ? sum / weight : current;
	}
	
	private static int applyLandShade(int argb, double sc)
	{
		int a = (argb >>> 24) & 0xFF;
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		if(sc > 0.0)
		{
			double light = sc * 0.55;
			r += (int)(light * (255 - r));
			g += (int)(light * (255 - g));
			b += (int)(light * (255 - b));
		}else if(sc < 0.0)
		{
			double dark = -sc * 1.12;
			r -= (int)(dark * r);
			g -= (int)(dark * g);
			b -= (int)(dark * b);
		}
		
		r = Mth.clamp(r, 0, 255);
		g = Mth.clamp(g, 0, 255);
		b = Mth.clamp(b, 0, 255);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
	
	private static int applyShade(int argb, double sc)
	{
		int a = (argb >>> 24) & 0xFF;
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		if(sc > 0.0)
		{
			r += (int)(sc * (255 - r));
			g += (int)(sc * (255 - g));
			b += (int)(sc * (255 - b));
		}else if(sc < 0.0)
		{
			double dark = -sc;
			r -= (int)(dark * r);
			g -= (int)(dark * g);
			b -= (int)(dark * b);
		}
		
		r = Mth.clamp(r, 0, 255);
		g = Mth.clamp(g, 0, 255);
		b = Mth.clamp(b, 0, 255);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
	
	private static int multiplyRgb(int baseRgb, int tintRgb)
	{
		int br = (baseRgb >> 16) & 0xFF;
		int bg = (baseRgb >> 8) & 0xFF;
		int bb = baseRgb & 0xFF;
		int tr = (tintRgb >> 16) & 0xFF;
		int tg = (tintRgb >> 8) & 0xFF;
		int tb = tintRgb & 0xFF;
		int r = (br * tr) / 255;
		int g = (bg * tg) / 255;
		int b = (bb * tb) / 255;
		return (r << 16) | (g << 8) | b;
	}
	
	private static int applyTintStrength(int neutralRgb, int tintedRgb,
		int tintRgb, float strength)
	{
		float clamped = Mth.clamp(strength, 0.0f, 2.0f);
		if(clamped <= 1.0f)
		{
			return blendRgb(neutralRgb, tintedRgb, clamped);
		}
		int boosted = multiplyRgb(tintedRgb, tintRgb);
		return blendRgb(tintedRgb, boosted, clamped - 1.0f);
	}
	
	private static int applyOptionalColorOverride(int rgb, int overrideColor,
		float strength)
	{
		if(overrideColor < 0)
		{
			return rgb;
		}
		int override = overrideColor & 0x00FFFFFF;
		int target = tintRgbPreservingLuma(rgb, override);
		int shadedTarget = blendRgb(target, multiplyRgb(rgb, override), 0.38f);
		float clamped = Mth.clamp(strength, 0.0f, 2.0f);
		if(clamped <= 1.0f)
		{
			return blendRgb(rgb, shadedTarget, clamped * 0.5f);
		}
		int boosted = tintRgbPreservingLuma(shadedTarget,
			blendRgb(override, 0xFFFFFF, 0.12f));
		return blendRgb(shadedTarget, boosted, clamped - 1.0f);
	}
	
	private static int tintRgbPreservingLuma(int rgb, int tintRgb)
	{
		int baseLuma = perceivedLuma(rgb);
		int tintLuma = Math.max(1, perceivedLuma(tintRgb));
		float scale = baseLuma / (float)tintLuma;
		int tr =
			Mth.clamp(Math.round(((tintRgb >> 16) & 0xFF) * scale), 0, 255);
		int tg = Mth.clamp(Math.round(((tintRgb >> 8) & 0xFF) * scale), 0, 255);
		int tb = Mth.clamp(Math.round((tintRgb & 0xFF) * scale), 0, 255);
		return (tr << 16) | (tg << 8) | tb;
	}
	
	private static int perceivedLuma(int rgb)
	{
		int r = (rgb >> 16) & 0xFF;
		int g = (rgb >> 8) & 0xFF;
		int b = rgb & 0xFF;
		return (r * 77 + g * 150 + b * 29) >> 8;
	}
	
	private static int blendRgb(int baseRgb, int overlayRgb,
		float overlayWeight)
	{
		float t = Mth.clamp(overlayWeight, 0.0f, 1.0f);
		int br = (baseRgb >> 16) & 0xFF;
		int bg = (baseRgb >> 8) & 0xFF;
		int bb = baseRgb & 0xFF;
		int or = (overlayRgb >> 16) & 0xFF;
		int og = (overlayRgb >> 8) & 0xFF;
		int ob = overlayRgb & 0xFF;
		int r = Mth.clamp((int)(br * (1.0f - t) + or * t), 0, 255);
		int g = Mth.clamp((int)(bg * (1.0f - t) + og * t), 0, 255);
		int b = Mth.clamp((int)(bb * (1.0f - t) + ob * t), 0, 255);
		return (r << 16) | (g << 8) | b;
	}
	
	private static int colorMultiplier(int color1, int color2)
	{
		int alpha1 = (color1 >>> 24) & 0xFF;
		int red1 = (color1 >> 16) & 0xFF;
		int green1 = (color1 >> 8) & 0xFF;
		int blue1 = color1 & 0xFF;
		int alpha2 = (color2 >>> 24) & 0xFF;
		int red2 = (color2 >> 16) & 0xFF;
		int green2 = (color2 >> 8) & 0xFF;
		int blue2 = color2 & 0xFF;
		int alpha = alpha1 * alpha2 / 255;
		int red = red1 * red2 / 255;
		int green = green1 * green2 / 255;
		int blue = blue1 * blue2 / 255;
		return ((alpha & 0xFF) << 24) | ((red & 0xFF) << 16)
			| ((green & 0xFF) << 8) | (blue & 0xFF);
	}
	
	private static int colorAdder(int color1, int color2)
	{
		float topAlpha = ((color1 >>> 24) & 0xFF) / 255.0F;
		float red1 = ((color1 >> 16) & 0xFF) * topAlpha;
		float green1 = ((color1 >> 8) & 0xFF) * topAlpha;
		float blue1 = (color1 & 0xFF) * topAlpha;
		float bottomAlpha = ((color2 >>> 24) & 0xFF) / 255.0F;
		float red2 = ((color2 >> 16) & 0xFF) * bottomAlpha * (1.0F - topAlpha);
		float green2 = ((color2 >> 8) & 0xFF) * bottomAlpha * (1.0F - topAlpha);
		float blue2 = (color2 & 0xFF) * bottomAlpha * (1.0F - topAlpha);
		float alpha = topAlpha + bottomAlpha * (1.0F - topAlpha);
		if(alpha <= 0.0001F)
		{
			return 0;
		}
		float red = (red1 + red2) / alpha;
		float green = (green1 + green2) / alpha;
		float blue = (blue1 + blue2) / alpha;
		return ((int)(alpha * 255.0F) & 0xFF) << 24 | ((int)red & 0xFF) << 16
			| ((int)green & 0xFF) << 8 | ((int)blue & 0xFF);
	}
	
	private int styleColor(int argb)
	{
		if(basicPaletteMode)
		{
			return argb;
		}
		int a = (argb >>> 24) & 0xFF;
		float r = ((argb >> 16) & 0xFF) / 255.0f;
		float g = ((argb >> 8) & 0xFF) / 255.0f;
		float b = (argb & 0xFF) / 255.0f;
		
		float brightness = STYLE_BRIGHTNESS * surfaceBrightness;
		float saturation = STYLE_SATURATION * surfaceSaturation;
		float contrast = STYLE_CONTRAST * surfaceContrast;
		
		r *= brightness;
		g *= brightness;
		b *= brightness;
		
		float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
		r = luma + (r - luma) * saturation;
		g = luma + (g - luma) * saturation;
		b = luma + (b - luma) * saturation;
		
		r = (r - 0.5f) * contrast + 0.5f;
		g = (g - 0.5f) * contrast + 0.5f;
		b = (b - 0.5f) * contrast + 0.5f;
		
		int rr = Mth.clamp((int)(r * 255.0f), 0, 255);
		int gg = Mth.clamp((int)(g * 255.0f), 0, 255);
		int bb = Mth.clamp((int)(b * 255.0f), 0, 255);
		return (a << 24) | (rr << 16) | (gg << 8) | bb;
	}
	
	private void applySurfaceSharpen(int[] pixels, int[] flags, int samples)
	{
		float amount = textureSharpness - 1.0f;
		if(Math.abs(amount) < 0.01f)
		{
			return;
		}
		int[] src = pixels.clone();
		for(int x = 1; x < samples - 1; x++)
		{
			int row = x * samples;
			for(int y = 1; y < samples - 1; y++)
			{
				int idx = row + y;
				if((flags[idx] & SAMPLE_FLAG_WATER) != 0)
				{
					continue;
				}
				int c = src[idx];
				int n1 = src[(x - 1) * samples + y];
				int n2 = src[(x + 1) * samples + y];
				int n3 = src[x * samples + (y - 1)];
				int n4 = src[x * samples + (y + 1)];
				
				int avgR = (((n1 >> 16) & 0xFF) + ((n2 >> 16) & 0xFF)
					+ ((n3 >> 16) & 0xFF) + ((n4 >> 16) & 0xFF)) >> 2;
				int avgG = (((n1 >> 8) & 0xFF) + ((n2 >> 8) & 0xFF)
					+ ((n3 >> 8) & 0xFF) + ((n4 >> 8) & 0xFF)) >> 2;
				int avgB = ((n1 & 0xFF) + (n2 & 0xFF) + (n3 & 0xFF)
					+ (n4 & 0xFF)) >> 2;
				
				int r = (c >> 16) & 0xFF;
				int g = (c >> 8) & 0xFF;
				int b = c & 0xFF;
				
				int outR =
					Mth.clamp((int)(r + (r - avgR) * amount * 0.85f), 0, 255);
				int outG =
					Mth.clamp((int)(g + (g - avgG) * amount * 0.85f), 0, 255);
				int outB =
					Mth.clamp((int)(b + (b - avgB) * amount * 0.85f), 0, 255);
				pixels[idx] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
			}
		}
	}
	
	private void applySurfaceSharpenRegion(int[] pixels, int[] flags,
		int samples, int minX, int minZ, int maxX, int maxZ)
	{
		float amount = textureSharpness - 1.0f;
		if(Math.abs(amount) < 0.01f)
		{
			return;
		}
		int startX = Math.max(1, minX);
		int endX = Math.min(samples - 2, maxX);
		int startZ = Math.max(1, minZ);
		int endZ = Math.min(samples - 2, maxZ);
		if(startX > endX || startZ > endZ)
		{
			return;
		}
		int copyMinX = Math.max(0, startX - 1);
		int copyMaxX = Math.min(samples - 1, endX + 1);
		int copyMinZ = Math.max(0, startZ - 1);
		int copyMaxZ = Math.min(samples - 1, endZ + 1);
		int copyHeight = copyMaxZ - copyMinZ + 1;
		int[] src = captureRegionPixels(pixels, samples, copyMinX, copyMinZ,
			copyMaxX, copyMaxZ);
		for(int x = startX; x <= endX; x++)
		{
			int row = x * samples;
			int localX = x - copyMinX;
			int localRow = localX * copyHeight;
			for(int y = startZ; y <= endZ; y++)
			{
				int idx = row + y;
				if((flags[idx] & SAMPLE_FLAG_WATER) != 0)
				{
					continue;
				}
				int localY = y - copyMinZ;
				int c = src[localRow + localY];
				int n1 = src[(localX - 1) * copyHeight + localY];
				int n2 = src[(localX + 1) * copyHeight + localY];
				int n3 = src[localRow + (localY - 1)];
				int n4 = src[localRow + (localY + 1)];
				
				int avgR = (((n1 >> 16) & 0xFF) + ((n2 >> 16) & 0xFF)
					+ ((n3 >> 16) & 0xFF) + ((n4 >> 16) & 0xFF)) >> 2;
				int avgG = (((n1 >> 8) & 0xFF) + ((n2 >> 8) & 0xFF)
					+ ((n3 >> 8) & 0xFF) + ((n4 >> 8) & 0xFF)) >> 2;
				int avgB = ((n1 & 0xFF) + (n2 & 0xFF) + (n3 & 0xFF)
					+ (n4 & 0xFF)) >> 2;
				
				int r = (c >> 16) & 0xFF;
				int g = (c >> 8) & 0xFF;
				int b = c & 0xFF;
				
				int outR =
					Mth.clamp((int)(r + (r - avgR) * amount * 0.85f), 0, 255);
				int outG =
					Mth.clamp((int)(g + (g - avgG) * amount * 0.85f), 0, 255);
				int outB =
					Mth.clamp((int)(b + (b - avgB) * amount * 0.85f), 0, 255);
				pixels[idx] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
			}
		}
	}
	
	private void smoothWaterSurface(int[] pixels, int[] flags,
		boolean[] unknown, int samples)
	{
		int[] src = pixels.clone();
		for(int x = 1; x < samples - 1; x++)
		{
			int row = x * samples;
			for(int y = 1; y < samples - 1; y++)
			{
				int idx = row + y;
				if((flags[idx] & SAMPLE_FLAG_WATER) == 0 || unknown[idx])
				{
					continue;
				}
				pixels[idx] =
					smoothWaterPixel(src, flags, unknown, samples, x, y, idx);
			}
		}
	}
	
	private void smoothWaterSurfaceRegion(int[] pixels, int[] flags,
		boolean[] unknown, int samples, int minX, int minZ, int maxX, int maxZ)
	{
		int startX = Math.max(1, minX);
		int endX = Math.min(samples - 2, maxX);
		int startZ = Math.max(1, minZ);
		int endZ = Math.min(samples - 2, maxZ);
		if(startX > endX || startZ > endZ)
		{
			return;
		}
		int copyMinX = Math.max(0, startX - 1);
		int copyMaxX = Math.min(samples - 1, endX + 1);
		int copyMinZ = Math.max(0, startZ - 1);
		int copyMaxZ = Math.min(samples - 1, endZ + 1);
		int copyHeight = copyMaxZ - copyMinZ + 1;
		int[] src = captureRegionPixels(pixels, samples, copyMinX, copyMinZ,
			copyMaxX, copyMaxZ);
		for(int x = startX; x <= endX; x++)
		{
			int row = x * samples;
			int localX = x - copyMinX;
			int localRow = localX * copyHeight;
			for(int y = startZ; y <= endZ; y++)
			{
				int idx = row + y;
				if((flags[idx] & SAMPLE_FLAG_WATER) == 0 || unknown[idx])
				{
					continue;
				}
				int localY = y - copyMinZ;
				pixels[idx] = smoothWaterPixel(src, flags, unknown, samples, x,
					y, idx, copyHeight, copyMinX, copyMinZ, localRow, localY);
			}
		}
	}
	
	private static int smoothWaterPixel(int[] src, int[] flags,
		boolean[] unknown, int samples, int x, int y, int idx)
	{
		return smoothWaterPixel(src, flags, unknown, samples, x, y, idx,
			samples, 0, 0, x * samples, y);
	}
	
	private static int smoothWaterPixel(int[] src, int[] flags,
		boolean[] unknown, int samples, int x, int y, int idx, int srcStride,
		int srcMinX, int srcMinZ, int srcRow, int srcY)
	{
		int current = src[srcRow + srcY];
		int sumR = ((current >> 16) & 0xFF) * 4;
		int sumG = ((current >> 8) & 0xFF) * 4;
		int sumB = (current & 0xFF) * 4;
		int weight = 4;
		
		for(int dx = -1; dx <= 1; dx++)
		{
			for(int dz = -1; dz <= 1; dz++)
			{
				if(dx == 0 && dz == 0)
				{
					continue;
				}
				int nx = x + dx;
				int nz = y + dz;
				int neighborIdx = nx * samples + nz;
				if((flags[neighborIdx] & SAMPLE_FLAG_WATER) == 0
					|| unknown[neighborIdx])
				{
					continue;
				}
				int localX = nx - srcMinX;
				int localZ = nz - srcMinZ;
				int neighbor = src[localX * srcStride + localZ];
				int neighborWeight = (dx == 0 || dz == 0) ? 2 : 1;
				sumR += ((neighbor >> 16) & 0xFF) * neighborWeight;
				sumG += ((neighbor >> 8) & 0xFF) * neighborWeight;
				sumB += (neighbor & 0xFF) * neighborWeight;
				weight += neighborWeight;
			}
		}
		
		if(weight <= 4)
		{
			return current;
		}
		
		int avgR = sumR / weight;
		int avgG = sumG / weight;
		int avgB = sumB / weight;
		int smoothed = 0xFF000000 | (avgR << 16) | (avgG << 8) | avgB;
		return 0xFF000000
			| blendRgb(current & 0x00FFFFFF, smoothed & 0x00FFFFFF, 0.58f);
	}
	
	private void applyContourSoftness(int[] pixels, int[] flags,
		boolean[] unknown, int samples)
	{
		float amount = Mth.clamp(surfaceContourSoftness, 0.0f, 1.0f);
		if(amount < 0.01f)
		{
			return;
		}
		int[] src = pixels.clone();
		for(int x = 1; x < samples - 1; x++)
		{
			int row = x * samples;
			for(int y = 1; y < samples - 1; y++)
			{
				int idx = row + y;
				if(unknown[idx] || (flags[idx] & SAMPLE_FLAG_WATER) != 0)
				{
					continue;
				}
				
				int c = src[idx];
				int n1 = src[(x - 1) * samples + y];
				int n2 = src[(x + 1) * samples + y];
				int n3 = src[x * samples + (y - 1)];
				int n4 = src[x * samples + (y + 1)];
				
				int avgR = (((n1 >> 16) & 0xFF) + ((n2 >> 16) & 0xFF)
					+ ((n3 >> 16) & 0xFF) + ((n4 >> 16) & 0xFF)) >> 2;
				int avgG = (((n1 >> 8) & 0xFF) + ((n2 >> 8) & 0xFF)
					+ ((n3 >> 8) & 0xFF) + ((n4 >> 8) & 0xFF)) >> 2;
				int avgB = ((n1 & 0xFF) + (n2 & 0xFF) + (n3 & 0xFF)
					+ (n4 & 0xFF)) >> 2;
				
				int r = (c >> 16) & 0xFF;
				int g = (c >> 8) & 0xFF;
				int b = c & 0xFF;
				
				int outR = Mth.clamp((int)(r * (1.0f - amount) + avgR * amount),
					0, 255);
				int outG = Mth.clamp((int)(g * (1.0f - amount) + avgG * amount),
					0, 255);
				int outB = Mth.clamp((int)(b * (1.0f - amount) + avgB * amount),
					0, 255);
				pixels[idx] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
			}
		}
	}
	
	private void applyContourSoftnessRegion(int[] pixels, int[] flags,
		boolean[] unknown, int samples, int minX, int minZ, int maxX, int maxZ)
	{
		float amount = Mth.clamp(surfaceContourSoftness, 0.0f, 1.0f);
		if(amount < 0.01f)
		{
			return;
		}
		int startX = Math.max(1, minX);
		int endX = Math.min(samples - 2, maxX);
		int startZ = Math.max(1, minZ);
		int endZ = Math.min(samples - 2, maxZ);
		if(startX > endX || startZ > endZ)
		{
			return;
		}
		int copyMinX = Math.max(0, startX - 1);
		int copyMaxX = Math.min(samples - 1, endX + 1);
		int copyMinZ = Math.max(0, startZ - 1);
		int copyMaxZ = Math.min(samples - 1, endZ + 1);
		int copyHeight = copyMaxZ - copyMinZ + 1;
		int[] src = captureRegionPixels(pixels, samples, copyMinX, copyMinZ,
			copyMaxX, copyMaxZ);
		for(int x = startX; x <= endX; x++)
		{
			int row = x * samples;
			int localX = x - copyMinX;
			int localRow = localX * copyHeight;
			for(int y = startZ; y <= endZ; y++)
			{
				int idx = row + y;
				if(unknown[idx] || (flags[idx] & SAMPLE_FLAG_WATER) != 0)
				{
					continue;
				}
				
				int localY = y - copyMinZ;
				int c = src[localRow + localY];
				int n1 = src[(localX - 1) * copyHeight + localY];
				int n2 = src[(localX + 1) * copyHeight + localY];
				int n3 = src[localRow + (localY - 1)];
				int n4 = src[localRow + (localY + 1)];
				
				int avgR = (((n1 >> 16) & 0xFF) + ((n2 >> 16) & 0xFF)
					+ ((n3 >> 16) & 0xFF) + ((n4 >> 16) & 0xFF)) >> 2;
				int avgG = (((n1 >> 8) & 0xFF) + ((n2 >> 8) & 0xFF)
					+ ((n3 >> 8) & 0xFF) + ((n4 >> 8) & 0xFF)) >> 2;
				int avgB = ((n1 & 0xFF) + (n2 & 0xFF) + (n3 & 0xFF)
					+ (n4 & 0xFF)) >> 2;
				
				int r = (c >> 16) & 0xFF;
				int g = (c >> 8) & 0xFF;
				int b = c & 0xFF;
				
				int outR = Mth.clamp((int)(r * (1.0f - amount) + avgR * amount),
					0, 255);
				int outG = Mth.clamp((int)(g * (1.0f - amount) + avgG * amount),
					0, 255);
				int outB = Mth.clamp((int)(b * (1.0f - amount) + avgB * amount),
					0, 255);
				pixels[idx] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
			}
		}
	}
	
	private int[] captureRegionPixels(int[] pixels, int samples, int minX,
		int minZ, int maxX, int maxZ)
	{
		int width = maxX - minX + 1;
		int height = maxZ - minZ + 1;
		int required = width * height;
		if(regionScratchPixels.length < required)
		{
			regionScratchPixels = new int[required];
		}
		for(int x = 0; x < width; x++)
		{
			System.arraycopy(pixels, (minX + x) * samples + minZ,
				regionScratchPixels, x * height, height);
		}
		return regionScratchPixels;
	}
	
	private static void softenJaggedEdges(int[] pixels, int samples,
		boolean undergroundMode)
	{
		int[] src = pixels.clone();
		int diffThreshold = undergroundMode ? 150 : 170;
		int blendNumerator = undergroundMode ? 2 : 1; // denominator 8
		for(int x = 1; x < samples - 1; x++)
		{
			int row = x * samples;
			for(int y = 1; y < samples - 1; y++)
			{
				int idx = row + y;
				int c = src[idx];
				int r = (c >> 16) & 0xFF;
				int g = (c >> 8) & 0xFF;
				int b = c & 0xFF;
				
				int n1 = src[(x - 1) * samples + y];
				int n2 = src[(x + 1) * samples + y];
				int n3 = src[x * samples + (y - 1)];
				int n4 = src[x * samples + (y + 1)];
				int avgR = (((n1 >> 16) & 0xFF) + ((n2 >> 16) & 0xFF)
					+ ((n3 >> 16) & 0xFF) + ((n4 >> 16) & 0xFF)) >> 2;
				int avgG = (((n1 >> 8) & 0xFF) + ((n2 >> 8) & 0xFF)
					+ ((n3 >> 8) & 0xFF) + ((n4 >> 8) & 0xFF)) >> 2;
				int avgB = ((n1 & 0xFF) + (n2 & 0xFF) + (n3 & 0xFF)
					+ (n4 & 0xFF)) >> 2;
				
				int diff = Math.abs(r - avgR) + Math.abs(g - avgG)
					+ Math.abs(b - avgB);
				if(diff < diffThreshold)
				{
					continue;
				}
				
				int outR =
					(r * (8 - blendNumerator) + avgR * blendNumerator) >> 3;
				int outG =
					(g * (8 - blendNumerator) + avgG * blendNumerator) >> 3;
				int outB =
					(b * (8 - blendNumerator) + avgB * blendNumerator) >> 3;
				pixels[idx] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
			}
		}
	}
	
	private static long packSample(Sample sample)
	{
		long color = sample.argb() & 0xFFFFFFFFL;
		long y = (sample.y() + 32768L) & 0xFFFFL;
		long known = sample.known() ? 1L : 0L;
		long flags = sample.flags() & 0xFFL;
		return color | (y << 32) | (known << 48) | (flags << 49);
	}
	
	private static int computeBackingSamples(int visibleSamples)
	{
		int rotationOverscan = (int)Math
			.ceil((rotatedSourceSamples(visibleSamples) - visibleSamples) * 0.5)
			+ 3;
		int totalOverscan = SAMPLE_OVERSCAN + rotationOverscan;
		return Math.min(MAX_BACKING_SAMPLES,
			visibleSamples + totalOverscan * 2);
	}
	
	private static int rotatedSourceSamples(int visibleSamples)
	{
		return Math.min(MAX_BACKING_SAMPLES,
			(int)Math.ceil(visibleSamples * ROTATION_SCALE));
	}
	
	private static double rotationAngleRad(boolean rotateWithPlayer,
		float yawDeg, boolean invertRotation)
	{
		if(!rotateWithPlayer)
		{
			return 0.0;
		}
		double yaw = Math.toRadians(yawDeg + 180.0f);
		return invertRotation ? -yaw : yaw;
	}
	
	private double cachedRotationAngleRad()
	{
		return rotationAngleRad(cachedRotateWithPlayer, cachedYawDeg,
			cachedInvertRotation);
	}
	
	private static int unpackColor(long packed)
	{
		return (int)(packed & 0xFFFFFFFFL);
	}
	
	private static int unpackY(long packed)
	{
		return (int)(((packed >>> 32) & 0xFFFFL) - 32768L);
	}
	
	private static boolean unpackKnown(long packed)
	{
		return ((packed >>> 48) & 1L) != 0L;
	}
	
	private static int unpackFlags(long packed)
	{
		return (int)((packed >>> 49) & 0xFFL);
	}
	
	private record Sample(int argb, int y, boolean known, int flags)
	{}
	
	private record CacheSnapshot(Path path, long[] keys, int[] colors, int[] ys,
		int[] flags)
	{}
	
	public static record WorldMapSnapshot(double playerX, double playerZ,
		String cacheKey, long[] keys, int[] colors)
	{}
}
