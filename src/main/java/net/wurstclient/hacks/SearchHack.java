/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Arrays;
import java.util.PriorityQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstRenderLayers;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.BlockSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.BlockVertexCompiler;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.EasyVertexBuffer;
import net.wurstclient.util.RegionPos;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.ShaderUtils;
import net.wurstclient.util.chunk.ChunkUtils;
import net.wurstclient.util.chunk.ChunkSearcher;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

@SearchTags({"BlockESP", "block esp"})
public final class SearchHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener, PacketInputListener
{
	private enum SearchMode
	{
		LIST,
		BLOCK_ID,
		QUERY
	}
	
	private static final int MAX_QUERY_LENGTH = 256;
	private static final long BLOCK_UPDATE_GRACE_MS = 750L;
	private static final long CHUNK_SCAN_EXPIRY_MS = 30000L;
	private static final long REVEAL_SAMPLE_EXPIRY_MS = 120000L;
	private static final long BURST_WINDOW_MS = 2000L;
	private static final long BURST_QUIET_MS = 10000L;
	private static final int BURST_THRESHOLD = 40;
	private static final int REVEAL_MIN_SAMPLES = 12;
	private final net.wurstclient.settings.EnumSetting<SearchMode> mode =
		new net.wurstclient.settings.EnumSetting<>("Mode", SearchMode.values(),
			SearchMode.BLOCK_ID);
	private final net.wurstclient.settings.BlockListSetting blockList =
		new net.wurstclient.settings.BlockListSetting("Block List",
			"Blocks to search when Mode is set to List.");
	private final TextFieldSetting query = new TextFieldSetting("Query",
		"Enter text to match block IDs or names by keyword. Separate multiple terms with commas.",
		"", value -> value.length() <= MAX_QUERY_LENGTH);
	private final BlockSetting block = new BlockSetting("Block",
		"The type of block to search for.", "minecraft:diamond_ore", false);
	// New: style setting for boxes/lines like ChestESP
	private final net.wurstclient.settings.EspStyleSetting style =
		new net.wurstclient.settings.EspStyleSetting();
	private final CheckboxSetting highlightCorners = new CheckboxSetting(
		"Highlight corners",
		"Partial ESP for blocks, will cause lag if there are too many!", false);
	private final CheckboxSetting highlightFill = new CheckboxSetting(
		"Fill blocks (outline + fill)",
		"Adds filled boxes for matched blocks. Will cause lag if there are too many!",
		false);
	private final SliderSetting highlightAlpha =
		new SliderSetting("Highlight transparency", 80, 1, 100, 1,
			ValueDisplay.INTEGER.withSuffix("%"));
	private final CheckboxSetting tracerFlash = new CheckboxSetting(
		"Tracer flash", "Make tracers pulse with a smooth fade.", false);
	private final net.wurstclient.settings.CheckboxSetting stickyArea =
		new net.wurstclient.settings.CheckboxSetting("Sticky area",
			"Off: Re-centers the scan every chunk to match ESP drop-off.\n"
				+ "On: Keeps results anchored so you can path back to them.",
			false);
	private final net.wurstclient.settings.CheckboxSetting useFixedColor =
		new net.wurstclient.settings.CheckboxSetting("Use fixed color",
			"Enable to use a fixed color instead of rainbow.", false);
	private final net.wurstclient.settings.ColorSetting fixedColor =
		new net.wurstclient.settings.ColorSetting("Fixed color",
			"Color used when \"Use fixed color\" is enabled.", Color.RED);
	// New: optionally show detected count in HackList
	private final net.wurstclient.settings.CheckboxSetting showCountInHackList =
		new net.wurstclient.settings.CheckboxSetting("HackList count",
			"Appends the number of found blocks to this hack's entry in the HackList.",
			false);
	private final CheckboxSetting antiEspDetection = new CheckboxSetting(
		"Anti-ESP detection",
		"Detects suspicious packet patterns for the currently searched blocks (missing block entities, delayed reveals, fake replacements, and packet bursts).",
		true);
	private final CheckboxSetting antiEspAlerts = new CheckboxSetting(
		"Anti-ESP alerts",
		"Sends chat warnings when Search detects suspicious anti-ESP behavior.",
		true);
	
