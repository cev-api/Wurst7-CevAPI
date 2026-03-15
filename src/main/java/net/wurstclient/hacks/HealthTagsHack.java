/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.StringUtil;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.EntityHealthRenderer;

@SearchTags({"health tags"})
public final class HealthTagsHack extends Hack implements GUIRenderListener
{
	private final CheckboxSetting heartsBelowName =
		new CheckboxSetting("Hearts below name",
			"Shows hearts on a second line below the nametag instead of"
				+ " appending a numeric value to the name.",
			false);
	
	private final CheckboxSetting ignoreNpcs = new CheckboxSetting(
		"Ignore NPCs",
		"Hides health tags/hearts for likely NPC players (tab-list mismatch, no tab entry, or NPC-style names).",
		true);
	
	private final java.util.Set<LivingEntity> entitiesToRender =
		java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
	private static final Pattern VALID_MC_USERNAME =
		Pattern.compile("^[A-Za-z0-9_]{3,16}$");
	private static final Pattern NPC_STYLE_NAME =
		Pattern.compile("(?i)^npc[0-9a-f-]{6,}$");
	
	public HealthTagsHack()
	{
		super("HealthTags");
		setCategory(Category.RENDER);
		addSetting(heartsBelowName);
		addSetting(ignoreNpcs);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(GUIRenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(GUIRenderListener.class, this);
		entitiesToRender.clear();
	}
	
	public Component addHealth(LivingEntity entity, MutableComponent nametag)
	{
		if(!isEnabled())
			return nametag;
		
		if(shouldSkipEntity(entity))
			return nametag;
		
		if(heartsBelowName.isChecked())
			return nametag;
		
		int health = (int)entity.getHealth();
		
		MutableComponent formattedHealth = Component.literal(" ")
			.append(Integer.toString(health)).withStyle(getColor(health));
		return nametag.append(formattedHealth);
	}
	
	public boolean shouldRenderHeartsBelowName()
	{
		return isEnabled() && heartsBelowName.isChecked();
	}
	
	public void markForHeartRender(LivingEntity entity)
	{
		if(shouldRenderHeartsBelowName() && !shouldSkipEntity(entity))
			entitiesToRender.add(entity);
	}
	
	private boolean shouldSkipEntity(LivingEntity entity)
	{
		if(entity instanceof ArmorStand && entity.isInvisible())
			return true;
		
		return ignoreNpcs.isChecked() && isLikelyNpcPlayer(entity);
	}
	
	private boolean isLikelyNpcPlayer(LivingEntity entity)
	{
		if(!(entity instanceof Player player))
			return false;
		
		String normalizedName = normalizeIdentityName(
			player.getName() == null ? null : player.getName().getString());
		if(normalizedName == null
			|| NPC_STYLE_NAME.matcher(normalizedName).matches())
			return true;
		if(!VALID_MC_USERNAME.matcher(normalizedName).matches())
			return true;
		
		UUID uuid = player.getUUID();
		if(uuid == null)
			return true;
		
		if(MC == null || MC.getConnection() == null)
			return false;
		
		var tabInfo = MC.getConnection().getPlayerInfo(uuid);
		if(tabInfo == null || tabInfo.getProfile() == null)
			return true;
		
		String tabName = normalizeIdentityName(tabInfo.getProfile().name());
		if(tabName == null)
			return true;
		
		return !tabName.equalsIgnoreCase(normalizedName);
	}
	
	private String normalizeIdentityName(String rawName)
	{
		if(rawName == null)
			return null;
		
		String stripped = StringUtil.stripColor(rawName).trim();
		return stripped.isEmpty() ? null : stripped;
	}
	
	private ChatFormatting getColor(int health)
	{
		if(health <= 5)
			return ChatFormatting.DARK_RED;
		
		if(health <= 10)
			return ChatFormatting.GOLD;
		
		if(health <= 15)
			return ChatFormatting.YELLOW;
		
		return ChatFormatting.GREEN;
	}
	
	@Override
	public void onRenderGUI(GuiGraphics context, float partialTicks)
	{
		for(LivingEntity entity : entitiesToRender)
		{
			if(entity == null || !entity.isAlive())
				continue;
			
			EntityHealthRenderer.drawHeartsAtEntity(context, entity,
				partialTicks, -10F);
		}
		
		entitiesToRender.clear();
	}
	
	// See EntityRendererMixin
}
