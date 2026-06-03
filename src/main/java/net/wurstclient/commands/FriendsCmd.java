/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.commands;

import java.awt.Color;
import java.util.ArrayList;

import net.wurstclient.Category;
import net.wurstclient.command.CmdError;
import net.wurstclient.command.CmdException;
import net.wurstclient.command.CmdSyntaxError;
import net.wurstclient.command.Command;
import net.wurstclient.settings.ButtonSetting;
import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.ColorSetting;
import net.wurstclient.settings.FriendListSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.TextFieldSetting;
import net.wurstclient.util.ChatUtils;
import net.wurstclient.util.MathUtils;

public class FriendsCmd extends Command
{
	private static final int FRIENDS_PER_PAGE = 8;
	
	private final CheckboxSetting middleClickFriends =
		new CheckboxSetting("Middle click friends",
			"Add/remove friends by clicking them with the middle mouse button.",
			true);
	private final CheckboxSetting friendJoinAlerts =
		new CheckboxSetting("Friend join alerts",
			"Alerts in chat when a friend joins your current server,\n"
				+ "or when you join a server that already has friends online.",
			true);
	private final CheckboxSetting autoPing = new CheckboxSetting("Auto ping",
		"Periodically whispers your encrypted coordinates to online friends on"
			+ " the same server.",
		false);
	private final TextFieldSetting pingPassword =
		new TextFieldSetting("Ping password",
			"Shared password used to encrypt and decrypt friend pings.", "");
	private final SliderSetting pingInterval = new SliderSetting(
		"Ping interval",
		"How often automatic friend pings are sent while Auto ping is enabled.",
		30, 1, 120, 1, SliderSetting.ValueDisplay.INTEGER.withSuffix("s"));
	private final CheckboxSetting twoWayCommunication = new CheckboxSetting(
		"Two-way communication",
		"When enabled, replying to a received friend ping sends one encrypted"
			+ " ping back automatically. Reply pings do not auto-chain.",
		false);
	private final CheckboxSetting showDistanceLabels = new CheckboxSetting(
		"Show distance labels",
		"Shows the friend name and distance above remote friend ping markers.",
		true);
	private final SliderSetting labelScale = new SliderSetting("Label scale",
		"Scale of the label drawn above remote friend ping markers.", 1.0, 0.5,
		3.0, 0.1, SliderSetting.ValueDisplay.DECIMAL);
	private final SliderSetting espTimeout = new SliderSetting("ESP timeout",
		"How long PlayerESP remembers a friend's last pinged position.", 10, 1,
		120, 1, SliderSetting.ValueDisplay.INTEGER.withSuffix("s"));
	private final SliderSetting pingTracerThickness =
		new SliderSetting("Ping tracer thickness",
			"Line thickness for remote friend ping tracers.", 2.0, 0.5, 10.0,
			0.1, SliderSetting.ValueDisplay.DECIMAL.withSuffix("px"));
	private final ColorSetting pingBoxColor = new ColorSetting("Ping box color",
		"Color of the remote friend ping box.", new Color(0x40A0FF));
	private final ColorSetting pingTracerColor =
		new ColorSetting("Ping tracer color",
			"Color of the remote friend ping tracer and label.",
			new Color(0x40A0FF));
	private final TextFieldSetting lastPingStatus = new TextFieldSetting(
		"Last ping",
		"Shows the latest friend ping that was decrypted or sent. You can also"
			+ " use it as a quick sanity check when auto ping chat is suppressed.",
		"No ping yet.");
	private final FriendListSetting friendList = new FriendListSetting(
		"Friend list",
		"Opens a menu where you can add friends by name or remove selected friends.");
	private final ButtonSetting sendPingNow =
		new ButtonSetting("Send ping now", this::sendPingNow);
	
	public FriendsCmd()
	{
		super("friends", "Manages your friends list.", ".friends add <name>",
			".friends remove <name>", ".friends remove-all",
			".friends list [<page>]", ".friends ping", ".friends ping <name>",
			".friends waypoint <friend> <x> <y> <z> <dim>");
		setCategory(Category.OTHER);
		
		addSetting(middleClickFriends);
		addSetting(friendJoinAlerts);
		addSetting(autoPing);
		addSetting(pingPassword);
		addSetting(pingInterval);
		addSetting(twoWayCommunication);
		addSetting(showDistanceLabels);
		addSetting(labelScale);
		addSetting(espTimeout);
		addSetting(pingTracerThickness);
		addSetting(pingBoxColor);
		addSetting(pingTracerColor);
		addSetting(lastPingStatus);
		addSetting(friendList);
		addSetting(sendPingNow);
		addPossibleKeybind(".friends ping",
			"Sends an encrypted friend ping to online friends.");
	}
	
