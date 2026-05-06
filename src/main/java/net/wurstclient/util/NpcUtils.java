/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.Minecraft;

public final class NpcUtils
{
	private static final Pattern VALID_MC_USERNAME =
		Pattern.compile("^[A-Za-z0-9_]{3,16}$");
	private static final Pattern VALID_BEDROCK_USERNAME =
		Pattern.compile("^\\.[A-Za-z0-9_]{3,16}$");
	private static final Pattern NPC_STYLE_NAME =
		Pattern.compile("(?i)^npc[0-9a-f-]{6,}$");
	private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d{2,16}$");
	private static final Pattern DOT_DIGITS = Pattern.compile("^\\.\\d{1,6}$");
	
	private NpcUtils()
	{}
	
	public static boolean isLikelyNpcPlayer(Player player)
	{
		if(player == null)
			return true;
		
		return isLikelyNpc(player.getUUID(),
			player.getName() == null ? null : player.getName().getString());
	}
	
	public static boolean isLikelyNpc(UUID uuid, String rawName)
	{
		if(uuid == null)
			return true;
		
		String normalizedName = normalizeIdentityName(rawName);
		if(normalizedName == null || normalizedName.isEmpty())
			return true;
		if(!isValidPlayerName(normalizedName))
			return true;
		
		// Common lobby/bot name styles on some servers.
		if(DIGITS_ONLY.matcher(normalizedName).matches()
			|| DOT_DIGITS.matcher(normalizedName).matches())
			return true;
		
		// Many NPC plugins generate names like NPC7facdc7b-679c.
		if(NPC_STYLE_NAME.matcher(normalizedName).matches())
			return true;
		
		return !hasValidTabListIdentity(uuid, normalizedName);
	}
	
	private static String normalizeIdentityName(String rawName)
	{
		if(rawName == null)
			return null;
		
		String stripped = StringUtil.stripColor(rawName).trim();
		return stripped.isEmpty() ? null : stripped;
	}
	
	private static boolean isValidPlayerName(String normalizedName)
	{
		return VALID_MC_USERNAME.matcher(normalizedName).matches()
			|| VALID_BEDROCK_USERNAME.matcher(normalizedName).matches();
	}
	
	private static boolean hasValidTabListIdentity(UUID uuid, String entityName)
	{
		if(uuid == null)
			return false;
		
		Minecraft mc = Minecraft.getInstance();
		if(mc == null || mc.getConnection() == null)
			return true;
		
		var tabInfo = mc.getConnection().getPlayerInfo(uuid);
		if(tabInfo == null || tabInfo.getProfile() == null)
			return false;
		
		String tabName = tabInfo.getProfile().name();
		if(tabName == null)
			return false;
		
		String normalizedTabName = normalizeIdentityName(tabName);
		if(normalizedTabName == null)
			return false;
		
		String normalizedEntityName = normalizeIdentityName(entityName);
		if(normalizedEntityName == null)
			return false;
		
		return normalizedEntityName.equalsIgnoreCase(normalizedTabName);
	}
}
