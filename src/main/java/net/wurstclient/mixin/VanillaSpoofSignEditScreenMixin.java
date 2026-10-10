/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen;
import net.minecraft.network.chat.Component;
import net.wurstclient.WurstClient;

/** Protect server-provided text before it becomes editable sign strings. */
@Mixin(AbstractSignEditScreen.class)
public abstract class VanillaSpoofSignEditScreenMixin
{
	@WrapOperation(
		method = "<init>(Lnet/minecraft/world/level/block/entity/SignBlockEntity;Lnet/minecraft/world/level/block/entity/SignTextSlot;ZLnet/minecraft/network/chat/Component;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/chat/Component;getString()Ljava/lang/String;"))
	private String wurst$vanillaSignFeedback(Component line,
		Operation<String> original)
	{
		var spoof = WurstClient.INSTANCE.getOtfs().vanillaSpoofOtf;
		return spoof.isTextFeedbackProtectionEnabled()
			? spoof.getSignFeedbackLine(line) : original.call(line);
	}
}
