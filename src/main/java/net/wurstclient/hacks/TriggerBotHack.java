/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.HandleInputListener;
import net.wurstclient.events.PreMotionListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyBinding;
import net.wurstclient.settings.AttackSpeedSliderSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.MobWeaponRuleSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.SwingHandSetting;
import net.wurstclient.settings.SwingHandSetting.SwingHand;
import net.wurstclient.settings.filterlists.EntityFilterList;
import net.wurstclient.util.EntityUtils;

@SearchTags({"trigger bot", "AutoAttack", "auto attack", "AutoClicker",
	"auto clicker"})
public final class TriggerBotHack extends Hack
	implements PreMotionListener, HandleInputListener
{
	private final SliderSetting range =
		new SliderSetting("Range", 4.25, 1, 6, 0.05, ValueDisplay.DECIMAL);
	
	private final AttackSpeedSliderSetting speed =
		new AttackSpeedSliderSetting();
	
	private final SliderSetting speedRandMS =
		new SliderSetting("Speed randomization",
			"Helps you bypass anti-cheat plugins by varying the delay between"
				+ " attacks.\n\n" + "\u00b1100ms is recommended for Vulcan.\n\n"
				+ "0 (off) is fine for NoCheat+, AAC, Grim, Verus, Spartan, and"
				+ " vanilla servers.",
			100, 0, 1000, 50, ValueDisplay.INTEGER.withPrefix("\u00b1")
				.withSuffix("ms").withLabel(0, "off"));
	
	private final SwingHandSetting swingHand =
		new SwingHandSetting(this, SwingHand.CLIENT);
	
	private final CheckboxSetting attackWhileBlocking =
		new CheckboxSetting("Attack while blocking",
			"Attacks even while you're blocking with a shield or using"
				+ " items.\n\n"
				+ "This would not be possible in vanilla and won't work if"
				+ " \"Simulate mouse click\" is enabled.",
			false);
	
	private final CheckboxSetting simulateMouseClick = new CheckboxSetting(
		"Simulate mouse click",
		"Simulates an actual mouse click (or key press) when attacking. Can be"
			+ " used to trick CPS measuring tools into thinking that you're"
			+ " attacking manually.\n\n"
			+ "\u00a7c\u00a7lWARNING:\u00a7r Simulating mouse clicks can lead"
			+ " to unexpected behavior, like in-game menus clicking themselves."
			+ " Also, the \"Swing hand\" and \"Attack while blocking\" settings"
			+ " will not work while this option is enabled.",
		false);
	
	private final EntityFilterList entityFilters =
		EntityFilterList.genericCombat();
	
	private final MobWeaponRuleSetting[] weaponRuleSettings =
		new MobWeaponRuleSetting[]{new MobWeaponRuleSetting("Mob Tool Rule 1"),
			new MobWeaponRuleSetting("Mob Tool Rule 2"),
			new MobWeaponRuleSetting("Mob Tool Rule 3")};
	private final WeaponRuleController[] weaponRuleControllers =
		new WeaponRuleController[]{
			new WeaponRuleController(weaponRuleSettings[0]),
			new WeaponRuleController(weaponRuleSettings[1]),
			new WeaponRuleController(weaponRuleSettings[2])};
	
	private boolean simulatingMouseClick;
	
	public TriggerBotHack()
	{
		super("TriggerBot");
		setCategory(Category.COMBAT);
		
		addSetting(range);
		addSetting(speed);
		addSetting(speedRandMS);
		addSetting(swingHand);
		addSetting(attackWhileBlocking);
		addSetting(simulateMouseClick);
		
		entityFilters.forEach(this::addSetting);
		for(MobWeaponRuleSetting setting : weaponRuleSettings)
			addSetting(setting);
	}
	
	@Override
	protected void onEnable()
	{
		// disable other killauras
		WURST.getHax().clickAuraHack.setEnabled(false);
		WURST.getHax().crystalAuraHack.setEnabled(false);
		WURST.getHax().fightBotHack.setEnabled(false);
		WURST.getHax().killauraLegitHack.setEnabled(false);
		WURST.getHax().killauraHack.setEnabled(false);
		WURST.getHax().multiAuraHack.setEnabled(false);
		WURST.getHax().protectHack.setEnabled(false);
		WURST.getHax().tpAuraHack.setEnabled(false);
		
		speed.resetTimer(speedRandMS.getValue());
		EVENTS.add(PreMotionListener.class, this);
		EVENTS.add(HandleInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		if(simulatingMouseClick)
		{
			IKeyBinding.get(MC.options.keyAttack).simulatePress(false);
			simulatingMouseClick = false;
		}
		
		for(WeaponRuleController controller : weaponRuleControllers)
			controller.reset();
		
		EVENTS.remove(PreMotionListener.class, this);
		EVENTS.remove(HandleInputListener.class, this);
	}
	
	@Override
	public void onPreMotion()
	{
		if(!simulatingMouseClick)
			return;
		
		IKeyBinding.get(MC.options.keyAttack).simulatePress(false);
		simulatingMouseClick = false;
	}
	
	@Override
	public void onHandleInput()
	{
		speed.updateTimer();
		LocalPlayer player = MC.player;
		if(player == null)
		{
			releaseWeaponRules();
			return;
		}
		
		// don't attack when a container/inventory screen is open
		if(MC.screen instanceof AbstractContainerScreen)
		{
			releaseWeaponRules();
			return;
		}
		
		if(!attackWhileBlocking.isChecked() && player.isUsingItem())
		{
			releaseWeaponRules();
			return;
		}
		
		Entity target = getTargetUnderCrosshair();
		boolean usedCustomRule = applyWeaponRules(target);
		if(target == null)
			return;
		
		if(!speed.isTimeToAttack())
			return;
		
		if(!usedCustomRule)
			WURST.getHax().autoSwordHack.setSlot(target);
		
		if(simulateMouseClick.isChecked())
		{
			IKeyBinding.get(MC.options.keyAttack).simulatePress(true);
			simulatingMouseClick = true;
			
		}else
		{
			MC.gameMode.attack(player, target);
			swingHand.swing(InteractionHand.MAIN_HAND);
		}
		
		speed.resetTimer(speedRandMS.getValue());
	}
	
	private boolean isCorrectEntity(Entity entity)
	{
		if(!EntityUtils.IS_ATTACKABLE.test(entity))
			return false;
		
		if(MC.player.distanceToSqr(entity) > range.getValueSq())
			return false;
		
		return entityFilters.testOne(entity);
	}
	
	private Entity getTargetUnderCrosshair()
	{
		if(MC.hitResult == null
			|| !(MC.hitResult instanceof EntityHitResult eResult))
			return null;
		
		Entity entity = eResult.getEntity();
		return isCorrectEntity(entity) ? entity : null;
	}
	
	private boolean applyWeaponRules(Entity target)
	{
		boolean applied = false;
		for(WeaponRuleController controller : weaponRuleControllers)
		{
			if(!applied && controller.tryApply(target))
			{
				applied = true;
				continue;
			}
			
			controller.deactivate();
		}
		
		return applied;
	}
	
	private void releaseWeaponRules()
	{
		for(WeaponRuleController controller : weaponRuleControllers)
			controller.deactivate();
	}
	
	private final class WeaponRuleController
	{
		private final MobWeaponRuleSetting setting;
		private int previousSlot = -1;
		private boolean active;
		
		private WeaponRuleController(MobWeaponRuleSetting setting)
		{
			this.setting = setting;
		}
		
		private boolean tryApply(Entity target)
		{
			if(target == null || !setting.isActiveFor(target))
				return false;
			
			LocalPlayer player = MC.player;
			if(player == null)
				return false;
			
			int slot = setting.findPreferredHotbarSlot(player);
			if(slot < 0)
				return false;
			
			if(previousSlot == -1)
				previousSlot = player.getInventory().getSelectedSlot();
			
			player.getInventory().setSelectedSlot(slot);
			active = true;
			return true;
		}
		
		private void deactivate()
		{
			if(!active && previousSlot == -1)
				return;
			
			active = false;
			restoreSlot();
		}
		
		private void reset()
		{
			active = false;
			restoreSlot();
		}
		
		private void restoreSlot()
		{
			if(previousSlot != -1 && MC.player != null)
				MC.player.getInventory().setSelectedSlot(previousSlot);
			previousSlot = -1;
		}
	}
}
