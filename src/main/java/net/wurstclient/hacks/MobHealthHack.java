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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.EntityHealthRenderer;

@SearchTags({"mob health", "health", "hearts"})
public final class MobHealthHack extends Hack implements GUIRenderListener
{
	private static final float HEARTS_Y_WITH_NAMETAG = -20F;
	private static final float HEARTS_Y_NO_NAMETAG = -10F;
	
	private final CheckboxSetting showAsNumber =
		new CheckboxSetting("Show as number",
			"Shows mob health as a colored number instead of hearts.", false);
	
	private final CheckboxSetting hostileOnly =
		new CheckboxSetting("Hostile mobs only",
			"Only shows health for hostile mobs (zombies, skeletons, etc.).",
			false);
	
	private final CheckboxSetting ignoreNpcs = new CheckboxSetting(
		"Ignore NPCs",
		"Ignores likely scripted server NPC mobs (for example no-AI showcase NPCs).",
		true);
	
	private final CheckboxSetting throughWalls = new CheckboxSetting(
		"Through walls",
		"Detects the looked-at mob through walls instead of requiring direct line of sight.",
		false);
	
	private final CheckboxSetting showNames = new CheckboxSetting("Show names",
		"When enabled, keeps the mob's name and adds health next to it.", true);
	
	public MobHealthHack()
	{
		super("MobHealth");
		setCategory(Category.RENDER);
		addSetting(showAsNumber);
		addSetting(hostileOnly);
		addSetting(ignoreNpcs);
		addSetting(throughWalls);
		addSetting(showNames);
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
	}
	
	public boolean shouldForceMobNametags(Mob mob)
	{
		return isEnabled() && shouldDisplayForMob(mob)
			&& (showNames.isChecked() || showAsNumber.isChecked());
	}
	
	public boolean shouldShowAsNumber()
	{
		return showAsNumber.isChecked();
	}
	
	public boolean shouldShowNames()
	{
		return showNames.isChecked();
	}
	
	public boolean shouldDisplayForMob(Mob mob)
	{
		if(mob == null)
			return false;
		
		if(isExcludedMob(mob))
			return false;
		
		if(getLookedAtMob() != mob)
			return false;
		
		if(hostileOnly.isChecked() && !(mob instanceof Enemy))
			return false;
		
		return true;
	}
	
	public Component getDisplayText(Mob mob)
	{
		if(showAsNumber.isChecked() && showNames.isChecked())
			return mob.getName().copy()
				.append(Component.literal(" ").append(getNumberText(mob)));
		
		if(showAsNumber.isChecked())
			return getNumberText(mob);
		
		return Component.empty();
	}
	
	private Component getNumberText(Mob mob)
	{
		float health = Math.max(0, mob.getHealth());
		int roundedHealth = Math.round(health);
		return Component.literal(Integer.toString(roundedHealth))
			.withStyle(getColor(roundedHealth));
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
		if(showAsNumber.isChecked())
			return;
		
		Mob mob = getLookedAtMob();
		if(mob == null)
			return;
		
		if(!shouldDisplayForMob(mob) || !mob.isAlive())
			return;
		
		float yOffset = shouldShowNames() || shouldShowAsNumber()
			? HEARTS_Y_WITH_NAMETAG : HEARTS_Y_NO_NAMETAG;
		EntityHealthRenderer.drawHeartsAtEntity(context, mob, partialTicks,
			yOffset);
	}
	
	private Mob getLookedAtMob()
	{
		if(!throughWalls.isChecked())
		{
			Entity target = MC.crosshairPickEntity;
			return target instanceof Mob mob ? mob : null;
		}
		
		if(MC.player == null || MC.level == null)
			return null;
		
		double range = MC.player.entityInteractionRange();
		Vec3 cameraPos = MC.player.getEyePosition(1.0F);
		Vec3 look = MC.player.getViewVector(1.0F);
		Vec3 end = cameraPos.add(look.scale(range));
		AABB searchBox = MC.player.getBoundingBox()
			.expandTowards(look.scale(range)).inflate(1.0);
		
		EntityHitResult hit = ProjectileUtil.getEntityHitResult(MC.player,
			cameraPos, end, searchBox,
			e -> e instanceof Mob && !isExcludedMob(e) && e.isAlive(),
			range * range);
		
		if(hit == null)
			return null;
		
		Entity entity = hit.getEntity();
		return entity instanceof Mob mob ? mob : null;
	}
	
	private boolean isExcludedMob(Entity entity)
	{
		if(entity instanceof ArmorStand)
			return true;
		
		return ignoreNpcs.isChecked() && isLikelyNpc(entity);
	}
	
	private boolean isLikelyNpc(Entity entity)
	{
		if(!(entity instanceof Mob mob))
			return false;
		
		// Common pattern for server-side scripted NPCs.
		return mob.isNoAi();
	}
}
