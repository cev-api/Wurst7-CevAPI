/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import com.mojang.blaze3d.platform.Window;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.util.Mth;
import net.wurstclient.WurstClient;
import net.wurstclient.other_features.PerformanceOverlayOtf;
import net.wurstclient.other_features.PerformanceOverlayOtf.SortMode;

public final class HackPerformanceOverlay
{
	private static final Minecraft MC = WurstClient.MC;
	private static final int HORIZONTAL_MARGIN = 8;
	private static final int VERTICAL_MARGIN = 8;
	private static final int BOX_PADDING = 6;
	private static final int LINE_SPACING = 1;
	private static final int GRAPH_HEIGHT = 34;
	private static final int GRAPH_GAP = 4;
	private static final long GRAPH_SAMPLE_INTERVAL_MS = 100L;
	private static final int GRAPH_MAX_SAMPLES = 240;
	private static final HackPerformanceOverlay INSTANCE =
		new HackPerformanceOverlay();
	private boolean dragging;
	private double dragStartMouseX;
	private double dragStartMouseY;
	private int dragStartOffsetX;
	private int dragStartOffsetY;
	private int dragOffsetX;
	private int dragOffsetY;
	private final double[] graphSamples = new double[GRAPH_MAX_SAMPLES];
	private int graphStart;
	private int graphSize;
	private long lastGraphSampleMs;
	
	private HackPerformanceOverlay()
	{}
	
	public static HackPerformanceOverlay getInstance()
	{
		return INSTANCE;
	}
	
	public void render(GuiGraphics graphics)
	{
		PerformanceOverlayOtf otf = getSettings();
		if(otf == null || !otf.isEnabled())
			return;
		
		Font font = WurstClient.MC.font;
		double fontScale = otf.getFontScale();
		int scaledLineHeight =
			Math.max(1, (int)Math.round(font.lineHeight * fontScale));
		int scaledLineSpacing =
			Math.max(1, (int)Math.round(LINE_SPACING * fontScale));
		int scaledWidth = WurstClient.MC.getWindow().getGuiScaledWidth();
		int scaledHeight = WurstClient.MC.getWindow().getGuiScaledHeight();
		HackPerformanceTracker.ListSnapshot snapshot = HackPerformanceTracker
			.getTopRows(otf.getMaxRows(), otf.getSortMode());
		if(snapshot.rows().isEmpty())
			return;
		
		if(otf.shouldShowGraph())
			updateGraphSample(snapshot.rows());
		
		int availableWidth = Math.max(120,
			scaledWidth - HORIZONTAL_MARGIN * 2 - BOX_PADDING * 2);
		ArrayList<String> lines =
			buildLines(snapshot.rows(), snapshot.usingWindowData(), otf);
		if(lines.isEmpty())
			return;
		
		int availableUnscaledWidth =
			Math.max(60, (int)Math.floor(availableWidth / fontScale));
		ArrayList<String> clipped = new ArrayList<>(lines.size());
		int maxLineWidth = 0;
		for(String line : lines)
		{
			String clippedLine =
				clipToWidth(font, line, availableUnscaledWidth);
			clipped.add(clippedLine);
			maxLineWidth = Math.max(maxLineWidth,
				(int)Math.round(font.width(clippedLine) * fontScale));
		}
		if(maxLineWidth <= 0)
			return;
		
		int boxWidth = Math.min(maxLineWidth + BOX_PADDING * 2,
			scaledWidth - HORIZONTAL_MARGIN * 2);
		int graphHeight =
			Math.max(10, (int)Math.round(GRAPH_HEIGHT * fontScale));
		int graphGap = Math.max(2, (int)Math.round(GRAPH_GAP * fontScale));
		int graphBlockHeight = 0;
		if(otf.shouldShowGraph())
			graphBlockHeight = scaledLineHeight + 1 + graphHeight + graphGap;
		
		int textHeight = clipped.size() * scaledLineHeight
			+ Math.max(0, clipped.size() - 1) * scaledLineSpacing;
		int boxHeight = textHeight + BOX_PADDING * 2 + graphBlockHeight;
		
		int x = HORIZONTAL_MARGIN + getCurrentOffsetX(otf);
		int y =
			scaledHeight - VERTICAL_MARGIN - boxHeight + getCurrentOffsetY(otf);
		
		handleDrag(graphics, otf, x, y, boxWidth, boxHeight);
		x = HORIZONTAL_MARGIN + getCurrentOffsetX(otf);
		y = scaledHeight - VERTICAL_MARGIN - boxHeight + getCurrentOffsetY(otf);
		
		int bgColor = (otf.getBackgroundAlpha() << 24);
		graphics.fill(x, y, x + boxWidth, y + boxHeight, bgColor);
		
		int textY = y + BOX_PADDING + graphBlockHeight;
		if(otf.shouldShowGraph())
			drawGraph(graphics, font, x, y, boxWidth, graphHeight, fontScale);
		
		int textX = x + BOX_PADDING;
		for(String line : clipped)
		{
			RenderUtils.drawScaledText(graphics, font, line, textX, textY,
				0xFFFFFFFF, false, fontScale);
			textY += scaledLineHeight + scaledLineSpacing;
		}
	}
	
