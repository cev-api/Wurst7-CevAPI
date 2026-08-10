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
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import io.netty.channel.Channel;
import net.wurstclient.proxy.ProxyConnection;

/** Adds the selected proxy without replacing Minecraft's connection call. */
@Mixin(targets = "net.minecraft.network.Connection$1", remap = false)
public abstract class ConnectionInitializerMixin
{
	@Inject(method = "initChannel", at = @At("HEAD"))
	private void wurst$addSelectedProxy(Channel channel, CallbackInfo ci)
	{
		ProxyConnection.addSelectedProxyHandler(channel.pipeline());
	}
}
