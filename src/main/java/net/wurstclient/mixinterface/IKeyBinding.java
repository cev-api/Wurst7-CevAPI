/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixinterface;

import net.minecraft.client.KeyMapping;

public interface IKeyBinding
{
	static IKeyBinding get(KeyMapping key)
	{
		return (IKeyBinding)key;
	}
	
	default KeyMapping asVanilla()
	{
		return (KeyMapping)this;
	}
	
	default void setPressed(boolean value)
	{
		asVanilla().setDown(value);
	}
	
	default boolean isPressed()
	{
		return asVanilla().isDown();
	}
	
	default void setDown(boolean value)
	{
		setPressed(value);
	}
	
	default boolean isDown()
	{
		return isPressed();
	}
	
	void resetPressedState();
	
	void simulatePress(boolean pressed);
}
