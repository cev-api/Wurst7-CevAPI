/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Scrollable, bounded presentation of the passive server fingerprint snapshot.
 */
public final class UiUtilsVerboseServerScanScreen extends Screen
{
	private final Screen parent;
	private int scroll;
	private boolean draggingScrollbar;
	private int scrollbarGrabOffset;
	private ScrollbarMetrics lastScrollbar = ScrollbarMetrics.none();
	
	public UiUtilsVerboseServerScanScreen(Screen parent)
	{
		super(Component.literal("ServerIntel: Verbose Server Scan"));
		this.parent = parent;
	}
	
	@Override
	protected void init()
	{
		startMissingScans();
		int width = Math.min(360, this.width - 24);
		int left = (this.width - width) / 2;
		addRenderableWidget(UiUtils.styledButton("Refresh",
			b -> startMissingScans(), left, this.height - 30, 86, 20));
		addRenderableWidget(UiUtils.styledButton("Copy Report", b -> {
			if(this.minecraft != null)
				this.minecraft.keyboardHandler.setClipboard(buildReport());
		}, left + 91, this.height - 30, 100, 20));
		addRenderableWidget(UiUtils.styledButton("Done", b -> {
			saveSnapshot();
			McCompat.setScreen(this.minecraft, parent);
		}, left + 196, this.height - 30, width - 196, 20));
	}
	
