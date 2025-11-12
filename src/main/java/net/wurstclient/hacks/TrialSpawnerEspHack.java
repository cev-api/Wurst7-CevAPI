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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.VaultBlock;
import net.minecraft.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.block.entity.VaultBlockEntity;
import net.minecraft.block.enums.TrialSpawnerState;
import net.minecraft.block.enums.VaultState;
import net.minecraft.block.spawner.TrialSpawnerConfig;
import net.minecraft.block.spawner.TrialSpawnerData;
import net.minecraft.block.spawner.TrialSpawnerLogic;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.font.TextRenderer.TextLayerType;
import net.minecraft.client.render.VertexConsumerProvider.Immediate;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RotationAxis;
import net.minecraft.util.math.Vec3d;

import net.wurstclient.Category;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.CameraTransformViewBobbingListener.CameraTransformViewBobbingEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixin.TrialSpawnerDataAccessor;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RenderUtils.ColoredBox;
import net.wurstclient.util.RenderUtils.ColoredPoint;
import net.wurstclient.util.chunk.ChunkUtils;

public final class TrialSpawnerEspHack extends Hack implements UpdateListener,
	CameraTransformViewBobbingListener, RenderListener
{
	private final SliderSetting maxDistance =
		new SliderSetting("Max distance", 160, 0, 256, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting drawTracers =
		new CheckboxSetting("Show tracer", true);
	private final CheckboxSetting fillShapes =
		new CheckboxSetting("Fill boxes", true);
	private final CheckboxSetting showOverlay =
		new CheckboxSetting("Text overlay", true);
	private final SliderSetting overlayScale = new SliderSetting(
		"Overlay scale", 1.0, 0.5, 2.0, 0.05, ValueDisplay.DECIMAL);
	private final CheckboxSetting showMobType =
		new CheckboxSetting("Show mob type", true);
	private final CheckboxSetting showStatus =
		new CheckboxSetting("Show status", true);
	// removed wave info setting (simplified status display)
	private final CheckboxSetting showNextSpawn =
		new CheckboxSetting("Show next wave", true);
	private final ColorSetting vaultBoxColor =
		new ColorSetting("Vault box color", new Color(0xFF7CF2C9));
	private final ColorSetting ominousVaultBoxColor =
		new ColorSetting("Ominous vault color", new Color(0xFF9B59B6));
	private final CheckboxSetting showDistance =
		new CheckboxSetting("Show distance", true);
	private final CheckboxSetting showTrialType =
		new CheckboxSetting("Show trial type", true);
	private final CheckboxSetting showActivationRadius =
		new CheckboxSetting("Show activation radius", true);
	private final CheckboxSetting showVaultLink =
		new CheckboxSetting("Show vault link", true);
	private final SliderSetting vaultLinkRange = new SliderSetting(
		"Vault link range", 48, 8, 96, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting showCountInHackList =
		new CheckboxSetting("HackList count", false);
	
	private final ColorSetting colorIdle =
		new ColorSetting("Idle color", new Color(0xFFAAAAAA));
	private final ColorSetting colorCharging =
		new ColorSetting("Charging color", new Color(0xFFF4C542));
	private final ColorSetting colorActive =
		new ColorSetting("Active color", new Color(0xFFE5534B));
	private final ColorSetting colorCompleted =
		new ColorSetting("Completed color", new Color(0xFF51C271));
	private final ColorSetting radiusColor =
		new ColorSetting("Radius color", new Color(0xFF66B3FF));
	private final ColorSetting vaultLinkColor =
		new ColorSetting("Vault link color", new Color(0xFF7CF2C9));
	
	private final ArrayList<TrialSpawnerInfo> spawners = new ArrayList<>();
	private final ArrayList<VaultInfo> vaults = new ArrayList<>();
	private int foundCount;
	
	public TrialSpawnerEspHack()
	{
		super("TrialSpawnerESP");
		setCategory(Category.RENDER);
		addSetting(maxDistance);
		addSetting(drawTracers);
		addSetting(fillShapes);
		addSetting(showOverlay);
		addSetting(overlayScale);
		addSetting(showMobType);
		addSetting(showStatus);
		addSetting(showNextSpawn);
		addSetting(showDistance);
		addSetting(showTrialType);
		addSetting(showActivationRadius);
		addSetting(showVaultLink);
		addSetting(vaultLinkRange);
		addSetting(vaultBoxColor);
		addSetting(ominousVaultBoxColor);
		addSetting(showCountInHackList);
		addSetting(colorIdle);
		addSetting(colorCharging);
		addSetting(colorActive);
		addSetting(colorCompleted);
		addSetting(radiusColor);
		addSetting(vaultLinkColor);
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
		spawners.clear();
		vaults.clear();
		foundCount = 0;
	}
	
	@Override
	public void onUpdate()
	{
		spawners.clear();
		if(MC.world == null || MC.player == null)
		{
			vaults.clear();
			foundCount = 0;
			return;
		}
		
		vaults.clear();
		ChunkUtils.getLoadedBlockEntities()
			.filter(be -> be instanceof VaultBlockEntity)
			.map(be -> (VaultBlockEntity)be).forEach(
				be -> vaults.add(new VaultInfo(be.getPos().toImmutable())));
		
		boolean limit = maxDistance.getValue() > 0;
		double maxDistanceSq = maxDistance.getValue() * maxDistance.getValue();
		
		ChunkUtils.getLoadedBlockEntities()
			.filter(be -> be instanceof TrialSpawnerBlockEntity)
			.map(be -> (TrialSpawnerBlockEntity)be).forEach(spawner -> {
				BlockPos pos = spawner.getPos();
				double distSq = MC.player.squaredDistanceTo(pos.getX() + 0.5,
					pos.getY() + 0.5, pos.getZ() + 0.5);
				if(limit && distSq > maxDistanceSq)
					return;
				
				BlockPos immutablePos = pos.toImmutable();
				VaultInfo link = showVaultLink.isChecked()
					? findLinkedVault(immutablePos) : null;
				String decorMob = detectMobFromDecor(immutablePos);
				spawners.add(new TrialSpawnerInfo(spawner, immutablePos, distSq,
					link, decorMob));
			});
		
		foundCount = spawners.size();
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		if(drawTracers.isChecked() || showOverlay.isChecked())
			event.cancel();
	}
	
	@Override
	public void onRender(MatrixStack matrices, float partialTicks)
	{
		if(MC.world == null)
			return;
		
		ArrayList<ColoredBox> outlineBoxes = new ArrayList<>();
		ArrayList<ColoredBox> filledBoxes =
			fillShapes.isChecked() ? new ArrayList<>() : null;
		ArrayList<ColoredPoint> tracerTargets =
			drawTracers.isChecked() ? new ArrayList<>() : null;
		
		// Render vault ESP boxes and simple status labels while hack is enabled
		if(!vaults.isEmpty())
		{
			for(VaultInfo v : vaults)
			{
				BlockPos vpos = v.pos();
				BlockState vstate = MC.world.getBlockState(vpos);
				int vcolor = vaultBoxColor.getColorI();
				boolean ominous = vstate.contains(VaultBlock.OMINOUS)
					&& vstate.get(VaultBlock.OMINOUS);
				if(ominous)
					vcolor = ominousVaultBoxColor.getColorI();
				Box vbox = new Box(vpos);
				outlineBoxes.add(new ColoredBox(vbox, vcolor));
				if(filledBoxes != null)
					filledBoxes
						.add(new ColoredBox(vbox, withAlpha(vcolor, 0.18F)));
				
				// show simple status label above the vault
				String status = describeVaultState(vstate);
				List<OverlayLine> lines =
					List.of(new OverlayLine("Vault", vcolor),
						new OverlayLine(status, 0xFFFFFFFF));
				Vec3d labelPos = Vec3d.ofCenter(vpos).add(0, 1.0, 0);
				labelPos = resolveLabelPosition(labelPos);
				drawLabel(matrices, labelPos, lines, overlayScale.getValueF());
			}
		}
		
		for(TrialSpawnerInfo info : spawners)
		{
			TrialSpawnerBlockEntity be = info.blockEntity();
			if(be == null || be.isRemoved() || be.getWorld() != MC.world)
				continue;
			
			TrialSpawnerLogic logic = be.getSpawner();
			if(logic == null)
				continue;
			
			TrialSpawnerState state = logic.getSpawnerState() == null
				? TrialSpawnerState.INACTIVE : logic.getSpawnerState();
			TrialStatus status = TrialStatus.fromState(state);
			int color = getColorForStatus(status);
			Box box = new Box(info.pos());
			outlineBoxes.add(new ColoredBox(box, color));
			if(filledBoxes != null)
				filledBoxes.add(new ColoredBox(box, withAlpha(color, 0.18F)));
			if(tracerTargets != null)
				tracerTargets
					.add(new ColoredPoint(Vec3d.ofCenter(info.pos()), color));
			
			if(showActivationRadius.isChecked())
				drawActivationRadius(matrices, info, logic, color);
			
			if(showVaultLink.isChecked() && info.vault() != null)
				drawVaultLink(matrices, info, color);
			
			if(showOverlay.isChecked())
				drawOverlay(matrices, info, logic, state, color);
		}
		
		if(filledBoxes != null && !filledBoxes.isEmpty())
			RenderUtils.drawSolidBoxes(matrices, filledBoxes, false);
		if(!outlineBoxes.isEmpty())
			RenderUtils.drawOutlinedBoxes(matrices, outlineBoxes, false);
		if(tracerTargets != null && !tracerTargets.isEmpty())
			RenderUtils.drawTracers(matrices, partialTicks, tracerTargets,
				false);
	}
	
	private void drawActivationRadius(MatrixStack matrices,
		TrialSpawnerInfo info, TrialSpawnerLogic logic, int stateColor)
	{
		int radius = logic.getDetectionRadius();
		if(radius <= 0)
			return;
		
		Vec3d center = Vec3d.ofCenter(info.pos()).add(0, 0.05, 0);
		double radiusSq = radius * radius;
		Vec3d playerPos = MC.player == null ? null
			: new Vec3d(MC.player.getX(), MC.player.getY(), MC.player.getZ());
		boolean inside = playerPos != null
			&& playerPos.squaredDistanceTo(center) <= radiusSq;
		int color = withAlpha(
			inside ? mixWithWhite(stateColor, 0.35F) : radiusColor.getColorI(),
			inside ? 0.65F : 0.35F);
		int segments = Math.max(32, radius * 12);
		double step = (Math.PI * 2) / segments;
		
		Vec3d prev = center.add(radius, 0, 0);
		for(int i = 1; i <= segments; i++)
		{
			double angle = i * step;
			Vec3d next = center.add(radius * Math.cos(angle), 0,
				radius * Math.sin(angle));
			RenderUtils.drawLine(matrices, prev, next, color, false);
			prev = next;
		}
	}
	
	private void drawVaultLink(MatrixStack matrices, TrialSpawnerInfo info,
		int stateColor)
	{
		if(MC.world == null || info.vault() == null)
			return;
		
		BlockPos vaultPos = info.vault().pos();
		Vec3d start = Vec3d.ofCenter(info.pos());
		Vec3d end = Vec3d.ofCenter(vaultPos).add(0, 0.25, 0);
		int color = vaultLinkColor.getColorI();
		RenderUtils.drawLine(matrices, start, end, color, false);
		
		BlockState state = MC.world.getBlockState(vaultPos);
		if(!state.isOf(Blocks.VAULT))
			return;
		
		String status = describeVaultState(state);
		List<OverlayLine> lines = List.of(new OverlayLine("Vault", stateColor),
			new OverlayLine(status, color));
		Vec3d labelPos = end.add(0, 0.6, 0);
		drawLabel(matrices, labelPos, lines, overlayScale.getValueF());
	}
	
	private void drawOverlay(MatrixStack matrices, TrialSpawnerInfo info,
		TrialSpawnerLogic logic, TrialSpawnerState state, int headerColor)
	{
		if(MC.world == null)
			return;
		
		TrialSpawnerData data = logic.getData();
		if(data == null)
			return;
		
		TrialSpawnerDataAccessor accessor = (TrialSpawnerDataAccessor)data;
		long worldTime = MC.world.getTime();
		long cooldownTicks = accessor.getCooldownEnd() - worldTime;
		double cooldownSeconds = Math.max(0, cooldownTicks / 20.0);
		long nextSpawnTicks = accessor.getNextMobSpawnsAt() - worldTime;
		double nextSpawnSeconds = Math.max(0, nextSpawnTicks / 20.0);
		
		int additionalPlayers = data.getAdditionalPlayers(info.pos());
		TrialSpawnerConfig config = logic.getConfig();
		int totalMobs = Math.max(1, config.getTotalMobs(additionalPlayers));
		int simultaneous =
			Math.max(1, config.getSimultaneousMobs(additionalPlayers));
		int trackedSpawned =
			MathHelper.clamp(accessor.getTotalSpawnedMobs(), 0, totalMobs);
		Set<UUID> aliveSet = accessor.getSpawnedMobsAlive();
		int aliveFromData =
			aliveSet == null ? 0 : Math.min(aliveSet.size(), totalMobs);
		int totalWaves =
			Math.max(1, (int)Math.ceil(totalMobs / (double)simultaneous));
		String decorMob = info.decorMob() == null ? "" : info.decorMob();
		String spawnId = readMobId(data.getSpawnDataNbt(state));
		String resolvedMob = resolveMobName(spawnId);
		String mobName = !decorMob.isEmpty() ? decorMob
			: (resolvedMob == null ? "" : resolvedMob);
		if(mobName.isEmpty())
			mobName = "Unknown";
		EntityType<?> mobType = resolveEntityType(spawnId, decorMob);
		int aliveFromWorld =
			countWorldMobs(info.pos(), logic, mobType, mobName);
		int alive = Math.max(aliveFromWorld, aliveFromData);
		if(trackedSpawned <= 0 && alive > 0)
			trackedSpawned = alive;
		int mobsProgress =
			MathHelper.clamp(Math.max(trackedSpawned, alive), 0, totalMobs);
		int currentWave = MathHelper.clamp(
			(int)Math.ceil(Math.max(1, mobsProgress) / (double)simultaneous),
			mobsProgress > 0 ? 1 : 0, totalWaves);
		
		String trialType = describeTrialType(mobName, spawnId);
		TrialStatus status = TrialStatus.fromState(state);
		String statusLine = describeStatus(status);
		
		ArrayList<OverlayLine> lines = new ArrayList<>();
		String title =
			logic.isOminous() ? "Ominous Trial Spawner" : "Trial Spawner";
		lines.add(new OverlayLine(title, headerColor));
		
		if(showMobType.isChecked())
			lines.add(new OverlayLine("Mob: " + mobName, 0xFFFFFFFF));
		
		if(showStatus.isChecked())
			lines.add(new OverlayLine("Status: " + statusLine, 0xFFFFFFFF));
		
		if(alive > 0)
			lines.add(new OverlayLine("Active mobs: " + alive, 0xFFFFFFFF));
		
		// removed wave info display (keeps overlay simple)
		
		if(showNextSpawn.isChecked() && state == TrialSpawnerState.ACTIVE)
		{
			String next = "Next: " + simultaneous + "x " + mobName + " in "
				+ formatSeconds(nextSpawnSeconds);
			lines.add(new OverlayLine(next, 0xFFFFFFFF));
		}
		
		// removed cooldown display
		
		if(showTrialType.isChecked())
			lines.add(new OverlayLine("Trial: " + trialType, 0xFFFFFFFF));
		
		if(showDistance.isChecked())
		{
			double meters = Math.sqrt(info.distanceSq());
			lines.add(new OverlayLine("Distance: " + Math.round(meters) + "m",
				0xFFFFFFFF));
		}
		
		if(showVaultLink.isChecked() && info.vault() != null)
		{
			String vaultInfo =
				describeVaultState(MC.world.getBlockState(info.vault().pos()));
			lines.add(new OverlayLine("Vault: " + vaultInfo, 0xFFFFFFFF));
		}
		
		Vec3d labelPos = Vec3d.ofCenter(info.pos()).add(0, 1.6, 0);
		labelPos = resolveLabelPosition(labelPos);
		drawLabel(matrices, labelPos, lines, overlayScale.getValueF());
	}
	
	private void drawLabel(MatrixStack matrices, Vec3d position,
		List<OverlayLine> lines, float scale)
	{
		if(lines.isEmpty() || MC.textRenderer == null)
			return;
		
		matrices.push();
		Vec3d cam = RenderUtils.getCameraPos();
		matrices.translate(position.x - cam.x, position.y - cam.y,
			position.z - cam.z);
		var camEntity = MC.getCameraEntity();
		if(camEntity != null)
		{
			matrices.multiply(
				RotationAxis.POSITIVE_Y.rotationDegrees(-camEntity.getYaw()));
			matrices.multiply(
				RotationAxis.POSITIVE_X.rotationDegrees(camEntity.getPitch()));
		}
		matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180));
		float s = 0.025F * scale;
		matrices.scale(s, -s, s);
		
		TextRenderer tr = MC.textRenderer;
		int bg = (int)(MC.options.getTextBackgroundOpacity(0.25F) * 255) << 24;
		int lineHeight = tr.fontHeight + 2;
		int maxWidth = lines.stream().mapToInt(line -> tr.getWidth(line.text()))
			.max().orElse(0);
		int x = -maxWidth / 2;
		
		Immediate vcp = RenderUtils.getVCP();
		for(int i = 0; i < lines.size(); i++)
		{
			OverlayLine line = lines.get(i);
			int y = i * lineHeight;
			TextLayerType layerType = TextLayerType.SEE_THROUGH;
			tr.draw(line.text(), x, y, line.color(), false,
				matrices.peek().getPositionMatrix(), vcp, layerType, bg,
				0xF000F0);
		}
		vcp.draw();
		matrices.pop();
	}
	
	private Vec3d resolveLabelPosition(Vec3d target)
	{
		Vec3d cam = RenderUtils.getCameraPos();
		double distance = cam.distanceTo(target);
		double anchor = 12;
		if(distance <= anchor)
			return target;
		
		Vec3d dir = target.subtract(cam);
		double len = dir.length();
		if(len < 1e-4)
			return target;
		
		return cam.add(dir.multiply(anchor / len));
	}
	
	private VaultInfo findLinkedVault(BlockPos spawnerPos)
	{
		if(vaults.isEmpty())
			return null;
		
		double maxRangeSq =
			vaultLinkRange.getValue() * vaultLinkRange.getValue();
		VaultInfo closest = null;
		double best = maxRangeSq;
		for(VaultInfo info : vaults)
		{
			double distSq = info.pos().getSquaredDistance(spawnerPos);
			if(distSq <= best)
			{
				closest = info;
				best = distSq;
			}
		}
		return closest;
	}
	
	private String detectMobFromDecor(BlockPos spawnerPos)
	{
		if(MC.world == null || spawnerPos == null)
			return "";
		
		BlockPos base = spawnerPos.down();
		boolean hasCobble = false;
		boolean hasMossyCobble = false;
		boolean hasStone = false;
		boolean hasCobweb = false;
		boolean hasPodzol = false;
		boolean hasMushroom = false;
		boolean hasChiseledSandstone = false;
		boolean hasChiseledTuff = false;
		boolean hasPackedIce = false;
		boolean hasStoneBricks = false;
		boolean hasMossBlock = false;
		boolean hasBoneBlock = false;
		
		for(int dx = -1; dx <= 1; dx++)
		{
			for(int dz = -1; dz <= 1; dz++)
			{
				if(dx == 0 && dz == 0)
					continue;
				
				BlockPos check = base.add(dx, 0, dz);
				BlockState state = MC.world.getBlockState(check);
				BlockState above = MC.world.getBlockState(check.up());
				
				if(state.isOf(Blocks.COBBLESTONE))
					hasCobble = true;
				if(state.isOf(Blocks.MOSSY_COBBLESTONE))
					hasMossyCobble = true;
				if(state.isOf(Blocks.STONE))
					hasStone = true;
				if(state.isOf(Blocks.COBWEB) || above.isOf(Blocks.COBWEB))
					hasCobweb = true;
				if(state.isOf(Blocks.PODZOL))
					hasPodzol = true;
				if(isMushroom(state) || isMushroom(above))
					hasMushroom = true;
				if(state.isOf(Blocks.CHISELED_SANDSTONE))
					hasChiseledSandstone = true;
				if(state.isOf(Blocks.CHISELED_TUFF))
					hasChiseledTuff = true;
				if(state.isOf(Blocks.PACKED_ICE))
					hasPackedIce = true;
				if(state.isOf(Blocks.STONE_BRICKS))
					hasStoneBricks = true;
				if(state.isOf(Blocks.MOSS_BLOCK))
					hasMossBlock = true;
				if(state.isOf(Blocks.BONE_BLOCK))
					hasBoneBlock = true;
			}
		}
		
		boolean hasPodzolMushroom = hasPodzol && hasMushroom;
		if(hasBoneBlock && hasPodzolMushroom)
			return "Bogged";
		if(hasCobble && hasMossyCobble)
			return "Baby Zombie";
		if(hasStone && hasCobweb && hasPodzolMushroom)
			return "Cave Spider";
		if(hasStone && hasCobweb)
			return "Spider";
		if(hasChiseledTuff)
			return "Breeze";
		if(hasChiseledSandstone)
			return "Husk";
		if(hasPackedIce)
			return "Stray";
		if(hasMossBlock)
			return "Slime";
		if(hasBoneBlock)
			return "Skeleton";
		if(hasStoneBricks)
			return "Silverfish";
		if(hasMossyCobble)
			return "Zombie";
		return "";
	}
	
	private boolean isMushroom(BlockState state)
	{
		return state.isOf(Blocks.BROWN_MUSHROOM)
			|| state.isOf(Blocks.RED_MUSHROOM);
	}
	
	private int getColorForStatus(TrialStatus status)
	{
		return switch(status)
		{
			case ACTIVE -> colorActive.getColorI();
			case CHARGING -> colorCharging.getColorI();
			case COMPLETED -> colorCompleted.getColorI();
			case IDLE -> colorIdle.getColorI();
		};
	}
	
	private String readMobId(NbtCompound spawnData)
	{
		if(spawnData == null)
			return "";
		Optional<NbtCompound> entity = spawnData.getCompound("entity");
		if(entity.isPresent())
		{
			Optional<String> id = entity.get().getString("id");
			if(id.isPresent())
				return id.get();
		}
		return spawnData.getString("id").orElse("");
	}
	
	private String resolveMobName(String mobId)
	{
		if(mobId == null || mobId.isEmpty())
			return "Unknown";
		
		Identifier id = Identifier.tryParse(mobId);
		if(id != null)
		{
			EntityType<?> entity = Registries.ENTITY_TYPE.get(id);
			if(entity != null)
				return entity.getName().getString();
		}
		
		return prettifyId(mobId);
	}
	
	private String prettifyId(String raw)
	{
		int colon = raw.indexOf(':');
		String clean = colon >= 0 ? raw.substring(colon + 1) : raw;
		String[] parts = clean.split("_");
		StringBuilder sb = new StringBuilder();
		for(String part : parts)
		{
			if(part.isEmpty())
				continue;
			if(sb.length() > 0)
				sb.append(' ');
			sb.append(Character.toUpperCase(part.charAt(0)));
			if(part.length() > 1)
				sb.append(part.substring(1));
		}
		return sb.length() == 0 ? clean : sb.toString();
	}
	
	private String describeTrialType(String resolvedName, String mobId)
	{
		String trial = describeTrialTypeFromValue(resolvedName);
		if(!"Unknown".equals(trial))
			return trial;
		return describeTrialTypeFromValue(mobId);
	}
	
	private String describeTrialTypeFromValue(String value)
	{
		if(value == null || value.isEmpty())
			return "Unknown";
		
		String lower = value.toLowerCase(Locale.ROOT);
		if(lower.contains("breeze"))
			return "Breeze / Parkour";
		if(lower.contains("bogged") || lower.contains("drifter")
			|| lower.contains("husk") || lower.contains("skeleton")
			|| lower.contains("stray") || lower.contains("zombie")
			|| lower.contains("spider") || lower.contains("slime")
			|| lower.contains("silverfish") || lower.contains("baby"))
			return "Combat";
		return "Unknown";
	}
	
	private EntityType<?> resolveEntityType(String mobId, String decorMob)
	{
		EntityType<?> type = parseMobType(mobId);
		if(type != null)
			return type;
		return mapDecorMobToType(decorMob);
	}
	
	private EntityType<?> parseMobType(String mobId)
	{
		if(mobId == null || mobId.isEmpty())
			return null;
		Identifier id = Identifier.tryParse(mobId);
		if(id == null)
			return null;
		RegistryKey<EntityType<?>> key =
			RegistryKey.of(RegistryKeys.ENTITY_TYPE, id);
		return Registries.ENTITY_TYPE.get(key);
	}
	
	private EntityType<?> mapDecorMobToType(String decorMob)
	{
		if(decorMob == null || decorMob.isEmpty())
			return null;
		
		return switch(decorMob.toLowerCase(Locale.ROOT))
		{
			case "baby zombie", "zombie" -> EntityType.ZOMBIE;
			case "husk" -> EntityType.HUSK;
			case "spider" -> EntityType.SPIDER;
			case "cave spider" -> EntityType.CAVE_SPIDER;
			case "silverfish" -> EntityType.SILVERFISH;
			case "slime" -> EntityType.SLIME;
			case "skeleton" -> EntityType.SKELETON;
			case "bogged" -> EntityType.BOGGED;
			case "stray" -> EntityType.STRAY;
			case "breeze" -> EntityType.BREEZE;
			default -> null;
		};
	}
	
	private int countWorldMobs(BlockPos pos, TrialSpawnerLogic logic,
		EntityType<?> mobType, String mobName)
	{
		if(MC.world == null || pos == null)
			return 0;
		
		double radius =
			logic == null ? 8 : Math.max(6, logic.getDetectionRadius() + 4);
		Box box =
			Box.of(Vec3d.ofCenter(pos), radius * 2, radius * 2, radius * 2);
		return MC.world.getEntitiesByClass(LivingEntity.class, box,
			entity -> matchesMob(entity, mobType, mobName)).size();
	}
	
	private boolean matchesMob(LivingEntity entity, EntityType<?> mobType,
		String mobName)
	{
		if(entity == null)
			return false;
		if(mobType != null && entity.getType() == mobType)
			return true;
		if(mobName == null || mobName.isEmpty())
			return false;
		
		String typeName = entity.getType().getName().getString();
		return typeName.equalsIgnoreCase(mobName);
	}
	
	private String describeVaultState(BlockState state)
	{
		if(!state.isOf(Blocks.VAULT) || !state.contains(VaultBlock.VAULT_STATE))
			return "Missing";
		
		VaultState vaultState = state.get(VaultBlock.VAULT_STATE);
		boolean ominous =
			state.contains(VaultBlock.OMINOUS) && state.get(VaultBlock.OMINOUS);
		String prefix = ominous ? "Ominous " : "";
		return switch(vaultState)
		{
			case ACTIVE -> prefix + "Active";
			case UNLOCKING -> prefix + "Unlocking";
			case EJECTING -> prefix + "Ejecting loot";
			case INACTIVE -> prefix + "Locked";
		};
	}
	
	private String describeStatus(TrialStatus status)
	{
		return switch(status)
		{
			case ACTIVE -> "Spawning";
			case CHARGING -> "Priming";
			case COMPLETED -> "Completed";
			case IDLE -> "Idle";
		};
	}
	
	private String formatSeconds(double seconds)
	{
		if(seconds >= 10)
			return (int)Math.round(seconds) + "s";
		return String.format(Locale.ROOT, "%.1fs", seconds);
	}
	
	private String progressBar(double progress)
	{
		final int segments = 10;
		int filled =
			MathHelper.clamp((int)Math.round(progress * segments), 0, segments);
		StringBuilder sb = new StringBuilder("[");
		for(int i = 0; i < segments; i++)
			sb.append(i < filled ? "#" : "-");
		sb.append("]");
		return sb.toString();
	}
	
	private int withAlpha(int color, float alpha)
	{
		int a = MathHelper.clamp((int)(alpha * 255), 0, 255);
		return (color & 0x00FFFFFF) | (a << 24);
	}
	
	private int mixWithWhite(int color, float factor)
	{
		int a = color >>> 24;
		int r = (color >> 16) & 0xFF;
		int g = (color >> 8) & 0xFF;
		int b = color & 0xFF;
		r = MathHelper.clamp((int)MathHelper.lerp(factor, r, 255), 0, 255);
		g = MathHelper.clamp((int)MathHelper.lerp(factor, g, 255), 0, 255);
		b = MathHelper.clamp((int)MathHelper.lerp(factor, b, 255), 0, 255);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
	
	@Override
	public String getRenderName()
	{
		if(showCountInHackList.isChecked() && foundCount > 0)
			return getName() + " [" + foundCount + "]";
		return super.getRenderName();
	}
	
	private record TrialSpawnerInfo(TrialSpawnerBlockEntity blockEntity,
		BlockPos pos, double distanceSq, VaultInfo vault, String decorMob)
	{}
	
	private record VaultInfo(BlockPos pos)
	{}
	
	private record OverlayLine(String text, int color)
	{}
	
	private enum TrialStatus
	{
		IDLE,
		CHARGING,
		ACTIVE,
		COMPLETED;
		
		public static TrialStatus fromState(TrialSpawnerState state)
		{
			return switch(state)
			{
				case ACTIVE -> ACTIVE;
				case WAITING_FOR_PLAYERS -> CHARGING;
				case WAITING_FOR_REWARD_EJECTION, EJECTING_REWARD -> COMPLETED;
				case COOLDOWN, INACTIVE -> IDLE;
			};
		}
	}
}
