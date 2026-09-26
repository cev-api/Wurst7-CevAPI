/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Locale;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.EnderpearlItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.GUIRenderListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

/**
 * Gives an ender pearl the movement delta accepted by the vanilla movement
 * check immediately before the pearl-use packet is sent.
 *
 * <p>
 * The four padding packets are intentional. On 26.3, the fifth positional
 * packet is the one that receives the 5-packet movement-check allowance. The
 * existing connection compatibility layer inserts the required synthetic
 * client tick ends between these packets.
 *
 * A positional packet sent earlier in the same server tick can shift the
 * server's counter. The client cannot observe the server's
 * knownMovePacketCount, so this keeps the proven ArrowDMG burst as the safe
 * baseline instead of guessing at server-tick alignment.
 * </p>
 */
@SearchTags({"pearl launcher", "pearl boost", "ender pearl launcher"})
public final class PearlLauncherHack extends Hack implements GUIRenderListener
{
	private static final double NORMAL_METERS_PER_TICK = 100.0;
	private static final double FALL_FLYING_METERS_PER_TICK = 300.0;
	private static final int MAX_ACCEPTED_PACKETS = 5;
	private static final double MOVEMENT_CHECK_SAFETY_MARGIN = 0.995;
	private static final double PEARL_THROW_POWER = 1.5;
	private static final int MAX_RANGE_ESTIMATE_STEPS = 2000;
	
	private final SliderSetting power = new SliderSetting("Power",
		"description.wurst.setting.pearllauncher.power", 100, 1, 100, 1,
		ValueDisplay.INTEGER.withSuffix("%"));
	
	private final CheckboxSetting rangeHud = new CheckboxSetting("Range HUD",
		"description.wurst.setting.pearllauncher.range_hud", false);
	
	private final CheckboxSetting airbornePacketMode = new CheckboxSetting(
		"Airborne Packet Mode",
		"description.wurst.setting.pearllauncher.airborne_packet_mode", true);
	
	private final CheckboxSetting exploitElytraLimit = new CheckboxSetting(
		"Exploit Elytra Limit",
		"description.wurst.setting.pearllauncher.exploit_elytra_limit", true);
	
	private final CheckboxSetting sendSprintPacket =
		new CheckboxSetting("Send Sprint Packet",
			"description.wurst.setting.pearllauncher.send_sprint_packet", true);
	
	private final CheckboxSetting debug = new CheckboxSetting("Debug",
		"description.wurst.setting.pearllauncher.debug", false);
	
	private long nextRangeHudUpdate;
	private String rangeHudText = "";
	private boolean pearlDamageWarning;
	
