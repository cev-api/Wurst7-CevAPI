/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.wurstclient.WurstClient;
import net.wurstclient.hacks.AutoSignHack;

@Mixin(AbstractSignEditScreen.class)
public abstract class AbstractSignEditScreenMixin extends Screen
{
	@Shadow
	@Final
	private String[] messages;
	
	@Shadow
	@Final
	private SignBlockEntity sign;
	
	@Shadow
	@Final
	private boolean isFrontText;
	
	private boolean wurst$mirrorBothSides;
	
	private AbstractSignEditScreenMixin(WurstClient wurst, Component title)
	{
		super(title);
	}
	
	@Inject(method = "init()V", at = @At("HEAD"))
	private void onInit(CallbackInfo ci)
	{
		AutoSignHack autoSignHack = WurstClient.INSTANCE.getHax().autoSignHack;
		
		String[] autoSignText = autoSignHack.getSignText();
		if(autoSignText == null)
			return;
		
		wurst$mirrorBothSides = true;
		
		for(int i = 0; i < 4; i++)
			messages[i] = autoSignText[i];
		
		onDone();
	}
	
	@Inject(method = "onDone()V", at = @At("HEAD"))
	private void onFinishEditing(CallbackInfo ci)
	{
		WurstClient.INSTANCE.getHax().autoSignHack.setSignText(messages);
	}
	
	@Inject(method = "removed()V", at = @At("TAIL"))
	private void onRemoved(CallbackInfo ci)
	{
		if(!wurst$mirrorBothSides)
			return;
		
		Minecraft mc = Minecraft.getInstance();
		if(mc.getConnection() == null || sign == null)
			return;
		
		boolean otherSide = !isFrontText;
		mc.getConnection()
			.send(new ServerboundSignUpdatePacket(sign.getBlockPos(), otherSide,
				messages[0], messages[1], messages[2], messages[3]));
	}
	
	@Shadow
	private void onDone()
	{
		
	}
}
