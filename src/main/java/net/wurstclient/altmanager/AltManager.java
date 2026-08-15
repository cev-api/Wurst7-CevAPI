/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.altmanager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import net.wurstclient.WurstClient;
import net.wurstclient.proxy.SocksProxy;

public final class AltManager
{
	private final AltsFile altsFile;
	private final ArrayList<Alt> alts = new ArrayList<>();
	private int numPremium;
	private int numCracked;
	private boolean disconnectRandomAltReconnectEnabled = true;
	
	public AltManager(Path altsFile, Path encFolder)
	{
		this.altsFile = new AltsFile(altsFile, encFolder);
		this.altsFile.load(this);
	}
	
	public boolean contains(String name)
	{
		for(Alt alt : alts)
			if(alt.getName().equalsIgnoreCase(name))
				return true;
			
		return false;
	}
	
	public void add(Alt alt)
	{
		markAdded(alt);
		alts.add(alt);
		sortAlts();
		altsFile.save(this);
	}
	
	public void addAll(Collection<Alt> c)
	{
		addAll(c, true);
	}
	
	void addAll(Collection<Alt> c, boolean markAdded)
	{
		if(markAdded)
			c.forEach(this::markAdded);
		alts.addAll(c);
		sortAlts();
		altsFile.save(this);
	}
	
	private void markAdded(Alt alt)
	{
		if(alt.getLastValidatedAt() == 0)
			alt.markValidatedNow();
	}
	
	public void edit(Alt oldAlt, String newNameOrEmail, String newPassword)
	{
		String proxyStorageId = oldAlt.getProxyStorageId();
		remove(oldAlt);
		
		Alt replacement;
		if(newPassword.isEmpty())
			replacement = new CrackedAlt(newNameOrEmail, oldAlt.isFavorite());
		else
			replacement = new MojangAlt(newNameOrEmail, newPassword, "",
				oldAlt.isFavorite());
		
		replacement.setProxyStorageId(proxyStorageId);
		add(replacement);
	}
	
	public void updateTokenAltName(TokenAlt tokenAlt, String newName)
	{
		if(tokenAlt == null)
			return;
		
		for(Alt alt : alts)
		{
			if(alt != tokenAlt)
				continue;
			
			tokenAlt.setName(newName);
			sortAlts();
			altsFile.save(this);
			return;
		}
	}
	
	public SocksProxy getProxyAssociation(Alt alt)
	{
		if(alt == null)
			return null;
		
		return WurstClient.INSTANCE.getProxyManager()
			.findByStorageId(alt.getProxyStorageId());
	}
	
	public boolean hasProxyAssociation(Alt alt)
	{
		return alt != null && !alt.getProxyStorageId().isBlank();
	}
	
	public void setProxyAssociation(Alt alt, SocksProxy proxy)
	{
		if(alt == null || !alts.contains(alt))
			return;
		
		alt.setProxyStorageId(proxy == null ? "" : proxy.getStorageId());
		altsFile.save(this);
	}
	
	/**
	 * Saves a TokenAlt after its refresh token has been rotated by Microsoft.
	 */
	public void saveTokenAlt(TokenAlt tokenAlt)
	{
		if(alts.contains(tokenAlt))
			altsFile.save(this);
	}
	
	/**
	 * Logs the user in with this Alt. Also updates the counter for checked alts
	 * and saves the alt list file as necessary.
	 *
	 * @param alt
	 *            The Alt to login with.
	 * @throws LoginException
	 *             if the login attempt failed for any reason. The reason will
	 *             be explained in the Exception's message, which should be
	 *             displayed to the user.
	 */
	public void login(Alt alt) throws LoginException
	{
		boolean wasUnchecked = alt.isUncheckedPremium();
		SocksProxy associatedProxy = getProxyAssociation(alt);
		SocksProxy previousAuthProxy =
			MicrosoftLoginManager.getAuthenticationProxy();
		
		if(associatedProxy != null)
			MicrosoftLoginManager.setAuthenticationProxy(associatedProxy);
		
		try
		{
			alt.login();
			alt.markValidatedNow();
			
			if(wasUnchecked)
				numPremium++;
			
			WurstClient.INSTANCE.getProxyManager()
				.setAccountProxy(associatedProxy);
			
			if(!alt.isCracked())
				altsFile.save(this);
		}finally
		{
			MicrosoftLoginManager.setAuthenticationProxy(previousAuthProxy);
		}
	}
	
