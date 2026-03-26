/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.fabricmc.fabric.api.client.keybinding.v1;

import net.minecraft.client.KeyMapping;

public final class KeyBindingHelper
{
	private KeyBindingHelper()
	{}
	
	public static KeyMapping registerKeyBinding(KeyMapping keyBinding)
	{
		return keyBinding;
	}
}
