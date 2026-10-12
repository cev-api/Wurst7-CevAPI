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
import net.wurstclient.uiutils.ui.UiButton;
import net.wurstclient.uiutils.ui.UiContent;
import net.wurstclient.uiutils.ui.UiModernScreen;
import net.wurstclient.uiutils.ui.UiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Scrollable, bounded presentation of the passive server fingerprint snapshot.
 */
public final class UiUtilsVerboseServerScanScreen extends UiModernScreen
{
	private final Screen parent;
	private int renderedLineCount;
	private boolean scansStarted;
	private UiButton scanButton;
	private final java.util.Map<Integer, String> reportUrls =
		new java.util.HashMap<>();
	private List<ReportLine> renderedLines = List.of();
	private List<String> cachedReport = List.of();
	private Map<Integer, Integer> cachedFindingColors = Map.of();
	private List<ReportLine> cachedWrappedLines = List.of();
	private int cachedWrapWidth = -1;
	private int refreshTicks;
	private boolean previouslyScanning;
	
	public UiUtilsVerboseServerScanScreen(Screen parent)
	{
		super(Component.literal("Server Intel: Verbose Server Scan"));
		this.parent = parent;
	}
	
	@Override
	protected int naturalWidth()
	{
		return 460;
	}
	
	@Override
	protected boolean expandWidth()
	{
		return true;
	}
	
	@Override
	protected int maxWidth()
	{
		return 1100;
	}
	
	@Override
	protected void buildContent(UiContent c)
	{
		if(!scansStarted)
		{
			scansStarted = true;
			startMissingScans();
		}
		int lineHeight = lineHeight();
		renderedLines = wrappedReportLines(c.width());
		renderedLineCount = renderedLines.size();
		// Painted directly in the overlay, so its extent is declared for the
		// same
		// reason: text the screen draws is not covered by any widget.
		int reportHeight = renderedLines.size() * lineHeight + 4;
		c.reportBottom(reportHeight);
		c.space(reportHeight);
		scanButton = c.footerButton("", UiButton.Kind.SECONDARY, () -> {
			if(scansActive())
			{
				UiUtilsCommandScanner.stopScan();
				UiUtilsPluginScanner.stopScan();
				UiUtilsLegacyPluginScanner.stopScan();
				if(UiUtilsDupeDbUpdater.isServerLookupActive())
					UiUtilsDupeDbUpdater.stopScan();
			}else
			{
				UiUtilsPluginScanner.startScan();
				UiUtilsCommandScanner.startScan();
			}
			refreshScanButton();
			Minecraft.getInstance()
				.execute(this::rebuildWidgetsPreservingScroll);
		});
		refreshScanButton();
		c.footerButton("Copy Report", UiButton.Kind.SECONDARY, () -> {
			if(this.minecraft != null)
				this.minecraft.keyboardHandler.setClipboard(buildReport());
		});
		c.footerButton("Done", UiButton.Kind.PRIMARY, this::onClose);
	}
	
	@Override
	public void tick()
	{
		super.tick();
		refreshScanButton();
		boolean active = scansActive();
		if(++refreshTicks < 5 && active == previouslyScanning)
			return;
		refreshTicks = 0;
		previouslyScanning = active;
		List<ReportLine> fresh = wrappedReportLines(contentWidthUnits());
		renderedLines = fresh;
		int lineCount = fresh.size();
		if(lineCount != renderedLineCount)
			rebuildWidgetsPreservingScroll();
	}
	
	@Override
	protected void drawContentOverlay(GuiGraphicsExtractor graphics, Font font,
		int mouseX, int mouseY)
	{
		List<ReportLine> lines = renderedLines;
		int lineHeight = lineHeight();
		int top = (int)Math.round(scrollOffset());
		int bottom = top + contentHeightUnits();
		for(int i = Math.max(0, top / lineHeight); i < Math.min(lines.size(),
			bottom / lineHeight + 1); i++)
		{
			int y = i * lineHeight;
			if(y + lineHeight < top || y > bottom)
				continue;
			ReportLine line = lines.get(i);
			UiTheme.text(graphics, font, line.text(), 0, y + 1, line.color());
		}
	}
	
	private static boolean scansActive()
	{
		return UiUtilsCommandScanner.isActive()
			|| UiUtilsPluginScanner.isActive()
			|| UiUtilsLegacyPluginScanner.isActive()
			|| UiUtilsDupeDbUpdater.isServerLookupActive();
	}
	
	private void refreshScanButton()
	{
		if(scanButton != null)
			scanButton.setMessage(
				Component.literal(scansActive() ? "STOP scans" : "Refresh"));
	}
	
	private int lineHeight()
	{
		return Math.max(1,
			(int)Math
				.ceil(Minecraft.getInstance().font.lineHeight / currentScale()))
			+ 2;
	}
	
