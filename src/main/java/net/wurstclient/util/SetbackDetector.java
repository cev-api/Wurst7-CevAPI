/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.util;

import java.util.ArrayList;
import java.util.Set;

import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.wurstclient.WurstClient;
import net.wurstclient.events.ConnectionPacketOutputListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.AnchorAuraHack;
import net.wurstclient.hacks.AntiEntityPushHack;
import net.wurstclient.hacks.AntiKnockbackHack;
import net.wurstclient.hacks.AntiWaterPushHack;
import net.wurstclient.hacks.BeaconExploitHack;
import net.wurstclient.hacks.BlinkHack;
import net.wurstclient.hacks.BoatFlyHack;
import net.wurstclient.hacks.BowAimbotHack;
import net.wurstclient.hacks.BunnyHopHack;
import net.wurstclient.hacks.ClickAuraHack;
import net.wurstclient.hacks.CreativeFlightHack;
import net.wurstclient.hacks.CriticalsHack;
import net.wurstclient.hacks.CrystalAuraHack;
import net.wurstclient.hacks.ExtraElytraHack;
import net.wurstclient.hacks.FastBreakHack;
import net.wurstclient.hacks.FastLadderHack;
import net.wurstclient.hacks.FastPlaceHack;
import net.wurstclient.hacks.FlightHack;
import net.wurstclient.hacks.FreecamHack;
import net.wurstclient.hacks.GlideHack;
import net.wurstclient.hacks.HighJumpHack;
import net.wurstclient.hacks.JesusHack;
import net.wurstclient.hacks.JetpackHack;
import net.wurstclient.hacks.KillauraHack;
import net.wurstclient.hacks.KillauraLegitHack;
import net.wurstclient.hacks.MultiAuraHack;
import net.wurstclient.hacks.MaceDmgHack;
import net.wurstclient.hacks.NoClipHack;
import net.wurstclient.hacks.NoFallHack;
import net.wurstclient.hacks.NoLevitationHack;
import net.wurstclient.hacks.NoSlowdownHack;
import net.wurstclient.hacks.NoWebHack;
import net.wurstclient.hacks.OutreachHack;
import net.wurstclient.hacks.ParkourHack;
import net.wurstclient.hacks.ReachHack;
import net.wurstclient.hacks.SafeWalkHack;
import net.wurstclient.hacks.ScaffoldWalkHack;
import net.wurstclient.hacks.SpeedHackHack;
import net.wurstclient.hacks.SpeedNukerHack;
import net.wurstclient.hacks.SpiderHack;
import net.wurstclient.hacks.StepHack;
import net.wurstclient.hacks.TimerHack;
import net.wurstclient.hacks.TpAuraHack;
import net.wurstclient.hacks.TriggerBotHack;
import net.wurstclient.hacks.XCarryHack;

public final class SetbackDetector implements PacketInputListener
{
	private static final long COOLDOWN_MS = 500L;
	private static final long ACTIVE_WINDOW_MS = 3000L;
	private long lastSetbackMs;
	
	private static final Set<Class<? extends Hack>> SETBACK_HACK_TYPES = Set.of(
		AnchorAuraHack.class, AntiEntityPushHack.class, AntiKnockbackHack.class,
		AntiWaterPushHack.class, BlinkHack.class, BoatFlyHack.class,
		BowAimbotHack.class, BunnyHopHack.class, ClickAuraHack.class,
		CreativeFlightHack.class, CriticalsHack.class, CrystalAuraHack.class,
		ExtraElytraHack.class, FastBreakHack.class, FastLadderHack.class,
		FastPlaceHack.class, FlightHack.class, FreecamHack.class,
		GlideHack.class, HighJumpHack.class, JesusHack.class, JetpackHack.class,
		KillauraHack.class, KillauraLegitHack.class, MaceDmgHack.class,
		MultiAuraHack.class, NoClipHack.class, NoFallHack.class,
		NoLevitationHack.class, NoSlowdownHack.class, NoWebHack.class,
		OutreachHack.class, ParkourHack.class, ReachHack.class,
		SafeWalkHack.class, ScaffoldWalkHack.class, SpeedHackHack.class,
		SpeedNukerHack.class, SpiderHack.class, StepHack.class, TimerHack.class,
		TpAuraHack.class, TriggerBotHack.class);
	
	private static final Set<Class<? extends Hack>> EXCLUDED_PACKET_HACK_TYPES =
		Set.of(BeaconExploitHack.class, XCarryHack.class);
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!WurstClient.INSTANCE.getHax().antiCheatDetectHack
			.isSetbackDetectionEnabled())
			return;
		
		if(!(event.getPacket() instanceof ClientboundPlayerPositionPacket))
			return;
		
		long now = System.currentTimeMillis();
		if(now - lastSetbackMs < COOLDOWN_MS)
			return;
		
		lastSetbackMs = now;
		Hack culprit = findMostRecentHack();
		if(culprit == null)
			return;
		
		culprit.setEnabled(false);
		ChatUtils.error("Setback detected. Disabled: " + culprit.getName());
	}
	
	private Hack findMostRecentHack()
	{
		ArrayList<Hack> candidates = new ArrayList<>();
		for(Hack hack : WurstClient.INSTANCE.getHax().getAllHax())
		{
			if(hack.isEnabled() && isSetbackHack(hack))
				candidates.add(hack);
		}
		
		return HackActivityTracker.getMostRecentActive(candidates,
			ACTIVE_WINDOW_MS);
	}
	
	private boolean isSetbackHack(Hack hack)
	{
		if(EXCLUDED_PACKET_HACK_TYPES.contains(hack.getClass()))
			return false;
		
		if(hack instanceof PacketOutputListener
			|| hack instanceof ConnectionPacketOutputListener)
			return true;
		
		return SETBACK_HACK_TYPES.contains(hack.getClass());
	}
}
