/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.hacks;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"mace dmg", "MaceDamage", "mace damage"})
public final class MaceDmgHack extends Hack
	implements PlayerAttacksEntityListener
{
	private static final double DEFAULT_HEIGHT = Math.sqrt(500);
	private static final double MIN_FALL = 1.6;
	private static final double SCAN_STEP = 0.25;
	
	private final SliderSetting height = new SliderSetting("Height",
		"How high to fake before slamming. Height determines the damage boost.",
		DEFAULT_HEIGHT, 1.6, 50, 0.1, ValueDisplay.DECIMAL);
	
	private final CheckboxSetting caveMode = new CheckboxSetting("Cave mode",
		"Scans for an air gap above you to fall through, so smashes work under"
			+ " a ceiling. Fully sealed spaces still can't be smashed.",
		true);
	
	private long lastSmashTick = -1;
	
	public MaceDmgHack()
	{
		super("MaceDMG");
		setCategory(Category.COMBAT);
		addSetting(height);
		addSetting(caveMode);
	}
	
	@Override
	protected void onEnable()
	{
		EVENTS.add(PlayerAttacksEntityListener.class, this);
	}
	
	@Override
	protected void onDisable()
	{
		EVENTS.remove(PlayerAttacksEntityListener.class, this);
	}
	
	@Override
	public void onPlayerAttacksEntity(Entity target)
	{
		doSmash();
	}
	
	public void doSmash()
	{
		if(!isEnabled() || MC.player == null || MC.player.connection == null)
			return;
		if(!MC.player.getMainHandItem().is(Items.MACE))
			return;
		
		long now = MC.player.tickCount;
		if(now == lastSmashTick)
			return;
		lastSmashTick = now;
		// See ServerGamePacketListenerImpl.handleMovePlayer()
		// for why it's using these numbers.
		// Also, let me know if you find a way to bypass that check.
		for(int i = 0; i < 4; i++)
			sendFakeY(0);
		sendFakeY(findFallOffset());
		sendFakeY(0);
	}
	
	private double findFallOffset()
	{
		double cap = height.getValue();
		if(!caveMode.isChecked() || MC.level == null)
			return cap;
		
		for(double off = cap; off >= MIN_FALL; off -= SCAN_STEP)
		{
			AABB box = MC.player.getBoundingBox().move(0, off, 0);
			if(MC.level.noCollision(MC.player, box))
				return off;
		}
		
		return cap;
	}
	
	private void sendFakeY(double offset)
	{
		MC.player.connection
			.send(new Pos(MC.player.getX(), MC.player.getY() + offset,
				MC.player.getZ(), false, MC.player.horizontalCollision));
	}
}
