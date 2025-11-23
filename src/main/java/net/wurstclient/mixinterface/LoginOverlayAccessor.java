/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.mixinterface;

import java.util.List;

import net.minecraft.client.gui.Font;
import net.minecraft.util.FormattedCharSequence;

public interface LoginOverlayAccessor
{
	boolean isLoginOverlayVisible();
	
	List<FormattedCharSequence> getOverlayLines();
	
	int getOverlayWidth();
	
	int getOverlayHeight();
	
	int getOverlayX();
	
	int getOverlayY();
	
	void layoutLoginOverlay(Font font, int width, int height);
}
