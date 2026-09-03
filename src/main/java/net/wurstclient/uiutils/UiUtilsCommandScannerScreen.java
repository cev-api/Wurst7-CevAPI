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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class UiUtilsCommandScannerScreen extends Screen
{
	private final Screen parent;
	private EditBox searchField;
	private EditBox packetCommandsField;
	private UiUtilsColoredButton scannerModeButton;
	private int resultsScroll;
	private int resultsTop;
	private int resultsBottom;
	private int commandOutputTop;
	private int commandOutputBottom;
	private boolean manualOutputVisible;
	private int panelLeft;
	private int panelWidth;
	private final Set<String> expandedPlugins = new HashSet<>();
	private final Set<String> expandedCommandLetters = new HashSet<>();
	private final List<ClickTargetRow> clickableRows = new ArrayList<>();
	private boolean vulnerableListExpanded;
	private boolean draggingScrollbar;
	private int scrollbarGrabOffset;
	private ScrollbarMetrics lastScrollbar = ScrollbarMetrics.none();
	
	public UiUtilsCommandScannerScreen(Screen parent)
	{
		super(Component.literal("ServerIntel"));
		this.parent = parent;
	}
	
	@Override
	protected void init()
	{
		panelWidth = Math.min(420, Math.max(260, this.width - 32));
		int left = (this.width - panelWidth) / 2;
		panelLeft = left;
		int rowH = 20;
		int gap = 4;
		boolean stacked = panelWidth < 360;
		int splitWidth = stacked ? panelWidth : (panelWidth - 10) / 2;
		
		int topRows = stacked ? 11 : 7;
		int controlsHeight = (rowH * topRows) + (gap * (topRows - 1));
		int footerHeight = rowH + gap;
		int outputHeight = 56;
		int desiredResultsHeight = Math.min(320, Math.max(100,
			this.height - controlsHeight - footerHeight - outputHeight - 68));
		int totalBlockHeight = controlsHeight + 8 + desiredResultsHeight + 8
			+ footerHeight + 8 + outputHeight;
		int blockTop = Math.max(8, (this.height - totalBlockHeight) / 2);
		int y = blockTop;
		
		scannerModeButton = addRenderableWidget(UiUtils.styledButton("", b -> {
			boolean packet = !"CLIENT_SIDE_ENUMERATION"
				.equalsIgnoreCase(UiUtilsSettings.get().commandScannerMode);
			UiUtilsSettings.get().commandScannerMode =
				packet ? "CLIENT_SIDE_ENUMERATION" : "PACKET_PROBING";
			UiUtilsSettings.save();
			refreshScannerModeLabel();
		}, left, y, splitWidth, rowH));
		refreshScannerModeLabel();
		
		addRenderableWidget(UiUtils.styledButton("Run command scan",
			b -> UiUtilsCommandScanner.startScan(),
			stacked ? left : left + splitWidth + 10, y, splitWidth, rowH));
		y += rowH + gap;
		if(stacked)
			y += rowH + gap;
		
		addRenderableWidget(UiUtils.styledButton("Command debug: "
			+ (UiUtilsSettings.get().commandScannerDebugProbe ? "ON" : "OFF"),
			b -> {
				UiUtilsSettings.get().commandScannerDebugProbe =
					!UiUtilsSettings.get().commandScannerDebugProbe;
				UiUtilsSettings.save();
				b.setMessage(Component.literal("Command debug: "
					+ (UiUtilsSettings.get().commandScannerDebugProbe ? "ON"
						: "OFF")));
			}, left, y, splitWidth, rowH));
		
		addRenderableWidget(UiUtils.styledButton("Run plugin scan",
			b -> UiUtilsPluginScanner.startScan(),
			stacked ? left : left + splitWidth + 10, y, splitWidth, rowH));
		y += rowH + gap;
		if(stacked)
			y += rowH + gap;
		
		addRenderableWidget(UiUtils.styledButton("Verbose Server Scan", b -> {
			UiUtilsScanHistory.recordVerboseFingerprint(
				UiUtilsScanHistory.serverKey(this.minecraft),
				UiUtilsServerFingerprintCollector.snapshot());
			McCompat.setScreen(this.minecraft,
				new UiUtilsVerboseServerScanScreen(this));
		}, left, y, panelWidth, rowH));
		y += rowH + gap;
		
		addRenderableWidget(UiUtils.styledButton("Legacy Plugin Scan (Safer)",
			b -> UiUtilsLegacyPluginScanner.startScan(), left, y, panelWidth,
			rowH));
		y += rowH + gap;
		
		searchField = new EditBox(this.font, left, y, splitWidth, rowH,
			Component.literal("Search results"));
		searchField.setMaxLength(64);
		searchField.setHint(Component.literal("Search results..."));
		addRenderableWidget(searchField);
		
		addRenderableWidget(UiUtils.styledButton("Run found cmds: "
			+ (UiUtilsSettings.get().commandScannerRunFoundCommands ? "ON"
				: "OFF"),
			b -> {
				UiUtilsSettings.get().commandScannerRunFoundCommands =
					!UiUtilsSettings.get().commandScannerRunFoundCommands;
				UiUtilsSettings.save();
				b.setMessage(Component.literal("Run found cmds: "
					+ (UiUtilsSettings.get().commandScannerRunFoundCommands
						? "ON" : "OFF")));
			}, stacked ? left : left + splitWidth + 10, y, splitWidth, rowH));
		y += rowH + gap;
		if(stacked)
			y += rowH + gap;
		
		packetCommandsField = new EditBox(this.font, left, y, splitWidth, rowH,
			Component.literal("Packet commands"));
		packetCommandsField.setMaxLength(256);
		packetCommandsField
			.setValue(UiUtilsSettings.get().commandScannerPacketCommands);
		addRenderableWidget(packetCommandsField);
		addRenderableWidget(UiUtils.styledButton("Send packet cmds", b -> {
			UiUtilsSettings.get().commandScannerPacketCommands =
				packetCommandsField.getValue();
			UiUtilsSettings.save();
			UiUtilsCommandScanner.sendManualPacketCommands();
			manualOutputVisible = true;
		}, stacked ? left : left + splitWidth + 10, y, splitWidth, rowH));
		y += rowH + gap;
		if(stacked)
			y += rowH + gap;
		
		resultsTop = y + 4;
		resultsBottom = resultsTop + desiredResultsHeight;
		
		int footerY = resultsBottom + 8;
		addRenderableWidget(UiUtils.styledButton("Clear results",
			b -> clearResults(), left, footerY, splitWidth, rowH));
		addRenderableWidget(UiUtils.styledButton("Done",
			b -> McCompat.setScreen(this.minecraft, parent),
			stacked ? left : left + splitWidth + 10, footerY, splitWidth,
			rowH));
		commandOutputTop = footerY + rowH + 8;
		commandOutputBottom = commandOutputTop + outputHeight;
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
		expandedPlugins.clear();
		expandedCommandLetters.clear();
		vulnerableListExpanded = false;
		resultsScroll = 0;
		draggingScrollbar = false;
	}
	
	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX,
		int mouseY, float partialTicks)
	{
		super.extractRenderState(graphics, mouseX, mouseY, partialTicks);
		
		int left = panelLeft;
		int top = resultsTop;
		int bottom = Math.max(top + 40, resultsBottom);
		
		graphics.fill(left, top, left + panelWidth, bottom, 0xAA000000);
		graphics.fill(left, top, left + panelWidth, top + 1, 0xFF2A2A2A);
		graphics.fill(left, bottom - 1, left + panelWidth, bottom, 0xFF2A2A2A);
		
		List<Line> lines = buildLines();
		clickableRows.clear();
		int lineHeight = this.font.lineHeight + 1;
		int visibleRows = Math.max(1, (bottom - top - 8) / lineHeight);
		int maxScroll = Math.max(0, lines.size() - visibleRows);
		if(resultsScroll > maxScroll)
			resultsScroll = maxScroll;
		
		int y = top + 4;
		for(int i = resultsScroll; i < lines.size()
			&& y + lineHeight <= bottom - 4; i++)
		{
			Line line = lines.get(i);
			graphics.text(this.font, line.text, left + 6, y, line.color, false);
			if(line.clickKey != null)
			{
				clickableRows.add(new ClickTargetRow(line.clickKey, left + 4,
					y - 1, panelWidth - 10, lineHeight + 1));
			}
			y += lineHeight;
		}
		
		lastScrollbar = computeScrollbar(left + panelWidth - 5, top + 2,
			bottom - 2, lines.size(), visibleRows, resultsScroll);
		renderScrollbar(graphics, lastScrollbar);
		renderCommandOutput(graphics);
	}
	
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX,
		double scrollY)
	{
		int left = panelLeft;
		if(mouseX < left || mouseX > left + panelWidth || mouseY < resultsTop
			|| mouseY > resultsBottom)
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		
		if(scrollY < 0)
			resultsScroll++;
		else if(scrollY > 0)
			resultsScroll = Math.max(0, resultsScroll - 1);
		return true;
	}
	
	@Override
	public boolean mouseClicked(MouseButtonEvent context, boolean doubleClick)
	{
		if(context.button() == 0)
		{
			double mouseX = context.x();
			double mouseY = context.y();
			
			if(lastScrollbar.hasScroll
				&& lastScrollbar.contains(mouseX, mouseY))
			{
				if(mouseY >= lastScrollbar.thumbY
					&& mouseY <= lastScrollbar.thumbY + lastScrollbar.thumbH)
				{
					draggingScrollbar = true;
					scrollbarGrabOffset = (int)Math.max(0,
						Math.round(mouseY) - lastScrollbar.thumbY);
				}else
				{
					jumpScrollToMouse((int)Math.round(mouseY), 0);
				}
				return true;
			}
			
			for(ClickTargetRow row : clickableRows)
			{
				if(!row.contains(mouseX, mouseY))
					continue;
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
				}else if("vuln:list".equals(row.clickKey))
				{
					vulnerableListExpanded = !vulnerableListExpanded;
				}else if(row.clickKey.startsWith("command:"))
				{
					selectCommand(row.clickKey.substring("command:".length()));
				}
				return true;
			}
		}
		return super.mouseClicked(context, doubleClick);
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
		manualOutputVisible = false;
	}
	
	@Override
	public boolean mouseDragged(MouseButtonEvent context, double dragX,
		double dragY)
	{
		if(draggingScrollbar && context.button() == 0
			&& lastScrollbar.hasScroll)
		{
			jumpScrollToMouse((int)Math.round(context.y()),
				scrollbarGrabOffset);
			return true;
		}
		return super.mouseDragged(context, dragX, dragY);
	}
	
	@Override
	public boolean mouseReleased(MouseButtonEvent context)
	{
		if(context.button() == 0 && draggingScrollbar)
		{
			draggingScrollbar = false;
			return true;
		}
		return super.mouseReleased(context);
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
		resultsScroll = Math.max(0,
			Math.min(maxScroll, (int)Math.round(ratio * maxScroll)));
	}
	
	@Override
	public void onClose()
	{
		McCompat.setScreen(this.minecraft, parent);
	}
	
	private List<Line> buildLines()
	{
		List<Line> lines = new ArrayList<>();
		lines.add(new Line("Command Scanner", 0xFF8CC8FF));
		lines.add(new Line("Status: " + UiUtilsCommandScanner.getStatusLine(),
			0xFFEAEAEA));
		lines.add(
			new Line("Essentials commands/aliases are intentionally skipped.",
				0xFF909090));
		List<String> commands =
			UiUtilsCommandScanner.getFoundCommandsSnapshot();
		String commandCount = UiUtilsCommandScanner.hasTruncatedResults()
			? commands.size() + "+" : String.valueOf(commands.size());
		lines.add(new Line("Found commands: " + commandCount, 0xFFB8B8B8));
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
		lines.add(new Line(
			(vulnerableListExpanded ? "v " : "> ") + "Vulnerable Plugins ("
				+ vulnerableHits.size() + ")",
			vulnerableHits.isEmpty() ? 0xFFB8B8B8 : 0xFFFF7A7A, "vuln:list"));
		if(vulnerableListExpanded)
		{
			for(VulnerableHit hit : vulnerableHits.values())
			{
				String versionText = hit.versions.isEmpty() ? ""
					: " (" + String.join(", ", hit.versions) + ")";
				lines.add(new Line("  - " + hit.displayName + versionText,
					0xFFFFA8A8));
			}
		}
		
		String currentEvidence = "";
		for(UiUtilsPluginScanner.PluginResultRow row : plugins)
		{
			if(!row.evidence().equalsIgnoreCase(currentEvidence))
			{
				currentEvidence = row.evidence();
				lines.add(new Line("[" + currentEvidence + "]", 0xFFF5F5F5));
			}
			String pluginKey = "plugin:" + row.evidence().toLowerCase() + "|"
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
				List<String> details = UiUtilsServerFingerprintCollector
					.detailsForSoftware(row.plugin());
				for(String detail : details)
					lines.add(new Line("    " + detail, 0xFFB8D8FF));
				if(row.commands().isEmpty())
				{
					if(details.isEmpty())
						lines.add(new Line(
							"    (no commands or cached details)", 0xFF909090));
				}else
				{
					for(String cmd : row.commands())
						addSelectableCommandLine(lines, "    ", cmd);
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
	
	private void addSelectableCommandLine(List<Line> lines, String indent,
		String command)
	{
		if(command == null || command.isBlank())
			return;
		int color = UiUtilsCommandScanner.isClientVisibleCommand(command)
			? 0xFFAEEBFF : 0xFFFF7A7A;
		lines
			.add(new Line(indent + "/" + command, color, "command:" + command));
	}
	
	private void renderCommandOutput(GuiGraphicsExtractor graphics)
	{
		if(!manualOutputVisible)
			return;
		int left = panelLeft;
		graphics.fill(left, commandOutputTop, left + panelWidth,
			commandOutputBottom, 0xAA000000);
		graphics.fill(left, commandOutputTop, left + panelWidth,
			commandOutputTop + 1, 0xFF2A2A2A);
		graphics.fill(left, commandOutputBottom - 1, left + panelWidth,
			commandOutputBottom, 0xFF2A2A2A);
		graphics.text(font, "Command output", left + 6, commandOutputTop + 4,
			0xFF8CC8FF, false);
		List<String> output =
			UiUtilsCommandScanner.getManualCommandOutputSnapshot();
		if(output.isEmpty())
		{
			graphics.text(font, "Waiting for a system response...", left + 6,
				commandOutputTop + 17, 0xFF909090, false);
			return;
		}
		int y = commandOutputTop + 17;
		int maxWidth = panelWidth - 12;
		for(int i = Math.max(0, output.size() - 3); i < output.size()
			&& y + font.lineHeight <= commandOutputBottom - 3; i++)
		{
			String line = font.plainSubstrByWidth(output.get(i), maxWidth);
			graphics.text(font, line, left + 6, y, 0xFFEAEAEA, false);
			y += font.lineHeight + 1;
		}
	}
	
	private ScrollbarMetrics computeScrollbar(int x, int top, int bottom,
		int totalRows, int visibleRows, int scroll)
	{
		int trackH = Math.max(1, bottom - top);
		if(totalRows <= visibleRows)
			return new ScrollbarMetrics(x, top, bottom, top, trackH, false,
				totalRows, visibleRows);
		double ratio = visibleRows / (double)Math.max(1, totalRows);
		int thumbH = Math.max(12, (int)Math.round(trackH * ratio));
		int maxScroll = Math.max(1, totalRows - visibleRows);
		int travel = Math.max(1, trackH - thumbH);
		int thumbY = top + (int)Math.round(
			(Math.max(0, Math.min(scroll, maxScroll)) / (double)maxScroll)
				* travel);
		return new ScrollbarMetrics(x, top, bottom, thumbY, thumbH, true,
			totalRows, visibleRows);
	}
	
	private void renderScrollbar(GuiGraphicsExtractor graphics,
		ScrollbarMetrics m)
	{
		graphics.fill(m.x, m.trackTop, m.x + 3, m.trackBottom, 0xFF353535);
		if(m.hasScroll)
			graphics.fill(m.x, m.thumbY, m.x + 3, m.thumbY + m.thumbH,
				draggingScrollbar ? 0xFFFFFFFF : 0xFFCFCFCF);
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
	
	private record Line(String text, int color, String clickKey)
	{
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
	
	private record ScrollbarMetrics(int x, int trackTop, int trackBottom,
		int thumbY, int thumbH, boolean hasScroll, int totalRows,
		int visibleRows)
	{
		private static ScrollbarMetrics none()
		{
			return new ScrollbarMetrics(0, 0, 0, 0, 0, false, 0, 0);
		}
		
		private boolean contains(double mx, double my)
		{
			return mx >= x && mx <= x + 3 && my >= trackTop
				&& my <= trackBottom;
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
