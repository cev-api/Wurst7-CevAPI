/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.clickgui.screens.EditBlockListScreen;
import net.wurstclient.events.GetAmbientOcclusionLightLevelListener;
import net.wurstclient.events.RenderBlockEntityListener;
import net.wurstclient.events.SetOpaqueCubeListener;
import net.wurstclient.events.ShouldDrawSideListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.ISimpleOption;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ChunkAreaSetting;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.chunk.ChunkSearcherCoordinator;

@SearchTags({"XRay", "x ray", "OreFinder", "ore finder"})
public final class XRayHack extends Hack implements UpdateListener,
	SetOpaqueCubeListener, GetAmbientOcclusionLightLevelListener,
	ShouldDrawSideListener, RenderBlockEntityListener, RenderListener
{
	private final BlockListSetting ores = new BlockListSetting("Ores",
		"A list of blocks that X-Ray will show. They don't have to be just ores"
			+ " - you can add any block you want.\n\n"
			+ "Remember to restart X-Ray when changing this setting.",
		"minecraft:amethyst_cluster", "minecraft:ancient_debris",
		"minecraft:anvil", "minecraft:beacon", "minecraft:bone_block",
		"minecraft:bookshelf", "minecraft:brewing_stand",
		"minecraft:budding_amethyst", "minecraft:chain_command_block",
		"minecraft:chest", "minecraft:coal_block", "minecraft:coal_ore",
		"minecraft:command_block", "minecraft:copper_ore", "minecraft:crafter",
		"minecraft:crafting_table", "minecraft:decorated_pot",
		"minecraft:deepslate_coal_ore", "minecraft:deepslate_copper_ore",
		"minecraft:deepslate_diamond_ore", "minecraft:deepslate_emerald_ore",
		"minecraft:deepslate_gold_ore", "minecraft:deepslate_iron_ore",
		"minecraft:deepslate_lapis_ore", "minecraft:deepslate_redstone_ore",
		"minecraft:diamond_block", "minecraft:diamond_ore",
		"minecraft:dispenser", "minecraft:dropper", "minecraft:emerald_block",
		"minecraft:emerald_ore", "minecraft:enchanting_table",
		"minecraft:end_portal", "minecraft:end_portal_frame",
		"minecraft:ender_chest", "minecraft:furnace", "minecraft:glowstone",
		"minecraft:gold_block", "minecraft:gold_ore", "minecraft:hopper",
		"minecraft:iron_block", "minecraft:iron_ore", "minecraft:ladder",
		"minecraft:lapis_block", "minecraft:lapis_ore", "minecraft:lava",
		"minecraft:lodestone", "minecraft:mossy_cobblestone",
		"minecraft:nether_gold_ore", "minecraft:nether_portal",
		"minecraft:nether_quartz_ore", "minecraft:raw_copper_block",
		"minecraft:raw_gold_block", "minecraft:raw_iron_block",
		"minecraft:redstone_block", "minecraft:redstone_ore",
		"minecraft:repeating_command_block", "minecraft:sculk_catalyst",
		"minecraft:sculk_sensor", "minecraft:sculk_shrieker",
		"minecraft:spawner", "minecraft:suspicious_gravel",
		"minecraft:suspicious_sand", "minecraft:tnt", "minecraft:torch",
		"minecraft:trapped_chest", "minecraft:trial_spawner", "minecraft:vault",
		"minecraft:wall_torch", "minecraft:water");
	
	private final CheckboxSetting onlyExposed = new CheckboxSetting(
		"Only show exposed",
		"Only shows ores that would be visible in caves. This can help against"
			+ " anti-X-Ray plugins.\n\n"
			+ "Remember to restart X-Ray when changing this setting.",
		false);
	
	private final SliderSetting opacity = new SliderSetting("Opacity",
		"Opacity of non-ore blocks when X-Ray is enabled.\n\n"
			+ "Does not work when Sodium is installed.\n\n"
			+ "Remember to restart X-Ray when changing this setting.",
		0, 0, 0.99, 0.01, ValueDisplay.PERCENTAGE.withLabel(0, "off"));
	
	private final String optiFineWarning;
	private final String renderName =
		Math.random() < 0.01 ? "X-Wurst" : getName();
	
	private ArrayList<String> oreNamesCache;
	private java.util.Set<String> oreExactIds;
	private String[] oreKeywords;
	private double lastOpacityVal;
	private int lastOresHash;
	private final ThreadLocal<BlockPos.MutableBlockPos> mutablePosForExposedCheck =
		ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);
	
	// Track last selected mode so switching triggers reloads
	private Mode lastMode = null;
	private boolean lastOnlyExposed = false;
	
	// Mode: LIST (use configured block list) or QUERY (use query text)
	private static enum Mode
	{
		LIST,
		QUERY
	}
	
	private final EnumSetting<Mode> mode =
		new EnumSetting<>("Mode", new Mode[]{Mode.LIST, Mode.QUERY}, Mode.LIST);
	private final TextFieldSetting query = new TextFieldSetting("Query",
		"Enter text to match block IDs or names by keyword. Separate multiple terms with commas.",
		"");
	
	// Corner highlight ESP settings
	private final CheckboxSetting highlightCorners = new CheckboxSetting(
		"Highlight corners",
		"Partial ESP for blocks, will cause lag if there are too many!", false);
	private final CheckboxSetting highlightFill = new CheckboxSetting(
		"Fill blocks (outline + fill)",
		"Full ESP for blocks, will cause lag if there are too many!", false);
	private final ColorSetting highlightColor =
		new ColorSetting("Highlight color", new java.awt.Color(0xFFFF00));
	private final SliderSetting highlightAlpha =
		new SliderSetting("Highlight transparency", 80, 1, 100, 1,
			ValueDisplay.INTEGER.withSuffix("%"));
	// Maximum number of highlighted blocks to render (log scale)
	private final SliderSetting renderAmount = new SliderSetting(
		"Render amount", "The maximum number of blocks to render.", 3, 2, 6, 1,
		ValueDisplay.LOGARITHMIC);
	// (block transparency override removed)
	private final ChunkAreaSetting area = new ChunkAreaSetting("Area",
		"The area around the player to search in. Higher values require a faster computer.");
	private final ChunkSearcherCoordinator coordinator;
	// store found positions separately so we can quickly filter by exposure
	private boolean highlightPositionsUpToDate = false;
	private java.util.List<BlockPos> highlightPositions =
		new java.util.ArrayList<>();
	private boolean visibleBoxesUpToDate = false;
	private java.util.List<AABB> visibleBoxes = new java.util.ArrayList<>();
	private int lastMatchesVersion;
	
	// Debounce to avoid flashing when coordinator updates rapidly (e.g., on
	// right-click or fast movement). Measured in milliseconds.
	private static final long COORDINATOR_DEBOUNCE_MS = 200L;
	private long lastCoordinatorChangeMs = 0L;
	
	public XRayHack()
	{
		super("X-Ray");
		setCategory(Category.RENDER);
		addSetting(ores);
		addSetting(onlyExposed);
		addSetting(opacity);
		addSetting(mode);
		addSetting(query);
		addSetting(highlightCorners);
		addSetting(highlightFill);
		addSetting(highlightColor);
		addSetting(highlightAlpha);
		addSetting(renderAmount);
		optiFineWarning = checkOptiFine();
		// Coordinator query: lightweight matching (ID + simple name), exposure
		// filtering is applied on main thread when building boxes
		coordinator = new ChunkSearcherCoordinator((pos, state) -> {
			String idFull =
				net.wurstclient.util.BlockUtils.getName(state.getBlock());
			// LIST mode matching
			if(mode.getSelected() == Mode.LIST)
			{
				if(oreExactIds != null && oreExactIds.contains(idFull))
					return true;
				if(oreExactIds == null && oreNamesCache != null
					&& oreNamesCache.contains(idFull))
					return true;
				return false;
			}
			// QUERY mode matching (only by id/local id/local spaced)
			if(oreKeywords == null || oreKeywords.length == 0)
				return false;
			String localId = idFull.contains(":")
				? idFull.substring(idFull.indexOf(":") + 1) : idFull;
			String localSpaced = localId.replace('_', ' ');
			for(String term : oreKeywords)
			{
				if(idFull.toLowerCase(java.util.Locale.ROOT).contains(term)
					|| localId.toLowerCase(java.util.Locale.ROOT).contains(term)
					|| localSpaced.toLowerCase(java.util.Locale.ROOT)
						.contains(term))
					return true;
			}
			return false;
		}, area);
	}
	
	@Override
	public String getRenderName()
	{
		return renderName;
	}
	
	@Override
	protected void onEnable()
	{
		// cache block names in case the setting changes while X-Ray is enabled
		lastMode = mode.getSelected();
		if(lastMode == Mode.LIST)
		{
			oreNamesCache = new ArrayList<>(ores.getBlockNames());
			lastOresHash = ores.getBlockNames().hashCode();
			lastOpacityVal = opacity.getValue();
			// build lookup caches immediately so isVisible() works on first
			// tick
			rebuildOreCaches();
		}else
		{
			// QUERY mode: pre-parse query into keywords
			String q = query.getValue();
			oreExactIds = null;
			if(q == null || q.isBlank())
				oreKeywords = new String[0];
			else
			{
				oreKeywords = Stream.of(q.split(","))
					.map(s -> s.trim().toLowerCase(java.util.Locale.ROOT))
					.filter(s -> !s.isEmpty()).toArray(String[]::new);
			}
			lastOpacityVal = opacity.getValue();
		}
		
		// remember current onlyExposed value to detect changes later
		lastOnlyExposed = onlyExposed.isChecked();
		// reset coordinator
		coordinator.reset();
		lastMatchesVersion = coordinator.getMatchesVersion();
		highlightPositionsUpToDate = false;
		visibleBoxesUpToDate = false;
		lastCoordinatorChangeMs = System.currentTimeMillis();
		
		// add event listeners
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, coordinator);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(SetOpaqueCubeListener.class, this);
		EVENTS.add(GetAmbientOcclusionLightLevelListener.class, this);
		EVENTS.add(ShouldDrawSideListener.class, this);
		EVENTS.add(RenderBlockEntityListener.class, this);
		
		// reload chunks
		MC.levelRenderer.allChanged();
		
		// display warning if OptiFine is detected
		if(optiFineWarning != null)
			ChatUtils.warning(optiFineWarning);
	}
	
	@Override
	protected void onDisable()
	{
		// remove event listeners
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, coordinator);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(SetOpaqueCubeListener.class, this);
		EVENTS.remove(GetAmbientOcclusionLightLevelListener.class, this);
		EVENTS.remove(ShouldDrawSideListener.class, this);
		EVENTS.remove(RenderBlockEntityListener.class, this);
		
		// reload chunks
		MC.levelRenderer.allChanged();
		
		// reset gamma
		FullbrightHack fullbright = WURST.getHax().fullbrightHack;
		if(!fullbright.isChangingGamma())
			ISimpleOption.get(MC.options.gamma())
				.forceSetValue(fullbright.getDefaultGamma());
	}
	
	@Override
	public void onUpdate()
	{
		// update chunk searchers (background search)
		boolean changed = coordinator.update();
		int matchesVersion = coordinator.getMatchesVersion();
		boolean resultsChanged = matchesVersion != lastMatchesVersion;
		if(resultsChanged)
			lastMatchesVersion = matchesVersion;
		if(changed || resultsChanged)
		{
			highlightPositionsUpToDate = false;
			visibleBoxesUpToDate = false;
			// Record change timestamp and DO NOT immediately clear cached
			// boxes.
			// This avoids flicker during quick user actions; boxes will be
			// refreshed when new data arrives or after debounce.
			lastCoordinatorChangeMs = System.currentTimeMillis();
		}
		
		// force gamma to 16 so that ores are bright enough to see
		ISimpleOption.get(MC.options.gamma()).forceSetValue(16.0);
		// Live-apply changes to list and opacity
		// Detect mode changes and handle switching
		Mode curMode = mode.getSelected();
		if(curMode != lastMode)
		{
			lastMode = curMode;
			if(curMode == Mode.LIST)
			{
				oreNamesCache = new ArrayList<>(ores.getBlockNames());
				lastOresHash = ores.getBlockNames().hashCode();
				rebuildOreCaches();
				// reset coordinator & highlights so we immediately reflect LIST
				// mode
				resetCoordinatorAndHighlights();
				MC.levelRenderer.allChanged();
			}else // switched to QUERY
			{
				oreNamesCache = null; // avoid fallback to list
				oreExactIds = null;
				String q = query.getValue();
				if(q == null || q.isBlank())
					oreKeywords = new String[0];
				else
					oreKeywords = Stream.of(q.split(","))
						.map(s -> s.trim().toLowerCase(java.util.Locale.ROOT))
						.filter(s -> !s.isEmpty()).toArray(String[]::new);
				// reset coordinator & highlights so we immediately reflect
				// QUERY mode
				resetCoordinatorAndHighlights();
				MC.levelRenderer.allChanged();
			}
		}
		
		if(curMode == Mode.LIST)
		{
			int currentHash = ores.getBlockNames().hashCode();
			if(currentHash != lastOresHash)
			{
				lastOresHash = currentHash;
				oreNamesCache = new ArrayList<>(ores.getBlockNames());
				rebuildOreCaches();
				MC.levelRenderer.allChanged();
			}else
			{
				// safety: if caches are missing (e.g., after a reload), rebuild
				// them
				if(oreExactIds == null || oreKeywords == null)
					rebuildOreCaches();
			}
		}else // QUERY mode
		{
			String q = query.getValue();
			// parse and update keywords if changed
			if(q == null)
				q = "";
			String[] newKw = Stream.of(q.split(","))
				.map(s -> s.trim().toLowerCase(java.util.Locale.ROOT))
				.filter(s -> !s.isEmpty()).toArray(String[]::new);
			boolean kwChanged = false;
			if(oreKeywords == null || oreKeywords.length != newKw.length)
				kwChanged = true;
			else
			{
				for(int i = 0; i < newKw.length; i++)
					if(!oreKeywords[i].equals(newKw[i]))
					{
						kwChanged = true;
						break;
					}
			}
			if(kwChanged)
			{
				oreKeywords = newKw;
				oreExactIds = null; // force keyword path
				MC.levelRenderer.allChanged();
			}
		}
		
		double currentOpacity = opacity.getValue();
		if(currentOpacity != lastOpacityVal)
		{
			lastOpacityVal = currentOpacity;
			MC.levelRenderer.allChanged();
		}
		// Detect only-exposed toggle changes and reload chunks so mixins
		// re-evaluate visibility based on the new setting.
		boolean curOnly = onlyExposed.isChecked();
		if(curOnly != lastOnlyExposed)
		{
			lastOnlyExposed = curOnly;
			// Rebuild visible boxes immediately from known positions so the
			// ESP updates without waiting for a full coordinator pass.
			rebuildVisibleBoxes();
			MC.levelRenderer.allChanged();
		}
	}
	
	@Override
	public void onRender(PoseStack matrices, float partialTicks)
	{
		long now = System.currentTimeMillis();
		
		// If coordinator finished, refresh highlight positions immediately.
		if(!highlightPositionsUpToDate && coordinator.isDone())
		{
			highlightPositions.clear();
			// collect nearest N positions (limit controlled by renderAmount)
			BlockPos playerPos = MC.player.blockPosition();
			java.util.Comparator<BlockPos> comparator = java.util.Comparator
				.comparingInt(p -> playerPos.distManhattan(p));
			coordinator.getMatches().map(r -> r.pos()).sorted(comparator)
				.limit(renderAmount.getValueLog())
				.forEach(highlightPositions::add);
			highlightPositionsUpToDate = true;
			visibleBoxesUpToDate = false;
		}
		
		// Rebuild visible boxes if needed. Use debounce to avoid flicker: if
		// rebuild would create an empty set but the coordinator just changed
		// recently, keep existing boxes until debounce expires.
		if(!visibleBoxesUpToDate)
		{
			java.util.List<AABB> newBoxes = new java.util.ArrayList<>();
			for(BlockPos p : highlightPositions)
			{
				if(onlyExposed.isChecked() && !isExposed(p))
					continue;
				newBoxes.add(new AABB(p));
			}
			
			// If newBoxes empty but coordinator changed very recently, skip
			// replacing to avoid flicker.
			if(newBoxes.isEmpty()
				&& now - lastCoordinatorChangeMs < COORDINATOR_DEBOUNCE_MS)
			{
				// keep previous visibleBoxes until debounce expires
			}else
			{
				visibleBoxes = newBoxes;
				visibleBoxesUpToDate = true;
			}
		}
		
		if(visibleBoxes.isEmpty())
			return;
		
		int color = getHighlightColorWithAlpha();
		if(highlightFill.isChecked())
		{
			int fullAlpha = (color >>> 24) & 0xFF;
			int halfAlpha = Math.max(1, fullAlpha / 2);
			int rgb = color & 0x00FFFFFF;
			int solidColor = (halfAlpha << 24) | rgb;
			RenderUtils.drawSolidBoxes(matrices, visibleBoxes, solidColor,
				false);
		}
		if(highlightCorners.isChecked())
			RenderUtils.drawOutlinedBoxes(matrices, visibleBoxes, color, false);
	}
	
	private void rebuildVisibleBoxes()
	{
		visibleBoxes.clear();
		for(BlockPos p : highlightPositions)
		{
			if(onlyExposed.isChecked() && !isExposed(p))
				continue;
			visibleBoxes.add(new AABB(p));
		}
		visibleBoxesUpToDate = true;
	}
	
	@Override
	public void onSetOpaqueCube(SetOpaqueCubeEvent event)
	{
		event.cancel();
	}
	
	@Override
	public void onGetAmbientOcclusionLightLevel(
		GetAmbientOcclusionLightLevelEvent event)
	{
		event.setLightLevel(1);
	}
	
	@Override
	public void onShouldDrawSide(ShouldDrawSideEvent event)
	{
		boolean visible =
			isVisible(event.getState().getBlock(), event.getPos());
		if(!visible && opacity.getValue() > 0)
			return;
		
		event.setRendered(visible);
	}
	
	@Override
	public void onRenderBlockEntity(RenderBlockEntityEvent event)
	{
		BlockPos pos = event.getBlockEntity().getBlockPos();
		if(!isVisible(BlockUtils.getBlock(pos), pos))
			event.cancel();
	}
	
	// add fallback when caches are null
	public boolean isVisible(Block block, BlockPos pos)
	{
		String idFull = BlockUtils.getName(block);
		
		boolean visible = false;
		// Behavior depends on mode
		Mode cur = mode.getSelected();
		if(cur == Mode.LIST)
		{
			// exact ID set (preferred fast path)
			if(oreExactIds != null)
				visible = oreExactIds.contains(idFull);
			// fallback to original list if caches aren't ready yet
			if(!visible && oreExactIds == null && oreNamesCache != null)
				visible = oreNamesCache.contains(idFull);
		}else // QUERY mode
		{
			if(oreKeywords != null && oreKeywords.length > 0)
			{
				String localId = idFull.contains(":")
					? idFull.substring(idFull.indexOf(":") + 1) : idFull;
				String localSpaced = localId.replace('_', ' ');
				String transKey = block.getDescriptionId();
				String display = block.getName().getString();
				for(String term : oreKeywords)
					if(containsNormalized(idFull, term)
						|| containsNormalized(localId, term)
						|| containsNormalized(localSpaced, term)
						|| containsNormalized(transKey, term)
						|| containsNormalized(display, term))
					{
						visible = true;
						break;
					}
			}
		}
		
		if(visible && onlyExposed.isChecked() && pos != null)
			return isExposed(pos);
		
		return visible;
	}
	
	private boolean isExposed(BlockPos pos)
	{
		BlockPos.MutableBlockPos mutablePos = mutablePosForExposedCheck.get();
		for(Direction direction : Direction.values())
			if(!BlockUtils
				.isOpaqueFullCube(mutablePos.setWithOffset(pos, direction)))
				return true;
			
		return false;
	}
	
	private void rebuildOreCaches()
	{
		java.util.HashSet<String> exact = new java.util.HashSet<>();
		java.util.ArrayList<String> kw = new java.util.ArrayList<>();
		for(String s : oreNamesCache)
		{
			net.minecraft.resources.ResourceLocation id =
				net.minecraft.resources.ResourceLocation.tryParse(s);
			if(id != null)
				exact.add(id.toString());
			else if(s != null && !s.isBlank())
				kw.add(s.toLowerCase(java.util.Locale.ROOT));
		}
		oreExactIds = exact;
		oreKeywords = kw.toArray(new String[0]);
	}
	
	private static boolean containsNormalized(String haystack, String needle)
	{
		return haystack != null
			&& haystack.toLowerCase(java.util.Locale.ROOT).contains(needle);
	}
	
	// Public API used by rendering mixins
	public boolean isOpacityMode()
	{
		return isEnabled() && opacity.getValue() > 0;
	}
	
	public int getOpacityColorMask()
	{
		int a = Math.max(0,
			Math.min(255, (int)Math.round(opacity.getValue() * 255)));
		if(a == 0)
			a = 1; // avoid fully-zero alpha which can trigger renderer
					// edge-cases
		return (a << 24) | 0x00FFFFFF;
	}
	
	public float getOpacityFloat()
	{
		return opacity.getValueF();
	}
	
	// New public API for corner highlight ESP and block transparency override
	public boolean isHighlightCornersEnabled()
	{
		return isEnabled() && highlightCorners.isChecked();
	}
	
	public int getHighlightColorWithAlpha()
	{
		int v = (int)Math.round(highlightAlpha.getValue());
		v = Math.max(0, Math.min(100, v));
		int alpha = (int)Math.round(v / 100.0 * 255);
		int rgb = highlightColor.getColorI() & 0x00FFFFFF;
		return (alpha << 24) | rgb;
	}
	
	public float getHighlightAlphaFloat()
	{
		return (float)(Math.max(1,
			Math.min(100, (int)Math.round(highlightAlpha.getValue()))) / 100.0);
	}
	
	/**
	 * Checks if OptiFine/OptiFabric is installed and returns a warning message
	 * if it is.
	 */
	private String checkOptiFine()
	{
		Stream<String> mods = FabricLoader.getInstance().getAllMods().stream()
			.map(ModContainer::getMetadata).map(ModMetadata::getId);
		
		Pattern optifine = Pattern.compile("opti(?:fine|fabric).*");
		
		if(mods.anyMatch(optifine.asPredicate()))
			return "OptiFine is installed. X-Ray will not work properly!";
		
		return null;
	}
	
	public void openBlockListEditor(Screen prevScreen)
	{
		MC.setScreen(new EditBlockListScreen(prevScreen, ores));
	}
	
	// See AbstractBlockRenderContextMixin, RenderLayersMixin
	
	private void resetCoordinatorAndHighlights()
	{
		// Cancel current searches and clear cached highlights to avoid stale
		// results persisting after mode/list/query changes.
		coordinator.reset();
		lastMatchesVersion = coordinator.getMatchesVersion();
		highlightPositions.clear();
		visibleBoxes.clear();
		highlightPositionsUpToDate = false;
		visibleBoxesUpToDate = false;
		lastCoordinatorChangeMs = System.currentTimeMillis();
	}
}
