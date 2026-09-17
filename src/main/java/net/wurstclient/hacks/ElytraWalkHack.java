/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PacketOutputListener;
import net.wurstclient.events.PacketOutputListener.PacketOutputEvent;
import net.wurstclient.events.PacketInputListener;
import net.wurstclient.events.PacketInputListener.PacketInputEvent;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.PacketUtils;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.mixinterface.ILocalPlayer;

/**
 * Keeps the elytra pose lit while walking, using the 26.2 player command flow.
 */
@SearchTags({"elytra walk", "ground skating"})
public final class ElytraWalkHack extends Hack
	implements UpdateListener, PacketOutputListener, PacketInputListener
{
	private final SliderSetting flySpeed = new SliderSetting("Fly speed",
		"description.wurst.setting.elytrawalk.fly_speed", 1.5, 0, 3, .1,
		ValueDisplay.DECIMAL);
	private final CheckboxSetting autoElytra =
		new CheckboxSetting("Auto elytra",
			"description.wurst.setting.elytrawalk.auto_elytra", true);
	private final CheckboxSetting notify = new CheckboxSetting("Notify",
		"description.wurst.setting.elytrawalk.notify", true);
	private final CheckboxSetting showSelf = new CheckboxSetting("Show self",
		"description.wurst.setting.elytrawalk.show_self", false);
	private final CheckboxSetting hideNametag =
		new CheckboxSetting("Hide nametag",
			"description.wurst.setting.elytrawalk.hide_nametag", false);
	private final CheckboxSetting spin = new CheckboxSetting("Spin",
		"description.wurst.setting.elytrawalk.spin", false);
	private final SliderSetting spinSpeed = new SliderSetting("Spin speed",
		"description.wurst.setting.elytrawalk.spin_speed", 18, -90, 90, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting pitchLock = new SliderSetting("Pitch lock",
		"description.wurst.setting.elytrawalk.pitch_lock", -181, -181, 90, 1,
		ValueDisplay.INTEGER);
	private final CheckboxSetting bob = new CheckboxSetting("Bob",
		"description.wurst.setting.elytrawalk.bob", false);
	private final SliderSetting bobRange = new SliderSetting("Bob range",
		"description.wurst.setting.elytrawalk.bob_range", 35, 0, 90, 1,
		ValueDisplay.INTEGER);
	private final SliderSetting bobSpeed = new SliderSetting("Bob speed",
		"description.wurst.setting.elytrawalk.bob_speed", .1, .01, .5, .01,
		ValueDisplay.DECIMAL);
	private int retry, showTicks;
	private float spinYaw;
	private boolean serverGliding;
	private int relightAttempts;
	private int pendingAck;
	private boolean announced;
	private ItemStack originalChest;
	private boolean warnedMissingElytra;
	private int equipRetry;
	
	public ElytraWalkHack()
	{
		super("ElytraWalk");
		setCategory(Category.MOVEMENT);
		addSetting(flySpeed);
		addSetting(autoElytra);
		addSetting(notify);
		addSetting(showSelf);
		addSetting(hideNametag);
		addSetting(spin);
		addSetting(spinSpeed);
		addSetting(pitchLock);
		addSetting(bob);
		addSetting(bobRange);
		addSetting(bobSpeed);
	}
	
	@Override
	protected void onEnable()
	{
		retry = showTicks = 0;
		serverGliding = MC.player != null && MC.player.isFallFlying();
		relightAttempts = 0;
		pendingAck = 0;
		announced = false;
		originalChest = null;
		warnedMissingElytra = false;
		equipRetry = 0;
		spinYaw = MC.player == null ? 0 : MC.player.getYRot();
		EVENTS.add(UpdateListener.class, this);
		EVENTS.add(PacketOutputListener.class, this);
		EVENTS.add(PacketInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		EVENTS.remove(PacketOutputListener.class, this);
		EVENTS.remove(PacketInputListener.class, this);
		if(MC.player != null && MC.player.isFallFlying())
			MC.player.stopFallFlying();
		if(autoElytra.isChecked())
			restoreElytra();
	}
	
	@Override
	public void onUpdate()
	{
		LocalPlayer p = MC.player;
		if(p == null)
			return;
		if(pendingAck > 0)
			pendingAck--;
		if(equipRetry > 0)
			equipRetry--;
		if(!hasUsableElytra())
		{
			if(autoElytra.isChecked() && equipRetry == 0
				&& MC.gui.screen() == null)
			{
				equipRetry = 20;
				equipElytra();
			}
			if(!hasUsableElytra())
			{
				if(!warnedMissingElytra)
					ChatUtils
						.warning("ElytraWalk: waiting for a usable elytra. "
							+ "Equip or repair one, or put a spare in your inventory "
							+ "with room to unequip your chest item.");
				warnedMissingElytra = true;
				serverGliding = false;
				pendingAck = retry = 0;
				return;
			}
		}
		warnedMissingElytra = false;
		if(!canClaimPose())
			return;
		((ILocalPlayer)p).elytraWalk$setPositionReminder(20);
		if(!p.onGround())
			return;
		if(!serverGliding && pendingAck <= 0 && retry-- <= 0)
			light(p);
		if(serverGliding || p.isFallFlying())
		{
			showTicks++;
			if(serverGliding && !announced && notify.isChecked())
			{
				announced = true;
				ChatUtils.message("ElytraWalk: elytra pose lit.");
			}
		}
		if(!showSelf.isChecked() && (p.isFallFlying() || serverGliding))
			p.stopFallFlying();
		if(spin.isChecked())
			spinYaw = Mth.wrapDegrees(spinYaw + (float)spinSpeed.getValue());
	}
	
	@Override
	public void onReceivedPacket(PacketInputEvent e)
	{
		if(MC.player == null
			|| !(e.getPacket() instanceof ClientboundSetEntityDataPacket p)
			|| p.id() != MC.player.getId())
			return;
		for(var value : p.packedItems())
		{
			if(value == null || value.id() != 0
				|| !(value.value() instanceof Byte flags))
				continue;
			boolean now = (flags & 0x80) != 0;
			boolean changed = now != serverGliding;
			serverGliding = now;
			if(now || changed)
			{
				pendingAck = 0;
				retry = 0;
			}
			if(now)
				relightAttempts = 0;
			break;
		}
	}
	
	@Override
	public void onSentPacket(PacketOutputEvent e)
	{
		if(e.getPacket() instanceof ServerboundPlayerCommandPacket command
			&& command
				.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING)
		{
			pendingAck = echoWait();
			return;
		}
		
		if(hideNametag.isChecked()
			&& e.getPacket() instanceof ServerboundPlayerInputPacket input
			&& canClaimPose())
		{
			Input i = input.input();
			e.setPacket(new ServerboundPlayerInputPacket(
				new Input(i.forward(), i.backward(), i.left(), i.right(),
					i.jump(), true, i.sprint())));
			return;
		}
		if(!(e.getPacket() instanceof ServerboundMovePlayerPacket p)
			|| !canClaimPose())
			return;
		ServerboundMovePlayerPacket out = PacketUtils.modifyOnGround(p, false);
		if(spin.isChecked() || bob.isChecked() || pitchLock.getValue() > -180.5)
		{
			float yaw =
				spin.isChecked() ? spinYaw : p.getYRot(MC.player.getYRot());
			float pitch = bob.isChecked()
				? (float)(Math.sin(showTicks * bobSpeed.getValue())
					* bobRange.getValue())
				: pitchLock.getValue() > -180.5 ? (float)pitchLock.getValue()
					: p.getXRot(MC.player.getXRot());
			out = PacketUtils.modifyRotation(out, yaw, pitch);
		}
		e.setPacket(out);
	}
	
	/**
	 * Replaces horizontal SELF movement after travel has computed its velocity,
	 * before vanilla collision resolution. Pose acknowledgements never gate
	 * speed.
	 */
	public Vec3 getGroundMovement(MoverType type, Vec3 movement)
	{
		LocalPlayer p = MC.player;
		if(!isEnabled() || !canClaimPose() || type != MoverType.SELF
			|| !p.onGround() || MC.gui.screen() != null)
			return movement;
		double forward = (MC.options.keyUp.isDown() ? 1 : 0)
			- (MC.options.keyDown.isDown() ? 1 : 0);
		double side = (MC.options.keyLeft.isDown() ? 1 : 0)
			- (MC.options.keyRight.isDown() ? 1 : 0);
		double length = Math.hypot(forward, side);
		if(length < 1e-6)
			return movement;
		double yaw = Math.toRadians(p.getYRot());
		double speed = flySpeed.getValue() / length;
		return new Vec3(
			(-Math.sin(yaw) * forward + Math.cos(yaw) * side) * speed,
			movement.y,
			(Math.cos(yaw) * forward + Math.sin(yaw) * side) * speed);
	}
	
	private boolean canClaimPose()
	{
		return hasUsableElytra() && !MC.player.isPassenger()
			&& !MC.player.isInWater() && !MC.player.isInLava()
			&& !MC.player.getAbilities().flying;
	}
	
	/** Allows other movement hacks to yield without changing their toggles. */
	public boolean isGroundSkating()
	{
		return isEnabled() && canClaimPose() && MC.player.onGround();
	}
	
	private static boolean isUsableElytra(ItemStack stack)
	{
		return stack.is(Items.ELYTRA) && (!stack.isDamageableItem()
			|| stack.getMaxDamage() - stack.getDamageValue() > 1);
	}
	
	private boolean hasUsableElytra()
	{
		return MC.player != null
			&& isUsableElytra(MC.player.getItemBySlot(EquipmentSlot.CHEST));
	}
	
	private void light(LocalPlayer p)
	{
		int wait = echoWait();
		retry = wait;
		pendingAck = wait;
		relightAttempts++;
		p.connection.send(new ServerboundMovePlayerPacket.PosRot(p.position(),
			p.getYRot(), p.getXRot(), false, p.horizontalCollision));
		p.connection.send(new ServerboundPlayerCommandPacket(p,
			ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
		if(relightAttempts == 8 && notify.isChecked())
			ChatUtils
				.warning("ElytraWalk: server is refusing the elytra pose.");
	}
	
	private int echoWait()
	{
		int ping = 0;
		if(MC.player != null && MC.getConnection() != null)
		{
			var info = MC.getConnection().getPlayerInfo(MC.player.getUUID());
			if(info != null)
				ping = Math.max(0, info.getLatency());
		}
		int rttTicks = (int)Math.ceil(ping / 50.0) + 3;
		return Math.min(60, Math.max(4, rttTicks));
	}
	
	private boolean hasElytra()
	{
		return MC.player != null
			&& MC.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA);
	}
	
	private void equipElytra()
	{
		LocalPlayer p = MC.player;
		if(p == null)
			return;
		int slot =
			InventoryUtils.indexOf(ElytraWalkHack::isUsableElytra, 36, false);
		if(slot < 0)
			return;
		ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
		if(!chest.isEmpty() && p.getInventory().getFreeSlot() < 0)
			return;
		if(!chest.isEmpty())
		{
			if(originalChest == null && !chest.is(Items.ELYTRA))
				originalChest = chest.copy();
			IMC.getInteractionManager().windowClick_QUICK_MOVE(6);
		}
		slot =
			InventoryUtils.indexOf(ElytraWalkHack::isUsableElytra, 36, false);
		if(slot >= 0)
			IMC.getInteractionManager()
				.windowClick_QUICK_MOVE(InventoryUtils.toNetworkSlot(slot));
	}
	
	private void restoreElytra()
	{
		LocalPlayer p = MC.player;
		if(p == null || originalChest == null || !hasElytra()
			|| p.getInventory().getFreeSlot() < 0)
			return;
		IMC.getInteractionManager().windowClick_QUICK_MOVE(6);
		int slot = InventoryUtils
			.indexOf(stack -> stack.is(originalChest.getItem()), 36, false);
		if(slot >= 0)
			IMC.getInteractionManager()
				.windowClick_QUICK_MOVE(InventoryUtils.toNetworkSlot(slot));
		originalChest = null;
	}
	
	@Override
	public String getStatusText()
	{
		return MC.player != null && !hasUsableElytra()
			? "[Waiting for usable elytra]" : null;
	}
	
	public String getInfoString()
	{
		return MC.player == null ? null
			: !hasUsableElytra() ? "waiting for usable elytra"
				: serverGliding ? "soaring" : "lighting";
	}
}
