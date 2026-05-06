/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.ChatInputListener.ChatInputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.oppstats.OppStatsScreen;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.NpcUtils;
import net.wurstclient.util.json.JsonUtils;

public final class OppStatsHack extends Hack
	implements UpdateListener, ChatInputListener
{
	private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter
		.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
	private static final Pattern DEATH_PHRASE =
		Pattern.compile("(?i).*(was|fell|died|blew|burned|starved|slain).*");
	private static final Pattern LOBBY_BOT_NAME =
		Pattern.compile("^\\s*[:;.,'`~!@#\\-_=+]?\\d{1,4}\\s*$");
	
	private final Map<UUID, OppRecord> records = new HashMap<>();
	private final LinkedHashSet<UUID> lastOnline = new LinkedHashSet<>();
	private String lastServerKey = "unknown";
	private Path currentFile;
	private boolean dirty;
	private long lastSaveAt;
	
	private final CheckboxSetting ignoreNpcs =
		new CheckboxSetting("Ignore NPCs",
			"Filters likely NPC entities and identities from OppStats.", true);
	
	public OppStatsHack()
	{
		super("OppStats",
			"Tracks players per server and builds a progressive dossier.",
			false);
		setCategory(Category.INTEL);
		addSetting(new ButtonSetting("Open OppStats", this::openScreen));
		addSetting(ignoreNpcs);
	}
	
	@Override
	protected void onEnable()
	{
		records.clear();
		lastOnline.clear();
		lastServerKey = resolveServerKey();
		currentFile = resolveDataFile(lastServerKey);
		loadCurrentServerData();
		bootstrapFromTablist();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(ChatInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(ChatInputListener.class, this);
		saveIfNeeded(true);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.getConnection() == null)
			return;
		
		String keyNow = resolveServerKey();
		if(!keyNow.equals(lastServerKey))
		{
			saveIfNeeded(true);
			records.clear();
			lastOnline.clear();
			lastServerKey = keyNow;
			currentFile = resolveDataFile(lastServerKey);
			loadCurrentServerData();
			bootstrapFromTablist();
		}
		
		long now = System.currentTimeMillis();
		Set<UUID> onlineNow = new LinkedHashSet<>();
		for(PlayerInfo info : MC.getConnection().getOnlinePlayers())
		{
			UUID id = info.getProfile().id();
			String name = info.getProfile().name();
			if(ignoreNpcs.isChecked() && isBotLikeIdentity(id, name))
				continue;
			
			onlineNow.add(id);
			OppRecord rec =
				records.computeIfAbsent(id, k -> new OppRecord(id, name));
			rec.name = name;
			rec.online = true;
			rec.ping = info.getLatency();
			rec.gamemode =
				info.getGameMode() == null ? "N/A" : info.getGameMode().name();
			rec.tabSeenAt = now;
			if(!lastOnline.contains(id))
			{
				rec.joinCount++;
				rec.lastJoinAt = now;
				rec.addEvent("Joined server");
			}
			dirty = true;
		}
		if(ignoreNpcs.isChecked())
			records.entrySet()
				.removeIf(e -> isHardLobbyBotName(e.getValue().name));
		
		for(UUID wasOnline : lastOnline)
		{
			if(onlineNow.contains(wasOnline))
				continue;
			OppRecord rec = records.get(wasOnline);
			if(rec == null)
				continue;
			rec.online = false;
			rec.lastLeaveAt = now;
			rec.addEvent("Left server");
			dirty = true;
		}
		lastOnline.clear();
		lastOnline.addAll(onlineNow);
		
		if(MC.level != null && MC.player != null)
		{
			for(Player p : MC.level.players())
			{
				if(p == MC.player)
					continue;
				if(ignoreNpcs.isChecked()
					&& isBotLikeEntity(p.getUUID(), p.getName().getString()))
					continue;
				
				OppRecord rec = records.computeIfAbsent(p.getUUID(),
					k -> new OppRecord(p.getUUID(), p.getName().getString()));
				updateFromLivePlayer(rec, p, now);
				dirty = true;
			}
		}
		
		saveIfNeeded(false);
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		if(event == null || event.getComponent() == null)
			return;
		
		String msg = event.getComponent().getString();
		if(msg == null || msg.isBlank())
			return;
		
		boolean vanillaDeath = event.getComponent()
			.getContents() instanceof TranslatableContents tc
			&& tc.getKey().startsWith("death.");
		if(!vanillaDeath && !DEATH_PHRASE.matcher(msg).matches())
			return;
		
		OppRecord victim = null;
		for(OppRecord rec : records.values())
			if(msg.startsWith(rec.name + " "))
			{
				victim = rec;
				break;
			}
		
		if(victim == null)
			return;
		
		long now = System.currentTimeMillis();
		victim.deathCount++;
		victim.addEvent("Death: " + msg);
		String killerName = inferKillerName(msg, victim.name);
		if(killerName != null)
			for(OppRecord rec : records.values())
				if(rec.name.equalsIgnoreCase(killerName))
				{
					rec.killCount++;
					rec.addEvent("Kill: " + victim.name + " (" + msg + ")");
					break;
				}
		dirty = true;
	}
	
	private void updateFromLivePlayer(OppRecord rec, Player p, long now)
	{
		rec.name = p.getName().getString();
		rec.online = true;
		rec.lastPos = p.position();
		rec.lastSeenAt = now;
		rec.distance = MC.player == null ? Double.NaN : p.distanceTo(MC.player);
		rec.health = p.getHealth();
		rec.absorption = p.getAbsorptionAmount();
		rec.armorValue = p.getArmorValue();
		rec.mainHand = stackInfo(p.getMainHandItem());
		rec.offHand = stackInfo(p.getOffhandItem());
		rec.helmet = stackInfo(p.getItemBySlot(EquipmentSlot.HEAD));
		rec.chest = stackInfo(p.getItemBySlot(EquipmentSlot.CHEST));
		rec.legs = stackInfo(p.getItemBySlot(EquipmentSlot.LEGS));
		rec.boots = stackInfo(p.getItemBySlot(EquipmentSlot.FEET));
	}
	
	private void bootstrapFromTablist()
	{
		if(MC.getConnection() == null)
			return;
		
		long now = System.currentTimeMillis();
		for(PlayerInfo info : MC.getConnection().getOnlinePlayers())
		{
			UUID id = info.getProfile().id();
			String name = info.getProfile().name();
			if(ignoreNpcs.isChecked() && isBotLikeIdentity(id, name))
				continue;
			OppRecord rec =
				records.computeIfAbsent(id, k -> new OppRecord(id, name));
			rec.name = name;
			rec.online = true;
			rec.ping = info.getLatency();
			rec.gamemode =
				info.getGameMode() == null ? "N/A" : info.getGameMode().name();
			rec.tabSeenAt = now;
			lastOnline.add(id);
		}
	}
	
	public List<OppRecord> getOnlineRecords()
	{
		return records.values().stream().filter(r -> r.online)
			.filter(r -> !isHardLobbyBotName(r.name))
			.sorted(Comparator
				.comparing((OppRecord r) -> r.name.toLowerCase(Locale.ROOT)))
			.toList();
	}
	
	public List<OppRecord> getHistoricalRecords()
	{
		return records.values().stream().filter(r -> !r.online)
			.filter(r -> !isHardLobbyBotName(r.name))
			.sorted(Comparator
				.comparing((OppRecord r) -> r.name.toLowerCase(Locale.ROOT)))
			.toList();
	}
	
	public String formatForClipboard(OppRecord r)
	{
		String pos = r.lastPos == null ? "N/A" : String.format(Locale.ROOT,
			"(%.2f, %.2f, %.2f)", r.lastPos.x, r.lastPos.y, r.lastPos.z);
		String dist = Double.isNaN(r.distance) ? "N/A"
			: String.format(Locale.ROOT, "%.2f", r.distance);
		String lastSeen = r.lastSeenAt <= 0 ? "N/A"
			: TS_FORMAT.format(Instant.ofEpochMilli(r.lastSeenAt));
		return "Name: " + r.name + "\nUUID: " + r.uuid + "\nOnline: " + r.online
			+ "\nPosition: " + pos + "\nDistance: " + dist
			+ "\nTarget status: health=" + formatFloat(r.health)
			+ ", absorption=" + formatFloat(r.absorption) + ", armor="
			+ r.armorValue + ", ping=" + r.ping + ", gamemode="
			+ nullToNA(r.gamemode) + "\nMain hand: " + r.mainHand
			+ "\nOff hand: " + r.offHand + "\nHelmet: " + r.helmet
			+ "\nChestplate: " + r.chest + "\nLeggings: " + r.legs + "\nBoots: "
			+ r.boots + "\nKills: " + r.killCount + "\nDeaths: " + r.deathCount
			+ "\nJoins: " + r.joinCount + "\nLast join: "
			+ formatEpoch(r.lastJoinAt) + "\nLast leave: "
			+ formatEpoch(r.lastLeaveAt) + "\nLast seen: " + lastSeen
			+ "\nEvent log:\n" + String.join("\n", r.events);
	}
	
	public String formatLastSeen(OppRecord r)
	{
		return formatEpoch(r.lastSeenAt);
	}
	
	public String formatEpoch(long epochMs)
	{
		if(epochMs <= 0)
			return "N/A";
		return TS_FORMAT.format(Instant.ofEpochMilli(epochMs));
	}
	
	private String formatFloat(float v)
	{
		if(Float.isNaN(v))
			return "N/A";
		return String.format(Locale.ROOT, "%.1f", v);
	}
	
	private String stackInfo(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return "N/A";
		String itemId =
			BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		String ench = getEnchantmentSummary(stack);
		String dur = stack.isDamageableItem()
			? (stack.getMaxDamage() - stack.getDamageValue()) + "/"
				+ stack.getMaxDamage()
			: "N/A";
		return stack.getHoverName().getString() + " x" + stack.getCount()
			+ " [id=" + itemId + ", durability=" + dur + ", enchants="
			+ (ench.isBlank() ? "none" : ench) + "]";
	}
	
	private String getEnchantmentSummary(ItemStack stack)
	{
		ArrayList<String> parts = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		appendEnchantments(parts, seen, stack
			.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
		appendEnchantments(parts, seen, stack.getOrDefault(
			DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
		return String.join(", ", parts);
	}
	
	private static void appendEnchantments(List<String> out, Set<String> seen,
		ItemEnchantments enchantments)
	{
		for(Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments
			.entrySet())
		{
			Holder<Enchantment> holder = entry.getKey();
			int lvl = entry.getIntValue();
			String key = holder.getRegisteredName() + "#" + lvl;
			if(!seen.add(key))
				continue;
			out.add(Enchantment.getFullname(holder, lvl).getString());
		}
	}
	
	private void saveIfNeeded(boolean force)
	{
		if(!dirty || currentFile == null)
			return;
		long now = System.currentTimeMillis();
		if(!force && now - lastSaveAt < 2000L)
			return;
		
		JsonObject root = new JsonObject();
		JsonArray arr = new JsonArray();
		for(OppRecord rec : records.values())
			arr.add(rec.toJson());
		root.add("players", arr);
		try
		{
			Files.createDirectories(currentFile.getParent());
			JsonUtils.toJson(root, currentFile);
			dirty = false;
			lastSaveAt = now;
		}catch(Exception e)
		{
			ChatUtils.error("OppStats save failed: " + e.getMessage());
		}
	}
	
	private void loadCurrentServerData()
	{
		if(currentFile == null || !Files.exists(currentFile))
			return;
		try
		{
			JsonObject root =
				JsonUtils.parseFile(currentFile).getAsJsonObject();
			if(!root.has("players") || !root.get("players").isJsonArray())
				return;
			for(var el : root.getAsJsonArray("players"))
			{
				if(!el.isJsonObject())
					continue;
				OppRecord rec = OppRecord.fromJson(el.getAsJsonObject());
				records.put(rec.uuid, rec);
			}
		}catch(Exception e)
		{
			ChatUtils.error("OppStats load failed: " + e.getMessage());
		}
	}
	
	private Path resolveDataFile(String serverKey)
	{
		String safe = serverKey.replaceAll("[^a-zA-Z0-9._-]", "_");
		return WURST.getWurstFolder().resolve("oppstats")
			.resolve(safe + ".json");
	}
	
	private String resolveServerKey()
	{
		ServerData info = MC.getCurrentServer();
		if(info != null)
		{
			if(info.ip != null && !info.ip.isEmpty())
				return info.ip.replace(':', '_');
			if(info.isRealm())
				return "realms_" + (info.name == null ? "" : info.name);
			if(info.name != null && !info.name.isEmpty())
				return "server_" + info.name;
		}
		if(MC.hasSingleplayerServer())
			return "singleplayer";
		return "unknown";
	}
	
	public void openScreen()
	{
		Screen prev = MC.screen;
		MC.setScreen(new OppStatsScreen(prev, this));
	}
	
	private String nullToNA(String value)
	{
		return value == null || value.isBlank() ? "N/A" : value;
	}
	
	private String inferKillerName(String msg, String victimName)
	{
		String lower = msg.toLowerCase(Locale.ROOT);
		int by = lower.lastIndexOf(" by ");
		if(by < 0)
			return null;
		String suffix = msg.substring(by + 4).trim();
		for(OppRecord rec : records.values())
			if(suffix.startsWith(rec.name) && !rec.name.equals(victimName))
				return rec.name;
		return null;
	}
	
	private boolean isBotLikeIdentity(UUID uuid, String name)
	{
		if(isHardLobbyBotName(name))
			return true;
		return NpcUtils.isLikelyNpc(uuid, name);
	}
	
	private boolean isBotLikeEntity(UUID uuid, String displayName)
	{
		if(isHardLobbyBotName(displayName))
			return true;
		return NpcUtils.isLikelyNpc(uuid, displayName);
	}
	
	private boolean isHardLobbyBotName(String rawName)
	{
		if(rawName == null)
			return true;
		
		String name = StringUtil.stripColor(rawName).trim();
		if(name.isEmpty())
			return true;
		if(LOBBY_BOT_NAME.matcher(name).matches())
			return true;
		
		// Normalize weird lobby prefixes/symbols and re-check.
		String normalized = name.replaceAll("[^A-Za-z0-9_.]", "");
		if(normalized.matches("^\\.?\\d{1,4}$"))
			return true;
		
		int digits = 0;
		for(int i = 0; i < name.length(); i++)
		{
			char c = name.charAt(i);
			if(Character.isDigit(c))
			{
				digits++;
				continue;
			}
			if(Character.isLetter(c) || c == '_')
				return false;
		}
		
		return digits > 0 && digits <= 4;
	}
	
	public static final class OppRecord
	{
		public final UUID uuid;
		public String name;
		public boolean online;
		public int ping = -1;
		public String gamemode = "N/A";
		public Vec3 lastPos;
		public double distance = Double.NaN;
		public float health = Float.NaN;
		public float absorption = Float.NaN;
		public int armorValue = -1;
		public String mainHand = "N/A";
		public String offHand = "N/A";
		public String helmet = "N/A";
		public String chest = "N/A";
		public String legs = "N/A";
		public String boots = "N/A";
		public long lastSeenAt;
		public long tabSeenAt;
		public long lastJoinAt;
		public long lastLeaveAt;
		public int joinCount;
		public int killCount;
		public int deathCount;
		public final ArrayList<String> events = new ArrayList<>();
		
		public OppRecord(UUID uuid, String name)
		{
			this.uuid = uuid;
			this.name = name == null ? "unknown" : name;
		}
		
		public void addEvent(String text)
		{
			String line = TS_FORMAT.format(Instant.now()) + " - " + text;
			events.add(0, line);
			while(events.size() > 80)
				events.remove(events.size() - 1);
		}
		
		JsonObject toJson()
		{
			JsonObject o = new JsonObject();
			o.addProperty("uuid", uuid.toString());
			o.addProperty("name", name);
			o.addProperty("online", online);
			o.addProperty("ping", ping);
			o.addProperty("gamemode", gamemode);
			if(lastPos != null)
			{
				o.addProperty("x", lastPos.x);
				o.addProperty("y", lastPos.y);
				o.addProperty("z", lastPos.z);
			}
			o.addProperty("distance", distance);
			o.addProperty("health", health);
			o.addProperty("absorption", absorption);
			o.addProperty("armorValue", armorValue);
			o.addProperty("mainHand", mainHand);
			o.addProperty("offHand", offHand);
			o.addProperty("helmet", helmet);
			o.addProperty("chest", chest);
			o.addProperty("legs", legs);
			o.addProperty("boots", boots);
			o.addProperty("lastSeenAt", lastSeenAt);
			o.addProperty("tabSeenAt", tabSeenAt);
			o.addProperty("lastJoinAt", lastJoinAt);
			o.addProperty("lastLeaveAt", lastLeaveAt);
			o.addProperty("joinCount", joinCount);
			o.addProperty("killCount", killCount);
			o.addProperty("deathCount", deathCount);
			JsonArray ev = new JsonArray();
			for(String e : events)
				ev.add(e);
			o.add("events", ev);
			return o;
		}
		
		static OppRecord fromJson(JsonObject o) throws IOException
		{
			if(!o.has("uuid"))
				throw new IOException("Missing UUID");
			UUID id = UUID.fromString(o.get("uuid").getAsString());
			OppRecord r = new OppRecord(id, get(o, "name", "unknown"));
			r.online = getBool(o, "online", false);
			r.ping = getInt(o, "ping", -1);
			r.gamemode = get(o, "gamemode", "N/A");
			if(o.has("x") && o.has("y") && o.has("z"))
				r.lastPos = new Vec3(o.get("x").getAsDouble(),
					o.get("y").getAsDouble(), o.get("z").getAsDouble());
			r.distance = getDouble(o, "distance", Double.NaN);
			r.health = (float)getDouble(o, "health", Double.NaN);
			r.absorption = (float)getDouble(o, "absorption", Double.NaN);
			r.armorValue = getInt(o, "armorValue", -1);
			r.mainHand = get(o, "mainHand", "N/A");
			r.offHand = get(o, "offHand", "N/A");
			r.helmet = get(o, "helmet", "N/A");
			r.chest = get(o, "chest", "N/A");
			r.legs = get(o, "legs", "N/A");
			r.boots = get(o, "boots", "N/A");
			r.lastSeenAt = getLong(o, "lastSeenAt", 0L);
			r.tabSeenAt = getLong(o, "tabSeenAt", 0L);
			r.lastJoinAt = getLong(o, "lastJoinAt", 0L);
			r.lastLeaveAt = getLong(o, "lastLeaveAt", 0L);
			r.joinCount = getInt(o, "joinCount", 0);
			r.killCount = getInt(o, "killCount", 0);
			r.deathCount = getInt(o, "deathCount", 0);
			if(o.has("events") && o.get("events").isJsonArray())
				for(var ev : o.getAsJsonArray("events"))
					r.events.add(ev.getAsString());
			return r;
		}
		
		private static String get(JsonObject o, String key, String fallback)
		{
			return o.has(key) ? o.get(key).getAsString() : fallback;
		}
		
		private static int getInt(JsonObject o, String key, int fallback)
		{
			return o.has(key) ? o.get(key).getAsInt() : fallback;
		}
		
		private static long getLong(JsonObject o, String key, long fallback)
		{
			return o.has(key) ? o.get(key).getAsLong() : fallback;
		}
		
		private static double getDouble(JsonObject o, String key,
			double fallback)
		{
			return o.has(key) ? o.get(key).getAsDouble() : fallback;
		}
		
		private static boolean getBool(JsonObject o, String key,
			boolean fallback)
		{
			return o.has(key) ? o.get(key).getAsBoolean() : fallback;
		}
	}
}
