/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.HandleInputListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.IKeyMapping;
import net.wurstclient.mixinterface.IClientPlayerInteractionManager;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.util.EntityUtils;
import net.wurstclient.util.InventoryUtils;
import net.wurstclient.util.ChatUtils;

@SearchTags({"shield", "shield swing", "swing while blocking",
	"attack while blocking"})
public final class ShieldSwingHack extends Hack implements HandleInputListener
{
	private final CheckboxSetting autoHoldShield =
		new CheckboxSetting("Auto hold shield",
			"Automatically holds right-click while this hack is enabled and you"
				+ " have a shield in your offhand.",
			false);
	
	private final CheckboxSetting autoEquipShield =
		new CheckboxSetting("Auto equip shield",
			"Automatically moves a shield from your inventory into your offhand"
				+ " while this hack is enabled.",
			false);
	
	private final CheckboxSetting useNoShieldOverlay =
		new CheckboxSetting("Use NoShieldOverlay",
			"Applies NoShieldOverlay while ShieldSwing is enabled, even if"
				+ " NoShieldOverlay itself is turned off.",
			false);
	
	private boolean attackKeyWasDown;
	private boolean forcingUseKey;
	private int nextTickSlot = -1;
	private boolean autoTotemWasEnabledBeforeShieldSwing;
	
	public ShieldSwingHack()
	{
		super("ShieldSwing");
		setCategory(Category.COMBAT);
		addSetting(autoHoldShield);
		addSetting(autoEquipShield);
		addSetting(useNoShieldOverlay);
	}
	
	@Override
	protected void onEnable()
	{
		if(WURST.getHax().autoTotemHack.isEnabled())
		{
			autoTotemWasEnabledBeforeShieldSwing = true;
			WURST.getHax().autoTotemHack.setEnabled(false);
			ChatUtils
				.warning("AutoTotem disabled because ShieldSwing is enabled.");
		}else
		{
			autoTotemWasEnabledBeforeShieldSwing = false;
		}
		
		attackKeyWasDown = false;
		forcingUseKey = false;
		nextTickSlot = -1;
		EVENTS.add(HandleInputListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		releaseUseKey();
		finishShieldSwap();
		EVENTS.remove(HandleInputListener.class, this);
		
		if(autoTotemWasEnabledBeforeShieldSwing
			&& !WURST.getHax().autoTotemHack.isEnabled())
		{
			autoTotemWasEnabledBeforeShieldSwing = false;
			WURST.getHax().autoTotemHack.setEnabled(true);
			ChatUtils.message("AutoTotem restored.");
		}
	}
	
	@Override
	public void onHandleInput()
	{
		if(MC.player == null || MC.gameMode == null || MC.options == null)
		{
			releaseUseKey();
			attackKeyWasDown = false;
			nextTickSlot = -1;
			return;
		}
		
		finishShieldSwap();
		tryAutoEquipShield();
		tryAutoHoldShield();
		
		if(shouldRestoreAutoTotemNow())
		{
			setEnabled(false);
			return;
		}
		
		if(MC.screen != null)
		{
			attackKeyWasDown = false;
			return;
		}
		
		boolean attackKeyDown = MC.options.keyAttack.isDown();
		boolean attackKeyPressed = attackKeyDown && !attackKeyWasDown;
		attackKeyWasDown = attackKeyDown;
		if(!attackKeyPressed)
			return;
		
		LocalPlayer player = MC.player;
		if(!player.isUsingItem() || !player.getUseItem().is(Items.SHIELD))
			return;
		
		if(!(MC.hitResult instanceof EntityHitResult eHitResult))
			return;
		
		Entity target = eHitResult.getEntity();
		if(target == null || !EntityUtils.IS_ATTACKABLE.test(target))
			return;
		
		WURST.getHax().autoSwordHack.setSlot(target);
		MC.gameMode.attack(player, target);
		player.swing(InteractionHand.MAIN_HAND);
	}
	
	public boolean shouldUseNoShieldOverlay()
	{
		return isEnabled() && useNoShieldOverlay.isChecked();
	}
	
	private void tryAutoHoldShield()
	{
		boolean shouldForce = autoHoldShield.isChecked() && MC.screen == null
			&& hasShieldInOffhand(MC.player);
		
		if(shouldForce)
		{
			MC.options.keyUse.setDown(true);
			forcingUseKey = true;
			return;
		}
		
		releaseUseKey();
	}
	
	private void tryAutoEquipShield()
	{
		if(!autoEquipShield.isChecked())
			return;
		
		if(hasShieldInOffhand(MC.player))
			return;
		
		if(MC.player.containerMenu != null
			&& !MC.player.containerMenu.getCarried().isEmpty())
			return;
		
		if(MC.screen instanceof AbstractContainerScreen
			&& !(MC.screen instanceof InventoryScreen
				|| MC.screen instanceof CreativeModeInventoryScreen))
			return;
		
		int shieldSlot = InventoryUtils.indexOf(this::isShield, 40);
		if(shieldSlot < 0 || shieldSlot == 40)
			return;
		
		moveToOffhand(InventoryUtils.toNetworkSlot(shieldSlot));
	}
	
	private void moveToOffhand(int itemSlot)
	{
		boolean offhandEmpty = MC.player.getOffhandItem().isEmpty();
		IClientPlayerInteractionManager interactionManager =
			IMC.getInteractionManager();
		interactionManager.windowClick_PICKUP(itemSlot);
		interactionManager.windowClick_PICKUP(45);
		
		if(!offhandEmpty)
			nextTickSlot = itemSlot;
	}
	
	private void finishShieldSwap()
	{
		if(nextTickSlot == -1 || MC.player == null)
			return;
		
		IClientPlayerInteractionManager interactionManager =
			IMC.getInteractionManager();
		interactionManager.windowClick_PICKUP(nextTickSlot);
		nextTickSlot = -1;
	}
	
	private boolean hasShieldInOffhand(LocalPlayer player)
	{
		return isShield(player.getOffhandItem());
	}
	
	private boolean isShield(ItemStack stack)
	{
		return stack != null && stack.is(Items.SHIELD);
	}
	
	private void releaseUseKey()
	{
		if(!forcingUseKey || MC.options == null)
			return;
		
		IKeyMapping.get(MC.options.keyUse).resetPressedState();
		forcingUseKey = false;
	}
	
	private boolean shouldRestoreAutoTotemNow()
	{
		if(!autoTotemWasEnabledBeforeShieldSwing || MC.player == null)
			return false;
		
		if(hasShieldInOffhand(MC.player))
			return false;
		
		boolean canAutoEquip = autoEquipShield.isChecked()
			&& InventoryUtils.indexOf(this::isShield, 40, false) >= 0;
		return !canAutoEquip;
	}
}