	public Alt loginRandomUntilSuccess() throws LoginException
	{
		ArrayList<Alt> shuffled = new ArrayList<>(alts);
		if(shuffled.isEmpty())
			throw new LoginException("No accounts available.");
		
		Collections.shuffle(shuffled);
		LoginException lastException = null;
		
		for(Alt alt : shuffled)
			try
			{
				login(alt);
				return alt;
				
			}catch(LoginException e)
			{
				lastException = e;
			}
		
		if(lastException != null)
			throw new LoginException("Random login failed for all accounts.",
				lastException);
		
		throw new LoginException("Random login failed for all accounts.");
	}
	
	/**
	 * Changes whether or not the Alt is marked as a favorite, then sorts the
	 * alt list accordingly and saves the changes.
	 */
	public void toggleFavorite(Alt alt)
	{
		alt.setFavorite(!alt.isFavorite());
		sortAlts();
		altsFile.save(this);
	}
	
	/**
	 * Removes the Alt at the given index. Faster than {@link #remove(Alt)}.
	 *
	 * @param index
	 *            The index of the Alt to be removed.
	 * @throws IndexOutOfBoundsException
	 *             if the index is not valid.
	 */
	public void remove(int index)
	{
		Alt alt = alts.get(index);
		alts.remove(index);
		
		if(alt.isCracked())
			numCracked--;
		else if(alt.isCheckedPremium())
			numPremium--;
		
		altsFile.save(this);
	}
	
	/**
	 * Removes the given Alt. Slower than {@link #remove(int)}. Fails safely and
	 * silently if the given Alt is not in the list.
	 *
	 * @param alt
	 *            The Alt to be removed.
	 */
	public void remove(Alt alt)
	{
		if(!alts.remove(alt))
			return;
		
		if(alt.isCracked())
			numCracked--;
		else if(alt.isCheckedPremium())
			numPremium--;
		
		altsFile.save(this);
	}
	
	public boolean dedupeByUsernamePreferRefreshToken()
	{
		LinkedHashMap<String, Alt> bestByName = new LinkedHashMap<>();
		ArrayList<Alt> removeList = new ArrayList<>();
		
		for(Alt alt : alts)
		{
			if(alt.isCracked() || alt.getName().isEmpty())
				continue;
			
			String key = alt.getName().toLowerCase(Locale.ROOT);
			Alt existing = bestByName.get(key);
			
			if(existing == null)
			{
				bestByName.put(key, alt);
				continue;
			}
			
			if(duplicatePriority(alt) > duplicatePriority(existing))
			{
				bestByName.put(key, alt);
				removeList.add(existing);
			}else
				removeList.add(alt);
		}
		
		if(removeList.isEmpty())
			return false;
		
		alts.removeAll(removeList);
		sortAlts();
		altsFile.save(this);
		return true;
	}
	
	private int duplicatePriority(Alt alt)
	{
		int score = 0;
		
		if(alt instanceof TokenAlt
			&& !((TokenAlt)alt).getRefreshToken().isEmpty())
			score += 100;
		
		if(alt.isFavorite())
			score += 10;
		
		if(alt.isCheckedPremium())
			score += 1;
		
		return score;
	}
	
	private void sortAlts()
	{
		Comparator<Alt> c = Comparator.comparing(a -> !a.isFavorite());
		c = c.thenComparing(Alt::isCracked);
		c = c.thenComparing(a -> a.getDisplayName().toLowerCase());
		
		ArrayList<Alt> newAlts = alts.stream().distinct().sorted(c)
			.collect(Collectors.toCollection(ArrayList::new));
		
		alts.clear();
		alts.addAll(newAlts);
		
		numCracked = (int)alts.stream().filter(Alt::isCracked).count();
		numPremium = (int)alts.stream().filter(Alt::isCheckedPremium).count();
	}
	
	public List<Alt> getList()
	{
		return Collections.unmodifiableList(alts);
	}
	
	public boolean isDisconnectRandomAltReconnectEnabled()
	{
		return disconnectRandomAltReconnectEnabled;
	}
	
	public void setDisconnectRandomAltReconnectEnabled(boolean enabled)
	{
		if(disconnectRandomAltReconnectEnabled == enabled)
			return;
		
		disconnectRandomAltReconnectEnabled = enabled;
		altsFile.save(this);
	}
	
	void setDisconnectRandomAltReconnectEnabledSilently(boolean enabled)
	{
		disconnectRandomAltReconnectEnabled = enabled;
	}
	
	public int getNumPremium()
	{
		return numPremium;
	}
	
	public int getNumCracked()
	{
		return numCracked;
	}
	
	public Exception getFolderException()
	{
		return altsFile.getFolderException();
	}
}
