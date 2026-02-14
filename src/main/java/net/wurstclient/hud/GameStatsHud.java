/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hud;

import com.mojang.blaze3d.platform.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.EntityType;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.GameStatsHack;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.ServerObserver;

public final class GameStatsHud
{
	private static final Minecraft MC = WurstClient.MC;
	private static final float BASE_X = 8F;
	private static final float BASE_Y = 8F;
	private static final float PADDING = 4F;
	private static final float LINE_GAP = 2F;
	private static final long SAMPLE_INTERVAL_MS = 1000L;
	private static final DateTimeFormatter TIME_FORMAT =
		DateTimeFormatter.ofPattern("HH:mm:ss");
	
	private final GameStatsHack hack;
	
	private boolean wasEnabled;
	private long sessionStartMs;
	private long lastSampleMs;
	
	private long fpsSampleCount;
	private long pingSampleCount;
	private long tpsSampleCount;
	
	private double fpsSampleSum;
	private double pingSampleSum;
	private double tpsSampleSum;
	private double distanceTravelledMeters;
	private boolean hasLastPos;
	private double lastX;
	private double lastY;
	private double lastZ;
	private int mobKillsBaseline = -1;
	private int playerKillsBaseline = -1;
	private int xpBaseline = -1;
	
	private float offsetX;
	private float offsetY;
	private boolean dragging;
	private double dragStartMouseX;
	private double dragStartMouseY;
	private float dragStartOffsetX;
	private float dragStartOffsetY;
	
	public GameStatsHud(GameStatsHack hack)
	{
		this.hack = hack;
	}
	
	public void render(GuiGraphics context)
	{
		if(MC == null || hack == null)
			return;
		
		boolean enabled = hack.isEnabled();
		if(enabled && !wasEnabled)
			resetSessionStats();
		wasEnabled = enabled;
		
		if(!enabled)
			return;
		
		Font font = MC.font;
		if(font == null)
			return;
		
		updateSessionTracking();
		updateAverages();
		
		double scale = hack.getFontScale();
		List<String> lines = buildLines();
		if(lines.isEmpty())
			return;
		
		float maxTextWidth = 0F;
		for(String line : lines)
			maxTextWidth =
				Math.max(maxTextWidth, (float)(font.width(line) * scale));
		
		float lineHeight = (float)(font.lineHeight * scale);
		float textHeight = lines.size() * lineHeight
			+ Math.max(0, lines.size() - 1) * LINE_GAP;
		
		float boxWidth = maxTextWidth + PADDING * 2F;
		float boxHeight = textHeight + PADDING * 2F;
		
		float x = BASE_X + offsetX;
		float y = BASE_Y + offsetY;
		
		handleDrag(context, x, y, boxWidth, boxHeight);
		
		x = BASE_X + offsetX;
		y = BASE_Y + offsetY;
		
		if(hack.hasBackgroundBox())
		{
			int bgColor = withAlpha(hack.getBackgroundColorI(),
				hack.getBackgroundOpacity());
			RenderUtils.fill2D(context, x, y, x + boxWidth, y + boxHeight,
				bgColor);
		}
		
		int fontColor = withAlpha(hack.getFontColorI(), hack.getFontOpacity());
		int strokeColor = withAlpha(0xFF000000,
			Math.min(255, (int)Math.round(hack.getFontOpacity() * 0.9)));
		
		float drawY = y + PADDING;
		for(String line : lines)
		{
			float drawX = x + PADDING;
			int ix = Math.round(drawX);
			int iy = Math.round(drawY);
			
			if(hack.hasFontStroke())
				drawStrokeText(context, font, line, ix, iy, strokeColor, scale);
			
			RenderUtils.drawScaledText(context, font, line, ix, iy, fontColor,
				false, scale);
			drawY += lineHeight + LINE_GAP;
		}
	}
	
	private List<String> buildLines()
	{
		ArrayList<String> lines = new ArrayList<>(10);
		boolean showPrefixes = hack.showPrefixes();
		boolean showAverages = hack.showAverages();
		
		if(hack.showFps())
		{
			int fps = MC.getFps();
			String fpsValue = Integer.toString(fps);
			if(showAverages && fpsSampleCount > 0)
				fpsValue +=
					" (" + formatRounded(fpsSampleSum / fpsSampleCount) + ")";
			lines.add(withPrefix(showPrefixes, "FPS", fpsValue));
		}
		
		if(hack.showTps())
		{
			double tps = getServerTps();
			String tpsValue = Double.isNaN(tps) ? "--"
				: String.format(Locale.ROOT, "%.2f", tps);
			if(showAverages && tpsSampleCount > 0)
				tpsValue += " (" + String.format(Locale.ROOT, "%.2f",
					tpsSampleSum / tpsSampleCount) + ")";
			lines.add(withPrefix(showPrefixes, "TPS", tpsValue));
		}
		
		if(hack.showPing())
		{
			int ping = getPlayerPing();
			String pingValue = ping < 0 ? "--" : Integer.toString(ping);
			if(showAverages && pingSampleCount > 0)
				pingValue +=
					" (" + formatRounded(pingSampleSum / pingSampleCount) + ")";
			lines.add(withPrefix(showPrefixes, "Ping", pingValue));
		}
		
		if(hack.showPlayTime())
			lines.add(withPrefix(showPrefixes, "Play Time",
				formatDurationMs(elapsedSessionMs())));
		
		if(hack.showCurrentTime())
			lines.add(withPrefix(showPrefixes, "Time",
				LocalTime.now().format(TIME_FORMAT)));
		
		if(hack.showPacketRate())
			lines.add(
				withPrefix(showPrefixes, "Packets", hack.getIncomingPacketRate()
					+ "/" + hack.getOutgoingPacketRate()));
		
		if(hack.showDistanceTravelled())
			lines.add(withPrefix(showPrefixes, "Distance",
				formatDistance(distanceTravelledMeters)));
		
		if(hack.showMobKills())
			lines.add(withPrefix(showPrefixes, "Mob Kills",
				Integer.toString(getSessionMobKills())));
		
		if(hack.showPlayerKills())
			lines.add(withPrefix(showPrefixes, "Player Kills",
				Integer.toString(getSessionPlayerKills())));
		
		if(hack.showXpGained())
			lines.add(withPrefix(showPrefixes, "XP Gained",
				Integer.toString(getSessionXpGained())));
		
		return lines;
	}
	
