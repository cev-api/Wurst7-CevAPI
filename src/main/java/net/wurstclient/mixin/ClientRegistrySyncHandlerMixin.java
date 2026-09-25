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

import net.fabricmc.fabric.impl.registry.sync.packet.RegistrySyncPayload;
import net.wurstclient.util.RegistrySyncBypass;

/**
 * Runs before Fabric compares the server's registry data with the local
 * registries, which is the point where a client without the server's mods
 * normally gets kicked. {@link RegistrySyncBypass} rewrites that data instead,
 * but only if the player turned the Wurst Option on.
 */
@Mixin(
	targets = "net.fabricmc.fabric.impl.client.registry.sync.ClientRegistrySyncHandler",
	remap = false)
public abstract class ClientRegistrySyncHandlerMixin
{
	@Inject(
		method = "checkRemoteRemap(Lnet/fabricmc/fabric/impl/registry/sync/packet/RegistrySyncPayload;)V",
		at = @At("HEAD"))
	private static void onCheckRemoteRemap(RegistrySyncPayload payload,
		CallbackInfo ci)
	{
		RegistrySyncBypass.prepareIncomingMap(payload.registryMap());
	}
}
