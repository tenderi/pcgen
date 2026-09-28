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
package pcgen.inttest.game_pathfinder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import pcgen.LocaleDependentTestCase;
import pcgen.cdom.base.Constants;
import pcgen.core.GameMode;
import pcgen.core.SettingsHandler;
import pcgen.facade.core.CharacterFacade;
import pcgen.facade.core.SourceSelectionFacade;
import pcgen.facade.core.UIDelegate;
import pcgen.persistence.SourceFileLoader;
import pcgen.system.BatchExporter;
import pcgen.system.CharacterManager;
import pcgen.system.ConsoleUIDelegate;
import pcgen.system.Main;
import pcgen.util.GracefulExit;
import pcgen.util.TestHelper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

/**
 * Saving a character and loading the saved file must give back the same
 * character: export the original and the re-saved copy with the same data
 * loaded and compare. Set the system property {@code roundtrip.pcg} to a
 * .pcg file to check another Pathfinder character (its sources must exist).
 */
class PfrpgSaveRoundTripTest
{
	private static final String TEST_CONFIG_FILE = "config.ini.junit";
	private static final String TEMPLATE = "code/testsuite/base-xml.ftl";

	@TempDir
	Path tempDir;

	@BeforeEach
	void setUp()
	{
		LocaleDependentTestCase.before(Locale.US);
	}

	@AfterEach
	void tearDown()
	{
		LocaleDependentTestCase.after();
		GracefulExit.registerExitFunction(System::exit);
	}

	@Test
	void saveAndReloadKeepsTheCharacter() throws IOException
	{
		String override = System.getProperty("roundtrip.pcg");
		Path original = (override == null || override.isBlank())
			? Paths.get("code/testsuite/PCGfiles/pf_goldielocks.pcg")
			: Paths.get(override);

		// A normal batch export sets up settings, game modes and plugins as the app does.
		Path settingsDir = Files.createDirectories(tempDir.resolve("testsuite"));
		TestHelper.createDummySettingsFile(tempDir.resolve(TEST_CONFIG_FILE).toString(), settingsDir.toString(),
			TestHelper.findDataFolder());
		GracefulExit.registerExitFunction(status -> assertTrue(status == 0, "setup export failed: " + status));
		Main.main("--character", original.toString(), "--exportsheet", TEMPLATE,
			"--outputfile", tempDir.resolve("setup.xml").toString(),
			"--settingsdir", tempDir.toString(), "--configfilename", TEST_CONFIG_FILE);

		UIDelegate delegate = new ConsoleUIDelegate();
		SourceSelectionFacade sources = CharacterManager.getRequiredSourcesForCharacter(original.toFile(), delegate);
		SourceFileLoader loader =
				new SourceFileLoader(delegate, sources.getCampaigns(), sources.getGameMode().get().getName());
		loader.run();
		applyPointBuyPreference(original);

		CharacterFacade character = CharacterManager.openCharacter(original.toFile(), delegate,
			loader.getDataSetFacade());
		assertNotNull(character, "could not load " + original);
		File before = tempDir.resolve("before.xml").toFile();
		assertTrue(BatchExporter.exportCharacterToNonPDF(character, before, new File(TEMPLATE)));

		File saved = tempDir.resolve("saved.pcg").toFile();
		character.setFile(saved);
		assertTrue(CharacterManager.saveCharacter(character), "save failed");
		String keepDir = System.getProperty("roundtrip.keep");
		if (keepDir != null && !keepDir.isBlank())
		{
			// for investigating a failure: keep the re-saved file and both exports
			Files.createDirectories(Path.of(keepDir));
			Files.copy(saved.toPath(), Path.of(keepDir, "saved.pcg"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}

		CharacterFacade reloaded = CharacterManager.openCharacter(saved, delegate, loader.getDataSetFacade());
		assertNotNull(reloaded, "could not reload the saved character");
		File after = tempDir.resolve("after.xml").toFile();
		assertTrue(BatchExporter.exportCharacterToNonPDF(reloaded, after, new File(TEMPLATE)));

		Diff diff = DiffBuilder.compare(Input.fromFile(before)).withTest(Input.fromFile(after))
			.ignoreWhitespace().build();
		assertFalse(diff.hasDifferences(), diff.fullDescription());
	}

	/**
	 * The roll method is a game-mode preference, not read back from a .pcg (the file's
	 * PURCHASEPOINTS line only records it). Set it the way the app's preferences would
	 * for this character, or a point-buy character's pool is reset to 0 on save.
	 */
	private static void applyPointBuyPreference(Path pcg) throws IOException
	{
		for (String line : Files.readAllLines(pcg))
		{
			if (line.startsWith("PURCHASEPOINTS:Y|TYPE:"))
			{
				GameMode gameMode = SettingsHandler.getGameAsProperty().get();
				gameMode.setRollMethod(Constants.CHARACTER_STAT_METHOD_PURCHASE);
				gameMode.setPurchaseMethodName(line.substring("PURCHASEPOINTS:Y|TYPE:".length()).trim());
				return;
			}
		}
	}
}
