/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixinterface;

import net.minecraft.client.KeyMapping;

public interface IKeyMapping extends IKeyBinding
{
	default KeyMapping asVanilla()
	{
		return (KeyMapping)(Object)this;
	}
	
	/**
	 * Returns the given KeyMapping object as an IKeyMapping, allowing you to
	 * access the resetPressedState() method.
	 */
	public static IKeyMapping get(KeyMapping kb)
	{
		return (IKeyMapping)(Object)kb;
	}
	
	default void setDown(boolean pressed)
	{
		asVanilla().setDown(pressed);
	}
	
	default boolean isDown()
	{
		return asVanilla().isDown();
	}
}
