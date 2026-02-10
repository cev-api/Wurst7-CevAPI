/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import com.mojang.blaze3d.vertex.PoseStack;
import java.awt.Color;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.CameraTransformViewBobbingListener;
import net.wurstclient.events.IsNormalCubeListener;
import net.wurstclient.events.IsNormalCubeListener.IsNormalCubeEvent;
import net.wurstclient.events.MouseScrollListener;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.RenderListener;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.events.VisGraphListener;
import net.wurstclient.hack.DontSaveState;
import net.wurstclient.hack.Hack;
import net.wurstclient.hacks.freecam.FreecamInitialPosSetting;
import net.wurstclient.hacks.freecam.FreecamInputSetting;
import net.wurstclient.hacks.freecam.FreecamInputSetting.ApplyInputTo;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.FakePlayerEntity;
import net.wurstclient.util.RenderUtils;
import net.wurstclient.util.RotationUtils;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

@DontSaveState
@SearchTags({"free camera", "spectator"})
public final class FreecamHack extends Hack implements UpdateListener,
	VisGraphListener, CameraTransformViewBobbingListener, RenderListener,
	MouseScrollListener, PacketOutputListener, IsNormalCubeListener
{
	private static final double DEFAULT_SPEED_STEP = 0.5;
	
	private final FreecamInputSetting applyInputTo = new FreecamInputSetting();
	
	private final SliderSetting horizontalSpeed =
		new SliderSetting("Horizontal speed",
			"description.wurst.setting.freecam.horizontal_speed", 1, 0.05, 10,
			0.05, ValueDisplay.DECIMAL);
	
	private final SliderSetting verticalSpeed = new SliderSetting(
		"Vertical speed", "description.wurst.setting.freecam.vertical_speed", 1,
		0.05, 10, 0.05,
		v -> ValueDisplay.DECIMAL.getValueString(getActualVerticalSpeed()));
	
	private final CheckboxSetting tieVerticalToHorizontal = new CheckboxSetting(
		"Tie vertical to horizontal",
		"description.wurst.setting.freecam.tie_vertical_to_horizontal", false);
	
	private final SliderSetting speedStep = new SliderSetting("Speed step",
		"description.wurst.setting.freecam.speed_step", DEFAULT_SPEED_STEP,
		0.05, 5.0, 0.05, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting legacyMode = new CheckboxSetting(
		"Legacy mode", "description.wurst.setting.freecam.legacy_mode", true);
	
	private final CheckboxSetting scrollToChangeSpeed =
		new CheckboxSetting("Scroll to change speed",
			"description.wurst.setting.freecam.scroll_to_change_speed", false);
	
	private final CheckboxSetting renderSpeed =
		new CheckboxSetting("Show speed in HackList",
			"description.wurst.setting.freecam.show_speed_in_hacklist", true);
	
	private final FreecamInitialPosSetting initialPos =
		new FreecamInitialPosSetting();
	
	private final CheckboxSetting tracer = new CheckboxSetting("Tracer",
		"description.wurst.setting.freecam.tracer", false);
	
	private final ColorSetting color =
		new ColorSetting("Tracer color", Color.WHITE);
	
	private final CheckboxSetting hideHand = new CheckboxSetting("Hide hand",
		"description.wurst.setting.freecam.hide_hand", true);
	
	private final CheckboxSetting disableOnDamage =
		new CheckboxSetting("Disable on damage",
			"description.wurst.setting.freecam.disable_on_damage", true);
	
	private Vec3 camPos;
	private Vec3 prevCamPos;
	private float camYaw;
	private float camPitch;
	private float lastHealth;
	
	private FakePlayerEntity fakePlayer;
	
	public FreecamHack()
	{
		super("Freecam");
		setCategory(Category.RENDER);
		addSetting(applyInputTo);
		addSetting(horizontalSpeed);
		addSetting(verticalSpeed);
		addSetting(tieVerticalToHorizontal);
		addSetting(speedStep);
		addSetting(legacyMode);
		addSetting(scrollToChangeSpeed);
		addSetting(renderSpeed);
		addSetting(initialPos);
		addSetting(tracer);
		addSetting(color);
		addSetting(hideHand);
		addSetting(disableOnDamage);
	}
	
	@Override
	public String getRenderName()
	{
		if(!renderSpeed.isChecked())
			return getName();
		
		return getName() + " [" + horizontalSpeed.getValueString() + ", "
			+ verticalSpeed.getValueString() + "]";
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(VisGraphListener.class, this);
		EVENTS.add(CameraTransformViewBobbingListener.class, this);
		EVENTS.add(RenderListener.class, this);
		EVENTS.add(MouseScrollListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(IsNormalCubeListener.class, this);
		
		lastHealth = Float.MIN_VALUE;
		camPos = RotationUtils.getEyesPos()
			.add(initialPos.getSelected().getOffset());
		prevCamPos = camPos;
		camYaw = MC.player.getYRot();
		camPitch = MC.player.getXRot();
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(VisGraphListener.class, this);
		EVENTS.remove(CameraTransformViewBobbingListener.class, this);
		EVENTS.remove(RenderListener.class, this);
		EVENTS.remove(MouseScrollListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(IsNormalCubeListener.class, this);
		
		deactivateLegacyMode();
		
		MC.levelRenderer.allChanged();
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer player = MC.player;
		
		// Check for damage
		float currentHealth = player.getHealth();
		if(disableOnDamage.isChecked() && currentHealth < lastHealth)
		{
			setEnabled(false);
			return;
		}
		lastHealth = currentHealth;
		
		boolean cameraMode = isMovingCamera();
		if(!cameraMode)
		{
			ensureLegacyModeState();
			handlePlayerMode(player);
			prevCamPos = camPos;
			return;
		}
		
		if(MC.screen != null)
		{
			ensureLegacyModeState();
			if(isLegacyModeActive())
			{
				player.snapTo(camPos.x, camPos.y, camPos.z, camYaw, camPitch);
				player.setDeltaMovement(Vec3.ZERO);
				player.getAbilities().flying = false;
				player.setOnGround(false);
			}
			
			prevCamPos = camPos;
			return;
		}
		
		ensureLegacyModeState();
		
		// Get movement vector (x=left, y=forward)
		Vec2 moveVector = player.input.getMoveVector();
		
		// Convert to world coordinates
		double yawRad = MC.gameRenderer.getMainCamera().yRot() * Mth.DEG_TO_RAD;
		double sinYaw = Mth.sin(yawRad);
		double cosYaw = Mth.cos(yawRad);
		double offsetX = moveVector.x * cosYaw - moveVector.y * sinYaw;
		double offsetZ = moveVector.x * sinYaw + moveVector.y * cosYaw;
		
		// Calculate vertical offset
		double offsetY = 0;
		double vSpeed = getActualVerticalSpeed();
		if(IKeyMapping.get(MC.options.keyJump).isActuallyDown())
			offsetY += vSpeed;
		if(IKeyMapping.get(MC.options.keyShift).isActuallyDown())
			offsetY -= vSpeed;
		
		// Apply to camera
		Vec3 offsetVec = new Vec3(offsetX, 0, offsetZ)
			.scale(horizontalSpeed.getValueF()).add(0, offsetY, 0);
		prevCamPos = camPos;
		camPos = camPos.add(offsetVec);
		
		if(isLegacyModeActive())
		{
			// Move the player client-side so reach/raycast interactions use the
			// camera position, but cancel movement packets so the server
			// doesn't
			// see any movement.
			player.snapTo(camPos.x, camPos.y, camPos.z, camYaw, camPitch);
			player.setDeltaMovement(Vec3.ZERO);
			player.getAbilities().flying = false;
			player.setOnGround(false);
		}
	}
	
	private double getActualVerticalSpeed()
	{
		if(tieVerticalToHorizontal.isChecked())
			return Mth.clamp(
				horizontalSpeed.getValue() * verticalSpeed.getValue(), 0.05,
				10);
		
		return Mth.clamp(verticalSpeed.getValue(), 0.05, 10);
	}
	
	private void handlePlayerMode(LocalPlayer player)
	{
		if(player == null)
			return;
		
		Vec2 moveVector = player.input.getMoveVector();
		double yawRad = MC.gameRenderer.getMainCamera().yRot() * Mth.DEG_TO_RAD;
		double sinYaw = Mth.sin(yawRad);
		double cosYaw = Mth.cos(yawRad);
		double speed = horizontalSpeed.getValue();
		double offsetX =
			(moveVector.x * cosYaw - moveVector.y * sinYaw) * speed;
		double offsetZ =
			(moveVector.x * sinYaw + moveVector.y * cosYaw) * speed;
		
		double offsetY = 0;
		double vSpeed = getActualVerticalSpeed();
		if(IKeyMapping.get(MC.options.keyJump).isActuallyDown())
			offsetY += vSpeed;
		if(IKeyMapping.get(MC.options.keyShift).isActuallyDown())
			offsetY -= vSpeed;
		
		Vec3 offset = new Vec3(offsetX, offsetY, offsetZ);
		player.setDeltaMovement(offset);
		player.move(MoverType.SELF, offset);
		player.getAbilities().flying = false;
		player.setOnGround(false);
		
		camPos = player.position();
		prevCamPos = camPos;
	}
	
	@Override
	public void onMouseScroll(double amount)
	{
		if(!isControllingScrollEvents())
			return;
		
		double step = speedStep.getValue();
		if(amount > 0)
			horizontalSpeed.setValue(horizontalSpeed.getValue() + step);
		else if(amount < 0)
			horizontalSpeed.setValue(horizontalSpeed.getValue() - step);
	}
	
	public double getSpeedStep()
	{
		return speedStep.getValue();
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent event)
	{
		if(!isLegacyModeActive())
			return;
		
		if(event.getPacket() instanceof ServerboundMovePlayerPacket)
			event.cancel();
	}
	
	public boolean isLegacyModeActive()
	{
		return legacyMode.isChecked() && isMovingCamera() && fakePlayer != null;
	}
	
	private void ensureLegacyModeState()
	{
		boolean wantLegacy = legacyMode.isChecked() && isMovingCamera();
		if(wantLegacy && fakePlayer == null)
		{
			fakePlayer = new FakePlayerEntity();
			// Keep the decoy purely functional without rendering it.
			fakePlayer.setInvisible(true);
			fakePlayer.setCustomNameVisible(false);
		}else if(!wantLegacy && fakePlayer != null)
			deactivateLegacyMode();
		if(MC.player != null)
			MC.player.noPhysics = wantLegacy;
	}
	
	private void deactivateLegacyMode()
	{
		if(fakePlayer == null)
			return;
		
		fakePlayer.resetPlayerPosition();
		fakePlayer.despawn();
		fakePlayer = null;
		if(MC.player != null)
		{
			if(MC.player != null)
				MC.player.noPhysics = false;
		}
	}
	
	public boolean isControllingScrollEvents()
	{
		return isMovingCamera() && scrollToChangeSpeed.isChecked()
			&& MC.screen == null
			&& !WURST.getOtfs().zoomOtf.isControllingScrollEvents();
	}
	
	public boolean isMovingCamera()
	{
		return isEnabled() && applyInputTo.getSelected() == ApplyInputTo.CAMERA;
	}
	
	@Override
	public void onVisGraph(VisGraphEvent event)
	{
		event.cancel();
	}
	
	@Override
	public void onIsNormalCube(IsNormalCubeEvent event)
	{
		// Legacy mode moves the player client-side to the camera position for
		// reach/raycasting. If the camera is inside blocks while noclipping,
		// Minecraft may treat the view as "inside a full block" and obstruct
		// it.
		// Cancelling this makes blocks stop counting as full cubes while legacy
		// Freecam is active, matching the old Freecam/NoClip behavior.
		if(legacyMode.isChecked() && isMovingCamera())
			event.cancel();
	}
	
	@Override
	public void onCameraTransformViewBobbing(
		CameraTransformViewBobbingEvent event)
	{
		event.cancel();
	}
	
	@Override
	public void onRender(PoseStack matrixStack, float partialTicks)
	{
		if(!tracer.isChecked())
			return;
		
		int colorI = color.getColorI(0x80);
		
		// Box
		double extraSize = 0.05;
		AABB rawBox = isLegacyModeActive()
			? EntityUtils.getLerpedBox(fakePlayer, partialTicks)
			: EntityUtils.getLerpedBox(MC.player, partialTicks);
		AABB box = rawBox.move(0, extraSize, 0).inflate(extraSize);
		RenderUtils.drawOutlinedBox(matrixStack, box, colorI, false);
		
		// Line
		RenderUtils.drawTracer(matrixStack, partialTicks, rawBox.getCenter(),
			colorI, false);
	}
	
	public boolean shouldHideHand()
	{
		return isEnabled() && hideHand.isChecked();
	}
	
	public Vec3 getCamPos(float partialTicks)
	{
		return Mth.lerp(partialTicks, prevCamPos, camPos);
	}
	
	public void turn(double deltaYaw, double deltaPitch)
	{
		// This needs to be consistent with Entity.turn()
		camYaw += (float)(deltaYaw * 0.15);
		camPitch += (float)(deltaPitch * 0.15);
		camPitch = Mth.clamp(camPitch, -90, 90);
	}
	
	public float getCamYaw()
	{
		return camYaw;
	}
	
	public float getCamPitch()
	{
		return camPitch;
	}
}
