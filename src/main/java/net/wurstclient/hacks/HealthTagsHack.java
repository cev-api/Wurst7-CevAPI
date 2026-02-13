/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.LivingEntity;
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
	
	private final java.util.Set<LivingEntity> entitiesToRender =
		java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
	
	public HealthTagsHack()
	{
		super("HealthTags");
		setCategory(Category.RENDER);
		addSetting(heartsBelowName);
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
		if(shouldRenderHeartsBelowName())
			entitiesToRender.add(entity);
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
