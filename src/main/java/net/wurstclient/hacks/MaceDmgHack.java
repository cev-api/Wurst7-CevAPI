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
import net.minecraft.world.phys.HitResult;
import net.wurstclient.Category;
import net.wurstclient.SearchTags;
import net.wurstclient.events.PlayerAttacksEntityListener;
import net.wurstclient.hack.Hack;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;

@SearchTags({"mace dmg", "MaceDamage", "mace damage"})
public final class MaceDmgHack extends Hack
	implements PlayerAttacksEntityListener
{
	private static final double DEFAULT_HEIGHT = Math.sqrt(500);
	
	private final SliderSetting height = new SliderSetting("Height",
		"How high to fake before slamming. Height determines the damage boost.",
		DEFAULT_HEIGHT, 1.6, 50, 0.1, ValueDisplay.DECIMAL);
	
	public MaceDmgHack()
	{
		super("MaceDMG");
		setCategory(Category.COMBAT);
		addSetting(height);
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
		if(MC.hitResult == null
			|| MC.hitResult.getType() != HitResult.Type.ENTITY)
			return;
		
		if(!MC.player.getMainHandItem().is(Items.MACE))
			return;
			
		// See ServerGamePacketListenerImpl.handleMovePlayer()
		// for why it's using these numbers.
		// Also, let me know if you find a way to bypass that check.
		for(int i = 0; i < 4; i++)
			sendFakeY(0);
		sendFakeY(height.getValue());
		sendFakeY(0);
	}
	
	private void sendFakeY(double offset)
	{
		MC.player.connection
			.send(new Pos(MC.player.getX(), MC.player.getY() + offset,
				MC.player.getZ(), false, MC.player.horizontalCollision));
	}
}
