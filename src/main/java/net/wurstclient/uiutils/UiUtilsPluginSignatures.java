/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Known-plugin fingerprint database.
 *
 * The scanner cannot rely on tab completion because anti-enumeration servers
 * suppress {@code ClientboundCommandSuggestionsPacket} entirely. Instead it
 * matches passive evidence - command roots, command namespaces, plugin message
 * channels, known packs, registry/advancement namespaces - against this
 * database, then combines the matches into a confidence score.
 *
 * A single distinctive root such as {@code /lp} is reasonable evidence. Several
 * characteristic roots, or a namespace/channel match, are much stronger.
 */
public final class UiUtilsPluginSignatures
{
	/**
	 * Evidence kinds, ordered from strongest to weakest. The scanner records
	 * which kinds matched so the UI can explain every hit.
	 */
	public enum Source
	{
		NAMESPACE("command namespace"),
		CHANNEL("plugin channel"),
		KNOWN_PACK("known pack"),
		PLUGIN_LIST("plugin list"),
		VERSION("version query"),
		COMMAND("command root"),
		FOREIGN_NAMESPACE("foreign namespace"),
		VERSION_HINT("version hint"),
		BRAND("server brand");
		
		private final String label;
		
		Source(String label)
		{
			this.label = label;
		}
		
		public String label()
		{
			return label;
		}
	}
	
	private static final Map<String, String> DISPLAY_NAMES =
		new LinkedHashMap<>();
	private static final Map<String, String> COMMANDS = new LinkedHashMap<>();
	private static final Map<String, String> NAMESPACES = new LinkedHashMap<>();
	private static final Map<String, String> CHANNELS = new LinkedHashMap<>();
	
	/**
	 * Roots that many unrelated plugins provide, so they prove very little.
	 * Built from a list rather than {@code Set.of} so a repeated entry can
	 * never
	 * crash class initialization.
	 */
	private static final Set<String> AMBIGUOUS_ROOTS =
		new LinkedHashSet<>(List.of("home", "homes", "spawn", "kit", "kits",
			"warp", "warps", "sethome", "delhome", "setwarp", "delwarp", "back",
			"tp", "tpa", "tpaccept", "tpdeny", "tpahere", "rtp", "wild",
			"wilderness", "shop", "sell", "ah", "auction", "pay", "balance",
			"bal", "money", "ban", "unban", "mute", "unmute", "kick", "warn",
			"jail", "fly", "god", "heal", "feed", "repair", "nick", "hat",
			"near", "seen", "mail", "msg", "reply", "help", "list", "menu",
			"poke", "broadcast", "reset", "stats", "top", "score", "quest",
			"quests", "daily", "reward"));
	
	private UiUtilsPluginSignatures()
	{}
	
