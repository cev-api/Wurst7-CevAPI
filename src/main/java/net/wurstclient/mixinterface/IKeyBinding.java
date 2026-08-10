/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixinterface;

import net.minecraft.client.KeyMapping;

/**
 * Backwards-compatible keybinding mixinterface.
 *
 * Historically, Wurst used an {@code IKeyBinding} mixinterface. Some hacks and
 * modules still refer to it. Newer code also uses {@code IKeyMapping}.
 */
public interface IKeyBinding
{
	/**
	 * @deprecated Use {@link #isActuallyDown()} instead.
	 */
	@Deprecated
	boolean wurst_isActuallyDown();
	
	/**
	 * @deprecated Use {@link #resetPressedState()} instead.
	 */
	@Deprecated
	void wurst_resetPressedState();
	
	/**
	 * @deprecated Use {@link #simulatePress(boolean)} instead.
	 */
	@Deprecated
	void wurst_simulatePress(boolean pressed);
	
	/*
	 * Returns whether the user is actually pressing this key on their keyboard
	 * or mouse.
	 */
	default boolean isActuallyDown()
	{
		return wurst_isActuallyDown();
	}
	
	/**
	 * Resets the pressed state to whether or not the user is actually pressing
	 * this key on their keyboard.
	 */
	default void resetPressedState()
	{
		wurst_resetPressedState();
	}
	
	/**
	 * Simulates a key press/release for this binding.
	 */
	default void simulatePress(boolean pressed)
	{
		wurst_simulatePress(pressed);
	}
	
	/**
	 * Compatibility layer for older call sites.
	 */
	default void setPressed(boolean pressed)
	{
		asVanilla().setDown(pressed);
	}
	
	/**
	 * Compatibility layer for older call sites.
	 */
	default boolean isPressed()
	{
		return asVanilla().isDown();
	}
	
	default KeyMapping asVanilla()
	{
		return (KeyMapping)(Object)this;
	}
	
	/**
	 * Returns the given KeyMapping object as an IKeyBinding.
	 */
	static IKeyBinding get(KeyMapping kb)
	{
		return (IKeyBinding)(Object)kb;
	}
}
