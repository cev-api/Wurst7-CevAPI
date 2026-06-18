/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.google.gson.JsonObject;
import com.mojang.math.Axis;
import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.util.Mth;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.CameraTransformViewBobbingListener.CameraTransformViewBobbingEvent;
import java.lang.reflect.Method;
import net.minecraft.client.multiplayer.ServerData;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.RightClickListener;
import net.wurstclient.events.RightClickListener.RightClickEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.util.BlockUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.DisconnectContext;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.OwnerResolver;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.json.JsonUtils;

@SearchTags({"Pearl ESP", "ender pearl warning", "PearlESP"})
public class PearlEspHack extends Hack
	implements UpdateListener, CameraTransformViewBobbingListener,
	PacketInputListener, RightClickListener, RenderListener
{
	private static final double LANDING_BOX_RADIUS = 0.35;
	private static final double HAND_BOX_RADIUS = 0.18;
	private static final long STASIS_LABEL_STICKY_MS = 1200;
	private static final long OWN_THROW_INFERENCE_MS = 2000;
	private static final double OWN_THROW_INFERENCE_RADIUS = 12.0;
	
	private final ColorSetting color = new ColorSetting("Color",
		"Highlight color for pearls, landing boxes and trajectory.",
		new Color(0xFF00FF));
	private final CheckboxSetting highlightHeld = new CheckboxSetting(
		"Highlight held pearls",
		"Highlight players who are currently holding an ender pearl.", true);
	private final CheckboxSetting showPlayerPrediction = new CheckboxSetting(
		"Show player prediction",
		"Show predicted trajectory while the local player is holding a pearl.",
		true);
	private final CheckboxSetting showTracerLine =
		new CheckboxSetting("Show tracer line",
			"Draw trajectory lines for pearl predictions.", true);
	private final CheckboxSetting showStasisOwners = new CheckboxSetting(
		"Show stasis owners",
		"Show the owner name above visible ender pearls sitting in stasis chambers.",
		true);
	private final CheckboxSetting stasisEspColor =
		new CheckboxSetting("Stasis ESP color",
			"Use a separate ESP color for pearls sitting in stasis chambers.",
			true);
	private final ColorSetting stasisColor = new ColorSetting("Stasis color",
		"Highlight color for pearls in stasis chambers.", new Color(0x55FFAA));
	private final CheckboxSetting stasisFlash =
		new CheckboxSetting("Stasis flash",
			"Make ESP for pearls in stasis chambers pulse with a smooth fade.",
			false);
	private final SliderSetting tracerThickness = new SliderSetting(
		"Tracer thickness", 2, 0.5, 8, 0.1, ValueDisplay.DECIMAL);
	private final CheckboxSetting tracerFlash = new CheckboxSetting(
		"Tracer flash", "Make tracers pulse with a smooth fade.", false);
	private final CheckboxSetting chatAlerts =
		new CheckboxSetting("Chat alerts",
			"Show a chat alert when a new ender pearl is detected.", false);
	private final CheckboxSetting soundAlerts =
		new CheckboxSetting("Sound alerts",
			"Play a sound alert when a new ender pearl is detected.", false);
	private final EnumSetting<AlertSound> alertSound =
		new EnumSetting<>("Alert sound",
			"Sound used for PearlESP alerts (includes all note-block sounds).",
			AlertSound.values(), AlertSound.NOTE_BLOCK_CHIME);
	private final CheckboxSetting persistOwnership = new CheckboxSetting(
		"Persist ownership",
		"Save pearl owner mappings per-server so ownership survives game restarts.",
		true);
	private final ButtonSetting clearOwnershipLog =
		new ButtonSetting("Clear ownership log",
			"Delete stored pearl ownership data for the current server.",
			this::clearOwnershipLog);
	private final CheckboxSetting inferOwnership = new CheckboxSetting(
		"Infer ownership",
		"Infer pearl owner from join timing when a player joins and a pearl appears within a tight window.",
		true);
	private final SliderSetting inferenceWindowMs =
		new SliderSetting("Inference window", 32, 2, 250, 1,
			ValueDisplay.INTEGER.withSuffix(" ms"));
	
	private final ArrayList<TrackedPearl> pearls = new ArrayList<>();
	private final ArrayList<HeldPearl> holders = new ArrayList<>();
	private final HashSet<UUID> alertedPearls = new HashSet<>();
	private final Map<UUID, StasisLabel> stasisLabels = new HashMap<>();
	private final Map<UUID, UUID> pearlOwnerUuids = new HashMap<>();
	private final Map<UUID, String> pearlOwnerLabels = new HashMap<>();
	private final Map<UUID, Integer> pearlSpawnOwnerIds = new HashMap<>();
	private final Map<Integer, EntityIdBinding> entityIdBindings =
		new HashMap<>();
	private final Map<UUID, PlayerIdentity> playerIdentities = new HashMap<>();
	private final Map<UUID, PearlIdentity> pearlIdentities = new HashMap<>();
	private final Map<UUID, String> knownPlayerNames = new HashMap<>();
	private final Map<UUID, Long> playerJoinTimes = new HashMap<>();
	private final Map<UUID, Long> playerLeaveTimes = new HashMap<>();
	private boolean hasOwnPearl;
	private long ownPearlSuppressUntilMs;
	private long lastOwnPearlUseMs;
	private Vec3 lastOwnPearlUsePos;
	
	// Per-frame tracking for disconnect inference
	private HashSet<UUID> previousPearlUuids = new HashSet<>();
	private final Map<UUID, Vec3> lastPearlPositions = new HashMap<>();
	private final Map<UUID, StasisLabel> offlineStasisLabels = new HashMap<>();
	private static final long OFFLINE_STASIS_STICKY_MS = 8000;
	
	// Per-server file persistence
	private boolean dirty;
	private long lastSaveAt;
	private Path currentFile;
	private String lastServerKey;
	
	public PearlEspHack()
	{
		super("PearlESP");
		setCategory(Category.RENDER);
		addSetting(color);
		addSetting(highlightHeld);
		addSetting(showPlayerPrediction);
		addSetting(showTracerLine);
		addSetting(showStasisOwners);
		addSetting(stasisEspColor);
		addSetting(stasisColor);
		addSetting(stasisFlash);
		addSetting(tracerThickness);
		addSetting(tracerFlash);
		addSetting(chatAlerts);
		addSetting(soundAlerts);
		addSetting(alertSound);
		addSetting(persistOwnership);
		addSetting(clearOwnershipLog);
		addSetting(inferOwnership);
		addSetting(inferenceWindowMs);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
		EVENTS.add(RightClickListener.class, this);
		EVENTS.add(RenderListener.class, this);
		loadOwnershipData();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		EVENTS.remove(RightClickListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		saveOwnershipData(true);
		pearls.clear();
		holders.clear();
		alertedPearls.clear();
		stasisLabels.clear();
		pearlOwnerUuids.clear();
		pearlOwnerLabels.clear();
		pearlSpawnOwnerIds.clear();
		entityIdBindings.clear();
		playerIdentities.clear();
		pearlIdentities.clear();
		knownPlayerNames.clear();
		playerJoinTimes.clear();
		playerLeaveTimes.clear();
		previousPearlUuids.clear();
		lastPearlPositions.clear();
		offlineStasisLabels.clear();
		hasOwnPearl = false;
		ownPearlSuppressUntilMs = 0L;
		lastOwnPearlUseMs = 0L;
		lastOwnPearlUsePos = null;
		dirty = false;
		currentFile = null;
		lastServerKey = null;
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent event)
	{
		rememberPacket(event.getPacket());
	}
	
	@Override
	public void onRightClick(RightClickEvent event)
	{
		if(MC.player == null)
			return;
		
		if(!isHoldingPearlAnyHand(MC.player))
			return;
		
		lastOwnPearlUseMs = System.currentTimeMillis();
		lastOwnPearlUsePos = MC.player.position();
	}
	
	private void rememberPacket(Packet<?> rawPacket)
	{
		if(rawPacket instanceof ClientboundBundlePacket bundle)
		{
			for(Packet<?> subPacket : bundle.subPackets())
				rememberPacket(subPacket);
			return;
		}
		
		if(rawPacket instanceof ClientboundPlayerInfoUpdatePacket packet)
		{
			rememberPlayerInfo(packet);
			return;
		}
		
		if(rawPacket instanceof ClientboundRemoveEntitiesPacket remove)
		{
			rememberEntityRemoval(remove);
			return;
		}
		
		if(!(rawPacket instanceof ClientboundAddEntityPacket packet))
			return;
		
		if(packet.getType() == EntityType.PLAYER)
		{
			rememberPlayerSpawn(packet);
			return;
		}
		
		if(packet.getType() != EntityType.ENDER_PEARL)
			return;
		
		rememberPearlSpawn(packet);
	}
	
	@Override
	public void onUpdate()
	{
		pearls.clear();
		holders.clear();
		hasOwnPearl = false;
		HashSet<UUID> seenPearls = new HashSet<>();
		long now = System.currentTimeMillis();
		
		if(MC.level == null)
			return;
		
		for(Entity entity : MC.level.entitiesForRendering())
			if(entity instanceof Player player)
				rememberVisibleOwner(player);
			
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(entity.getType() == EntityType.ENDER_PEARL)
			{
				Entity pearl = entity;
				seenPearls.add(pearl.getUUID());
				lastPearlPositions.put(pearl.getUUID(), pearl.position());
				PearlPrediction prediction = buildPrediction(pearl);
				Entity owner = getPearlOwner(pearl);
				String ownerLabel = getPearlOwnerLabel(pearl, owner);
				Vec3 stasisLabelPos = getStasisLabelPos(pearl);
				if(stasisLabelPos != null && ownerLabel != null)
					stasisLabels.put(pearl.getUUID(),
						new StasisLabel(ownerLabel, stasisLabelPos,
							now + STASIS_LABEL_STICKY_MS));
				StasisLabel stasisLabel = stasisLabels.get(pearl.getUUID());
				pearls.add(new TrackedPearl(pearl, prediction, ownerLabel,
					stasisLabel));
				boolean ownPearl = isOwnPearl(pearl, owner, ownerLabel);
				if(ownPearl)
				{
					hasOwnPearl = true;
					ownPearlSuppressUntilMs = System.currentTimeMillis() + 4000;
				}
				
				if(!ownPearl && alertedPearls.add(pearl.getUUID()))
					triggerPearlAlert(pearl, ownerLabel);
				continue;
			}
			
			if(entity instanceof Player player)
			{
				handleHolder(player);
			}
		}
		
		alertedPearls.retainAll(seenPearls);
		stasisLabels.entrySet().removeIf(e -> !seenPearls.contains(e.getKey())
			|| e.getValue().untilMs() < now);
		offlineStasisLabels.entrySet()
			.removeIf(e -> seenPearls.contains(e.getKey())
				|| e.getValue() == null || e.getValue().untilMs() < now
				|| isPositionOccupiedByVisiblePearl(
					e.getValue() != null ? e.getValue().pos() : null,
					seenPearls));
		
		// Disconnect inference: pearls that disappeared since last frame
		if(inferOwnership.isChecked())
		{
			HashSet<UUID> vanished = new HashSet<>(previousPearlUuids);
			vanished.removeAll(seenPearls);
			for(UUID vanishedUuid : vanished)
			{
				Vec3 vanishedPos = lastPearlPositions.get(vanishedUuid);
				if(vanishedPos == null)
					continue;
					
				// Skip if a currently visible pearl is at the same position
				// (UUID change due to server re-creating the entity)
				if(isPositionOccupiedByVisiblePearl(vanishedPos, seenPearls))
					continue;
				
				UUID existingOwner = pearlOwnerUuids.get(vanishedUuid);
				if(existingOwner != null)
				{
					// Never create offline labels for your own pearls. You
					// already know where they are and "Owner: You" is noise.
					if(MC.player != null
						&& existingOwner.equals(MC.player.getUUID()))
						continue;
					
					String inferred = getOwnerUuidLabel(existingOwner);
					if(inferred != null)
						putOfflineStasisLabel(vanishedUuid,
							formatOfflineLabelText(vanishedUuid, inferred),
							vanishedPos, now);
					continue;
				}
				
				String inferred = inferOwnerFromLeaveTiming(vanishedUuid);
				if(inferred != null)
					putOfflineStasisLabel(vanishedUuid, inferred, vanishedPos,
						now);
			}
		}
		previousPearlUuids = seenPearls;
		
		// Re-resolve current file in case server changed
		resolveCurrentFile();
		saveIfNeeded(false);
	}
	
	private void triggerPearlAlert(Entity pearl, String ownerLabel)
	{
		if(!chatAlerts.isChecked() && !soundAlerts.isChecked())
			return;
		
		if(chatAlerts.isChecked())
		{
			Vec3 selfPos = MC.player == null ? null : MC.player.position();
			String detectionDetails = DisconnectContext
				.formatPlayerDetectionDetails(selfPos, pearl.position());
			String ownerInfo =
				ownerLabel != null ? "\n" + ownerLabel + "." : "";
			ChatUtils.message("PearlESP: Ender pearl detected." + ownerInfo
				+ "\nDetected at:\n" + detectionDetails);
		}
		
		if(!soundAlerts.isChecked() || MC.level == null || MC.player == null)
			return;
		
		SoundEvent sound = alertSound.getSelected().resolve();
		if(sound == null)
			return;
		
		MC.level.playLocalSound(MC.player.getX(), MC.player.getY(),
			MC.player.getZ(), sound, SoundSource.PLAYERS, 1.0F, 1.0F, false);
	}
	
	private void handleHolder(Player player)
	{
		if(player == null || player == MC.player || player.isSpectator())
			return;
		
		for(InteractionHand hand : InteractionHand.values())
		{
			if(isHoldingPearl(player, hand))
				holders.add(new HeldPearl(player, hand));
		}
	}
	
	private boolean isHoldingPearl(Player player, InteractionHand hand)
	{
		ItemStack stack = hand == InteractionHand.MAIN_HAND
			? player.getMainHandItem() : player.getOffhandItem();
		return !stack.isEmpty() && stack.is(Items.ENDER_PEARL);
	}
	
	private boolean isHoldingPearlAnyHand(Player player)
	{
		return isHoldingPearl(player, InteractionHand.MAIN_HAND)
			|| isHoldingPearl(player, InteractionHand.OFF_HAND);
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(!showTracerLine.isChecked())
			return;
		
		for(TrackedPearl t : pearls)
		{
			PearlPrediction p = t.prediction();
			if(p != null && p.path() != null && p.path().size() > 1)
			{
				event.cancel();
				return;
			}
		}
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(pearls.isEmpty() && holders.isEmpty())
		{
			boolean wantPrediction = showPlayerPrediction.isChecked()
				&& MC.player != null && isHoldingPearlAnyHand(MC.player);
			if(!wantPrediction)
				return;
		}
		
		ArrayList<AABB> pearlBoxes = new ArrayList<>();
		ArrayList<AABB> stasisPearlBoxes = new ArrayList<>();
		ArrayList<AABB> landingBoxes = new ArrayList<>();
		ArrayList<AABB> stasisLandingBoxes = new ArrayList<>();
		ArrayList<AABB> handBoxes = new ArrayList<>();
		int lineColor = color.getColorI();
		int fillColor = color.getColorI(0x50);
		int stasisLineColor = getStasisLineColor();
		int stasisFillColor = getStasisFillColor();
		
		for(TrackedPearl tracked : pearls)
		{
			Entity pearl = tracked.pearl();
			if(pearl == null || pearl.isRemoved())
				continue;
			
			AABB box = EntityUtils.getLerpedBox(pearl, partialTicks);
			boolean isStasis = tracked.stasisLabel() != null;
			
			if(isStasis)
				stasisPearlBoxes.add(box);
			else
				pearlBoxes.add(box);
			
			if(showStasisOwners.isChecked())
			{
				StasisLabel label = tracked.stasisLabel();
				if(label != null && label.text() != null
					&& !label.text().isBlank())
				{
					Vec3 labelPos = label.pos();
					float dynScale = computeDistanceScale(labelPos, 0.85F);
					drawWorldLabel(matrixStack, label.text(), labelPos.x,
						labelPos.y, labelPos.z,
						isStasis ? stasisLineColor : lineColor, dynScale, 0F);
				}else if(tracked.ownerLabel() != null
					&& !tracked.ownerLabel().isBlank())
				{
					Vec3 labelPos =
						getVisiblePearlLabelPos(pearl, partialTicks);
					float dynScale = computeDistanceScale(labelPos, 0.7F);
					drawWorldLabel(matrixStack, tracked.ownerLabel(),
						labelPos.x, labelPos.y, labelPos.z, lineColor, dynScale,
						0F);
				}
			}
			
			PearlPrediction prediction = tracked.prediction();
			if(prediction != null && prediction.landing() != null)
			{
				AABB landing =
					createLandingBox(prediction.landing(), LANDING_BOX_RADIUS);
				if(isStasis)
					stasisLandingBoxes.add(landing);
				else
					landingBoxes.add(landing);
				renderTrajectory(matrixStack, prediction.path(),
					isStasis ? stasisLineColor : lineColor);
			}
		}
		
		if(showStasisOwners.isChecked())
		{
			for(StasisLabel label : offlineStasisLabels.values())
			{
				if(label == null || label.text() == null
					|| label.text().isBlank() || label.pos() == null)
					continue;
				
				Vec3 labelPos = label.pos();
				float dynScale = computeDistanceScale(labelPos, 0.85F);
				drawWorldLabel(matrixStack, label.text(), labelPos.x,
					labelPos.y, labelPos.z, stasisLineColor, dynScale, 0F);
			}
		}
		
		for(HeldPearl held : holders)
		{
			if(!highlightHeld.isChecked())
				continue;
			AABB box = getHandBox(held.player(), held.hand(), partialTicks);
			if(box != null)
				handBoxes.add(box);
		}
		
		// Show predicted trajectory for local player while holding a pearl
		if(MC.player != null)
		{
			for(InteractionHand hand : InteractionHand.values())
			{
				if(!showPlayerPrediction.isChecked())
					break;
				if(isHoldingPearl(MC.player, hand))
				{
					Vec3 start = getHandPos(MC.player, hand, partialTicks);
					// Suppress prediction if our pearl is in-flight or recently
					// thrown
					boolean hasActual = hasOwnPearl
						|| System.currentTimeMillis() < ownPearlSuppressUntilMs;
					if(start != null)
					{
						if(!hasActual)
							for(TrackedPearl t : pearls)
							{
								Entity e = t.pearl();
								if(e == null)
									continue;
								try
								{
									Method m =
										e.getClass().getMethod("getOwner");
									Object owner = m.invoke(e);
									if(owner == MC.player)
									{
										hasActual = true;
										break;
									}
								}catch(Exception ex)
								{
									// Fallback: proximity to hand
									Vec3 ppos = e.position();
									if(ppos.distanceTo(start) < 1.5)
									{
										hasActual = true;
										break;
									}
								}
							}
					}
					
					if(hasActual)
						continue; // a real pearl exists — don't draw prediction
						
					PearlPrediction pred = buildPredictionFromPlayer(MC.player,
						hand, partialTicks);
					if(pred != null && pred.landing() != null)
					{
						landingBoxes.add(createLandingBox(pred.landing(),
							LANDING_BOX_RADIUS));
						renderTrajectory(matrixStack, pred.path(), lineColor);
					}
				}
			}
		}
		
		if(!pearlBoxes.isEmpty())
			RenderUtils.drawOutlinedBoxes(matrixStack, pearlBoxes, lineColor,
				false);
		if(!stasisPearlBoxes.isEmpty())
			RenderUtils.drawOutlinedBoxes(matrixStack, stasisPearlBoxes,
				stasisLineColor, false);
		
		if(!landingBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrixStack, landingBoxes, fillColor,
				false);
			RenderUtils.drawOutlinedBoxes(matrixStack, landingBoxes, lineColor,
				false);
		}
		if(!stasisLandingBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrixStack, stasisLandingBoxes,
				stasisFillColor, false);
			RenderUtils.drawOutlinedBoxes(matrixStack, stasisLandingBoxes,
				stasisLineColor, false);
		}
		
		if(!handBoxes.isEmpty())
		{
			RenderUtils.drawSolidBoxes(matrixStack, handBoxes, fillColor,
				false);
			RenderUtils.drawOutlinedBoxes(matrixStack, handBoxes, lineColor,
				false);
		}
	}
	
	private void renderTrajectory(PoseStack matrixStack, List<Vec3> path,
		int lineColor)
	{
		if(!showTracerLine.isChecked() || path == null || path.size() < 2)
			return;
		
		if(tracerFlash.isChecked())
			lineColor = RenderUtils.flashColor(lineColor);
		RenderUtils.drawCurvedLine(matrixStack, path, lineColor, false,
			tracerThickness.getValue());
	}
	
	private Entity getPearlOwner(Entity pearl)
	{
		if(pearl instanceof Projectile projectile)
		{
			Entity owner = projectile.getOwner();
			if(owner != null)
				return owner;
		}
		
		try
		{
			Method m = pearl.getClass().getMethod("getOwner");
			Object owner = m.invoke(pearl);
			if(owner instanceof Entity entity)
				return entity;
		}catch(Exception ignored)
		{}
		
		Integer ownerId = pearlSpawnOwnerIds.get(pearl.getUUID());
		if(ownerId != null && MC.level != null)
			return MC.level.getEntity(ownerId);
		
		return null;
	}
	
	private String getPearlOwnerLabel(Entity pearl, Entity owner)
	{
		UUID pearlUuid = pearl.getUUID();
		UUID ownerUuid = getKnownOwnerUuid(owner);
		if(ownerUuid != null)
		{
			rememberPearlOwnerUuid(pearlUuid, ownerUuid);
			String label = getOwnerUuidLabel(ownerUuid);
			if(label != null)
			{
				OwnerConfidence conf = getPearlConfidence(pearlUuid);
				// If inference already fired in rememberPearlSpawn,
				// keep (Inferred) until entity-id confirmation
				if(conf == OwnerConfidence.INFERRED_JOIN_TIMING)
				{
					updatePearlIdentityConfidence(pearlUuid, ownerUuid,
						OwnerConfidence.CONFIRMED_CURRENT_ENTITY_ID);
					return rememberPearlOwnerLabel(pearlUuid, label);
				}
				updatePearlIdentityConfidence(pearlUuid, ownerUuid,
					OwnerConfidence.CONFIRMED_CURRENT_ENTITY_ID);
				return rememberPearlOwnerLabel(pearlUuid, label);
			}
		}
		
		// Check confidence state — if inferred, keep showing (Inferred)
		OwnerConfidence existingConf = getPearlConfidence(pearlUuid);
		
		ownerUuid = inferOwnPearlOwner(pearl);
		if(ownerUuid != null)
		{
			rememberPearlOwnerUuid(pearlUuid, ownerUuid);
			updatePearlIdentityConfidence(pearlUuid, ownerUuid,
				OwnerConfidence.CONFIRMED_CURRENT_ENTITY_ID);
			String label = getOwnerUuidLabel(ownerUuid);
			if(label != null)
				return rememberPearlOwnerLabel(pearlUuid, label);
		}
		
		// Check pearl UUID cache — survives entity id changes
		UUID cachedOwnerUuid = pearlOwnerUuids.get(pearlUuid);
		if(cachedOwnerUuid != null)
		{
			String label = getOwnerUuidLabel(cachedOwnerUuid);
			if(label != null)
			{
				if(existingConf == null
					|| existingConf == OwnerConfidence.UNKNOWN)
					updatePearlIdentityConfidence(pearlUuid, cachedOwnerUuid,
						OwnerConfidence.CONFIRMED_PEARL_UUID_CACHE);
				// Preserve (Inferred) suffix if still inferred
				if(existingConf == OwnerConfidence.INFERRED_JOIN_TIMING)
					return rememberPearlOwnerLabel(pearlUuid,
						label + " (Inferred)");
				return rememberPearlOwnerLabel(pearlUuid, label);
			}
		}
		
		// Try resolving via the spawn packet owner entity id
		Integer ownerId = pearlSpawnOwnerIds.get(pearl.getUUID());
		if(ownerId != null)
		{
			ownerUuid = resolveOwnerUuid(ownerId);
			if(ownerUuid != null)
			{
				if(cachedOwnerUuid == null)
					rememberPearlOwnerUuid(pearlUuid, ownerUuid);
				String label = getOwnerUuidLabel(ownerUuid);
				if(label != null)
				{
					updatePearlIdentityConfidence(pearlUuid, ownerUuid,
						OwnerConfidence.CONFIRMED_CURRENT_ENTITY_ID);
					return rememberPearlOwnerLabel(pearlUuid, label);
				}
			}
			
			// Owner entity id couldn't resolve, check historical binding
			EntityIdBinding binding = entityIdBindings.get(ownerId);
			if(binding != null && !binding.active()
				&& binding.playerUuid() != null)
			{
				String label = getOwnerUuidLabel(binding.playerUuid());
				if(label != null)
				{
					rememberPearlOwnerUuid(pearlUuid, binding.playerUuid());
					updatePearlIdentityConfidence(pearlUuid,
						binding.playerUuid(),
						OwnerConfidence.HISTORICAL_ENTITY_ID);
					return rememberPearlOwnerLabel(pearlUuid, label);
				}
			}
		}
		
		// Check stored label from prior sessions
		UUID anyOwnerUuid = cachedOwnerUuid != null ? cachedOwnerUuid
			: pearlOwnerUuids.get(pearlUuid);
		if(anyOwnerUuid != null)
		{
			String label = getOwnerUuidLabel(anyOwnerUuid);
			if(label != null)
				return rememberPearlOwnerLabel(pearlUuid, label);
		}
		
		String storedLabel = pearlOwnerLabels.get(pearlUuid);
		if(storedLabel != null && !storedLabel.isBlank())
			return storedLabel;
		
		// Inference already fired in rememberPearlSpawn?
		if(existingConf == OwnerConfidence.INFERRED_JOIN_TIMING
			&& cachedOwnerUuid != null)
		{
			String label = getOwnerUuidLabel(cachedOwnerUuid);
			if(label != null)
				return rememberPearlOwnerLabel(pearlUuid,
					label + " (Inferred)");
		}
		
		return null;
	}
	
	// These methods update the dirty flag for auto-save
	private void rememberPearlOwnerUuid(UUID pearlUuid, UUID ownerUuid)
	{
		if(pearlUuid == null || ownerUuid == null)
			return;
		
		pearlOwnerUuids.put(pearlUuid, ownerUuid);
		dirty = true;
		
		// Update pearl identity with owner
		PearlIdentity pearlId = pearlIdentities.get(pearlUuid);
		if(pearlId != null)
			pearlIdentities.put(pearlUuid,
				pearlId.withOwner(ownerUuid,
					OwnerConfidence.CONFIRMED_PEARL_UUID_CACHE,
					System.currentTimeMillis()));
	}
	
	private String rememberPearlOwnerLabel(UUID pearlUuid, String label)
	{
		if(pearlUuid != null && label != null && !label.isBlank())
		{
			pearlOwnerLabels.put(pearlUuid, label);
			dirty = true;
		}
		return label;
	}
	
	private UUID getKnownOwnerUuid(Entity owner)
	{
		if(owner == MC.player)
			return owner.getUUID();
		if(owner instanceof Player player)
		{
			rememberVisibleOwner(player);
			return player.getUUID();
		}
		return null;
	}
	
	private void rememberPlayerInfo(ClientboundPlayerInfoUpdatePacket packet)
	{
		long now = System.currentTimeMillis();
		
		for(ClientboundPlayerInfoUpdatePacket.Entry entry : packet.entries())
		{
			if(entry == null || entry.profileId() == null)
				continue;
			
			// ADD_PLAYER: profile is present → record join time
			if(entry.profile() != null)
			{
				playerJoinTimes.put(entry.profileId(), now);
				String name = entry.profile().name();
				if(name != null && !name.isBlank())
					knownPlayerNames.put(entry.profileId(), name);
			}else
			{
				// REMOVE_PLAYER: profile is null → record leave time
				playerLeaveTimes.put(entry.profileId(), now);
				if(inferOwnership.isChecked())
					inferOwnersFromPlayerLeave(entry.profileId(), now);
			}
		}
	}
	
	private void rememberPlayerSpawn(ClientboundAddEntityPacket packet)
	{
		UUID uuid = packet.getUUID();
		if(uuid == null)
			return;
		
		int entityId = packet.getId();
		long now = System.currentTimeMillis();
		
		// Deactivate any previous binding for this player UUID
		markPlayerEntityIdInactiveForUuid(uuid, now);
		
		// Create active binding
		entityIdBindings.put(entityId,
			new EntityIdBinding(uuid, now, 0L, true));
		
		// Update player identity
		PlayerIdentity existing = playerIdentities.get(uuid);
		if(existing != null)
			playerIdentities.put(uuid, existing.withEntityId(entityId, now));
		else
			playerIdentities.put(uuid,
				new PlayerIdentity(uuid, null, entityId, now, now));
		
		resolvePlayerName(uuid);
		rememberPearlOwnersForEntityId(entityId, uuid);
	}
	
	private void rememberVisibleOwner(Player player)
	{
		if(player == null)
			return;
		
		int entityId = player.getId();
		UUID uuid = player.getUUID();
		long now = System.currentTimeMillis();
		
		// Deactivate any previous binding for this player UUID
		markPlayerEntityIdInactiveForUuid(uuid, now);
		
		// Create/refresh active binding
		entityIdBindings.put(entityId,
			new EntityIdBinding(uuid, now, 0L, true));
		
		// Update player identity
		PlayerIdentity existing = playerIdentities.get(uuid);
		if(existing != null)
			playerIdentities.put(uuid, existing.withEntityId(entityId, now));
		else
			playerIdentities.put(uuid,
				new PlayerIdentity(uuid, null, entityId, now, now));
		
		rememberPearlOwnersForEntityId(entityId, uuid);
		
		String name = player.getName().getString();
		if(name == null || name.isBlank())
			return;
		
		knownPlayerNames.put(uuid, name);
		
		// Update player identity with name
		PlayerIdentity pid = playerIdentities.get(uuid);
		if(pid != null && pid.name() == null)
			playerIdentities.put(uuid, pid.withName(name, now));
	}
	
	private UUID resolveOwnerUuid(int ownerId)
	{
		if(MC.player != null && ownerId == MC.player.getId())
		{
			rememberVisibleOwner(MC.player);
			return MC.player.getUUID();
		}
		
		// Check active entity id binding first
		EntityIdBinding binding = entityIdBindings.get(ownerId);
		if(binding != null && binding.active() && binding.playerUuid() != null)
			return binding.playerUuid();
		
		if(MC.level == null)
			return null;
		
		Entity entity = MC.level.getEntity(ownerId);
		if(entity instanceof Player player)
		{
			rememberVisibleOwner(player);
			return player.getUUID();
		}
		
		for(Entity visible : MC.level.entitiesForRendering())
		{
			if(visible.getId() != ownerId
				|| !(visible instanceof Player player))
				continue;
			
			rememberVisibleOwner(player);
			return player.getUUID();
		}
		
		// Fall back to inactive binding (historical match)
		if(binding != null && binding.playerUuid() != null)
			return binding.playerUuid();
		
		return null;
	}
	
	private void rememberPearlOwnersForEntityId(int ownerId, UUID ownerUuid)
	{
		if(ownerUuid == null)
			return;
		
		// Walk all known pearl spawn owner ids
		for(Map.Entry<UUID, Integer> entry : new ArrayList<>(
			pearlSpawnOwnerIds.entrySet()))
		{
			Integer pearlOwnerId = entry.getValue();
			if(pearlOwnerId == null || pearlOwnerId != ownerId)
				continue;
			
			rememberPearlOwnerUuid(entry.getKey(), ownerUuid);
		}
		
		// Also walk pearl identities that have this owner entity id
		for(Map.Entry<UUID, PearlIdentity> entry : new ArrayList<>(
			pearlIdentities.entrySet()))
		{
			PearlIdentity pid = entry.getValue();
			if(pid.ownerEntityId() != null && pid.ownerEntityId() == ownerId)
			{
				UUID pearlUuid = entry.getKey();
				if(pearlSpawnOwnerIds.containsKey(pearlUuid)
					&& pearlSpawnOwnerIds.get(pearlUuid) == ownerId)
					// Already handled above
					continue;
				rememberPearlOwnerUuid(pearlUuid, ownerUuid);
			}
		}
	}
	
	private String getOwnerUuidLabel(UUID ownerUuid)
	{
		if(ownerUuid == null)
			return null;
		
		if(MC.player != null && ownerUuid.equals(MC.player.getUUID()))
			return "Owner: You";
		
		String name = resolvePlayerName(ownerUuid);
		return name == null ? null : "Owner: " + name;
	}
	
	private boolean isOwnPearl(Entity pearl, Entity owner, String ownerLabel)
	{
		if(MC.player == null)
			return false;
		
		if(owner == MC.player)
			return true;
		
		UUID ownerUuid =
			pearl != null ? pearlOwnerUuids.get(pearl.getUUID()) : null;
		if(MC.player.getUUID().equals(ownerUuid))
			return true;
		
		return "Owner: You".equals(ownerLabel);
	}
	
	private UUID inferOwnPearlOwner(Entity pearl)
	{
		if(pearl == null || MC.player == null)
			return null;
		
		Integer ownerId = pearlSpawnOwnerIds.get(pearl.getUUID());
		if(ownerId != null && ownerId > 0 && ownerId != MC.player.getId())
			return null;
		
		if(System.currentTimeMillis()
			- lastOwnPearlUseMs > OWN_THROW_INFERENCE_MS)
			return null;
		
		Vec3 reference = lastOwnPearlUsePos != null ? lastOwnPearlUsePos
			: MC.player.position();
		if(reference == null)
			return null;
		
		double maxDistSq =
			OWN_THROW_INFERENCE_RADIUS * OWN_THROW_INFERENCE_RADIUS;
		if(pearl.position().distanceToSqr(reference) > maxDistSq)
			return null;
		
		return MC.player.getUUID();
	}
	
	private String resolvePlayerName(UUID uuid)
	{
		if(uuid == null)
			return null;
		
		String name = OwnerResolver.lookupPlayerName(uuid);
		if(OwnerResolver.isResolvablePlayerName(name))
		{
			knownPlayerNames.put(uuid, name);
			return name;
		}
		
		String cached = knownPlayerNames.get(uuid);
		if(cached != null && !cached.isBlank())
			return cached;
		
		return null;
	}
	
	private Vec3 getStasisLabelPos(Entity pearl)
	{
		if(MC.level == null)
			return null;
		
		BlockPos pearlPos = pearl.blockPosition();
		for(int yOffset = 2; yOffset >= -2; yOffset--)
		{
			BlockPos checkPos = pearlPos.offset(0, yOffset, 0);
			BlockPos base = getStasisBase(checkPos);
			if(base != null)
				return getStaticLabelPos(base);
		}
		
		return null;
	}
	
	private Vec3 getVisiblePearlLabelPos(Entity pearl, float partialTicks)
	{
		AABB box = EntityUtils.getLerpedBox(pearl, partialTicks);
		return new Vec3(box.getCenter().x, box.maxY + 0.45, box.getCenter().z);
	}
	
	private BlockPos getStasisBase(BlockPos pos)
	{
		if(!isBubbleColumn(pos))
			return null;
		
		for(int dy = 1; dy <= 8; dy++)
		{
			BlockPos below = pos.below(dy);
			BlockState state = MC.level.getBlockState(below);
			if(state.getBlock() == Blocks.SOUL_SAND
				|| state.getBlock() == Blocks.SOUL_SOIL)
				return below;
			if(!isBubbleColumn(below) && state.getFluidState().isEmpty())
				return null;
		}
		
		return null;
	}
	
	private Vec3 getStaticLabelPos(BlockPos base)
	{
		int topY = base.getY();
		for(int dy = 1; dy <= 16; dy++)
		{
			BlockPos above = base.above(dy);
			if(!isBubbleColumn(above))
				break;
			topY = above.getY();
		}
		
		return new Vec3(base.getX() + 0.5, topY + 2.35, base.getZ() + 0.5);
	}
	
	private boolean isBubbleColumn(BlockPos pos)
	{
		BlockState state = MC.level.getBlockState(pos);
		return state.getBlock() instanceof BubbleColumnBlock;
	}
	
	private void drawWorldLabel(PoseStack matrices, String text, double x,
		double y, double z, int argb, float scale, float offsetPx)
	{
		matrices.pushPose();
		Vec3 cam = RenderUtils.getCameraPos();
		matrices.translate(x - cam.x, y - cam.y, z - cam.z);
		
		var camEntity = MC.getCameraEntity();
		if(camEntity != null)
		{
			matrices.mulPose(Axis.YP.rotationDegrees(-camEntity.getYRot()));
			matrices.mulPose(Axis.XP.rotationDegrees(camEntity.getXRot()));
		}
		
		matrices.mulPose(Axis.YP.rotationDegrees(180.0F));
		float s = 0.025F * scale;
		matrices.scale(s, -s, s);
		matrices.translate(0, offsetPx, 0);
		
		Font font = MC.font;
		MultiBufferSource.BufferSource vcp = RenderUtils.getVCP();
		float w = font.width(text) / 2F;
		int baseAlpha = (argb >>> 24) & 0xFF;
		int bgAlpha =
			(int)Math.round(MC.options.getBackgroundOpacity(0.25F) * baseAlpha);
		int bg = bgAlpha << 24;
		int strokeColor =
			(Math.max(0, Math.min(255, baseAlpha)) << 24) | 0x000000;
		var matrix = matrices.last().pose();
		
		font.drawInBatch(text, -w - 1, 0, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		font.drawInBatch(text, -w + 1, 0, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		font.drawInBatch(text, -w, -1, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		font.drawInBatch(text, -w, 1, strokeColor, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
		font.drawInBatch(text, -w, 0, argb, false, matrix, vcp,
			Font.DisplayMode.SEE_THROUGH, bg, 0xF000F0);
		
		vcp.endBatch();
		matrices.popPose();
	}
	
	private PearlPrediction buildPrediction(Entity pearl)
	{
		ArrayList<Vec3> path = new ArrayList<>();
		Vec3 position = pearl.position();
		Vec3 velocity = pearl.getDeltaMovement();
		path.add(position);
		Vec3 landing = position;
		
		if(MC.player == null)
			return new PearlPrediction(path, landing);
		
		for(int i = 0; i < 160; i++)
		{
			Vec3 next = position.add(velocity);
			BlockHitResult hit = BlockUtils.raycast(position, next);
			if(hit.getType() != HitResult.Type.MISS)
			{
				Vec3 hitPos = hit.getLocation();
				path.add(hitPos);
				return new PearlPrediction(path, hitPos);
			}
			
			path.add(next);
			position = next;
			landing = next;
			
			velocity = applyDrag(velocity, pearl);
			
			if(position.y < MC.level.getMinY() - 4)
				break;
			if(velocity.lengthSqr() < 1e-4)
				break;
		}
		
		return new PearlPrediction(path, landing);
	}
	
	private PearlPrediction buildPredictionFromPlayer(Player player,
		InteractionHand hand, float partialTicks)
	{
		Vec3 start = getHandPos(player, hand, partialTicks);
		if(start == null)
			return null;
		
		// initial velocity based on player yaw/pitch like Trajectories
		double yaw = Math.toRadians(player.getYRot());
		double pitch = Math.toRadians(player.getXRot());
		double cosPitch = Math.cos(pitch);
		double vx = -Math.sin(yaw) * cosPitch;
		double vy = -Math.sin(pitch);
		double vz = Math.cos(yaw) * cosPitch;
		Vec3 velocity = new Vec3(vx, vy, vz).scale(1.5);
		
		ArrayList<Vec3> path = new ArrayList<>();
		Vec3 position = start;
		Vec3 landing = position;
		
		for(int i = 0; i < 160; i++)
		{
			Vec3 next = position.add(velocity);
			BlockHitResult hit = BlockUtils.raycast(position, next);
			if(hit.getType() != HitResult.Type.MISS)
			{
				Vec3 hitPos = hit.getLocation();
				path.add(hitPos);
				return new PearlPrediction(path, hitPos);
			}
			
			path.add(next);
			position = next;
			landing = next;
			
			velocity = applyDrag(velocity, player);
			
			if(position.y < MC.level.getMinY() - 4)
				break;
			if(velocity.lengthSqr() < 1e-4)
				break;
		}
		
		return new PearlPrediction(path, landing);
	}
	
	private Vec3 applyDrag(Vec3 velocity, Entity pearl)
	{
		double drag = pearl.isInWater() ? 0.8 : 0.99;
		Vec3 slowed = velocity.scale(drag);
		if(!pearl.isNoGravity())
			slowed = slowed.add(0, -0.03, 0);
		return slowed;
	}
	
	private AABB createLandingBox(Vec3 center, double radius)
	{
		return new AABB(center.x - radius, center.y - radius, center.z - radius,
			center.x + radius, center.y + radius, center.z + radius);
	}
	
	private AABB getHandBox(Player player, InteractionHand hand,
		float partialTicks)
	{
		Vec3 center = getHandPos(player, hand, partialTicks);
		if(center == null)
			return null;
		return createLandingBox(center, HAND_BOX_RADIUS);
	}
	
	private Vec3 getHandPos(Player player, InteractionHand hand,
		float partialTicks)
	{
		if(player == null)
			return null;
		
		Vec3 basePos = EntityUtils.getLerpedPos(player, partialTicks);
		double yaw = Math.toRadians(player.getYRot());
		
		HumanoidArm mainArm = player.getMainArm();
		boolean rightSide = mainArm == HumanoidArm.RIGHT
			&& hand == InteractionHand.MAIN_HAND
			|| mainArm == HumanoidArm.LEFT && hand == InteractionHand.OFF_HAND;
		double side = rightSide ? -1 : 1;
		
		double offsetX = Math.cos(yaw) * 0.18 * side;
		double offsetY = player.getEyeHeight(player.getPose()) - 0.1;
		double offsetZ = Math.sin(yaw) * 0.18 * side;
		
		return basePos.add(offsetX, offsetY, offsetZ);
	}
	
	// ---- New ownership tracking methods ----
	
	private String inferOwnerFromJoinTiming(UUID pearlUuid, Entity pearl)
	{
		long now = System.currentTimeMillis();
		long windowMs = inferenceWindowMs.getValueI();
		long pearlSpawnTime = 0L;
		
		// Use lastSeenMs (most recent respawn time), not firstSeenMs
		PearlIdentity pid = pearlIdentities.get(pearlUuid);
		if(pid != null)
			pearlSpawnTime = pid.lastSeenMs();
		
		// If identity doesn't have a recent time, use now
		if(pearlSpawnTime == 0L || pearlSpawnTime < now - 5000L)
			pearlSpawnTime = now;
		
		// Find players who joined within the window before this pearl spawned
		UUID bestCandidate = null;
		int candidateCount = 0;
		
		for(Map.Entry<UUID, Long> entry : playerJoinTimes.entrySet())
		{
			long joinTime = entry.getValue();
			if(joinTime >= pearlSpawnTime - windowMs
				&& joinTime <= pearlSpawnTime)
			{
				bestCandidate = entry.getKey();
				candidateCount++;
			}
		}
		
		// Only infer if exactly one candidate
		if(candidateCount != 1 || bestCandidate == null)
			return null;
		
		// Don't infer if we already have a different confirmed owner
		UUID existingOwner = pearlOwnerUuids.get(pearlUuid);
		if(existingOwner != null && !existingOwner.equals(bestCandidate))
			return null;
		
		boolean wasAlreadyKnown =
			existingOwner != null && existingOwner.equals(bestCandidate);
		
		String label = getOwnerUuidLabel(bestCandidate);
		if(label == null)
			return null;
		
		// Remember the inferred owner so subsequent lookups find it
		rememberPearlOwnerUuid(pearlUuid, bestCandidate);
		updatePearlIdentityConfidence(pearlUuid, bestCandidate,
			OwnerConfidence.INFERRED_JOIN_TIMING);
		
		// Only chat about it if this is a new discovery
		if(!wasAlreadyKnown && !bestCandidate.equals(MC.player.getUUID()))
			ChatUtils.message("PearlESP: Inferred owner " + label
				+ " for pearl via join timing ("
				+ (pearlSpawnTime - playerJoinTimes.get(bestCandidate))
				+ "ms gap).");
		
		return label + " (Inferred)";
	}
	
	private String inferOwnerFromLeaveTiming(UUID pearlUuid)
	{
		long now = System.currentTimeMillis();
		long windowMs = inferenceWindowMs.getValueI();
		
		// Pearl vanished — use now as reference
		UUID bestCandidate = null;
		int candidateCount = 0;
		
		for(Map.Entry<UUID, Long> entry : playerLeaveTimes.entrySet())
		{
			long leaveTime = entry.getValue();
			if(leaveTime >= now - windowMs && leaveTime <= now + windowMs)
			{
				bestCandidate = entry.getKey();
				candidateCount++;
			}
		}
		
		if(candidateCount != 1 || bestCandidate == null)
			return null;
		
		// Never infer yourself — you know you didn't leave
		if(MC.player != null && bestCandidate.equals(MC.player.getUUID()))
			return null;
		
		String label = getOwnerUuidLabel(bestCandidate);
		if(label == null)
			return null;
		
		// Only chat if the owner wasn't already known for this pearl
		UUID existingOwner = pearlOwnerUuids.get(pearlUuid);
		boolean wasAlreadyKnown =
			existingOwner != null && existingOwner.equals(bestCandidate);
		
		if(!wasAlreadyKnown && !bestCandidate.equals(MC.player.getUUID()))
			ChatUtils.message("PearlESP: Inferred owner " + label
				+ " for vanished pearl via leave timing ("
				+ (now - playerLeaveTimes.get(bestCandidate)) + "ms gap).");
		
		rememberPearlOwnerUuid(pearlUuid, bestCandidate);
		updatePearlIdentityConfidence(pearlUuid, bestCandidate,
			OwnerConfidence.INFERRED_LEAVE_TIMING);
		return label + " (Inferred, Offline)";
	}
	
	private void inferOwnersFromPlayerLeave(UUID playerUuid, long now)
	{
		if(playerUuid == null)
			return;
			
		// Ignore REMOVE_PLAYER info refreshes where the player is still
		// visible in the world (common on servers that send remove+add
		// cycles for tab-list/latency updates).
		if(isPlayerVisibleInWorld(playerUuid))
			return;
		
		Integer playerEntityId = null;
		PlayerIdentity identity = playerIdentities.get(playerUuid);
		if(identity != null)
			playerEntityId = identity.currentEntityId();
		
		if(playerEntityId == null)
		{
			for(Map.Entry<Integer, EntityIdBinding> entry : entityIdBindings
				.entrySet())
			{
				EntityIdBinding binding = entry.getValue();
				if(binding != null && playerUuid.equals(binding.playerUuid()))
				{
					playerEntityId = entry.getKey();
					break;
				}
			}
		}
		
		if(playerEntityId == null)
			return;
		
		String label = getOwnerUuidLabel(playerUuid);
		if(label == null)
			return;
		
		int inferredCount = 0;
		int alreadyKnownCount = 0;
		for(Map.Entry<UUID, Integer> entry : pearlSpawnOwnerIds.entrySet())
		{
			Integer ownerId = entry.getValue();
			if(ownerId == null || ownerId.intValue() != playerEntityId)
				continue;
			
			UUID pearlUuid = entry.getKey();
			UUID existingOwner = pearlOwnerUuids.get(pearlUuid);
			if(existingOwner != null && !existingOwner.equals(playerUuid))
				continue;
			
			boolean wasAlreadyKnown =
				existingOwner != null && existingOwner.equals(playerUuid);
			if(wasAlreadyKnown)
				alreadyKnownCount++;
			
			rememberPearlOwnerUuid(pearlUuid, playerUuid);
			updatePearlIdentityConfidence(pearlUuid, playerUuid,
				OwnerConfidence.INFERRED_LEAVE_TIMING);
			
			Vec3 pos = lastPearlPositions.get(pearlUuid);
			if(pos != null)
				putOfflineStasisLabel(pearlUuid,
					formatOfflineLabelText(pearlUuid, label), pos, now);
			
			inferredCount++;
		}
		
		// Only chat about newly inferred pearls (not re-inferred ones)
		int newCount = inferredCount - alreadyKnownCount;
		if(newCount > 0 && !playerUuid.equals(MC.player.getUUID()))
		{
			ChatUtils.message("PearlESP: Inferred owner " + label + " for "
				+ newCount + " pearl" + (newCount == 1 ? "" : "s")
				+ " via leave timing.");
		}
	}
	
	private void rememberPearlSpawn(ClientboundAddEntityPacket packet)
	{
		UUID pearlUuid = packet.getUUID();
		int pearlEntityId = packet.getId();
		int ownerId = packet.getData();
		long now = System.currentTimeMillis();
		
		pearlSpawnOwnerIds.put(pearlUuid, ownerId);
		
		// Update pearl identity
		PearlIdentity existing = pearlIdentities.get(pearlUuid);
		if(existing != null)
		{
			// Pearl reappeared — keep cached owner UUID, update entity id
			pearlIdentities.put(pearlUuid,
				existing.withEntityId(pearlEntityId, ownerId, now));
		}else
		{
			pearlIdentities.put(pearlUuid,
				new PearlIdentity(pearlUuid, pearlEntityId, null, ownerId,
					OwnerConfidence.UNKNOWN, now, now));
		}
		
		// Try to resolve owner now
		UUID ownerUuid = resolveOwnerUuid(ownerId);
		if(ownerUuid != null)
		{
			rememberPearlOwnerUuid(pearlUuid, ownerUuid);
			updatePearlIdentityConfidence(pearlUuid, ownerUuid,
				OwnerConfidence.CONFIRMED_CURRENT_ENTITY_ID);
			return;
		}
		
		// Check if we already have a cached owner for this pearl UUID
		UUID cachedOwner = pearlOwnerUuids.get(pearlUuid);
		boolean alreadyCached = cachedOwner != null;
		
		if(alreadyCached)
			updatePearlIdentityConfidence(pearlUuid, cachedOwner,
				OwnerConfidence.CONFIRMED_PEARL_UUID_CACHE);
			
		// Try join-timing inference NOW (during packet processing).
		// Run even if cached owner exists — we want the (Inferred)
		// suffix when a fresh join correlates with this spawn.
		if(inferOwnership.isChecked())
		{
			String inferred = inferOwnerFromJoinTiming(pearlUuid, null);
			if(inferred != null)
			{
				UUID inferredUuid = pearlOwnerUuids.get(pearlUuid);
				if(inferredUuid != null)
					updatePearlIdentityConfidence(pearlUuid, inferredUuid,
						OwnerConfidence.INFERRED_JOIN_TIMING);
				return;
			}
		}
		
		// If we had a cached owner and inference didn't fire,
		// the cached owner is still valid — stop here.
		if(alreadyCached)
			return;
		
		// Check if the owner entity id matches a historical binding
		EntityIdBinding binding = entityIdBindings.get(ownerId);
		if(binding != null && binding.playerUuid() != null)
		{
			rememberPearlOwnerUuid(pearlUuid, binding.playerUuid());
			OwnerConfidence conf =
				binding.active() ? OwnerConfidence.CONFIRMED_CURRENT_ENTITY_ID
					: OwnerConfidence.HISTORICAL_ENTITY_ID;
			updatePearlIdentityConfidence(pearlUuid, binding.playerUuid(),
				conf);
		}
	}
	
	private void rememberEntityRemoval(ClientboundRemoveEntitiesPacket packet)
	{
		long now = System.currentTimeMillis();
		
		for(int id : packet.getEntityIds())
			markEntityIdInactive(id, now);
	}
	
	private void markEntityIdInactive(int entityId, long now)
	{
		EntityIdBinding binding = entityIdBindings.get(entityId);
		if(binding != null && binding.active())
			entityIdBindings.put(entityId, binding.withInactive(now));
	}
	
	private void markPlayerEntityIdInactiveForUuid(UUID playerUuid, long now)
	{
		// Find all active bindings for this player UUID and mark them inactive
		for(Map.Entry<Integer, EntityIdBinding> entry : entityIdBindings
			.entrySet())
		{
			EntityIdBinding binding = entry.getValue();
			if(binding.active() && playerUuid.equals(binding.playerUuid()))
				entityIdBindings.put(entry.getKey(), binding.withInactive(now));
		}
	}
	
	private void updatePearlIdentityConfidence(UUID pearlUuid, UUID ownerUuid,
		OwnerConfidence confidence)
	{
		PearlIdentity pid = pearlIdentities.get(pearlUuid);
		if(pid != null)
			pearlIdentities.put(pearlUuid, pid.withOwner(ownerUuid, confidence,
				System.currentTimeMillis()));
	}
	
	private OwnerConfidence getPearlConfidence(UUID pearlUuid)
	{
		PearlIdentity pid = pearlIdentities.get(pearlUuid);
		return pid != null ? pid.confidence() : null;
	}
	
	/**
	 * Checks whether any currently visible pearl is at approximately the
	 * same position as the given vanished position. This prevents creating
	 * duplicate offline labels when the server re-creates a pearl entity
	 * with a new UUID (common in bubble-column stasis chambers).
	 */
	private boolean isPositionOccupiedByVisiblePearl(Vec3 vanishedPos,
		HashSet<UUID> seenPearls)
	{
		if(vanishedPos == null)
			return false;
		double threshold = 0.5;
		for(UUID seenUuid : seenPearls)
		{
			Vec3 seenPos = lastPearlPositions.get(seenUuid);
			if(seenPos != null
				&& seenPos.distanceToSqr(vanishedPos) < threshold * threshold)
				return true;
		}
		return false;
	}
	
	/**
	 * Checks whether a player with the given UUID is currently visible
	 * among the entities being rendered in the world.
	 */
	private boolean isPlayerVisibleInWorld(UUID playerUuid)
	{
		if(playerUuid == null || MC.level == null)
			return false;
		for(Entity entity : MC.level.entitiesForRendering())
		{
			if(entity instanceof Player && playerUuid.equals(entity.getUUID()))
				return true;
		}
		return false;
	}
	
	/**
	 * Adds or refreshes an offline stasis label, de-duplicating by position.
	 * If an existing offline label exists at approximately the same position,
	 * its timestamp is refreshed instead of creating a duplicate.
	 */
	private void putOfflineStasisLabel(UUID vanishedUuid, String text, Vec3 pos,
		long now)
	{
		double threshold = 0.5;
		long untilMs = now + OFFLINE_STASIS_STICKY_MS;
		
		// Check for an existing offline label at the same position
		for(Map.Entry<UUID, StasisLabel> entry : offlineStasisLabels.entrySet())
		{
			StasisLabel existing = entry.getValue();
			if(existing != null && existing.pos() != null
				&& existing.pos().distanceToSqr(pos) < threshold * threshold)
			{
				// Refresh the existing entry instead of adding a duplicate
				offlineStasisLabels.put(entry.getKey(),
					new StasisLabel(existing.text(), existing.pos(), untilMs));
				return;
			}
		}
		
		offlineStasisLabels.put(vanishedUuid,
			new StasisLabel(text, pos, untilMs));
	}
	
	private float computeDistanceScale(Vec3 labelPos, float baseScale)
	{
		if(MC.player == null)
			return baseScale;
		
		Vec3 cam = RenderUtils.getCameraPos();
		double dist = cam.distanceTo(labelPos);
		float scale = baseScale * (float)Math.max(1.0, dist * 0.1);
		
		double nearRef = 6.0;
		double maxRef = 256.0;
		double t = (dist - nearRef) / (maxRef - nearRef);
		t = Mth.clamp(t, 0.0, 1.0);
		t = t * t * (3.0 - 2.0 * t);
		double factor = 1.80 + (0.90 - 1.80) * t;
		scale *= (float)Mth.clamp(factor, 0.75, 2.50);
		
		return scale;
	}
	
	private int getStasisLineColor()
	{
		int c = stasisColor.getColorI();
		return stasisFlash.isChecked() ? RenderUtils.flashColor(c) : c;
	}
	
	private int getStasisFillColor()
	{
		int c = stasisColor.getColorI(0x50);
		return stasisFlash.isChecked() ? RenderUtils.flashColor(c) : c;
	}
	
	private String formatOfflineLabelText(UUID pearlUuid, String label)
	{
		OwnerConfidence conf = getPearlConfidence(pearlUuid);
		if(conf == OwnerConfidence.INFERRED_JOIN_TIMING
			|| conf == OwnerConfidence.INFERRED_LEAVE_TIMING)
			return label + " (Inferred, Offline)";
		
		return label;
	}
	
	// ---- Per-server file persistence ----
	
	private void resolveCurrentFile()
	{
		String key = resolveServerKey();
		if(key.equals(lastServerKey) && currentFile != null)
			return;
		
		lastServerKey = key;
		currentFile = resolveDataFile(key);
		loadOwnershipData();
	}
	
	private String resolveServerKey()
	{
		ServerData info = MC.getCurrentServer();
		if(info != null)
		{
			if(info.ip != null && !info.ip.isEmpty())
				return info.ip.replace(':', '_');
			if(info.isRealm())
				return "realms_" + (info.name == null ? "" : info.name);
			if(info.name != null && !info.name.isEmpty())
				return "server_" + info.name;
		}
		if(MC.hasSingleplayerServer())
			return "singleplayer";
		
		// Fallback via ServerObserver
		var observer = WURST.getServerObserver();
		if(observer != null)
		{
			String addr = observer.getServerAddress();
			if(addr != null && !addr.isBlank())
				return addr.replace(':', '_');
		}
		
		return "unknown";
	}
	
	private Path resolveDataFile(String serverKey)
	{
		String safe = serverKey.replaceAll("[^a-zA-Z0-9._-]", "_");
		return WURST.getWurstFolder().resolve("pearlesp")
			.resolve(safe + ".json");
	}
	
	private void loadOwnershipData()
	{
		resolveCurrentFile();
		if(currentFile == null || !Files.exists(currentFile))
			return;
		
		try
		{
			JsonObject root =
				JsonUtils.parseFile(currentFile).getAsJsonObject();
			
			// Load pearl owner UUID mappings
			if(root.has("pearlOwners")
				&& root.get("pearlOwners").isJsonObject())
			{
				JsonObject owners = root.getAsJsonObject("pearlOwners");
				for(String key : owners.keySet())
				{
					try
					{
						UUID pearlUuid = UUID.fromString(key);
						UUID ownerUuid =
							UUID.fromString(owners.get(key).getAsString());
						pearlOwnerUuids.put(pearlUuid, ownerUuid);
					}catch(IllegalArgumentException ignored)
					{}
				}
			}
			
			// Load pearl owner labels
			if(root.has("pearlLabels")
				&& root.get("pearlLabels").isJsonObject())
			{
				JsonObject labels = root.getAsJsonObject("pearlLabels");
				for(String key : labels.keySet())
				{
					try
					{
						UUID pearlUuid = UUID.fromString(key);
						String label = labels.get(key).getAsString();
						if(!label.isBlank())
							pearlOwnerLabels.put(pearlUuid, label);
					}catch(IllegalArgumentException ignored)
					{}
				}
			}
			
			// Load known player names
			if(root.has("playerNames")
				&& root.get("playerNames").isJsonObject())
			{
				JsonObject names = root.getAsJsonObject("playerNames");
				for(String key : names.keySet())
				{
					try
					{
						UUID uuid = UUID.fromString(key);
						String name = names.get(key).getAsString();
						if(!name.isBlank())
							knownPlayerNames.put(uuid, name);
					}catch(IllegalArgumentException ignored)
					{}
				}
			}
		}catch(Exception e)
		{
			ChatUtils
				.error("PearlESP ownership load failed: " + e.getMessage());
		}
	}
	
	private void saveOwnershipData(boolean force)
	{
		if(!persistOwnership.isChecked())
			return;
		
		resolveCurrentFile();
		if(currentFile == null)
			return;
		
		long now = System.currentTimeMillis();
		if(!force && now - lastSaveAt < 2000L)
			return;
		
		JsonObject root = new JsonObject();
		
		// Save pearl owner UUIDs
		JsonObject owners = new JsonObject();
		for(Map.Entry<UUID, UUID> entry : pearlOwnerUuids.entrySet())
			owners.addProperty(entry.getKey().toString(),
				entry.getValue().toString());
		root.add("pearlOwners", owners);
		
		// Save pearl owner labels
		JsonObject labels = new JsonObject();
		for(Map.Entry<UUID, String> entry : pearlOwnerLabels.entrySet())
			labels.addProperty(entry.getKey().toString(), entry.getValue());
		root.add("pearlLabels", labels);
		
		// Save known player names
		JsonObject names = new JsonObject();
		for(Map.Entry<UUID, String> entry : knownPlayerNames.entrySet())
			names.addProperty(entry.getKey().toString(), entry.getValue());
		root.add("playerNames", names);
		
		try
		{
			Files.createDirectories(currentFile.getParent());
			JsonUtils.toJson(root, currentFile);
			dirty = false;
			lastSaveAt = now;
		}catch(IOException | net.wurstclient.util.json.JsonException e)
		{
			ChatUtils
				.error("PearlESP ownership save failed: " + e.getMessage());
		}
	}
	
	private void saveIfNeeded(boolean force)
	{
		if(!dirty || currentFile == null)
			return;
		long now = System.currentTimeMillis();
		if(!force && now - lastSaveAt < 2000L)
			return;
		
		saveOwnershipData(force);
	}
	
	private void clearOwnershipLog()
	{
		resolveCurrentFile();
		if(currentFile != null)
		{
			try
			{
				Files.deleteIfExists(currentFile);
			}catch(IOException ignored)
			{}
		}
		
		pearlOwnerUuids.clear();
		pearlOwnerLabels.clear();
		pearlIdentities.clear();
		stasisLabels.clear();
		dirty = false;
		
		ChatUtils.message("PearlESP: Cleared ownership data for this server.");
	}
	
	// ---- Records and enums ----
	
	private enum OwnerConfidence
	{
		UNKNOWN,
		CONFIRMED_CURRENT_ENTITY_ID,
		CONFIRMED_PEARL_UUID_CACHE,
		HISTORICAL_ENTITY_ID,
		INFERRED_JOIN_TIMING,
		INFERRED_LEAVE_TIMING
	}
	
	private record EntityIdBinding(UUID playerUuid, long validFromMs,
		long validUntilMs, boolean active)
	{
		EntityIdBinding withInactive(long now)
		{
			return new EntityIdBinding(playerUuid, validFromMs, now, false);
		}
	}
	
	private record PlayerIdentity(UUID uuid, String name, int currentEntityId,
		long firstSeenMs, long lastSeenMs)
	{
		PlayerIdentity withEntityId(int entityId, long now)
		{
			return new PlayerIdentity(uuid, name, entityId, firstSeenMs, now);
		}
		
		PlayerIdentity withName(String newName, long now)
		{
			return new PlayerIdentity(uuid, newName, currentEntityId,
				firstSeenMs, now);
		}
	}
	
	private record PearlIdentity(UUID pearlUuid, int currentEntityId,
		UUID ownerUuid, Integer ownerEntityId, OwnerConfidence confidence,
		long firstSeenMs, long lastSeenMs)
	{
		PearlIdentity withOwner(UUID newOwnerUuid,
			OwnerConfidence newConfidence, long now)
		{
			return new PearlIdentity(pearlUuid, currentEntityId, newOwnerUuid,
				ownerEntityId, newConfidence, firstSeenMs, now);
		}
		
		PearlIdentity withEntityId(int entityId, Integer newOwnerEntityId,
			long now)
		{
			return new PearlIdentity(pearlUuid, entityId, ownerUuid,
				newOwnerEntityId, confidence, firstSeenMs, now);
		}
	}
	
	private record TrackedPearl(Entity pearl, PearlPrediction prediction,
		String ownerLabel, StasisLabel stasisLabel)
	{}
	
	private record PearlPrediction(List<Vec3> path, Vec3 landing)
	{}
	
	private record HeldPearl(Player player, InteractionHand hand)
	{}
	
	private record StasisLabel(String text, Vec3 pos, long untilMs)
	{}
	
	private enum AlertSound
	{
		NOTE_BLOCK_HARP("Note Block Harp", "minecraft:block.note_block.harp"),
		NOTE_BLOCK_BASS("Note Block Bass", "minecraft:block.note_block.bass"),
		NOTE_BLOCK_BASEDRUM("Note Block Basedrum",
			"minecraft:block.note_block.basedrum"),
		NOTE_BLOCK_SNARE("Note Block Snare",
			"minecraft:block.note_block.snare"),
		NOTE_BLOCK_HAT("Note Block Hat", "minecraft:block.note_block.hat"),
		NOTE_BLOCK_GUITAR("Note Block Guitar",
			"minecraft:block.note_block.guitar"),
		NOTE_BLOCK_FLUTE("Note Block Flute",
			"minecraft:block.note_block.flute"),
		NOTE_BLOCK_BELL("Note Block Bell", "minecraft:block.note_block.bell"),
		NOTE_BLOCK_CHIME("Note Block Chime",
			"minecraft:block.note_block.chime"),
		NOTE_BLOCK_XYLOPHONE("Note Block Xylophone",
			"minecraft:block.note_block.xylophone"),
		NOTE_BLOCK_IRON_XYLOPHONE("Note Block Iron Xylophone",
			"minecraft:block.note_block.iron_xylophone"),
		NOTE_BLOCK_COW_BELL("Note Block Cow Bell",
			"minecraft:block.note_block.cow_bell"),
		NOTE_BLOCK_DIDGERIDOO("Note Block Didgeridoo",
			"minecraft:block.note_block.didgeridoo"),
		NOTE_BLOCK_BIT("Note Block Bit", "minecraft:block.note_block.bit"),
		NOTE_BLOCK_BANJO("Note Block Banjo",
			"minecraft:block.note_block.banjo"),
		NOTE_BLOCK_PLING("Note Block Pling",
			"minecraft:block.note_block.pling"),
		XP_PICKUP("XP Pickup", "minecraft:entity.experience_orb.pickup"),
		UI_BUTTON_CLICK("UI Button Click", "minecraft:ui.button.click"),
		AMETHYST_CHIME("Amethyst Chime",
			"minecraft:block.amethyst_block.chime"),
		BELL_USE("Bell Use", "minecraft:block.bell.use"),
		ITEM_PICKUP("Item Pickup", "minecraft:entity.item.pickup");
		
		private final String displayName;
		private final String id;
		
		private AlertSound(String displayName, String id)
		{
			this.displayName = displayName;
			this.id = id;
		}
		
		private SoundEvent resolve()
		{
			try
			{
				return BuiltInRegistries.SOUND_EVENT
					.getValue(Identifier.parse(id));
			}catch(Exception e)
			{
				return null;
			}
		}
		
		@Override
		public String toString()
		{
			return displayName;
		}
	}
}