	static
	{
		/* --------------------------- LuckPerms -------------------------- */
		register("luckperms", "LuckPerms", "lp", "luckperms", "perms", "perm",
			"permissions", "meta", "editu", "u", "track", "tracks", "group",
			"groups", "defaults", "verbose");
		namespace("luckperms", "luckperms");
		channel("luckperms", "luckperms");
		
		/* ---------------------------- WorldEdit ------------------------- */
		register("worldedit", "WorldEdit", "we", "worldedit", "schematic",
			"schem", "gmask", "hcyl", "sphere", "hsphere", "cyl", "walls",
			"faces", "overlay", "stack", "move", "distr", "expands", "contract",
			"hpos1", "hpos2", "pos1", "pos2", "jumpto", "thru", "none",
			"toggleeditwand", "wand", "tool", "brush", "mask", "size", "count");
		namespace("worldedit", "worldedit");
		channel("worldedit", "worldedit");
		
		/* ---------------------------- WorldGuard ------------------------ */
		register("worldguard", "WorldGuard", "rg", "region", "regions",
			"worldguard", "wg");
		namespace("worldguard", "worldguard");
		
		/* ---------------------------- Essentials ------------------------ */
		register("essentialsx", "EssentialsX", "essentials", "ess",
			"essversion", "eco", "baltop", "balancetop", "ci", "clearinventory",
			"ec", "enderchest", "echest", "invsee", "kittycannon", "powertool",
			"powertoollist", "ptime", "pweather", "socialspy", "vanish",
			"sethome", "setwarp", "tpo", "tpohere", "tppos", "tpoffline",
			"tempban", "tempbanip", "skull", "spawnmob", "spawner", "more",
			"nickname", "realname", "unlimited", "bottom", "depth", "jump",
			"condense", "itemdb", "itemlore", "itemname", "lightning", "nuke",
			"fireball", "beezooka", "broadcastworld", "motd", "rules", "whois",
			"gc", "lag", "uptime", "tps", "playtime", "mail", "mailtoggle");
		namespace("essentials", "essentialsx");
		namespace("essentialsx", "essentialsx");
		channel("essentials", "essentialsx");
		
		/* --------------------------- CoreProtect ------------------------ */
		register("coreprotect", "CoreProtect", "coreprotect", "co", "cohelp",
			"coinspect", "colookup", "corollback", "corestore", "copurge",
			"conear", "corelations", "colimit");
		namespace("coreprotect", "coreprotect");
		
		/* ---------------------------- Citizens -------------------------- */
		register("citizens", "Citizens", "citizens", "npc", "npc2", "npcs",
			"trait", "tpl", "waypoint", "template");
		namespace("citizens", "citizens");
		
		/* --------------------------- Multiverse ------------------------- */
		register("multiverse-core", "Multiverse-Core", "multiverse", "mv",
			"mvtp", "mvcreate", "mvdelete", "mvinfo", "mvlist", "mvmodify",
			"mvspawn", "mvconfig", "mvimport", "mvreload", "mvwho", "mvreg");
		namespace("multiverse-core", "multiverse-core");
		namespace("multiverse", "multiverse-core");
		
		/* ---------------------------- PlaceholderAPI -------------------- */
		register("placeholderapi", "PlaceholderAPI", "placeholderapi", "papi",
			"placeholders", "phapi");
		namespace("placeholderapi", "placeholderapi");
		channel("placeholderapi", "placeholderapi");
		
		/* ------------------------------ CMI ----------------------------- */
		register("cmi", "CMI", "cmi", "cmiadmin", "cmikits", "cmiutility",
			"cmieconomy");
		namespace("cmi", "cmi");
		namespace("cmilib", "cmi");
		
		/* ---------------------------- ProtocolLib ----------------------- */
		register("protocollib", "ProtocolLib", "protocollib", "plib",
			"protocol");
		namespace("protocollib", "protocollib");
		
		/* ---------------------------- ViaVersion ------------------------ */
		register("viaversion", "ViaVersion", "viaversion", "viaver", "via",
			"vv", "viafabric", "viaadmin");
		namespace("viaversion", "viaversion");
		namespace("viafabric", "viaversion");
		register("viabackwards", "ViaBackwards", "viabackwards");
		namespace("viabackwards", "viabackwards");
		register("viarewind", "ViaRewind", "viarewind");
		namespace("viarewind", "viarewind");
		channel("viaversion", "viaversion");
		channel("viabackwards", "viabackwards");
		channel("viarewind", "viarewind");
		
		/* ---------------------------- GeyserMC -------------------------- */
		register("geysermc", "GeyserMC", "geyser");
		namespace("geyser", "geysermc");
		channel("geyser", "geysermc");
		
		/* ---------------------------- Floodgate ------------------------- */
		register("floodgate", "Floodgate", "floodgate", "fg");
		namespace("floodgate", "floodgate");
		channel("floodgate", "floodgate");
		
		/* -------------------------- GriefPrevention --------------------- */
		register("griefprevention", "GriefPrevention", "griefprevention", "gp",
			"claim", "claims", "trust", "untrust", "trustlist", "abandonclaim",
			"abandonallclaims", "claimslist", "deleteclaim", "accesstrust",
			"containertrust", "permissiontrust", "buyclaimblocks",
			"sellclaimblocks", "ignoreplayer", "unignoreplayer",
			"ignoredplayerlist", "transferclaim", "restoreclaim",
			"restoreallclaims");
		namespace("griefprevention", "griefprevention");
		
		/* -------------------------- Towny ------------------------------- */
		register("towny", "Towny", "towny", "town", "townyadmin", "nation",
			"resident", "mayor", "ta", "townychat", "plot", "plotgroup");
		namespace("towny", "towny");
		
		/* -------------------------- Factions ---------------------------- */
		register("factions", "Factions", "factions", "faction", "f", "fac",
			"fadmin");
		namespace("factions", "factions");
		
		/* --------------------------- mcMMO ------------------------------ */
		register("mcmmo", "mcMMO", "mcmmo", "mcc", "mcstats", "mctop", "mcrank",
			"party", "partyadmin", "mcinspect", "addxp", "mmoedit",
			"skillreset", "mcability", "mcgod");
		namespace("mcmmo", "mcmmo");
		
		/* ---------------------------- Jobs ------------------------------ */
		register("jobs", "Jobs Reborn", "jobs", "job", "jobinfo", "jobtop",
			"jobstats", "jobbrowse", "jobsadmin");
		namespace("jobs", "jobs");
		
		/* -------------------------- MythicMobs -------------------------- */
		register("mythicmobs", "MythicMobs", "mythicmobs", "mythicmob", "mm",
			"mmob", "mmobs", "mythic");
		namespace("mythicmobs", "mythicmobs");
		
		/* --------------------------- Skript ----------------------------- */
		register("skript", "Skript", "skript", "sk", "skquery", "skriptadmin");
		namespace("skript", "skript");
		
		/* -------------------------- DeluxeMenus ------------------------- */
		register("deluxemenus", "DeluxeMenus", "deluxemenus", "dm",
			"deluxemenu");
		namespace("deluxemenus", "deluxemenus");
		
		/* -------------------------- PlotSquared ------------------------- */
		register("plotsquared", "PlotSquared", "plotsquared", "plots", "plot",
			"p2", "plotme");
		namespace("plotsquared", "plotsquared");
		
		/* --------------------------- Shopkeepers ------------------------ */
		register("shopkeepers", "Shopkeepers", "shopkeepers", "shopkeeper",
			"skadmin");
		namespace("shopkeepers", "shopkeepers");
		
		/* --------------------------- Dynmap ----------------------------- */
		register("dynmap", "Dynmap", "dynmap", "dmap");
		namespace("dynmap", "dynmap");
		channel("dynmap", "dynmap");
		
		/* -------------------------- SuperVanish ------------------------- */
		register("supervanish", "SuperVanish", "supervanish", "sv", "unvanish");
		namespace("supervanish", "supervanish");
		
		/* ---------------------------- AuthMe ---------------------------- */
		register("authme", "AuthMe", "authme", "authmeadmin", "unregister",
			"changepassword", "email", "captcha", "authmebungee");
		namespace("authme", "authme");
		channel("authme", "authme");
		
		/* ------------------------- SkinsRestorer ------------------------ */
		register("skinsrestorer", "SkinsRestorer", "skinsrestorer", "skin",
			"skins", "sr", "skinadmin");
		namespace("skinsrestorer", "skinsrestorer");
		
		/* ---------------------------- Oraxen ---------------------------- */
		register("oraxen", "Oraxen", "oraxen");
		namespace("oraxen", "oraxen");
		channel("oraxen", "oraxen");
		
		/* --------------------------- ItemsAdder ------------------------- */
		register("itemsadder", "ItemsAdder", "itemsadder", "ia", "iareload",
			"iacons");
		namespace("itemsadder", "itemsadder");
		channel("itemsadder", "itemsadder");
		
		/* -------------------------- PacketEvents ------------------------ */
		register("packetevents", "PacketEvents", "packetevents", "pe",
			"packetevent");
		namespace("packetevents", "packetevents");
		
		/* ---------------------------- LiteBans -------------------------- */
		register("litebans", "LiteBans", "litebans", "lb", "bans", "mutes",
			"warns", "kicks", "history", "dupeip", "alts", "checkban",
			"checkmute", "note", "staffnotes");
		namespace("litebans", "litebans");
		
		/* -------------------------- Simple Voice Chat ------------------- */
		register("voicechat", "Simple Voice Chat", "voicechat", "vc");
		namespace("voicechat", "voicechat");
		channel("voicechat", "voicechat");
		
		/* --------------------------- Terraform / datapack platforms ----- */
		register("terra", "Terra", "terra");
		namespace("terra", "terra");
		
		/* --------------------------- Discord ---------------------------- */
		register("discordsrv", "DiscordSRV", "discord", "discordsrv", "dsrv");
		namespace("discordsrv", "discordsrv");
		channel("discordsrv", "discordsrv");
		
		/* --------------------------- Chat plugins ----------------------- */
		register("venturechat", "VentureChat", "venturechat", "vchat", "ch");
		namespace("venturechat", "venturechat");
		channel("venturechat", "venturechat");
		register("styledchat", "StyledChat", "styledchat", "stylechat");
		namespace("styledchat", "styledchat");
		
		/* --------------------------- ClearLag --------------------------- */
		register("clearlag", "ClearLag", "clearlag", "lagg", "laggclear",
			"lagclear");
		namespace("clearlag", "clearlag");
		
		/* ---------------------------- LibsLogin ------------------------- */
		register("librelogin", "LibreLogin", "librelogin", "lrl");
		namespace("librelogin", "librelogin");
		channel("librelogin", "librelogin");
		register("openlogin", "OpenLogin", "openlogin", "ologin");
		namespace("openlogin", "openlogin");
		channel("openlogin", "openlogin");
		
		/* ------------------------- Proxy platforms ---------------------- */
		register("bungeecord", "BungeeCord", "bungee", "bungeecord", "alert",
			"send", "glist", "greload", "ip", "server");
		channel("bungeecord", "bungeecord");
		register("velocity", "Velocity", "velocity", "velo");
		channel("velocity", "velocity");
		
		/* ---------------------------- Emotecraft ------------------------ */
		register("emotecraft", "Emotecraft", "emotecraft");
		namespace("emotecraft", "emotecraft");
		channel("emotecraft", "emotecraft");
		
		/* --------------------------- Server brands ---------------------- */
		register("papermc", "PaperMC", "paper");
		namespace("paper", "papermc");
		register("spigot", "Spigot", "spigot", "spigotadmin");
		namespace("spigot", "spigot");
		
		/* ---------------------------- Datapacks ------------------------- */
		register("terralith", "Terralith", "terralith");
		namespace("terralith", "terralith");
		register("incendium", "Incendium", "incendium");
		namespace("incendium", "incendium");
		register("nullscape", "Nullscape", "nullscape");
		namespace("nullscape", "nullscape");
	}
	