	// Above-ground filter
	private final net.wurstclient.settings.CheckboxSetting onlyAboveGround =
		new net.wurstclient.settings.CheckboxSetting("Above ground only",
			"Only show blocks at or above the configured Y level.", false);
	private final SliderSetting aboveGroundY = new SliderSetting(
		"Set ESP Y limit", 62, -65, 255, 1, SliderSetting.ValueDisplay.INTEGER);
	private Block lastBlock;
	private String lastQuery = "";
	private ChunkAreaSetting.ChunkArea lastAreaSelection;
	
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in.\n"
			+ "Higher values require a faster computer.",
		ChunkAreaSetting.ChunkArea.A33);
	
	private final SliderSetting limit = new SliderSetting("Limit",
		"The maximum number of blocks to display.\n"
			+ "Higher values require a faster computer.",
		4, 2, 6, 1, ValueDisplay.LOGARITHMIC);
	private int prevLimit;
	private boolean notify;
	
	private final ChunkSearcherCoordinator coordinator =
		new ChunkSearcherCoordinator(area);
	
	private ForkJoinPool forkJoinPool;
	private ForkJoinTask<HashSet<BlockPos>> getMatchingBlocksTask;
	private ForkJoinTask<ArrayList<int[]>> compileVerticesTask;
	private boolean shaderSafeMode;
	private int buildGeneration;
	private int currentBuildGeneration;
	
	// Keep a copy of matching positions for tracers
	private HashSet<BlockPos> lastMatchingBlocks;
	private java.util.List<AABB> highlightBoxes;
	
	private EasyVertexBuffer vertexBuffer;
	private RegionPos bufferRegion;
	private boolean bufferUpToDate;
	// Precomputed tracer endpoints
	private java.util.List<net.minecraft.world.phys.Vec3> tracerEnds;
	private ChunkPos lastPlayerChunk;
	private int lastMatchesVersion;
	private boolean lastNeedsVertexBuffer;
	
	private SearchMode lastMode;
	private int lastListHash;
	// Cache for LIST mode: exact IDs and keyword terms
	private java.util.Set<String> listExactIds;
	private String[] listKeywords;
	
	private int foundCount; // number of currently displayed matches (clamped)
	private final HashMap<BlockPos, Long> pendingContainerUpdates =
		new HashMap<>();
	private final HashMap<BlockPos, Long> recentBlockUpdates = new HashMap<>();
	private final HashMap<BlockPos, Long> missingContainerAt = new HashMap<>();
	private final HashMap<BlockPos, Long> lastContainerBlockUpdateAt =
		new HashMap<>();
	private final HashSet<BlockPos> discoveredContainers = new HashSet<>();
	private final ArrayDeque<RevealSample> revealSamples = new ArrayDeque<>();
	private final HashSet<BlockPos> seenContainerBlockEntities =
		new HashSet<>();
	private final ArrayDeque<Long> containerBeBurstTimes = new ArrayDeque<>();
	private final ArrayDeque<ChunkScanRequest> chunkScanQueue =
		new ArrayDeque<>();
	private final HashSet<Long> queuedChunkScans = new HashSet<>();
	private final HashMap<String, Long> antiEspCooldowns = new HashMap<>();
	private final Object antiEspLock = new Object();
	private long lastContainerBePacketMs;
	private long burstStartMs = -1L;
	private boolean antiEspSuspicious;
	private int antiEspSignals;
	private boolean antiEspPreviouslyEnabled;
	
	public SearchHack()
	{
		super("Search");
		setCategory(Category.RENDER);
		addSetting(mode);
		addSetting(blockList);
		addSetting(query);
		addSetting(block);
		addSetting(style);
		addSetting(highlightCorners);
		addSetting(highlightFill);
		addSetting(highlightAlpha);
		addSetting(tracerFlash);
		addSetting(stickyArea);
		addSetting(useFixedColor);
		addSetting(fixedColor);
		addSetting(area);
		addSetting(limit);
		addSetting(onlyAboveGround);
		addSetting(aboveGroundY);
		addSetting(antiEspDetection);
		addSetting(antiEspAlerts);
		// new setting
		addSetting(showCountInHackList);
	}
	
	@Override
	public String getRenderName()
	{
		String colorMode = useFixedColor.isChecked() ? "Fixed" : "Rainbow";
		String base;
		switch(mode.getSelected())
		{
			case LIST:
			base = getName() + " [List:" + blockList.size() + "] (" + colorMode
				+ ")";
			break;
			case QUERY:
			String rawQuery = query.getValue().trim();
			if(!rawQuery.isEmpty())
				base = getName() + " [" + abbreviate(rawQuery) + "] ("
					+ colorMode + ")";
			else
				base = getName() + " [query] (" + colorMode + ")";
			break;
			case BLOCK_ID:
			default:
			base = getName() + " ["
				+ block.getBlockName().replace("minecraft:", "") + "] ("
				+ colorMode + ")";
		}
		if(showCountInHackList.isChecked() && foundCount > 0)
			return base + " [" + Math.min(foundCount, 999) + "]";
		return base;
	}
	
	@Override
	protected void onEnable()
	{
		prevLimit = limit.getValueI();
		notify = true;
		forkJoinPool = new ForkJoinPool();
		shaderSafeMode = ShaderUtils.refreshShadersActive();
		buildGeneration = 0;
		currentBuildGeneration = 0;
		bufferUpToDate = false;
		lastAreaSelection = area.getSelected();
		lastPlayerChunk = ChunkPos.containing(MC.player.blockPosition());
		lastMode = mode.getSelected();
		lastListHash = blockList.getBlockNames().hashCode();
		lastNeedsVertexBuffer = needsVertexBuffer();
		applySearchCriteria(block.getBlock(), "");
		lastMatchesVersion = coordinator.getMatchesVersion();
		resetAntiEspState();
		antiEspPreviouslyEnabled = antiEspDetection.isChecked();
		if(shaderSafeMode)
			ChatUtils
				.message("Shaders detected - using safe mode for SearchHack.");
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, coordinator);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, coordinator);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		stopBuildingBuffer(true);
		coordinator.reset();
		forkJoinPool.shutdownNow();
		if(vertexBuffer != null)
			vertexBuffer.close();
		vertexBuffer = null;
		bufferRegion = null;
		lastMatchingBlocks = null;
		highlightBoxes = null;
		tracerEnds = null;
		lastPlayerChunk = null;
		foundCount = 0; // reset count
		resetAntiEspState();
		antiEspPreviouslyEnabled = false;
	}
	
	@Override
	public void onUpdate()
	{
		boolean antiEspNow = antiEspDetection.isChecked();
		if(antiEspNow != antiEspPreviouslyEnabled)
		{
			resetAntiEspState();
			antiEspPreviouslyEnabled = antiEspNow;
		}
		
		if(antiEspNow)
		{
			processContainerAntiEspChecks();
			if(isContainerSearchActive())
			{
				recordVisibleContainerDiscoveries();
				processQueuedChunkScans(2);
				checkRevealPattern();
				pruneContainerAntiEspState();
			}
		}
		
		boolean currentShaderSafeMode = ShaderUtils.refreshShadersActive();
		if(currentShaderSafeMode != shaderSafeMode)
		{
			shaderSafeMode = currentShaderSafeMode;
			stopBuildingBuffer(true);
			if(shaderSafeMode)
				ChatUtils.message(
					"Shaders detected - using safe mode for SearchHack.");
			else
				ChatUtils.message(
					"Shaders disabled - returning SearchHack to normal mode.");
		}
		
		SearchMode currentMode = mode.getSelected();
		
		// Mode/list changes
		if(currentMode != lastMode)
		{
			lastMode = currentMode;
			applySearchCriteria(block.getBlock(), "");
		}
		if(currentMode == SearchMode.LIST)
		{
			int listHash = blockList.getBlockNames().hashCode();
			if(listHash != lastListHash)
			{
				lastListHash = listHash;
				applySearchCriteria(block.getBlock(), "");
			}
		}
		
		// Recenter per chunk when sticky is off
		ChunkPos currentChunk = ChunkPos.containing(MC.player.blockPosition());
		if(!stickyArea.isChecked() && !currentChunk.equals(lastPlayerChunk))
		{
			lastPlayerChunk = currentChunk;
			coordinator.reset();
			stopBuildingBuffer(false);
		}
		
		// Area changes
		ChunkAreaSetting.ChunkArea currentArea = area.getSelected();
		if(currentArea != lastAreaSelection)
		{
			lastAreaSelection = currentArea;
			coordinator.reset();
			stopBuildingBuffer(true);
			notify = true;
		}
		
		// Criteria changes only for modes that use them
		if(currentMode == SearchMode.BLOCK_ID)
		{
			Block currentBlock = block.getBlock();
			if(currentBlock != lastBlock)
				applySearchCriteria(currentBlock, "");
		}else if(currentMode == SearchMode.QUERY)
		{
			String currentQuery = normalizeQuery(query.getValue());
			if(!currentQuery.equals(lastQuery))
				applySearchCriteria(block.getBlock(), currentQuery);
		}
		
		// Update coordinator (adds/removes searchers, applies packet updates)
		coordinator.update();
		
		int matchesVersion = coordinator.getMatchesVersion();
		if(matchesVersion != lastMatchesVersion)
		{
			lastMatchesVersion = matchesVersion;
			stopBuildingBuffer(false);
		}
		
		if(limit.getValueI() != prevLimit)
		{
			stopBuildingBuffer(false);
			prevLimit = limit.getValueI();
			notify = true;
		}
		
		boolean needsVertexBuffer = needsVertexBuffer();
		if(needsVertexBuffer != lastNeedsVertexBuffer)
		{
			lastNeedsVertexBuffer = needsVertexBuffer;
			stopBuildingBuffer(true);
		}
		
		if(!coordinator.hasReadyMatches())
			return;
		
		if(shaderSafeMode)
		{
			buildBufferSafeMode();
			return;
		}
		
		if(getMatchingBlocksTask == null)
			startGetMatchingBlocksTask();
		
		if(!getMatchingBlocksTask.isDone())
			return;
		
		if(!needsVertexBuffer)
		{
			if(!bufferUpToDate)
				setSimpleBufferFromTask();
			return;
		}
		
		if(compileVerticesTask == null)
			startCompileVerticesTask();
		
		if(!compileVerticesTask.isDone())
			return;
		
		if(!bufferUpToDate)
			setBufferFromTask();
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!antiEspDetection.isChecked() || MC.level == null)
			return;
		
		Packet<?> packet = event.getPacket();
		if(isContainerSearchActive())
		{
			ChunkPos affected = ChunkUtils.getAffectedChunk(packet);
			if(affected != null)
				queueChunkScan(affected);
		}
		if(packet instanceof ClientboundBlockUpdatePacket blockUpdate)
		{
			handleContainerBlockUpdate(blockUpdate.getPos(),
				blockUpdate.getBlockState());
			if(isContainerSearchActive())
				handleContainerSpecificBlockUpdate(blockUpdate.getPos(),
					blockUpdate.getBlockState());
			return;
		}
		
		if(packet instanceof ClientboundSectionBlocksUpdatePacket deltaUpdate)
		{
			deltaUpdate.runUpdates((pos, state) -> {
				handleContainerBlockUpdate(pos, state);
				if(isContainerSearchActive())
					handleContainerSpecificBlockUpdate(pos, state);
			});
			return;
		}
		
		if(packet instanceof ClientboundBlockEntityDataPacket bePacket)
		{
			handleContainerBlockEntityPacket(bePacket);
			if(isContainerSearchActive())
				handleContainerSpecificBePacket(bePacket);
		}
	}
	
	// New: cancel view bobbing if drawing lines
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		boolean drawBoxes =
			style.hasBoxes() && vertexBuffer != null && bufferRegion != null;
		boolean drawTracers =
			style.hasLines() && tracerEnds != null && !tracerEnds.isEmpty();
		boolean drawHighlights =
			highlightBoxes != null && !highlightBoxes.isEmpty();
		
		if(!drawBoxes && !drawTracers && !drawHighlights)
			return;
		
		float[] rgb = useFixedColor.isChecked() ? fixedColor.getColorF()
			: RenderUtils.getRainbowColor();
		
		if(drawBoxes && highlightFill.isChecked())
		{
			matrixStack.pushPose();
			RenderUtils.applyRegionalRenderOffset(matrixStack, bufferRegion);
			vertexBuffer.draw(matrixStack, WurstRenderLayers.ESP_QUADS, rgb,
				0.5F);
			matrixStack.popPose();
		}
		
		if(drawTracers)
		{
			int tracerColor = RenderUtils.toIntColor(rgb, 0.5F);
			if(tracerFlash.isChecked())
				tracerColor = RenderUtils.flashColor(tracerColor);
			RenderUtils.drawTracers(matrixStack, partialTicks, tracerEnds,
				tracerColor, false);
		}
		
		if(drawHighlights)
		{
			float alpha = getHighlightAlphaFloat();
			int color = RenderUtils.toIntColor(rgb, alpha);
			if(highlightFill.isChecked() && !drawBoxes)
			{
				float halfAlpha = Math.max(1F / 255F, alpha / 2F);
				int solidColor = RenderUtils.toIntColor(rgb, halfAlpha);
				RenderUtils.drawSolidBoxes(matrixStack, highlightBoxes,
					solidColor, false);
			}
			if(highlightCorners.isChecked())
				RenderUtils.drawOutlinedBoxes(matrixStack, highlightBoxes,
					color, false);
		}
	}
	
	private String normalizeQuery(String rawQuery)
	{
		if(rawQuery == null)
			return "";
		
		return rawQuery.trim().toLowerCase(Locale.ROOT);
	}
	
	private String abbreviate(String text)
	{
		if(text.length() <= 32)
			return text;
		
		return text.substring(0, 32) + "...";
	}
	
	private void applySearchCriteria(Block currentBlock, String normalizedQuery)
	{
		stopBuildingBuffer(true);
		
		switch(mode.getSelected())
		{
			case LIST:
			lastBlock = currentBlock;
			// Build caches: exact IDs vs keyword terms
			java.util.List<String> names = blockList.getBlockNames();
			java.util.ArrayList<String> kw = new java.util.ArrayList<>();
			java.util.HashSet<String> exact = new java.util.HashSet<>();
			for(String s : names)
			{
				if(s == null)
					continue;
				String raw = s.trim();
				if(raw.isEmpty())
					continue;
				Identifier id = Identifier.tryParse(raw);
				if(id != null && BuiltInRegistries.BLOCK.containsKey(id))
					exact.add(id.toString());
				else
					kw.add(raw.toLowerCase(Locale.ROOT));
			}
			listExactIds = exact;
			listKeywords = kw.toArray(new String[0]);
			coordinator.setQuery((pos, state) -> {
				if(onlyAboveGround.isChecked()
					&& pos.getY() < aboveGroundY.getValue())
					return false;
				String idFull = BlockUtils.getName(state.getBlock());
				if(listExactIds.contains(idFull))
					return true;
				String localId = idFull.contains(":")
					? idFull.substring(idFull.indexOf(":") + 1) : idFull;
				String localSpaced = localId.replace('_', ' ');
				String transKey = state.getBlock().getDescriptionId();
				String display = state.getBlock().getName().getString();
				for(String term : listKeywords)
				{
					if(containsNormalized(idFull, term)
						|| containsNormalized(localId, term)
						|| containsNormalized(localSpaced, term)
						|| containsNormalized(transKey, term)
						|| containsNormalized(display, term))
						return true;
				}
				return false;
			});
			lastQuery = "";
			break;
			case QUERY:
			lastBlock = currentBlock;
			coordinator.setQuery((pos, state) -> {
				if(onlyAboveGround.isChecked()
					&& pos.getY() < aboveGroundY.getValue())
					return false;
				return blockMatchesQuery(state.getBlock(), normalizedQuery);
			});
			lastQuery = normalizedQuery;
			break;
			case BLOCK_ID:
			default:
			lastBlock = currentBlock;
			coordinator.setQuery((pos, state) -> {
				if(onlyAboveGround.isChecked()
					&& pos.getY() < aboveGroundY.getValue())
					return false;
				return state.getBlock() == currentBlock;
			});
			lastQuery = "";
		}
		notify = true;
		lastMatchesVersion = coordinator.getMatchesVersion();
	}
	
	private boolean blockMatchesQuery(Block block, String normalizedQuery)
	{
		String id = BlockUtils.getName(block);
		String localId =
			id.contains(":") ? id.substring(id.indexOf(":") + 1) : id;
		// Support multiple comma-separated terms; match if ANY term matches
		String[] terms = Arrays.stream(normalizedQuery.split(","))
			.map(String::trim).filter(s -> !s.isEmpty()).toArray(String[]::new);
		if(terms.length == 0)
			terms = new String[]{normalizedQuery};
		String localSpaced = localId.replace('_', ' ');
		String transKey = block.getDescriptionId();
		String display = block.getName().getString();
		for(String term : terms)
			if(containsNormalized(id, term) || containsNormalized(localId, term)
				|| containsNormalized(localSpaced, term)
				|| containsNormalized(transKey, term)
				|| containsNormalized(display, term))
				return true;
		return false;
	}
	
	private boolean containsNormalized(String haystack, String normalizedQuery)
	{
		return haystack != null
			&& haystack.toLowerCase(Locale.ROOT).contains(normalizedQuery);
	}
	
	private void startGetMatchingBlocksTask()
	{
		// Use a bounded max-heap to keep only the closest N matches without
		// sorting the entire result set. This avoids long pauses when scanning
		// very large areas.
		BlockPos eyesPos = BlockPos.containing(RotationUtils.getEyesPos());
		final int limitCount = getEffectiveRenderLimit();
		currentBuildGeneration = buildGeneration;
		getMatchingBlocksTask = forkJoinPool.submit(() -> {
			PriorityQueue<BlockPos> heap = new PriorityQueue<>((limitCount + 1),
				(a, b) -> Integer.compare(b.distManhattan(eyesPos),
					a.distManhattan(eyesPos)));
			java.util.Iterator<ChunkSearcher.Result> it =
				coordinator.getReadyMatches().iterator();
			while(it.hasNext())
			{
				ChunkSearcher.Result r = it.next();
				BlockPos pos = r.pos();
				if(heap.size() < limitCount)
					heap.offer(pos);
				else if(pos.distManhattan(eyesPos) < heap.peek()
					.distManhattan(eyesPos))
				{
					heap.poll();
					heap.offer(pos);
				}
			}
			return new HashSet<>(heap);
		});
	}
	
	private void startCompileVerticesTask()
	{
		if(currentBuildGeneration != buildGeneration)
		{
			stopBuildingBuffer(false);
			return;
		}
		
		HashSet<BlockPos> matchingBlocks = getMatchingBlocksTask.join();
		// store for tracers
		lastMatchingBlocks = matchingBlocks;
		
		int effectiveLimit = getEffectiveRenderLimit();
		if(matchingBlocks.size() < effectiveLimit)
			notify = true;
		else if(notify)
		{
			ChatUtils.warning("Search found \u00a7lA LOT\u00a7r of blocks!"
				+ " To prevent lag, it will only show the closest \u00a76"
				+ effectiveLimit + "\u00a7r results.");
			notify = false;
		}
		
		compileVerticesTask = forkJoinPool
			.submit(() -> BlockVertexCompiler.compile(matchingBlocks));
	}
	
	private void buildBufferSafeMode()
	{
		if(bufferUpToDate)
			return;
		
		if(getMatchingBlocksTask != null || compileVerticesTask != null)
			stopBuildingBuffer(false);
			
		// Use a bounded max-heap to keep only the closest N matches without
		// sorting the entire result set. This avoids long pauses when scanning
		// very large areas.
		BlockPos eyesPos = BlockPos.containing(RotationUtils.getEyesPos());
		final int limitCount = getEffectiveRenderLimit();
		java.util.ArrayList<ChunkSearcher.Result> readyMatches =
			coordinator.getReadyMatches().collect(
				java.util.stream.Collectors.toCollection(ArrayList::new));
		PriorityQueue<BlockPos> heap =
			new PriorityQueue<>((limitCount + 1), (a, b) -> Integer
				.compare(b.distManhattan(eyesPos), a.distManhattan(eyesPos)));
		for(ChunkSearcher.Result r : readyMatches)
		{
			BlockPos pos = r.pos();
			if(heap.size() < limitCount)
				heap.offer(pos);
			else if(pos.distManhattan(eyesPos) < heap.peek()
				.distManhattan(eyesPos))
			{
				heap.poll();
				heap.offer(pos);
			}
		}
		
		HashSet<BlockPos> matchingBlocks = new HashSet<>(heap);
		// store for tracers
		lastMatchingBlocks = matchingBlocks;
		
		int effectiveLimit = getEffectiveRenderLimit();
		if(matchingBlocks.size() < effectiveLimit)
			notify = true;
		else if(notify)
		{
			ChatUtils.warning("Search found \u00a7lA LOT\u00a7r of blocks!"
				+ " To prevent lag, it will only show the closest \u00a76"
				+ effectiveLimit + "\u00a7r results.");
			notify = false;
		}
		
		if(!needsVertexBuffer())
		{
			setSimpleBufferFromMatches(matchingBlocks);
			return;
		}
		
		ArrayList<int[]> vertices = BlockVertexCompiler.compile(matchingBlocks);
		setBufferFromVertices(vertices, matchingBlocks);
	}
	
	private void setBufferFromTask()
	{
		if(currentBuildGeneration != buildGeneration)
		{
			stopBuildingBuffer(false);
			return;
		}
		
		ArrayList<int[]> vertices = compileVerticesTask.join();
		setBufferFromVertices(vertices, lastMatchingBlocks);
	}
	
	private void setSimpleBufferFromTask()
	{
		HashSet<BlockPos> matchingBlocks = getMatchingBlocksTask.join();
		lastMatchingBlocks = matchingBlocks;
		setSimpleBufferFromMatches(matchingBlocks);
	}
	
	private void setSimpleBufferFromMatches(HashSet<BlockPos> matchingBlocks)
	{
		if(vertexBuffer != null)
		{
			vertexBuffer.close();
			vertexBuffer = null;
		}
		
		bufferRegion = null;
		bufferUpToDate = true;
		
		if(matchingBlocks != null)
		{
			highlightBoxes = matchingBlocks.stream().map(AABB::new)
				.collect(java.util.stream.Collectors.toList());
			tracerEnds = matchingBlocks.stream().map(pos -> {
				if(net.wurstclient.util.BlockUtils.canBeClicked(pos))
					return net.wurstclient.util.BlockUtils.getBoundingBox(pos)
						.getCenter();
				return pos.getCenter();
			}).collect(java.util.stream.Collectors.toList());
			foundCount = Math.min(matchingBlocks.size(), 999);
			
		}else
		{
			highlightBoxes = null;
			tracerEnds = null;
			foundCount = 0;
		}
	}
	
	private void setBufferFromVertices(ArrayList<int[]> vertices,
		HashSet<BlockPos> matchingBlocks)
	{
		RegionPos region = RenderUtils.getCameraRegion();
		if(vertexBuffer != null)
			vertexBuffer.close();
		vertexBuffer = EasyVertexBuffer.createAndUpload(Mode.QUADS,
			DefaultVertexFormat.POSITION_COLOR, buffer -> {
				for(int[] vertex : vertices)
					buffer.addVertex(vertex[0] - region.x(), vertex[1],
						vertex[2] - region.z()).setColor(0xFFFFFFFF);
			});
		bufferUpToDate = true;
		bufferRegion = region;
		// build tracer endpoints now that we have matching blocks
		if(matchingBlocks != null)
		{
			highlightBoxes = matchingBlocks.stream().map(AABB::new)
				.collect(java.util.stream.Collectors.toList());
			tracerEnds = matchingBlocks.stream().map(pos -> {
				if(net.wurstclient.util.BlockUtils.canBeClicked(pos))
					return net.wurstclient.util.BlockUtils.getBoundingBox(pos)
						.getCenter();
				return pos.getCenter();
			}).collect(java.util.stream.Collectors.toList());
			// update count for HUD (clamped to 999)
			foundCount = Math.min(matchingBlocks.size(), 999);
		}else
		{
			highlightBoxes = null;
			foundCount = 0;
		}
	}
	
	private void stopBuildingBuffer(boolean discardCurrent)
	{
		buildGeneration++;
		if(getMatchingBlocksTask != null)
			getMatchingBlocksTask.cancel(true);
		getMatchingBlocksTask = null;
		if(compileVerticesTask != null)
			compileVerticesTask.cancel(true);
		compileVerticesTask = null;
		bufferUpToDate = false;
		if(discardCurrent)
		{
			tracerEnds = null;
			lastMatchingBlocks = null;
			highlightBoxes = null;
			foundCount = 0;
			if(vertexBuffer != null)
			{
				vertexBuffer.close();
				vertexBuffer = null;
			}
			bufferRegion = null;
		}
	}
	
	private int getEffectiveRenderLimit()
	{
		int localLimit = limit.getValueLog();
		int effective = WURST.getHax().globalToggleHack
			.applyGlobalEspRenderLimit(localLimit);
		return Math.max(1, effective);
	}
	
	private boolean needsVertexBuffer()
	{
		return highlightFill.isChecked();
	}
	
	private float getHighlightAlphaFloat()
	{
		int v = (int)Math.round(highlightAlpha.getValue());
		v = Math.max(1, Math.min(100, v));
		return v / 100F;
	}
	
	public void enableQuerySearch(String rawQuery)
	{
		mode.setSelected(SearchMode.QUERY);
		query.setValue(rawQuery == null ? "" : rawQuery.trim());
		setEnabled(true);
	}
	
	private void processContainerAntiEspChecks()
	{
		if(MC.level == null)
		{
			synchronized(antiEspLock)
			{
				pendingContainerUpdates.clear();
			}
			return;
		}
		
		long now = System.currentTimeMillis();
		synchronized(antiEspLock)
		{
			java.util.Iterator<java.util.Map.Entry<BlockPos, Long>> it =
				pendingContainerUpdates.entrySet().iterator();
			while(it.hasNext())
			{
				java.util.Map.Entry<BlockPos, Long> entry = it.next();
				if(now - entry.getValue() <= BLOCK_UPDATE_GRACE_MS)
					continue;
				
				BlockPos pos = entry.getKey();
				net.minecraft.world.level.block.state.BlockState state =
					MC.level.getBlockState(pos);
				if(isTrackedBlock(state.getBlock()) && state.hasBlockEntity()
					&& MC.level.getBlockEntity(pos) == null)
					flagAntiEsp("missing-be", "Searched block at "
						+ formatPos(pos) + " has no block entity after update");
				
				it.remove();
			}
			
			while(!containerBeBurstTimes.isEmpty()
				&& now - containerBeBurstTimes.peekFirst() > BURST_WINDOW_MS)
				containerBeBurstTimes.removeFirst();
			recentBlockUpdates.entrySet()
				.removeIf(e -> now - e.getValue() > BLOCK_UPDATE_GRACE_MS * 4L);
		}
	}
	
	private void handleContainerBlockUpdate(BlockPos pos,
		net.minecraft.world.level.block.state.BlockState state)
	{
		if(pos == null || state == null)
			return;
		
		BlockState oldState =
			MC.level == null ? null : MC.level.getBlockState(pos);
		boolean newTracked = isTrackedBlock(state.getBlock());
		if(newTracked && oldState != null
			&& oldState.getBlock() != state.getBlock())
		{
			double distance = MC.player == null ? 0
				: MC.player.position().distanceTo(Vec3.atCenterOf(pos));
			if(distance > 5)
				flagAntiEsp("fake-replacement",
					"Searched block changed via block update at "
						+ formatPos(pos) + " (" + formatBlock(oldState) + " -> "
						+ formatBlock(state) + ")");
		}
		
		BlockPos immutablePos = pos.immutable();
		synchronized(antiEspLock)
		{
			recentBlockUpdates.put(immutablePos, System.currentTimeMillis());
			if(newTracked)
			{
				pendingContainerUpdates.put(immutablePos,
					System.currentTimeMillis());
				return;
			}
			
			boolean hadPending =
				pendingContainerUpdates.remove(immutablePos) != null;
			boolean hadSeenBe = seenContainerBlockEntities.remove(immutablePos);
			if(hadPending || hadSeenBe)
				flagAntiEsp("fake-replacement",
					"Searched block at " + formatPos(immutablePos)
						+ " changed into " + formatBlock(state));
		}
	}
	
	private void handleContainerBlockEntityPacket(
		ClientboundBlockEntityDataPacket bePacket)
	{
		BlockPos pos = bePacket.getPos().immutable();
		net.minecraft.world.level.block.state.BlockState state =
			MC.level.getBlockState(pos);
		
		long now = System.currentTimeMillis();
		synchronized(antiEspLock)
		{
			containerBeBurstTimes.addLast(now);
			while(!containerBeBurstTimes.isEmpty()
				&& now - containerBeBurstTimes.peekFirst() > BURST_WINDOW_MS)
				containerBeBurstTimes.removeFirst();
			
			if(now - lastContainerBePacketMs > BURST_QUIET_MS)
				burstStartMs = now;
			lastContainerBePacketMs = now;
			
			if(containerBeBurstTimes.size() >= BURST_THRESHOLD
				&& burstStartMs > 0 && now - burstStartMs <= BURST_WINDOW_MS)
				flagAntiEsp("be-burst",
					"Received " + containerBeBurstTimes.size()
						+ " searched block-entity packets in "
						+ (now - burstStartMs) + "ms");
		}
		
		if(state == null || !isTrackedBlock(state.getBlock())
			|| !state.hasBlockEntity())
			return;
		
		synchronized(antiEspLock)
		{
			seenContainerBlockEntities.add(pos);
			Long lastUpdate = pendingContainerUpdates.remove(pos);
			if(lastUpdate == null)
				lastUpdate = recentBlockUpdates.get(pos);
			if(lastUpdate == null || now - lastUpdate > BLOCK_UPDATE_GRACE_MS)
				flagAntiEsp("late-block-entity",
					"Block entity for searched block at " + formatPos(pos)
						+ " arrived without recent block update");
		}
	}
	
	private void flagAntiEsp(String key, String message)
	{
		synchronized(antiEspLock)
		{
			long now = System.currentTimeMillis();
			Long cooldown = antiEspCooldowns.get(key);
			if(cooldown != null && now - cooldown < 8000L)
				return;
			
			antiEspCooldowns.put(key, now);
			antiEspSuspicious = true;
			antiEspSignals = Math.min(antiEspSignals + 1, 999);
			if(antiEspAlerts.isChecked())
				ChatUtils.warning("Search Anti-ESP: " + message);
		}
	}
	
	private void resetAntiEspState()
	{
		synchronized(antiEspLock)
		{
			pendingContainerUpdates.clear();
			recentBlockUpdates.clear();
			missingContainerAt.clear();
			lastContainerBlockUpdateAt.clear();
			discoveredContainers.clear();
			revealSamples.clear();
			seenContainerBlockEntities.clear();
			containerBeBurstTimes.clear();
			chunkScanQueue.clear();
			queuedChunkScans.clear();
			antiEspCooldowns.clear();
			lastContainerBePacketMs = 0L;
			burstStartMs = -1L;
			antiEspSuspicious = false;
			antiEspSignals = 0;
		}
	}
	
	private boolean isContainerSearchActive()
	{
		SearchMode currentMode = mode.getSelected();
		if(currentMode == SearchMode.BLOCK_ID)
			return isContainerBlock(block.getBlock());
		
		if(currentMode == SearchMode.QUERY)
		{
			String q = normalizeQuery(query.getValue());
			return q.contains("chest") || q.contains("barrel")
				|| q.contains("shulker") || q.contains("hopper")
				|| q.contains("dispenser") || q.contains("dropper")
				|| q.contains("furnace") || q.contains("beacon")
				|| q.contains("crafter");
		}
		
		for(String name : blockList.getBlockNames())
		{
			if(name == null)
				continue;
			String normalized = name.trim().toLowerCase(Locale.ROOT);
			if(normalized.isEmpty())
				continue;
			Identifier id = Identifier.tryParse(normalized);
			if(id != null && BuiltInRegistries.BLOCK.containsKey(id)
				&& isContainerId(id.toString()))
				return true;
		}
		
		return false;
	}
	
	private void handleContainerSpecificBlockUpdate(BlockPos pos,
		BlockState newState)
	{
		if(MC.level == null || pos == null || newState == null)
			return;
		
		long now = System.currentTimeMillis();
		BlockState oldState = MC.level.getBlockState(pos);
		boolean oldContainer = isContainerBlock(oldState);
		boolean newContainer = isContainerBlock(newState);
		
		if(oldContainer || newContainer)
			synchronized(antiEspLock)
			{
				lastContainerBlockUpdateAt.put(pos.immutable(), now);
			}
		
		if(!oldContainer && newContainer)
		{
			double distance = MC.player == null ? 0
				: MC.player.position().distanceTo(Vec3.atCenterOf(pos));
			if(distance > 5)
				flagAntiEsp("fake-replacement",
					"Container block appeared via block update at "
						+ formatPos(pos) + " (" + formatBlock(oldState) + " -> "
						+ formatBlock(newState) + ")");
		}
	}
	
	private void handleContainerSpecificBePacket(
		ClientboundBlockEntityDataPacket p)
	{
		if(MC.level == null)
			return;
		
		long now = System.currentTimeMillis();
		BlockPos pos = p.getPos().immutable();
		BlockState state = MC.level.getBlockState(pos);
		if(!isContainerBlock(state))
			return;
		
		Long missingAt;
		Long updatedAt;
		synchronized(antiEspLock)
		{
			missingAt = missingContainerAt.get(pos);
			updatedAt = lastContainerBlockUpdateAt.get(pos);
		}
		boolean missingRecently =
			missingAt != null && now - missingAt <= CHUNK_SCAN_EXPIRY_MS;
		boolean noRecentUpdate =
			updatedAt == null || now - updatedAt > BLOCK_UPDATE_GRACE_MS;
		if(missingRecently && noRecentUpdate)
			flagAntiEsp("late-block-entity", "Block entity for container at "
				+ formatPos(pos) + " arrived later without a block change");
	}
	
	private void queueChunkScan(ChunkPos chunkPos)
	{
		if(chunkPos == null)
			return;
		
		long key = chunkKey(chunkPos);
		synchronized(antiEspLock)
		{
			if(!queuedChunkScans.add(key))
				return;
			
			chunkScanQueue.addLast(new ChunkScanRequest(chunkPos, key,
				System.currentTimeMillis()));
		}
	}
	
	private void processQueuedChunkScans(int maxPerTick)
	{
		if(MC.level == null || maxPerTick <= 0)
			return;
		
		long now = System.currentTimeMillis();
		for(int i = 0; i < maxPerTick; i++)
		{
			ChunkScanRequest request;
			synchronized(antiEspLock)
			{
				request = chunkScanQueue.pollFirst();
			}
			if(request == null)
				return;
			
			synchronized(antiEspLock)
			{
				queuedChunkScans.remove(request.key());
			}
			if(now - request.queuedAt() > CHUNK_SCAN_EXPIRY_MS)
				continue;
			
			scanChunkForContainers(request.chunkPos());
		}
	}
	
	private void scanChunkForContainers(ChunkPos chunkPos)
	{
		if(MC.level == null || !MC.level.hasChunk(chunkPos.x(), chunkPos.z()))
			return;
		
		LevelChunk chunk = MC.level.getChunk(chunkPos.x(), chunkPos.z());
		if(chunk == null)
			return;
		
		int minY = chunk.getMinY();
		int minSectionY = minY >> 4;
		int containerBlocks = 0;
		int withBlockEntity = 0;
		int withoutBlockEntity = 0;
		
		LevelChunkSection[] sections = chunk.getSections();
		for(int sectionIndex =
			0; sectionIndex < sections.length; sectionIndex++)
		{
			LevelChunkSection section = sections[sectionIndex];
			if(section == null || section.hasOnlyAir())
				continue;
			
			int sectionY = minSectionY + sectionIndex;
			int baseY = sectionY << 4;
			for(int lx = 0; lx < 16; lx++)
				for(int ly = 0; ly < 16; ly++)
					for(int lz = 0; lz < 16; lz++)
					{
						BlockState state = section.getStates().get(lx, ly, lz);
						if(!isContainerBlock(state))
							continue;
						
						containerBlocks++;
						BlockPos pos =
							new BlockPos(chunkPos.getMinBlockX() + lx,
								baseY + ly, chunkPos.getMinBlockZ() + lz);
						BlockEntity be = chunk.getBlockEntity(pos);
						if(be == null)
						{
							withoutBlockEntity++;
							synchronized(antiEspLock)
							{
								missingContainerAt.put(pos.immutable(),
									System.currentTimeMillis());
							}
						}else
							withBlockEntity++;
					}
		}
		
		if(withoutBlockEntity > 0)
			flagAntiEsp("missing-be",
				"Chunk " + chunkPos.x() + ", " + chunkPos.z() + " has "
					+ withoutBlockEntity
					+ " container blocks without block entities");
		
		if(containerBlocks >= 8 && withBlockEntity == 0)
			flagAntiEsp("chunk-te-mismatch",
				"Chunk " + chunkPos.x() + ", " + chunkPos.z() + " has "
					+ containerBlocks
					+ " container blocks but 0 block entities");
	}
	
	private void pruneContainerAntiEspState()
	{
		long now = System.currentTimeMillis();
		synchronized(antiEspLock)
		{
			missingContainerAt.entrySet()
				.removeIf(e -> now - e.getValue() > CHUNK_SCAN_EXPIRY_MS);
			lastContainerBlockUpdateAt.entrySet()
				.removeIf(e -> now - e.getValue() > CHUNK_SCAN_EXPIRY_MS);
			revealSamples.removeIf(
				sample -> now - sample.timestamp() > REVEAL_SAMPLE_EXPIRY_MS);
		}
	}
	
	private void recordVisibleContainerDiscoveries()
	{
		if(MC.level == null || MC.player == null)
			return;
		
		ChunkUtils.getLoadedBlockEntities().forEach(be -> {
			if(!isContainerBlockEntity(be))
				return;
			
			BlockPos pos = be.getBlockPos().immutable();
			synchronized(antiEspLock)
			{
				if(!discoveredContainers.add(pos))
					return;
				
				double distance =
					MC.player.position().distanceTo(Vec3.atCenterOf(pos));
				revealSamples.addLast(
					new RevealSample(distance, System.currentTimeMillis()));
				while(revealSamples.size() > 64)
					revealSamples.removeFirst();
			}
		});
	}
	
	private void checkRevealPattern()
	{
		synchronized(antiEspLock)
		{
			if(revealSamples.size() < REVEAL_MIN_SAMPLES)
				return;
			
			double sum = 0;
			for(RevealSample sample : revealSamples)
				sum += sample.distance();
			
			double mean = sum / revealSamples.size();
			double variance = 0;
			for(RevealSample sample : revealSamples)
			{
				double d = sample.distance() - mean;
				variance += d * d;
			}
			double stddev = Math.sqrt(variance / revealSamples.size());
			
			boolean suspiciousRadius =
				mean >= 12 && mean <= 32 && stddev <= 2.8;
			if(suspiciousRadius)
				flagAntiEsp("reveal-radius",
					"Containers are consistently discovered at ~"
						+ String.format(java.util.Locale.ROOT, "%.1f", mean)
						+ " blocks (stddev "
						+ String.format(java.util.Locale.ROOT, "%.1f", stddev)
						+ ")");
		}
	}
	
	private boolean isContainerBlock(BlockState state)
	{
		return state != null && isContainerBlock(state.getBlock());
	}
	
	private boolean isContainerBlock(Block candidate)
	{
		if(candidate == null)
			return false;
		
		return candidate instanceof ChestBlock
			|| candidate instanceof BarrelBlock
			|| candidate instanceof ShulkerBoxBlock
			|| candidate instanceof HopperBlock
			|| candidate instanceof DispenserBlock
			|| candidate == Blocks.DROPPER || candidate == Blocks.FURNACE
			|| candidate == Blocks.BLAST_FURNACE || candidate == Blocks.SMOKER
			|| candidate == Blocks.BEACON || candidate == Blocks.ENDER_CHEST
			|| candidate == Blocks.CRAFTER;
	}
	
	private boolean isContainerBlockEntity(BlockEntity be)
	{
		if(be == null)
			return false;
		
		return isContainerBlock(be.getBlockState());
	}
	
	private boolean isContainerId(String id)
	{
		if(id == null || id.isEmpty())
			return false;
		
		return id.endsWith("chest") || id.endsWith("barrel")
			|| id.contains("shulker_box") || id.endsWith("hopper")
			|| id.endsWith("dispenser") || id.endsWith("dropper")
			|| id.endsWith("furnace") || id.endsWith("blast_furnace")
			|| id.endsWith("smoker") || id.endsWith("beacon")
			|| id.endsWith("crafter");
	}
	
	private static long chunkKey(ChunkPos pos)
	{
		return ((long)pos.x() << 32) ^ (pos.z() & 0xFFFFFFFFL);
	}
	
	private boolean isTrackedBlock(Block candidate)
	{
		if(candidate == null)
			return false;
		
		SearchMode currentMode = mode.getSelected();
		if(currentMode == SearchMode.BLOCK_ID)
			return candidate == block.getBlock();
		
		if(currentMode == SearchMode.QUERY)
			return blockMatchesQuery(candidate,
				normalizeQuery(query.getValue()));
		
		String idFull = BlockUtils.getName(candidate);
		if(listExactIds != null && listExactIds.contains(idFull))
			return true;
		
		String[] terms = listKeywords == null ? new String[0] : listKeywords;
		String localId = idFull.contains(":")
			? idFull.substring(idFull.indexOf(":") + 1) : idFull;
		String localSpaced = localId.replace('_', ' ');
		String transKey = candidate.getDescriptionId();
		String display = candidate.getName().getString();
		for(String term : terms)
		{
			if(containsNormalized(idFull, term)
				|| containsNormalized(localId, term)
				|| containsNormalized(localSpaced, term)
				|| containsNormalized(transKey, term)
				|| containsNormalized(display, term))
				return true;
		}
		
		return false;
	}
	
	private static String formatPos(BlockPos pos)
	{
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
	
	private static String formatBlock(
		net.minecraft.world.level.block.state.BlockState state)
	{
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}
	
	private record ChunkScanRequest(ChunkPos chunkPos, long key, long queuedAt)
	{}
	
	private record RevealSample(double distance, long timestamp)
	{}
}
