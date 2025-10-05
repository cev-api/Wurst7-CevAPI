/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hack;

import java.util.Objects;

import net.wurstclient.Category;
import net.wurstclient.Feature;
import net.wurstclient.hacks.ClickGuiHack;
import net.wurstclient.hacks.NavigatorHack;
import net.wurstclient.hacks.TooManyHaxHack;

public abstract class Hack extends Feature
{
	private final String name;
	private final String description;
	private Category category;
	
	private boolean enabled;
	private final boolean stateSaved =
		!getClass().isAnnotationPresent(DontSaveState.class);
	
	// favorites
	private boolean favorite = false;
	
	public Hack(String name)
	{
		this.name = Objects.requireNonNull(name);
		description = "description.wurst.hack." + name.toLowerCase();
		addPossibleKeybind(name, "Toggle " + name);
	}
	
	@Override
	public final String getName()
	{
		return name;
	}
	
	public String getRenderName()
	{
		return name;
	}
	
	/**
	 * Optional: A representative ARGB color to display this hack in the
	 * HackList.
	 * Default implementation searches for a ColorSetting among this hack's
	 * settings
	 * and returns its color with the given alpha, or -1 if not available.
	 *
	 * @param alpha
	 *            0-255 alpha byte used for composition in the HUD
	 * @return ARGB int color or -1 if no color is defined
	 */
	public int getHackListColorI(int alpha)
	{
		for(net.wurstclient.settings.Setting s : getSettings().values())
			if(s instanceof net.wurstclient.settings.ColorSetting cs)
				return cs.getColorI(alpha);
		return -1;
	}
	
	@Override
	public final String getDescription()
	{
		return WURST.translate(description);
	}
	
	public final String getDescriptionKey()
	{
		return description;
	}
	
	@Override
	public final Category getCategory()
	{
		return category;
	}
	
	protected final void setCategory(Category category)
	{
		this.category = category;
	}
	
	@Override
	public final boolean isEnabled()
	{
		return enabled;
	}
	
	public final void setEnabled(boolean enabled)
	{
		if(this.enabled == enabled)
			return;
		
		TooManyHaxHack tooManyHax = WURST.getHax().tooManyHaxHack;
		if(enabled && tooManyHax.isEnabled() && tooManyHax.isBlocked(this))
			return;
		
		this.enabled = enabled;
		
		if(!(this instanceof NavigatorHack || this instanceof ClickGuiHack))
			WURST.getHud().getHackList().updateState(this);
		
		if(enabled)
			onEnable();
		else
			onDisable();
		
		if(stateSaved)
			WURST.getHax().saveEnabledHax();
	}
	
	@Override
	public final String getPrimaryAction()
	{
		return enabled ? "Disable" : "Enable";
	}
	
	@Override
	public final void doPrimaryAction()
	{
		setEnabled(!enabled);
	}
	
	public final boolean isStateSaved()
	{
		return stateSaved;
	}
	
	// favorites
	public final boolean isFavorite()
	{
		return favorite;
	}
	
	public final void setFavorite(boolean fav)
	{
		if(this.favorite == fav)
			return;
		this.favorite = fav;
		// allow HackList to persist favorites when needed
		if(WURST != null && WURST.getHax() != null)
			WURST.getHax().saveFavoriteHax();
		
		// update ClickGui immediately if present
		if(WURST != null && WURST.getGui() != null)
		{
			try
			{
				if(fav)
					WURST.getGui().addFavoriteFeature(this);
				else
					WURST.getGui().removeFavoriteFeature(this);
			}catch(Exception e)
			{
				// ignore GUI update failures
			}
		}
	}
	
	protected void onEnable()
	{
		
	}
	
	protected void onDisable()
	{
		
	}
}
