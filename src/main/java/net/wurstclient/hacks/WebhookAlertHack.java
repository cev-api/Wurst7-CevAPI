/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ChatInputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.NpcUtils;
import net.wurstclient.util.PlayerRangeAlertManager;

@SearchTags({"webhook", "discord", "alert", "ping"})
public final class WebhookAlertHack extends Hack
	implements ChatInputListener, UpdateListener
{
	private static final DateTimeFormatter TIME_FORMAT =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final long STOPPED_MOVING_MS = 5000L;
	private static final long STARVING_COOLDOWN_MS = 60000L;
	private static final int PORTAL_SCAN_RADIUS = 4;
	
	private final TextFieldSetting webhookUrl = new TextFieldSetting(
		"Discord webhook URL",
		"Discord webhook URL to send alerts to. Leave empty to disable sending.",
		"");
	private final TextFieldSetting mentionAliases =
		new TextFieldSetting("Mention aliases",
			"Optional comma-separated names that also count as mentions.", "");
	
	private final CheckboxSetting mentions = new CheckboxSetting("Mentions",
		"Send a webhook when chat mentions your name or alias.", true);
	private final CheckboxSetting playerEnterRange = new CheckboxSetting(
		"Player enter range",
		"Send a webhook when another player enters client render range.", true);
	private final CheckboxSetting portalDetection = new CheckboxSetting(
		"Portal detection",
		"Send a webhook when a nearby nether/end portal is detected.", true);
	private final CheckboxSetting playerDamage = new CheckboxSetting(
		"Player damage", "Send a webhook when you take damage.", true);
	private final CheckboxSetting playerStarving =
		new CheckboxSetting("Player starving",
			"Send a webhook when your food level reaches zero.", true);
	private final CheckboxSetting newChunkDetection =
		new CheckboxSetting("New chunk detection",
			"Send a webhook when NewerNewChunks marks a new chunk.", true);
	private final CheckboxSetting oldChunkDetection =
		new CheckboxSetting("Old chunk detection",
			"Send a webhook when NewerNewChunks marks an old chunk.", true);
	private final SliderSetting chunkAlertTravelDistance = new SliderSetting(
		"Chunk alert travel distance",
		"Minimum distance you must move before another old/new chunk webhook is sent.",
		96, 16, 512, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final CheckboxSetting playerStoppedMoving =
		new CheckboxSetting("Player stopped moving",
			"Send a webhook when you have not moved for 5 seconds.", true);
	private final CheckboxSetting ignoreNpcs =
		new CheckboxSetting("Ignore NPCs",
			"Skips likely NPC players in player-range webhook alerts.", true);
	private final CheckboxSetting autoFlyStopped =
		new CheckboxSetting("AutoFly stopped",
			"Send a webhook when AutoFly stops due to a stop condition.", true);
	private final CheckboxSetting chatFeedback =
		new CheckboxSetting("Chat feedback",
			"Show local chat warnings if a webhook cannot be sent.", false);
	
	private final ExecutorService webhookExecutor =
		Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "webhook-alert");
			t.setDaemon(true);
			return t;
		});
	private final Set<Long> seenPortalPositions = new HashSet<>();
	private final PlayerRangeAlertManager.Listener rangeListener =
		new PlayerRangeAlertManager.Listener()
		{
			@Override
			public void onPlayerEnter(Player player,
				PlayerRangeAlertManager.PlayerInfo info)
			{
				handlePlayerEnter(player, info);
			}
			
			@Override
			public void onPlayerExit(PlayerRangeAlertManager.PlayerInfo info)
			{}
		};
	
	private float lastHealth = -1F;
	private int lastHurtTime;
	private int lastFoodLevel = -1;
	private long lastStarvingAlertMs;
	private Vec3 lastMovingPos;
	private long lastMovedMs;
	private boolean stoppedMovingAlertSent;
	private Vec3 lastNewChunkAlertPos;
	private Vec3 lastOldChunkAlertPos;
	
	public WebhookAlertHack()
	{
		super("WebhookAlert",
			"Sends selected client-side alerts to a Discord webhook.", false);
		setCategory(Category.OTHER);
		addSetting(webhookUrl);
		addSetting(mentionAliases);
		addSetting(mentions);
		addSetting(playerEnterRange);
		addSetting(portalDetection);
		addSetting(playerDamage);
		addSetting(playerStarving);
		addSetting(newChunkDetection);
		addSetting(oldChunkDetection);
		addSetting(chunkAlertTravelDistance);
		addSetting(playerStoppedMoving);
		addSetting(ignoreNpcs);
		addSetting(autoFlyStopped);
		addSetting(chatFeedback);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(ChatInputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		WURST.getPlayerRangeAlertManager().addListener(rangeListener);
		resetState();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(ChatInputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		WURST.getPlayerRangeAlertManager().removeListener(rangeListener);
		seenPortalPositions.clear();
	}
	
	@Override
	public void onReceivedMessage(ChatInputEvent event)
	{
		if(!mentions.isChecked() || MC.player == null || event == null)
			return;
		
		Component component = event.getComponent();
		String message = component == null ? ""
			: stripFormatting(component.getString()).trim();
		if(message.isEmpty() || !containsMention(message))
			return;
		
		send("Mention",
			"Time: " + now() + "\nMessage: " + message + "\nMessage length: "
				+ message.length() + "\n" + localPlayerStatus() + "\n"
				+ worldStatus());
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null)
		{
			resetState();
			return;
		}
		
		checkDamage();
		checkStarving();
		checkStoppedMoving();
		checkNearbyPortals();
	}
	
	public void onChunkDetected(String type, ChunkPos chunkPos)
	{
		if(!isEnabled() || chunkPos == null)
			return;
		
		boolean isOld =
			type != null && type.toLowerCase(Locale.ROOT).contains("old");
		if(isOld && !oldChunkDetection.isChecked())
			return;
		if(!isOld && !newChunkDetection.isChecked())
			return;
		
		int x = chunkPos.getMiddleBlockX();
		int z = chunkPos.getMiddleBlockZ();
		Vec3 playerPos = MC.player == null ? null : MC.player.position();
		if(playerPos != null)
		{
			Vec3 lastPos = isOld ? lastOldChunkAlertPos : lastNewChunkAlertPos;
			if(lastPos != null)
			{
				double minTravel = chunkAlertTravelDistance.getValue();
				if(playerPos.distanceTo(lastPos) < minTravel)
					return;
			}
			if(isOld)
				lastOldChunkAlertPos = playerPos;
			else
				lastNewChunkAlertPos = playerPos;
		}
		
		String label = isOld ? "Old chunk detected" : "New chunk detected";
		send(label,
			"Time: " + now() + "\nChunk: " + chunkPos.x() + ", " + chunkPos.z()
				+ "\nApprox coords: " + x + ", " + z + "\n"
				+ "Chunk block range: x=" + (chunkPos.getMinBlockX()) + ".."
				+ (chunkPos.getMaxBlockX()) + ", z=" + (chunkPos.getMinBlockZ())
				+ ".." + (chunkPos.getMaxBlockZ()) + "\n" + localPlayerStatus()
				+ "\n" + worldStatus());
	}
	
	public void onAutoFlyStopped(String reason)
	{
		if(!isEnabled() || !autoFlyStopped.isChecked())
			return;
		
		send("AutoFly stopped", "Time: " + now() + "\nReason: " + safe(reason)
			+ "\n" + localPlayerStatus() + "\n" + worldStatus());
	}
	
	private void resetState()
	{
		lastHealth = MC.player == null ? -1F : MC.player.getHealth();
		lastHurtTime = MC.player == null ? 0 : MC.player.hurtTime;
		lastFoodLevel =
			MC.player == null ? -1 : MC.player.getFoodData().getFoodLevel();
		lastMovingPos = MC.player == null ? null : MC.player.position();
		lastMovedMs = System.currentTimeMillis();
		stoppedMovingAlertSent = false;
		lastStarvingAlertMs = 0L;
		lastNewChunkAlertPos = null;
		lastOldChunkAlertPos = null;
		seenPortalPositions.clear();
	}
	
	private void handlePlayerEnter(Player player,
		PlayerRangeAlertManager.PlayerInfo info)
	{
		if(!playerEnterRange.isChecked() || MC.player == null)
			return;
		if(ignoreNpcs.isChecked())
		{
			if(player != null && NpcUtils.isLikelyNpcPlayer(player))
				return;
			if(info != null
				&& NpcUtils.isLikelyNpc(info.getUuid(), info.getName()))
				return;
		}
		
		String name = info == null ? "Unknown" : safe(info.getName());
		Vec3 pos = info == null ? null : info.getLastPos();
		if(pos == null && player != null)
			pos = player.position();
		
		double distance =
			pos == null ? -1 : MC.player.position().distanceTo(pos);
		UUID uuid = info == null ? null : info.getUuid();
		Player livePlayer = player;
		if(livePlayer == null && uuid != null && MC.level != null)
			livePlayer = MC.level.getPlayerByUUID(uuid);
		
		send("Player entered range",
			"Time: " + now() + "\nPlayer: " + name + "\nUUID: "
				+ (uuid == null ? "unknown" : uuid) + "\nPosition: "
				+ formatVec(pos) + "\nDistance: "
				+ (distance < 0 ? "unknown"
					: String.format(Locale.ROOT, "%.1f blocks", distance))
				+ "\nTarget status: " + formatTargetStatus(livePlayer)
				+ "\nMain hand: "
				+ formatDetailedStack(livePlayer == null
					? ItemStack.EMPTY : livePlayer.getMainHandItem())
				+ "\nOff hand: "
				+ formatDetailedStack(livePlayer == null
					? ItemStack.EMPTY : livePlayer.getOffhandItem())
				+ "\nHelmet: "
				+ formatDetailedStack(livePlayer == null ? ItemStack.EMPTY
					: livePlayer.getItemBySlot(EquipmentSlot.HEAD))
				+ "\nChestplate: "
				+ formatDetailedStack(livePlayer == null ? ItemStack.EMPTY
					: livePlayer.getItemBySlot(EquipmentSlot.CHEST))
				+ "\nLeggings: "
				+ formatDetailedStack(livePlayer == null ? ItemStack.EMPTY
					: livePlayer.getItemBySlot(EquipmentSlot.LEGS))
				+ "\nBoots: "
				+ formatDetailedStack(livePlayer == null ? ItemStack.EMPTY
					: livePlayer.getItemBySlot(EquipmentSlot.FEET))
				+ "\nEquipment summary: " + formatEquipment(livePlayer) + "\n"
				+ localPlayerStatus() + "\n" + worldStatus());
	}
	
	private void checkDamage()
	{
		if(!playerDamage.isChecked())
		{
			lastHealth = MC.player.getHealth();
			lastHurtTime = MC.player.hurtTime;
			return;
		}
		
		float health = MC.player.getHealth();
		int hurtTime = MC.player.hurtTime;
		if(hurtTime > lastHurtTime)
		{
			float amount =
				lastHealth < 0 ? 0F : Math.max(0F, lastHealth - health);
			DamageSource source = MC.player.getLastDamageSource();
			send("Player damage", "Time: " + now() + "\nWhere: "
				+ formatBlockPos(MC.player.blockPosition()) + "\nAmount: "
				+ String.format(Locale.ROOT, "%.1f", amount) + "\nHealth: "
				+ String.format(Locale.ROOT, "%.1f", health) + "\nAbsorption: "
				+ String.format(Locale.ROOT, "%.1f",
					MC.player.getAbsorptionAmount())
				+ "\nSource: " + formatDamageSource(source) + "\n"
				+ localPlayerStatus() + "\n" + worldStatus());
		}
		
		lastHealth = health;
		lastHurtTime = hurtTime;
	}
	
	private void checkStarving()
	{
		int food = MC.player.getFoodData().getFoodLevel();
		long nowMs = System.currentTimeMillis();
		if(playerStarving.isChecked() && food <= 0 && lastFoodLevel > 0
			&& nowMs - lastStarvingAlertMs >= STARVING_COOLDOWN_MS)
		{
			lastStarvingAlertMs = nowMs;
			send("Player starving",
				"Time: " + now() + "\nWhere: "
					+ formatBlockPos(MC.player.blockPosition())
					+ "\nFood level: " + food + "\nSaturation: "
					+ String.format(Locale.ROOT, "%.1f",
						MC.player.getFoodData().getSaturationLevel())
					+ "\n" + localPlayerStatus() + "\n" + worldStatus());
		}
		lastFoodLevel = food;
	}
	
	private void checkStoppedMoving()
	{
		if(!playerStoppedMoving.isChecked())
		{
			lastMovingPos = MC.player.position();
			lastMovedMs = System.currentTimeMillis();
			stoppedMovingAlertSent = false;
			return;
		}
		
		Vec3 pos = MC.player.position();
		long nowMs = System.currentTimeMillis();
		if(lastMovingPos == null
			|| pos.distanceToSqr(lastMovingPos) > 0.01 * 0.01)
		{
			lastMovingPos = pos;
			lastMovedMs = nowMs;
			stoppedMovingAlertSent = false;
			return;
		}
		
		if(!stoppedMovingAlertSent && nowMs - lastMovedMs >= STOPPED_MOVING_MS)
		{
			stoppedMovingAlertSent = true;
			send("Player stopped moving",
				"Time: " + now() + "\nWhere: " + formatVec(pos) + "\n"
					+ "Idle for: " + (STOPPED_MOVING_MS / 1000) + "s\n"
					+ localPlayerStatus() + "\n" + worldStatus());
		}
	}
	
	private void checkNearbyPortals()
	{
		if(!portalDetection.isChecked())
			return;
		
		BlockPos center = MC.player.blockPosition();
		for(int x = -PORTAL_SCAN_RADIUS; x <= PORTAL_SCAN_RADIUS; x++)
			for(int y = -PORTAL_SCAN_RADIUS; y <= PORTAL_SCAN_RADIUS; y++)
				for(int z = -PORTAL_SCAN_RADIUS; z <= PORTAL_SCAN_RADIUS; z++)
				{
					BlockPos pos = center.offset(x, y, z);
					Block block = MC.level.getBlockState(pos).getBlock();
					if(block != Blocks.NETHER_PORTAL
						&& block != Blocks.END_PORTAL)
						continue;
					
					if(!seenPortalPositions.add(pos.asLong()))
						continue;
					
					send("Portal detected",
						"Time: " + now() + "\nType: "
							+ (block == Blocks.NETHER_PORTAL ? "Nether portal"
								: "End portal")
							+ "\nCoordinates: " + formatBlockPos(pos)
							+ "\nDistance from player: "
							+ String.format(Locale.ROOT, "%.1f blocks",
								MC.player.position()
									.distanceTo(Vec3.atCenterOf(pos)))
							+ "\n" + localPlayerStatus() + "\n"
							+ worldStatus());
				}
	}
	
	private boolean containsMention(String message)
	{
		String ownName = MC.getUser().getName();
		if(containsName(message, ownName))
			return true;
		
		String aliases = mentionAliases.getValue();
		if(aliases == null || aliases.isBlank())
			return false;
		
		for(String alias : aliases.split("[,\\s]+"))
			if(containsName(message, alias))
				return true;
			
		return false;
	}
	
	private boolean containsName(String message, String name)
	{
		if(message == null || name == null || name.isBlank())
			return false;
		
		Pattern pattern = Pattern
			.compile("(?i)(^|\\W)@?" + Pattern.quote(name.trim()) + "(\\W|$)");
		return pattern.matcher(message).find();
	}
	
	private void send(String title, String body)
	{
		String url = webhookUrl.getValue();
		if(url == null || url.isBlank())
			return;
		
		String content = "**" + safe(title) + "**\n" + safe(body);
		if(content.length() > 1900)
			content = content.substring(0, 1900) + "...";
		
		String payload = "{\"content\":\"" + escapeJson(content) + "\"}";
		webhookExecutor.submit(() -> postWebhook(url.trim(), payload));
	}
	
	private void postWebhook(String url, String payload)
	{
		try
		{
			HttpURLConnection conn =
				(HttpURLConnection)URI.create(url).toURL().openConnection();
			conn.setRequestMethod("POST");
			conn.setConnectTimeout(5000);
			conn.setReadTimeout(5000);
			conn.setDoOutput(true);
			conn.setRequestProperty("Content-Type",
				"application/json; charset=utf-8");
			byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
			conn.setFixedLengthStreamingMode(bytes.length);
			try(OutputStream out = conn.getOutputStream())
			{
				out.write(bytes);
			}
			
			int code = conn.getResponseCode();
			if(code < 200 || code >= 300)
				reportError("Discord webhook returned HTTP " + code + ".");
			
			conn.disconnect();
		}catch(Exception e)
		{
			reportError("Discord webhook failed: " + e.getMessage());
		}
	}
	
	private void reportError(String message)
	{
		if(chatFeedback.isChecked() && MC != null)
			MC.execute(() -> ChatUtils.error("WebhookAlert: " + message));
	}
	
	private String localPlayerStatus()
	{
		if(MC.player == null || MC.level == null)
			return "Player: unknown";
		
		return "Player: " + MC.getUser().getName() + "\nCurrent position: "
			+ formatBlockPos(MC.player.blockPosition()) + "\nHealth: "
			+ String.format(Locale.ROOT, "%.1f", MC.player.getHealth()) + " (+"
			+ String.format(Locale.ROOT, "%.1f",
				MC.player.getAbsorptionAmount())
			+ " absorption)\nArmor points: " + MC.player.getArmorValue()
			+ "\nFood: " + MC.player.getFoodData().getFoodLevel() + ", sat="
			+ String.format(Locale.ROOT, "%.1f",
				MC.player.getFoodData().getSaturationLevel())
			+ "\nExperience level: " + MC.player.experienceLevel + ", progress="
			+ String.format(Locale.ROOT, "%.2f", MC.player.experienceProgress)
			+ "\nVelocity: " + formatVec(MC.player.getDeltaMovement())
			+ "\nOn ground: " + MC.player.onGround();
	}
	
	private String worldStatus()
	{
		if(MC.player == null || MC.level == null)
			return "World: unknown";
		
		ChunkPos chunk = MC.player.chunkPosition();
		long gameTime = MC.level.getGameTime();
		return "Dimension: " + MC.level.dimension().identifier() + "\nChunk: "
			+ chunk.x() + ", " + chunk.z() + "\nBiome: "
			+ MC.level.getBiome(MC.player.blockPosition()).unwrapKey()
				.map(k -> k.identifier().toString()).orElse("unknown")
			+ "\nWorld time: " + gameTime + " ticks";
	}
	
	private String formatDamageSource(DamageSource source)
	{
		if(source == null)
			return "unknown";
		
		String msg = safe(source.getMsgId());
		Entity attacker = source.getEntity();
		Entity direct = source.getDirectEntity();
		Entity actor = attacker != null ? attacker : direct;
		if(actor != null)
			msg += " by " + actor.getName().getString() + " at "
				+ formatBlockPos(actor.blockPosition());
		return msg;
	}
	
	private String formatEquipment(Player player)
	{
		if(player == null)
			return "unknown";
		
		return "main=" + formatStack(player.getMainHandItem()) + ", off="
			+ formatStack(player.getOffhandItem()) + ", head="
			+ formatStack(player.getItemBySlot(EquipmentSlot.HEAD)) + ", chest="
			+ formatStack(player.getItemBySlot(EquipmentSlot.CHEST)) + ", legs="
			+ formatStack(player.getItemBySlot(EquipmentSlot.LEGS)) + ", feet="
			+ formatStack(player.getItemBySlot(EquipmentSlot.FEET));
	}
	
	private String formatTargetStatus(Player player)
	{
		if(player == null)
			return "unknown";
		
		String ping = "unknown";
		String gameMode = "unknown";
		if(MC.getConnection() != null
			&& MC.getConnection().getPlayerInfo(player.getUUID()) != null)
		{
			var info = MC.getConnection().getPlayerInfo(player.getUUID());
			ping = info.getLatency() + "ms";
			if(info.getGameMode() != null)
				gameMode = info.getGameMode().getName();
		}
		
		return "health="
			+ String.format(Locale.ROOT, "%.1f", player.getHealth())
			+ ", absorption="
			+ String.format(Locale.ROOT, "%.1f", player.getAbsorptionAmount())
			+ ", armor=" + player.getArmorValue() + ", ping=" + ping
			+ ", gamemode=" + gameMode;
	}
	
	private String formatDetailedStack(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return "empty";
		
		String enchants = getEnchantmentSummary(stack);
		String durability = stack.isDamageableItem()
			? ("durability=" + (stack.getMaxDamage() - stack.getDamageValue())
				+ "/" + stack.getMaxDamage())
			: "durability=n/a";
		
		return stack.getHoverName().getString() + " x" + stack.getCount()
			+ ", itemId="
			+ net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getKey(stack.getItem()).toString()
			+ ", " + durability + ", enchants="
			+ (enchants.isBlank() ? "none" : enchants);
	}
	
	private String getEnchantmentSummary(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return "";
		
		ArrayList<String> parts = new ArrayList<>();
		LinkedHashSet<String> seen = new LinkedHashSet<>();
		appendEnchantmentParts(parts, seen, stack
			.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
		appendEnchantmentParts(parts, seen, stack.getOrDefault(
			DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
		if(parts.isEmpty())
			return "";
		return String.join(", ", parts);
	}
	
	private static void appendEnchantmentParts(List<String> out,
		Set<String> seen, ItemEnchantments enchantments)
	{
		for(Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments
			.entrySet())
		{
			Holder<Enchantment> holder = entry.getKey();
			int level = entry.getIntValue();
			String key = holder.getRegisteredName() + "#" + level;
			if(!seen.add(key))
				continue;
			
			out.add(Enchantment.getFullname(holder, level).getString());
		}
	}
	
	private String formatStack(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return "empty";
		return stack.getHoverName().getString() + " x" + stack.getCount();
	}
	
	private String formatBlockPos(BlockPos pos)
	{
		if(pos == null)
			return "unknown";
		return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
	}
	
	private String formatVec(Vec3 pos)
	{
		if(pos == null)
			return "unknown";
		return String.format(Locale.ROOT, "%.1f, %.1f, %.1f", pos.x, pos.y,
			pos.z);
	}
	
	private String now()
	{
		return LocalDateTime.now().format(TIME_FORMAT);
	}
	
	private String safe(String s)
	{
		return s == null ? "" : s;
	}
	
	private String stripFormatting(String text)
	{
		if(text == null || text.isEmpty())
			return "";
		
		StringBuilder sb = new StringBuilder(text.length());
		for(int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			if(c == '\u00a7' && i + 1 < text.length())
			{
				i++;
				continue;
			}
			sb.append(c);
		}
		return sb.toString();
	}
	
	private String escapeJson(String text)
	{
		StringBuilder sb = new StringBuilder(text.length() + 16);
		for(int i = 0; i < text.length(); i++)
		{
			char c = text.charAt(i);
			switch(c)
			{
				case '\\' -> sb.append("\\\\");
				case '"' -> sb.append("\\\"");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default ->
				{
					if(c < 0x20)
						sb.append(String.format("\\u%04x", (int)c));
					else
						sb.append(c);
				}
			}
		}
		return sb.toString();
	}
}
