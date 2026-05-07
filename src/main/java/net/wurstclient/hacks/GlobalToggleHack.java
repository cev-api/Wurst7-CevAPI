/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Map;

import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.AboveGroundFilterManager;
import net.wurstclient.hack.CheckboxOverrideManager;
import net.wurstclient.hack.Hack;
import net.wurstclient.render.globalesp.GlobalEspRenderMode;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.chunk.ChunkSearcher;

@SearchTags({"global toggle", "render global toggle"})
public final class GlobalToggleHack extends Hack implements UpdateListener
{
	private static final int HARD_GLOBAL_ESP_RENDER_LIMIT = 100_000;
	
	private final CheckboxSetting stickyAreaOverride = new CheckboxSetting(
		"Sticky area override",
		"Forces sticky area on for all supported hacks while enabled.", false);
	
	private final CheckboxSetting yLimitOverride = new CheckboxSetting(
		"Y limit override",
		"Forces the above-ground filter on for all supported hacks while enabled.",
		false);
	private final SliderSetting yLimitValue = new SliderSetting(
		"Global Y limit", 62, -65, 255, 1, ValueDisplay.INTEGER);
	private final SliderSetting searchThreadPriority = new SliderSetting(
		"Search thread priority",
		"Global background thread priority for Search/ESP/X-Ray chunk scanning.",
		ChunkSearcher.getBackgroundThreadPriority(), Thread.MIN_PRIORITY,
		Thread.MAX_PRIORITY, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting setSliderLimitOverride = new CheckboxSetting(
		".setslider limit override",
		"Allows .setslider to set values beyond slider min/max limits. UI sliders stay clamped.",
		false);
	private final EnumSetting<ChunkScanMode> chunkScanMode = new EnumSetting<>(
		"Chunk scan mode",
		"FULL: Update only when full area scan is done (old behavior).\n"
			+ "PARTIAL: Update from ready chunks immediately (faster detection).\n"
			+ "AUTO: Use FULL while slow and PARTIAL while moving fast.",
		ChunkScanMode.values(), ChunkScanMode.FULL);
	private final SliderSetting autoPartialSpeed = new SliderSetting(
		"Auto partial speed",
		"Movement speed in blocks/second where AUTO chunk scan mode switches to PARTIAL.",
		80, 5, 300, 5, ValueDisplay.INTEGER.withSuffix(" b/s"));
	private final EnumSetting<EspPriorityMode> espPriorityMode =
		new EnumSetting<>("ESP priority",
			"OFF: Global ESP limits apply normally.\n"
				+ "PORTALS_AND_CHESTS: PortalESP and ChestESP bypass global render limits and keep scanning warm while moving fast.",
			EspPriorityMode.values(), EspPriorityMode.OFF);
	private final EnumSetting<GlobalEspRenderMode> globalEspRenderMode =
		new EnumSetting<>("Global ESP render mode",
			"LEGACY: existing per-hack draw path.\n"
				+ "SHADER_OUTLINE: centralized global ESP pipeline.",
			GlobalEspRenderMode.values(), GlobalEspRenderMode.LEGACY);
	private final SliderSetting globalEspRenderLimit = new SliderSetting(
		"Global ESP render limit",
		"Max ESP targets rendered per frame across supported ESP features.\n"
			+ "0 = unlimited",
		0, 0, 100_000, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting globalEspRenderLimitEnabled =
		new CheckboxSetting("Enable global ESP render limit",
			"When disabled, the global ESP render-limit slider is ignored.",
			false);
	private final CheckboxSetting disableAllTracers = new CheckboxSetting(
		"Disable all tracers",
		"Globally hides tracer lines from all hacks without changing each hack's own settings.",
		false);
	private final CheckboxSetting whitelistChestEspTracers =
		new CheckboxSetting("Whitelist ChestESP tracers",
			"When 'Disable all tracers' is on, still allow ChestESP tracers.",
			true);
	private final CheckboxSetting whitelistPlayerEspTracers =
		new CheckboxSetting("Whitelist PlayerESP tracers",
			"When 'Disable all tracers' is on, still allow PlayerESP tracers.",
			true);
	private final CheckboxSetting whitelistPortalEspTracers =
		new CheckboxSetting("Whitelist PortalESP tracers",
			"When 'Disable all tracers' is on, still allow PortalESP tracers.",
			true);
	private final CheckboxSetting whitelistPlayerSonarTracers =
		new CheckboxSetting("Whitelist PlayerSonar tracers",
			"When 'Disable all tracers' is on, still allow PlayerSonar tracers.",
			true);
	
	private Map<CheckboxSetting, Boolean> stickySnapshot = Map.of();
	
	private boolean lastStickyOverride;
	private boolean lastYLimitOverride;
	private int lastYLimitValue = 62;
	private int lastSearchThreadPriority =
		ChunkSearcher.getBackgroundThreadPriority();
	private boolean lastDisableAllTracers;
	private boolean tracerStateInitialized;
	private Vec3 lastPlayerPos;
	private double playerSpeedBlocksPerSecond;
	
	public GlobalToggleHack()
	{
		super("GlobalToggle");
		setCategory(Category.OTHER);
		
		addSetting(stickyAreaOverride);
		addSetting(yLimitOverride);
		addSetting(yLimitValue);
		addSetting(searchThreadPriority);
		addSetting(setSliderLimitOverride);
		addSetting(chunkScanMode);
		addSetting(autoPartialSpeed);
		addSetting(espPriorityMode);
		addSetting(globalEspRenderMode);
		addSetting(globalEspRenderLimitEnabled);
		addSetting(globalEspRenderLimit);
		addSetting(disableAllTracers);
		addSetting(whitelistChestEspTracers);
		addSetting(whitelistPlayerEspTracers);
		addSetting(whitelistPortalEspTracers);
		addSetting(whitelistPlayerSonarTracers);
		
		addPossibleKeybind(".globaltoggle tracers",
			"Toggle GlobalToggle's tracer suppression");
		
		lastYLimitValue = yLimitValue.getValueI();
		lastSearchThreadPriority = searchThreadPriority.getValueI();
		
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onEnable()
	{
		setEnabled(false);
	}
	
	@Override
	public void onUpdate()
	{
		var hacks = WURST.getHax();
		updatePlayerSpeed();
		
		// Sticky override --------------------------------------------------
		boolean stickyOverride = stickyAreaOverride.isChecked();
		if(stickyOverride != lastStickyOverride)
		{
			if(lastStickyOverride)
				CheckboxOverrideManager.restore(stickySnapshot);
			
			if(stickyOverride)
			{
				stickySnapshot =
					CheckboxOverrideManager.capture(hacks, "stickyArea");
				CheckboxOverrideManager.apply(hacks, "stickyArea", true);
				ChatUtils.message("Global sticky area override forcing ON.");
			}else
			{
				stickySnapshot = Map.of();
				ChatUtils.message("Global sticky area override disabled.");
			}
			
			lastStickyOverride = stickyOverride;
		}
		
		// Y limit override -------------------------------------------------
		boolean yOverride = yLimitOverride.isChecked();
		int yValue = yLimitValue.getValueI();
		
		if(yOverride != lastYLimitOverride)
		{
			if(yOverride)
			{
				CheckboxOverrideManager.apply(hacks, "onlyAboveGround", true);
				AboveGroundFilterManager.setY(hacks, yValue);
				ChatUtils.message("Global Y limit override forcing ON.");
			}else
			{
				AboveGroundFilterManager.toggle(hacks, false);
				ChatUtils.message("Global Y limit override disabled.");
			}
			
			lastYLimitOverride = yOverride;
		}
		
		if(yOverride && yValue != lastYLimitValue)
		{
			AboveGroundFilterManager.setY(hacks, yValue);
			lastYLimitValue = yValue;
		}else if(yValue != lastYLimitValue)
		{
			lastYLimitValue = yValue;
		}
		
		int priority = searchThreadPriority.getValueI();
		if(priority != lastSearchThreadPriority)
		{
			ChunkSearcher.setBackgroundThreadPriority(priority);
			lastSearchThreadPriority = priority;
		}
		
		boolean suppressTracers = disableAllTracers.isChecked();
		if(!tracerStateInitialized)
		{
			lastDisableAllTracers = suppressTracers;
			tracerStateInitialized = true;
		}else if(suppressTracers != lastDisableAllTracers)
		{
			ChatUtils.message(suppressTracers ? "All tracers disabled globally."
				: "Global tracer suppression disabled.");
			lastDisableAllTracers = suppressTracers;
		}
	}
	
	public boolean usePartialChunkScan()
	{
		return switch(chunkScanMode.getSelected())
		{
			case FULL -> false;
			case PARTIAL -> true;
			case AUTO -> playerSpeedBlocksPerSecond >= autoPartialSpeed
				.getValue();
		};
	}
	
	public double getPlayerSpeedBlocksPerSecond()
	{
		return playerSpeedBlocksPerSecond;
	}
	
	public boolean shouldKeepPriorityEspWarm(String source)
	{
		return isPriorityEsp(source)
			&& playerSpeedBlocksPerSecond >= autoPartialSpeed.getValue();
	}
	
	public boolean isPriorityEsp(String source)
	{
		if(source == null
			|| espPriorityMode.getSelected() == EspPriorityMode.OFF)
			return false;
		
		return switch(espPriorityMode.getSelected())
		{
			case OFF -> false;
			case PORTALS_AND_CHESTS -> switch(source.toLowerCase())
			{
				case "portalesp", "chestesp" -> true;
				default -> false;
			};
		};
	}
	
	public boolean isSetSliderLimitOverrideAllowed()
	{
		return setSliderLimitOverride.isChecked();
	}
	
	public GlobalEspRenderMode getGlobalEspRenderMode()
	{
		return globalEspRenderMode.getSelected();
	}
	
	public int getGlobalEspRenderLimit()
	{
		return globalEspRenderLimit.getValueI();
	}
	
	public int getEffectiveGlobalEspRenderLimit()
	{
		if(!globalEspRenderLimitEnabled.isChecked())
			return 0;
		
		return Math.min(HARD_GLOBAL_ESP_RENDER_LIMIT,
			Math.max(0, getGlobalEspRenderLimit()));
	}
	
	public int getEffectiveGlobalEspRenderLimit(String source)
	{
		if(isPriorityEsp(source))
			return 0;
		
		return getEffectiveGlobalEspRenderLimit();
	}
	
	public int applyGlobalEspRenderLimit(int localLimit)
	{
		int globalLimit = getEffectiveGlobalEspRenderLimit();
		if(localLimit <= 0 || globalLimit <= 0)
			return localLimit;
		
		return Math.min(localLimit, globalLimit);
	}
	
	public int applyGlobalEspRenderLimit(String source, int localLimit)
	{
		int globalLimit = getEffectiveGlobalEspRenderLimit(source);
		if(localLimit <= 0 || globalLimit <= 0)
			return localLimit;
		
		return Math.min(localLimit, globalLimit);
	}
	
	public boolean isGlobalEspRenderLimitEnabled()
	{
		return globalEspRenderLimitEnabled.isChecked();
	}
	
	public boolean areAllTracersDisabled()
	{
		return disableAllTracers.isChecked();
	}
	
	public void toggleAllTracers()
	{
		disableAllTracers.setChecked(!disableAllTracers.isChecked());
	}
	
	public void setAllTracersDisabled(boolean disabled)
	{
		disableAllTracers.setChecked(disabled);
	}
	
	public boolean isTracerSourceWhitelisted(String source)
	{
		if(source == null)
			return false;
		
		return switch(source.toLowerCase())
		{
			case "chestesp" -> whitelistChestEspTracers.isChecked();
			case "playeresp" -> whitelistPlayerEspTracers.isChecked();
			case "portalesp" -> whitelistPortalEspTracers.isChecked();
			case "playersonar" -> whitelistPlayerSonarTracers.isChecked();
			default -> false;
		};
	}
	
	private enum ChunkScanMode
	{
		FULL,
		PARTIAL,
		AUTO;
	}
	
	private enum EspPriorityMode
	{
		OFF("Off"),
		PORTALS_AND_CHESTS("Portals + Chests");
		
		private final String name;
		
		private EspPriorityMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private void updatePlayerSpeed()
	{
		if(MC.player == null)
		{
			lastPlayerPos = null;
			playerSpeedBlocksPerSecond = 0;
			return;
		}
		
		Vec3 pos = MC.player.position();
		if(lastPlayerPos == null)
		{
			lastPlayerPos = pos;
			playerSpeedBlocksPerSecond = 0;
			return;
		}
		
		playerSpeedBlocksPerSecond = pos.distanceTo(lastPlayerPos) * 20.0;
		lastPlayerPos = pos;
	}
}
