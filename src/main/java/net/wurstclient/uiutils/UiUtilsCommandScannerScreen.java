/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.wurstclient.uiutils.ui.UiButton;
import net.wurstclient.uiutils.ui.UiContent;
import net.wurstclient.uiutils.ui.UiInput;
import net.wurstclient.uiutils.ui.UiModernScreen;
import net.wurstclient.uiutils.ui.UiTheme;
import net.wurstclient.uiutils.ui.UiToggle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class UiUtilsCommandScannerScreen extends UiModernScreen
{
	private static final int OUTPUT_LINES = 3;
	
	private final Screen parent;
	private UiInput searchField;
	private UiInput packetCommandsField;
	private UiButton scannerModeButton;
	private UiButton commandScanButton, pluginScanButton, legacyScanButton,
		verboseScanButton;
	private boolean commandOutputVisible;
	private int commandCategory;
	private int commandOutputTop;
	private int resultsFlowTop;
	/**
	 * Lines built for the last layout, shared by the measure and draw passes.
	 */
	private List<Line> resultLines = List.of();
	private int refreshTicks;
	private boolean previouslyScanning;
	private final Set<String> expandedPlugins = new HashSet<>();
	private final Set<String> expandedCommandLetters = new HashSet<>();
	private final List<ClickTargetRow> clickableRows = new ArrayList<>();
	private boolean vulnerableListExpanded;
	
	public UiUtilsCommandScannerScreen(Screen parent)
	{
		super(Component.literal("Wurst Server Intel"));
		this.parent = parent;
		commandCategory =
			"KNOWN".equalsIgnoreCase(
				UiUtilsSettings.get().commandScannerResultFilter)
					? 1
					: "NEW".equalsIgnoreCase(
						UiUtilsSettings.get().commandScannerResultFilter) ? 2
							: 0;
	}
	
	@Override
	protected int naturalWidth()
	{
		return 480;
	}
	
	@Override
	protected boolean expandHeight()
	{
		return true;
	}
	
	@Override
	protected void buildContent(UiContent c)
	{
		c.section("Command scanning");
		scannerModeButton = c.button("", () -> {
			boolean packet = !"CLIENT_SIDE_ENUMERATION"
				.equalsIgnoreCase(UiUtilsSettings.get().commandScannerMode);
			UiUtilsSettings.get().commandScannerMode =
				packet ? "CLIENT_SIDE_ENUMERATION" : "PACKET_PROBING";
			UiUtilsSettings.save();
			refreshScannerModeLabel();
		});
		refreshScannerModeLabel();
		
		commandScanButton = c.button("", () -> {
			if(UiUtilsCommandScanner.isActive())
				UiUtilsCommandScanner.stopScan();
			else
				UiUtilsCommandScanner.startScan();
			refreshScanButtons();
		});
		c.row(UiContent.of(toggle("Command debug",
			() -> UiUtilsSettings.get().commandScannerDebugProbe, v -> {
				UiUtilsSettings.get().commandScannerDebugProbe = v;
				UiUtilsSettings.save();
			})),
			UiContent.of(toggle("Run found cmds",
				() -> UiUtilsSettings.get().commandScannerRunFoundCommands,
				v -> {
					UiUtilsSettings.get().commandScannerRunFoundCommands = v;
					UiUtilsSettings.save();
				})));
		c.section("Plugin scanning");
		pluginScanButton = UiButton.of("", () -> {
			if(UiUtilsPluginScanner.isActive())
				UiUtilsPluginScanner.stopScan();
			else
				UiUtilsPluginScanner.startScan();
			refreshScanButtons();
		});
		legacyScanButton = UiButton.of("", () -> {
			if(UiUtilsLegacyPluginScanner.isActive())
				UiUtilsLegacyPluginScanner.stopScan();
			else
				UiUtilsLegacyPluginScanner.startScan();
			refreshScanButtons();
		});
		c.row(UiContent.of(pluginScanButton), UiContent.of(legacyScanButton));
		c.toggle("Version oracle (/ver queries)",
			() -> UiUtilsSettings.get().pluginScanVersionOracle, value -> {
				UiUtilsSettings.get().pluginScanVersionOracle = value;
				UiUtilsSettings.save();
			})
			.setTooltip(net.minecraft.client.gui.components.Tooltip
				.create(Component.literal(
					"OFF by default. Sends up to 12 /ver <plugin> command packets. Replies appear in your chat; queries are not public chat messages.")));
		c.toggle("Oracle dictionary fallback",
			() -> UiUtilsSettings.get().pluginScanOracleDictionary, value -> {
				UiUtilsSettings.get().pluginScanOracleDictionary = value;
				UiUtilsSettings.save();
			})
			.setTooltip(net.minecraft.client.gui.components.Tooltip
				.create(Component.literal(
					"Fill unused oracle queries with known plugin names after discovered names. Maximum 12 queries total.")));
		c.section("Scan settings");
		c.slider("Scanner speed", UiUtilsSettings.Data.MIN_PROBES_PER_SECOND,
			UiUtilsSettings.Data.MAX_PROBES_PER_SECOND,
			UiUtilsSettings.getProbesPerSecond(), value -> {
				UiUtilsSettings.get().scannerProbesPerSecond = value;
				UiUtilsSettings.save();
			}).suffix(" probes/s");
		verboseScanButton = c.button("", () -> {
			if(scanning())
			{
				UiUtilsCommandScanner.stopScan();
				UiUtilsPluginScanner.stopScan();
				UiUtilsLegacyPluginScanner.stopScan();
				if(UiUtilsDupeDbUpdater.isServerLookupActive())
					UiUtilsDupeDbUpdater.stopScan();
				refreshScanButtons();
				return;
			}
			UiUtilsScanHistory.recordVerboseFingerprint(
				UiUtilsScanHistory.serverKey(this.minecraft),
				UiUtilsServerFingerprintCollector.snapshot());
			McCompat.setScreen(this.minecraft,
				new UiUtilsVerboseServerScanScreen(this));
		});
		refreshScanButtons();
		c.section("Commands");
		c.row(UiContent.of(toggle("Ignore vanilla",
			() -> !UiUtilsSettings.get().commandScannerShowVanilla, value -> {
				UiUtilsSettings.get().commandScannerShowVanilla = !value;
				UiUtilsSettings.save();
				rebuildWidgetsPreservingScroll();
			})));
		c.toggle("Exclude Essentials commands",
			() -> UiUtilsSettings.get().commandScannerExcludeEssentials,
			value -> {
				UiUtilsSettings.get().commandScannerExcludeEssentials = value;
				UiUtilsSettings.save();
				rebuildWidgetsPreservingScroll();
			})
			.setTooltip(net.minecraft.client.gui.components.Tooltip
				.create(Component.literal(
					"Hide Essentials namespaces and common root aliases in command results. Root aliases may belong to other plugins.")));
		c.button("Show: " + new String[]{"All discovered", "Already known",
			"Newly discovered"}[commandCategory], () -> {
				commandCategory = (commandCategory + 1) % 3;
				UiUtilsSettings.get().commandScannerResultFilter =
					new String[]{"ALL", "KNOWN", "NEW"}[commandCategory];
				UiUtilsSettings.save();
				rebuildWidgetsPreservingScroll();
			});
		searchField = c.input(searchField == null ? "" : searchField.getValue(),
			value -> Minecraft.getInstance()
				.execute(this::rebuildWidgetsPreservingScroll));
		searchField.setMaxLength(64);
		searchField.setHint(Component.literal("Search results..."));
		packetCommandsField = c.inputSlot(packetCommandsField == null
			? UiUtilsSettings.get().commandScannerPacketCommands
			: packetCommandsField.getValue(), value -> {});
		packetCommandsField.setMaxLength(256);
		c.row(UiContent.of(packetCommandsField, 3F), UiContent
			.of(UiButton.of("Send packet cmds", this::sendPacketCommands), 2F));
		
		if(commandOutputVisible)
		{
			c.section("Command output");
			commandOutputTop = c.cursor();
			c.space(OUTPUT_LINES * lineHeight() + 4);
		}
		
		c.section("Results");
		resultsFlowTop = c.cursor();
		int lineHeight = lineHeight();
		// The results and command output are painted directly, not as widgets,
		// so
		// their exact extent is declared here. Without this the scroll system
		// would
		// only see the widgets above and could never reach the bottom of the
		// list.
		resultLines = buildLines();
		int drawnBottom = resultsFlowTop + 2 + resultLines.size() * lineHeight;
		c.reportBottom(drawnBottom);
		c.space(drawnBottom - c.cursor());
		setStatus("");
		
		c.footerButton("Clear results", UiButton.Kind.DANGER,
			this::clearResults);
		c.footerButton("Done", UiButton.Kind.PRIMARY, this::onClose);
	}
	
	@Override
	protected void drawContentOverlay(GuiGraphics graphics, Font font,
		int mouseX, int mouseY)
	{
		int lineHeight = lineHeight();
		List<Line> lines = resultLines;
		clickableRows.clear();
		if(commandOutputVisible)
			drawCommandOutput(graphics, font, commandOutputTop + 2);
		int startY = resultsFlowTop + 2;
		int top = (int)Math.floor(scrollOffset());
		int bottom = top + contentHeightUnits();
		int first =
			Math.max(0, Math.min(lines.size(), (top - startY) / lineHeight));
		int end = Math.max(first,
			Math.min(lines.size(), (bottom - startY) / lineHeight + 1));
		int y = startY + first * lineHeight;
		for(int i = first; i < end; i++)
		{
			Line line = lines.get(i);
			UiTheme.text(graphics, font,
				UiTheme.ellipsize(font,
					line.bold
						? Component.literal(line.text)
							.withStyle(net.minecraft.ChatFormatting.BOLD)
						: Component.literal(line.text),
					contentWidthUnits()),
				0, y, line.color);
			if(line.clickKey != null)
				clickableRows.add(new ClickTargetRow(line.clickKey, 0, y - 1,
					contentWidthUnits(), lineHeight + 1));
			y += lineHeight;
		}
	}
	
	@Override
	protected boolean onContentClick(int x, int y, boolean doubleClick)
	{
		for(ClickTargetRow row : clickableRows)
		{
			if(!row.contains(x, y))
				continue;
			if(row.clickKey.startsWith("dupedb:url:"))
			{
				String url = UiUtilsDupeDbDatabase
					.safeUrl(row.clickKey.substring("dupedb:url:".length()));
				if(url != null)
					net.minecraft.client.gui.screens.ConfirmLinkScreen
						.confirmLinkNow(this, java.net.URI.create(url), false);
				return true;
			}
			if(row.clickKey.equals("dupedb:lookup"))
			{
				UiUtilsDupeDbUpdater.toggleServerLookup();
				return true;
			}
			if(row.clickKey.startsWith("plugin:"))
			{
				if(expandedPlugins.contains(row.clickKey))
					expandedPlugins.remove(row.clickKey);
				else
					expandedPlugins.add(row.clickKey);
			}else if(row.clickKey.startsWith("letter:"))
			{
				if(expandedCommandLetters.contains(row.clickKey))
					expandedCommandLetters.remove(row.clickKey);
				else
					expandedCommandLetters.add(row.clickKey);
			}else if(row.clickKey.startsWith("command:"))
			{
				selectCommand(row.clickKey.substring("command:".length()));
			}else if("vuln:list".equals(row.clickKey))
			{
				vulnerableListExpanded = !vulnerableListExpanded;
			}else
			{
				continue;
			}
			return true;
		}
		return false;
	}
	
	private void sendPacketCommands()
	{
		UiUtilsSettings.get().commandScannerPacketCommands =
			packetCommandsField.getValue();
		UiUtilsSettings.save();
		UiUtilsCommandScanner.sendManualPacketCommands();
		commandOutputVisible = true;
		Minecraft.getInstance().execute(this::rebuildWidgetsPreservingScroll);
	}
	
	/** Keeps scan results live without rebuilding long reports every tick. */
	@Override
	public void tick()
	{
		super.tick();
		refreshScanButtons();
		boolean active = scanning();
		// Update live results four times per second, with an immediate final
		// refresh.
		if(++refreshTicks >= 5 || active != previouslyScanning)
		{
			refreshTicks = 0;
			refreshResults();
		}
		previouslyScanning = active;
	}
	
	private void refreshScanButtons()
	{
		if(commandScanButton != null)
			commandScanButton
				.setMessage(Component.literal(UiUtilsCommandScanner.isActive()
					? "STOP command scan" : "Run command scan"));
		if(pluginScanButton != null)
			pluginScanButton
				.setMessage(Component.literal(UiUtilsPluginScanner.isActive()
					? "STOP plugin scan" : "Run plugin scan"));
		if(legacyScanButton != null)
			legacyScanButton.setMessage(
				Component.literal(UiUtilsLegacyPluginScanner.isActive()
					? "STOP legacy scan" : "Legacy Plugin Scan (Safer)"));
		if(verboseScanButton != null)
			verboseScanButton.setMessage(Component.literal(
				scanning() ? "STOP server scans" : "Verbose Server Scan"));
	}
	
	private boolean scanning()
	{
		return UiUtilsCommandScanner.isActive()
			|| UiUtilsPluginScanner.isActive()
			|| UiUtilsLegacyPluginScanner.isActive()
			|| UiUtilsDupeDbUpdater.isServerLookupActive();
	}
	
	private void refreshResults()
	{
		List<Line> fresh = buildLines();
		boolean sizeChanged = fresh.size() != resultLines.size();
		resultLines = fresh;
		// Growing results must not re-scale the screen, so the panel is only
		// re-laid out to widen its scroll range. The scale stays put.
		if(sizeChanged)
			Minecraft.getInstance()
				.execute(this::rebuildWidgetsPreservingScroll);
		setStatus(scanning() ? "Scanning..." : "");
	}
	
	private void drawCommandOutput(GuiGraphics graphics, Font font, int top)
	{
		int lineHeight = lineHeight();
		int outputWidth = Math.max(1, contentWidthUnits() - 8);
		List<String> output =
			UiUtilsCommandScanner.getManualCommandOutputSnapshot();
		if(output.isEmpty())
		{
			UiTheme.text(graphics, font,
				"Run packet commands to see their output.", 4, top,
				UiTheme.TEXT_MUTED);
			return;
		}
		int y = top;
		int firstVisible = Math.max(0, output.size() - OUTPUT_LINES);
		for(int i = output.size() - 1; i >= firstVisible; i--)
		{
			UiTheme.text(graphics, font,
				UiTheme.ellipsize(font, output.get(i), outputWidth), 4, y,
				UiTheme.TEXT_DIM);
			y += lineHeight;
		}
	}
	
	private static int lineHeight()
	{
		return Minecraft.getInstance().font.lineHeight + 1;
	}
	
	/** Toggle that saves the setting, for rows that pair two of them. */
	private static UiToggle toggle(String label,
		java.util.function.BooleanSupplier getter,
		java.util.function.Consumer<Boolean> setter)
	{
		return new UiToggle(label, getter, value -> {
			setter.accept(value);
			UiUtilsSettings.save();
		});
	}
	
	private void refreshScannerModeLabel()
	{
		if(scannerModeButton == null)
			return;
		boolean packet = !"CLIENT_SIDE_ENUMERATION"
			.equalsIgnoreCase(UiUtilsSettings.get().commandScannerMode);
		scannerModeButton.setMessage(Component
			.literal("Scanner mode: " + (packet ? "Packet" : "Client")));
	}
	
	private void clearResults()
	{
		UiUtilsCommandScanner.clearResultsForUi();
		UiUtilsPluginScanner.clearResultsForUi();
		UiUtilsLegacyPluginScanner.clearResultsForUi();
		UiUtilsCommandScanner.clearManualCommandOutput();
		expandedPlugins.clear();
		expandedCommandLetters.clear();
		vulnerableListExpanded = false;
		commandOutputVisible = false;
		Minecraft.getInstance().execute(this::rebuildWidgetsPreservingScroll);
	}
	
	@Override
	public void onClose()
	{
		McCompat.setScreen(this.minecraft, parent);
	}
	
	private List<Line> buildLines()
	{
		List<Line> lines = new ArrayList<>();
		String serverAddress = UiUtilsDupeDbUpdater.currentServer();
		List<UiUtilsVulnerablePlugins.Finding> serverFindings =
			UiUtilsVulnerablePlugins.serverFindings(serverAddress);
		if(!serverAddress.isEmpty() && (!serverFindings.isEmpty()
			|| UiUtilsSettings.get().dupeDbCheckServerIp))
		{
			lines.add(new Line("DupeDB server reports: " + serverAddress,
				0xFFFFDE7A));
			lines.add(
				new Line(UiUtilsDupeDbUpdater.serverLookupStatus(serverAddress),
					0xFFB8D8FF));
			if(UiUtilsSettings.get().dupeDbCheckServerIp)
				lines.add(new Line(
					UiUtilsDupeDbUpdater.isServerLookupActive()
						? "STOP DupeDB server lookup"
						: "Check / refresh server in DupeDB",
					0xFF8CC8FF, "dupedb:lookup"));
			if(serverFindings.isEmpty())
				lines.add(new Line(
					"No cached exploit or sighting matches for this address.",
					0xFF909090));
			for(var finding : serverFindings)
				addDupeDbFinding(lines, finding, "  ");
			lines.add(new Line("", 0xFFFFFFFF));
		}
		lines.add(new Line("Command Scanner", 0xFF8CC8FF));
		lines.add(new Line("Status: " + UiUtilsCommandScanner.getStatusLine(),
			0xFFEAEAEA));
		lines.add(new Line(
			"Suggestion probing: "
				+ UiUtilsSuggestionCapability.getStatusLabel(),
			UiUtilsSuggestionCapability.isSuppressed() ? 0xFFFFC857
				: 0xFF909090));
		lines.add(new Line(
			(UiUtilsSettings.get().commandScannerExcludeEssentials
				? "Essentials command aliases are hidden by the results filter."
				: "Essentials commands are included in the results."),
			0xFF909090));
		UiUtilsCommandScanner.refreshKnownCommandsFromServer();
		boolean showVanilla = UiUtilsSettings.get().commandScannerShowVanilla;
		List<String> knownCommands =
			UiUtilsCommandScanner.getKnownFoundCommandsSnapshot(showVanilla);
		List<String> newCommands =
			UiUtilsCommandScanner.getNewFoundCommandsSnapshot(showVanilla);
		List<String> commands =
			UiUtilsCommandScanner.getVisibleFoundCommandsSnapshot(
				UiUtilsSettings.get().commandScannerResultFilter, showVanilla);
		String commandCount = UiUtilsCommandScanner.hasTruncatedResults()
			? commands.size() + "+" : String.valueOf(commands.size());
		lines.add(new Line("Commands: " + commandCount + " shown ("
			+ knownCommands.size() + " known, " + newCommands.size() + " new)",
			0xFFB8B8B8));
		if(UiUtilsCommandScanner.hasTruncatedResults())
			lines.add(new Line(
				"Some responses hit the server limit; counts are lower bounds.",
				0xFFFFC857));
		if(commands.size() > 50 || UiUtilsCommandScanner.hasTruncatedResults())
		{
			Map<Character, List<String>> byLetter = new LinkedHashMap<>();
			for(char c = 'a'; c <= 'z'; c++)
				byLetter.put(c, new ArrayList<>());
			byLetter.put('#', new ArrayList<>());
			for(String cmd : commands)
			{
				char first =
					cmd.isEmpty() ? '#' : Character.toLowerCase(cmd.charAt(0));
				if(first < 'a' || first > 'z')
					first = '#';
				byLetter.get(first).add(cmd);
			}
			for(Map.Entry<Character, List<String>> entry : byLetter.entrySet())
			{
				if(entry.getValue().isEmpty())
					continue;
				String key = "letter:" + entry.getKey();
				boolean expanded = expandedCommandLetters.contains(key);
				String groupCount = String.valueOf(entry.getValue().size());
				if(UiUtilsCommandScanner.isLetterGroupTruncated(entry.getKey()))
					groupCount += "+";
				lines.add(new Line((expanded ? "v " : "> ") + "["
					+ Character.toUpperCase(entry.getKey()) + "] (" + groupCount
					+ ")", 0xFFFFFFFF, key));
				if(expanded)
					for(String cmd : entry.getValue())
						addSelectableCommandLine(lines, "    ", cmd);
			}
		}else
		{
			for(String cmd : commands)
				addSelectableCommandLine(lines, "  ", cmd);
		}
		
		lines.add(new Line("", 0xFFFFFFFF));
		lines.add(new Line("Plugin Scanner", 0xFFFFDE7A));
		lines.add(new Line("Status: " + UiUtilsPluginScanner.getStatusLine(),
			0xFFEAEAEA));
		List<UiUtilsPluginScanner.PluginResultRow> plugins =
			UiUtilsPluginScanner.getResultsSnapshot();
		lines.add(new Line("Detected plugins: " + plugins.size(), 0xFFB8B8B8));
		Map<String, VulnerableHit> vulnerableHits =
			collectVulnerableHits(plugins);
		lines.add(new Line((vulnerableListExpanded ? "v " : "> ")
			+ "Plugin vulnerability reports (" + vulnerableHits.size() + ")",
			vulnerableHits.isEmpty() ? 0xFFB8B8B8 : 0xFFFF7A7A, "vuln:list"));
		if(vulnerableListExpanded)
		{
			for(VulnerableHit hit : vulnerableHits.values())
			{
				String versionText = hit.versions.isEmpty() ? ""
					: " (" + String.join(", ", hit.versions) + ")";
				lines.add(new Line(
					"  - " + hit.displayName + " listed versions" + versionText,
					0xFFFFA8A8));
				for(var finding : UiUtilsVulnerablePlugins
					.pluginFindings(hit.displayName))
					addDupeDbFinding(lines, finding, "    ");
			}
		}
		
		String currentConfidence = "";
		for(UiUtilsPluginScanner.PluginResultRow row : plugins)
		{
			if(!row.confidence().equalsIgnoreCase(currentConfidence))
			{
				currentConfidence = row.confidence();
				lines.add(new Line("", 0xFFFFFFFF));
				lines.add(new Line(confidenceHeading(row.confidenceLevel()),
					confidenceHeadingColor(row.confidenceLevel())));
			}
			String pluginKey = "plugin:" + row.confidence().toLowerCase() + "|"
				+ row.plugin().toLowerCase();
			boolean expanded = expandedPlugins.contains(pluginKey);
			String caret = expanded ? "v " : "> ";
			String flag = row.anticheatFlagged() ? " ! " : " - ";
			boolean vulnerable =
				UiUtilsVulnerablePlugins.entriesByKey().containsKey(
					UiUtilsVulnerablePlugins.normalizeKey(row.plugin()));
			lines.add(new Line(
				caret + flag + row.plugin() + " (" + row.commandCount()
					+ " cmds)",
				vulnerable ? 0xFFFF7A7A
					: (row.anticheatFlagged() ? 0xFFFFA8A8 : 0xFF93F7A4),
				pluginKey));
			if(expanded)
			{
				lines.add(new Line("    evidence:", 0xFFD8D8D8));
				for(String detail : row.evidence())
					lines.add(new Line("      " + detail, 0xFFB8D8FF));
				if(row.evidence().isEmpty())
					lines.add(new Line("      (none recorded)", 0xFF909090));
				if(row
					.confidenceLevel() == UiUtilsPluginScanner.PluginConfidence.LOW)
					lines.add(new Line(
						"      ambiguous: these commands may be provided by another plugin",
						0xFF909090));
				
				List<String> details = UiUtilsServerFingerprintCollector
					.detailsForSoftware(row.plugin());
				for(String detail : details)
					lines.add(new Line("      " + detail, 0xFFB8B8B8));
				if(!row.commands().isEmpty())
				{
					lines.add(new Line("    commands:", 0xFFD8D8D8));
					for(String cmd : row.commands())
						addSelectableCommandLine(lines, "      ", cmd);
				}
			}
		}
		
		lines.add(new Line("", 0xFFFFFFFF));
		lines.add(new Line("Legacy Plugin Scanner", 0xFFFFB347));
		lines.add(
			new Line("Status: " + UiUtilsLegacyPluginScanner.getStatusLine(),
				0xFFEAEAEA));
		List<UiUtilsPluginScanner.PluginResultRow> legacyPlugins =
			UiUtilsLegacyPluginScanner.getResultsSnapshot();
		lines.add(
			new Line("Detected plugins: " + legacyPlugins.size(), 0xFFB8B8B8));
		for(UiUtilsPluginScanner.PluginResultRow row : legacyPlugins)
		{
			String flag = row.anticheatFlagged() ? " ! " : " - ";
			boolean vulnerable =
				UiUtilsVulnerablePlugins.entriesByKey().containsKey(
					UiUtilsVulnerablePlugins.normalizeKey(row.plugin()));
			lines.add(new Line(flag + row.plugin(), vulnerable ? 0xFFFF7A7A
				: (row.anticheatFlagged() ? 0xFFFFA8A8 : 0xFF93F7A4)));
		}
		
		lines.add(new Line("", 0xFFFFFFFF));
		lines.add(new Line("Recent events", 0xFFD8D8D8));
		List<String> commandEvents =
			UiUtilsCommandScanner.getRecentEventsSnapshot();
		List<String> pluginEvents =
			UiUtilsPluginScanner.getRecentEventsSnapshot();
		for(int i = Math.max(0, commandEvents.size() - 8); i < commandEvents
			.size(); i++)
			lines.add(new Line("CMD: " + commandEvents.get(i), 0xFFAAAAAA));
		for(int i = Math.max(0, pluginEvents.size() - 8); i < pluginEvents
			.size(); i++)
			lines.add(new Line("PLG: " + pluginEvents.get(i), 0xFFAAAAAA));
		List<String> legacyEvents =
			UiUtilsLegacyPluginScanner.getRecentEventsSnapshot();
		for(int i = Math.max(0, legacyEvents.size() - 8); i < legacyEvents
			.size(); i++)
			lines.add(new Line("LGC: " + legacyEvents.get(i), 0xFFAAAAAA));
		
		String query = searchField == null ? ""
			: searchField.getValue().trim().toLowerCase();
		if(query.isEmpty())
			return lines;
		
		List<Line> filtered = new ArrayList<>();
		for(Line line : lines)
			if(line.text.toLowerCase().contains(query))
				filtered.add(line);
		return filtered;
	}
	
	private void addDupeDbFinding(List<Line> lines,
		UiUtilsVulnerablePlugins.Finding finding, String indent)
	{
		String url = UiUtilsDupeDbDatabase.safeUrl(finding.url());
		lines.add(
			new Line(indent + finding.title() + (url == null ? "" : " [open]"),
				UiUtilsVulnerablePlugins.patchColor(finding.patched()),
				url == null ? null : "dupedb:url:" + url, true));
		for(String detail : finding.details())
			lines.add(new Line(indent + "  " + detail,
				UiUtilsVulnerablePlugins.patchDetailColor(detail, 0xFFD8D8D8)));
	}
	
	private void addSelectableCommandLine(List<Line> lines, String indent,
		String command)
	{
		if(command == null || command.isBlank())
			return;
		int color = UiUtilsCommandScanner.isClientVisibleCommand(command)
			? 0xFFAEEBFF : 0xFFFF7A7A;
		lines.add(new Line(
			indent + "/" + command
				+ UiUtilsPluginScanner.commandAttribution(command),
			color, "command:" + command));
	}
	
	private static String confidenceHeading(
		UiUtilsPluginScanner.PluginConfidence confidence)
	{
		return switch(confidence)
		{
			case HIGH -> "Detected plugins (high confidence)";
			case MEDIUM -> "Likely plugins (medium confidence)";
			case LOW -> "Possible plugins (weak evidence)";
		};
	}
	
	private static int confidenceHeadingColor(
		UiUtilsPluginScanner.PluginConfidence confidence)
	{
		return switch(confidence)
		{
			case HIGH -> 0xFF93F7A4;
			case MEDIUM -> 0xFFFFD479;
			case LOW -> 0xFFB8B8B8;
		};
	}
	
	private void selectCommand(String command)
	{
		if(packetCommandsField == null || command == null || command.isBlank())
			return;
		String value = command.startsWith("/") ? command.substring(1) : command;
		if(value.startsWith("trigger (") && value.endsWith(")"))
			value = "trigger "
				+ value.substring("trigger (".length(), value.length() - 1);
		packetCommandsField.setValue("/" + value);
		UiUtilsSettings.get().commandScannerPacketCommands =
			packetCommandsField.getValue();
		UiUtilsSettings.save();
		UiUtilsCommandScanner.clearManualCommandOutput();
		rebuildWidgetsPreservingScroll();
	}
	
	private static Map<String, VulnerableHit> collectVulnerableHits(
		List<UiUtilsPluginScanner.PluginResultRow> plugins)
	{
		Map<String, VulnerableHit> hits = new LinkedHashMap<>();
		Map<String, UiUtilsVulnerablePlugins.VulnerableEntry> vulnEntries =
			UiUtilsVulnerablePlugins.entriesByKey();
		for(UiUtilsPluginScanner.PluginResultRow row : plugins)
		{
			String key = UiUtilsVulnerablePlugins.normalizeKey(row.plugin());
			UiUtilsVulnerablePlugins.VulnerableEntry vuln =
				vulnEntries.get(key);
			if(vuln == null)
				continue;
			VulnerableHit hit = hits.computeIfAbsent(key,
				ignored -> new VulnerableHit(row.plugin()));
			hit.versions.addAll(vuln.versions());
		}
		return hits;
	}
	
	private record Line(String text, int color, String clickKey, boolean bold)
	{
		private Line(String text, int color, String clickKey)
		{
			this(text, color, clickKey, false);
		}
		
		private Line(String text, int color)
		{
			this(text, color, null);
		}
	}
	
	private record ClickTargetRow(String clickKey, int x, int y, int w, int h)
	{
		private boolean contains(double mx, double my)
		{
			return mx >= x && mx <= x + w && my >= y && my <= y + h;
		}
	}
	
	private static final class VulnerableHit
	{
		private final String displayName;
		private final Set<String> versions = new LinkedHashSet<>();
		
		private VulnerableHit(String displayName)
		{
			this.displayName = displayName;
		}
	}
	
}
