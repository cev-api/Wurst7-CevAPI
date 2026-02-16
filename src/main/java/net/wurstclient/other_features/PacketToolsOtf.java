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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.wurstclient.Category;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.json.JsonUtils;
import org.slf4j.Logger;

public final class PacketToolsOtf extends OtherFeature
	implements PacketInputListener, PacketOutputListener, UpdateListener
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final DateTimeFormatter TIME_FORMAT =
		DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static final DateTimeFormatter FILE_TIME_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
	private static final Path CONFIG_FILE = FabricLoader.getInstance()
		.getConfigDir().resolve("wurst-packet-tools.json");
	private static final Path LOG_DIR =
		FabricLoader.getInstance().getConfigDir().resolve("packet-logger");
	
	private final CheckboxSetting loggingEnabled =
		new CheckboxSetting("Logging enabled", false);
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
		
		loadSelectionConfig();
		
		lastDelayEnabledState = delayEnabled.isChecked();
		lastLoggingState = loggingEnabled.isChecked();
		
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	public Category getCategory()
	{
		return Category.OTHER;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		Packet<?> packet = event.getPacket();
		String name = net.wurstclient.other_features.packettools.PacketCatalog
			.formatPacketName(packet);
		discoveredS2C.add(name);
		recordUnknown(packet.getClass().getSimpleName(), PacketDirection.S2C);
		
		if(loggingEnabled.isChecked() && logS2C.contains(name))
			logPacket(name, "S2C", packet);
		
		if(denyEnabled.isChecked() && denyS2C.contains(name))
		{
			event.cancel();
			return;
		}
		
		if(delayEnabled.isChecked() && delayTicks.getValueI() > 0
			&& delayS2C.contains(name))
		{
			event.cancel();
			delayedIncoming.addLast(new QueuedPacket(packet,
				getCurrentTick() + delayTicks.getValueI()));
		}
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		Packet<?> packet = event.getPacket();
		if(bypassOutput.remove(packet))
			return;
		
		String name = net.wurstclient.other_features.packettools.PacketCatalog
			.formatPacketName(packet);
		discoveredC2S.add(name);
		recordUnknown(packet.getClass().getSimpleName(), PacketDirection.C2S);
		
		if(loggingEnabled.isChecked() && logC2S.contains(name))
			logPacket(name, "C2S", packet);
		
		if(denyEnabled.isChecked() && denyC2S.contains(name))
		{
			event.cancel();
			return;
		}
		
		if(delayEnabled.isChecked() && delayTicks.getValueI() > 0
			&& delayC2S.contains(name))
		{
			event.cancel();
			delayedOutgoing.addLast(new QueuedPacket(packet,
				getCurrentTick() + delayTicks.getValueI()));
		}
	}
	
	@Override
	public void onUpdate()
	{
		boolean delayActive =
			delayEnabled.isChecked() && delayTicks.getValueI() > 0;
		if(!delayActive && lastDelayEnabledState)
			flushQueues(true);
		else if(delayActive)
			flushQueues(false);
		
		lastDelayEnabledState = delayEnabled.isChecked();
		
		if(lastLoggingState && !loggingEnabled.isChecked())
			currentLogFile = null;
		lastLoggingState = loggingEnabled.isChecked();
	}
	
	public void openScreen()
	{
		if(MC == null)
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
		typed.handle(connection);
	}
	
	private void logPacket(String name, String direction, Packet<?> packet)
	{
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
