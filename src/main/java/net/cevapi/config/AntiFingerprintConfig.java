/*
 * Copyright (c) 2014-2025 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.cevapi.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.wurstclient.settings.CheckboxSetting;
import net.wurstclient.settings.EnumSetting;
import net.wurstclient.settings.Setting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.settings.SliderSetting.ValueDisplay;
import net.wurstclient.settings.TextFieldSetting;

/**
 * Configuration model for the Anti-Fingerprint protections.
 */
public final class AntiFingerprintConfig
{
	public static final AntiFingerprintConfig INSTANCE =
		new AntiFingerprintConfig();
	
	private static final int THRESHOLD_MIN = 2;
	private static final int THRESHOLD_MAX = 10;
	private static final int WINDOW_MIN = 250;
	private static final int WINDOW_MAX = 15000;
	
	private final EnumSetting<Policy> policy =
		new EnumSetting<>("Policy", Policy.values(), Policy.OBSERVE);
	
	private final EnumSetting<ToastVerbosity> toastVerbosity =
		new EnumSetting<>("Toast verbosity", ToastVerbosity.values(),
			ToastVerbosity.IMPORTANT_ONLY);
	
	private final CheckboxSetting auditLog =
		new CheckboxSetting("Audit log", false);
	
	private final CheckboxSetting purgeCache =
		new CheckboxSetting("Clear cache before download",
			"Deletes the local server resource-pack cache right before every"
				+ " request, forcing a fresh download each time.",
			false);
	
	private final CheckboxSetting isolateCache = new CheckboxSetting(
		"Isolate cached packs",
		"Stores server resource packs inside a per-session directory so cached copies can't be reused for fingerprinting.",
		false);
	
	private final CheckboxSetting extractSandbox = new CheckboxSetting(
		"Extract sandbox copy",
		"After sandboxing a pack, automatically extract it using Minecraft's resource-pack loader so you can inspect the contents.",
		false);
	
	private final SliderSetting fingerprintThreshold = new SliderSetting(
		"Fingerprint threshold",
		"Number of packs within the window before a fingerprint attempt is assumed.",
		3, THRESHOLD_MIN, THRESHOLD_MAX, 1, ValueDisplay.INTEGER);
	
	private final SliderSetting fingerprintWindowMs =
		new SliderSetting("Fingerprint window (ms)",
			"Time window used to detect rapid pack requests.", 1500, WINDOW_MIN,
			WINDOW_MAX, 250, ValueDisplay.INTEGER);
	
	private final TextFieldSetting whitelistedHosts = new TextFieldSetting(
		"Whitelisted hosts",
		"Comma separated list of hostnames or host:port entries that should bypass the protector.",
		"", AntiFingerprintConfig::isValidHostList);
	
	private AntiFingerprintConfig()
	{
		
	}
	
	public EnumSetting<Policy> getPolicySetting()
	{
		return policy;
	}
	
	public EnumSetting<ToastVerbosity> getToastVerbositySetting()
	{
		return toastVerbosity;
	}
	
	public CheckboxSetting getAuditLogSetting()
	{
		return auditLog;
	}
	
	public CheckboxSetting getPurgeCacheSetting()
	{
		return purgeCache;
	}
	
	public CheckboxSetting getIsolateCacheSetting()
	{
		return isolateCache;
	}
	
	public CheckboxSetting getExtractSandboxSetting()
	{
		return extractSandbox;
	}
	
	public SliderSetting getFingerprintThresholdSetting()
	{
		return fingerprintThreshold;
	}
	
	public SliderSetting getFingerprintWindowSetting()
	{
		return fingerprintWindowMs;
	}
	
	public TextFieldSetting getWhitelistSetting()
	{
		return whitelistedHosts;
	}
	
	public Policy getPolicy()
	{
		return policy.getSelected();
	}
	
	public ToastVerbosity getToastVerbosity()
	{
		return toastVerbosity.getSelected();
	}
	
	public boolean isAuditLogEnabled()
	{
		return auditLog.isChecked();
	}
	
	public boolean shouldClearCache()
	{
		return purgeCache.isChecked();
	}
	
	public boolean shouldIsolateCache()
	{
		return isolateCache.isChecked();
	}
	
	public boolean shouldExtractSandbox()
	{
		return extractSandbox.isChecked();
	}
	
	public int getFingerprintThreshold()
	{
		return Math.max(2, fingerprintThreshold.getValueI());
	}
	
	public long getFingerprintWindowMs()
	{
		return Math.max(250L, fingerprintWindowMs.getValueI());
	}
	
	public int getThresholdMin()
	{
		return THRESHOLD_MIN;
	}
	
	public int getThresholdMax()
	{
		return THRESHOLD_MAX;
	}
	
	public int getWindowMin()
	{
		return WINDOW_MIN;
	}
	
	public int getWindowMax()
	{
		return WINDOW_MAX;
	}
	
	public String getWhitelistRaw()
	{
		return whitelistedHosts.getValue();
	}
	
	public void setWhitelistRaw(String value)
	{
		whitelistedHosts.setValue(value);
	}
	
	public Set<String> getWhitelistedHosts()
	{
		String value = whitelistedHosts.getValue();
		if(value.isBlank())
			return Collections.emptySet();
		
		String[] items = value.split(",");
		LinkedHashSet<String> set = new LinkedHashSet<>();
		for(String item : items)
		{
			String trimmed = item.trim().toLowerCase(Locale.ROOT);
			if(trimmed.isEmpty())
				continue;
			set.add(trimmed);
		}
		
		return Collections.unmodifiableSet(set);
	}
	
	public List<Setting> getAllSettings()
	{
		return Arrays.asList(policy, toastVerbosity, auditLog, purgeCache,
			isolateCache, extractSandbox, fingerprintThreshold,
			fingerprintWindowMs, whitelistedHosts);
	}
	
	private static boolean isValidHostList(String value)
	{
		if(value == null)
			return false;
		
		if(value.isEmpty())
			return true;
		
		String[] items = value.split(",");
		for(String item : items)
		{
			String trimmed = item.trim();
			if(trimmed.isEmpty())
				return false;
			
			if(trimmed.length() > 128)
				return false;
			
			if(!trimmed.matches("^[A-Za-z0-9\\-._:\\[\\]]+$"))
				return false;
		}
		
		return true;
	}
	
	public static enum Policy
	{
		OBSERVE("Observe"),
		BLOCK_LOCAL("Block Local"),
		SANDBOX_ALL("Sandbox All"),
		BLOCK_ALL("Block All");
		
		private final String displayName;
		
		private Policy(String displayName)
		{
			this.displayName = displayName;
		}
		
		@Override
		public String toString()
		{
			return displayName;
		}
	}
	
	public static enum ToastVerbosity
	{
		SILENT("Silent")
		{
			@Override
			public boolean allows(ToastLevel level)
			{
				return false;
			}
		},
		IMPORTANT_ONLY("Important")
		{
			@Override
			public boolean allows(ToastLevel level)
			{
				return level != ToastLevel.INFO;
			}
		},
		VERBOSE("Verbose")
		{
			@Override
			public boolean allows(ToastLevel level)
			{
				return true;
			}
		};
		
		private final String displayName;
		
		private ToastVerbosity(String displayName)
		{
			this.displayName = displayName;
		}
		
		public boolean allows(ToastLevel level)
		{
			return true;
		}
		
		@Override
		public String toString()
		{
			return displayName;
		}
	}
	
	public static enum ToastLevel
	{
		INFO,
		WARN,
		ERROR
	}
}
