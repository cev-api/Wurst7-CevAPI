/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.item.ItemStack;
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
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SettingGroup;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.PacketUtils;
import net.wurstclient.util.RotationUtils;
import net.wurstclient.util.text.WText;

@SearchTags({"mace dmg", "MaceDamage", "mace damage"})
public final class MaceDmgHack extends Hack
	implements PlayerAttacksEntityListener, PacketInputListener, UpdateListener,
	PacketOutputListener, ConnectionPacketOutputListener
{
	private static final double DEFAULT_HEIGHT = 22.0;
	private static final double MIN_FALL = 1.6;
	private static final double SCAN_STEP = 0.25;
	private static final int CONFIRM_TIMEOUT_TICKS = 20;
	private static final int TOTEM_POP_FALLBACK_TICKS = 2;
	private static final int SAFE_RECOVERY_TICKS = 2;
	private static final int UNSAFE_RECOVERY_TICKS = 6;
	private static final int TOTEM_BYPASS_GRACE_TICKS = 40;
	private static final double FABRIC_MAX_HEIGHT = 22.3;
	private static final double PAPER_MAX_HEIGHT = 50.0;
	
	private enum ServerType
	{
		PAPER("Paper"),
		FABRIC("Fabric");
		
		private final String displayName;
		
		ServerType(String displayName)
		{
			this.displayName = displayName;
		}
		
		@Override
		public String toString()
		{
			return displayName;
		}
	}
	
	private final EnumSetting<ServerType> serverType = new EnumSetting<>(
		"Server type",
		"Gameplay. The type of server you are playing on. Determines how the totem\n"
			+ "bypass cycles fall heights.\n"
			+ "Paper: Can spoof heights up to 50 blocks (no vanilla cap).\n"
			+ "Fabric: Height limited to 22 blocks (vanilla teleport limit).",
		ServerType.values(), ServerType.FABRIC)
	{
		@Override
		public void setSelected(ServerType selected)
		{
			super.setSelected(selected);
			onServerTypeChanged();
		}
	};
	
	private final CheckboxSetting autoOptimize = new CheckboxSetting(
		"Auto-optimize",
		"Gameplay. Automatically adjusts fall height and increase values for the\n"
			+ "selected server type and keeps the height slider capped for that\n"
			+ "server type.",
		true)
	{
		@Override
		public void update()
		{
			if(isChecked() && !autoOptimizeWasChecked)
				applyAutoOptimize();
			autoOptimizeWasChecked = isChecked();
		}
	};
	
	private final SliderSetting height = new SliderSetting("Height",
		"How high to fake before slamming. Height determines the damage boost.",
		DEFAULT_HEIGHT, 1.6, PAPER_MAX_HEIGHT, 0.1, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting attackCap = new CheckboxSetting(
		"6-block attack cap",
		"Blocks all entity attacks started while MaceDMG is enabled when the target"
			+ " is more than 6 blocks horizontally or vertically away.",
		true);
	
	private final CheckboxSetting totemBypass = new CheckboxSetting(
		"Totem bypass",
		"Paper only. With MultiAura, sends a rising-damage mace burst against totem users.",
		true)
	{
		@Override
		public void update()
		{
			if(!isChecked())
				totemBypassSteps.clear();
		}
	};
	
	private final SliderSetting heightIncrease = new SliderSetting(
		"Height increase",
		"Gameplay. How much to increase the fall height on each consecutive hit\n"
			+ "against a totem-holding player. Damage must increase each hit.",
		7, 1, 20, 0.5, ValueDisplay.DECIMAL);
	
	private final SliderSetting attackCount = new SliderSetting("Attack count",
		"Gameplay. Number of attacks to send in one totem-bypass burst.", 3, 2,
		5, 1, ValueDisplay.INTEGER);
	
	private final CheckboxSetting caveMode = new CheckboxSetting("Cave mode",
		"Gameplay. Scans for an air gap above you to fall through, so smashes"
			+ " work under a ceiling. Fully sealed spaces still can't be smashed.",
		true);
	
	private final CheckboxSetting smashSparkles = new CheckboxSetting(
		"Smash sparkles",
		"Cosmetic only. Show a full FunCreepers-style party when a mace smash attack successfully lands.",
		true);
	
	private final SliderSetting excitement = new SliderSetting("Excitement",
		"Cosmetic only. Controls how intense and chaotic the smash celebration gets.",
		100, 25, 300, 25, ValueDisplay.PERCENTAGE);
	
	private final SliderSetting fireworks = new SliderSetting("Fireworks",
		"Cosmetic only. How many fireworks sparks burst out on a successful smash.",
		35, 5, 200, 5, ValueDisplay.INTEGER);
	
	private final SliderSetting confetti = new SliderSetting("Confetti",
		"Cosmetic only. How much confetti-like celebration pops out on a successful smash.",
		60, 10, 300, 10, ValueDisplay.INTEGER);
	
	private final SliderSetting sparkles = new SliderSetting("Sparkles",
		"Cosmetic only. How many colorful sparkles get sprayed around on a successful smash.",
		90, 20, 400, 10, ValueDisplay.INTEGER);
	
	private final ColorSetting sparkleColor = new ColorSetting("Sparkle color",
		"Cosmetic only. Color of the smash sparkles.", new Color(0xFF55FF));
	
	private final SettingGroup cosmeticGroup =
		new SettingGroup("Cosmetic effects",
			WText.literal(
				"Visual-only celebration settings for successful smashes."),
			false, true).addChildren(smashSparkles, excitement, fireworks,
				confetti, sparkles, sparkleColor);
	
	private volatile SmashState smashState = SmashState.IDLE;
	private volatile LocalPlayer debtPlayer;
	private volatile int pendingTargetId = -1;
	private volatile int confirmTicks;
	private volatile int recoveryTicks;
	private volatile int recentTotemPopEntityId = -1;
	private volatile int recentTotemPopTicks;
	private volatile boolean safetyListenersRegistered;
	private boolean autoOptimizeWasChecked;
	private final Map<UUID, TotemBypassState> totemBypassSteps =
		new HashMap<>();
	private final ArrayDeque<Integer> queuedTargetIds = new ArrayDeque<>();
	private final RandomSource random = RandomSource.create();
	private volatile int queuedPrepTargetId = -1;
	private volatile int queuedPrepTicks;
	private volatile int queuedPreviousSlot = -1;
	private volatile boolean queuedAttackInProgress;
	
	public MaceDmgHack()
	{
		super("MaceDMG");
		setCategory(Category.COMBAT);
		addSetting(serverType);
		addSetting(height);
		addSetting(attackCap);
		addSetting(totemBypass);
		addSetting(autoOptimize);
		addSetting(heightIncrease);
		addSetting(attackCount);
		addSetting(caveMode);
		addSetting(cosmeticGroup);
		onServerTypeChanged();
	}
	
	@Override
	public String getRenderName()
	{
		if(!isEnabled())
			return getName();
		
		StringBuilder sb = new StringBuilder(getName());
		sb.append(" [").append(serverType.getSelected().toString());
		
		double base = height.getValue();
		boolean fabric = serverType.getSelected() == ServerType.FABRIC;
		
		if(fabric)
			return sb.append(" - ").append(String.format("%.1f", base))
				.append("]").toString();
		
		if(!totemBypass.isChecked())
			return sb.append(" ").append(String.format("%.0f", base))
				.append("]").toString();
		
		double inc = heightIncrease.getValue();
		int count = attackCount.getValueI();
		
		for(int i = 0; i < count; i++)
		{
			double h = base + i * inc;
			sb.append(i == 0 ? " " : "\u2192").append(String.format("%.0f", h));
		}
		
		sb.append(" | +").append(String.format("%.0f", inc)).append("]");
		return sb.toString();
	}
	
	@Override
	protected void onEnable()
	{
		autoOptimizeWasChecked = false;
		onServerTypeChanged();
		EVENTS.add(PlayerAttacksEntityListener.class, this);
		registerSafetyListeners();
	}
	
	@Override
	protected void onDisable()
	{
		autoOptimizeWasChecked = false;
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
		totemBypassSteps.clear();
		clearQueuedTargets();
		restoreQueuedSlot();
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
		if(queuedAttackInProgress)
			return;
		
		WURST.getHax().attributeSwapHack.prepareForAttack(target);
		if(!beginSmashAttempt(target))
			return;
		
		sendSmashSequence(findFallOffset(target));
		smashState = SmashState.WAITING_FOR_MACE_CONFIRM;
		confirmTicks = CONFIRM_TIMEOUT_TICKS;
	}
	
	private boolean beginSmashAttempt(Entity target)
	{
		if(!isEnabled() || MC.player == null || MC.player.connection == null)
			return false;
		if(!MC.player.getMainHandItem().is(Items.MACE))
			return false;
		if(!canAttemptSmash(target))
			return false;
		
		if(smashState == SmashState.FAILED_UNSAFE)
			clearFallDebt();
		
		if(hasFallDebt())
			return false;
		
		debtPlayer = MC.player;
		pendingTargetId = target.getId();
		smashState = SmashState.SPOOF_SENT;
		return true;
	}
	
	private boolean canAttemptSmash(Entity target)
	{
		return target instanceof LivingEntity living && living.isAlive()
			&& !living.isRemoved();
	}
	
	public boolean shouldBlockAttack(Entity target)
	{
		if(!isEnabled() || !attackCap.isChecked() || MC.player == null
			|| target == null)
			return false;
		
		AABB box = target.getBoundingBox();
		double x = Math.clamp(MC.player.getX(), box.minX, box.maxX);
		double z = Math.clamp(MC.player.getZ(), box.minZ, box.maxZ);
		double dx = MC.player.getX() - x;
		double dz = MC.player.getZ() - z;
		double verticalDistance = Math.abs(MC.player.getY() - target.getY());
		return dx * dx + dz * dz > 36.0 || verticalDistance > 6.0;
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
	
	public boolean queueTargets(List<Entity> targets)
	{
		if(!isAuraSupportReady() || targets == null || targets.isEmpty())
			return false;
		
		ArrayList<Integer> validIds = new ArrayList<>();
		for(Entity target : targets)
		{
			if(canAttemptSmash(target))
				validIds.add(target.getId());
		}
		
		if(validIds.isEmpty())
			return false;
		
		queuedTargetIds.clear();
		for(int id : validIds)
		{
			if(id != pendingTargetId && id != queuedPrepTargetId)
				queuedTargetIds.addLast(id);
		}
		
		if(queuedTargetIds.isEmpty())
			return false;
		
		if(queuedPrepTargetId == -1 && !hasFallDebt())
			startNextQueuedTarget();
		
		return true;
	}
	
	public boolean isAuraSupportReady()
	{
		if(!isEnabled() || MC.player == null)
			return false;
		
		if(MC.player.getMainHandItem().is(Items.MACE))
			return true;
		
		AttributeSwapHack attributeSwap = WURST.getHax().attributeSwapHack;
		if(attributeSwap == null || !attributeSwap.isEnabled())
			return false;
		
		int reservedSlot = attributeSwap.getTargetHotbarSlot();
		if(reservedSlot < 0 || reservedSlot > 8)
			return false;
		
		return MC.player.getInventory().getItem(reservedSlot).is(Items.MACE);
	}
	
	public boolean shouldControlAuraAttacks()
	{
		return isAuraSupportReady() && totemBypass.isChecked();
	}
	
	public boolean isExecutingAuraBurst()
	{
		return queuedAttackInProgress;
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
		if(event.getPacket() instanceof ClientboundEntityEventPacket entityEvent
			&& entityEvent.getEventId() == 35 && MC.level != null
			&& entityEvent.getEntity(MC.level) instanceof Player poppedPlayer)
		{
			recentTotemPopEntityId = poppedPlayer.getId();
			recentTotemPopTicks = 2;
			armTotemBypassBurst(poppedPlayer);
			if(smashState == SmashState.WAITING_FOR_MACE_CONFIRM
				&& poppedPlayer.getId() == pendingTargetId)
			{
				// Totem-pop shows the burst resolved, but not that the server
				// safely cleared our fake fall state. Cut the wait short
				// without
				// releasing fall debt immediately.
				confirmTicks = Math.min(confirmTicks, TOTEM_POP_FALLBACK_TICKS);
			}
		}
		
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
		confirmCurrentSmash(confirmedSmash);
	}
	
	private void confirmCurrentSmash(boolean spawnSparkles)
	{
		if(spawnSparkles && smashSparkles.isChecked() && MC.level != null
			&& pendingTargetId != -1)
		{
			int targetId = pendingTargetId;
			if(!MC.isSameThread())
				MC.execute(() -> spawnSmashPartyEffects(targetId));
			else
				spawnSmashPartyEffects(targetId);
		}
		
		smashState = SmashState.CONFIRMED_SAFE;
		confirmTicks = 0;
		recoveryTicks = SAFE_RECOVERY_TICKS;
	}
	
	@Override
	public void onUpdate()
	{
		if(smashState == SmashState.WAITING_FOR_MACE_CONFIRM
			&& --confirmTicks <= 0)
		{
			smashState = SmashState.FAILED_UNSAFE;
			recoveryTicks = UNSAFE_RECOVERY_TICKS;
		}
		
		if(recentTotemPopTicks > 0 && --recentTotemPopTicks <= 0)
			recentTotemPopEntityId = -1;
		
		if(smashState == SmashState.CONFIRMED_SAFE)
			smashState = SmashState.RECOVERING;
		else if(smashState == SmashState.FAILED_UNSAFE && --recoveryTicks <= 0)
			clearFallDebt();
		else if(smashState == SmashState.RECOVERING && --recoveryTicks <= 0)
			clearFallDebt();
		
		processQueuedTargets();
		pruneTotemBypassSteps();
	}
	
	private void clearFallDebt()
	{
		smashState = SmashState.IDLE;
		debtPlayer = null;
		pendingTargetId = -1;
		confirmTicks = 0;
		recoveryTicks = 0;
		restoreQueuedSlot();
		
		if(!isEnabled())
			unregisterSafetyListeners();
	}
	
	private void processQueuedTargets()
	{
		if(!isEnabled() || MC.player == null || MC.level == null)
			return;
		
		if(queuedPrepTargetId != -1)
		{
			Entity target = MC.level.getEntity(queuedPrepTargetId);
			if(!canAttemptSmash(target))
			{
				queuedPrepTargetId = -1;
				queuedPrepTicks = 0;
			}else if(queuedPrepTicks-- <= 0)
				executeQueuedTarget(target);
			
			return;
		}
		
		if(hasFallDebt())
			return;
		
		startNextQueuedTarget();
	}
	
	private void startNextQueuedTarget()
	{
		if(MC.level == null)
			return;
		
		while(!queuedTargetIds.isEmpty())
		{
			int targetId = queuedTargetIds.removeFirst();
			Entity target = MC.level.getEntity(targetId);
			if(!canAttemptSmash(target))
				continue;
			
			RotationUtils
				.getNeededRotations(target.getBoundingBox().getCenter())
				.sendPlayerLookPacket();
			queuedPrepTargetId = targetId;
			queuedPrepTicks = 1;
			return;
		}
	}
	
	private void executeQueuedTarget(Entity target)
	{
		queuedPrepTargetId = -1;
		queuedPrepTicks = 0;
		if(target == null || MC.gameMode == null)
			return;
		
		int maceSlot = findMaceSlot();
		if(maceSlot < 0)
			return;
		
		queuedAttackInProgress = true;
		try
		{
			selectQueuedMaceSlot(maceSlot);
			boolean burstSent = performQueuedSmashBurst(target);
			if(burstSent)
				restoreQueuedSlot();
		}finally
		{
			if(queuedPreviousSlot != -1)
				restoreQueuedSlot();
			queuedAttackInProgress = false;
		}
	}
	
	private int findMaceSlot()
	{
		for(int i = 0; i < 9; i++)
		{
			ItemStack stack = MC.player.getInventory().getItem(i);
			if(stack.is(Items.MACE))
				return i;
		}
		
		return -1;
	}
	
	private void selectQueuedMaceSlot(int slot)
	{
		if(slot < 0 || slot >= 9)
			return;
		
		int currentSlot = MC.player.getInventory().getSelectedSlot();
		if(currentSlot == slot)
			return;
		
		if(queuedPreviousSlot == -1)
			queuedPreviousSlot = currentSlot;
		
		MC.player.getInventory().setSelectedSlot(slot);
		if(MC.player.connection != null)
			MC.player.connection
				.send(new ServerboundSetCarriedItemPacket(slot));
	}
	
	private void restoreQueuedSlot()
	{
		if(MC.player == null || queuedPreviousSlot < 0
			|| queuedPreviousSlot > 8)
		{
			queuedPreviousSlot = -1;
			return;
		}
		
		if(MC.player.getInventory().getSelectedSlot() != queuedPreviousSlot)
		{
			MC.player.getInventory().setSelectedSlot(queuedPreviousSlot);
			if(MC.player.connection != null)
			{
				MC.player.connection.send(
					new ServerboundSetCarriedItemPacket(queuedPreviousSlot));
			}
		}
		
		queuedPreviousSlot = -1;
	}
	
	private void clearQueuedTargets()
	{
		queuedTargetIds.clear();
		queuedPrepTargetId = -1;
		queuedPrepTicks = 0;
		queuedAttackInProgress = false;
	}
	
	private boolean performQueuedSmashBurst(Entity target)
	{
		if(target == null || MC.gameMode == null)
			return false;
		if(shouldBlockAttack(target))
			return false;
		List<Double> offsets = getBurstOffsets(target);
		if(offsets.isEmpty() || !beginSmashAttempt(target))
			return false;
		
		for(double offset : offsets)
		{
			sendSmashSequence(offset);
			MC.gameMode.attack(MC.player, target);
			MC.player.swing(InteractionHand.MAIN_HAND);
		}
		
		smashState = SmashState.WAITING_FOR_MACE_CONFIRM;
		confirmTicks = CONFIRM_TIMEOUT_TICKS;
		return true;
	}
	
	private double findFallOffset(Entity target)
	{
		return findSafeFallOffset(height.getValue());
	}
	
	private List<Double> getBurstOffsets(Entity target)
	{
		ArrayList<Double> offsets = new ArrayList<>();
		boolean useBypassBurst = shouldUseTotemBypassBurst(target);
		if(!useBypassBurst)
		{
			double singleOffset = findFallOffset(target);
			if(singleOffset >= MIN_FALL)
				offsets.add(singleOffset);
			return offsets;
		}
		
		double base = height.getValue();
		double increase = heightIncrease.getValue();
		double previousOffset = MIN_FALL - 1;
		
		for(int i = 0; i < attackCount.getValueI(); i++)
		{
			double requestedOffset = base + i * increase;
			double resolvedOffset = findSafeFallOffset(requestedOffset);
			if(resolvedOffset < MIN_FALL)
				continue;
			if(resolvedOffset <= previousOffset + 0.05)
				continue;
			
			offsets.add(resolvedOffset);
			previousOffset = resolvedOffset;
		}
		
		if(offsets.isEmpty())
		{
			double singleOffset = findFallOffset(target);
			if(singleOffset >= MIN_FALL)
				offsets.add(singleOffset);
		}
		
		if(useBypassBurst && target instanceof Player player)
			consumeTotemBypassBurst(player);
		
		return offsets;
	}
	
	private boolean shouldUseTotemBypassBurst(Entity target)
	{
		if(!totemBypass.isChecked() || !(target instanceof Player player))
			return false;
		
		TotemBypassState state = totemBypassSteps.get(player.getUUID());
		return state != null && state.graceTicks > 0 && state.burstReady;
	}
	
	private double findSafeFallOffset(double desiredOffset)
	{
		double clampedDesired = clampOffsetForServer(desiredOffset);
		if(!caveMode.isChecked() || MC.level == null)
			return clampedDesired;
		
		for(double off = clampedDesired; off >= MIN_FALL; off -= SCAN_STEP)
		{
			AABB box = MC.player.getBoundingBox().move(0, off, 0);
			if(MC.level.noCollision(MC.player, box))
				return off;
		}
		
		return MIN_FALL;
	}
	
	private double clampOffsetForServer(double offset)
	{
		return Math.min(offset, getServerTypeMaxHeight());
	}
	
	private void sendSmashSequence(double offset)
	{
		for(int i = 0; i < 4; i++)
			sendFakeY(0);
		sendFakeY(offset);
		sendFakeY(0);
	}
	
	private boolean hasTotem(Player player)
	{
		return player.getMainHandItem().is(Items.TOTEM_OF_UNDYING)
			|| player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
	}
	
	private void pruneTotemBypassSteps()
	{
		if(!totemBypass.isChecked())
		{
			totemBypassSteps.clear();
			return;
		}
		
		if(totemBypassSteps.isEmpty() || MC.level == null)
			return;
		
		totemBypassSteps.entrySet().removeIf(entry -> {
			Player player = MC.level.getPlayerByUUID(entry.getKey());
			if(player == null || !player.isAlive())
				return true;
			
			TotemBypassState state = entry.getValue();
			if(hasTotem(player))
			{
				state.graceTicks = TOTEM_BYPASS_GRACE_TICKS;
				return false;
			}
			
			return --state.graceTicks <= 0;
		});
	}
	
	private TotemBypassState refreshTotemBypassState(Player player)
	{
		if(!totemBypass.isChecked())
			return new TotemBypassState();
		
		TotemBypassState state = totemBypassSteps
			.computeIfAbsent(player.getUUID(), id -> new TotemBypassState());
		state.graceTicks = TOTEM_BYPASS_GRACE_TICKS;
		return state;
	}
	
	private void armTotemBypassBurst(Player player)
	{
		if(!totemBypass.isChecked())
			return;
		
		TotemBypassState state = refreshTotemBypassState(player);
		state.burstReady = true;
	}
	
	private void consumeTotemBypassBurst(Player player)
	{
		if(!totemBypass.isChecked())
			return;
		
		TotemBypassState state = totemBypassSteps.get(player.getUUID());
		if(state == null)
			return;
		
		state.burstReady = false;
	}
	
	private void applyAutoOptimize()
	{
		if(!autoOptimize.isChecked())
			return;
		
		switch(serverType.getSelected())
		{
			case PAPER:
			if(height.getValue() <= FABRIC_MAX_HEIGHT)
				height.setValue(22.8);
			heightIncrease.setValue(9);
			attackCount.setValue(3);
			break;
			
			case FABRIC:
			height.setValue(22.3);
			heightIncrease.setValue(7);
			attackCount.setValue(2);
			if(height.getValue() > FABRIC_MAX_HEIGHT)
				height.setValue(FABRIC_MAX_HEIGHT);
			break;
		}
		autoOptimizeWasChecked = true;
	}
	
	private void onServerTypeChanged()
	{
		double maxHeight = getServerTypeMaxHeight();
		height.setUsableMax(maxHeight);
		if(height.getValue() > maxHeight)
			height.setValue(maxHeight);
		
		boolean paper = serverType.getSelected() == ServerType.PAPER;
		totemBypass.setVisibleInGui(paper);
		autoOptimize.setVisibleInGui(paper);
		heightIncrease.setVisibleInGui(paper);
		attackCount.setVisibleInGui(paper);
		
		if(!paper)
			totemBypass.setChecked(false);
		
		if(WURST.getGuiIfInitialized() != null)
			WURST.getGuiIfInitialized().requestRefresh();
		
		if(autoOptimize.isChecked())
			applyAutoOptimize();
	}
	
	private double getServerTypeMaxHeight()
	{
		return serverType.getSelected() == ServerType.FABRIC ? FABRIC_MAX_HEIGHT
			: PAPER_MAX_HEIGHT;
	}
	
	private void sendFakeY(double offset)
	{
		MC.player.connection
			.send(new Pos(MC.player.getX(), MC.player.getY() + offset,
				MC.player.getZ(), false, MC.player.horizontalCollision));
	}
	
	private void spawnSmashPartyEffects(int entityId)
	{
		if(!MC.isSameThread())
		{
			MC.execute(() -> spawnSmashPartyEffects(entityId));
			return;
		}
		
		if(!isEnabled() || !smashSparkles.isChecked() || MC.level == null)
			return;
		
		Entity target = MC.level.getEntity(entityId);
		if(!(target instanceof LivingEntity living) || !living.isAlive()
			|| living.isRemoved())
			return;
		
		int fireworksCount = scaledCount(fireworks.getValueI());
		int confettiCount = scaledCount(confetti.getValueI());
		int sparkleCount = scaledCount(sparkles.getValueI());
		
		double x = target.getX();
		double y = target.getY() + target.getBbHeight() * 0.5;
		double z = target.getZ();
		double spread = Math.max(target.getBbWidth(), 0.8) * 0.75;
		double verticalSpread = Math.max(target.getBbHeight(), 1.0) * 0.35;
		int color = sparkleColor.getColorI();
		
		for(int i = 0; i < fireworksCount; i++)
		{
			double dx = randomRange(0.8);
			double dy = randomRange(0.8) + 0.25;
			double dz = randomRange(0.8);
			MC.level.addParticle(new DustParticleOptions(color, 1.35F), x,
				y + 0.25, z, dx, dy, dz);
			if(random.nextBoolean())
				MC.level.addParticle(ParticleTypes.END_ROD, x, y + 0.25, z,
					dx * 0.6, dy * 0.6, dz * 0.6);
		}
		
		for(int i = 0; i < confettiCount; i++)
		{
			double px = x + randomRange(2.5);
			double py = y + random.nextDouble() * 2.5;
			double pz = z + randomRange(2.5);
			double dx = randomRange(0.15);
			double dy = random.nextDouble() * 0.2;
			double dz = randomRange(0.15);
			MC.level.addParticle(new DustParticleOptions(color, 0.8F), px, py,
				pz, dx, dy, dz);
		}
		
		for(int i = 0; i < sparkleCount; i++)
		{
			double px = x + randomRange(spread);
			double py = y + randomRange(verticalSpread);
			double pz = z + randomRange(spread);
			double dx = randomRange(0.2);
			double dy = random.nextDouble() * 0.25;
			double dz = randomRange(0.2);
			MC.level.addParticle(new DustParticleOptions(color, 1.0F), px, py,
				pz, dx, dy, dz);
			if(random.nextBoolean())
				MC.level.addParticle(ParticleTypes.END_ROD, px, py, pz,
					dx * 0.7, dy * 0.7, dz * 0.7);
		}
	}
	
	private int scaledCount(int base)
	{
		return (int)Math.round(base * excitementMultiplier());
	}
	
	private double excitementMultiplier()
	{
		return excitement.getValue() / 100.0;
	}
	
	private double randomRange(double halfSpan)
	{
		return random.nextDouble() * halfSpan * 2 - halfSpan;
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
	
	private static final class TotemBypassState
	{
		private int graceTicks = TOTEM_BYPASS_GRACE_TICKS;
		private boolean burstReady;
	}
}
