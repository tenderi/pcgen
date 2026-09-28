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
package pcgen.gui3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiUtilityTest
{
	@TempDir
	Path tempDir;

	@Test
	void existingDirectoryIsKept()
	{
		assertEquals(tempDir.toFile(), GuiUtility.existingInitialDirectory(tempDir.toFile()));
	}

	@Test
	void missingDirectoryFallsBackToNearestExistingAncestor()
	{
		File missing = tempDir.resolve("gone/deeper").toFile();
		assertEquals(tempDir.toFile(), GuiUtility.existingInitialDirectory(missing));
	}

	@Test
	void fileFallsBackToItsFolder() throws IOException
	{
		File file = Files.createFile(tempDir.resolve("sheet.pdf")).toFile();
		assertEquals(tempDir.toFile(), GuiUtility.existingInitialDirectory(file));
	}

	@Test
	void blankOrNullGivesPlatformDefault()
	{
		assertNull(GuiUtility.existingInitialDirectory(null));
		assertNull(GuiUtility.existingInitialDirectory(new File("")));
	}

	@Test
	void relativePathResolvesToAnAbsoluteDirectory()
	{
		File result = GuiUtility.existingInitialDirectory(new File("no-such-dir-for-test"));
		assertTrue(result.isAbsolute() && result.isDirectory());
	}
}
