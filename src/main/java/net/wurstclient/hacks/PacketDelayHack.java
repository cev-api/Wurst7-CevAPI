/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"LagSwitch", "lag switch"})
public final class PacketDelayHack extends Hack implements UpdateListener,
	PacketInputListener, PacketOutputListener, GUIRenderListener
{
	private final CheckboxSetting delayS2c = new CheckboxSetting("Delay S2C",
		"Delays incoming server packets.", true);
	private final CheckboxSetting delayC2s = new CheckboxSetting("Delay C2S",
		"Delays outgoing client packets.", true);
	private final CheckboxSetting timeDelay = new CheckboxSetting("Time delay",
		"Release queued packets once the delay has passed.", false);
	private final SliderSetting delay = new SliderSetting("Delay",
		"How long to wait in ticks before releasing queued packets.", 0, 0, 200,
		1, ValueDisplay.INTEGER);
	private final CheckboxSetting showStatusText = new CheckboxSetting(
		"Show status text",
		"Shows queue/mode/delay info below the crosshair while PacketDelay is enabled.",
		true);
	
	private final ArrayDeque<PacketAndTime> s2cQueue = new ArrayDeque<>();
	private final ArrayDeque<PacketAndTime> c2sQueue = new ArrayDeque<>();
	private boolean flushingC2s;
	
	public PacketDelayHack()
	{
		super("PacketDelay");
		setCategory(Category.TOOLS);
		addSetting(delayS2c);
		addSetting(delayC2s);
		addSetting(timeDelay);
		addSetting(delay);
		addSetting(showStatusText);
	}
	
	@Override
	public String getRenderName()
	{
		int total = s2cQueue.size() + c2sQueue.size();
		return getName() + " [" + total + "]";
	}
	
	@Override
	protected void onEnable()
	{
		s2cQueue.clear();
		c2sQueue.clear();
		
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(GUIRenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(GUIRenderListener.class, this);
		
		flushQueues(true);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.level == null || MC.getConnection() == null)
		{
			setEnabled(false);
			return;
		}
		
		if(timeDelay.isChecked())
			flushQueues(false);
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!delayS2c.isChecked())
			return;
		
		long time = MC.level != null ? MC.level.getGameTime() : 0;
		s2cQueue.addLast(new PacketAndTime(event.getPacket(), time));
		event.cancel();
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(flushingC2s || !delayC2s.isChecked())
			return;
		
		long time = MC.level != null ? MC.level.getGameTime() : 0;
		c2sQueue.addLast(new PacketAndTime(event.getPacket(), time));
		event.cancel();
	}
	
	@Override
	public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
	{
		if(!showStatusText.isChecked())
			return;
		
		int s2c = s2cQueue.size();
		int c2s = c2sQueue.size();
		int total = s2c + c2s;
		
		ArrayList<String> parts = new ArrayList<>();
		parts.add("Packet Delay");
		parts.add("Queued: " + total + getQueueBreakdownText(s2c, c2s));
		String modeText = getModeText();
		if(modeText != null)
			parts.add("mode: " + modeText);
		String delayText = getDelayText();
		if(delayText != null)
			parts.add(delayText);
		String text = String.join(" | ", parts);
		
		Font font = MC.font;
		int x = context.guiWidth() / 2 - font.width(text) / 2;
		int y = context.guiHeight() / 2 + 12;
		context.text(font, text, x, y, 0xFFFFFFFF, true);
	}
	
	private String getModeText()
	{
		boolean s2c = delayS2c.isChecked();
		boolean c2s = delayC2s.isChecked();
		
		if(s2c && c2s)
			return "S2C+C2S";
		if(s2c)
			return "S2C";
		if(c2s)
			return "C2S";
		return null;
	}
	
	private String getDelayText()
	{
		if(!timeDelay.isChecked())
			return null;
		
		double seconds = getSecondsLeft();
		return String.format("Time Left: %.2fs", seconds);
	}
	
	private String getQueueBreakdownText(int s2c, int c2s)
	{
		ArrayList<String> details = new ArrayList<>(2);
		if(delayS2c.isChecked())
			details.add("S2C: " + s2c);
		if(delayC2s.isChecked())
			details.add("C2S: " + c2s);
		if(details.isEmpty())
			return "";
		
		return " (" + String.join(", ", details) + ")";
	}
	
	private double getSecondsLeft()
	{
		long now = MC.level != null ? MC.level.getGameTime() : 0;
		int delayTicks = delay.getValueI();
		
		double maxLeftTicks = 0;
		if(delayS2c.isChecked() && !s2cQueue.isEmpty())
		{
			PacketAndTime first = s2cQueue.peekFirst();
			maxLeftTicks =
				Math.max(maxLeftTicks, first.time + delayTicks - now);
		}
		
		if(delayC2s.isChecked() && !c2sQueue.isEmpty())
		{
			PacketAndTime first = c2sQueue.peekFirst();
			maxLeftTicks =
				Math.max(maxLeftTicks, first.time + delayTicks - now);
		}
		
		return Math.max(0, maxLeftTicks) / 20.0;
	}
	
	private void flushQueues(boolean forceAll)
	{
		if(forceAll || delay.getValueI() <= 0)
			forceAll = true;
		
		long now = MC.level != null ? MC.level.getGameTime() : 0;
		
		if(forceAll || shouldReleaseQueue(s2cQueue, now, delay.getValueI()))
		{
			while(!s2cQueue.isEmpty())
				applyPacket(s2cQueue.removeFirst().packet);
		}
		
		if(forceAll || shouldReleaseQueue(c2sQueue, now, delay.getValueI()))
		{
			sendQueuedPackets();
		}
	}
	
	private boolean shouldReleaseQueue(ArrayDeque<PacketAndTime> queue,
		long now, int delayTicks)
	{
		if(queue.isEmpty())
			return false;
		
		return queue.peekFirst().time <= now - delayTicks;
	}
	
	private void sendQueuedPackets()
	{
		if(c2sQueue.isEmpty())
			return;
		
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
		{
			c2sQueue.clear();
			return;
		}
		
		flushingC2s = true;
		try
		{
			while(!c2sQueue.isEmpty())
				connection.send(c2sQueue.removeFirst().packet);
			
		}finally
		{
			flushingC2s = false;
		}
	}
	
	private void applyPacket(Packet<?> packet)
	{
		ClientPacketListener connection = MC.getConnection();
		if(connection == null)
			return;
		
		@SuppressWarnings("unchecked")
		Packet<ClientPacketListener> typedPacket =
			(Packet<ClientPacketListener>)packet;
		typedPacket.handle(connection);
	}
	
	private static class PacketAndTime
	{
		private final Packet<?> packet;
		private final long time;
		
		private PacketAndTime(Packet<?> packet, long time)
		{
			this.packet = packet;
			this.time = time;
		}
	}
}