	private ArrayList<String> buildLines(List<HackPerformanceTracker.Row> rows,
		boolean usingWindowData, PerformanceOverlayOtf otf)
	{
		ArrayList<String> lines = new ArrayList<>();
		String mode = usingWindowData ? "1s" : "live";
		lines.add("Hack Perf [" + mode + "] sort="
			+ (otf.getSortMode() == SortMode.PEAK_TIME ? "peak" : "total"));
		
		for(HackPerformanceTracker.Row row : rows)
		{
			StringBuilder sb = new StringBuilder(row.name());
			sb.append(" | T ").append(formatMs(row.totalMs()));
			if(otf.shouldShowUpdate())
				sb.append(" U ").append(formatMs(row.updateMs()));
			if(otf.shouldShowRender())
				sb.append(" R ").append(formatMs(row.renderMs()));
			if(otf.shouldShowGui())
				sb.append(" G ").append(formatMs(row.guiMs()));
			sb.append(" | P ").append(formatMs(row.peakMs()));
			lines.add(sb.toString());
		}
		
		return lines;
	}
	
	private String formatMs(double ms)
	{
		if(ms >= 100)
			return String.format(java.util.Locale.ROOT, "%.0fms", ms);
		if(ms >= 10)
			return String.format(java.util.Locale.ROOT, "%.1fms", ms);
		return String.format(java.util.Locale.ROOT, "%.2fms", ms);
	}
	
	private String clipToWidth(Font font, String text, int maxWidth)
	{
		if(font.width(text) <= maxWidth)
			return text;
		
		String suffix = "...";
		int suffixWidth = font.width(suffix);
		String s = text;
		while(!s.isEmpty() && font.width(s) + suffixWidth > maxWidth)
			s = s.substring(0, s.length() - 1);
		
		return s + suffix;
	}
	
	private PerformanceOverlayOtf getSettings()
	{
		if(!WurstClient.INSTANCE.isEnabled()
			|| WurstClient.INSTANCE.getOtfs() == null)
			return null;
		return WurstClient.INSTANCE.getOtfs().performanceOverlayOtf;
	}
	
	private void updateGraphSample(List<HackPerformanceTracker.Row> rows)
	{
		long now = System.currentTimeMillis();
		if(now - lastGraphSampleMs < GRAPH_SAMPLE_INTERVAL_MS)
			return;
		
		lastGraphSampleMs = now;
		double totalMs = 0;
		for(HackPerformanceTracker.Row row : rows)
			totalMs += row.totalMs();
		
		pushGraphSample(totalMs);
	}
	
	private void pushGraphSample(double value)
	{
		if(graphSize < GRAPH_MAX_SAMPLES)
		{
			int idx = (graphStart + graphSize) % GRAPH_MAX_SAMPLES;
			graphSamples[idx] = value;
			graphSize++;
			return;
		}
		
		graphSamples[graphStart] = value;
		graphStart = (graphStart + 1) % GRAPH_MAX_SAMPLES;
	}
	
	private double getGraphSample(int idx)
	{
		if(idx < 0 || idx >= graphSize)
			return 0;
		
		return graphSamples[(graphStart + idx) % GRAPH_MAX_SAMPLES];
	}
	
	private double getGraphMax()
	{
		double max = 0;
		for(int i = 0; i < graphSize; i++)
			max = Math.max(max, getGraphSample(i));
		return max;
	}
	
