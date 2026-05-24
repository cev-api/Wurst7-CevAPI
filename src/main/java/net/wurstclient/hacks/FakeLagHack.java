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
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.world.entity.player.Player;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;

@SearchTags({"fake lag", "combat blink", "dynamic lag"})
public final class FakeLagHack extends Hack
	implements UpdateListener, PacketOutputListener
{
	private enum Mode
	{
		CONSTANT("Constant"),
		DYNAMIC("Dynamic");
		
		private final String name;
		
		private Mode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private final SliderSetting range = new SliderSetting("Range", 4, 1, 10,
		0.25, SliderSetting.ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting delay = new SliderSetting("Delay", 400, 0, 1000,
		25, SliderSetting.ValueDisplay.INTEGER.withSuffix(" ms"));
	private final SliderSetting recoilTime = new SliderSetting("Recoil time",
		250, 0, 1000, 25, SliderSetting.ValueDisplay.INTEGER.withSuffix(" ms"));
	private final EnumSetting<Mode> mode =
		new EnumSetting<>("Mode", Mode.values(), Mode.DYNAMIC);
	private final CheckboxSetting flushOnAttack =
		new CheckboxSetting("Flush on attack", true);
	
	private final ArrayDeque<Packet<?>> queue = new ArrayDeque<>();
	private long queueStartedAt;
	private long recoilUntil;
	private boolean flushing;
	
	public FakeLagHack()
	{
		super("FakeLag",
			"Holds back movement packets for short bursts, especially near enemies.",
			false);
		setCategory(Category.COMBAT);
		addSetting(range);
		addSetting(delay);
		addSetting(recoilTime);
		addSetting(mode);
		addSetting(flushOnAttack);
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
		EVENTS.add(PacketOutputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		flush();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null || MC.screen != null)
		{
			flush();
			return;
		}
		
		if(!queue.isEmpty()
			&& System.currentTimeMillis() - queueStartedAt >= delay.getValueI())
		{
			recoilUntil = System.currentTimeMillis() + recoilTime.getValueI();
			flush();
		}
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(flushing || MC.player == null || MC.level == null)
			return;
		
		Packet<?> packet = event.getPacket();
		if(flushOnAttack.isChecked()
			&& (packet instanceof ServerboundInteractPacket
				|| packet instanceof ServerboundSwingPacket))
		{
			recoilUntil = System.currentTimeMillis() + recoilTime.getValueI();
			flush();
			return;
		}
		
		if(!(packet instanceof ServerboundMovePlayerPacket))
			return;
		if(MC.screen != null || MC.player.isInWater()
			|| System.currentTimeMillis() < recoilUntil)
			return;
		if(mode.getSelected() == Mode.DYNAMIC && !isEnemyNearby())
			return;
		
		if(queue.isEmpty())
			queueStartedAt = System.currentTimeMillis();
		queue.addLast(packet);
		event.cancel();
	}
	
	private boolean isEnemyNearby()
	{
		double rangeSq = range.getValue() * range.getValue();
		for(Player player : MC.level.players())
		{
			if(player == MC.player || player.isSpectator() || !player.isAlive())
				continue;
			if(player.distanceToSqr(MC.player) <= rangeSq)
				return true;
		}
		return false;
	}
	
	private void flush()
	{
		if(queue.isEmpty())
			return;
		
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
		{
			queue.clear();
			return;
		}
		
		flushing = true;
		try
		{
			while(!queue.isEmpty())
				connection.send(queue.removeFirst());
		}finally
		{
			flushing = false;
		}
	}
}
