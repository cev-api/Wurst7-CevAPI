/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import net.wurstclient.mixinterface.IServerDataExt;

@Mixin(ServerData.class)
public class ServerDataMixin implements IServerDataExt
{
	@Unique
	private boolean wurst$bypassMojangBlock;
	
	@Inject(method = "write()Lnet/minecraft/nbt/CompoundTag;",
		at = @At("RETURN"))
	private void writeBypass(CallbackInfoReturnable<CompoundTag> cir)
	{
		CompoundTag tag = cir.getReturnValue();
		tag.putBoolean("wurstBypassMojangBlock", wurst$bypassMojangBlock);
	}
	
	@Inject(
		method = "read(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/client/multiplayer/ServerData;",
		at = @At("RETURN"))
	private static void readBypass(CompoundTag tag,
		CallbackInfoReturnable<ServerData> cir)
	{
		ServerData server = cir.getReturnValue();
		if(server != null)
			((IServerDataExt)(Object)server).wurst$setBypassMojangBlock(
				tag.getBooleanOr("wurstBypassMojangBlock", false));
	}
	
	@Inject(method = "copyFrom(Lnet/minecraft/client/multiplayer/ServerData;)V",
		at = @At("TAIL"))
	private void copyBypass(ServerData source, CallbackInfo ci)
	{
		wurst$bypassMojangBlock =
			((IServerDataExt)(Object)source).wurst$getBypassMojangBlock();
	}
	
	@Override
	public boolean wurst$getBypassMojangBlock()
	{
		return wurst$bypassMojangBlock;
	}
	
	@Override
	public void wurst$setBypassMojangBlock(boolean bypass)
	{
		wurst$bypassMojangBlock = bypass;
	}
}
