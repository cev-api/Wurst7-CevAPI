/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.patreon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import net.wurstclient.altmanager.Encryption;

/**
 * Stores the supporter session in Wurst's existing encrypted configuration
 * format.
 *
 * <p>
 * Wurst already protects its alt list using a per-installation AES key that is
 * itself wrapped with an RSA key stored in the user's home directory. That
 * cross-platform mechanism is reused here. If the key material cannot be
 * loaded, the session is kept in memory only rather than written in plain
 * text. Tokens are never logged.
 */
final class SupporterCredentialStore implements SupporterStore
{
	private static final String FILE_NAME = "patreon.encrypted_json";
	
	private final Path file;
	private final Encryption encryption;
	
	SupporterCredentialStore(Path wurstFolder, Path encryptionFolder)
	{
		file = wurstFolder.resolve(FILE_NAME);
		Encryption created = null;
		if(encryptionFolder != null)
		{
			try
			{
				created = new Encryption(encryptionFolder);
				
			}catch(Exception ignored)
			{
				// Keep the session in memory if secure storage is unavailable.
			}
		}
		encryption = created;
	}
	
	@Override
	public boolean isPersistent()
	{
		return encryption != null;
	}
	
	@Override
	public SupporterData load() throws IOException
	{
		if(encryption == null || Files.notExists(file))
			return new SupporterData();
		
		try
		{
			String json = encryption.loadEncryptedFile(file);
			JsonObject object = JsonParser.parseString(json).getAsJsonObject();
			return SupporterData.fromJson(object);
			
		}catch(JsonParseException | IllegalStateException e)
		{
			throw new IOException("The stored Patreon session is malformed.",
				e);
		}
	}
	
	@Override
	public void save(SupporterData data) throws IOException
	{
		if(encryption == null)
			return;
		
		Files.createDirectories(file.getParent());
		encryption.saveEncryptedFile(file, data.toJson().toString());
		restrictPermissions();
	}
	
	@Override
	public void delete() throws IOException
	{
		if(Files.exists(file))
			Files.delete(file);
	}
	
	private void restrictPermissions()
	{
		try
		{
			Files.setPosixFilePermissions(file,
				PosixFilePermissions.fromString("rw-------"));
			
		}catch(UnsupportedOperationException | IOException ignored)
		{
			// Windows and some network shares don't support POSIX permissions.
		}
	}
}
