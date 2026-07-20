/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.WurstClient;
import net.wurstclient.events.HandleInputListener;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"untouchable", "dodge hvh", "hacker vs hacker", "anti mace",
	"anti spear", "anti sword", "anti axe", "mace dodge", "spear dodge"})
public final class UntouchableHack extends Hack
	implements UpdateListener, PacketInputListener, HandleInputListener
{
	private static final int DIRECTION_SAMPLES = 16;
	private static final long MACE_PACKET_CUE_MS = 500;
	private static final long PRIMED_SPEAR_MEMORY_MS = 1200;
	private static final long CHARGING_CREEPER_MEMORY_MS = 1200;
	
	private enum KeepDistanceMode
	{
		ALWAYS("Always"),
		WHILE_DODGING("While dodging");
		
		private final String name;
		
		KeepDistanceMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private enum MovePauseMode
	{
		MOVE_TOWARD_PLAYER("Moving toward player"),
		ANY_MOVEMENT_KEY("Any movement key");
		
		private final String name;
		
		MovePauseMode(String name)
		{
			this.name = name;
		}
		
		@Override
		public String toString()
		{
			return name;
		}
	}
	
	private final CheckboxSetting dodgeMaces =
		new CheckboxSetting("Dodge maces",
			"description.wurst.setting.untouchable.dodge_maces", true);
	private final CheckboxSetting dodgeSwords =
		new CheckboxSetting("Dodge swords",
			"description.wurst.setting.untouchable.dodge_swords", true);
	private final CheckboxSetting dodgeAxes = new CheckboxSetting("Dodge axes",
		"description.wurst.setting.untouchable.dodge_axes", true);
	private final CheckboxSetting dodgeSpears =
		new CheckboxSetting("Dodge spears",
			"description.wurst.setting.untouchable.dodge_spears", true);
	private final CheckboxSetting onlyPrimedSpears =
		new CheckboxSetting("Only primed spears",
			"description.wurst.setting.untouchable.only_primed_spears", true);
	private final CheckboxSetting keepDistance =
		new CheckboxSetting("Keep distance",
			"description.wurst.setting.untouchable.keep_distance", true);
	private final CheckboxSetting autoDistanceOnDamage = new CheckboxSetting(
		"Auto distance on damage",
		"description.wurst.setting.untouchable.auto_distance_on_damage", false);
	private final CheckboxSetting autoDistanceOnTotemPop =
		new CheckboxSetting("Auto distance on totem pop",
			"description.wurst.setting.untouchable.auto_distance_on_totem_pop",
			false);
	private final EnumSetting<KeepDistanceMode> keepDistanceMode =
		new EnumSetting<>("Keep distance mode",
			"description.wurst.setting.untouchable.keep_distance_mode",
			KeepDistanceMode.values(), KeepDistanceMode.ALWAYS);
	private final EnumSetting<MovePauseMode> movePauseMode =
		new EnumSetting<>("Move pause mode",
			"description.wurst.setting.untouchable.move_pause_mode",
			MovePauseMode.values(), MovePauseMode.MOVE_TOWARD_PLAYER);
	private final CheckboxSetting pauseOnShift = new CheckboxSetting(
		"Pause on shift",
		"Holding sneak prevents Untouchable from teleporting. While held, the "
			+ "sneak key is released again so you keep moving at normal speed.",
		false);
	private final CheckboxSetting pauseOnLeftControl =
		new CheckboxSetting("Pause on left Ctrl",
			"Holding left Ctrl prevents Untouchable from teleporting.", false);
	private final SliderSetting playerDistance =
		new SliderSetting("Player distance",
			"description.wurst.setting.untouchable.player_distance", 7, 3, 16,
			0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting detectionRange =
		new SliderSetting("Detection range",
			"description.wurst.setting.untouchable.detection_range", 12, 6, 32,
			0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting reachAllowance =
		new SliderSetting("Reach allowance",
			"description.wurst.setting.untouchable.reach_allowance", 6, 3, 10,
			0.25, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting reactionTicks = new SliderSetting("Prediction",
		"description.wurst.setting.untouchable.prediction", 4, 1, 10, 1,
		ValueDisplay.INTEGER.withSuffix(" ticks"));
	private final SliderSetting scanDistance =
		new SliderSetting("Teleport distance",
			"description.wurst.setting.untouchable.teleport_distance", 6, 1, 12,
			0.25, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final SliderSetting verticalScan = new SliderSetting(
		"Vertical scan", "description.wurst.setting.untouchable.vertical_scan",
		3, 0, 8, 1, ValueDisplay.INTEGER.withSuffix(" blocks"));
	private final SliderSetting teleportPackets =
		new SliderSetting("Teleport packets",
			"description.wurst.setting.untouchable.teleport_packets", 4, 1, 10,
			1, ValueDisplay.INTEGER);
	private final SliderSetting teleportCooldown =
		new SliderSetting("Teleport cooldown",
			"description.wurst.setting.untouchable.teleport_cooldown", 3, 1, 10,
			1, ValueDisplay.INTEGER.withSuffix(" ticks"));
	private final CheckboxSetting avoidDrops =
		new CheckboxSetting("Avoid drops",
			"description.wurst.setting.untouchable.avoid_drops", true);
	private final CheckboxSetting avoidHostileMobs =
		new CheckboxSetting("Avoid hostile mobs",
			"description.wurst.setting.untouchable.avoid_hostile_mobs", false);
	private final CheckboxSetting onlyChargingCreepers = new CheckboxSetting(
		"Only charging creepers",
		"description.wurst.setting.untouchable.only_charging_creepers", false);
	private final CheckboxSetting avoidArrows =
		new CheckboxSetting("Avoid arrows",
			"description.wurst.setting.untouchable.avoid_arrows", true);
	private final CheckboxSetting avoidCrystals = new CheckboxSetting(
		"Avoid crystals",
		"Dodges dangerous end crystals near you when they are placed.", true);
	private final SliderSetting armedRadiusBonus =
		new SliderSetting("Armed radius bonus",
			"Extra defensive radius while an enemy is holding a spear or mace.",
			4, 0, 12, 0.5, ValueDisplay.DECIMAL.withSuffix(" blocks"));
	private final CheckboxSetting emergencyEscape = new CheckboxSetting(
		"Emergency escape",
		"Teleports perpendicular to the current threat after taking at least the configured damage.",
		true);
	private final SliderSetting emergencyDamage =
		new SliderSetting("Emergency damage",
			"Minimum health damage that triggers an emergency escape.", 10, 1,
			40, 1, ValueDisplay.INTEGER.withSuffix(" HP"));
	
	private final Map<Integer, Vec3> previousPositions = new HashMap<>();
	private final Map<Integer, MacePacketCue> macePacketCues =
		new ConcurrentHashMap<>();
	private final Map<Integer, Long> primedSpearCues =
		new ConcurrentHashMap<>();
	private final Map<Integer, Long> chargingCreeperCues =
		new ConcurrentHashMap<>();
	private int cooldownTicksLeft;
	private int statusTicksLeft;
	private ThreatType activeThreat;
	private float lastHealth = -1;
	
	public UntouchableHack()
	{
		super("Untouchable", "description.wurst.hack.untouchable", true);
		setCategory(Category.COMBAT);
		addSetting(dodgeMaces);
		addSetting(dodgeSwords);
		addSetting(dodgeAxes);
		addSetting(dodgeSpears);
		addSetting(onlyPrimedSpears);
		addSetting(avoidCrystals);
		addSetting(keepDistance);
		addSetting(autoDistanceOnDamage);
		addSetting(autoDistanceOnTotemPop);
		addSetting(keepDistanceMode);
		addSetting(movePauseMode);
		addSetting(pauseOnShift);
		addSetting(pauseOnLeftControl);
		addSetting(playerDistance);
		addSetting(detectionRange);
		addSetting(reachAllowance);
		addSetting(reactionTicks);
		addSetting(armedRadiusBonus);
		addSetting(scanDistance);
		addSetting(verticalScan);
		addSetting(teleportPackets);
		addSetting(teleportCooldown);
		addSetting(avoidDrops);
		addSetting(avoidHostileMobs);
		addSetting(onlyChargingCreepers);
		addSetting(avoidArrows);
		addSetting(emergencyEscape);
		addSetting(emergencyDamage);
	}
	
	@Override
	public String getStatusText()
	{
		return activeThreat == null ? null : activeThreat.label;
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(HandleInputListener.class, this);
		reset();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(HandleInputListener.class, this);
		reset();
	}
	
	private void reset()
	{
		previousPositions.clear();
		macePacketCues.clear();
		primedSpearCues.clear();
		chargingCreeperCues.clear();
		cooldownTicksLeft = 0;
		statusTicksLeft = 0;
		activeThreat = null;
		lastHealth = -1;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		inspectPacket(event.getPacket());
	}
	
	@Override
	public void onHandleInput()
	{
		if(shouldPauseOnShift())
			releaseSneakKey();
	}
	
	private void inspectPacket(Packet<?> packet)
	{
		if(packet instanceof ClientboundBundlePacket bundle)
		{
			for(Packet<?> subPacket : bundle.subPackets())
				inspectPacket(subPacket);
			return;
		}
		
		if(packet instanceof ClientboundAnimatePacket animation && (animation
			.getAction() == ClientboundAnimatePacket.SWING_MAIN_HAND
			|| animation
				.getAction() == ClientboundAnimatePacket.SWING_OFF_HAND))
		{
			int entityId = animation.getId();
			MC.execute(() -> handleSwingPacket(entityId));
			return;
		}
		
		if(packet instanceof ClientboundEntityEventPacket entityEvent
			&& entityEvent.getEventId() == 35)
		{
			MC.execute(() -> handleEntityEvent(entityEvent));
			return;
		}
		
		if(packet instanceof ClientboundDamageEventPacket damage)
		{
			MC.execute(() -> handleDamageEvent(damage));
			return;
		}
		
		int id;
		Vec3 position;
		if(packet instanceof ClientboundTeleportEntityPacket teleport)
		{
			id = teleport.id();
			position = teleport.change().position();
		}else if(packet instanceof ClientboundEntityPositionSyncPacket sync)
		{
			id = sync.id();
			position = sync.values().position();
		}else if(packet instanceof ClientboundMoveEntityPacket move
			&& move.hasPosition() && MC.level != null)
		{
			Entity movedEntity = move.getEntity(MC.level);
			if(movedEntity == null)
				return;
			id = movedEntity.getId();
			position = movedEntity.getPositionCodec().decode(move.getXa(),
				move.getYa(), move.getZa());
		}else
			return;
		
		if(MC.player == null || MC.level == null || position == null)
			return;
		int movingEntityId = id;
		Vec3 incomingPosition = position;
		MC.execute(
			() -> handleIncomingPosition(movingEntityId, incomingPosition));
		Entity entity = MC.level.getEntity(id);
		if(!(entity instanceof Player player) || player == MC.player
			|| isIgnoredPlayer(player) || !isHoldingMace(player))
			return;
		
		Vec3 oldPosition = entity.position();
		boolean suddenRise = position.y - oldPosition.y >= 1.25;
		boolean aboveUs = position.y - MC.player.getY() >= 1.25;
		double horizontalSq =
			horizontalDistanceSqr(position, MC.player.position());
		if(suddenRise && aboveUs
			&& horizontalSq <= square(reachAllowance.getValue()))
		{
			macePacketCues.put(id,
				new MacePacketCue(position, System.currentTimeMillis()));
			if(MC.isSameThread())
				tryImmediatePacketDodge();
			else
				MC.execute(this::tryImmediatePacketDodge);
		}
	}
	
	private void handleEntityEvent(ClientboundEntityEventPacket entityEvent)
	{
		if(!isEnabled() || MC.player == null || MC.level == null)
			return;
		
		Entity entity = entityEvent.getEntity(MC.level);
		if(!(entity instanceof Player player))
			return;
		
		if(player == MC.player && autoDistanceOnTotemPop.isChecked())
			setKeepDistanceAlways();
	}
	
	private void handleDamageEvent(ClientboundDamageEventPacket damage)
	{
		if(!isEnabled() || MC.player == null || MC.level == null
			|| !autoDistanceOnDamage.isChecked()
			|| damage.entityId() != MC.player.getId())
			return;
		
		Entity source = MC.level.getEntity(damage.sourceCauseId());
		if(!(source instanceof Player attacker) || attacker == MC.player
			|| isIgnoredPlayer(attacker))
			return;
		
		setKeepDistanceAlways();
	}
	
	private void setKeepDistanceAlways()
	{
		if(keepDistanceMode.getSelected() != KeepDistanceMode.ALWAYS)
			keepDistanceMode.setSelected(KeepDistanceMode.ALWAYS);
	}
	
	private void tryImmediatePacketDodge()
	{
		if(!isEnabled() || MC.player == null || MC.level == null
			|| cooldownTicksLeft > 0)
			return;
		Threat threat = findMostUrgentThreat();
		if(threat != null && threat.type == ThreatType.MACE)
			teleportAway(threat);
	}
	
	private void handleSwingPacket(int entityId)
	{
		if(!isEnabled() || MC.player == null || MC.level == null
			|| cooldownTicksLeft > 0)
			return;
		Entity entity = MC.level.getEntity(entityId);
		if(!(entity instanceof Player attacker) || attacker == MC.player
			|| isIgnoredPlayer(attacker))
			return;
		
		Threat threat = null;
		if(dodgeSpears.isChecked()
			&& (!onlyPrimedSpears.isChecked() && isSpear(getHeldSpear(attacker))
				|| wasRecentlyPrimed(attacker)))
			threat = getSpearThreat(attacker, true);
		if(dodgeMaces.isChecked() && isHoldingMace(attacker))
		{
			Threat maceThreat = getMaceThreat(attacker);
			if(maceThreat != null)
				threat = maceThreat;
		}
		if(dodgeSwords.isChecked())
		{
			Threat swordThreat =
				getMeleeThreat(attacker, WeaponType.SWORD, true);
			if(swordThreat != null
				&& (threat == null || swordThreat.urgency > threat.urgency))
				threat = swordThreat;
		}
		if(dodgeAxes.isChecked())
		{
			Threat axeThreat = getMeleeThreat(attacker, WeaponType.AXE, true);
			if(axeThreat != null
				&& (threat == null || axeThreat.urgency > threat.urgency))
				threat = axeThreat;
		}
		if(threat == null)
			return;
		
		activeThreat = threat.type;
		teleportAway(threat);
	}
	
	private void handleIncomingPosition(int entityId, Vec3 incomingPosition)
	{
		if(!isEnabled() || MC.player == null || MC.level == null)
			return;
		Entity entity = MC.level.getEntity(entityId);
		if(!(entity instanceof Player attacker) || attacker == MC.player
			|| isIgnoredPlayer(attacker))
			return;
		
		Threat threat = dodgeSpears.isChecked()
			? getPacketSpearThreat(attacker, incomingPosition) : null;
		if(threat == null && dodgeSwords.isChecked())
			threat = getPacketMeleeThreat(attacker, incomingPosition,
				WeaponType.SWORD);
		if(threat == null && dodgeAxes.isChecked())
			threat = getPacketMeleeThreat(attacker, incomingPosition,
				WeaponType.AXE);
		if(threat == null && keepDistance.isChecked()
			&& (keepDistanceMode.getSelected() == KeepDistanceMode.ALWAYS
				|| dodgeSpears.isChecked() && getSpearThreat(attacker) != null
				|| dodgeMaces.isChecked() && getMaceThreat(attacker) != null
				|| dodgeSwords.isChecked()
					&& getMeleeThreat(attacker, WeaponType.SWORD, false) != null
				|| dodgeAxes.isChecked()
					&& getMeleeThreat(attacker, WeaponType.AXE, false) != null))
			threat = getSpacingThreat(attacker, incomingPosition);
		if(threat == null)
			return;
		if(cooldownTicksLeft > 0 && threat.type != ThreatType.SPACING
			&& threat.type != ThreatType.SPEAR)
			return;
		
		activeThreat = threat.type;
		teleportAway(threat);
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.level == null || MC.player.isSpectator())
		{
			reset();
			return;
		}
		
		rememberPrimedSpears();
		rememberChargingCreepers();
		checkEmergencyDamage();
		if(cooldownTicksLeft > 0)
			cooldownTicksLeft--;
		
		Threat threat = findMostUrgentThreat();
		if(threat != null)
		{
			activeThreat = threat.type;
			statusTicksLeft = teleportCooldown.getValueI() + 2;
			if(cooldownTicksLeft <= 0 || threat.type == ThreatType.SPACING
				|| threat.type == ThreatType.MACE)
				teleportAway(threat);
		}else if(statusTicksLeft > 0)
			statusTicksLeft--;
		else
		{
			activeThreat = null;
		}
		
		rememberPositions();
		prunePacketCues();
	}
	
	private Threat findMostUrgentThreat()
	{
		Threat best = null;
		for(Player attacker : MC.level.players())
		{
			if(attacker == MC.player || !attacker.isAlive()
				|| isIgnoredPlayer(attacker) || attacker.isSpectator())
				continue;
			
			Threat threat = null;
			Threat weaponThreat = null;
			if(dodgeSpears.isChecked())
			{
				Threat spearThreat = getSpearThreat(attacker);
				if(spearThreat != null)
					weaponThreat = spearThreat;
			}
			if(dodgeMaces.isChecked())
			{
				Threat maceThreat = getMaceThreat(attacker);
				if(maceThreat != null && (weaponThreat == null
					|| maceThreat.urgency > weaponThreat.urgency))
					weaponThreat = maceThreat;
			}
			if(dodgeSwords.isChecked())
			{
				Threat swordThreat =
					getMeleeThreat(attacker, WeaponType.SWORD, false);
				if(swordThreat != null && (weaponThreat == null
					|| swordThreat.urgency > weaponThreat.urgency))
					weaponThreat = swordThreat;
			}
			if(dodgeAxes.isChecked())
			{
				Threat axeThreat =
					getMeleeThreat(attacker, WeaponType.AXE, false);
				if(axeThreat != null && (weaponThreat == null
					|| axeThreat.urgency > weaponThreat.urgency))
					weaponThreat = axeThreat;
			}
			
			if(keepDistance.isChecked()
				&& (keepDistanceMode.getSelected() == KeepDistanceMode.ALWAYS
					|| weaponThreat != null))
			{
				threat = getSpacingThreat(attacker, attacker.position());
			}
			if(weaponThreat != null
				&& (threat == null || weaponThreat.urgency > threat.urgency))
				threat = weaponThreat;
			
			if(threat != null
				&& (best == null || threat.urgency > best.urgency))
				best = threat;
		}
		if(avoidHostileMobs.isChecked())
		{
			for(Entity entity : MC.level.entitiesForRendering())
			{
				if(!(entity instanceof Enemy) || entity == MC.player
					|| !entity.isAlive())
					continue;
				if(shouldSuppressDodging(entity.position()))
					continue;
				if(onlyChargingCreepers.isChecked()
					&& !isChargingCreeper(entity))
					continue;
				
				Threat threat = getHostileMobThreat(entity);
				if(threat != null
					&& (best == null || threat.urgency > best.urgency))
					best = threat;
			}
		}
		if(avoidArrows.isChecked())
		{
			for(Entity entity : MC.level.entitiesForRendering())
			{
				if(!(entity instanceof AbstractArrow arrow) || !arrow.isAlive()
					|| arrow.getOwner() == MC.player)
					continue;
				Entity owner = arrow.getOwner();
				if(owner instanceof Player player && isIgnoredPlayer(player))
					continue;
				
				Threat threat = getArrowThreat(arrow);
				if(threat != null
					&& (best == null || threat.urgency > best.urgency))
					best = threat;
			}
		}
		if(avoidCrystals.isChecked())
		{
			for(Entity entity : MC.level.entitiesForRendering())
			{
				if(!(entity instanceof EndCrystal crystal)
					|| !crystal.isAlive())
					continue;
				Threat threat = getCrystalThreat(crystal);
				if(threat != null
					&& (best == null || threat.urgency > best.urgency))
					best = threat;
			}
		}
		return best;
	}
	
	private void checkEmergencyDamage()
	{
		float health = MC.player.getHealth();
		if(lastHealth < 0)
		{
			lastHealth = health;
			return;
		}
		
		float damage = lastHealth - health;
		lastHealth = health;
		if(!emergencyEscape.isChecked() || damage < emergencyDamage.getValueF()
			|| MC.player.hurtTime <= 0)
			return;
		
		Threat threat = null;
		for(Player attacker : MC.level.players())
		{
			if(attacker == MC.player || !attacker.isAlive()
				|| isIgnoredPlayer(attacker))
				continue;
			Threat candidate =
				getMeleeThreat(attacker, WeaponType.SWORD, false);
			if(candidate == null)
				candidate = getMeleeThreat(attacker, WeaponType.AXE, false);
			if(candidate != null
				&& (threat == null || candidate.urgency > threat.urgency))
				threat = candidate;
		}
		
		if(threat == null)
		{
			Vec3 center = MC.player.getBoundingBox().getCenter();
			threat = new Threat(ThreatType.EMERGENCY, center,
				center.add(0, 0, 1), 1000);
		}
		activeThreat = ThreatType.EMERGENCY;
		teleportAway(threat, true);
	}
	
	private Threat getSpacingThreat(Player attacker, Vec3 attackerPosition)
	{
		if(shouldSuppressDodging(attackerPosition))
			return null;
		Vec3 centerOffset =
			attacker.getBoundingBox().getCenter().subtract(attacker.position());
		Vec3 attackerCenter = attackerPosition.add(centerOffset);
		Vec3 playerCenter = MC.player.getBoundingBox().getCenter();
		double distance = attackerCenter.distanceTo(playerCenter);
		if(distance >= playerDistance.getValue())
			return null;
		return new Threat(ThreatType.SPACING, attackerCenter, playerCenter,
			300 + (playerDistance.getValue() - distance) * 10);
	}
	
	private Threat getPacketSpearThreat(Player attacker, Vec3 incomingPosition)
	{
		ItemStack spear = attacker.getUseItem();
		if(!isSpear(spear))
			spear = getHeldSpear(attacker);
		if(!isSpear(spear))
			return null;
		if(onlyPrimedSpears.isChecked()
			&& !(attacker.isUsingItem()
				&& attacker.getTicksUsingItem() >= getSpearReadyTicks(spear))
			&& !wasRecentlyPrimed(attacker))
			return null;
		
		Vec3 centerOffset =
			attacker.getBoundingBox().getCenter().subtract(attacker.position());
		Vec3 start = incomingPosition.add(centerOffset);
		Vec3 playerCenter = MC.player.getBoundingBox().getCenter();
		Vec3 velocity = incomingPosition.subtract(attacker.position());
		boolean primed = wasRecentlyPrimed(attacker) || attacker.isUsingItem()
			&& attacker.getTicksUsingItem() >= getSpearReadyTicks(spear);
		// At extreme speeds, item-use metadata can arrive a packet late. A
		// held spear moving over 20 blocks/sec is dangerous enough to predict.
		if(!primed && velocity.length() < 1)
			return null;
		Vec3 toPlayer = playerCenter.subtract(start);
		if(velocity.lengthSqr() < 0.01 || velocity.dot(toPlayer) <= 0)
		{
			Vec3 aim = attacker.getLookAngle();
			if(aim.dot(toPlayer.normalize()) < 0.7)
				return null;
			velocity = aim.scale(Math.max(0.75, velocity.length()));
		}
		
		double ticks = reactionTicks.getValue() + 2;
		double effectiveRange = Math.max(detectionRange.getValue(),
			velocity.length() * ticks + reachAllowance.getValue());
		if(start.distanceToSqr(playerCenter) > square(effectiveRange))
			return null;
		Vec3 end = start.add(velocity.scale(ticks));
		if(distanceToSegment(playerCenter, start, end) > reachAllowance
			.getValue())
			return null;
		return new Threat(ThreatType.SPEAR, start, end, 500);
	}
	
	private Threat getSpearThreat(Player attacker)
	{
		return getSpearThreat(attacker, false);
	}
	
	private Threat getSpearThreat(Player attacker, boolean swingTriggered)
	{
		ItemStack spear = attacker.getUseItem();
		boolean primedNow = attacker.isUsingItem() && isSpear(spear)
			&& attacker.getTicksUsingItem() >= getSpearReadyTicks(spear);
		if(onlyPrimedSpears.isChecked() && !primedNow
			&& !wasRecentlyPrimed(attacker))
			return null;
		if(!primedNow && !swingTriggered)
			return null;
		if(!isSpear(spear))
			spear = getHeldSpear(attacker);
		if(!isSpear(spear))
			return null;
		
		Vec3 start = attacker.getBoundingBox().getCenter();
		Vec3 playerCenter = MC.player.getBoundingBox().getCenter();
		Vec3 velocity = getObservedVelocity(attacker);
		double horizontalSpeed = horizontalLength(velocity);
		Vec3 toPlayer = playerCenter.subtract(start);
		double ticks = reactionTicks.getValue();
		// Fast Flight/SpearKill users can cross the fixed detection range in a
		// single network tick. Expand it by their observed travel over the
		// prediction window, plus two ticks of packet/jitter headroom.
		double speedScaledRange =
			velocity.length() * (ticks + 2) + reachAllowance.getValue();
		double effectiveRange =
			Math.max(detectionRange.getValue(), speedScaledRange);
		if(start.distanceToSqr(playerCenter) > square(effectiveRange))
			return null;
		
		boolean movingAtUs =
			horizontalSpeed >= 0.12 && horizontalDot(toPlayer, velocity) > 0;
		Vec3 attackVelocity = velocity;
		if(!movingAtUs)
		{
			Vec3 aim = attacker.getLookAngle();
			double distance = toPlayer.length();
			double aimDot =
				distance < 1.0E-6 ? 1 : aim.dot(toPlayer.scale(1 / distance));
			double armedRange = reachAllowance.getValue() + ticks * 0.75;
			if(aimDot < 0.75 || distance > armedRange)
				return null;
				
			// Flight/AimAssist users can go from hovering to full speed in one
			// tick. Model that armed attack line before movement is visible.
			attackVelocity = aim.scale(Math.max(0.75, velocity.length()));
			horizontalSpeed = Math.max(0.12, horizontalLength(attackVelocity));
		}
		
		Vec3 end = start.add(attackVelocity.scale(ticks));
		double missDistance =
			horizontalDistanceToSegment(playerCenter, start, end);
		double verticalMiss =
			verticalDistanceToSegment(playerCenter, start, end);
		if(missDistance > reachAllowance.getValue() || verticalMiss > 3)
			return null;
		
		double currentDistance = horizontalLength(toPlayer);
		double ticksToReach = Math.max(0,
			(currentDistance - reachAllowance.getValue()) / horizontalSpeed);
		double urgency = (swingTriggered ? 180 : movingAtUs ? 100 : 85)
			- ticksToReach * 10 - missDistance;
		return new Threat(ThreatType.SPEAR, start, end, urgency);
	}
	
	private Threat getPacketMeleeThreat(Player attacker, Vec3 incomingPosition,
		WeaponType weaponType)
	{
		ItemStack weapon = weaponType.getWeapon(attacker);
		if(weapon.isEmpty())
			return null;
		
		Vec3 centerOffset =
			attacker.getBoundingBox().getCenter().subtract(attacker.position());
		Vec3 start = incomingPosition.add(centerOffset);
		Vec3 playerCenter = MC.player.getBoundingBox().getCenter();
		Vec3 velocity = incomingPosition.subtract(attacker.position());
		Vec3 toPlayer = playerCenter.subtract(start);
		double distance = toPlayer.length();
		double ticks = reactionTicks.getValue() + 2;
		double weaponReach = weaponType == WeaponType.AXE ? 4.0 : 3.5;
		double effectiveRange = Math.max(detectionRange.getValue(), weaponReach
			+ velocity.length() * ticks + reachAllowance.getValue());
		if(distance > effectiveRange)
			return null;
		
		Vec3 attackVelocity = velocity;
		if(attackVelocity.lengthSqr() < 0.01
			|| attackVelocity.dot(toPlayer) <= 0)
		{
			Vec3 aim = attacker.getLookAngle();
			if(distance > 1.0E-6 && aim.dot(toPlayer.normalize()) < 0.5)
				return null;
			attackVelocity = aim.scale(Math.max(0.5, velocity.length()));
		}
		
		Vec3 end = start.add(attackVelocity.scale(ticks));
		double missDistance = distanceToSegment(playerCenter, start, end);
		double closeRange = weaponReach + 0.75;
		if(missDistance > reachAllowance.getValue() && distance > closeRange)
			return null;
		
		double urgency = (weaponType == WeaponType.AXE ? 95 : 90)
			+ Math.max(0, closeRange - distance) * 10
			+ (attacker.isUsingItem() ? 20 : 0);
		return new Threat(weaponType.threatType, start, end, urgency);
	}
	
	private Threat getMeleeThreat(Player attacker, WeaponType weaponType,
		boolean swingTriggered)
	{
		ItemStack weapon = weaponType.getWeapon(attacker);
		if(weapon.isEmpty())
			return null;
		
		Vec3 start = attacker.getBoundingBox().getCenter();
		Vec3 playerCenter = MC.player.getBoundingBox().getCenter();
		Vec3 toPlayer = playerCenter.subtract(start);
		double distance = toPlayer.length();
		double horizontalDistance = horizontalLength(toPlayer);
		double weaponReach = weaponType == WeaponType.AXE ? 4.0 : 3.5;
		double ticks = reactionTicks.getValue();
		double speed = horizontalLength(getObservedVelocity(attacker));
		double effectiveRange = Math.max(detectionRange.getValue(),
			weaponReach + speed * (ticks + 2) + reachAllowance.getValue());
		if(distance > effectiveRange)
			return null;
		
		Vec3 velocity = getObservedVelocity(attacker);
		boolean movingAtUs = horizontalLength(velocity) >= 0.08
			&& horizontalDot(toPlayer, velocity) > 0;
		Vec3 aim = attacker.getLookAngle();
		double aimDot =
			distance < 1.0E-6 ? 1 : aim.dot(toPlayer.scale(1 / distance));
		boolean closeEnough = horizontalDistance <= weaponReach + 0.9;
		boolean armed =
			swingTriggered || movingAtUs || aimDot > 0.55 || closeEnough;
		if(!armed)
			return null;
		
		Vec3 attackDirection = movingAtUs ? velocity : aim;
		if(attackDirection.lengthSqr() < 1.0E-6)
			attackDirection = toPlayer.normalize();
		Vec3 end = start.add(attackDirection.scale(ticks + 1));
		double missDistance = distanceToSegment(playerCenter, start, end);
		if(missDistance > reachAllowance.getValue() && !closeEnough)
			return null;
		
		double urgency = swingTriggered ? 180 : movingAtUs ? 120 : 100;
		urgency += Math.max(0, weaponReach - horizontalDistance) * 8;
		urgency -= Math.min(distance, 6) * 6;
		if(weaponType == WeaponType.AXE)
			urgency += 10;
		return new Threat(weaponType.threatType, start, end, urgency);
	}
	
	private Threat getMaceThreat(Player attacker)
	{
		if(!isHoldingMace(attacker))
			return null;
		
		Vec3 playerCenter = MC.player.getBoundingBox().getCenter();
		Vec3 attackerCenter = attacker.getBoundingBox().getCenter();
		double horizontalDistance =
			horizontalLength(attackerCenter.subtract(playerCenter));
		double armedRadius =
			reachAllowance.getValue() + 2 + armedRadiusBonus.getValue();
		if(horizontalDistance > armedRadius)
			return null;
		
		MacePacketCue cue = macePacketCues.get(attacker.getId());
		boolean packetSpoof = cue != null
			&& System.currentTimeMillis() - cue.timeMs <= MACE_PACKET_CUE_MS;
		Vec3 velocity = getObservedVelocity(attacker);
		boolean fallingAbove = attackerCenter.y - playerCenter.y >= 1.25
			&& (velocity.y < -0.08 || attacker.fallDistance >= 1.25F);
		Vec3 toPlayer = playerCenter.subtract(attackerCenter);
		double aimDot = toPlayer.lengthSqr() < 1.0E-6 ? 1
			: attacker.getLookAngle().dot(toPlayer.normalize());
		boolean closing = horizontalDot(toPlayer, velocity) > 0.01;
		boolean inReach = horizontalDistance <= reachAllowance.getValue();
		boolean preparingAttack = inReach || aimDot > 0.55 || closing;
		if(!packetSpoof && !fallingAbove && !preparingAttack)
			return null;
		
		Vec3 pathStart = packetSpoof ? cue.position : attackerCenter;
		Vec3 pathEnd =
			new Vec3(attackerCenter.x, playerCenter.y, attackerCenter.z);
		double urgency = packetSpoof ? 200
			: fallingAbove
				? 150 + Math.max(0, -velocity.y * 20)
					- Math.max(0, attackerCenter.y - playerCenter.y)
				: 125 - horizontalDistance;
		return new Threat(ThreatType.MACE, pathStart, pathEnd, urgency);
	}
	
	private Threat getHostileMobThreat(Entity mob)
	{
		if(MC.player == null || mob == null)
			return null;
		
		Vec3 playerCenter = MC.player.getBoundingBox().getCenter();
		Vec3 mobCenter = mob.getBoundingBox().getCenter();
		double distance = mobCenter.distanceTo(playerCenter);
		if(distance >= playerDistance.getValue())
			return null;
		
		return new Threat(ThreatType.MOB, mobCenter, playerCenter,
			250 + (playerDistance.getValue() - distance) * 10);
	}
	
	private Threat getArrowThreat(AbstractArrow arrow)
	{
		if(MC.player == null || MC.level == null)
			return null;
		
		Vec3 playerCenter = MC.player.getBoundingBox().getCenter();
		Vec3 arrowCenter = arrow.getBoundingBox().getCenter();
		Vec3 velocity = getObservedVelocity(arrow);
		if(velocity.lengthSqr() < 0.01)
			velocity = arrow.getDeltaMovement();
		if(velocity.lengthSqr() < 0.01)
			return null;
		
		Vec3 toPlayer = playerCenter.subtract(arrowCenter);
		if(velocity.dot(toPlayer) <= 0)
			return null;
		
		double ticks = reactionTicks.getValue() + 2;
		Vec3 end = arrowCenter.add(velocity.scale(ticks));
		double missDistance = distanceToSegment(playerCenter, arrowCenter, end);
		double effectiveRange = Math.max(detectionRange.getValue(),
			velocity.length() * ticks + reachAllowance.getValue());
		if(arrowCenter.distanceToSqr(playerCenter) > square(effectiveRange)
			|| missDistance > reachAllowance.getValue() + 0.75)
			return null;
		
		double urgency =
			110 + Math.max(0, 6 - arrowCenter.distanceTo(playerCenter)) * 12
				- missDistance * 5;
		return new Threat(ThreatType.ARROW, arrowCenter, end, urgency);
	}
	
	private Threat getCrystalThreat(EndCrystal crystal)
	{
		Vec3 playerCenter = MC.player.getBoundingBox().getCenter();
		Vec3 crystalCenter = crystal.getBoundingBox().getCenter();
		double distance = crystalCenter.distanceTo(playerCenter);
		double radius = Math.max(detectionRange.getValue(), 6);
		if(distance > radius)
			return null;
		
		double urgency = 260 + Math.max(0, radius - distance) * 20;
		return new Threat(ThreatType.CRYSTAL, crystalCenter, playerCenter,
			urgency);
	}
	
	private boolean isChargingCreeper(Entity entity)
	{
		if(!(entity instanceof Creeper creeper))
			return false;
		
		return isChargingCreeper(creeper);
	}
	
	private boolean isChargingCreeper(Creeper creeper)
	{
		if(creeper == null || !creeper.isAlive())
			return false;
		
		if(creeper.getSwellDir() > 0 || creeper.isIgnited()
			|| creeper.isPowered())
			return true;
		
		Long expiry = chargingCreeperCues.get(creeper.getId());
		return expiry != null && expiry >= System.currentTimeMillis();
	}
	
	private Vec3 chooseDodgeDestination(Threat threat)
	{
		Vec3 playerPos = MC.player.position();
		Vec3 playerCenter = MC.player.getBoundingBox().getCenter();
		Vec3 attackAxis = threat.pathEnd.subtract(threat.pathStart);
		if(attackAxis.lengthSqr() > 1.0E-6)
			attackAxis = attackAxis.normalize();
		Vec3 currentVelocity = MC.player.getDeltaMovement();
		Vec3 currentHorizontal =
			new Vec3(currentVelocity.x, 0, currentVelocity.z);
		if(currentHorizontal.lengthSqr() > 1.0E-6)
			currentHorizontal = currentHorizontal.normalize();
		
		ArrayList<DodgeCandidate> candidates = new ArrayList<>();
		int verticalRange = verticalScan.getValueI();
		double maxHorizontal =
			threat.type == ThreatType.ARROW ? 1.0 : scanDistance.getValue();
		double minHorizontal = threat.type == ThreatType.ARROW ? 1.0 : 0.5;
		double horizontalStep = threat.type == ThreatType.ARROW ? 1.0 : 0.5;
		
		for(int yOffset = -verticalRange; yOffset <= verticalRange; yOffset++)
		{
			for(double distance =
				maxHorizontal; distance >= minHorizontal; distance -=
					horizontalStep)
			{
				for(int i = 0; i < DIRECTION_SAMPLES; i++)
				{
					double angle = Math.PI * 2 * i / DIRECTION_SAMPLES;
					Vec3 offset = new Vec3(Math.cos(angle) * distance, yOffset,
						Math.sin(angle) * distance);
					double score = scoreDestination(threat, playerCenter,
						offset, attackAxis, currentHorizontal);
					if(score == -Double.MAX_VALUE)
						continue;
					Vec3 destination = playerPos.add(offset);
					if(!isSafeDestination(offset))
						continue;
					candidates.add(new DodgeCandidate(destination, score));
				}
			}
			
			// Also consider a straight vertical escape when it is genuinely
			// perpendicular to the incoming attack.
			if(yOffset != 0)
			{
				Vec3 offset = new Vec3(0, yOffset, 0);
				double score = scoreDestination(threat, playerCenter, offset,
					attackAxis, currentHorizontal);
				if(score != -Double.MAX_VALUE && isSafeDestination(offset))
					candidates
						.add(new DodgeCandidate(playerPos.add(offset), score));
			}
		}
		
		if(candidates.isEmpty())
			return null;
		candidates
			.sort(Comparator.comparingDouble(DodgeCandidate::score).reversed());
		int randomPool = Math.min(8, candidates.size());
		return candidates
			.get(ThreadLocalRandom.current().nextInt(randomPool)).destination;
	}
	
	private double scoreDestination(Threat threat, Vec3 playerCenter,
		Vec3 offset, Vec3 attackAxis, Vec3 currentHorizontal)
	{
		Vec3 dodgeAxis = offset.normalize();
		double alongAttack = attackAxis.lengthSqr() > 1.0E-6
			? Math.abs(dodgeAxis.dot(attackAxis)) : 0;
		// Never move forward or backward along a spear trajectory. Only a
		// lateral/vertical displacement can leave its extended attack line.
		if(threat.type == ThreatType.SPEAR && alongAttack > 0.3)
			return -Double.MAX_VALUE;
		
		Vec3 destinationCenter = playerCenter.add(offset);
		if((threat.type == ThreatType.SPACING || threat.type == ThreatType.MOB)
			&& destinationCenter.distanceTo(threat.pathStart) < playerDistance
				.getValue())
			return -Double.MAX_VALUE;
		double pathSeparation =
			distanceToLine(destinationCenter, threat.pathStart, threat.pathEnd);
		double attackerSeparation =
			destinationCenter.distanceTo(threat.pathStart);
		double momentumBonus = currentHorizontal.lengthSqr() > 1.0E-6
			? dodgeAxis.dot(currentHorizontal) * 0.25 : 0;
		double verticalCost = Math.abs(offset.y) * 0.15;
		double score = pathSeparation * 10 + offset.length() * 0.2
			+ momentumBonus - verticalCost;
		if(threat.type == ThreatType.SPEAR)
			score += (1 - alongAttack) * 5;
		else
			score += attackerSeparation * 0.5;
		return score;
	}
	
	private boolean isSafeDestination(Vec3 offset)
	{
		AABB moved = MC.player.getBoundingBox().move(offset);
		if(!MC.level.noCollision(MC.player, moved))
			return false;
		return !avoidDrops.isChecked() || !MC.player.onGround()
			|| !MC.level.noCollision(MC.player, moved.move(0, -0.65, 0));
	}
	
	private void teleportAway(Threat threat)
	{
		teleportAway(threat, false);
	}
	
	private void teleportAway(Threat threat, boolean emergency)
	{
		if(MC.player == null || MC.player.connection == null)
			return;
		if(!emergency && shouldSuppressDodging(threat.pathStart))
			return;
		Vec3 destination = chooseDodgeDestination(threat);
		if(destination == null)
			return;
		
		MC.player.setPos(destination.x, destination.y, destination.z);
		MC.player.setDeltaMovement(Vec3.ZERO);
		MC.player.connection.send(new ServerboundMovePlayerPacket.PosRot(
			destination.x, destination.y, destination.z, MC.player.getYRot(),
			MC.player.getXRot(), false, false));
		for(int i = 1; i < teleportPackets.getValueI(); i++)
		{
			MC.player.connection.send(new ServerboundMovePlayerPacket.Pos(
				destination.x, destination.y, destination.z, false, false));
		}
		cooldownTicksLeft = teleportCooldown.getValueI();
		statusTicksLeft = cooldownTicksLeft + 2;
	}
	
	private void rememberPositions()
	{
		previousPositions.clear();
		for(Player player : MC.level.players())
			if(player != MC.player)
				previousPositions.put(player.getId(), player.position());
	}
	
	private void rememberPrimedSpears()
	{
		long now = System.currentTimeMillis();
		for(Player player : MC.level.players())
		{
			if(player == MC.player || isIgnoredPlayer(player)
				|| !player.isUsingItem())
				continue;
			ItemStack spear = player.getUseItem();
			if(isSpear(spear)
				&& player.getTicksUsingItem() >= getSpearReadyTicks(spear))
				primedSpearCues.put(player.getId(),
					now + PRIMED_SPEAR_MEMORY_MS);
		}
		primedSpearCues.values().removeIf(expiry -> expiry < now);
	}
	
	private void rememberChargingCreepers()
	{
		if(MC.level == null)
			return;
		
		long now = System.currentTimeMillis();
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(!(entity instanceof Creeper creeper) || !creeper.isAlive())
				continue;
			if(creeper.getSwellDir() > 0 || creeper.isIgnited()
				|| creeper.isPowered())
				chargingCreeperCues.put(creeper.getId(),
					now + CHARGING_CREEPER_MEMORY_MS);
		}
		chargingCreeperCues.values().removeIf(expiry -> expiry < now);
	}
	
	private boolean wasRecentlyPrimed(Player player)
	{
		Long expiry = primedSpearCues.get(player.getId());
		if(expiry != null && expiry >= System.currentTimeMillis())
			return true;
		ItemStack spear = player.getUseItem();
		return player.isUsingItem() && isSpear(spear)
			&& player.getTicksUsingItem() >= getSpearReadyTicks(spear);
	}
	
	private void prunePacketCues()
	{
		long cutoff = System.currentTimeMillis() - MACE_PACKET_CUE_MS;
		macePacketCues.values().removeIf(cue -> cue.timeMs < cutoff);
	}
	
	private Vec3 getObservedVelocity(Entity entity)
	{
		Vec3 networkVelocity = entity.getDeltaMovement();
		Vec3 previous = previousPositions.get(entity.getId());
		if(previous == null)
			return networkVelocity;
		Vec3 observed = entity.position().subtract(previous);
		return observed.lengthSqr() > networkVelocity.lengthSqr() ? observed
			: networkVelocity;
	}
	
	private boolean isWalkingToward(Vec3 targetPosition)
	{
		if(MC.options == null || MC.player == null)
			return false;
		if(movePauseMode.getSelected() == MovePauseMode.ANY_MOVEMENT_KEY)
			return MC.options.keyUp.isDown() || MC.options.keyDown.isDown()
				|| MC.options.keyLeft.isDown() || MC.options.keyRight.isDown();
		float forward = (MC.options.keyUp.isDown() ? 1 : 0)
			- (MC.options.keyDown.isDown() ? 1 : 0);
		float strafe = (MC.options.keyLeft.isDown() ? 1 : 0)
			- (MC.options.keyRight.isDown() ? 1 : 0);
		if(forward == 0 && strafe == 0)
			return false;
		
		double yaw = Math.toRadians(MC.player.getYRot());
		double sin = Math.sin(yaw);
		double cos = Math.cos(yaw);
		Vec3 inputDirection = new Vec3(-sin * forward + cos * strafe, 0,
			cos * forward + sin * strafe).normalize();
		Vec3 toPlayer = new Vec3(targetPosition.x - MC.player.getX(), 0,
			targetPosition.z - MC.player.getZ());
		return toPlayer.lengthSqr() > 1.0E-6
			&& inputDirection.dot(toPlayer.normalize()) > 0.55;
	}
	
	private boolean shouldSuppressDodging(Vec3 targetPosition)
	{
		if(shouldPauseOnShift() || shouldPauseOnLeftControl())
			return true;
		if(movePauseMode.getSelected() == MovePauseMode.ANY_MOVEMENT_KEY)
			return MC.options != null && (MC.options.keyUp.isDown()
				|| MC.options.keyDown.isDown() || MC.options.keyLeft.isDown()
				|| MC.options.keyRight.isDown());
		return isWalkingToward(targetPosition);
	}
	
	private boolean shouldPauseOnShift()
	{
		if(!pauseOnShift.isChecked() || MC.options == null)
			return false;
		
		return IKeyMapping.get(MC.options.keyShift).isActuallyDown();
	}
	
	private boolean shouldPauseOnLeftControl()
	{
		return pauseOnLeftControl.isChecked() && MC.getWindow() != null
			&& InputConstants.isKeyDown(MC.getWindow(),
				GLFW.GLFW_KEY_LEFT_CONTROL);
	}
	
	private void releaseSneakKey()
	{
		IKeyMapping sneakKey = IKeyMapping.get(MC.options.keyShift);
		sneakKey.setDown(false);
		if(MC.player != null)
			MC.player.setShiftKeyDown(false);
	}
	
	private boolean isIgnoredPlayer(Player player)
	{
		return player != null
			&& WurstClient.INSTANCE.getFriends().isFriend(player);
	}
	
	private boolean isHoldingMace(Player player)
	{
		return player.getMainHandItem().is(Items.MACE)
			|| player.getOffhandItem().is(Items.MACE);
	}
	
	private ItemStack getHeldSpear(Player player)
	{
		ItemStack mainHand = player.getMainHandItem();
		if(isSpear(mainHand))
			return mainHand;
		ItemStack offHand = player.getOffhandItem();
		return isSpear(offHand) ? offHand : ItemStack.EMPTY;
	}
	
	private boolean isSpear(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return id != null
			&& id.getPath().toLowerCase(Locale.ROOT).contains("spear");
	}
	
	private static boolean isSword(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return id != null
			&& id.getPath().toLowerCase(Locale.ROOT).endsWith("_sword");
	}
	
	private static boolean isAxe(ItemStack stack)
	{
		if(stack == null || stack.isEmpty())
			return false;
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		return id != null
			&& id.getPath().toLowerCase(Locale.ROOT).endsWith("_axe");
	}
	
	private int getSpearReadyTicks(ItemStack stack)
	{
		String name = stack.getItem().toString().toLowerCase(Locale.ROOT);
		if(name.contains("netherite"))
			return 7;
		if(name.contains("diamond"))
			return 9;
		if(name.contains("iron"))
			return 11;
		if(name.contains("copper"))
			return 12;
		if(name.contains("stone") || name.contains("golden"))
			return 13;
		return 14;
	}
	
	private static double distanceToLine(Vec3 point, Vec3 start, Vec3 end)
	{
		Vec3 line = end.subtract(start);
		double lengthSq = line.lengthSqr();
		if(lengthSq < 1.0E-8)
			return point.distanceTo(start);
		double t = point.subtract(start).dot(line) / lengthSq;
		return point.distanceTo(start.add(line.scale(t)));
	}
	
	private static double distanceToSegment(Vec3 point, Vec3 start, Vec3 end)
	{
		Vec3 segment = end.subtract(start);
		double lengthSq = segment.lengthSqr();
		double t = lengthSq < 1.0E-8 ? 0 : Math.max(0,
			Math.min(1, point.subtract(start).dot(segment) / lengthSq));
		return point.distanceTo(start.add(segment.scale(t)));
	}
	
	private static double horizontalDistanceToSegment(Vec3 point, Vec3 start,
		Vec3 end)
	{
		Vec3 segment = new Vec3(end.x - start.x, 0, end.z - start.z);
		Vec3 offset = new Vec3(point.x - start.x, 0, point.z - start.z);
		double lengthSq = segment.lengthSqr();
		double t = lengthSq < 1.0E-8 ? 0
			: Math.max(0, Math.min(1, offset.dot(segment) / lengthSq));
		Vec3 closest = start.add(segment.scale(t));
		return horizontalLength(point.subtract(closest));
	}
	
	private static double verticalDistanceToSegment(Vec3 point, Vec3 start,
		Vec3 end)
	{
		Vec3 segment = end.subtract(start);
		double lengthSq = segment.lengthSqr();
		double t = lengthSq < 1.0E-8 ? 0 : Math.max(0,
			Math.min(1, point.subtract(start).dot(segment) / lengthSq));
		return Math.abs(point.y - start.add(segment.scale(t)).y);
	}
	
	private static double horizontalDistanceSqr(Vec3 first, Vec3 second)
	{
		double x = first.x - second.x;
		double z = first.z - second.z;
		return x * x + z * z;
	}
	
	private static double horizontalDot(Vec3 first, Vec3 second)
	{
		return first.x * second.x + first.z * second.z;
	}
	
	private static double horizontalLength(Vec3 vector)
	{
		return Math.sqrt(vector.x * vector.x + vector.z * vector.z);
	}
	
	private static double square(double value)
	{
		return value * value;
	}
	
	private enum ThreatType
	{
		MACE("Mace!"),
		SPEAR("Spear!"),
		SWORD("Sword!"),
		AXE("Axe!"),
		ARROW("Arrow"),
		CRYSTAL("Crystal!"),
		MOB("Mob"),
		EMERGENCY("Emergency!"),
		SPACING("Spacing");
		
		private final String label;
		
		ThreatType(String label)
		{
			this.label = label;
		}
	}
	
	private record Threat(ThreatType type, Vec3 pathStart, Vec3 pathEnd,
		double urgency)
	{}
	
	private record MacePacketCue(Vec3 position, long timeMs)
	{}
	
	private record DodgeCandidate(Vec3 destination, double score)
	{}
	
	private enum WeaponType
	{
		SWORD(ThreatType.SWORD),
		AXE(ThreatType.AXE);
		
		private final ThreatType threatType;
		
		WeaponType(ThreatType threatType)
		{
			this.threatType = threatType;
		}
		
		private ItemStack getWeapon(Player player)
		{
			ItemStack mainHand = player.getMainHandItem();
			if(this == SWORD && isSword(mainHand)
				|| this == AXE && isAxe(mainHand))
				return mainHand;
			ItemStack offHand = player.getOffhandItem();
			if(this == SWORD && isSword(offHand)
				|| this == AXE && isAxe(offHand))
				return offHand;
			return ItemStack.EMPTY;
		}
	}
}
