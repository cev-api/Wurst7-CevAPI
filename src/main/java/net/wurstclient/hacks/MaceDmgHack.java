/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.ConnectionPacketOutputListener;
import net.wurstclient.events.ConnectionPacketOutputListener.ConnectionPacketOutputEvent;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.PacketUtils;

@SearchTags({"mace dmg", "MaceDamage", "mace damage"})
public final class MaceDmgHack extends Hack
	implements PlayerAttacksEntityListener, PacketInputListener, UpdateListener,
	PacketOutputListener, ConnectionPacketOutputListener
{
	private static final double DEFAULT_HEIGHT = 22.0;
	private static final double MIN_FALL = 1.6;
	private static final double SCAN_STEP = 0.25;
	private static final int CONFIRM_TIMEOUT_TICKS = 20;
	private static final int RECOVERY_TICKS = 2;
	
	private final SliderSetting height = new SliderSetting("Height",
		"How high to fake before slamming. Height determines the damage boost.",
		DEFAULT_HEIGHT, 1.6, 50, 0.1, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting caveMode = new CheckboxSetting("Cave mode",
		"Scans for an air gap above you to fall through, so smashes work under"
			+ " a ceiling. Fully sealed spaces still can't be smashed.",
		true);
	
	private volatile SmashState smashState = SmashState.IDLE;
	private volatile LocalPlayer debtPlayer;
	private volatile int pendingTargetId = -1;
	private volatile int confirmTicks;
	private volatile int recoveryTicks;
	private volatile boolean safetyListenersRegistered;
	
	public MaceDmgHack()
	{
		super("MaceDMG");
		setCategory(Category.COMBAT);
		addSetting(height);
		addSetting(caveMode);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PlayerAttacksEntityListener.class, this);
		registerSafetyListeners();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		if(!hasFallDebt())
			unregisterSafetyListeners();
	}
	
	private void registerSafetyListeners()
	{
		if(safetyListenersRegistered)
			return;
		
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(ConnectionPacketOutputListener.class, this);
		safetyListenersRegistered = true;
	}
	
	private void unregisterSafetyListeners()
	{
		if(!safetyListenersRegistered)
			return;
		
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(ConnectionPacketOutputListener.class, this);
		safetyListenersRegistered = false;
	}
	
	@Override
	public void onPlayerAttacksEntity(Entity target)
	{
		WURST.getHax().attributeSwapHack.prepareForAttack(target);
		doSmash(target);
	}
	
	private void doSmash(Entity target)
	{
		if(!isEnabled() || MC.player == null || MC.player.connection == null)
			return;
		if(!MC.player.getMainHandItem().is(Items.MACE))
			return;
		if(!canAttemptSmash(target))
			return;
		
		if(smashState == SmashState.FAILED_UNSAFE)
		{
			// Reuse the existing server-side fall distance. Do not stack
			// another
			// spoof; the attack that follows this event can still clear the
			// debt.
			smashState = SmashState.WAITING_FOR_MACE_CONFIRM;
			pendingTargetId = target.getId();
			confirmTicks = CONFIRM_TIMEOUT_TICKS;
			return;
		}
		
		if(hasFallDebt())
			return;
		
		debtPlayer = MC.player;
		pendingTargetId = target.getId();
		smashState = SmashState.SPOOF_SENT;
		// See ServerGamePacketListenerImpl.handleMovePlayer()
		// for why it's using these numbers.
		// Also, let me know if you find a way to bypass that check.
		for(int i = 0; i < 4; i++)
			sendFakeY(0);
		sendFakeY(findFallOffset());
		sendFakeY(0);
		smashState = SmashState.WAITING_FOR_MACE_CONFIRM;
		confirmTicks = CONFIRM_TIMEOUT_TICKS;
	}
	
	private boolean canAttemptSmash(Entity target)
	{
		return target instanceof LivingEntity living && living.isAlive()
			&& !living.isRemoved();
	}
	
	public boolean hasFallDebt()
	{
		if(smashState == SmashState.IDLE)
			return false;
		
		if(MC.player == null || MC.player != debtPlayer)
		{
			clearFallDebt();
			return false;
		}
		
		return smashState != SmashState.IDLE;
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		event.setPacket(withFallDebtGuard(event.getPacket()));
	}
	
	@Override
	public void onSentConnectionPacket(ConnectionPacketOutputEvent event)
	{
		// Covers direct Connection sends and is the final guard after all
		// normal
		// packet-output listeners have run.
		event.setPacket(withFallDebtGuard(event.getPacket()));
	}
	
	private Packet<?> withFallDebtGuard(Packet<?> packet)
	{
		if(!hasFallDebt())
			return packet;
		if(!(packet instanceof ServerboundMovePlayerPacket move)
			|| !move.isOnGround())
			return packet;
		
		return PacketUtils.modifyOnGround(move, false);
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if((smashState != SmashState.WAITING_FOR_MACE_CONFIRM
			&& smashState != SmashState.FAILED_UNSAFE) || debtPlayer == null)
			return;
		if(!(event.getPacket() instanceof ClientboundDamageEventPacket damage))
			return;
		if(damage.entityId() != pendingTargetId
			|| damage.sourceCauseId() != debtPlayer.getId())
			return;
		
		boolean confirmedSmash = damage.sourceType().is(DamageTypes.MACE_SMASH);
		boolean confirmedRejectedSpoof =
			damage.sourceType().is(DamageTypes.PLAYER_ATTACK);
		if(!confirmedSmash && !confirmedRejectedSpoof)
			return;
			
		// MACE_SMASH proves that postHurtEnemy() reset server fall distance.
		// PLAYER_ATTACK from the same pending mace attack proves that the
		// server
		// evaluated canSmashAttack() as false, so dangerous fake fall distance
		// was not retained. Either result safely ends this attempt.
		smashState = SmashState.CONFIRMED_SAFE;
		confirmTicks = 0;
		recoveryTicks = RECOVERY_TICKS;
	}
	
	@Override
	public void onUpdate()
	{
		if(smashState == SmashState.WAITING_FOR_MACE_CONFIRM
			&& --confirmTicks <= 0)
			smashState = SmashState.FAILED_UNSAFE;
		
		if(smashState == SmashState.CONFIRMED_SAFE)
			smashState = SmashState.RECOVERING;
		else if(smashState == SmashState.RECOVERING && --recoveryTicks <= 0)
			clearFallDebt();
	}
	
	private void clearFallDebt()
	{
		smashState = SmashState.IDLE;
		debtPlayer = null;
		pendingTargetId = -1;
		confirmTicks = 0;
		recoveryTicks = 0;
		
		if(!isEnabled())
			unregisterSafetyListeners();
	}
	
	private double findFallOffset()
	{
		double cap = height.getValue();
		if(!caveMode.isChecked() || MC.level == null)
			return cap;
		
		for(double off = cap; off >= MIN_FALL; off -= SCAN_STEP)
		{
			AABB box = MC.player.getBoundingBox().move(0, off, 0);
			if(MC.level.noCollision(MC.player, box))
				return off;
		}
		
		return cap;
	}
	
	private void sendFakeY(double offset)
	{
		MC.player.connection
			.send(new Pos(MC.player.getX(), MC.player.getY() + offset,
				MC.player.getZ(), false, MC.player.horizontalCollision));
	}
	
	private enum SmashState
	{
		IDLE,
		SPOOF_SENT,
		WAITING_FOR_MACE_CONFIRM,
		CONFIRMED_SAFE,
		FAILED_UNSAFE,
		RECOVERING
	}
}
