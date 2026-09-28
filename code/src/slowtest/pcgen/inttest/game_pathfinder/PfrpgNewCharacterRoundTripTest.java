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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import pcgen.LocaleDependentTestCase;
import pcgen.cdom.base.Constants;
import pcgen.core.Equipment;
import pcgen.core.GameMode;
import pcgen.core.PCAlignment;
import pcgen.core.PCClass;
import pcgen.core.PCStat;
import pcgen.core.Race;
import pcgen.core.SettingsHandler;
import pcgen.facade.core.CharacterFacade;
import pcgen.facade.core.DataSetFacade;
import pcgen.facade.core.SourceSelectionFacade;
import pcgen.facade.core.UIDelegate;
import pcgen.facade.util.ListFacade;
import pcgen.persistence.SourceFileLoader;
import pcgen.system.BatchExporter;
import pcgen.system.CharacterManager;
import pcgen.system.ConsoleUIDelegate;
import pcgen.system.Main;
import pcgen.system.PropertyContext;
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
 * Builds a new Pathfinder character the way the Summary/Class/Gear tabs do
 * (race, alignment, point-buy stats, class levels, gear), saves it and checks
 * that reloading the saved file gives back the same character.
 */
class PfrpgNewCharacterRoundTripTest
{
	private static final String TEST_CONFIG_FILE = "config.ini.junit";
	private static final String TEMPLATE = "code/testsuite/base-xml.ftl";
	/** Supplies the Core Rulebook + APG source selection and sets the app up. */
	private static final String SOURCES_FROM = "code/testsuite/PCGfiles/pf_goldielocks.pcg";

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
	void newCharacterSurvivesSaveAndReload() throws IOException
	{
		Path settingsDir = Files.createDirectories(tempDir.resolve("testsuite"));
		TestHelper.createDummySettingsFile(tempDir.resolve(TEST_CONFIG_FILE).toString(), settingsDir.toString(),
			TestHelper.findDataFolder());
		GracefulExit.registerExitFunction(status -> assertEquals(0, status, "setup export failed"));
		Main.main("--character", SOURCES_FROM, "--exportsheet", TEMPLATE,
			"--outputfile", tempDir.resolve("setup.xml").toString(),
			"--settingsdir", tempDir.toString(), "--configfilename", TEST_CONFIG_FILE);

		// Answer "Yes" to confirmations (e.g. "Are your abilities set as you'd like them?" before
		// the first level-up) as a user would; the plain console delegate answers "No".
		UIDelegate delegate = new ConsoleUIDelegate()
		{
			@Override
			public Boolean maybeShowWarningConfirm(String title, String message, String checkBoxText,
				PropertyContext context, String contextProp)
			{
				return true;
			}

			@Override
			public boolean showWarningConfirm(String title, String message)
			{
				return true;
			}
		};
		SourceSelectionFacade sources =
				CharacterManager.getRequiredSourcesForCharacter(new File(SOURCES_FROM), delegate);
		SourceFileLoader loader =
				new SourceFileLoader(delegate, sources.getCampaigns(), sources.getGameMode().get().getName());
		loader.run();
		DataSetFacade data = loader.getDataSetFacade();

		GameMode gameMode = SettingsHandler.getGameAsProperty().get();
		gameMode.setRollMethod(Constants.CHARACTER_STAT_METHOD_PURCHASE);
		gameMode.setPurchaseMethodName("High Fantasy");

		CharacterFacade character = CharacterManager.createNewCharacter(delegate, data);
		assertNotNull(character);
		character.setName("Round Trip");
		character.setRace(find(data.getRaces(), r -> r.getKeyName().equals("Human")));
		character.setAlignment(find(data.getAlignments(), a -> a.getKeyName().equals("CN")));
		Map<String, Integer> scores = Map.of("STR", 8, "DEX", 14, "CON", 12, "INT", 13, "WIS", 10, "CHA", 17);
		for (PCStat stat : data.getStats())
		{
			character.setScoreBase(stat, scores.getOrDefault(stat.getKeyName(), 10));
		}
		PCClass sorcerer = find(data.getClasses(), c -> c.getKeyName().equals("Sorcerer"));
		character.addCharacterLevels(new PCClass[]{sorcerer, sorcerer, sorcerer});
		for (String key : new String[]{"Crossbow (Light)", "Bolt (Crossbow)", "Backpack", "Dagger"})
		{
			character.addPurchasedEquipment(
				find(data.getEquipment(), e -> ((Equipment) e).getKeyName().equals(key)), 1, false, true); // free: a new PC has no gold
		}

		File before = tempDir.resolve("before.xml").toFile();
		assertTrue(BatchExporter.exportCharacterToNonPDF(character, before, new File(TEMPLATE)));
		// guard against a vacuous pass: the character must really have been built
		String built = Files.readString(before.toPath());
		assertTrue(built.contains("<levels_total>3</levels_total>"), "class levels not applied");
		assertTrue(built.contains("<name>Sorcerer</name>"), "class not applied");
		assertTrue(built.contains("<race>Human"), "race not applied");
		assertTrue(built.contains("Crossbow"), () -> "equipment not added: "
			+ built.replaceAll("(?s).*?(<equipment>.{0,1500}).*", "$1"));

		File saved = tempDir.resolve("new.pcg").toFile();
		character.setFile(saved);
		assertTrue(CharacterManager.saveCharacter(character), "save failed");

		CharacterFacade reloaded = CharacterManager.openCharacter(saved, delegate, data);
		assertNotNull(reloaded, "could not reload the saved character");
		File after = tempDir.resolve("after.xml").toFile();
		assertTrue(BatchExporter.exportCharacterToNonPDF(reloaded, after, new File(TEMPLATE)));

		Diff diff = DiffBuilder.compare(Input.fromFile(before)).withTest(Input.fromFile(after))
			.ignoreWhitespace().build();
		assertFalse(diff.hasDifferences(), diff.fullDescription());
	}

	private static <T> T find(ListFacade<T> list, Predicate<T> match)
	{
		for (T element : list)
		{
			if (match.test(element))
			{
				return element;
			}
		}
		throw new AssertionError("not found in loaded data");
	}
}