	// ### ADDED ### Verbose Scan is a combined view; run missing active scans
	// once for this server.
	private static void startMissingScans()
	{
		if(!UiUtilsPluginScanner.isActive()
			&& !UiUtilsPluginScanner.hasResultsForCurrentServer())
			UiUtilsPluginScanner.startScan();
		if(!UiUtilsCommandScanner.isActive()
			&& !UiUtilsCommandScanner.hasResultsForCurrentServer())
			UiUtilsCommandScanner.startScan();
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX,
		int mouseY, float partialTicks)
	{
		super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
		int width = Math.min(360, this.width - 24),
			left = (this.width - width) / 2;
		int top = 26, bottom = this.height - 36,
			lineHeight = this.font.lineHeight + 2;
		graphics.fill(left, top, left + width, bottom, 0xB0000000);
		List<String> lines = reportLines();
		int visible = Math.max(1, (bottom - top - 6) / lineHeight);
		scroll =
			Math.max(0, Math.min(scroll, Math.max(0, lines.size() - visible)));
		int y = top + 4;
		for(int i = scroll; i < lines.size()
			&& y < bottom - lineHeight; i++, y += lineHeight)
		{
			String line = lines.get(i);
			int color = line.startsWith("[") ? 0xFFFFDE7A
				: line.startsWith("  ") ? 0xFFB8D8FF : 0xFFEAEAEA;
			graphics.text(this.font, line, left + 6, y, color, false);
		}
		lastScrollbar = computeScrollbar(left + width - 5, top + 2, bottom - 2,
			lines.size(), visible, scroll);
		renderScrollbar(graphics, lastScrollbar);
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX,
		double scrollY)
	{
		if(scrollY < 0)
			scroll++;
		else if(scrollY > 0)
			scroll = Math.max(0, scroll - 1);
		return true;
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick)
	{
		if(event.button() == 0 && lastScrollbar.hasScroll
			&& lastScrollbar.contains(event.x(), event.y()))
		{
			if(event.y() >= lastScrollbar.thumbY
				&& event.y() <= lastScrollbar.thumbY + lastScrollbar.thumbH)
			{
				draggingScrollbar = true;
				scrollbarGrabOffset = (int)Math.max(0,
					Math.round(event.y()) - lastScrollbar.thumbY);
			}else
				jumpScrollToMouse((int)Math.round(event.y()), 0);
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}
	
	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX,
		double dragY)
	{
		if(draggingScrollbar && event.button() == 0 && lastScrollbar.hasScroll)
		{
			jumpScrollToMouse((int)Math.round(event.y()), scrollbarGrabOffset);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}
	
	@Override
	public boolean mouseReleased(MouseButtonEvent event)
	{
		if(event.button() == 0 && draggingScrollbar)
		{
			draggingScrollbar = false;
			return true;
		}
		return super.mouseReleased(event);
	}
	
	private void jumpScrollToMouse(int mouseY, int grabOffset)
	{
		if(!lastScrollbar.hasScroll)
			return;
		int maxScroll =
			Math.max(1, lastScrollbar.totalRows - lastScrollbar.visibleRows);
		int travel = Math.max(1, lastScrollbar.trackBottom
			- lastScrollbar.trackTop - lastScrollbar.thumbH);
		int thumbTop = Math.max(lastScrollbar.trackTop,
			Math.min(lastScrollbar.trackBottom - lastScrollbar.thumbH,
				mouseY - grabOffset));
		double ratio = (thumbTop - lastScrollbar.trackTop) / (double)travel;
		scroll = Math.max(0,
			Math.min(maxScroll, (int)Math.round(ratio * maxScroll)));
	}
	
	private static ScrollbarMetrics computeScrollbar(int x, int top, int bottom,
		int totalRows, int visibleRows, int scroll)
	{
		int trackH = Math.max(1, bottom - top);
		if(totalRows <= visibleRows)
			return new ScrollbarMetrics(x, top, bottom, top, trackH, false,
				totalRows, visibleRows);
		int thumbH = Math.max(12, (int)Math
			.round(trackH * visibleRows / (double)Math.max(1, totalRows)));
		int maxScroll = Math.max(1, totalRows - visibleRows);
		int travel = Math.max(1, trackH - thumbH);
		int thumbY =
			top + (int)Math.round(Math.max(0, Math.min(scroll, maxScroll))
				/ (double)maxScroll * travel);
		return new ScrollbarMetrics(x, top, bottom, thumbY, thumbH, true,
			totalRows, visibleRows);
	}
	
	private void renderScrollbar(GuiGraphicsExtractor graphics,
		ScrollbarMetrics metrics)
	{
		graphics.fill(metrics.x, metrics.trackTop, metrics.x + 3,
			metrics.trackBottom, 0xFF353535);
		if(metrics.hasScroll)
			graphics.fill(metrics.x, metrics.thumbY, metrics.x + 3,
				metrics.thumbY + metrics.thumbH,
				draggingScrollbar ? 0xFFFFFFFF : 0xFFCFCFCF);
	}
	
	private record ScrollbarMetrics(int x, int trackTop, int trackBottom,
		int thumbY, int thumbH, boolean hasScroll, int totalRows,
		int visibleRows)
	{
		private static ScrollbarMetrics none()
		{
			return new ScrollbarMetrics(0, 0, 0, 0, 0, false, 0, 0);
		}
		
		private boolean contains(double mouseX, double mouseY)
		{
			return mouseX >= x && mouseX <= x + 3 && mouseY >= trackTop
				&& mouseY <= trackBottom;
		}
	}
	
	@Override
	public void onClose()
	{
		saveSnapshot();
		McCompat.setScreen(this.minecraft, parent);
	}
	
	private void saveSnapshot()
	{
		UiUtilsScanHistory.recordVerboseFingerprint(
			UiUtilsScanHistory.serverKey(this.minecraft),
			UiUtilsServerFingerprintCollector.snapshot());
	}
	
	public static String buildReport()
	{
		return String.join("\n", reportLines());
	}
	
	private static List<String> reportLines()
	{
		UiUtilsServerFingerprintCollector.Snapshot snapshot =
			UiUtilsServerFingerprintCollector.snapshot();
		List<String> lines = new ArrayList<>();
		lines.add("[Summary]");
		lines.add("Connected snapshot: " + snapshot.connected());
		if(!snapshot.configurationCaptured())
			lines.add(
				"WARNING: Configuration-phase fingerprint unavailable. Reconnect to capture Known Packs and registry synchronization.");
		lines.add("[Detected Server Software]");
		Map<String, String> software =
			new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		for(UiUtilsServerFingerprintCollector.KnownPackInfo pack : snapshot
			.knownPacks())
		{
			if("minecraft".equals(pack.namespace()) && "core".equals(pack.id()))
				continue;
			software.put(
				UiUtilsServerFingerprintCollector.friendlyName(pack.id()),
				"Known Pack - exact advertised version " + pack.version());
		}
		for(UiUtilsServerFingerprintCollector.ChannelInfo channel : snapshot
			.payloads())
		{
			String friendly = UiUtilsServerFingerprintCollector
				.friendlyName(channel.namespace());
			if(!friendly.equals(channel.namespace())
				&& !software.containsKey(friendly))
				software.put(friendly, "Server custom payload " + channel.id());
		}
		// Do not duplicate a Known Pack with its same active-scan row (for
		// example, MintUtils 1.0.0).
		for(UiUtilsPluginScanner.PluginResultRow row : UiUtilsPluginScanner
			.getResultsSnapshot())
		{
			boolean alreadyCovered = software.keySet().stream().anyMatch(
				name -> row.plugin().equalsIgnoreCase(name) || row.plugin()
					.regionMatches(true, 0, name + " ", 0, name.length() + 1));
			if(!alreadyCovered)
				software.put(row.plugin(), "Plugin scan " + row.evidence());
		}
		if(software.isEmpty())
			lines.add("  No corroborated software evidence captured.");
		else
			software.forEach(
				(name, evidence) -> lines.add("  " + name + " - " + evidence));
		lines.add("[Known Packs]");
		for(UiUtilsServerFingerprintCollector.KnownPackInfo pack : snapshot
			.knownPacks())
			lines.add("  " + pack.namespace() + ":" + pack.id() + ":"
				+ pack.version());
		lines.add("[Platform / Brand]");
		lines.add(
			"  " + (snapshot.brand().isBlank() ? "Unknown" : snapshot.brand()));
		lines.add("[Server Registered Channels / Custom Payload Evidence]");
		Map<String, List<UiUtilsServerFingerprintCollector.ChannelInfo>> channels =
			new LinkedHashMap<>();
		for(UiUtilsServerFingerprintCollector.ChannelInfo channel : snapshot
			.payloads())
			channels.computeIfAbsent(channel.namespace(),
				ignored -> new ArrayList<>()).add(channel);
		if(channels.isEmpty())
			lines.add("  No server payload IDs captured.");
		else
			channels.forEach((namespace, values) -> {
				lines.add("  " + namespace + ":");
				for(var value : values)
					lines.add("    " + value.id() + " [" + value.phase() + ", "
						+ value.source() + "]");
			});
		lines.add("[Custom Registries]");
		for(UiUtilsServerFingerprintCollector.RegistryInfo registry : snapshot
			.registries())
		{
			if(registry.entries().isEmpty())
				continue;
			lines.add("  " + registry.registry());
			for(UiUtilsServerFingerprintCollector.RegistryEntryInfo entry : registry
				.entries())
				lines.add("    " + entry.id()
					+ (entry.hasCustomData() ? " [custom data]" : ""));
		}
		lines.add("[Dimensions]");
		for(String value : snapshot.dimensions())
			lines.add("  " + value);
		lines.add("[Advancement / Datapack Namespaces]");
		for(String value : snapshot.advancements())
			lines.add("  " + value);
		lines.add("[Chat Completion Metadata]");
		lines.add("  total=" + snapshot.chatCompletionCount() + ", emoji="
			+ snapshot.emojiCompletionCount() + ", formatting/action="
			+ snapshot.formattingCompletionCount());
		if(!snapshot.chatSamples().isEmpty())
			lines.add("  sample=" + String.join(", ", snapshot.chatSamples()));
		lines.add("[Scoreboard / UI Signatures]");
		for(String value : snapshot.objectives())
			lines.add("  objective: " + value);
		for(String value : snapshot.tabText())
			lines.add("  tab: " + value);
		lines.add("[Server Configuration]");
		snapshot.serverConfig()
			.forEach((key, value) -> lines.add("  " + key + " = " + value));
		return lines;
	}
}
