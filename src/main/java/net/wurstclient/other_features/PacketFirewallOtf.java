/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.other_features;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.PosRot;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.StatusOnly;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.other_feature.OtherFeature;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.StringDropdownSetting;
import net.wurstclient.util.PacketUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.MovementMutationTracker;
import net.wurstclient.util.text.WText;

@SearchTags({"packet firewall", "movement packet validator", "movement packets",
	"anti kick"})
public final class PacketFirewallOtf extends OtherFeature
	implements PacketOutputListener, PacketInputListener, UpdateListener
{
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final long SETBACK_COOLDOWN_MS = 500;
	private static final long SETBACK_LOOKBACK_MS = 1500;
	private static final String HACK_PACKAGE_PREFIX = "net.wurstclient.hacks.";
	
	private boolean firewallEnabled;
	
	private final CheckboxSetting dropInvalidSetting =
		new CheckboxSetting("Drop invalid", false);
	private final CheckboxSetting clampPitchSetting =
		new CheckboxSetting("Clamp pitch", false);
	private final CheckboxSetting wrapYawSetting =
		new CheckboxSetting("Wrap yaw", false);
	private final CheckboxSetting dedupMovementSetting =
		new CheckboxSetting("Dedup movement", false);
	private final CheckboxSetting debugLoggingSetting =
		new CheckboxSetting("Debug logging", false);
	private final StringDropdownSetting disabledHacksSetting =
		new StringDropdownSetting("Temporarily disabled",
			WText.literal("Hacks currently suppressed by PacketFirewall."));
	private final ButtonSetting reEnableSelectedSetting = new ButtonSetting(
		"Re-enable selected",
		WText.literal(
			"Re-enables the selected hack and temporarily whitelists it while PacketFirewall stays enabled."),
		this::reEnableSelectedHack);
	private final ButtonSetting reEnableAllSetting = new ButtonSetting(
		"Re-enable all",
		WText.literal(
			"Re-enables all currently suppressed hacks and temporarily whitelists them."),
		this::reEnableAllHacks);
	private final StringDropdownSetting whitelistSetting =
		new StringDropdownSetting("Temporary whitelist", WText.literal(
			"Whitelisted hacks are not auto-disabled again until PacketFirewall is turned off."));
	private final ButtonSetting removeWhitelistSelectedSetting =
		new ButtonSetting("Remove selected whitelist",
			WText.literal(
				"Removes the selected hack from the temporary whitelist."),
			this::removeSelectedWhitelistEntry);
	private final ButtonSetting clearWhitelistSetting = new ButtonSetting(
		"Clear whitelist", WText.literal("Clears the temporary whitelist."),
		this::clearTemporaryWhitelist);
	
	private Vec3 lastGoodPos;
	private float lastGoodYaw;
	private float lastGoodPitch;
	private boolean lastGoodOnGround;
	private long lastGoodTickTime = -1;
	private boolean lastGoodValid;
	
	private long droppedNaN;
	private long sanitizedRot;
	private long sanitizedPos;
	private long sanitizedOnGround;
	private long dedupedMoves;
	
	private PendingMovement pendingMovement;
	private boolean sendingPending;
	
	private final LinkedHashSet<Hack> temporarilyDisabledHacks =
		new LinkedHashSet<>();
	private final LinkedHashSet<String> temporaryWhitelist =
		new LinkedHashSet<>();
	private final LinkedHashMap<Hack, String> suppressedReasons =
		new LinkedHashMap<>();
	private final LinkedHashMap<Hack, GrimPacketEvidence> recentGrimEvidence =
		new LinkedHashMap<>();
	private final LinkedHashMap<String, Hack> hackClassLookup =
		new LinkedHashMap<>();
	private boolean suppressingRiskyHacks;
	private long lastSetbackMs;
	
	public PacketFirewallOtf()
	{
		super("PacketFirewall",
			"description.wurst.other_feature.packet_firewall");
		
		addSetting(dropInvalidSetting);
		addSetting(clampPitchSetting);
		addSetting(wrapYawSetting);
		addSetting(dedupMovementSetting);
		addSetting(debugLoggingSetting);
		addSetting(disabledHacksSetting);
		addSetting(reEnableSelectedSetting);
		addSetting(reEnableAllSetting);
		addSetting(whitelistSetting);
		addSetting(removeWhitelistSelectedSetting);
		addSetting(clearWhitelistSetting);
		
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	public Category getCategory()
	{
		return Category.OTHER;
	}
	
	@Override
	public boolean isEnabled()
	{
		return firewallEnabled;
	}
	
	@Override
	public String getPrimaryAction()
	{
		return isEnabled() ? "Disable" : "Enable";
	}
	
	@Override
	public void doPrimaryAction()
	{
		firewallEnabled = !firewallEnabled;
		if(!firewallEnabled)
			restoreSuppressedHacks();
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(!isFirewallEnabled())
			return;
		
		boolean allowDedup = !sendingPending;
		Packet<?> packet = event.getPacket();
		GrimPacketSurface grimSurface = classifyGrimSurface(packet);
		if(grimSurface != null)
			recordGrimPacketEvidence(packet, grimSurface);
		
		if(packet instanceof ServerboundMovePlayerPacket movePacket)
		{
			recordMovementMutationAttribution(movePacket);
			handleMovePlayer(event, movePacket, allowDedup);
			return;
		}
		
		// Movement-adjacent packet that carries vehicle position/rotation.
		if(packet instanceof ServerboundMoveVehiclePacket vehiclePacket)
		{
			handleMoveVehicle(event, vehiclePacket, allowDedup);
			return;
		}
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		if(!isFirewallEnabled())
			return;
		
		if(!(event.getPacket() instanceof ClientboundPlayerPositionPacket))
			return;
		
		MC.execute(this::handleSetbackOnClientThread);
	}
	
	private void handleSetbackOnClientThread()
	{
		long now = System.currentTimeMillis();
		if(now - lastSetbackMs < SETBACK_COOLDOWN_MS)
			return;
		
		lastSetbackMs = now;
		suppressBySetback();
	}
	
	@Override
	public void onUpdate()
	{
		if(!isFirewallEnabled())
			restoreSuppressedHacks();
		
		refreshManualControlLists();
		
		if(!isFirewallEnabled())
			return;
		
		if(pendingMovement == null)
			return;
		
		long tick = getClientTick();
		if(pendingMovement.packet.tick <= tick)
			sendPendingMovement("dedup-tick");
	}
	
	private boolean isFirewallEnabled()
	{
		return firewallEnabled && WURST.isEnabled();
	}
	
	private void restoreSuppressedHacks()
	{
		if(!suppressingRiskyHacks && temporarilyDisabledHacks.isEmpty()
			&& suppressedReasons.isEmpty() && temporaryWhitelist.isEmpty()
			&& recentGrimEvidence.isEmpty())
			return;
		
		for(Hack hack : temporarilyDisabledHacks)
			if(!hack.isEnabled())
				hack.setEnabled(true);
			
		temporarilyDisabledHacks.clear();
		suppressedReasons.clear();
		temporaryWhitelist.clear();
		recentGrimEvidence.clear();
		MovementMutationTracker.clear();
		suppressingRiskyHacks = false;
	}
	
	private GrimPacketSurface classifyGrimSurface(Packet<?> packet)
	{
		if(packet instanceof ServerboundMovePlayerPacket)
			return GrimPacketSurface.PLAYER_FLYING;
		
		if(packet instanceof ServerboundMoveVehiclePacket)
			return GrimPacketSurface.VEHICLE_MOVE;
		
		if(packet instanceof ServerboundInteractPacket)
			return GrimPacketSurface.INTERACT_ENTITY;
		
		if(packet instanceof ServerboundPlayerCommandPacket)
			return GrimPacketSurface.ENTITY_ACTION;
		
		if(packet instanceof ServerboundPlayerActionPacket)
			return GrimPacketSurface.PLAYER_DIGGING;
		
		if(packet instanceof ServerboundUseItemOnPacket)
			return GrimPacketSurface.PLAYER_BLOCK_PLACEMENT;
		
		if(packet instanceof ServerboundSwingPacket)
			return GrimPacketSurface.ANIMATION;
		
		if(packet instanceof ServerboundSetCarriedItemPacket)
			return GrimPacketSurface.HELD_ITEM_CHANGE;
		
		return null;
	}
	
	private void recordGrimPacketEvidence(Packet<?> packet,
		GrimPacketSurface surface)
	{
		long now = System.currentTimeMillis();
		pruneOldEvidence(now);
		
		SenderResolution sender = resolveSenderHackFromStack();
		if(sender == null)
			return;
		
		GrimPacketEvidence evidence = new GrimPacketEvidence(now, surface,
			packet.getClass().getSimpleName(), sender.stackFrame());
		recentGrimEvidence.put(sender.hack(), evidence);
		
		if(debugLoggingSetting.isChecked())
			LOGGER.info("[PacketFirewall] observed {} from {} ({})",
				packet.getClass().getSimpleName(), sender.hack().getName(),
				surface.debugPath());
	}
	
	private void recordMovementMutationAttribution(
		ServerboundMovePlayerPacket packet)
	{
		long now = System.currentTimeMillis();
		pruneOldEvidence(now);
		
		LinkedHashMap<Hack, MovementMutationTracker.MutationEvidence> mutations =
			MovementMutationTracker.getRecentMutations(SETBACK_LOOKBACK_MS);
		for(var entry : mutations.entrySet())
		{
			Hack hack = entry.getKey();
			MovementMutationTracker.MutationEvidence mutation =
				entry.getValue();
			if(hack == null || mutation == null || !hack.isEnabled()
				|| hack == WURST.getHax().panicHack)
				continue;
			
			long timestamp = mutation.timestampMs();
			if(now - timestamp > SETBACK_LOOKBACK_MS)
				continue;
			
			String source =
				mutation.source() + ", mutationSender=" + mutation.stackFrame();
			GrimPacketEvidence evidence = new GrimPacketEvidence(timestamp,
				GrimPacketSurface.PLAYER_FLYING,
				packet.getClass().getSimpleName(), source);
			recentGrimEvidence.put(hack, evidence);
			
			if(debugLoggingSetting.isChecked())
				LOGGER.info(
					"[PacketFirewall] movement mutation attribution {} -> {} ({})",
					packet.getClass().getSimpleName(), hack.getName(), source);
		}
	}
	
	private void suppressBySetback()
	{
		long now = System.currentTimeMillis();
		pruneOldEvidence(now);
		
		ArrayList<CandidateSuppression> candidates = new ArrayList<>();
		for(var entry : recentGrimEvidence.entrySet())
		{
			Hack hack = entry.getKey();
			GrimPacketEvidence evidence = entry.getValue();
			if(hack == null || evidence == null)
				continue;
			
			if(now - evidence.timestampMs() > SETBACK_LOOKBACK_MS)
				continue;
			
			if(!hack.isEnabled() || hack == WURST.getHax().panicHack)
				continue;
			
			candidates.add(new CandidateSuppression(hack, evidence));
		}
		
		if(candidates.isEmpty())
		{
			if(debugLoggingSetting.isChecked())
				LOGGER.info(
					"[PacketFirewall] setback seen, but no Grim-attributed packet/movement evidence found in {}ms; no hack auto-disabled.",
					SETBACK_LOOKBACK_MS);
			return;
		}
		
		candidates.sort((a, b) -> Long.compare(b.evidence().timestampMs(),
			a.evidence().timestampMs()));
		
		for(CandidateSuppression candidate : candidates)
		{
			Hack hack = candidate.hack();
			GrimPacketEvidence evidence = candidate.evidence();
			String reason = "grim-core setback after "
				+ evidence.surface().grimPacketType() + " ("
				+ evidence.surface().debugPath() + "), packet="
				+ evidence.packetName() + ", sender=" + evidence.stackFrame();
			
			if(!suppressHackTemporarily(hack, reason))
				continue;
			
			suppressingRiskyHacks = !temporarilyDisabledHacks.isEmpty();
			
			if(debugLoggingSetting.isChecked())
				LOGGER.info("[PacketFirewall] auto-disabled {}: {}",
					hack.getName(), reason);
			return;
		}
	}
	
	private void pruneOldEvidence(long now)
	{
		recentGrimEvidence.entrySet().removeIf(
			entry -> now - entry.getValue().timestampMs() > SETBACK_LOOKBACK_MS
				|| !entry.getKey().isEnabled());
	}
	
	private boolean suppressHackTemporarily(Hack hack, String reason)
	{
		if(hack == null || !hack.isEnabled())
			return false;
		
		if(temporaryWhitelist.contains(hack.getName()))
		{
			if(debugLoggingSetting.isChecked())
				LOGGER.info("[PacketFirewall] whitelist skipped {} ({})",
					hack.getName(), reason);
			return false;
		}
		
		temporarilyDisabledHacks.add(hack);
		suppressedReasons.put(hack, reason);
		hack.setEnabled(false);
		ChatUtils.message("[PacketFirewall] Temporarily disabled "
			+ hack.getName() + " (" + reason + ").");
		return true;
	}
	
	private void refreshManualControlLists()
	{
		List<String> disabledNames =
			temporarilyDisabledHacks.stream().map(Hack::getName).toList();
		disabledHacksSetting.setOptions(disabledNames);
		whitelistSetting.setOptions(temporaryWhitelist);
	}
	
	private void reEnableSelectedHack()
	{
		String selected = disabledHacksSetting.getSelected();
		if(selected == null || selected.isBlank())
			return;
		
		reEnableAndWhitelist(selected);
	}
	
	private void reEnableAllHacks()
	{
		for(Hack hack : new LinkedHashSet<>(temporarilyDisabledHacks))
			reEnableAndWhitelist(hack.getName());
	}
	
	private void reEnableAndWhitelist(String hackName)
	{
		Hack hack = WURST.getHax().getHackByName(hackName);
		if(hack == null)
			return;
		
		String reason = suppressedReasons.getOrDefault(hack, "manual override");
		temporarilyDisabledHacks.remove(hack);
		suppressedReasons.remove(hack);
		temporaryWhitelist.add(hack.getName());
		
		if(!hack.isEnabled())
			hack.setEnabled(true);
		
		ChatUtils.message("[PacketFirewall] Re-enabled " + hack.getName()
			+ " and added to temporary whitelist (" + reason + ").");
	}
	
	private void removeSelectedWhitelistEntry()
	{
		String selected = whitelistSetting.getSelected();
		if(selected == null || selected.isBlank())
			return;
		
		if(temporaryWhitelist.remove(selected))
			ChatUtils.message("[PacketFirewall] Removed " + selected
				+ " from temporary whitelist.");
	}
	
	private void clearTemporaryWhitelist()
	{
		if(temporaryWhitelist.isEmpty())
			return;
		
		temporaryWhitelist.clear();
		ChatUtils.message("[PacketFirewall] Cleared temporary whitelist.");
	}
	
	private void handleMovePlayer(PacketOutputEvent event,
		ServerboundMovePlayerPacket packet, boolean allowDedup)
	{
		MovementSnapshot original = snapshot(packet);
		MovementSnapshot sanitized = sanitizeSnapshot(original, packet);
		
		if(sanitized.dropPacket)
		{
			event.cancel();
			logDrop("invalid position values", packet, original, sanitized);
			return;
		}
		
		ServerboundMovePlayerPacket newPacket =
			applySanitized(packet, sanitized);
		
		if(sanitized.sanitizedRot)
			sanitizedRot++;
		if(sanitized.sanitizedPos)
			sanitizedPos++;
		if(sanitized.sanitizedOnGround)
			sanitizedOnGround++;
		
		if(sanitized.hasChanges())
			logSanitize(packet, original, sanitized);
		
		long tick = getClientTick();
		if(allowDedup && queueForDedup(event, newPacket, sanitized, tick))
			return;
		
		if(newPacket != packet)
			event.setPacket(newPacket);
		
		updateLastGoodFromMovePlayer(newPacket, sanitized);
	}
	
	private void handleMoveVehicle(PacketOutputEvent event,
		ServerboundMoveVehiclePacket packet, boolean allowDedup)
	{
		VehicleSnapshot original = snapshot(packet);
		VehicleSnapshot sanitized = sanitizeSnapshot(original);
		
		if(sanitized.dropPacket)
		{
			event.cancel();
			logDrop("invalid vehicle position values", packet, original,
				sanitized);
			return;
		}
		
		ServerboundMoveVehiclePacket newPacket =
			applySanitized(packet, sanitized);
		
		if(sanitized.sanitizedRot)
			sanitizedRot++;
		if(sanitized.sanitizedPos)
			sanitizedPos++;
		
		if(sanitized.hasChanges())
			logSanitize(packet, original, sanitized);
		
		long tick = getClientTick();
		if(allowDedup && queueForDedup(event, newPacket, sanitized, tick))
			return;
		
		if(newPacket != packet)
			event.setPacket(newPacket);
		
		updateLastGoodFromVehicle(sanitized);
	}
	
	private boolean queueForDedup(PacketOutputEvent event, Packet<?> packet,
		Snapshot snapshot, long tick)
	{
		if(!dedupMovementSetting.isChecked())
			return false;
		
		boolean replacedSameTick =
			pendingMovement != null && pendingMovement.packet.tick == tick;
		if(pendingMovement != null && pendingMovement.packet.tick != tick)
			sendPendingMovement("dedup-tick-boundary");
		
		MovementPacket pending = new MovementPacket(packet, snapshot, tick);
		pendingMovement = new PendingMovement(pending);
		event.cancel();
		if(replacedSameTick)
		{
			dedupedMoves++;
			logDedup(packet);
		}
		return true;
	}
	
	private void sendPendingMovement(String reason)
	{
		if(pendingMovement == null || MC.getConnection() == null)
			return;
		
		MovementPacket packet = pendingMovement.packet;
		pendingMovement = null;
		
		try
		{
			sendingPending = true;
			MC.getConnection().send(packet.packet);
			
		}finally
		{
			sendingPending = false;
		}
		
		if(debugLoggingSetting.isChecked())
			LOGGER.info("[PacketFirewall] sent deduped {} ({})",
				packet.packet.getClass().getSimpleName(), reason);
	}
	
	private MovementSnapshot snapshot(ServerboundMovePlayerPacket packet)
	{
		boolean hasPos = packet instanceof Pos || packet instanceof PosRot;
		boolean hasRot = packet instanceof Rot || packet instanceof PosRot;
		
		double x = hasPos ? packet.getX(0) : 0;
		double y = hasPos ? packet.getY(0) : 0;
		double z = hasPos ? packet.getZ(0) : 0;
		float yaw = hasRot ? packet.getYRot(0) : 0;
		float pitch = hasRot ? packet.getXRot(0) : 0;
		
		return new MovementSnapshot(hasPos, hasRot, x, y, z, yaw, pitch,
			packet.isOnGround(), packet.horizontalCollision());
	}
	
	private MovementSnapshot sanitizeSnapshot(MovementSnapshot original,
		ServerboundMovePlayerPacket packet)
	{
		MovementSnapshot sanitized = original.copy();
		
		if(original.hasPos && !isFinitePosition(original))
		{
			droppedNaN++;
			if(dropInvalidSetting.isChecked())
			{
				sanitized.dropPacket = true;
				return sanitized;
			}
			
			Vec3 fallback = getFallbackPos();
			sanitized.x = fallback.x;
			sanitized.y = fallback.y;
			sanitized.z = fallback.z;
			sanitized.sanitizedPos = true;
		}
		
		if(original.hasRot)
		{
			float yaw = original.yaw;
			float pitch = original.pitch;
			
			if(!Float.isFinite(yaw))
			{
				yaw = getFallbackYaw();
				sanitized.sanitizedRot = true;
			}
			
			if(wrapYawSetting.isChecked())
			{
				float wrapped = Mth.wrapDegrees(yaw);
				if(wrapped != yaw)
					sanitized.sanitizedRot = true;
				yaw = wrapped;
			}
			
			if(!Float.isFinite(pitch))
			{
				pitch = getFallbackPitch();
				sanitized.sanitizedRot = true;
			}
			
			if(clampPitchSetting.isChecked())
			{
				float clamped = Mth.clamp(pitch, -90.0F, 90.0F);
				if(clamped != pitch)
					sanitized.sanitizedRot = true;
				pitch = clamped;
			}
			
			sanitized.yaw = yaw;
			sanitized.pitch = pitch;
		}
		
		if(packet instanceof StatusOnly && !lastGoodValid)
		{
			sanitized.onGround = lastGoodOnGround;
			sanitized.sanitizedOnGround = true;
		}
		
		return sanitized;
	}
	
	private ServerboundMovePlayerPacket applySanitized(
		ServerboundMovePlayerPacket packet, MovementSnapshot sanitized)
	{
		ServerboundMovePlayerPacket updated = packet;
		
		if(sanitized.hasPos && sanitized.sanitizedPos)
			updated = PacketUtils.modifyPosition(updated, sanitized.x,
				sanitized.y, sanitized.z);
		
		if(sanitized.hasRot && sanitized.sanitizedRot)
			updated = PacketUtils.modifyRotation(updated, sanitized.yaw,
				sanitized.pitch);
		
		if(sanitized.sanitizedOnGround)
			updated = PacketUtils.modifyOnGround(updated, sanitized.onGround);
		
		return updated;
	}
	
	private VehicleSnapshot snapshot(ServerboundMoveVehiclePacket packet)
	{
		Vec3 position = packet.position();
		return new VehicleSnapshot(position.x, position.y, position.z,
			packet.yRot(), packet.xRot(), packet.onGround());
	}
	
	private VehicleSnapshot sanitizeSnapshot(VehicleSnapshot original)
	{
		VehicleSnapshot sanitized = original.copy();
		
		if(!isFinitePosition(original))
		{
			droppedNaN++;
			if(dropInvalidSetting.isChecked())
			{
				sanitized.dropPacket = true;
				return sanitized;
			}
			
			Vec3 fallback = getFallbackPos();
			sanitized.x = fallback.x;
			sanitized.y = fallback.y;
			sanitized.z = fallback.z;
			sanitized.sanitizedPos = true;
		}
		
		float yaw = original.yaw;
		float pitch = original.pitch;
		
		if(!Float.isFinite(yaw))
		{
			yaw = getFallbackYaw();
			sanitized.sanitizedRot = true;
		}
		
		if(wrapYawSetting.isChecked())
		{
			float wrapped = Mth.wrapDegrees(yaw);
			if(wrapped != yaw)
				sanitized.sanitizedRot = true;
			yaw = wrapped;
		}
		
		if(!Float.isFinite(pitch))
		{
			pitch = getFallbackPitch();
			sanitized.sanitizedRot = true;
		}
		
		if(clampPitchSetting.isChecked())
		{
			float clamped = Mth.clamp(pitch, -90.0F, 90.0F);
			if(clamped != pitch)
				sanitized.sanitizedRot = true;
			pitch = clamped;
		}
		
		sanitized.yaw = yaw;
		sanitized.pitch = pitch;
		
		return sanitized;
	}
	
	private ServerboundMoveVehiclePacket applySanitized(
		ServerboundMoveVehiclePacket packet, VehicleSnapshot sanitized)
	{
		if(!sanitized.hasChanges())
			return packet;
		
		return new ServerboundMoveVehiclePacket(
			new Vec3(sanitized.x, sanitized.y, sanitized.z), sanitized.yaw,
			sanitized.pitch, sanitized.onGround);
	}
	
	private void updateLastGoodFromMovePlayer(
		ServerboundMovePlayerPacket packet, MovementSnapshot sanitized)
	{
		if(sanitized.hasPos && !sanitized.dropPacket)
			lastGoodPos = new Vec3(sanitized.x, sanitized.y, sanitized.z);
		
		if(sanitized.hasRot && !sanitized.dropPacket)
		{
			lastGoodYaw = sanitized.yaw;
			lastGoodPitch = sanitized.pitch;
		}
		
		if(!sanitized.dropPacket)
		{
			lastGoodOnGround = sanitized.onGround;
			lastGoodTickTime = getClientTick();
			lastGoodValid = true;
		}
	}
	
	private void updateLastGoodFromVehicle(VehicleSnapshot snapshot)
	{
		if(snapshot.dropPacket)
			return;
		
		lastGoodPos = new Vec3(snapshot.x, snapshot.y, snapshot.z);
		lastGoodYaw = snapshot.yaw;
		lastGoodPitch = snapshot.pitch;
		lastGoodOnGround = snapshot.onGround;
		lastGoodTickTime = getClientTick();
		lastGoodValid = true;
	}
	
	private Vec3 getFallbackPos()
	{
		if(lastGoodValid && lastGoodPos != null)
			return lastGoodPos;
		if(MC.player != null)
			return MC.player.position();
		return Vec3.ZERO;
	}
	
	private float getFallbackYaw()
	{
		if(lastGoodValid)
			return lastGoodYaw;
		if(MC.player != null)
			return MC.player.getYRot();
		return 0.0F;
	}
	
	private float getFallbackPitch()
	{
		if(lastGoodValid)
			return lastGoodPitch;
		if(MC.player != null)
			return MC.player.getXRot();
		return 0.0F;
	}
	
	private long getClientTick()
	{
		if(MC.level != null)
			return MC.level.getGameTime();
		if(MC.player != null)
			return MC.player.tickCount;
		return System.currentTimeMillis();
	}
	
	private boolean isFinitePosition(MovementSnapshot snapshot)
	{
		return Double.isFinite(snapshot.x) && Double.isFinite(snapshot.y)
			&& Double.isFinite(snapshot.z);
	}
	
	private boolean isFinitePosition(VehicleSnapshot snapshot)
	{
		return Double.isFinite(snapshot.x) && Double.isFinite(snapshot.y)
			&& Double.isFinite(snapshot.z);
	}
	
	private void logDrop(String reason, Packet<?> packet, Snapshot original,
		Snapshot sanitized)
	{
		String packetName = packet.getClass().getSimpleName();
		if(debugLoggingSetting.isChecked())
		{
			LOGGER.warn(
				"[PacketFirewall] dropped {}: {} | original={} sanitized={} sender={}",
				packetName, reason, original.describe(), sanitized.describe(),
				resolveSenderInfo());
			return;
		}
		
		LOGGER.warn("[PacketFirewall] dropped {}: {}", packetName, reason);
	}
	
	private void logSanitize(Packet<?> packet, Snapshot original,
		Snapshot sanitized)
	{
		String packetName = packet.getClass().getSimpleName();
		if(debugLoggingSetting.isChecked())
		{
			LOGGER.info(
				"[PacketFirewall] sanitized {} | original={} sanitized={} sender={}",
				packetName, original.describe(), sanitized.describe(),
				resolveSenderInfo());
			return;
		}
		
		LOGGER.info("[PacketFirewall] sanitized {}", packetName);
	}
	
	private void logDedup(Packet<?> packet)
	{
		if(!debugLoggingSetting.isChecked())
			return;
		
		LOGGER.info("[PacketFirewall] deduped {}", packet.getClass().getName());
	}
	
	private String resolveSenderInfo()
	{
		SenderResolution sender = resolveSenderHackFromStack();
		if(sender != null)
			return "hack=" + sender.hack().getName() + " via "
				+ sender.stackFrame();
		
		return "stack=" + shortStackTrace();
	}
	
	private SenderResolution resolveSenderHackFromStack()
	{
		rebuildHackClassLookup();
		
		StackTraceElement[] trace = Thread.currentThread().getStackTrace();
		for(StackTraceElement element : trace)
		{
			String className = element.getClassName();
			if(!className.startsWith(HACK_PACKAGE_PREFIX))
				continue;
			
			Hack hack = getHackFromClassName(className);
			if(hack == null)
				continue;
			
			if(!hack.isEnabled() || hack == WURST.getHax().panicHack)
				continue;
			
			String frame = className + "." + element.getMethodName() + ":"
				+ element.getLineNumber();
			return new SenderResolution(hack, frame);
		}
		
		return null;
	}
	
	private void rebuildHackClassLookup()
	{
		if(hackClassLookup.size() == WURST.getHax().countHax())
			return;
		
		hackClassLookup.clear();
		for(Hack hack : WURST.getHax().getAllHax())
			hackClassLookup.put(hack.getClass().getName(), hack);
	}
	
	private Hack getHackFromClassName(String className)
	{
		Hack direct = hackClassLookup.get(className);
		if(direct != null)
			return direct;
		
		int index = className.indexOf('$');
		if(index < 0)
			return null;
		
		return hackClassLookup.get(className.substring(0, index));
	}
	
	private String shortStackTrace()
	{
		StackTraceElement[] trace = Thread.currentThread().getStackTrace();
		StringBuilder builder = new StringBuilder();
		int added = 0;
		for(StackTraceElement element : trace)
		{
			String className = element.getClassName();
			if(className.contains(PacketFirewallOtf.class.getName()))
				continue;
			if(className.startsWith("net.wurstclient.event."))
				continue;
			if(className.startsWith("java.lang.Thread"))
				continue;
			
			if(added > 0)
				builder.append(" <- ");
			builder.append(element.getClassName()).append(".")
				.append(element.getMethodName()).append(":")
				.append(element.getLineNumber());
			added++;
			if(added >= 3)
				break;
		}
		return builder.toString();
	}
	
	private enum GrimPacketSurface
	{
		PLAYER_FLYING("PacketType.Play.Client.PLAYER_*",
			"checks/impl/{movement,flight,groundspoof,timer,badpackets,packetorder}"),
		VEHICLE_MOVE("PacketType.Play.Client.VEHICLE_MOVE",
			"checks/impl/{vehicle,movement/SetbackBlocker,badpackets/BadPacketsR}"),
		INTERACT_ENTITY("PacketType.Play.Client.INTERACT_ENTITY",
			"checks/impl/{combat/Reach,combat/MultiInteractA/B,movement/SetbackBlocker,badpackets/BadPacketsC/T}"),
		ENTITY_ACTION("PacketType.Play.Client.ENTITY_ACTION",
			"checks/impl/{elytra,sprint,badpackets,misc/Post}"),
		PLAYER_DIGGING("PacketType.Play.Client.PLAYER_DIGGING",
			"checks/impl/{breaking,multiactions,misc/Post,packetorder}"),
		PLAYER_BLOCK_PLACEMENT(
			"PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT|USE_ITEM",
			"checks/impl/{multiactions,packetorder,misc/Post,scaffolding,badpackets/exploit}"),
		ANIMATION("PacketType.Play.Client.ANIMATION",
			"checks/impl/{breaking/NoSwingBreak,misc/Post,multiactions,packetorder}"),
		HELD_ITEM_CHANGE("PacketType.Play.Client.HELD_ITEM_CHANGE",
			"checks/impl/{badpackets/BadPacketsA,misc/Post,packetorder}");
		
		private final String grimPacketType;
		private final String debugPath;
		
		GrimPacketSurface(String grimPacketType, String debugPath)
		{
			this.grimPacketType = grimPacketType;
			this.debugPath = debugPath;
		}
		
		private String grimPacketType()
		{
			return grimPacketType;
		}
		
		private String debugPath()
		{
			return debugPath;
		}
	}
	
	private record SenderResolution(Hack hack, String stackFrame)
	{}
	
	private record GrimPacketEvidence(long timestampMs,
		GrimPacketSurface surface, String packetName, String stackFrame)
	{}
	
	private record CandidateSuppression(Hack hack, GrimPacketEvidence evidence)
	{}
	
	private record MovementPacket(Packet<?> packet, Snapshot snapshot,
		long tick)
	{}
	
	private record PendingMovement(MovementPacket packet)
	{}
	
	private interface Snapshot
	{
		String describe();
	}
	
	private static final class MovementSnapshot implements Snapshot
	{
		private final boolean hasPos;
		private final boolean hasRot;
		private double x;
		private double y;
		private double z;
		private float yaw;
		private float pitch;
		private boolean onGround;
		private final boolean horizontalCollision;
		private boolean dropPacket;
		private boolean sanitizedPos;
		private boolean sanitizedRot;
		private boolean sanitizedOnGround;
		
		private MovementSnapshot(boolean hasPos, boolean hasRot, double x,
			double y, double z, float yaw, float pitch, boolean onGround,
			boolean horizontalCollision)
		{
			this.hasPos = hasPos;
			this.hasRot = hasRot;
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.onGround = onGround;
			this.horizontalCollision = horizontalCollision;
		}
		
		private MovementSnapshot copy()
		{
			return new MovementSnapshot(hasPos, hasRot, x, y, z, yaw, pitch,
				onGround, horizontalCollision);
		}
		
		private boolean hasChanges()
		{
			return sanitizedPos || sanitizedRot || sanitizedOnGround;
		}
		
		@Override
		public String describe()
		{
			StringBuilder builder = new StringBuilder();
			builder.append("pos=");
			if(hasPos)
				builder.append(x).append(",").append(y).append(",").append(z);
			else
				builder.append("n/a");
			builder.append(" rot=");
			if(hasRot)
				builder.append(yaw).append(",").append(pitch);
			else
				builder.append("n/a");
			builder.append(" onGround=").append(onGround);
			builder.append(" hCollision=").append(horizontalCollision);
			return builder.toString();
		}
	}
	
	private static final class VehicleSnapshot implements Snapshot
	{
		private double x;
		private double y;
		private double z;
		private float yaw;
		private float pitch;
		private boolean onGround;
		private boolean dropPacket;
		private boolean sanitizedPos;
		private boolean sanitizedRot;
		
		private VehicleSnapshot(double x, double y, double z, float yaw,
			float pitch, boolean onGround)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.yaw = yaw;
			this.pitch = pitch;
			this.onGround = onGround;
		}
		
		private VehicleSnapshot copy()
		{
			return new VehicleSnapshot(x, y, z, yaw, pitch, onGround);
		}
		
		private boolean hasChanges()
		{
			return sanitizedPos || sanitizedRot;
		}
		
		@Override
		public String describe()
		{
			return "pos=" + x + "," + y + "," + z + " rot=" + yaw + "," + pitch
				+ " onGround=" + onGround;
		}
	}
}