	// Verbose Scan is a combined view; run missing active scans once for this
	// server.
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
	public void onClose()
	{
		UiUtilsScanHistory.recordVerboseFingerprint(
			UiUtilsScanHistory.serverKey(this.minecraft),
			UiUtilsServerFingerprintCollector.snapshot());
		McCompat.setScreen(this.minecraft, parent);
	}
	
	public static String buildReport()
	{
		return String.join("\n", reportLines());
	}
	
	private List<ReportLine> wrappedReportLines(int contentWidth)
	{
		List<ReportLine> result = new ArrayList<>();
		Font font = Minecraft.getInstance().font;
		int width = Math.max(1, (int)Math.floor(contentWidth * currentScale()));
		
		Map<Integer, Integer> findingColors = new java.util.HashMap<>();
		List<String> report = reportLines(findingColors);
		if(width == cachedWrapWidth && report.equals(cachedReport)
			&& findingColors.equals(cachedFindingColors))
			return cachedWrappedLines;
		reportUrls.clear();
		for(int lineIndex = 0; lineIndex < report.size(); lineIndex++)
		{
			String line = report.get(lineIndex);
			int color = line.startsWith("[") ? 0xFFFFDE7A
				: line.startsWith("  ") ? 0xFFB8D8FF : 0xFFEAEAEA;
			color = UiUtilsVulnerablePlugins.patchDetailColor(line,
				findingColors.getOrDefault(lineIndex, color));
			Component text = findingColors.containsKey(lineIndex)
				? Component.literal(line)
					.withStyle(net.minecraft.ChatFormatting.BOLD)
				: Component.literal(line);
			for(net.minecraft.util.FormattedCharSequence part : font.split(text,
				width))
			{
				if(line.startsWith("    URL: "))
					reportUrls.put(result.size(),
						line.substring("    URL: ".length()));
				result.add(new ReportLine(part, color));
			}
		}
		cachedReport = List.copyOf(report);
		cachedFindingColors = Map.copyOf(findingColors);
		cachedWrapWidth = width;
		cachedWrappedLines = List.copyOf(result);
		return result;
	}
	
	@Override
	protected boolean onContentClick(int x, int y, boolean doubleClick)
	{
		String url =
			UiUtilsDupeDbDatabase.safeUrl(reportUrls.get(y / lineHeight()));
		if(url == null)
			return false;
		net.minecraft.client.gui.screens.ConfirmLinkScreen.confirmLinkNow(this,
			java.net.URI.create(url), false);
		return true;
	}
	
	private record ReportLine(net.minecraft.util.FormattedCharSequence text,
		int color)
	{}
	
	private static List<String> reportLines()
	{
		return reportLines(new java.util.HashMap<>());
	}
	
	private static List<String> reportLines(Map<Integer, Integer> findingColors)
	{
		UiUtilsServerFingerprintCollector.Snapshot snapshot =
			UiUtilsServerFingerprintCollector.snapshot();
		List<String> lines = new ArrayList<>();
		String serverAddress = UiUtilsDupeDbUpdater.currentServer();
		var serverFindings =
			UiUtilsVulnerablePlugins.serverFindings(serverAddress);
		if(!serverAddress.isEmpty() && (!serverFindings.isEmpty()
			|| UiUtilsSettings.get().dupeDbCheckServerIp))
		{
			lines.add("[DupeDB server reports: " + serverAddress + "]");
			lines.add(UiUtilsDupeDbUpdater.serverLookupStatus(serverAddress));
			for(var finding : serverFindings)
			{
				findingColors.put(lines.size(),
					UiUtilsVulnerablePlugins.patchColor(finding.patched()));
				lines.add("  " + finding.title());
				for(String detail : finding.details())
					lines.add("    " + detail);
				if(finding.url() != null)
					lines.add("    URL: " + finding.url());
			}
		}
		java.util.Set<String> seenPlugins = new java.util.HashSet<>();
		for(var plugin : UiUtilsPluginScanner.getResultsSnapshot())
			if(seenPlugins
				.add(UiUtilsVulnerablePlugins.normalizeKey(plugin.plugin())))
			{
				var findings =
					UiUtilsVulnerablePlugins.pluginFindings(plugin.plugin());
				if(!findings.isEmpty())
					lines.add(
						"[DupeDB plugin reports: " + plugin.plugin() + "]");
				for(var finding : findings)
				{
					findingColors.put(lines.size(),
						UiUtilsVulnerablePlugins.patchColor(finding.patched()));
					lines.add("  " + finding.title());
					for(String detail : finding.details())
						lines.add("    " + detail);
					if(finding.url() != null)
						lines.add("    URL: " + finding.url());
				}
			}
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