	private static void register(String key, String displayName,
		String... commandRoots)
	{
		DISPLAY_NAMES.put(key, displayName);
		for(String root : commandRoots)
			COMMANDS.putIfAbsent(root.toLowerCase(Locale.ROOT), key);
	}
	
	private static void namespace(String namespace, String key)
	{
		if(!DISPLAY_NAMES.containsKey(key))
			DISPLAY_NAMES.put(key, key);
		NAMESPACES.putIfAbsent(namespace.toLowerCase(Locale.ROOT), key);
	}
	
	private static void channel(String namespace, String key)
	{
		if(!DISPLAY_NAMES.containsKey(key))
			DISPLAY_NAMES.put(key, key);
		CHANNELS.putIfAbsent(namespace.toLowerCase(Locale.ROOT), key);
	}
	
	/**
	 * Maps a command root onto a plugin, or null when it is not a known
	 * signature. Ambiguous roots still map, but callers should treat them as
	 * weak evidence.
	 */
	public static String pluginForCommand(String root)
	{
		if(root == null || root.isBlank())
			return null;
		return COMMANDS.get(root.trim().toLowerCase(Locale.ROOT));
	}
	
	public static String pluginForNamespace(String namespace)
	{
		if(namespace == null || namespace.isBlank())
			return null;
		return NAMESPACES.get(namespace.trim().toLowerCase(Locale.ROOT));
	}
	
