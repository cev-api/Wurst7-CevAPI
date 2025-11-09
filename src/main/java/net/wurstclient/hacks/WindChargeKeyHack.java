/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.UpdateListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

@SearchTags({"wind charge", "auto wind charge", "windcharge"})
public final class WindChargeKeyHack extends Hack implements UpdateListener
{
	private final TextFieldSetting keybind = new TextFieldSetting("Keybind",
		"Determines the activation key.\n\n"
			+ "Use translation keys such as \u00a7lkey.keyboard.g\u00a7r.\n"
			+ "You can find these by looking at the F3 debug screen or by\n"
			+ "checking vanilla keybind configuration files.",
		"key.keyboard.g", this::isValidKey);
	
	private final SliderSetting switchDelayMs = new SliderSetting(
		"Switch delay", 50, 0, 500, 5, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final SliderSetting throwDelayMs = new SliderSetting("Throw delay",
		200, 0, 1000, 25, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private final CheckboxSetting silentMode = new CheckboxSetting("Silent",
		"Switches to the wind charge, uses it, and immediately switches back"
			+ " within the same tick.",
		false);
	
	private final CheckboxSetting autoJump = new CheckboxSetting("Auto jump",
		"Automatically jumps before throwing if you are on the ground.", true);
	
	private final CheckboxSetting lookDownBeforeThrow = new CheckboxSetting(
		"Look down",
		"Faces straight down before throwing to maximize launch height.", true);
	
	private final SliderSetting jumpDelayMs = new SliderSetting("Jump delay",
		10, 0, 200, 5, ValueDisplay.INTEGER.withSuffix("ms"));
	
	private boolean keyPressed;
	private int originalSlot = -1;
	private boolean needsSlotRestore;
	private boolean firstThrow = true;
	private boolean jumpScheduled;
	private long lastThrowTime;
	private long switchBackTime;
	private long jumpStartTime;
	private boolean awaitingLaunch;
	private int pendingSlot = -1;
	private boolean pendingSilent;
	private boolean pendingLookDown;
	private long launchDeadline;
	private boolean restorePitch;
	private float savedPitch;
	private long restorePitchAt;
	
	public WindChargeKeyHack()
	{
		super("WindChargeKey");
		setCategory(Category.COMBAT);
		
		addSetting(keybind);
		addSetting(switchDelayMs);
		addSetting(throwDelayMs);
		addSetting(silentMode);
		addSetting(autoJump);
		addSetting(lookDownBeforeThrow);
		addSetting(jumpDelayMs);
	}
	
	@Override
	protected void onEnable()
	{
		resetState();
		EVENTS.add(UpdateListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(UpdateListener.class, this);
		
		if(needsSlotRestore && originalSlot != -1 && MC.player != null)
			MC.player.getInventory().setSelectedSlot(originalSlot);
		
		resetState();
	}
	
	@Override
	public void onUpdate()
	{
		if(MC.player == null || MC.world == null)
			return;
		
		if(MC.currentScreen != null)
		{
			keyPressed = false;
			return;
		}
		
		InputUtil.Key key = getBoundKey();
		if(key == null)
			return;
		
		boolean currentlyPressed = InputUtil
			.isKeyPressed(MC.getWindow().getHandle(), key.getCode());
		
		if(currentlyPressed && !keyPressed)
			handleWindChargeThrow();
		
		long now = System.currentTimeMillis();
		
		if(jumpScheduled && autoJump.isChecked()
			&& now - jumpStartTime >= getJumpDelay())
		{
			jumpScheduled = false;
			
			if(MC.player.isOnGround())
				MC.player.jump();
		}
		
		if(awaitingLaunch)
		{
			boolean airborne = !MC.player.isOnGround();
			boolean upward = MC.player.getVelocity().y > 0.08;
			boolean ready = upward || (!autoJump.isChecked() && airborne)
				|| now >= launchDeadline;
			
			if(ready)
			{
				awaitingLaunch = false;
				int slot = pendingSlot;
				boolean silent = pendingSilent;
				boolean lookDown = pendingLookDown;
				clearPending();
				performThrow(slot, silent, lookDown);
			}
		}
		
		if(restorePitch && now >= restorePitchAt)
			restorePitchImmediate(savedPitch);
		
		if(needsSlotRestore && now - switchBackTime >= getSwitchDelay())
		{
			if(originalSlot != -1
				&& originalSlot != MC.player.getInventory().getSelectedSlot())
				MC.player.getInventory().setSelectedSlot(originalSlot);
			
			needsSlotRestore = false;
			originalSlot = -1;
		}
		
		keyPressed = currentlyPressed;
	}
	
	private void handleWindChargeThrow()
	{
		long now = System.currentTimeMillis();
		
		if(awaitingLaunch)
			return;
		
		if(!firstThrow && now - lastThrowTime < getThrowDelay())
			return;
		
		int windChargeSlot = findWindChargeInHotbar();
		if(windChargeSlot == -1)
			return;
		
		boolean silent = silentMode.isChecked();
		boolean lookDown = lookDownBeforeThrow.isChecked();
		
		if(autoJump.isChecked() && MC.player.isOnGround())
		{
			clearPending();
			pendingSlot = windChargeSlot;
			pendingSilent = silent;
			pendingLookDown = lookDown;
			launchDeadline = now + Math.max(250L, getJumpDelay() + 200L);
			awaitingLaunch = true;
			scheduleJump(now);
			return;
		}
		
		performThrow(windChargeSlot, silent, lookDown);
	}
	
	private void scheduleJump(long now)
	{
		long delay = getJumpDelay();
		if(delay <= 0)
		{
			if(MC.player.isOnGround())
				MC.player.jump();
			return;
		}
		
		jumpScheduled = true;
		jumpStartTime = now;
	}
	
	private void performThrow(int preferredSlot, boolean silent,
		boolean lookDown)
	{
		clearPending();
		
		int slot = resolveWindChargeSlot(preferredSlot);
		if(slot == -1)
			return;
		
		float previousPitch = 0F;
		boolean appliedLookDown = lookDown && lookDownBeforeThrow.isChecked();
		if(appliedLookDown)
			previousPitch = applyLookDown();
		
		long now = System.currentTimeMillis();
		boolean success = silent ? throwWindChargeSilently(slot)
			: throwWindChargeNormally(slot, now);
		
		if(!success)
		{
			if(appliedLookDown)
				restorePitchImmediate(previousPitch);
			return;
		}
		
		if(appliedLookDown)
		{
			savedPitch = previousPitch;
			restorePitch = true;
			restorePitchAt = now + 150;
		}else
		{
			restorePitch = false;
			restorePitchAt = 0;
		}
		
		firstThrow = false;
		lastThrowTime = now;
	}
	
	private int resolveWindChargeSlot(int preferredSlot)
	{
		if(preferredSlot >= 0 && preferredSlot < 9)
		{
			ItemStack stack = MC.player.getInventory().getStack(preferredSlot);
			if(stack.isOf(Items.WIND_CHARGE))
				return preferredSlot;
		}
		
		return findWindChargeInHotbar();
	}
	
	private float applyLookDown()
	{
		float oldPitch = MC.player.getPitch();
		float downwardPitch = 90F;
		MC.player.setPitch(downwardPitch);
		MC.player.networkHandler.sendPacket(
			new PlayerMoveC2SPacket.LookAndOnGround(MC.player.getYaw(),
				downwardPitch, MC.player.isOnGround(),
				MC.player.horizontalCollision));
		return oldPitch;
	}
	
	private void restorePitchImmediate(float pitch)
	{
		if(MC.player == null)
			return;
		
		MC.player.setPitch(pitch);
		MC.player.networkHandler.sendPacket(
			new PlayerMoveC2SPacket.LookAndOnGround(MC.player.getYaw(), pitch,
				MC.player.isOnGround(), MC.player.horizontalCollision));
		restorePitch = false;
		restorePitchAt = 0;
	}
	
	private void clearPending()
	{
		awaitingLaunch = false;
		pendingSlot = -1;
		pendingSilent = false;
		pendingLookDown = false;
		launchDeadline = 0;
		jumpScheduled = false;
		jumpStartTime = 0;
	}
	
	private boolean throwWindChargeSilently(int slot)
	{
		int currentSlot = MC.player.getInventory().getSelectedSlot();
		MC.player.getInventory().setSelectedSlot(slot);
		boolean success = useSelectedItem();
		MC.player.getInventory().setSelectedSlot(currentSlot);
		return success;
	}
	
	private boolean throwWindChargeNormally(int slot, long now)
	{
		originalSlot = MC.player.getInventory().getSelectedSlot();
		MC.player.getInventory().setSelectedSlot(slot);
		
		boolean success = useSelectedItem();
		
		if(success)
		{
			needsSlotRestore = true;
			switchBackTime = now;
			return true;
		}
		
		MC.player.getInventory().setSelectedSlot(originalSlot);
		originalSlot = -1;
		return false;
	}
	
	private boolean useSelectedItem()
	{
		if(MC.player == null || MC.interactionManager == null)
			return false;
		
		ItemStack stack = MC.player.getMainHandStack();
		if(stack.isEmpty())
			return false;
		
		ActionResult result =
			MC.interactionManager.interactItem(MC.player, Hand.MAIN_HAND);
		
		if(result.isAccepted())
			MC.player.swingHand(Hand.MAIN_HAND);
		
		return result.isAccepted();
	}
	
	private int findWindChargeInHotbar()
	{
		for(int i = 0; i < 9; i++)
		{
			ItemStack stack = MC.player.getInventory().getStack(i);
			if(stack.isOf(Items.WIND_CHARGE))
				return i;
		}
		
		return -1;
	}
	
	private InputUtil.Key getBoundKey()
	{
		try
		{
			return InputUtil.fromTranslationKey(keybind.getValue());
			
		}catch(IllegalArgumentException e)
		{
			return null;
		}
	}
	
	private long getSwitchDelay()
	{
		return Math.max(0L, Math.round(switchDelayMs.getValue()));
	}
	
	private long getThrowDelay()
	{
		return Math.max(0L, Math.round(throwDelayMs.getValue()));
	}
	
	private long getJumpDelay()
	{
		return Math.max(0L, Math.round(jumpDelayMs.getValue()));
	}
	
	private boolean isValidKey(String translationKey)
	{
		try
		{
			return InputUtil.fromTranslationKey(translationKey) != null;
			
		}catch(IllegalArgumentException e)
		{
			return false;
		}
	}
	
	private void resetState()
	{
		clearPending();
		
		if(restorePitch && MC.player != null)
			restorePitchImmediate(savedPitch);
		
		keyPressed = false;
		originalSlot = -1;
		needsSlotRestore = false;
		firstThrow = true;
		lastThrowTime = 0;
		switchBackTime = 0;
		restorePitch = false;
		restorePitchAt = 0;
		savedPitch = 0F;
	}
}
