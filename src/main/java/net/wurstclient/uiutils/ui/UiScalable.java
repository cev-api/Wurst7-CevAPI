/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.uiutils.ui;

/**
 * Implemented by UI-utils widgets that draw themselves in design units.
 * <p>
 * The modern screens scale the whole canvas at once, but the overlay panels
 * place
 * widgets in raw GUI pixels, so they hand each widget the scale to draw itself
 * with. Keeping one scale for every widget is what stops labels, fields and
 * buttons from ending up at different sizes.
 */
public interface UiScalable
{
	void applyUiScale(float scale);
}