	public static String pluginForChannel(String namespace)
	{
		if(namespace == null || namespace.isBlank())
			return null;
		return CHANNELS.get(namespace.trim().toLowerCase(Locale.ROOT));
	}
	
	public static boolean isAmbiguousRoot(String root)
	{
		if(root == null || root.isBlank())
			return true;
		return AMBIGUOUS_ROOTS.contains(root.trim().toLowerCase(Locale.ROOT));
	}
	
	public static String displayName(String pluginKey)
	{
		if(pluginKey == null || pluginKey.isBlank())
			return "Unknown";
		String key = pluginKey.trim().toLowerCase(Locale.ROOT);
		String display = DISPLAY_NAMES.get(key);
		if(display != null)
			return display;
		return UiUtilsServerFingerprintCollector.friendlyName(key);
	}
	
	/**
	 * Plugin names worth dictionary-testing with {@code /version <name>} and
	 * {@code /ver <name>}.
	 */
	public static Collection<String> candidatePluginNames()
	{
		Set<String> candidates = new LinkedHashSet<>();
		for(String display : DISPLAY_NAMES.values())
			candidates.add(display);
		for(String key : DISPLAY_NAMES.keySet())
			candidates.add(key);
		return List.copyOf(candidates);
	}
	
	/**
	 * Every namespace worth probing with {@code /<namespace>:}.
	 */
	public static Set<String> knownNamespaces()
	{
		return new TreeSet<>(NAMESPACES.keySet());
	}
}
