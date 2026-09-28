/*
 * Copyright 2026 (C) PCGen contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package pcgen.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import pcgen.core.Campaign;
import pcgen.core.GameMode;
import pcgen.core.PlayerCharacter;
import pcgen.system.PCGenSettings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IOHandlerBackupTest
{
	private String savedBackupDir;
	private boolean savedCreateBackup;

	private final IOHandler handler = new IOHandler()
	{
		@Override
		protected void read(PlayerCharacter aPC, InputStream in, boolean validate)
		{
		}

		@Override
		protected void write(PlayerCharacter aPC, GameMode mode, List<Campaign> campaigns, OutputStream out)
			throws RuntimeException
		{
			try
			{
				out.write("new".getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException e)
			{
				throw new RuntimeException(e);
			}
		}
	};

	@BeforeEach
	void setUp()
	{
		savedBackupDir = PCGenSettings.getBackupPcgDir();
		savedCreateBackup = PCGenSettings.getCreatePcgBackup();
		PCGenSettings.OPTIONS_CONTEXT.setBoolean(PCGenSettings.OPTION_CREATE_PCG_BACKUP, true);
	}

	@AfterEach
	void tearDown()
	{
		PCGenSettings.getInstance().setProperty(PCGenSettings.BACKUP_PCG_PATH, savedBackupDir);
		PCGenSettings.OPTIONS_CONTEXT.setBoolean(PCGenSettings.OPTION_CREATE_PCG_BACKUP, savedCreateBackup);
	}

	/** The backup dir may not exist yet; File.renameTo then failed silently and no backup was made. */
	@Test
	void backsUpIntoMissingBackupDirAndKeepsOriginal(@TempDir Path dir) throws IOException
	{
		Path character = Files.writeString(dir.resolve("hero.pcg"), "old");
		Path backupDir = dir.resolve("backups/nested");
		PCGenSettings.getInstance().setProperty(PCGenSettings.BACKUP_PCG_PATH, backupDir.toString());

		handler.createBackupForFile(character.toFile());

		assertEquals("old", Files.readString(backupDir.resolve("hero.pcg.bak")));
		assertEquals("old", Files.readString(character));
	}

	/** Writing by file name opened (truncated) the file before backing it up, so nothing was backed up. */
	@Test
	void writeByNameBacksUpTheOldContents(@TempDir Path dir) throws IOException
	{
		Path character = Files.writeString(dir.resolve("hero.pcg"), "old");
		PCGenSettings.getInstance().setProperty(PCGenSettings.BACKUP_PCG_PATH, "");

		handler.write(null, null, List.of(), character.toString());

		assertEquals("old", Files.readString(dir.resolve("hero.pcg.bak")));
		assertEquals("new", Files.readString(character));
	}

	@Test
	void noBackupWhenDisabled(@TempDir Path dir) throws IOException
	{
		Path character = Files.writeString(dir.resolve("hero.pcg"), "old");
		PCGenSettings.getInstance().setProperty(PCGenSettings.BACKUP_PCG_PATH, "");
		PCGenSettings.OPTIONS_CONTEXT.setBoolean(PCGenSettings.OPTION_CREATE_PCG_BACKUP, false);

		handler.createBackupForFile(character.toFile());

		assertFalse(Files.exists(dir.resolve("hero.pcg.bak")));
	}
}
