/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;

@SearchTags({"damage detect", "damage source", "hit warning"})
public final class DamageDetectHack extends Hack implements UpdateListener
{
	private int lastHurtTime = 0;
	
	public DamageDetectHack()
	{
		super("DamageDetect");
		// No category -> Navigator-only
	}
	
	@Override
	protected void onEnable()
	{
		lastHurtTime = 0;
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		if(player == null)
			return;
		
		int hurtTime = player.hurtTime;
		if(hurtTime > lastHurtTime)
		{
			announceDamage(player.getLastDamageSource(), player);
		}
		lastHurtTime = hurtTime;
	}
	
	private void announceDamage(DamageSource source, LocalPlayer player)
	{
		String cause = formatDamageSource(source);
		player.displayClientMessage(Component.literal("DamageDetect: " + cause),
			false);
	}
	
	private String formatDamageSource(DamageSource source)
	{
		if(source == null)
			return "unknown cause";
		
		String msg = source.getMsgId();
		if(msg == null || msg.isEmpty())
			msg = "damage";
		
		Entity attacker = source.getEntity();
		Entity shooter = source.getDirectEntity();
		Entity actor = attacker != null ? attacker : shooter;
		
		if(actor != null)
			msg += " by " + actor.getName().getString() + " at "
				+ formatEntityLocation(actor);
		
		return msg;
	}
	
	private String formatEntityLocation(Entity entity)
	{
		if(entity == null)
			return "unknown location";
		
		int x = (int)Math.floor(entity.getX());
		int y = (int)Math.floor(entity.getY());
		int z = (int)Math.floor(entity.getZ());
		return String.format("(%d, %d, %d)", x, y, z);
	}
}
