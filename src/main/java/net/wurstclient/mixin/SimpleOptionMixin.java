/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import java.util.Objects;
import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.wurstclient.WurstClient;
import net.wurstclient.mixinterface.ISimpleOption;

@Mixin(OptionInstance.class)
public class SimpleOptionMixin<T> implements ISimpleOption<T>
{
	@Shadow
	T value;
	
	@Shadow
	@Final
	private Consumer<T> onValueUpdate;
	
	@Override
	public void forceSetValue(T newValue)
	{
		if(!Minecraft.getInstance().isRunning())
		{
			value = newValue;
			return;
		}
		
		if(!Objects.equals(value, newValue))
		{
			value = newValue;
			onValueUpdate.accept(value);
		}
	}
	
	@SuppressWarnings("unchecked")
	@Inject(method = "get", at = @At("RETURN"), cancellable = true, require = 0)
	private void wurst$applyChatFontScale(CallbackInfoReturnable<T> cir)
	{
		T value = cir.getReturnValue();
		if(!(value instanceof Double d))
			return;
		
		Minecraft mc = Minecraft.getInstance();
		if(mc == null || mc.options == null
			|| (Object)this != mc.options.chatScale())
			return;
		
		double chatFontScale = 1;
		try
		{
			WurstClient wurst = WurstClient.INSTANCE;
			if(wurst != null && wurst.getHax() != null)
				chatFontScale =
					wurst.getHax().clientChatOverlayHack.getChatFontScale();
			
		}catch(RuntimeException ignored)
		{
			return;
		}
		
		if(Math.abs(chatFontScale - 1) < 1e-6)
			return;
		
		cir.setReturnValue((T)Double.valueOf(d * chatFontScale));
	}
}
