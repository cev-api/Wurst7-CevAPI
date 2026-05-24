/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;

@SearchTags({"back track", "lag compensation", "packet backtrack"})
public final class BacktrackHack extends Hack
	implements UpdateListener, PacketInputListener, PlayerAttacksEntityListener
{
	private final SliderSetting range = new SliderSetting("Range", 3, 1, 6,
		0.25, SliderSetting.ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting delay = new SliderSetting("Delay", 125, 0, 1000,
		25, SliderSetting.ValueDisplay.INTEGER.withSuffix(" ms"));
	private final SliderSetting keepAlive =
		new SliderSetting("Last attack time", 1000, 100, 5000, 50,
			SliderSetting.ValueDisplay.INTEGER.withSuffix(" ms"));
	private final CheckboxSetting pauseOnHurt =
		new CheckboxSetting("Pause on hurt", false);
	
	private final ArrayDeque<QueuedPacket> queue = new ArrayDeque<>();
	private Entity target;
	private long lastAttackAt;
	private boolean flushing;
	
	public BacktrackHack()
	{
		super("Backtrack",
			"Briefly delays enemy movement packets after you attack, making the server-side position easier to hit.",
			false);
		setCategory(Category.COMBAT);
		addSetting(range);
		addSetting(delay);
		addSetting(keepAlive);
		addSetting(pauseOnHurt);
	}
	
	@Override
	public String getRenderName()
	{
		return queue.isEmpty() ? getName()
			: getName() + " [" + queue.size() + "]";
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(PlayerAttacksEntityListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		flushAll();
		target = null;
	}
	
	@Override
	public void onPlayerAttacksEntity(Entity target)
	{
		if(target instanceof LivingEntity && target != MC.player)
		{
			this.target = target;
			lastAttackAt = System.currentTimeMillis();
		}
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null || target == null
			|| !target.isAlive()
			|| System.currentTimeMillis() - lastAttackAt > keepAlive.getValueI()
			|| MC.player.distanceToSqr(target) > range.getValue()
				* range.getValue())
		{
			target = null;
			flushAll();
			return;
		}
		
		if(pauseOnHurt.isChecked() && target instanceof LivingEntity living
			&& living.hurtTime > 0)
		{
			flushAll();
			return;
		}
		
		flushReady();
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(flushing || target == null || delay.getValueI() <= 0)
			return;
		
		Packet<?> packet = event.getPacket();
		if(!looksLikeEntityMovement(packet))
			return;
		
		queue.addLast(new QueuedPacket(packet, System.currentTimeMillis()));
		event.cancel();
	}
	
	private boolean looksLikeEntityMovement(Packet<?> packet)
	{
		String name = packet.getClass().getSimpleName();
		return name.contains("MoveEntity") || name.contains("TeleportEntity")
			|| name.contains("SetEntityMotion")
			|| name.contains("EntityPosition")
			|| name.contains("EntityVelocity");
	}
	
	private void flushReady()
	{
		long cutoff = System.currentTimeMillis() - delay.getValueI();
		while(!queue.isEmpty() && queue.peekFirst().time <= cutoff)
			applyPacket(queue.removeFirst().packet);
	}
	
	private void flushAll()
	{
		while(!queue.isEmpty())
			applyPacket(queue.removeFirst().packet);
	}
	
	private void applyPacket(Packet<?> packet)
	{
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
			return;
		
		flushing = true;
		try
		{
			@SuppressWarnings("unchecked")
			Packet<ClientPacketListener> typed =
				(Packet<ClientPacketListener>)packet;
			typed.handle(connection);
		}finally
		{
			flushing = false;
		}
	}
	
	private record QueuedPacket(Packet<?> packet, long time)
	{}
}