	private void updateSessionTracking()
	{
		if(MC.player == null)
		{
			hasLastPos = false;
			return;
		}
		
		double x = MC.player.getX();
		double y = MC.player.getY();
		double z = MC.player.getZ();
		if(hasLastPos)
		{
			double dx = x - lastX;
			double dy = y - lastY;
			double dz = z - lastZ;
			double delta = Math.sqrt(dx * dx + dy * dy + dz * dz);
			if(Double.isFinite(delta) && delta >= 0)
				distanceTravelledMeters += delta;
		}
		
		lastX = x;
		lastY = y;
		lastZ = z;
		hasLastPos = true;
		
		if(mobKillsBaseline < 0)
			mobKillsBaseline = getCurrentMobKills();
		if(playerKillsBaseline < 0)
			playerKillsBaseline = getCurrentPlayerKills();
		if(xpBaseline < 0)
			xpBaseline = getCurrentTotalXp();
	}
	
	private void updateAverages()
	{
		long now = System.currentTimeMillis();
		if(now - lastSampleMs < SAMPLE_INTERVAL_MS)
			return;
		
		lastSampleMs = now;
		
		int fps = MC.getFps();
		if(fps >= 0)
		{
			fpsSampleSum += fps;
			fpsSampleCount++;
		}
		
		double tps = getServerTps();
		if(!Double.isNaN(tps))
		{
			tpsSampleSum += tps;
			tpsSampleCount++;
		}
		
		int ping = getPlayerPing();
		if(ping >= 0)
		{
			pingSampleSum += ping;
			pingSampleCount++;
		}
	}
	
	private void resetSessionStats()
	{
		long now = System.currentTimeMillis();
		sessionStartMs = now;
		lastSampleMs = now - SAMPLE_INTERVAL_MS;
		
		fpsSampleCount = 0L;
		pingSampleCount = 0L;
		tpsSampleCount = 0L;
		
		fpsSampleSum = 0;
		pingSampleSum = 0;
		tpsSampleSum = 0;
		
		distanceTravelledMeters = 0;
		hasLastPos = false;
		lastX = 0;
		lastY = 0;
		lastZ = 0;
		mobKillsBaseline = -1;
		playerKillsBaseline = -1;
		xpBaseline = -1;
	}
	
	private long elapsedSessionMs()
	{
		if(sessionStartMs <= 0)
			return 0;
		
		return Math.max(0, System.currentTimeMillis() - sessionStartMs);
	}
	
	private static String formatRounded(double value)
	{
		return Integer.toString((int)Math.round(value));
	}
	