	private void drawGraph(GuiGraphics graphics, Font font, int boxX, int boxY,
		int boxWidth, int graphHeight, double fontScale)
	{
		int graphX = boxX + BOX_PADDING;
		int graphWidth = Math.max(30, boxWidth - BOX_PADDING * 2);
		int labelY = boxY + BOX_PADDING;
		
		double latest = graphSize > 0 ? getGraphSample(graphSize - 1) : 0;
		double peak = getGraphMax();
		String label =
			"Graph T: " + formatMs(latest) + " (max " + formatMs(peak) + ")";
		int labelMaxWidth =
			Math.max(40, (int)Math.floor(graphWidth / fontScale));
		String clippedLabel = clipToWidth(font, label, labelMaxWidth);
		RenderUtils.drawScaledText(graphics, font, clippedLabel, graphX, labelY,
			0xFFFFFFFF, false, fontScale);
		
		int plotY = labelY + (int)Math.round(font.lineHeight * fontScale) + 1;
		RenderUtils.fill2D(graphics, graphX, plotY, graphX + graphWidth,
			plotY + graphHeight, 0x40000000);
		RenderUtils.drawBorder2D(graphics, graphX, plotY, graphX + graphWidth,
			plotY + graphHeight, 0x70FFFFFF);
		
		double scaleMax = Math.max(20.0, getGraphMax() * 1.1);
		drawGuideLine(graphics, graphX, plotY, graphWidth, 16.67, scaleMax,
			graphHeight, 0x66B8D66A);
		drawGuideLine(graphics, graphX, plotY, graphWidth, 33.33, scaleMax,
			graphHeight, 0x66E0A85A);
		
		if(graphSize < 1 || graphWidth < 2)
			return;
		
		int pointCount = Math.max(2, Math.min(graphWidth, graphSize));
		float prevX = graphX;
		float prevY =
			valueToGraphY(getGraphSample(0), plotY, scaleMax, graphHeight);
		int prevColor = colorForValue(getGraphSample(0));
		
		for(int i = 1; i < pointCount; i++)
		{
			double t = i / (double)(pointCount - 1);
			int sampleIndex = (int)Math.round(t * (graphSize - 1));
			double sample = getGraphSample(sampleIndex);
			float x = (float)(graphX + t * (graphWidth - 1));
			float y = valueToGraphY(sample, plotY, scaleMax, graphHeight);
			int color = colorForValue(sample);
			RenderUtils.drawLine2D(graphics, prevX, prevY, x, y, prevColor);
			prevX = x;
			prevY = y;
			prevColor = color;
		}
	}
	
	private void drawGuideLine(GuiGraphics graphics, int graphX, int plotY,
		int graphWidth, double value, double scaleMax, int graphHeight,
		int color)
	{
		if(value > scaleMax)
			return;
		
		float y = valueToGraphY(value, plotY, scaleMax, graphHeight);
		RenderUtils.drawLine2D(graphics, graphX, y, graphX + graphWidth - 1, y,
			color);
	}
	
	private float valueToGraphY(double value, int plotY, double scaleMax,
		int graphHeight)
	{
		double clamped = Math.max(0.0, Math.min(scaleMax, value));
		double norm = scaleMax <= 0.0 ? 0.0 : clamped / scaleMax;
		return (float)(plotY + graphHeight - 1 - norm * (graphHeight - 1));
	}
	
	private int colorForValue(double ms)
	{
		if(ms <= 8.0)
			return 0xFF5EEA6E;
		if(ms <= 16.7)
			return 0xFFD0F25A;
		if(ms <= 33.3)
			return 0xFFF2A640;
		return 0xFFF25A5A;
	}
	
	private void handleDrag(GuiGraphics context, PerformanceOverlayOtf otf,
		int x, int y, int width, int height)
	{
		boolean canDrag = MC.screen instanceof ChatScreen
			|| MC.screen instanceof AbstractContainerScreen<?>;
		if(!canDrag)
		{
			if(dragging)
				commitDraggedOffset(otf);
			dragging = false;
			return;
		}
		
		Window window = MC.getWindow();
		if(window == null)
		{
			if(dragging)
				commitDraggedOffset(otf);
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
				dragStartOffsetX = otf.getHudOffsetX();
				dragStartOffsetY = otf.getHudOffsetY();
				dragOffsetX = dragStartOffsetX;
				dragOffsetY = dragStartOffsetY;
			}
			
			dragOffsetX = Mth.clamp(
				dragStartOffsetX + (int)Math.round(mouseX - dragStartMouseX),
				otf.getHudOffsetMinX(), otf.getHudOffsetMaxX());
			dragOffsetY = Mth.clamp(
				dragStartOffsetY + (int)Math.round(mouseY - dragStartMouseY),
				otf.getHudOffsetMinY(), otf.getHudOffsetMaxY());
			return;
		}
		
		if(!leftDown)
		{
			if(dragging)
				commitDraggedOffset(otf);
			dragging = false;
		}
	}
	
	private int getCurrentOffsetX(PerformanceOverlayOtf otf)
	{
		return dragging ? dragOffsetX : otf.getHudOffsetX();
	}
	
	private int getCurrentOffsetY(PerformanceOverlayOtf otf)
	{
		return dragging ? dragOffsetY : otf.getHudOffsetY();
	}
	
	private void commitDraggedOffset(PerformanceOverlayOtf otf)
	{
		otf.setHudOffsets(dragOffsetX, dragOffsetY);
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
}
