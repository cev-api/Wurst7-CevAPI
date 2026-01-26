/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.wurstclient.WurstClient;
import net.wurstclient.other_features.ConnectionLogOverlayOtf;

public final class ConnectionLogOverlay
{
	private static final int MAX_STORED_LINES = 128;
	private static final int MAX_VISIBLE_LINES = 12;
	private static final int HORIZONTAL_MARGIN = 8;
	private static final int VERTICAL_MARGIN = 8;
	private static final int BOX_PADDING = 6;
	private static final int LINE_SPACING = 2;
	private static final DateTimeFormatter TIME_FORMATTER =
		DateTimeFormatter.ofPattern("HH:mm:ss");
	private static final ZoneId ZONE = ZoneId.systemDefault();
	private static final ConnectionLogOverlay INSTANCE =
		new ConnectionLogOverlay();
	
	private final Deque<String> lines = new ArrayDeque<>(MAX_STORED_LINES);
	
	private ConnectionLogOverlay()
	{
		registerAppender();
	}
	
	public static ConnectionLogOverlay getInstance()
	{
		return INSTANCE;
	}
	
	public void render(GuiGraphics graphics)
	{
		if(!shouldRender())
			return;
		
		Minecraft mc = WurstClient.MC;
		if(mc == null)
			return;
		
		Font font = mc.font;
		int scaledWidth = mc.getWindow().getGuiScaledWidth();
		List<String> visibleLines = getVisibleLines();
		if(visibleLines.isEmpty())
			return;
		
		int availableWidth =
			Math.max(64, scaledWidth - HORIZONTAL_MARGIN * 2 - BOX_PADDING * 2);
		List<FormattedCharSequence> split = new ArrayList<>();
		for(String line : visibleLines)
		{
			if(line == null)
				continue;
			List<FormattedCharSequence> fragments =
				font.split(Component.literal(line), availableWidth);
			if(fragments.isEmpty())
				continue;
			split.addAll(fragments);
		}
		
		if(split.isEmpty())
			return;
		
		int maxLineWidth = split.stream().mapToInt(font::width).max().orElse(0);
		if(maxLineWidth <= 0)
			return;
		
		int boxWidth = Math.min(maxLineWidth + BOX_PADDING * 2,
			scaledWidth - HORIZONTAL_MARGIN * 2);
		if(boxWidth <= 0)
			return;
		
		int textHeight = split.size() * font.lineHeight
			+ Math.max(0, split.size() - 1) * LINE_SPACING;
		int boxHeight = textHeight + BOX_PADDING * 2;
		if(boxHeight <= 0)
			return;
		
		int x = HORIZONTAL_MARGIN;
		int y = VERTICAL_MARGIN;
		
		graphics.fill(x, y, x + boxWidth, y + boxHeight, 0x95000000);
		
		int textX = x + BOX_PADDING;
		int textY = y + BOX_PADDING;
		for(FormattedCharSequence line : split)
		{
			graphics.drawString(font, line, textX, textY, 0xFFFFFFFF, false);
			textY += font.lineHeight + LINE_SPACING;
		}
	}
	
	private boolean shouldRender()
	{
		if(!WurstClient.INSTANCE.isEnabled())
			return false;
		
		ConnectionLogOverlayOtf otf =
			WurstClient.INSTANCE.getOtfs().connectionLogOverlayOtf;
		return otf != null && otf.isConnectionLogEnabled();
	}
	
	private void registerAppender()
	{
		LoggerContext context = (LoggerContext)LogManager.getContext(false);
		Configuration config = context.getConfiguration();
		
		AbstractAppender appender = new AbstractAppender(
			"WurstConnectionLogOverlay", null, (Layout<?>)null, true, null)
		{
			@Override
			public void append(LogEvent event)
			{
				if(event == null || event.getLevel() == null)
					return;
				
				String message = event.getMessage() == null ? ""
					: event.getMessage().getFormattedMessage();
				if(message == null || message.isBlank())
					return;
				
				String sanitized = message.split("\\R", 2)[0].trim();
				if(sanitized.isEmpty())
					return;
				
				addLine(formatLine(event, sanitized));
			}
		};
		
		appender.start();
		config.addAppender(appender);
		LoggerConfig rootLogger = config.getRootLogger();
		rootLogger.addAppender(appender, null, null);
		context.updateLoggers();
	}
	
	private String formatLine(LogEvent event, String base)
	{
		String timestamp = TIME_FORMATTER
			.format(Instant.ofEpochMilli(event.getTimeMillis()).atZone(ZONE));
		String thread = event.getThreadName();
		if(thread == null || thread.isEmpty())
			thread = "????";
		String level = event.getLevel().name();
		return "[" + timestamp + "] [" + thread + "/" + level + "]: " + base;
	}
	
	private void addLine(String line)
	{
		synchronized(lines)
		{
			if(lines.size() >= MAX_STORED_LINES)
				lines.removeFirst();
			lines.addLast(line);
		}
	}
	
	private List<String> getVisibleLines()
	{
		synchronized(lines)
		{
			if(lines.isEmpty())
				return List.of();
			
			List<String> all = new ArrayList<>(lines);
			int start = Math.max(0, all.size() - MAX_VISIBLE_LINES);
			return new ArrayList<>(all.subList(start, all.size()));
		}
	}
}