	public PearlLauncherHack()
	{
		super("PearlLauncher");
		setCategory(Category.COMBAT);
		addSetting(power);
		addSetting(rangeHud);
		addSetting(airbornePacketMode);
		addSetting(exploitElytraLimit);
		addSetting(sendSprintPacket);
		addSetting(debug);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(GUIRenderListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(GUIRenderListener.class, this);
		rangeHudText = "";
		pearlDamageWarning = false;
	}
	
	@Override
	public void onRenderGUI(GuiGraphicsExtractor context, float partialTicks)
	{
		if(!rangeHud.isChecked() || MC.player == null
			|| findPearlHand(MC.player) == null)
			return;
		
		long now = System.nanoTime();
		if(now >= nextRangeHudUpdate)
		{
			rangeHudText = buildRangeHudText(MC.player);
			pearlDamageWarning = MC.player.getHealth() <= 5.0F;
			nextRangeHudUpdate = now + 100_000_000L;
		}
		
		if(rangeHudText.isBlank())
			return;
		
		Font font = MC.font;
		int centerX = context.guiWidth() / 2;
		int y = context.guiHeight() / 2 + 12;
		if(pearlDamageWarning)
		{
			drawHudLine(context, font, "WARNING: PEARL WILL KILL YOU", centerX,
				y, 0xFFFF5555);
			y += font.lineHeight + 3;
		}
		drawHudLine(context, font, rangeHudText, centerX, y, 0xFFFFFFFF);
	}
	
	private void drawHudLine(GuiGraphicsExtractor context, Font font,
		String text, int centerX, int y, int color)
	{
		int width = font.width(text);
		context.fill(centerX - width / 2 - 3, y - 2, centerX + width / 2 + 3,
			y + font.lineHeight + 2, 0x80000000);
		context.text(font, text, centerX - width / 2, y, color, true);
	}
	
	private String buildRangeHudText(LocalPlayer player)
	{
		InteractionHand hand = findPearlHand(player);
		if(hand == null)
			return "";
		
		double range = estimatePearlRange(player, hand);
		if(range < 0)
			return "Pearl range: unknown";
		
		return String.format(Locale.ROOT,
			"Pearl range: ~%.0f blocks | pitch: %+.1f", range,
			player.getXRot());
	}
	
	private InteractionHand findPearlHand(LocalPlayer player)
	{
		if(player.getMainHandItem().getItem() instanceof EnderpearlItem)
			return InteractionHand.MAIN_HAND;
		
		if(player.getOffhandItem().getItem() instanceof EnderpearlItem)
			return InteractionHand.OFF_HAND;
		
		return null;
	}
	
	private double estimatePearlRange(LocalPlayer player, InteractionHand hand)
	{
		Vec3 start = player.getEyePosition().add(0, -0.1, 0);
		HitResult surface =
			player.level().clip(new ClipContext(start, start.add(0, -512, 0),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		if(surface.getType() != HitResult.Type.BLOCK)
			return -1;
		
		double surfaceY = surface.getLocation().y;
		if(surfaceY >= start.y)
			return -1;
		
		Vec3 position = start;
		Vec3 motion = getEstimatedPearlVelocity(player, hand);
		for(int i = 0; i < MAX_RANGE_ESTIMATE_STEPS; i++)
		{
			Vec3 next = position.add(motion.scale(0.1));
			if(next.y <= surfaceY)
			{
				double denominator = position.y - next.y;
				double fraction =
					denominator > 0 ? (position.y - surfaceY) / denominator : 1;
				fraction = Math.max(0, Math.min(1, fraction));
				Vec3 impact = position.lerp(next, fraction);
				double dx = impact.x - start.x;
				double dz = impact.z - start.z;
				return Math.sqrt(dx * dx + dz * dz);
			}
			
			position = next;
			motion = motion.scale(0.999).add(0, -0.003, 0);
		}
		
		return -1;
	}
	
	/**
	 * Sends the packet-only burst immediately before vanilla sends the pearl
	 * use packet. This method is called from MultiPlayerGameMode.useItem(), so
	 * it also covers off-hand uses and all normal vanilla interaction checks.
	 */
	public void preparePearlUse(LocalPlayer player, InteractionHand hand)
	{
		Vec3 inherited = getEstimatedInheritedVelocity(player, hand);
		if(inherited == Vec3.ZERO)
			return;
		
		if(sendSprintPacket.isChecked())
			player.connection.send(new ServerboundPlayerCommandPacket(player,
				Action.START_SPRINTING));
		
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		boolean onGround =
			airbornePacketMode.isChecked() ? false : player.onGround();
		boolean horizontalCollision =
			airbornePacketMode.isChecked() ? false : player.horizontalCollision;
		
		// Positional packet #5 is the maximum-displacement packet. Do not add
		// a tick end after the final return packet: the pearl-use packet must
		// observe the return movement as ServerPlayer.knownMovement.
		for(int i = 0; i < 4; i++)
			sendPos(player, x, y, z, onGround, horizontalCollision);
		
		Vec3 fakePosition = new Vec3(x, y, z).subtract(inherited);
		sendPos(player, fakePosition.x, fakePosition.y, fakePosition.z,
			onGround, horizontalCollision);
		sendPos(player, x, y, z, onGround, horizontalCollision);
		
		if(debug.isChecked())
		{
			Vec3 pearlVelocity = getVanillaPearlVelocity(player).add(inherited);
			System.out.println("PearlLauncher: packets=real x4, fake #5, "
				+ "real #6; inherited=" + format(inherited.length())
				+ " b/t; pearl=" + format(pearlVelocity.length()) + " b/t"
				+ (player.isFallFlying() ? " (fall-flying)" : ""));
		}
	}
	
	private void sendPos(LocalPlayer player, double x, double y, double z,
		boolean onGround, boolean horizontalCollision)
	{
		player.connection.send(new Pos(x, y, z, onGround, horizontalCollision));
	}
	
	/**
	 * Returns the exact movement vector PearlLauncher intends to leave in the
	 * server's knownMovement field for this pearl.
	 */
	public Vec3 getEstimatedInheritedVelocity(LocalPlayer player,
		InteractionHand hand)
	{
		if(!isValidPearlUse(player, hand))
			return Vec3.ZERO;
		
		Vec3 aim = player.getViewVector(1.0F).normalize();
		if(player.onGround())
		{
			// Grounded movement is collision-sensitive and the server may turn
			// an upward onGround=false packet into a jump. Keep the inherited
			// burst horizontal; the vanilla pearl still keeps the user's pitch.
			aim = new Vec3(aim.x, 0, aim.z);
			if(aim.lengthSqr() < 1.0E-12)
			{
				double yaw = Math.toRadians(player.getYRot());
				aim = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
			}else
				aim = aim.normalize();
		}
		
		Vec3 inherited = aim.scale(getLaunchStrength(player));
		if(!player.onGround() && inherited.y != 0
			&& fakePathHitsBlock(player, inherited))
		{
			// A downward fake position can be inside terrain even when the
			// pearl itself would have a clear trajectory. In that case preserve
			// the useful horizontal inheritance instead of being corrected.
			Vec3 horizontalAim = new Vec3(aim.x, 0, aim.z);
			if(horizontalAim.lengthSqr() >= 1.0E-12)
				inherited =
					horizontalAim.normalize().scale(getLaunchStrength(player));
		}
		return inherited;
	}
	
	private boolean fakePathHitsBlock(LocalPlayer player, Vec3 inherited)
	{
		Vec3 start = player.position();
		Vec3 fake = start.subtract(inherited);
		HitResult hit = player.level().clip(new ClipContext(start, fake,
			ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.BLOCK;
	}
	
	/**
	 * Returns the vanilla pearl launch velocity plus the inherited movement
	 * that PearlLauncher would manufacture for this use.
	 */
	public Vec3 getEstimatedPearlVelocity(LocalPlayer player,
		InteractionHand hand)
	{
		if(!isValidPearlUse(player, hand))
			return Vec3.ZERO;
		
		return getVanillaPearlVelocity(player)
			.add(getEstimatedInheritedVelocity(player, hand));
	}
	
	/**
	 * Exposes a conservative vanilla-safe maximum before Power is applied. The
	 * small margin prevents floating-point rounding from exceeding the server's
	 * movement-check threshold when the player is grounded.
	 */
	public double getMaximumDisplacement(LocalPlayer player)
	{
		if(player == null)
			return 0;
		
		double metersPerTick =
			exploitElytraLimit.isChecked() && player.isFallFlying()
				? FALL_FLYING_METERS_PER_TICK : NORMAL_METERS_PER_TICK;
		double expectedMovement =
			Math.max(0, player.getDeltaMovement().lengthSqr());
		return Math
			.sqrt(metersPerTick * MAX_ACCEPTED_PACKETS + expectedMovement)
			* MOVEMENT_CHECK_SAFETY_MARGIN;
	}
	
	private double getLaunchStrength(LocalPlayer player)
	{
		return getMaximumDisplacement(player) * power.getValue() / 100.0;
	}
	
	private boolean isValidPearlUse(LocalPlayer player, InteractionHand hand)
	{
		if(!isEnabled() || player == null || hand == null
			|| player.isSpectator())
			return false;
		
		ItemStack stack = player.getItemInHand(hand);
		return stack.getItem() instanceof EnderpearlItem
			&& !player.getCooldowns().isOnCooldown(stack);
	}
	
	private Vec3 getVanillaPearlVelocity(LocalPlayer player)
	{
		return player.getViewVector(1.0F).normalize().scale(PEARL_THROW_POWER);
	}
	
	private String format(double value)
	{
		return String.format(java.util.Locale.ROOT, "%.2f", value);
	}
}
