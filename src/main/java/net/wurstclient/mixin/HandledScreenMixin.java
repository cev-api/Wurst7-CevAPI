/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.AntiDropHack;

@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin
{
	@Shadow
	public abstract net.minecraft.screen.ScreenHandler getScreenHandler();
	
	@Inject(at = @At("HEAD"),
		method = "onMouseClick(Lnet/minecraft/screen/slot/Slot;IILnet/minecraft/screen/slot/SlotActionType;)V",
		cancellable = true)
	private void onMouseClick(Slot slot, int slotId, int button,
		SlotActionType actionType, CallbackInfo ci)
	{
		if(actionType != SlotActionType.THROW && slotId != -999)
			return;
		
		if(!WurstClient.INSTANCE.isEnabled())
			return;
		
		AntiDropHack antiDrop = WurstClient.INSTANCE.getHax().antiDropHack;
		if(!antiDrop.isEnabled())
			return;
		
		ItemStack stack = ItemStack.EMPTY;
		
		if(slotId == -999)
			stack = getScreenHandler().getCursorStack();
		else if(slot != null)
			stack = slot.getStack();
		
		if(antiDrop.shouldBlock(stack))
			ci.cancel();
	}
}