	@Override
	public void call(String[] args) throws CmdException
	{
		if(args.length < 1)
			throw new CmdSyntaxError();
		
		switch(args[0].toLowerCase())
		{
			case "add":
			add(args);
			break;
			
			case "remove":
			remove(args);
			break;
			
			case "remove-all":
			removeAll(args);
			break;
			
			case "list":
			list(args);
			break;
			
			case "ping":
			ping(args);
			break;
			
			case "waypoint":
			waypoint(args);
			break;
			
			default:
			throw new CmdSyntaxError();
		}
	}
	
	private void add(String[] args) throws CmdException
	{
		if(args.length != 2)
			throw new CmdSyntaxError();
		
		String name = args[1];
		if(WURST.getFriends().contains(name))
			throw new CmdError(
				"\"" + name + "\" is already in your friends list.");
		
		WURST.getFriends().addAndSave(name);
		ChatUtils.message("Added friend \"" + name + "\".");
	}
	
	private void remove(String[] args) throws CmdException
	{
		if(args.length != 2)
			throw new CmdSyntaxError();
		
		String name = args[1];
		if(!WURST.getFriends().contains(name))
			throw new CmdError("\"" + name + "\" is not in your friends list.");
		
		WURST.getFriends().removeAndSave(name);
		ChatUtils.message("Removed friend \"" + name + "\".");
	}
	
	private void removeAll(String[] args) throws CmdException
	{
		if(args.length > 1)
			throw new CmdSyntaxError();
		
		WURST.getFriends().removeAllAndSave();
		ChatUtils.message("All friends removed. Oof.");
	}
	
	private void list(String[] args) throws CmdException
	{
		if(args.length > 2)
			throw new CmdSyntaxError();
		
		ArrayList<String> friends = WURST.getFriends().toList();
		int page = parsePage(args);
		int pages = (int)Math.ceil(friends.size() / (double)FRIENDS_PER_PAGE);
		pages = Math.max(pages, 1);
		
		if(page > pages || page < 1)
			throw new CmdSyntaxError("Invalid page: " + page);
		
		ChatUtils.message("Current friends: " + friends.size());
		
		int start = (page - 1) * FRIENDS_PER_PAGE;
		int end = Math.min(page * FRIENDS_PER_PAGE, friends.size());
		
		ChatUtils.message("Friends list (page " + page + "/" + pages + ")");
		for(int i = start; i < end; i++)
			ChatUtils.message(friends.get(i).toString());
	}
	
	private int parsePage(String[] args) throws CmdSyntaxError
	{
		if(args.length < 2)
			return 1;
		
		if(!MathUtils.isInteger(args[1]))
			throw new CmdSyntaxError("Not a number: " + args[1]);
		
		return Integer.parseInt(args[1]);
	}
	
	private void ping(String[] args) throws CmdException
	{
		String target = args.length == 2 ? args[1] : null;
		WURST.getFriends().sendManualPing(target);
	}
	
	private void sendPingNow()
	{
		if(WURST.getFriends() == null)
			return;
		
		WURST.getFriends().sendManualPing(null);
	}
	
	private void waypoint(String[] args) throws CmdException
	{
		if(args.length != 6)
			throw new CmdSyntaxError();
		
		String friendName = args[1];
		int x = parseCoord(args[2], "x");
		int y = parseCoord(args[3], "y");
		int z = parseCoord(args[4], "z");
		WURST.getFriends().addWaypointFromPing(friendName, args[5], x, y, z);
	}
	
	private int parseCoord(String value, String axis) throws CmdError
	{
		try
		{
			return Integer.parseInt(value);
			
		}catch(NumberFormatException e)
		{
			throw new CmdError("Invalid " + axis + " coordinate: " + value);
		}
	}
	
	public CheckboxSetting getMiddleClickFriends()
	{
		return middleClickFriends;
	}
	
	public CheckboxSetting getFriendJoinAlerts()
	{
		return friendJoinAlerts;
	}
	
	public CheckboxSetting getAutoPing()
	{
		return autoPing;
	}
	
	public TextFieldSetting getPingPassword()
	{
		return pingPassword;
	}
	
	public SliderSetting getPingInterval()
	{
		return pingInterval;
	}
	
	public CheckboxSetting getTwoWayCommunication()
	{
		return twoWayCommunication;
	}
	
	public CheckboxSetting getShowDistanceLabels()
	{
		return showDistanceLabels;
	}
	
	public SliderSetting getLabelScale()
	{
		return labelScale;
	}
	
	public SliderSetting getEspTimeout()
	{
		return espTimeout;
	}
	
	public SliderSetting getPingTracerThickness()
	{
		return pingTracerThickness;
	}
	
	public ColorSetting getPingBoxColor()
	{
		return pingBoxColor;
	}
	
	public ColorSetting getPingTracerColor()
	{
		return pingTracerColor;
	}
	
	public TextFieldSetting getLastPingStatus()
	{
		return lastPingStatus;
	}
}