	private static String formatDurationMs(long durationMs)
	{
		long totalSeconds = Math.max(0, durationMs / 1000L);
		long hours = totalSeconds / 3600L;
		long minutes = (totalSeconds % 3600L) / 60L;
		long seconds = totalSeconds % 60L;
		return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes,
			seconds);
	}
	
	private static String formatDistance(double meters)
	{
		return String.format(Locale.ROOT, "%.1f", meters);
	}
	
	private static String withPrefix(boolean showPrefix, String prefix,
		String value)
	{
		return showPrefix ? prefix + ": " + value : value;
	}
	
	private int getSessionMobKills()
	{
		int tracked = hack.getSessionMobKills();
		int current = getCurrentMobKills();
		if(current < 0 || mobKillsBaseline < 0)
			return tracked;
		int statsDelta = Math.max(0, current - mobKillsBaseline);
		return Math.max(tracked, statsDelta);
	}
	
	private int getSessionPlayerKills()
	{
		int tracked = hack.getSessionPlayerKills();
		int current = getCurrentPlayerKills();
		if(current < 0 || playerKillsBaseline < 0)
			return tracked;
		int statsDelta = Math.max(0, current - playerKillsBaseline);
		return Math.max(tracked, statsDelta);
	}
	
	private int getSessionXpGained()
	{
		int current = getCurrentTotalXp();
		if(current < 0 || xpBaseline < 0)
			return 0;
		return Math.max(0, current - xpBaseline);
	}
	
	private int getCurrentMobKills()
	{
		if(MC.player == null)
			return -1;
		
		try
		{
			int customMobKills = MC.player.getStats()
				.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS));
			int perEntityMobKills = 0;
			for(EntityType<?> type : BuiltInRegistries.ENTITY_TYPE)
			{
				if(type == EntityType.PLAYER)
					continue;
				
				perEntityMobKills += MC.player.getStats()
					.getValue(Stats.ENTITY_KILLED.get(type));
			}
			
			return Math.max(customMobKills, perEntityMobKills);
		}catch(Throwable ignored)
		{
			return -1;
		}
	}
	
	private int getCurrentPlayerKills()
	{
		if(MC.player == null)
			return -1;
		
		try
		{
			return MC.player.getStats()
				.getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS));
		}catch(Throwable ignored)
		{
			return -1;
		}
	}
	
	private int getCurrentTotalXp()
	{
		if(MC.player == null)
			return -1;
		
		try
		{
			return MC.player.totalExperience;
		}catch(Throwable ignored)
		{
			return -1;
		}
	}
	
	private double getServerTps()
	{
		ServerObserver observer = WurstClient.INSTANCE.getServerObserver();
		return observer != null ? observer.getTps() : Double.NaN;
	}
	
	private int getPlayerPing()
	{
		try
		{
			var handler = MC.getConnection();
			if(handler == null || MC.player == null)
				return -1;
			
			var entry = handler.getPlayerInfo(MC.player.getUUID());
			if(entry == null)
				return -1;
			
			try
			{
				return entry.getLatency();
			}catch(Throwable ignored)
			{}
			
			try
			{
				var m = entry.getClass().getMethod("getLatency");
				Object o = m.invoke(entry);
				if(o instanceof Integer i)
					return i;
				if(o instanceof Long l)
					return l.intValue();
			}catch(NoSuchMethodException ignored)
			{}
			
			try
			{
				var m = entry.getClass().getMethod("getLatencyMs");
				Object o = m.invoke(entry);
				if(o instanceof Integer i)
					return i;
				if(o instanceof Long l)
					return l.intValue();
			}catch(NoSuchMethodException ignored)
			{}
			
			try
			{
				var f = entry.getClass().getDeclaredField("latency");
				f.setAccessible(true);
				Object o = f.get(entry);
				if(o instanceof Integer i)
					return i;
				if(o instanceof Long l)
					return l.intValue();
			}catch(NoSuchFieldException ignored)
			{}
		}catch(Throwable ignored)
		{}
		
		return -1;
	}
	
	private void drawStrokeText(GuiGraphics context, Font font, String text,
		int x, int y, int strokeColor, double scale)
	{
		RenderUtils.drawScaledText(context, font, text, x - 1, y, strokeColor,
			false, scale);
		RenderUtils.drawScaledText(context, font, text, x + 1, y, strokeColor,
			false, scale);
		RenderUtils.drawScaledText(context, font, text, x, y - 1, strokeColor,
			false, scale);
		RenderUtils.drawScaledText(context, font, text, x, y + 1, strokeColor,
			false, scale);
	}
	
	private void handleDrag(GuiGraphics context, float x, float y, float width,
		float height)
	{
		boolean containerOpen = MC.screen instanceof AbstractContainerScreen<?>;
		if(!containerOpen)
		{
			dragging = false;
			return;
		}
		
		Window window = MC.getWindow();
		if(window == null)
		{
			dragging = false;
			return;
		}
		
		double mouseX = getScaledMouseX(context);
		double mouseY = getScaledMouseY(context);
		boolean leftDown = GLFW.glfwGetMouseButton(window.handle(),
			GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
		boolean overHud = mouseX >= x && mouseX <= x + width && mouseY >= y
			&& mouseY <= y + height;
		
		if(leftDown && overHud)
		{
			if(!dragging)
			{
				dragging = true;
				dragStartMouseX = mouseX;
				dragStartMouseY = mouseY;
				dragStartOffsetX = offsetX;
				dragStartOffsetY = offsetY;
			}
			
			offsetX = dragStartOffsetX + (float)(mouseX - dragStartMouseX);
			offsetY = dragStartOffsetY + (float)(mouseY - dragStartMouseY);
			return;
		}
		
		if(!leftDown)
			dragging = false;
	}
	
	private static double getScaledMouseX(GuiGraphics context)
	{
		Window window = MC.getWindow();
		if(window == null)
			return 0;
		
		return MC.mouseHandler.xpos() * context.guiWidth()
			/ window.getScreenWidth();
	}
	
	private static double getScaledMouseY(GuiGraphics context)
	{
		Window window = MC.getWindow();
		if(window == null)
			return 0;
		
		return MC.mouseHandler.ypos() * context.guiHeight()
			/ window.getScreenHeight();
	}
	
	private static int withAlpha(int rgb, int alpha)
	{
		return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0x00FFFFFF);
	}
}
