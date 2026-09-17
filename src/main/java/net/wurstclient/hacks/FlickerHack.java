/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import java.util.Random;

import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.wurstclient.Category;
import net.wurstclient.hack.Hack;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"flicker", "skin flicker", "skin animation"})
public final class FlickerHack extends Hack implements UpdateListener
{
	private final EnumSetting<Mode> mode = new EnumSetting<>("Mode",
		"description.wurst.setting.flicker.mode", Mode.values(), Mode.PEEL);
	private final SliderSetting rate =
		new SliderSetting("Rate", "description.wurst.setting.flicker.rate", 4,
			1, 40, 1, ValueDisplay.INTEGER);
	private final CheckboxSetting keepCape = new CheckboxSetting("Keep Cape",
		"description.wurst.setting.flicker.keep_cape", false);
	private final CheckboxSetting keepHat = new CheckboxSetting("Keep Hat",
		"description.wurst.setting.flicker.keep_hat", false);
	private final CheckboxSetting swapHands = new CheckboxSetting("Swap Hands",
		"description.wurst.setting.flicker.swap_hands", false);
	private final SliderSetting handRate = new SliderSetting("Hand Rate",
		"description.wurst.setting.flicker.hand_rate", 10, 1, 60, 1,
		ValueDisplay.INTEGER);
	private final CheckboxSetting itemStrobe = new CheckboxSetting(
		"Item Strobe", "description.wurst.setting.flicker.item_strobe", false);
	private final SliderSetting itemRate = new SliderSetting("Item Rate",
		"description.wurst.setting.flicker.item_rate", 2, 1, 20, 1,
		ValueDisplay.INTEGER);
	
	private static final PlayerModelPart[] ORDER = {PlayerModelPart.CAPE,
		PlayerModelPart.JACKET, PlayerModelPart.LEFT_SLEEVE,
		PlayerModelPart.RIGHT_SLEEVE, PlayerModelPart.LEFT_PANTS_LEG,
		PlayerModelPart.RIGHT_PANTS_LEG, PlayerModelPart.HAT};
	private static final int ALL = mask(ORDER);
	private final Random random = new Random();
	private ClientInformation original;
	private int timer;
	private int handTimer;
	private int itemTimer;
	private int step;
	private int originalSlot = -1;
	private HumanoidArm arm = HumanoidArm.RIGHT;
	
	public FlickerHack()
	{
		super("Flicker");
		setCategory(Category.FUN);
		addSetting(mode);
		addSetting(rate);
		addSetting(keepCape);
		addSetting(keepHat);
		addSetting(swapHands);
		addSetting(handRate);
		addSetting(itemStrobe);
		addSetting(itemRate);
	}
	
	@Override
	protected void onEnable()
	{
		original = MC.options.buildPlayerInformation();
		arm = original.mainHand();
		timer = handTimer = itemTimer = 0;
		step = 0;
		originalSlot =
			MC.player == null ? -1 : MC.player.getInventory().getSelectedSlot();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		if(original != null && MC.getConnection() != null)
			MC.getConnection()
				.send(new ServerboundClientInformationPacket(original));
		if(originalSlot >= 0 && MC.player != null)
			MC.player.getInventory().setSelectedSlot(originalSlot);
		original = null;
		originalSlot = -1;
	}
	
	@Override
	public void onUpdate()
	{
		if(original == null || MC.player == null || MC.getConnection() == null)
			return;
		boolean dirty = false;
		if(--timer <= 0)
		{
			timer = rate.getValueI();
			step++;
			dirty = true;
		}
		if(swapHands.isChecked() && --handTimer <= 0)
		{
			handTimer = handRate.getValueI();
			arm =
				arm == HumanoidArm.RIGHT ? HumanoidArm.LEFT : HumanoidArm.RIGHT;
			dirty = true;
		}
		if(dirty)
			MC.getConnection().send(
				new ServerboundClientInformationPacket(copy(mask(), arm)));
		if(itemStrobe.isChecked() && --itemTimer <= 0)
		{
			itemTimer = itemRate.getValueI();
			MC.player.getInventory().setSelectedSlot(
				(MC.player.getInventory().getSelectedSlot() + 1) % 9);
		}
	}
	
	private ClientInformation copy(int modelMask, HumanoidArm hand)
	{
		return new ClientInformation(original.language(),
			original.viewDistance(), original.chatVisibility(),
			original.chatColors(), modelMask, hand,
			original.textFilteringEnabled(), original.allowsListing(),
			original.particleStatus());
	}
	
	private int mask()
	{
		int locked = (keepCape.isChecked() ? PlayerModelPart.CAPE.getMask() : 0)
			| (keepHat.isChecked() ? PlayerModelPart.HAT.getMask() : 0);
		int free = ALL & ~locked;
		int bits = switch(mode.getSelected())
		{
			case STROBE -> step % 2 == 0 ? free : 0;
			case PEEL ->
			{
				int n = ORDER.length + 1;
				int phase = step % (n * 2);
				int shown = phase < n ? ORDER.length - phase : phase - n;
				int value = 0;
				for(int i = 0; i < Math.min(shown, ORDER.length); i++)
					value |= ORDER[i].getMask();
				yield value & free;
			}
			case CHASE -> ORDER[step % ORDER.length].getMask() & free;
			case STATIC -> random.nextInt(ALL + 1) & free;
			case BARE -> 0;
		};
		return bits | locked;
	}
	
	private static int mask(PlayerModelPart[] parts)
	{
		int value = 0;
		for(PlayerModelPart part : parts)
			value |= part.getMask();
		return value;
	}
	
	public String getInfoString()
	{
		return mode.getSelected().toString().toLowerCase();
	}
	
	public enum Mode
	{
		STROBE,
		PEEL,
		CHASE,
		STATIC,
		BARE
	}
}
