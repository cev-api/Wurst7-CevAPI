/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.Base64;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.minecraft.report.AbuseReport;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundEditBookPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.wurstclient.Category;
import net.wurstclient.events.ConnectionPacketOutputListener;
import net.wurstclient.events.ConnectionPacketOutputListener.ConnectionPacketOutputEvent;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.other_features.packettools.EntityLifecycleTracker;
import net.wurstclient.other_features.packettools.ItemInventoryForensics;
import net.wurstclient.other_features.packettools.PacketDecodeCoverage;
import net.wurstclient.other_features.packettools.PacketDumper;
import net.wurstclient.other_features.packettools.PacketFilter;
import net.wurstclient.other_features.packettools.ServerUpdatePhaseEstimator;
import net.minecraft.client.multiplayer.chat.report.ReportType;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.json.JsonUtils;
import org.slf4j.Logger;

public final class PacketToolsOtf extends OtherFeature
	implements PacketInputListener, PacketOutputListener,
	ConnectionPacketOutputListener, UpdateListener
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Gson VERBOSE_GSON = new GsonBuilder()
		.disableHtmlEscaping().serializeSpecialFloatingPointValues().create();
	private static final DateTimeFormatter ISO_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
	private static final DateTimeFormatter TIME_FORMAT =
		DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static final DateTimeFormatter FILE_TIME_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final Path CONFIG_FILE = FabricLoader.getInstance()
		.getConfigDir().resolve("wurst-packet-tools.json");
	private static final Path LOG_DIR =
		FabricLoader.getInstance().getConfigDir().resolve("packet-logger");
	
	private final CheckboxSetting loggingEnabled =
		new CheckboxSetting("Logging enabled", false)
		{
			@Override
			public void update()
			{
				forensicsDisconnected = false;
				syncForensicsState();
			}
		};
	private final CheckboxSetting denyEnabled =
		new CheckboxSetting("Deny enabled", false);
	private final CheckboxSetting delayEnabled =
		new CheckboxSetting("Delay enabled", false);
	private final CheckboxSetting fileOutput =
		new CheckboxSetting("Log to file", false);
	private final CheckboxSetting showUnknownPackets =
		new CheckboxSetting("Show unknown packets", false);
	private final SliderSetting delayTicks = new SliderSetting("Delay ticks",
		"How many ticks to hold packets selected for delay.", 5, 0, 9999, 1,
		ValueDisplay.INTEGER);
	private final ButtonSetting openUiButton =
		new ButtonSetting("Open Packet Tools UI", this::openScreen);
	
	// ---- Verbose mode ----
	private final CheckboxSetting verboseEnabled = new CheckboxSetting(
		"Verbose logging",
		"When enabled, packets selected for logging are dumped with\n"
			+ "full per-field detail (JSONL + optional human-readable)\n"
			+ "instead of a simple one-line toString().\n"
			+ "Honors the same per-packet Log selections in the UI.",
		false);
	private final CheckboxSetting monitorUdp =
		new CheckboxSetting("Monitor UDP",
			"Monitor UDP traffic observed through optional client protocol\n"
				+ "integrations and write it to a separate UDP JSONL log.",
			false);
	private final CheckboxSetting verboseOutsideGamePackets =
		new CheckboxSetting("Monitor outside game",
			"Also dump packets while the client is still connecting or\n"
				+ "before the world loads, and log chat report submissions.\n"
				+ "This logs every outside-game packet verbosely, even if it\n"
				+ "isn't selected in the UI.",
			false);
	private final CheckboxSetting verboseHumanReadable =
		new CheckboxSetting("Verbose human-readable",
			"Also write human-readable logs alongside JSONL.", false);
	private final SliderSetting verboseFlushInterval =
		new SliderSetting("Verbose flush ticks",
			"How often to flush the verbose buffer to disk (in ticks).\n"
				+ "Higher values reduce disk I/O but use more memory.",
			20, 1, 200, 1, ValueDisplay.INTEGER);
	private final ButtonSetting showCoverageReport =
		new ButtonSetting("Show coverage report", this::printCoverageReport);
	private final ButtonSetting showEntitySummary =
		new ButtonSetting("Show entity summary", this::printEntitySummary);
	private final CheckboxSetting itemForensicsEnabled = new CheckboxSetting(
		"Enable Item & Inventory Forensics",
		"Requires Logging enabled. While main logging is OFF, forensics is inactive. Captures all observable inventory activity independently of packet filters.",
		false)
	{
		@Override
		public void update()
		{
			forensicsDisconnected = false;
			syncForensicsState();
		}
	};
	// ### MODIFIED ### Coverage is automatic whenever the master toggle is on.
	private final CheckboxSetting forensicFullContent = new CheckboxSetting(
		"Export full item content",
		"Includes full component payloads in forensic records. Large books and container contents can use substantial disk space.",
		false);
	private static final int MAX_FORENSIC_CONTENT_BYTES = 1024 * 1024;
	private final CheckboxSetting forensicRedactServerAddress =
		new CheckboxSetting("Redact server address in report",
			"Omits the server address from the exported session summary.",
			true);
	private final ButtonSetting exportForensicsSummary = new ButtonSetting(
		"Export forensics session summary", this::exportForensicsSummary);
	
	private final PacketDecodeCoverage decodeCoverage =
		new PacketDecodeCoverage();
	private final EntityLifecycleTracker lifecycleTracker =
		new EntityLifecycleTracker();
	private final PacketFilter packetFilter = new PacketFilter();
	private PacketDumper packetDumper;
	private volatile ForensicActivation activeForensics;
	private final ServerUpdatePhaseEstimator bookBotPhaseEstimator =
		new ServerUpdatePhaseEstimator();
	private final java.util.concurrent.ConcurrentLinkedQueue<net.wurstclient.other_features.packettools.ForensicSessionWriter> endingForensics =
		new java.util.concurrent.ConcurrentLinkedQueue<>();
	private boolean forensicsLifecycleReady;
	private boolean forensicsDisconnected;
	private volatile long diagnosticClientTick;
	private volatile long recentTickDurationNs;
	private volatile Map<String, Object> performanceContext = Map.of();
	private volatile long clientTickStartedNs;
	private final ThreadLocal<Map<String, Object>> actionContext =
		ThreadLocal.withInitial(Map::of);
	
	private Path currentVerboseJsonlFile;
	private Path currentVerboseHumanFile;
	private Path currentUdpJsonlFile;
	// Batched write buffers â€“ flushed in onUpdate() to avoid per-packet disk
	// I/O
	private final ArrayList<String> verboseJsonlBuffer = new ArrayList<>(512);
	private final ArrayList<String> verboseHumanBuffer = new ArrayList<>(512);
	// ### ADDED ### Bound retained diagnostic data during prolonged write
	// failures.
	private static final int MAX_VERBOSE_BUFFER_LINES = 10_000;
	private long verboseDroppedRecords;
	private int verboseFlushTickCounter;
	private final ArrayList<String> udpJsonlBuffer = new ArrayList<>(512);
	private static final int MAX_UDP_BUFFERED_RECORDS = 4096;
	private boolean lastUdpMonitoringState;
	private boolean udpBufferOverflowReported;
	
	private final Set<String> logS2C = new LinkedHashSet<>();
	private final Set<String> logC2S = new LinkedHashSet<>();
	private final Set<String> denyS2C = new LinkedHashSet<>();
	private final Set<String> denyC2S = new LinkedHashSet<>();
	private final Set<String> delayS2C = new LinkedHashSet<>();
	private final Set<String> delayC2S = new LinkedHashSet<>();
	
	private final ArrayDeque<QueuedPacket> delayedIncoming = new ArrayDeque<>();
	private final ArrayDeque<QueuedPacket> delayedOutgoing = new ArrayDeque<>();
	private final Set<Packet<?>> bypassOutput =
		Collections.newSetFromMap(new IdentityHashMap<>());
	
	private final TreeSet<String> discoveredS2C =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private final TreeSet<String> discoveredC2S =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private final TreeSet<String> discoveredUnknownS2C =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	private final TreeSet<String> discoveredUnknownC2S =
		new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
	
	private boolean lastDelayEnabledState;
	private boolean lastLoggingState;
	private boolean lastDenyEnabledState;
	private Path currentLogFile;
	
	public PacketToolsOtf()
	{
		super("AdvancedPacketTool",
			"description.wurst.other_feature.packet_tools");
		
		addSetting(loggingEnabled);
		addSetting(denyEnabled);
		addSetting(delayEnabled);
		addSetting(fileOutput);
		addSetting(showUnknownPackets);
		addSetting(delayTicks);
		addSetting(openUiButton);
		addSetting(verboseEnabled);
		addSetting(monitorUdp);
		addSetting(verboseOutsideGamePackets);
		addSetting(verboseHumanReadable);
		addSetting(verboseFlushInterval);
		addSetting(showCoverageReport);
		addSetting(showEntitySummary);
		addSetting(itemForensicsEnabled);
		addSetting(forensicFullContent);
		addSetting(forensicRedactServerAddress);
		addSetting(exportForensicsSummary);
		
		loadSelectionConfig();
		
		lastDelayEnabledState = delayEnabled.isChecked();
		lastLoggingState = loggingEnabled.isChecked();
		lastDenyEnabledState = denyEnabled.isChecked();
		lastUdpMonitoringState = monitorUdp.isChecked();
		forensicsLifecycleReady = true;
		syncForensicsState();
		
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(ConnectionPacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	public String getHackListName()
	{
		ArrayList<String> modes = new ArrayList<>(3);
		if(loggingEnabled.isChecked())
			modes.add("Logging");
		if(denyEnabled.isChecked())
			modes.add("Deny");
		if(delayEnabled.isChecked())
			modes.add("Delay");
		if(modes.isEmpty())
			return getName();
		return getName() + " [" + String.join(", ", modes) + "]";
	}
	
	public boolean hasHackListStatus()
	{
		return loggingEnabled.isChecked() || denyEnabled.isChecked()
			|| delayEnabled.isChecked();
	}
	
	@Override
	public Category getCategory()
	{
		return Category.TOOLS;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		Packet<?> packet = event.getPacket();
		recordForensicPacket(packet, "S2C", "PACKETTOOLS_OBSERVED", List.of());
		String name = net.wurstclient.other_features.packettools.PacketCatalog
			.formatPacketName(packet);
		discoveredS2C.add(name);
		recordUnknown(packet.getClass().getSimpleName(), PacketDirection.S2C);
		
		if(shouldVerboseOutsideGame())
		{
			logVerbose(packet, "OUTSIDE-S2C");
			flushVerboseBuffers();
		}else if(loggingEnabled.isChecked() && logS2C.contains(name))
			logPacket(name, "S2C", packet);
		
		if(denyEnabled.isChecked() && denyS2C.contains(name))
		{
			recordForensicTiming(packet, "S2C", "DENIED");
			event.cancel();
			return;
		}
		
		if(delayEnabled.isChecked() && delayTicks.getValueI() > 0
			&& delayS2C.contains(name))
		{
			event.cancel();
			recordForensicTiming(packet, "S2C", "QUEUED");
			delayedIncoming.addLast(new QueuedPacket(packet,
				getCurrentTick() + delayTicks.getValueI()));
		}
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		Packet<?> packet = event.getPacket();
		recordForensicPacket(packet, "C2S", "PACKETTOOLS_CLIENT_SEND",
			event.getDebugTrace());
		if(bypassOutput.remove(packet))
			return;
		
		String name = net.wurstclient.other_features.packettools.PacketCatalog
			.formatPacketName(packet);
		discoveredC2S.add(name);
		recordUnknown(packet.getClass().getSimpleName(), PacketDirection.C2S);
		
		boolean alwaysVerbose =
			verboseEnabled.isChecked() && shouldAlwaysVerbose(packet);
		if(alwaysVerbose)
		{
			logVerbose(packet, "C2S", event.getDebugTrace());
		}
		
		if(!alwaysVerbose && shouldVerboseOutsideGame())
		{
			logVerbose(packet, "OUTSIDE-C2S", event.getDebugTrace());
			flushVerboseBuffers();
		}else if(!alwaysVerbose && loggingEnabled.isChecked()
			&& logC2S.contains(name))
			logPacket(name, "C2S", packet);
		
		if(denyEnabled.isChecked() && denyC2S.contains(name))
		{
			recordForensicTiming(packet, "C2S", "DENIED");
			event.cancel();
			return;
		}
		
		if(delayEnabled.isChecked() && delayTicks.getValueI() > 0
			&& delayC2S.contains(name))
		{
			event.cancel();
			recordForensicTiming(packet, "C2S", "QUEUED");
			delayedOutgoing.addLast(new QueuedPacket(packet,
				getCurrentTick() + delayTicks.getValueI()));
		}
	}
	
	@Override
	public void onSentConnectionPacket(ConnectionPacketOutputEvent event)
	{
		recordForensicPacket(event.getPacket(), "C2S",
			"PACKETTOOLS_CONNECTION_SEND", event.getDebugTrace());
		if(!shouldVerboseExternalMonitoring())
			return;
		
		Packet<?> packet = event.getPacket();
		String name = net.wurstclient.other_features.packettools.PacketCatalog
			.formatPacketName(packet);
		discoveredC2S.add(name);
		recordUnknown(packet.getClass().getSimpleName(), PacketDirection.C2S);
		logVerbose(packet, "OUTSIDE-C2S", event.getDebugTrace());
		if(isOutsideGame())
			flushVerboseBuffers();
	}
	
	@Override
	public void onUpdate()
	{
		boolean loggingState = loggingEnabled.isChecked();
		boolean denyState = denyEnabled.isChecked();
		boolean delayState = delayEnabled.isChecked();
		boolean previousLoggingState = lastLoggingState;
		boolean previousDelayState = lastDelayEnabledState;
		boolean previousUdpMonitoringState = lastUdpMonitoringState;
		boolean udpMonitoringState = monitorUdp.isChecked();
		announceToggleChange("Logging", loggingState, previousLoggingState);
		announceToggleChange("Blocking", denyState, lastDenyEnabledState);
		announceToggleChange("Delaying", delayState, previousDelayState);
		lastLoggingState = loggingState;
		lastDenyEnabledState = denyState;
		lastDelayEnabledState = delayState;
		lastUdpMonitoringState = udpMonitoringState;
		tickForensics();
		
		boolean delayActive = delayState && delayTicks.getValueI() > 0;
		if(!delayActive && previousDelayState)
			flushQueues(true);
		else if(delayActive)
			flushQueues(false);
		
		if(previousLoggingState && !loggingState)
		{
			// ### MODIFIED ### Flush records captured before logging was
			// disabled.
			flushVerboseBuffers();
			currentLogFile = null;
			currentVerboseJsonlFile = null;
			currentVerboseHumanFile = null;
		}
		if(previousUdpMonitoringState && !udpMonitoringState)
		{
			flushUdpBuffer();
			currentUdpJsonlFile = null;
			udpBufferOverflowReported = false;
		}
		if(udpMonitoringState)
			flushUdpBuffer();
		
		// Tick entity lifecycle tracker
		if(verboseEnabled.isChecked() && loggingState)
		{
			lifecycleTracker.tick();
			
			// Flush verbose buffers periodically
			int flushInterval = Math.max(1, verboseFlushInterval.getValueI());
			verboseFlushTickCounter++;
			if(verboseFlushTickCounter >= flushInterval)
			{
				verboseFlushTickCounter = 0;
				flushVerboseBuffers();
			}
		}else if(!verboseEnabled.isChecked() && lastVerboseState
			&& loggingState)
		{
			// Verbose just turned off â€” flush remaining and clear refs
			flushVerboseBuffers();
			currentVerboseJsonlFile = null;
			currentVerboseHumanFile = null;
		}
		lastVerboseState = verboseEnabled.isChecked();
	}
	
	private void announceToggleChange(String name, boolean current,
		boolean previous)
	{
		if(current == previous)
			return;
		
		ChatUtils.message(
			"[PacketTools] " + name + (current ? " activated." : " disabled."));
	}
	
	/**
	 * Settings callbacks stop admission immediately, including while the world
	 * is paused.
	 */
	private synchronized void syncForensicsState()
	{
		if(!forensicsLifecycleReady)
			return;
		boolean wanted =
			net.wurstclient.other_features.packettools.ForensicSessionWriter
				.effectiveEnabled(loggingEnabled.isChecked(),
					itemForensicsEnabled.isChecked());
		if(!wanted && activeForensics != null)
			endForensics("logging_disabled");
		else if(wanted && activeForensics == null && !forensicsDisconnected)
		{
			ItemInventoryForensics model = new ItemInventoryForensics();
			model.startNewSession();
			var timings =
				new net.wurstclient.other_features.packettools.PacketTimingTracker(
					model.getSessionId());
			Map<String, Object> settings = new LinkedHashMap<>();
			settings.put("coverage", "all_client_observable_item_state");
			settings.put("includeFullContent", forensicFullContent.isChecked());
			settings.put("serverAddress",
				forensicRedactServerAddress.isChecked()
					|| MC.getCurrentServer() == null ? "redacted"
						: MC.getCurrentServer().ip);
			settings.put("contentDeduplication", true);
			var writer =
				new net.wurstclient.other_features.packettools.ForensicSessionWriter(
					LOG_DIR.resolve("item-forensics"), model, settings,
					timings);
			activeForensics = new ForensicActivation(model, writer, timings,
				forensicFullContent.isChecked());
		}
	}
	
	private synchronized void endForensics(String reason)
	{
		ForensicActivation ending = activeForensics;
		activeForensics = null;
		if(ending == null)
			return;
		ending.writer.finish(reason);
		endingForensics.add(ending.writer);
	}
	
	public String getForensicsStatus()
	{
		return activeForensics != null ? "Forensics: active"
			: itemForensicsEnabled.isChecked() ? "Forensics: dormant"
				: "Forensics: off";
	}
	
	private void tickForensics()
	{
		diagnosticClientTick++;
		if(MC.player != null)
			forensicsDisconnected = false;
		syncForensicsState();
		endingForensics.removeIf(
			net.wurstclient.other_features.packettools.ForensicSessionWriter::isFinished);
		ForensicActivation a = activeForensics;
		if(a == null)
			return;
		if(a.sawWorld && (MC.player == null || MC.level == null))
		{
			forensicsDisconnected = true;
			endForensics("disconnected");
			return;
		}
		a.sawWorld |= MC.player != null;
		Map<String, Object> performance = new LinkedHashMap<>();
		performance.put("contextObservedNs", System.nanoTime());
		performance.put("fps", MC.getFps());
		if(MC.player != null)
			performance.put("playerPosition", Map.of("x", MC.player.getX(), "y",
				MC.player.getY(), "z", MC.player.getZ()));
		if(recentTickDurationNs > 0)
			performance.put("recentClientTickDurationNs", recentTickDurationNs);
		performance.put("paused", MC.isPaused());
		performance.put("connectionState",
			MC.getConnection() == null ? "disconnected" : "connected");
		if(MC.level != null)
			performance.put("dimension",
				MC.level.dimension().identifier().toString());
		if(MC.player != null && MC.getConnection() != null)
		{
			var info = MC.getConnection().getPlayerInfo(MC.player.getUUID());
			if(info != null)
			{
				performance.put("pingMs", info.getLatency());
				performance.put("pingSource",
					"server_reported_player_info_not_packet_specific_RTT");
			}
		}
		performanceContext = Map.copyOf(performance);
		a.dimension = MC.level == null ? "UNOBSERVED"
			: MC.level.dimension().identifier().toString();
		var state = net.wurstclient.other_features.packettools.ForensicSnapshots
			.world(MC, diagnosticClientTick);
		a.submit("client_tick", state.observedNs(), state.estimatedBytes(),
			() -> a.model.reconcileCaptured(state, true, a.fullContent,
				MAX_FORENSIC_CONTENT_BYTES, performance));
		for(var reference : a.nextTickPackets)
		{
			Packet<?> packet = reference.get();
			if(packet != null)
				a.observe(packet, "S2C", "NEXT_CLIENT_TICK");
		}
		a.nextTickPackets.clear();
	}
	
	public void recordClientTickStart(long now)
	{
		clientTickStartedNs = now;
	}
	
	public void recordClientTickDuration(long durationNs)
	{
		recentTickDurationNs = durationNs;
	}
	
	public void recordForensicTiming(Packet<?> packet, String direction,
		String stage)
	{
		if(packet instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket bundle
			&& (stage.equals("DECODED") || stage.equals("INBOUND_CONNECTION")
				|| stage.equals("CLIENT_DISPATCH")))
		{
			for(Packet<?> child : bundle.subPackets())
				recordForensicTiming(child, direction, stage);
			return;
		}
		ForensicActivation a = activeForensics;
		if(packet instanceof net.minecraft.network.protocol.game.ClientboundSetTimePacket
			&& direction.equals("S2C") && stage.equals("DECODED"))
			bookBotPhaseEstimator.observe(System.nanoTime());
		if(packet.type()
			.flow() != (direction.equals("S2C")
				? net.minecraft.network.protocol.PacketFlow.CLIENTBOUND
				: net.minecraft.network.protocol.PacketFlow.SERVERBOUND))
			return;
		if(a != null && isForensicPacket(packet))
		{
			boolean tickPacket =
				packet instanceof net.minecraft.network.protocol.game.ClientboundSetTimePacket
					|| packet instanceof net.minecraft.network.protocol.game.ClientboundTickingStatePacket
					|| packet instanceof net.minecraft.network.protocol.game.ClientboundTickingStepPacket;
			if(tickPacket
				&& (stage.equals("DECODED")
					|| stage.equals("INBOUND_CONNECTION"))
				&& !a.timings.hasStage(packet, "packetArrivalNs"))
			{
				Map<String, Object> trace = a.trace(packet, direction, stage);
				long now = ((Number)trace.get("packetArrivalNs")).longValue();
				List<Map<String, Object>> events;
				if(packet instanceof net.minecraft.network.protocol.game.ClientboundSetTimePacket time)
					events = a.telemetry.passive.sample(time.gameTime(), now,
						a.dimension, diagnosticClientTick,
						(String)trace.get("logicalPacketId"));
				else if(packet instanceof net.minecraft.network.protocol.game.ClientboundTickingStatePacket control)
					events = a.telemetry.passive.control(control.tickRate(),
						control.isFrozen(), false, now);
				else
					events = a.telemetry.passive.step(now, 0);
				a.emitTickEvents(events, trace);
				return;
			}
			a.observe(packet, direction, stage);
		}
	}
	
	private static boolean isForensicPacket(Packet<?> packet)
	{
		String name = packet.getClass().getSimpleName()
			.toLowerCase(java.util.Locale.ROOT);
		return packet instanceof net.minecraft.network.protocol.game.ClientboundSetTimePacket
			|| packet instanceof net.minecraft.network.protocol.game.ClientboundTickingStatePacket
			|| packet instanceof net.minecraft.network.protocol.game.ClientboundTickingStepPacket
			|| name.contains("container") || name.contains("inventory")
			|| name.contains("item") || name.contains("equipment")
			|| name.contains("book") || name.contains("interact")
			|| name.contains("block") || name.contains("entitydata")
			|| name.contains("playeraction") || name.contains("recipe")
			|| name.contains("creative");
	}
	
	private void recordForensicPacket(Packet<?> packet, String direction,
		String stage, List<String> debug)
	{
		if(packet instanceof net.minecraft.network.protocol.game.ClientboundBundlePacket bundle)
		{
			for(Packet<?> child : bundle.subPackets())
				recordForensicPacket(child, direction, stage, debug);
			return;
		}
		ForensicActivation a = activeForensics;
		if(a == null || !isForensicPacket(packet))
			return;
		Map<String, Object> trace = a.trace(packet, direction, stage);
		String id = (String)trace.get("logicalPacketId");
		boolean details;
		synchronized(a.decoded)
		{
			details = a.decoded.add(id);
			if(a.decoded.size() > 16384)
				a.decoded.remove(a.decoded.iterator().next());
		}
		Packet<?> frozen = details
			? net.wurstclient.other_features.packettools.ForensicSnapshots
				.freezePacket(packet)
			: null;
		List<String> debugCopy = List.copyOf(debug);
		a.submit("packet_observation",
			((Number)trace.get("monotonicNs")).longValue(),
			details
				? net.wurstclient.other_features.packettools.ForensicSnapshots
					.estimatedPacketBytes(frozen)
				: 1024,
			() -> {
				if(details)
					a.model.withObservation(trace,
						() -> a.model.recordPacket(frozen, direction, stage,
							debugCopy, a.dumper, a.fullContent,
							MAX_FORENSIC_CONTENT_BYTES, false, 2000));
				else
					a.model.recordObservation(trace);
			});
	}
	
	public void recordForensicsPreApplication(Packet<?> packet)
	{
		ForensicActivation a = activeForensics;
		if(a == null || !MC.isSameThread() || !isForensicPacket(packet))
			return;
		if(a.timings.hasStage(packet, "handlerStartNs"))
			return;
		Map<String, Object> timing = a.trace(packet, "S2C", "HANDLER_START");
		Map<String, Object> publishedStart =
			net.wurstclient.other_features.packettools.ForensicSnapshots
				.freezeFields(timing);
		a.submit("packet_timing", System.nanoTime(), 1024,
			() -> a.model.recordObservation(publishedStart));
		var state = net.wurstclient.other_features.packettools.ForensicSnapshots
			.affected(MC, packet, diagnosticClientTick);
		a.attachSlotActions(timing, state);
		if(!state.stacks().isEmpty())
			a.submit("handler_pre_state", state.observedNs(),
				state.estimatedBytes(), () -> a.model.reconcileCaptured(state,
					false, a.fullContent, MAX_FORENSIC_CONTENT_BYTES, timing));
	}
	
	public void recordForensicsPostApplication(Packet<?> packet)
	{
		ForensicActivation a = activeForensics;
		if(a == null || !MC.isSameThread() || !isForensicPacket(packet)
			|| a.timings.hasStage(packet, "stateAppliedNs"))
			return;
		var state = net.wurstclient.other_features.packettools.ForensicSnapshots
			.affected(MC, packet, diagnosticClientTick);
		if(state.stacks().isEmpty())
		{
			if(packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket block
				&& MC.level != null)
			{
				Map<String, Object> applied =
					a.trace(packet, "S2C", "STATE_APPLIED");
				applied.put("blockPosition", block.getPos().toShortString());
				applied.put("observedBlockState",
					net.wurstclient.other_features.packettools.RegistryDecoder
						.decodeBlockStateDetailed(
							MC.level.getBlockState(block.getPos())));
				a.submit("block_state_applied", System.nanoTime(), 2048,
					() -> a.model.recordObservation(applied));
			}
			return;
		}
		Map<String, Object> timing = a.trace(packet, "S2C", "STATE_APPLIED");
		a.attachSlotActions(timing, state);
		a.submit("handler_post_state", state.observedNs(),
			state.estimatedBytes(), () -> a.model.reconcileCaptured(state,
				false, a.fullContent, MAX_FORENSIC_CONTENT_BYTES, timing));
		if(a.nextTickPackets.size() < 4096)
			a.nextTickPackets.add(new java.lang.ref.WeakReference<>(packet));
	}
	
	public void recordForensicHandlerEnd(Packet<?> packet)
	{
		recordForensicTiming(packet, "S2C", "HANDLER_END");
		recordForensicsPostApplication(packet);
	}
	
	public void recordForensicsExternalEvent(String name,
		Map<String, Object> fields)
	{
		ForensicActivation a = activeForensics;
		if(a == null)
			return;
		Map<String, Object> event = new LinkedHashMap<>(performanceContext);
		event.putAll(fields);
		event.put("name", name);
		event.put("eventType", "external_forensics_event");
		event.put("monotonicNs", System.nanoTime());
		event.put("timestamp", java.time.Instant.now().toString());
		event.put("thread", Thread.currentThread().getName());
		event.put("clientTick", diagnosticClientTick);
		a.submit("external_forensics_event", System.nanoTime(), 8192,
			() -> a.model.recordObservation(event));
	}
	
	public Map<String, Object> beginForensicTransaction(String transactionId)
	{
		var previous = actionContext.get();
		if(transactionId != null)
			actionContext.set(Map.of("transactionId", transactionId));
		return previous;
	}
	
	public void endForensicTransaction(Map<String, Object> previous)
	{
		actionContext.set(previous);
	}
	
	public Map<String, Object> beginForensicInteraction(
		net.minecraft.world.phys.BlockHitResult hit,
		net.minecraft.world.InteractionHand hand)
	{
		Map<String, Object> previous = actionContext.get();
		if(activeForensics == null)
			return previous;
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("transactionId", actionContext.get()
			.getOrDefault("transactionId", "interaction-" + UUID.randomUUID()));
		fields.put("actionRequestedNs", System.nanoTime());
		fields.put("monotonicNs", fields.get("actionRequestedNs"));
		fields.put("timestamp", java.time.Instant.now().toString());
		fields.put("thread", Thread.currentThread().getName());
		fields.put("clientTick", diagnosticClientTick);
		fields.put("blockPosition", hit.getBlockPos().toShortString());
		fields.put("hitFace", hit.getDirection().getName());
		fields.put("hitPosition", Map.of("x", hit.getLocation().x, "y",
			hit.getLocation().y, "z", hit.getLocation().z));
		fields.put("hand", hand.name());
		if(MC.level != null)
			fields.put("blockState",
				net.wurstclient.other_features.packettools.RegistryDecoder
					.decodeBlockStateDetailed(
						MC.level.getBlockState(hit.getBlockPos())));
		if(MC.player != null)
			fields.put("submittedItemId",
				net.minecraft.core.registries.BuiltInRegistries.ITEM
					.getKey(MC.player.getItemInHand(hand).getItem())
					.toString());
		actionContext.set(Map.copyOf(fields));
		ForensicActivation owner = activeForensics;
		if(owner != null)
		{
			synchronized(owner.relatedActions)
			{
				owner.relatedActions.put(
					"block:" + hit.getBlockPos().toShortString(),
					Map.copyOf(fields));
				while(owner.relatedActions.size() > 256)
					owner.relatedActions.remove(
						owner.relatedActions.keySet().iterator().next());
			}
			ItemStack submitted = MC.player == null ? ItemStack.EMPTY
				: MC.player.getItemInHand(hand).copy();
			var registries =
				MC.level == null ? null : MC.level.registryAccess();
			var targetState =
				net.wurstclient.other_features.packettools.ForensicSnapshots
					.block(MC, hit.getBlockPos(), diagnosticClientTick);
			owner.submit("interaction_item_snapshot", System.nanoTime(),
				targetState.estimatedBytes() + 65536, () -> {
					Map<String, Object> event = new LinkedHashMap<>(fields);
					event.put("eventType", "interaction_item_snapshot");
					var item =
						net.wurstclient.other_features.packettools.ItemStackForensics
							.capture(submitted, true, owner.fullContent,
								MAX_FORENSIC_CONTENT_BYTES, registries);
					event.put("submittedItemFingerprint", item.fingerprint());
					event.put("submittedItem", item.details());
					owner.model.recordObservation(event);
					owner.model.reconcileCaptured(targetState, false,
						owner.fullContent, MAX_FORENSIC_CONTENT_BYTES, fields);
				});
		}
		recordForensicsExternalEvent("block_interaction_requested", fields);
		return previous;
	}
	
	public void recordForensicInteractionResult(
		net.minecraft.world.InteractionResult result)
	{
		if(activeForensics == null)
			return;
		Map<String, Object> fields = new LinkedHashMap<>(actionContext.get());
		fields.put("clientInteractionResult", String.valueOf(result));
		fields.put("storageOutcome", "UNKNOWN_UNTIL_SERVER_STATE");
		recordForensicsExternalEvent("block_interaction_returned", fields);
	}
	
	public void endForensicInteraction(Map<String, Object> previous)
	{
		recordForensicsExternalEvent("block_interaction_method_completed",
			actionContext.get());
		actionContext.set(previous);
	}
	
	public Map<String, Object> getBookBotPeriodicPhase()
	{
		return bookBotPhaseEstimator.snapshot(System.nanoTime());
	}
	
	public String beginBookBotForensicAttempt()
	{
		return activeForensics == null ? null
			: "BookBot.edit-" + UUID.randomUUID();
	}
	
	public String recordForensicActionRequested(Packet<?> packet, String action)
	{
		return recordForensicActionRequested(packet, action, null);
	}
	
	public String recordForensicActionRequested(Packet<?> packet, String action,
		String transactionId)
	{
		ForensicActivation a = activeForensics;
		if(a == null)
			return null;
		Map<String, Object> fields = new LinkedHashMap<>(performanceContext);
		fields.put("transactionId", transactionId == null
			? action + "-" + UUID.randomUUID() : transactionId);
		Map<String, Object> event =
			a.timings.observe(packet, "C2S", "ACTION_REQUESTED",
				System.nanoTime(), diagnosticClientTick, fields);
		if(packet instanceof net.minecraft.network.protocol.game.ServerboundEditBookPacket edit)
		{
			Map<String, Object> related =
				Map.of("transactionId", event.get("transactionId"),
					"actionRequestedNs", event.get("actionRequestedNs"));
			synchronized(a.relatedActions)
			{
				a.relatedActions.put("backing:" + edit.slot(), related);
				a.relatedActions.put(
					"slot:"
						+ (edit.slot() < 9 ? 36 + edit.slot() : edit.slot()),
					related);
				while(a.relatedActions.size() > 256)
					a.relatedActions
						.remove(a.relatedActions.keySet().iterator().next());
			}
		}
		a.submit("action_requested", System.nanoTime(), 1024,
			() -> a.model.recordObservation(event));
		return (String)event.get("transactionId");
	}
	
	/**
	 * Callback ownership stays with the original session even across rapid
	 * toggles.
	 */
	public java.util.function.BiConsumer<Boolean, String> recordForensicTransportSubmitted(
		Packet<?> packet, boolean completionAvailable)
	{
		ForensicActivation a = activeForensics;
		if(a == null || !isForensicPacket(packet))
			return (success, error) -> {};
		Map<String, Object> event =
			a.trace(packet, "C2S", "TRANSPORT_SUBMITTED");
		event.put("writeCompletionObservable", completionAvailable);
		a.submit("transport_submit", System.nanoTime(), 1024,
			() -> a.model.recordObservation(event));
		return (success, error) -> {
			Map<String, Object> completed = a.timings.transportCompleted(event,
				System.nanoTime(), diagnosticClientTick, success, error);
			a.submit("transport_completion", System.nanoTime(), 1024,
				() -> a.model.recordObservation(completed));
		};
	}
	
	public void recordForensicWorldContext()
	{
		ForensicActivation a = activeForensics;
		if(a == null || MC.level == null || !MC.isSameThread())
			return;
		a.dimension = MC.level.dimension().identifier().toString();
	}
	
	public synchronized void onPlayConnectionCreated()
	{
		if(activeForensics != null && activeForensics.sawWorld)
			endForensics("play_connection_replaced");
		forensicsDisconnected = false;
		syncForensicsState();
	}
	
	public void closeForensicSession(String reason)
	{
		forensicsDisconnected = true;
		endForensics(reason);
	}
	
	public void shutdownLogging()
	{
		closeForensicSession("client_shutdown");
		for(var writer : endingForensics)
			try
			{
				if(!writer.await(10000))
					LOGGER.error("Forensic shutdown flush timed out: {}",
						writer.getFile());
			}catch(InterruptedException e)
			{
				Thread.currentThread().interrupt();
				break;
			}
		flushVerboseBuffers();
		flushUdpBuffer();
	}
	
	private void exportForensicsSummary()
	{
		ForensicActivation a = activeForensics;
		if(a != null)
			a.writer.requestSummary();
		else
			ChatUtils.message(
				"PacketTools: each completed forensic session has its own automatic summary.");
	}
	
	private final class ForensicActivation
	{
		final PacketDumper dumper = new PacketDumper(new PacketDecodeCoverage(),
			new PacketFilter(), new EntityLifecycleTracker());
		final ItemInventoryForensics model;
		final net.wurstclient.other_features.packettools.ForensicSessionWriter writer;
		final net.wurstclient.other_features.packettools.PacketTimingTracker timings;
		final boolean fullContent;
		volatile String dimension = "UNOBSERVED";
		
		void attachSlotActions(Map<String, Object> timing,
			net.wurstclient.other_features.packettools.ForensicSnapshots.State state)
		{
			Map<String, Object> matches = new LinkedHashMap<>();
			synchronized(relatedActions)
			{
				for(String key : state.stacks().keySet())
					if(key.startsWith("player_inventory:0:slot:"))
					{
						String raw = key.substring(key.lastIndexOf(':') + 1);
						var action = relatedActions.get("slot:" + raw);
						if(action != null)
							matches.put(key, action);
					}
			}
			if(!matches.isEmpty())
			{
				timing.put("slotInteractions", matches);
				if("STATE_APPLIED".equals(timing.get("captureStage")))
				{
					List<Map<String, Object>> actual = new ArrayList<>();
					matches.forEach((key, action) -> {
						Map<String, Object> item =
							new LinkedHashMap<>((Map<String, Object>)action);
						ItemStack stack = state.stacks().get(key);
						item.put("observedItemEmpty", stack.isEmpty());
						item.put("observedBookPageCount", bookPages(stack));
						actual.add(Map.copyOf(item));
					});
					timing.put("inventoryTimingItems", List.copyOf(actual));
				}
			}
		}
		
		final Set<String> decoded = new LinkedHashSet<>();
		final Map<String, Map<String, Object>> relatedActions =
			new LinkedHashMap<>();
		final List<java.lang.ref.WeakReference<Packet<?>>> nextTickPackets =
			new ArrayList<>();
		boolean sawWorld;
		
		ForensicActivation(ItemInventoryForensics model,
			net.wurstclient.other_features.packettools.ForensicSessionWriter writer,
			net.wurstclient.other_features.packettools.PacketTimingTracker timings,
			boolean fullContent)
		{
			this.model = model;
			this.writer = writer;
			this.timings = timings;
			this.fullContent = fullContent;
		}
		
		Map<String, Object> trace(Packet<?> packet, String direction,
			String stage)
		{
			long tickBoundary = clientTickStartedNs;
			long now = System.nanoTime();
			Map<String, Object> context =
				new LinkedHashMap<>(performanceContext);
			context.putAll(actionContext.get());
			String target = null;
			if(packet instanceof net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket p)
				target = "block:" + p.getPos().toShortString();
			else if(packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket p)
				target = "block:" + p.getPos().toShortString();
			else if(packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket p)
			{
				context.put("containerId", p.getContainerId());
				context.put("rawSlot", p.getSlot());
				context.put("stateId", p.getStateId());
				if(p.getContainerId() == 0)
					target = "slot:" + p.getSlot();
			}else if(packet instanceof net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket p)
				target = "backing:" + p.slot();
			if(target != null)
			{
				synchronized(relatedActions)
				{
					var action = relatedActions.get(target);
					if(action != null)
					{
						context.put("relatedEarlierInteraction", action);
						context.put("relationshipConfidence",
							"SAME_TARGET_CANDIDATE_NOT_PROOF_OF_CAUSALITY");
					}
				}
			}
			Map<String, Object> observed = timings.observe(packet, direction,
				stage, now, diagnosticClientTick, context);
			if(tickBoundary > 0 && now >= tickBoundary)
			{
				observed.put("clientTickPhaseMs", (now - tickBoundary) / 1e6);
				observed.put("clientTickBoundaryNs", tickBoundary);
				observed.put("clientTickPhaseObservationNs", now);
				observed.put("clientTickPhaseBasis",
					"ELAPSED_SINCE_LAST_CLIENT_TICK_HEAD; NOT_SERVER_PHASE");
			}
			observed.put("dimension", dimension);
			addInventoryTiming(packet, observed);
			return observed;
		}
		
		void addInventoryTiming(Packet<?> packet, Map<String, Object> observed)
		{
			List<Map<String, Object>> items = new ArrayList<>();
			if(packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket p)
			{
				String key = p.getContainerId() == 0 ? "slot:" + p.getSlot()
					: p.getContainerId() == -2 ? "backing:" + p.getSlot()
						: null;
				if(key != null)
					inventoryTimingItem(items, key, p.getItem());
			}else if(packet instanceof net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket p)
				inventoryTimingItem(items, "backing:" + p.slot(), p.contents());
			else if(packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket p
				&& p.containerId() == 0)
				for(int slot = 0; slot < p.items().size(); slot++)
					inventoryTimingItem(items, "slot:" + slot,
						p.items().get(slot));
			if(!items.isEmpty())
				observed.put("inventoryTimingItems", List.copyOf(items));
		}
		
		void inventoryTimingItem(List<Map<String, Object>> items, String key,
			ItemStack stack)
		{
			Map<String, Object> action;
			synchronized(relatedActions)
			{
				action = relatedActions.get(key);
			}
			if(action == null)
				return;
			Map<String, Object> item = new LinkedHashMap<>(action);
			item.put("suppliedItemEmpty", stack.isEmpty());
			item.put("suppliedBookPageCount", bookPages(stack));
			items.add(Map.copyOf(item));
		}
		
		int bookPages(ItemStack stack)
		{
			var writable = stack.get(
				net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT);
			if(writable != null)
				return writable.pages().size();
			var written = stack.get(
				net.minecraft.core.component.DataComponents.WRITTEN_BOOK_CONTENT);
			return written == null ? 0 : written.pages().size();
		}
		
		void observe(Packet<?> packet, String direction, String stage)
		{
			Map<String, Object> event = trace(packet, direction, stage);
			submit("packet_timing",
				((Number)event.get("monotonicNs")).longValue(), 1024,
				() -> model.recordObservation(event));
		}
		
		void submit(String type, long now, long bytes, Runnable task)
		{
			writer.submit(type, now, bytes, task);
		}
	}
	
	private boolean lastVerboseState;
	
	public void openScreen()
	{
		if(MC == null || MC.gui == null)
			return;
		
		MC.setScreen(
			new net.wurstclient.other_features.packettools.PacketToolsScreen(
				MC.screen, this));
	}
	
	public synchronized void saveSelectionConfig()
	{
		JsonObject root = new JsonObject();
		root.add("logS2C", toJsonArray(logS2C));
		root.add("logC2S", toJsonArray(logC2S));
		root.add("denyS2C", toJsonArray(denyS2C));
		root.add("denyC2S", toJsonArray(denyC2S));
		root.add("delayS2C", toJsonArray(delayS2C));
		root.add("delayC2S", toJsonArray(delayC2S));
		
		try
		{
			Files.createDirectories(CONFIG_FILE.getParent());
			try(Writer writer = Files.newBufferedWriter(CONFIG_FILE))
			{
				JsonUtils.PRETTY_GSON.toJson(root, writer);
			}
			
		}catch(IOException e)
		{
			LOGGER.warn("Failed to save packet tool config", e);
		}
	}
	
	private synchronized void loadSelectionConfig()
	{
		if(!Files.exists(CONFIG_FILE))
			return;
		
		try
		{
			JsonObject root =
				JsonParser.parseReader(Files.newBufferedReader(CONFIG_FILE))
					.getAsJsonObject();
			loadSet(root, "logS2C", logS2C);
			loadSet(root, "logC2S", logC2S);
			loadSet(root, "denyS2C", denyS2C);
			loadSet(root, "denyC2S", denyC2S);
			loadSet(root, "delayS2C", delayS2C);
			loadSet(root, "delayC2S", delayC2S);
			
		}catch(Exception e)
		{
			LOGGER.warn("Failed to load packet tool config", e);
		}
	}
	
	private static void loadSet(JsonObject root, String key, Set<String> set)
	{
		set.clear();
		if(!root.has(key) || !root.get(key).isJsonArray())
			return;
		
		root.getAsJsonArray(key).forEach(e -> {
			if(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString())
			{
				String name = e.getAsString().trim();
				if(!name.isEmpty())
					set.add(name);
			}
		});
	}
	
	private static JsonArray toJsonArray(Set<String> values)
	{
		JsonArray array = new JsonArray();
		for(String value : values)
			array.add(value);
		return array;
	}
	
	private static boolean isUsablePacketName(String name)
	{
		if(name == null || name.isBlank())
			return false;
		
		String lower = name.toLowerCase();
		return !lower.startsWith("class_");
	}
	
	public Set<String> getLogSet(PacketDirection direction)
	{
		return direction == PacketDirection.S2C ? logS2C : logC2S;
	}
	
	public Set<String> getDenySet(PacketDirection direction)
	{
		return direction == PacketDirection.S2C ? denyS2C : denyC2S;
	}
	
	public Set<String> getDelaySet(PacketDirection direction)
	{
		return direction == PacketDirection.S2C ? delayS2C : delayC2S;
	}
	
	public List<String> getAvailablePackets(PacketDirection direction)
	{
		boolean includeUnknown = showUnknownPackets.isChecked();
		Set<String> merged = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		if(direction == PacketDirection.S2C)
		{
			merged
				.addAll(net.wurstclient.other_features.packettools.PacketCatalog
					.getS2CNames());
			merged.addAll(discoveredS2C);
			merged.addAll(logS2C);
			merged.addAll(denyS2C);
			merged.addAll(delayS2C);
			if(includeUnknown)
				merged.addAll(discoveredUnknownS2C);
		}else
		{
			merged
				.addAll(net.wurstclient.other_features.packettools.PacketCatalog
					.getC2SNames());
			merged.addAll(discoveredC2S);
			merged.addAll(logC2S);
			merged.addAll(denyC2S);
			merged.addAll(delayC2S);
			if(includeUnknown)
				merged.addAll(discoveredUnknownC2S);
		}
		
		ArrayList<String> list = new ArrayList<>();
		for(String name : merged)
		{
			if(includeUnknown || isUsablePacketName(name))
				list.add(name);
		}
		list.sort(Comparator.naturalOrder());
		return list;
	}
	
	private void recordUnknown(String className, PacketDirection direction)
	{
		if(!isUsablePacketName(className))
		{
			if(direction == PacketDirection.S2C)
				discoveredUnknownS2C.add(className);
			else
				discoveredUnknownC2S.add(className);
		}
	}
	
	public CheckboxSetting getLoggingEnabledSetting()
	{
		return loggingEnabled;
	}
	
	public CheckboxSetting getDenyEnabledSetting()
	{
		return denyEnabled;
	}
	
	public CheckboxSetting getDelayEnabledSetting()
	{
		return delayEnabled;
	}
	
	/**
	 * Turns off packet delay and drops packets that were waiting for release
	 * when the server connection ends.
	 */
	public void onDisconnected()
	{
		// ### ADDED ### Preserve buffered packet observations across
		// disconnects.
		flushVerboseBuffers();
		delayEnabled.setChecked(false);
		lastDelayEnabledState = false;
		delayedIncoming.clear();
		delayedOutgoing.clear();
		bypassOutput.clear();
	}
	
	public CheckboxSetting getFileOutputSetting()
	{
		return fileOutput;
	}
	
	public CheckboxSetting getShowUnknownPacketsSetting()
	{
		return showUnknownPackets;
	}
	
	public SliderSetting getDelayTicksSetting()
	{
		return delayTicks;
	}
	
	public void updateSelection(PacketMode mode, PacketDirection direction,
		Set<String> selected)
	{
		Set<String> target = getSet(mode, direction);
		target.clear();
		target.addAll(selected);
		saveSelectionConfig();
	}
	
	public Set<String> getSelection(PacketMode mode, PacketDirection direction)
	{
		return new LinkedHashSet<>(getSet(mode, direction));
	}
	
	private Set<String> getSet(PacketMode mode, PacketDirection direction)
	{
		Objects.requireNonNull(mode);
		Objects.requireNonNull(direction);
		
		return switch(mode)
		{
			case LOG -> getLogSet(direction);
			case DENY -> getDenySet(direction);
			case DELAY -> getDelaySet(direction);
		};
	}
	
	private void flushQueues(boolean forceAll)
	{
		long now = getCurrentTick();
		flushIncoming(now, forceAll);
		flushOutgoing(now, forceAll);
	}
	
	private void flushIncoming(long now, boolean forceAll)
	{
		if(delayedIncoming.isEmpty())
			return;
		
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
		{
			delayedIncoming.clear();
			return;
		}
		
		while(!delayedIncoming.isEmpty())
		{
			QueuedPacket queued = delayedIncoming.peekFirst();
			if(!forceAll && queued.releaseTick > now)
				break;
			
			delayedIncoming.removeFirst();
			recordForensicTiming(queued.packet, "S2C", "RELEASED");
			applyIncomingPacket(queued.packet);
		}
	}
	
	private void flushOutgoing(long now, boolean forceAll)
	{
		if(delayedOutgoing.isEmpty())
			return;
		
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
		{
			delayedOutgoing.clear();
			return;
		}
		
		while(!delayedOutgoing.isEmpty())
		{
			QueuedPacket queued = delayedOutgoing.peekFirst();
			if(!forceAll && queued.releaseTick > now)
				break;
			
			delayedOutgoing.removeFirst();
			recordForensicTiming(queued.packet, "C2S", "RELEASED");
			bypassOutput.add(queued.packet);
			connection.send(queued.packet);
		}
	}
	
	private void applyIncomingPacket(Packet<?> packet)
	{
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
			return;
		
		@SuppressWarnings("unchecked")
		Packet<ClientPacketListener> typed =
			(Packet<ClientPacketListener>)packet;
		recordForensicsPreApplication(packet);
		try
		{
			typed.handle(connection);
		}finally
		{
			recordForensicHandlerEnd(packet);
		}
	}
	
	private void logPacket(String name, String direction, Packet<?> packet)
	{
		// If verbose is enabled, use the detailed JSONL dumper instead of
		// the simple one-line toString() output.
		if(verboseEnabled.isChecked())
		{
			logVerbose(packet, direction);
			return;
		}
		
		String data = String.valueOf(packet);
		String timestamp = LocalDateTime.now().format(TIME_FORMAT);
		String line =
			"[" + timestamp + "] [" + direction + "] " + name + " " + data;
		
		if(fileOutput.isChecked())
		{
			appendToLogFile(line + System.lineSeparator());
			return;
		}
		
		if(MC.gui != null && MC.gui.getChat() != null)
		{
			MC.execute(() -> {
				MutableComponent msg = Component.literal("[PacketTools] ")
					.withColor(0x55FFFF).append(Component.literal(line));
				MC.gui.getChat().addMessage(msg);
			});
		}
	}
	
	private void appendToLogFile(String line)
	{
		try
		{
			Path file = getCurrentLogFile();
			Files.writeString(file, line, StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
			
		}catch(IOException e)
		{
			ChatUtils.error("PacketTools: failed writing log file.");
		}
	}
	
	private Path getCurrentLogFile() throws IOException
	{
		if(currentLogFile != null)
			return currentLogFile;
		
		Files.createDirectories(LOG_DIR);
		String name =
			"packets_" + LocalDateTime.now().format(FILE_TIME_FORMAT) + ".log";
		currentLogFile = LOG_DIR.resolve(name);
		return currentLogFile;
	}
	
	// === UDP monitoring ===
	
	/**
	 * Receives a passive UDP observation from an optional protocol integration.
	 * This method is safe to call from a protocol's networking thread.
	 */
	public void logUdpDatagram(String direction, SocketAddress localAddress,
		SocketAddress remoteAddress, int length, String socketClass,
		String protocol, Boolean decoded, String payloadSha256)
	{
		if(!monitorUdp.isChecked())
			return;
		
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("timestamp", LocalDateTime.now().format(ISO_FORMAT));
		record.put("direction", direction);
		record.put("transport", "UDP");
		putSocketAddress(record, "local", localAddress);
		putSocketAddress(record, "remote", remoteAddress);
		record.put("length", length);
		record.put("socketClass", socketClass);
		record.put("thread", Thread.currentThread().getName());
		record.put("protocol", protocol);
		record.put("decoded", decoded);
		if(payloadSha256 != null)
			record.put("payloadSha256", payloadSha256);
		enqueueUdpRecord(record);
	}
	
	/**
	 * Called only after Simple Voice Chat has decoded a UDP NetworkMessage. The
	 * packet is inspected through its public 26.2 accessors; audio bytes are
	 * deliberately never copied into the log.
	 */
	public void logSimpleVoiceChatPacket(Object packet,
		SocketAddress localAddress, SocketAddress remoteAddress)
	{
		if(!monitorUdp.isChecked() || packet == null)
			return;
		
		Map<String, Object> record = new LinkedHashMap<>();
		record.put("timestamp", LocalDateTime.now().format(ISO_FORMAT));
		record.put("direction", "S2C");
		record.put("transport", "UDP");
		record.put("protocol", "simple_voice_chat");
		record.put("decoded", true);
		record.put("class", packet.getClass().getName());
		record.put("packetClass", packet.getClass().getSimpleName());
		putSocketAddress(record, "local", localAddress);
		putSocketAddress(record, "remote", remoteAddress);
		record.put("thread", Thread.currentThread().getName());
		
		putAccessor(record, "senderUuid", packet, "getSender");
		putAccessor(record, "channelUuid", packet, "getChannelId");
		putAccessor(record, "sequenceNumber", packet, "getSequenceNumber");
		putAccessor(record, "category", packet, "getCategory");
		Object data = invokeAccessor(packet, "getData");
		if(data instanceof byte[] bytes)
			record.put("payloadLength", bytes.length);
		
		String simpleName = packet.getClass().getSimpleName();
		if("PlayerSoundPacket".equals(simpleName))
		{
			putAccessor(record, "whispering", packet, "isWhispering");
			putAccessor(record, "voiceDistance", packet, "getDistance");
		}else if("LocationSoundPacket".equals(simpleName))
		{
			putAccessor(record, "voiceDistance", packet, "getDistance");
			putLocation(record, invokeAccessor(packet, "getLocation"));
		}
		enqueueUdpRecord(record);
	}
	
	private void enqueueUdpRecord(Map<String, Object> record)
	{
		String json = VERBOSE_GSON.toJson(record);
		synchronized(udpJsonlBuffer)
		{
			if(udpJsonlBuffer.size() >= MAX_UDP_BUFFERED_RECORDS)
			{
				if(!udpBufferOverflowReported)
				{
					udpBufferOverflowReported = true;
					LOGGER.warn(
						"PacketTools UDP log buffer is full; dropping UDP records.");
				}
				return;
			}
			udpJsonlBuffer.add(json);
		}
	}
	
	private void flushUdpBuffer()
	{
		ArrayList<String> batch;
		synchronized(udpJsonlBuffer)
		{
			if(udpJsonlBuffer.isEmpty())
				return;
			batch = new ArrayList<>(udpJsonlBuffer);
			udpJsonlBuffer.clear();
		}
		try
		{
			Path file = getCurrentUdpJsonlFile();
			Files.writeString(file, String.join("\n", batch) + "\n",
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}catch(IOException e)
		{
			LOGGER.warn("PacketTools: failed writing UDP JSONL file.", e);
		}
	}
	
	private Path getCurrentUdpJsonlFile() throws IOException
	{
		if(currentUdpJsonlFile != null)
			return currentUdpJsonlFile;
		Files.createDirectories(LOG_DIR);
		String name =
			"udp_" + LocalDateTime.now().format(FILE_TIME_FORMAT) + ".jsonl";
		currentUdpJsonlFile = LOG_DIR.resolve(name);
		return currentUdpJsonlFile;
	}
	
	private static void putSocketAddress(Map<String, Object> record,
		String prefix, SocketAddress address)
	{
		if(address instanceof InetSocketAddress inet)
		{
			record.put(prefix + "Address", inet.getHostString());
			record.put(prefix + "Port", inet.getPort());
		}else if(address != null)
			record.put(prefix + "Address", String.valueOf(address));
	}
	
	private static void putAccessor(Map<String, Object> record, String key,
		Object source, String accessor)
	{
		Object value = invokeAccessor(source, accessor);
		if(value != null)
			record
				.put(key,
					value instanceof Number || value instanceof Boolean
						|| value instanceof String ? value
							: String.valueOf(value));
	}
	
	private static Object invokeAccessor(Object source, String accessor)
	{
		try
		{
			return source.getClass().getMethod(accessor).invoke(source);
		}catch(ReflectiveOperationException ignored)
		{
			return null;
		}
	}
	
	private static void putLocation(Map<String, Object> record, Object location)
	{
		if(location == null)
			return;
		try
		{
			record.put("x", location.getClass().getField("x").get(location));
			record.put("y", location.getClass().getField("y").get(location));
			record.put("z", location.getClass().getField("z").get(location));
		}catch(ReflectiveOperationException ignored)
		{
			// Coordinates are optional and must never be inferred.
		}
	}
	
	// === Verbose logging ===
	
	private void logVerbose(Packet<?> packet, String direction)
	{
		logVerbose(packet, direction, List.of());
	}
	
	private void logVerbose(Packet<?> packet, String direction,
		List<String> debugTrace)
	{
		if(!loggingEnabled.isChecked())
			return;
		
		if(packetDumper == null)
			packetDumper = new PacketDumper(decodeCoverage, packetFilter,
				lifecycleTracker);
		
		List<String> lines =
			packetDumper.dumpRecursive(packet, direction, null);
		if(lines.isEmpty())
			return;
		
		boolean human = verboseHumanReadable.isChecked();
		synchronized(verboseJsonlBuffer)
		{
			for(String line : lines)
			{
				if(!debugTrace.isEmpty())
				{
					try
					{
						JsonObject json =
							JsonParser.parseString(line).getAsJsonObject();
						json.add("wurstDebug",
							VERBOSE_GSON.toJsonTree(debugTrace));
						line = VERBOSE_GSON.toJson(json);
					}catch(RuntimeException ignored)
					{
						// Keep packet logging resilient for non-JSON dumper
						// lines.
					}
				}
				appendVerboseLineLocked(line, human);
			}
		}
	}
	
	/**
	 * Called after vanilla applies a map-data packet, when ClientLevel's map
	 * cache contains the complete current map state rather than only the
	 * packet's optional colour patch.
	 */
	public void logVerboseMapCache(ClientboundMapItemDataPacket packet,
		MapItemSavedData mapData)
	{
		if(!verboseEnabled.isChecked() || !loggingEnabled.isChecked()
			|| packet == null || mapData == null)
			return;
		
		String name = net.wurstclient.other_features.packettools.PacketCatalog
			.formatPacketName(packet);
		if(!logS2C.contains(name))
			return;
		
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("timestamp", LocalDateTime.now().format(ISO_FORMAT));
		root.put("direction", "S2C-CACHE");
		root.put("class", packet.getClass().getName());
		root.put("simpleName", packet.getClass().getSimpleName());
		root.put("packetId", name);
		root.put("mapId", packet.mapId().id());
		root.put("dataComponent", "minecraft:map_id");
		root.put("centerX", mapData.centerX);
		root.put("centerZ", mapData.centerZ);
		root.put("dimension", String.valueOf(mapData.dimension));
		root.put("scale", mapData.scale);
		root.put("locked", mapData.locked);
		Map<String, Object> colors = new LinkedHashMap<>();
		colors.put("length", mapData.colors.length);
		colors.put("base64",
			Base64.getEncoder().encodeToString(mapData.colors));
		root.put("colors", colors);
		
		String json = VERBOSE_GSON.toJson(root);
		synchronized(verboseJsonlBuffer)
		{
			appendVerboseLineLocked(json, verboseHumanReadable.isChecked());
		}
	}
	
	public void logVerboseChatReport(UUID reportedProfileId,
		ReportType reportType, AbuseReport abuseReport)
	{
		if(!shouldVerboseExternalMonitoring())
			return;
		
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("reportedProfileId",
			reportedProfileId != null ? reportedProfileId.toString() : null);
		fields.put("reportType",
			reportType != null ? String.valueOf(reportType) : null);
		fields.put("report", abuseReport);
		
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("timestamp", LocalDateTime.now().format(ISO_FORMAT));
		root.put("direction", "REPORT");
		root.put("class",
			abuseReport != null ? abuseReport.getClass().getName() : "unknown");
		root.put("simpleName", "ChatReport");
		root.put("fields", fields);
		
		enqueueVerboseExternalEvent(root);
	}
	
	public void logVerboseExternalEvent(String eventType,
		Map<String, Object> fields)
	{
		if(!shouldVerboseExternalMonitoring())
			return;
		enqueueVerboseEvent(eventType, "EXTERNAL", fields);
	}
	
	private void enqueueVerboseEvent(String eventType, String direction,
		Map<String, Object> fields)
	{
		if(!verboseEnabled.isChecked())
			return;
		
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("timestamp", LocalDateTime.now().format(ISO_FORMAT));
		root.put("direction", direction);
		root.put("class", eventType);
		root.put("simpleName", eventType);
		root.put("fields", fields);
		enqueueVerboseExternalEvent(root);
	}
	
	public void logVerboseChatAction(String source, String message,
		boolean command)
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("source", source);
		fields.put("message", message);
		fields.put("isCommand", command);
		enqueueVerboseEvent("ChatAction", "CHAT-C2S", fields);
	}
	
	public void logVerboseChatOutput(String source, String message)
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("source", source);
		fields.put("message", message);
		enqueueVerboseEvent("ChatMessage", "CHAT-S2C", fields);
	}
	
	public void logVerboseChatInput(String source, String message, String kind)
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("source", source);
		fields.put("message", message);
		fields.put("kind", kind);
		fields.put("isCommand",
			"command".equals(kind) || "uiutils_command".equals(kind));
		enqueueVerboseEvent("ChatInput", "CHAT-C2S", fields);
	}
	
	public void logVerboseJoinFlow(String source, ServerData serverData)
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("source", source);
		if(serverData != null)
		{
			fields.put("name", serverData.name);
			fields.put("address", serverData.ip);
		}
		logVerboseExternalEvent("JoinFlow", fields);
	}
	
	public void logVerboseSessionChange(String action, User user)
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("action", action);
		if(user != null)
		{
			fields.put("name", user.getName());
			fields.put("profileId", String.valueOf(user.getProfileId()));
		}
		logVerboseExternalEvent("SessionChange", fields);
	}
	
	public void logVerboseTelemetryEvent(String action, String details)
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("action", action);
		fields.put("details", details);
		logVerboseExternalEvent("Telemetry", fields);
	}
	
	public void logVerboseApiCall(String api, String details)
	{
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("api", api);
		fields.put("details", details);
		logVerboseExternalEvent("ApiCall", fields);
	}
	
	private void appendVerboseLineLocked(String jsonLine, boolean human)
	{
		if(verboseJsonlBuffer.size() >= MAX_VERBOSE_BUFFER_LINES)
		{
			verboseJsonlBuffer.remove(0);
			if(!verboseHumanBuffer.isEmpty())
				verboseHumanBuffer.remove(0);
			verboseDroppedRecords++;
		}
		verboseJsonlBuffer.add(jsonLine);
		if(human)
			verboseHumanBuffer.add(buildHumanReadable(jsonLine));
	}
	
	private boolean shouldVerboseExternalMonitoring()
	{
		return loggingEnabled.isChecked() && verboseEnabled.isChecked()
			&& verboseOutsideGamePackets.isChecked();
	}
	
	private void enqueueVerboseExternalEvent(Map<String, Object> root)
	{
		if(!loggingEnabled.isChecked())
			return;
		
		String json = VERBOSE_GSON.toJson(root);
		synchronized(verboseJsonlBuffer)
		{
			appendVerboseLineLocked(json, verboseHumanReadable.isChecked());
		}
	}
	
	private boolean shouldVerboseOutsideGame()
	{
		return shouldVerboseExternalMonitoring() && isOutsideGame();
	}
	
	private static boolean shouldAlwaysVerbose(Packet<?> packet)
	{
		return packet instanceof ServerboundSignUpdatePacket
			|| packet instanceof ServerboundEditBookPacket
			|| packet instanceof ServerboundChatAckPacket
			|| packet instanceof ServerboundChatSessionUpdatePacket
			|| packet instanceof ServerboundContainerClickPacket
			|| packet instanceof ServerboundContainerButtonClickPacket
			|| packet instanceof ServerboundContainerClosePacket;
	}
	
	private void flushVerboseBuffers()
	{
		ArrayList<String> jsonlBatch;
		ArrayList<String> humanBatch;
		long dropped = 0;
		synchronized(verboseJsonlBuffer)
		{
			if(verboseJsonlBuffer.isEmpty())
				return;
			jsonlBatch = new ArrayList<>(verboseJsonlBuffer);
			humanBatch = verboseHumanReadable.isChecked()
				? new ArrayList<>(verboseHumanBuffer) : null;
			verboseJsonlBuffer.clear();
			verboseHumanBuffer.clear();
		}
		
		try
		{
			Path jsonlFile = getCurrentVerboseJsonlFile();
			StringBuilder sb = new StringBuilder();
			synchronized(verboseJsonlBuffer)
			{
				dropped = verboseDroppedRecords;
				verboseDroppedRecords = 0;
			}
			if(dropped > 0)
				sb.append(VERBOSE_GSON.toJson(Map.of("recordType",
					"CAPTURE_LOSS", "droppedRecordCount", dropped, "buffer",
					"verbose", "reason", "BUFFER_CAPACITY"))).append('\n');
			for(String line : jsonlBatch)
				sb.append(line).append('\n');
			Files.writeString(jsonlFile, sb.toString(),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}catch(IOException e)
		{
			synchronized(verboseJsonlBuffer)
			{
				verboseDroppedRecords += dropped;
			}
			requeueVerboseLines(jsonlBatch, null);
			ChatUtils.error("PacketTools: failed writing verbose JSONL file.");
		}
		
		if(humanBatch != null)
		{
			try
			{
				Path humanFile = getCurrentVerboseHumanFile();
				StringBuilder sb = new StringBuilder();
				for(String line : humanBatch)
					sb.append(line);
				Files.writeString(humanFile, sb.toString(),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}catch(IOException e)
			{
				requeueVerboseHumanLines(humanBatch);
				ChatUtils.error(
					"PacketTools: failed writing verbose human-readable file.");
			}
		}
	}
	
	// ### ADDED ### Retain failed batches without holding the buffer lock
	// during I/O.
	private void requeueVerboseLines(List<String> jsonl, List<String> human)
	{
		synchronized(verboseJsonlBuffer)
		{
			int overflow = Math.max(0, verboseJsonlBuffer.size() + jsonl.size()
				- MAX_VERBOSE_BUFFER_LINES);
			while(overflow > 0 && !verboseJsonlBuffer.isEmpty())
			{
				verboseJsonlBuffer.remove(verboseJsonlBuffer.size() - 1);
				if(!verboseHumanBuffer.isEmpty())
					verboseHumanBuffer.remove(verboseHumanBuffer.size() - 1);
				verboseDroppedRecords++;
				overflow--;
			}
			if(overflow > 0)
			{
				jsonl = new ArrayList<>(
					jsonl.subList(0, Math.max(0, jsonl.size() - overflow)));
				verboseDroppedRecords += overflow;
			}
			verboseJsonlBuffer.addAll(0, jsonl);
			if(human != null)
				verboseHumanBuffer.addAll(0, human);
		}
	}
	
	private void requeueVerboseHumanLines(List<String> human)
	{
		synchronized(verboseJsonlBuffer)
		{
			int overflow = Math.max(0, verboseHumanBuffer.size() + human.size()
				- MAX_VERBOSE_BUFFER_LINES);
			while(overflow > 0 && !verboseHumanBuffer.isEmpty())
			{
				verboseHumanBuffer.remove(verboseHumanBuffer.size() - 1);
				verboseDroppedRecords++;
				overflow--;
			}
			if(overflow > 0)
			{
				human = new ArrayList<>(
					human.subList(0, Math.max(0, human.size() - overflow)));
				verboseDroppedRecords += overflow;
			}
			verboseHumanBuffer.addAll(0, human);
		}
	}
	
	private String buildHumanReadable(String jsonLine)
	{
		try
		{
			JsonObject obj = JsonParser.parseString(jsonLine).getAsJsonObject();
			StringBuilder sb = new StringBuilder();
			sb.append("[").append(obj.get("timestamp").getAsString())
				.append("] [").append(obj.get("direction").getAsString())
				.append("] ").append(obj.get("simpleName").getAsString());
			if(obj.has("bundlePath"))
				sb.append(" @ ").append(obj.get("bundlePath").getAsString());
			sb.append("\n");
			
			if(obj.has("fields"))
			{
				JsonObject fields = obj.getAsJsonObject("fields");
				for(Map.Entry<String, com.google.gson.JsonElement> e : fields
					.entrySet())
				{
					sb.append("  ").append(e.getKey()).append(" = ")
						.append(e.getValue()).append("\n");
				}
			}
			sb.append("\n");
			return sb.toString();
		}catch(Exception e)
		{
			return jsonLine + "\n";
		}
	}
	
	private Path getCurrentVerboseJsonlFile() throws IOException
	{
		if(currentVerboseJsonlFile != null)
			return currentVerboseJsonlFile;
		
		Files.createDirectories(LOG_DIR);
		String name = "verbose_" + LocalDateTime.now().format(FILE_TIME_FORMAT)
			+ ".jsonl";
		currentVerboseJsonlFile = LOG_DIR.resolve(name);
		return currentVerboseJsonlFile;
	}
	
	private Path getCurrentVerboseHumanFile() throws IOException
	{
		if(currentVerboseHumanFile != null)
			return currentVerboseHumanFile;
		
		Files.createDirectories(LOG_DIR);
		String name =
			"verbose_" + LocalDateTime.now().format(FILE_TIME_FORMAT) + ".log";
		currentVerboseHumanFile = LOG_DIR.resolve(name);
		return currentVerboseHumanFile;
	}
	
	private void printCoverageReport()
	{
		String report = decodeCoverage.buildReport();
		ChatUtils.message(report);
	}
	
	private void printEntitySummary()
	{
		String summary = lifecycleTracker.buildSummary();
		ChatUtils.message(summary);
	}
	
	// === Verbose mode settings accessors ===
	
	public CheckboxSetting getVerboseEnabledSetting()
	{
		return verboseEnabled;
	}
	
	public CheckboxSetting getItemForensicsEnabledSetting()
	{
		return itemForensicsEnabled;
	}
	
	public void exportItemForensicsSummary()
	{
		exportForensicsSummary();
	}
	
	public CheckboxSetting getVerboseOutsideGamePacketsSetting()
	{
		return verboseOutsideGamePackets;
	}
	
	public CheckboxSetting getVerboseHumanReadableSetting()
	{
		return verboseHumanReadable;
	}
	
	public SliderSetting getVerboseFlushIntervalSetting()
	{
		return verboseFlushInterval;
	}
	
	public PacketDecodeCoverage getDecodeCoverage()
	{
		return decodeCoverage;
	}
	
	public EntityLifecycleTracker getLifecycleTracker()
	{
		return lifecycleTracker;
	}
	
	public PacketFilter getPacketFilter()
	{
		return packetFilter;
	}
	
	private boolean isOutsideGame()
	{
		return MC == null || MC.level == null || MC.player == null;
	}
	
	private long getCurrentTick()
	{
		if(MC.level != null)
			return MC.level.getGameTime();
		if(MC.player != null)
			return MC.player.tickCount;
		return System.currentTimeMillis() / 50L;
	}
	
	private static final class QueuedPacket
	{
		private final Packet<?> packet;
		private final long releaseTick;
		
		private QueuedPacket(Packet<?> packet, long releaseTick)
		{
			this.packet = packet;
			this.releaseTick = releaseTick;
		}
	}
	
	public enum PacketMode
	{
		LOG("Log"),
		DENY("Deny"),
		DELAY("Delay");
		
		private final String label;
		
		PacketMode(String label)
		{
			this.label = label;
		}
		
		public String getLabel()
		{
			return label;
		}
		
		public PacketMode next()
		{
			return values()[(ordinal() + 1) % values().length];
		}
	}
	
	public enum PacketDirection
	{
		S2C,
		C2S
	}
}
