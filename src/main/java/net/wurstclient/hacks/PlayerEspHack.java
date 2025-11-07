/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.Util;
import net.minecraft.util.hit.HitResult;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;
import net.minecraft.world.RaycastContext;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.EspBoxSizeSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.settings.filters.FilterInvisibleSetting;
import net.wurstclient.settings.filters.FilterSleepingSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.FakePlayerEntity;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;

@SearchTags({"player esp", "PlayerTracers", "player tracers"})
public final class PlayerEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final PlayerEspStyleSetting style =
		new PlayerEspStyleSetting(PlayerEspStyleSetting.Style.LINES_AND_GLOW);
	
	private final EspBoxSizeSetting boxSize = new EspBoxSizeSetting(
		"\u00a7lAccurate\u00a7r mode shows the exact hitbox of each player.\n"
			+ "\u00a7lFancy\u00a7r mode shows slightly larger boxes that look better.");
	
	private final EntityFilterList entityFilters = new EntityFilterList(
		new FilterSleepingSetting("Won't show sleeping players.", false),
		new FilterInvisibleSetting("Won't show invisible players.", false));
	
	private final ArrayList<PlayerEntity> players = new ArrayList<>();
	// Alert settings & tracking for enter/exit notifications
	private final CheckboxSetting enterAlert = new CheckboxSetting(
		"Enter alert",
		"When enabled, notifies in chat when a player first becomes visible\n"
			+ "to PlayerESP, showing distance and XYZ.",
		false);
	private final CheckboxSetting exitAlert = new CheckboxSetting("Exit alert",
		"When enabled, notifies in chat when a player leaves PlayerESP\n"
			+ "visibility, showing distance and XYZ at which they left.",
		false);
	private final Set<UUID> prevVisible = new HashSet<>();
	private final Map<UUID, Vec3d> lastPositions = new HashMap<>();
	private final Map<UUID, String> lastNames = new HashMap<>();
	private final CheckboxSetting randomBrightColors = new CheckboxSetting(
		"Unique colors for players",
		"When enabled, assigns each player a bright color from a shared\n"
			+ "palette and forces it into the shared color registry.\n"
			+ "PlayerESP takes ownership of these colors (overrides Breadcrumbs).",
		false);
	private final CheckboxSetting losThreatDetection = new CheckboxSetting(
		"Line-of-sight detection",
		"Highlights players who currently have direct line of sight on you\n"
			+ "and temporarily overrides their ESP color.",
		false);
	private final SliderSetting losThreatFov =
		new SliderSetting("LOS FOV (degrees)", 140, 30, 180, 1,
			SliderSetting.ValueDisplay.INTEGER);
	private final SliderSetting losThreatRange =
		new SliderSetting("LOS detection range", 100, 16, 100, 1,
			SliderSetting.ValueDisplay.INTEGER);
	private final CheckboxSetting ignoreNpcs = new CheckboxSetting(
		"Ignore NPCs",
		"When enabled, players not present on the client's tab-list are\n"
			+ "considered likely NPCs and will be ignored. This filters common\n"
			+ "server-side NPCs but may hide real players who are intentionally\n"
			+ "hidden from the tab-list.",
		true);
	private final SliderSetting tracerThickness =
		new SliderSetting("Tracer thickness", 2, 0.5, 8, 0.1,
			SliderSetting.ValueDisplay.DECIMAL.withSuffix("px"));
	private final CheckboxSetting filledBoxes = new CheckboxSetting(
		"Filled boxes",
		"When enabled, renders solid filled boxes instead of outlined boxes.",
		false);
	private final CheckboxSetting useStaticPlayerColor = new CheckboxSetting(
		"Use static player color",
		"When enabled, uses the selected static color for all players and\n"
			+ "forces it into the shared color registry. PlayerESP owns the\n"
			+ "assignment while this is enabled (will override Breadcrumbs).",
		false);
	private final ColorSetting playerColor = new ColorSetting("Player color",
		"Static color used when 'Use static player color' is enabled.",
		new Color(255, 196, 64));
	private final net.wurstclient.settings.SliderSetting filledAlpha =
		new net.wurstclient.settings.SliderSetting("Filled box alpha", 35, 0,
			100, 1,
			net.wurstclient.settings.SliderSetting.ValueDisplay.INTEGER);
	private final Map<UUID, LosState> losStates = new HashMap<>();
	private static final long LOS_HOLD_MS = 250;
	private static final long LOS_FADE_MS = 120;
	private static final double THREAT_LINE_WIDTH = 4.0; // base thickness of
															// threat lines
	
	public PlayerEspHack()
	{
		super("PlayerESP");
		setCategory(Category.RENDER);
		addSetting(style);
		addSetting(randomBrightColors);
		addSetting(losThreatDetection);
		addSetting(losThreatFov);
		addSetting(losThreatRange);
		addSetting(tracerThickness);
		addSetting(filledBoxes);
		addSetting(filledAlpha);
		addSetting(useStaticPlayerColor);
		addSetting(playerColor);
		addSetting(boxSize);
		
		// Alerts when players enter/leave PlayerESP visibility
		addSetting(enterAlert);
		addSetting(exitAlert);
		entityFilters.forEach(this::addSetting);
		addSetting(ignoreNpcs);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		losStates.clear();
		prevVisible.clear();
		lastPositions.clear();
		lastNames.clear();
	}
	
	@Override
	public void onUpdate()
	{
		players.clear();
		
		Stream<AbstractClientPlayerEntity> stream = MC.world.getPlayers()
			.parallelStream().filter(e -> !e.isRemoved() && e.getHealth() > 0)
			.filter(e -> e != MC.player)
			.filter(e -> !(e instanceof FakePlayerEntity))
			.filter(e -> Math.abs(e.getY() - MC.player.getY()) <= 1e6);
		
		// If enabled, filter out players that aren't present on the client's
		// player list (likely NPCs spawned by server plugins).
		if(ignoreNpcs.isChecked())
		{
			stream = stream.filter(e -> {
				if(MC.getNetworkHandler() == null)
					return true;
				return MC.getNetworkHandler()
					.getPlayerListEntry(e.getUuid()) != null;
			});
		}
		
		stream = entityFilters.applyTo(stream);
		
		players.addAll(stream.collect(Collectors.toList()));
		
		// detect enter / exit visibility changes
		handleVisibilityChanges();
		
		if(losThreatDetection.isChecked())
			updateLosStates(Util.getMeasuringTimeMs());
		else
			losStates.clear();
	}
	
	/**
	 * Detect which players entered or left the PlayerESP-visible list since
	 * the last update and send chat alerts if configured.
	 */
	private void handleVisibilityChanges()
	{
		// Build a set of currently visible *non-bot* players. Use the same
		// bot/NPC checks PlayerESP already uses: FakePlayerEntity and
		// (when enabled) players missing from the client's tab-list.
		Set<UUID> currentNonBot = new HashSet<>();
		
		// Added players (only non-bots)
		for(PlayerEntity p : players)
		{
			// skip known fake players
			if(p instanceof FakePlayerEntity)
				continue;
			
			UUID id = p.getUuid();
			
			// If ignoreNpcs is enabled, treat players not on the client
			// player list as bots/NPCs and ignore them for alerts.
			if(ignoreNpcs.isChecked() && MC.getNetworkHandler() != null
				&& MC.getNetworkHandler().getPlayerListEntry(id) == null)
			{
				continue;
			}
			
			currentNonBot.add(id);
			lastPositions.put(id, new Vec3d(p.getX(), p.getY(), p.getZ()));
			lastNames.put(id, p.getName().getString());
			if(!prevVisible.contains(id) && enterAlert.isChecked())
				sendEnterMessage(p);
		}
		
		// Removed players (only consider previously tracked non-bot IDs)
		for(UUID id : new HashSet<>(prevVisible))
		{
			if(!currentNonBot.contains(id))
			{
				Vec3d pos = lastPositions.get(id);
				String name = lastNames.getOrDefault(id, "<unknown>");
				if(exitAlert.isChecked())
					sendExitMessage(id, name, pos);
				lastPositions.remove(id);
				lastNames.remove(id);
				prevVisible.remove(id);
			}
		}
		
		prevVisible.clear();
		prevVisible.addAll(currentNonBot);
	}
	
	private void sendEnterMessage(PlayerEntity p)
	{
		if(MC.player == null)
			return;
		UUID id = p.getUuid();
		double dist = Math.round(MC.player.distanceTo(p) * 10.0) / 10.0;
		int x = (int)Math.round(p.getX());
		int y = (int)Math.round(p.getY());
		int z = (int)Math.round(p.getZ());
		MutableText nameText =
			MutableText.of(Text.literal(p.getName().getString()).getContent());
		if(randomBrightColors.isChecked())
		{
			int idx = Math.abs(id.hashCode());
			java.awt.Color gen = net.wurstclient.util.PlayerColorRegistry
				.generateBrightColor(idx);
			nameText.setStyle(nameText.getStyle().withColor(TextColor.fromRgb(
				(gen.getRed() << 16) | (gen.getGreen() << 8) | gen.getBlue())));
		}
		Text msg = nameText.append(Text
			.literal(" entered range (" + dist + " blocks) at " + x + ", " + y
				+ ", " + z + ".")
			.styled(
				s -> s.withColor(TextColor.fromFormatting(Formatting.WHITE))));
		ChatUtils.component(msg);
	}
	
	private void sendExitMessage(UUID id, String name, Vec3d pos)
	{
		if(MC.player == null)
			return;
		double dist = pos == null ? -1.0
			: Math.round(pos.distanceTo(
				new Vec3d(MC.player.getX(), MC.player.getY(), MC.player.getZ()))
				* 10.0) / 10.0;
		int x = pos == null ? 0 : (int)Math.round(pos.x);
		int y = pos == null ? 0 : (int)Math.round(pos.y);
		int z = pos == null ? 0 : (int)Math.round(pos.z);
		MutableText nameText = MutableText.of(Text.literal(name).getContent());
		if(randomBrightColors.isChecked())
		{
			int idx = Math.abs(id.hashCode());
			java.awt.Color gen = net.wurstclient.util.PlayerColorRegistry
				.generateBrightColor(idx);
			nameText.setStyle(nameText.getStyle().withColor(TextColor.fromRgb(
				(gen.getRed() << 16) | (gen.getGreen() << 8) | gen.getBlue())));
		}
		String distStr = dist < 0 ? "unknown" : (dist + " blocks");
		Text msg = nameText.append(Text
			.literal(" left range (" + distStr + ") at " + x + ", " + y + ", "
				+ z + ".")
			.styled(
				s -> s.withColor(TextColor.fromFormatting(Formatting.WHITE))));
		ChatUtils.component(msg);
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(style.hasLines())
			event.cancel();
	}
	
	@Override
	public void onRender(MatrixStack matrixStack, float partialTicks)
	{
		long now = Util.getMeasuringTimeMs();
		Map<UUID, PlayerVisual> visualCache = new HashMap<>(players.size());
		
		if(style.hasBoxes())
		{
			double extraSize = boxSize.getExtraSize() / 2;
			
			ArrayList<ColoredBox> normalOutline = new ArrayList<>();
			ArrayList<ColoredBox> threatOutline = new ArrayList<>();
			ArrayList<ColoredBox> solid = filledBoxes.isChecked()
				? new ArrayList<>(players.size()) : null;
			
			for(PlayerEntity e : players)
			{
				Box box = EntityUtils.getLerpedBox(e, partialTicks)
					.offset(0, extraSize, 0).expand(extraSize);
				PlayerVisual visual = visualCache.computeIfAbsent(e.getUuid(),
					id -> getVisual(e, now));
				int boxColor = visual.boxColor();
				
				if(filledBoxes.isChecked())
				{
					int rgb = boxColor & 0x00FFFFFF;
					int solidAlpha =
						(int)((filledAlpha.getValue() / 100f) * 255) << 24;
					int solidColor = rgb | solidAlpha;
					if(solid != null)
						solid.add(new ColoredBox(box, solidColor));
					int outlineColor = rgb | (0xFF << 24);
					ColoredBox outlineBox = new ColoredBox(box, outlineColor);
					if(visual.isThreat())
						threatOutline.add(outlineBox);
					else
						normalOutline.add(outlineBox);
				}else
				{
					ColoredBox cb = new ColoredBox(box, boxColor);
					if(visual.isThreat())
						threatOutline.add(cb);
					else
						normalOutline.add(cb);
				}
			}
			
			if(filledBoxes.isChecked())
			{
				if(solid != null && !solid.isEmpty())
					RenderUtils.drawSolidBoxes(matrixStack, solid, false);
				if(!normalOutline.isEmpty())
					RenderUtils.drawOutlinedBoxes(matrixStack, normalOutline,
						false);
				if(!threatOutline.isEmpty())
					RenderUtils.drawOutlinedBoxes(matrixStack, threatOutline,
						false, THREAT_LINE_WIDTH);
			}else
			{
				if(!normalOutline.isEmpty())
					RenderUtils.drawOutlinedBoxes(matrixStack, normalOutline,
						false);
				if(!threatOutline.isEmpty())
					RenderUtils.drawOutlinedBoxes(matrixStack, threatOutline,
						false, THREAT_LINE_WIDTH);
			}
		}
		
		if(style.hasLines())
		{
			ArrayList<ColoredPoint> normalEnds =
				new ArrayList<>(players.size());
			ArrayList<ColoredPoint> threatEnds = new ArrayList<>();
			
			for(PlayerEntity e : players)
			{
				PlayerVisual visual = visualCache.computeIfAbsent(e.getUuid(),
					id -> getVisual(e, now));
				Vec3d point =
					EntityUtils.getLerpedBox(e, partialTicks).getCenter();
				ColoredPoint colored =
					new ColoredPoint(point, visual.tracerColor());
				if(visual.isThreat())
					threatEnds.add(colored);
				else
					normalEnds.add(colored);
			}
			
			double normalLineWidth = tracerThickness.getValue();
			double threatLineWidth = normalLineWidth + Math.max(0,
				THREAT_LINE_WIDTH - tracerThickness.getDefaultValue());
			if(!normalEnds.isEmpty())
				RenderUtils.drawTracers(matrixStack, partialTicks, normalEnds,
					false, normalLineWidth);
			if(!threatEnds.isEmpty())
				RenderUtils.drawTracers(matrixStack, partialTicks, threatEnds,
					false, threatLineWidth);
		}
	}
	
	public Integer getGlowColor(LivingEntity entity)
	{
		if(!isEnabled())
			return null;
		if(!style.hasGlow())
			return null;
		if(!(entity instanceof PlayerEntity player))
			return null;
		if(!players.contains(player))
			return null;
		
		return makeOpaque(getBaseColor(player));
	}
	
	private int getBaseColor(PlayerEntity e)
	{
		if(WURST.getFriends().contains(e.getName().getString()))
			return 0x800000FF;
		// If PlayerESP enforces a static color, force it into the registry so
		// PlayerESP always overrides Breadcrumbs. Return that color.
		if(useStaticPlayerColor.isChecked())
		{
			java.awt.Color pc = playerColor.getColor();
			net.wurstclient.util.PlayerColorRegistry.forceAssign(e.getUuid(),
				pc, "PlayerESP");
			return RenderUtils.toIntColor(new float[]{pc.getRed() / 255f,
				pc.getGreen() / 255f, pc.getBlue() / 255f}, 0.85F);
		}
		
		// If PlayerESP requests random bright colors, generate one
		// deterministically and force-assign it so PlayerESP overrides
		// Breadcrumbs.
		if(randomBrightColors.isChecked())
		{
			int idx = Math.abs(e.getUuid().hashCode());
			java.awt.Color gen = net.wurstclient.util.PlayerColorRegistry
				.generateBrightColor(idx);
			net.wurstclient.util.PlayerColorRegistry.forceAssign(e.getUuid(),
				gen, "PlayerESP");
			return RenderUtils.toIntColor(new float[]{gen.getRed() / 255f,
				gen.getGreen() / 255f, gen.getBlue() / 255f}, 0.9F);
		}
		
		// If neither static nor random are enabled, remove any PlayerESP-owned
		// registry entries so other hacks' colors can show. Then fall back to
		// dynamic distance-based coloring.
		if(!useStaticPlayerColor.isChecked() && !randomBrightColors.isChecked())
		{
			// remove all registry assignments owned by PlayerESP
			net.wurstclient.util.PlayerColorRegistry.removeByOwner("PlayerESP");
			// Continue to distance-based dynamic coloring below
		}
		
		// Only consult the shared registry when PlayerESP has explicitly opted
		// into owning colors. This prevents Breadcrumbs (or other hacks)
		// changing PlayerESP colors unexpectedly.
		if(useStaticPlayerColor.isChecked() || randomBrightColors.isChecked())
		{
			java.awt.Color reg2 =
				net.wurstclient.util.PlayerColorRegistry.get(e.getUuid());
			if(reg2 != null)
			{
				return RenderUtils
					.toIntColor(
						new float[]{reg2.getRed() / 255f,
							reg2.getGreen() / 255f, reg2.getBlue() / 255f},
						0.9F);
			}
		}
		
		// Otherwise fall back to the dynamic distance-based coloring (default).
		
		float f = MC.player.distanceTo(e) / 20F;
		float r = MathHelper.clamp(2 - f, 0, 1);
		float g = MathHelper.clamp(f, 0, 1);
		float[] rgb = {r, g, 0};
		return RenderUtils.toIntColor(rgb, 0.5F);
	}
	
	private PlayerVisual getVisual(PlayerEntity e, long now)
	{
		int baseColor = getBaseColor(e);
		if(!losThreatDetection.isChecked())
			return new PlayerVisual(baseColor, makeOpaque(baseColor), 0F);
		
		float factor = getLosFactor(e, now);
		int boxColor = mixThreatColor(baseColor, factor, false);
		int tracerColor = mixThreatColor(makeOpaque(baseColor), factor, true);
		return new PlayerVisual(boxColor, tracerColor, factor);
	}
	
	private float getLosFactor(PlayerEntity e, long now)
	{
		LosState state = losStates.get(e.getUuid());
		if(state == null)
			return 0F;
		
		if(state.los)
			return 1F;
		
		if(now <= state.holdUntil)
			return 1F;
		
		if(state.fadeUntil > now)
		{
			long fadeStart = state.fadeUntil - LOS_FADE_MS;
			long elapsed = Math.max(0L, now - fadeStart);
			float progress = 1F - (float)elapsed / (float)LOS_FADE_MS;
			return MathHelper.clamp(progress, 0F, 1F);
		}
		
		return 0F;
	}
	
	private float getFovDotThreshold()
	{
		double fovDegrees = MathHelper.clamp(losThreatFov.getValue(), 1, 180);
		double halfAngle = fovDegrees / 2.0;
		return (float)Math.cos(Math.toRadians(halfAngle));
	}
	
	private int mixThreatColor(int baseColor, float factor, boolean tracer)
	{
		if(factor <= 0F)
			return tracer ? makeOpaque(baseColor) : baseColor;
		
		if(tracer)
			baseColor = makeOpaque(baseColor);
		
		float clampedFactor = MathHelper.clamp(factor, 0F, 1F);
		
		float baseA = ((baseColor >>> 24) & 0xFF) / 255F;
		float baseR = ((baseColor >>> 16) & 0xFF) / 255F;
		float baseG = ((baseColor >>> 8) & 0xFF) / 255F;
		float baseB = (baseColor & 0xFF) / 255F;
		
		float threatAlpha =
			MathHelper.clamp(baseA + (tracer ? 0.45F : 0.35F), 0F, 1F);
		
		float inv = 1F - clampedFactor;
		float r = 1F * clampedFactor + baseR * inv;
		float g = baseG * inv;
		float b = baseB * inv;
		float a = tracer ? 1F : threatAlpha * clampedFactor + baseA * inv;
		
		return RenderUtils.toIntColor(new float[]{r, g, b}, a);
	}
	
	private static int makeOpaque(int color)
	{
		return color | 0xFF000000;
	}
	
	private void updateLosStates(long now)
	{
		if(MC.player == null || MC.world == null)
			return;
		
		for(LosState state : losStates.values())
			state.touched = false;
		
		for(PlayerEntity player : players)
		{
			LosState state = losStates.computeIfAbsent(player.getUuid(),
				uuid -> new LosState(uuid, now));
			state.touched = true;
			updateLosState(player, state, now);
		}
		
		losStates.entrySet().removeIf(entry -> !entry.getValue().touched);
	}
	
	private void updateLosState(PlayerEntity target, LosState state, long now)
	{
		PlayerEntity self = MC.player;
		if(self == null)
			return;
		
		double maxRange = losThreatRange.getValue();
		double maxRangeSq = maxRange * maxRange;
		double distSq = target.squaredDistanceTo(self);
		
		if(distSq > maxRangeSq)
		{
			state.setLos(false, now);
			state.scheduleNext(now);
			return;
		}
		
		if(now < state.nextCheckAt)
			return;
		
		boolean hasLos = computeLineOfSight(target);
		state.setLos(hasLos, now);
		state.scheduleNext(now);
	}
	
	private boolean computeLineOfSight(PlayerEntity target)
	{
		PlayerEntity self = MC.player;
		if(self == null || MC.world == null)
			return false;
		
		Box myBox = self.getBoundingBox();
		Vec3d eyePos = new Vec3d(target.getX(),
			target.getY() + target.getStandingEyeHeight(), target.getZ());
		
		if(myBox.contains(eyePos))
			return true;
		
		double clampedX = MathHelper.clamp(eyePos.x, myBox.minX, myBox.maxX);
		double clampedY = MathHelper.clamp(eyePos.y, myBox.minY, myBox.maxY);
		double clampedZ = MathHelper.clamp(eyePos.z, myBox.minZ, myBox.maxZ);
		Vec3d closestPoint = new Vec3d(clampedX, clampedY, clampedZ);
		
		Vec3d dirToYouVec = closestPoint.subtract(eyePos);
		double distance = dirToYouVec.length();
		if(distance < 1e-4)
			return true;
		Vec3d dirToYou = dirToYouVec.normalize();
		
		Vec3d lookVec = target.getRotationVec(1.0F);
		float fovThreshold = getFovDotThreshold();
		if(lookVec.dotProduct(dirToYou) < fovThreshold)
			return false;
		
		Optional<Vec3d> hitOpt = myBox.raycast(eyePos, closestPoint);
		if(hitOpt.isEmpty())
			hitOpt = myBox.raycast(eyePos, myBox.getCenter());
		Vec3d hitPos = hitOpt.orElse(closestPoint);
		
		RaycastContext ctx =
			new RaycastContext(eyePos, hitPos, RaycastContext.ShapeType.VISUAL,
				RaycastContext.FluidHandling.NONE, target);
		HitResult blockHit = MC.world.raycast(ctx);
		
		if(blockHit.getType() == HitResult.Type.MISS)
			return true;
		
		double blockDistSq = blockHit.getPos().squaredDistanceTo(eyePos);
		double targetDistSq = hitPos.squaredDistanceTo(eyePos);
		return blockDistSq >= targetDistSq - 1e-3;
	}
	
	private static final class PlayerVisual
	{
		private final int boxColor;
		private final int tracerColor;
		private final float threatFactor;
		
		private PlayerVisual(int boxColor, int tracerColor, float threatFactor)
		{
			this.boxColor = boxColor;
			this.tracerColor = tracerColor;
			this.threatFactor = threatFactor;
		}
		
		public int boxColor()
		{
			return boxColor;
		}
		
		public int tracerColor()
		{
			return tracerColor;
		}
		
		public boolean isThreat()
		{
			return threatFactor > 0.001F;
		}
	}
	
	private static final class LosState
	{
		private final int checkIntervalMs;
		private long nextCheckAt;
		private boolean los;
		private long holdUntil = Long.MIN_VALUE;
		private long fadeUntil = Long.MIN_VALUE;
		private boolean touched;
		
		private LosState(UUID uuid, long now)
		{
			int hash = Math.floorMod(uuid.hashCode(), 10_000);
			checkIntervalMs = 80 + hash % 90;
			int offset = hash % checkIntervalMs;
			nextCheckAt = now + offset;
		}
		
		private void scheduleNext(long now)
		{
			nextCheckAt = now + checkIntervalMs;
		}
		
		private void setLos(boolean value, long now)
		{
			if(los == value)
				return;
			
			los = value;
			if(value)
			{
				holdUntil = Long.MIN_VALUE;
				fadeUntil = Long.MIN_VALUE;
			}else
			{
				holdUntil = now + LOS_HOLD_MS;
				fadeUntil = holdUntil + LOS_FADE_MS;
			}
		}
	}
	
	private static final class PlayerEspStyleSetting
		extends EnumSetting<PlayerEspStyleSetting.Style>
	{
		private PlayerEspStyleSetting(Style defaultStyle)
		{
			super("Style", Style.values(), defaultStyle);
		}
		
		public boolean hasBoxes()
		{
			return getSelected().boxes;
		}
		
		public boolean hasLines()
		{
			return getSelected().lines;
		}
		
		public boolean hasGlow()
		{
			return getSelected().glow;
		}
		
		private enum Style
		{
			BOXES("Boxes only", true, false, false),
			LINES("Lines only", false, true, false),
			LINES_AND_BOXES("Lines and boxes", true, true, false),
			GLOW("Glow only", false, false, true),
			LINES_AND_GLOW("Lines and glow", false, true, true);
			
			private final String name;
			private final boolean boxes;
			private final boolean lines;
			private final boolean glow;
			
			private Style(String name, boolean boxes, boolean lines,
				boolean glow)
			{
				this.name = name;
				this.boxes = boxes;
				this.lines = lines;
				this.glow = glow;
			}
			
			@Override
			public String toString()
			{
				return name;
			}
		}
	}
}
