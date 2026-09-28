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
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import pcgen.LocaleDependentTestCase;
import pcgen.cdom.base.Constants;
import pcgen.core.GameMode;
import pcgen.core.PCClass;
import pcgen.core.PCStat;
import pcgen.core.SettingsHandler;
import pcgen.facade.core.CharacterFacade;
import pcgen.facade.core.DataSetFacade;
import pcgen.facade.core.SourceSelectionFacade;
import pcgen.facade.core.UIDelegate;
import pcgen.facade.util.ListFacade;
import pcgen.persistence.SourceFileLoader;
import pcgen.system.CharacterManager;
import pcgen.system.ConsoleUIDelegate;
import pcgen.system.Main;
import pcgen.system.PropertyContext;
import pcgen.util.GracefulExit;
import pcgen.util.TestHelper;
import pcgen.util.chooser.ChooserFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Summary tab's point-buy line ("spent / available") must follow the ability
 * scores as the player sets them, using the Pathfinder point-buy costs.
 */
class PfrpgPointBuyDisplayTest
{
	private static final String TEST_CONFIG_FILE = "config.ini.junit";
	private static final String TEMPLATE = "code/testsuite/base-xml.ftl";
	private static final String SOURCES_FROM = "code/testsuite/PCGfiles/pf_goldielocks.pcg";
	/** Pathfinder Core Rulebook point-buy cost per score (10 = 0). */
	private static final Map<Integer, Integer> COST = Map.ofEntries(Map.entry(7, -4), Map.entry(8, -2),
		Map.entry(9, -1), Map.entry(10, 0), Map.entry(11, 1), Map.entry(12, 2), Map.entry(13, 3), Map.entry(14, 5),
		Map.entry(15, 7), Map.entry(16, 10), Map.entry(17, 13), Map.entry(18, 17));

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
	void spentPointsFollowTheScores() throws IOException
	{
		Path settingsDir = Files.createDirectories(tempDir.resolve("testsuite"));
		TestHelper.createDummySettingsFile(tempDir.resolve(TEST_CONFIG_FILE).toString(), settingsDir.toString(),
			TestHelper.findDataFolder());
		GracefulExit.registerExitFunction(status -> assertEquals(0, status, "setup export failed"));
		Main.main("--character", SOURCES_FROM, "--exportsheet", TEMPLATE,
			"--outputfile", tempDir.resolve("setup.xml").toString(),
			"--settingsdir", tempDir.toString(), "--configfilename", TEST_CONFIG_FILE);

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
		ChooserFactory.setDelegate(delegate);
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
		character.setRace(find(data.getRaces(), r -> r.getKeyName().equals("Human")));

		List<String> log = new ArrayList<>();
		Map<String, Integer> scores = new java.util.LinkedHashMap<>();
		for (PCStat stat : data.getStats())
		{
			scores.put(stat.getKeyName(), 10);
		}
		log.add("new character: " + text(character) + "   expected spent " + spent(scores));

		int[][] steps = {{0, 14}, {1, 16}, {2, 12}, {3, 8}, {5, 17}, {1, 15}, {0, 10}};
		List<PCStat> stats = new ArrayList<>();
		data.getStats().forEach(stats::add);
		boolean mismatch = false;
		for (int[] step : steps)
		{
			PCStat stat = stats.get(step[0]);
			character.setScoreBase(stat, step[1]);
			scores.put(stat.getKeyName(), step[1]);
			String shown = text(character);
			int expected = spent(scores);
			boolean ok = shown.contains(" " + expected + " of ");
			mismatch |= !ok;
			log.add(stat.getKeyName() + "=" + step[1] + ": " + shown + "   expected spent " + expected
				+ (ok ? "" : "   <-- MISMATCH"));
		}

		PCClass sorcerer = find(data.getClasses(), c -> c.getKeyName().equals("Sorcerer"));
		character.addCharacterLevels(new PCClass[]{sorcerer});
		log.add("after level 1: " + text(character) + "   expected spent " + spent(scores));
		character.addCharacterLevels(new PCClass[]{sorcerer, sorcerer});
		log.add("after level 3: " + text(character) + "   expected spent " + spent(scores));

		if (mismatch)
		{
			fail(String.join("\n", log));
		}
		System.out.println(String.join("\n", log));
	}

	private static String text(CharacterFacade character)
	{
		return character.getStatTotalLabelTextRef().get() + " " + character.getStatTotalTextRef().get();
	}

	private static int spent(Map<String, Integer> scores)
	{
		return scores.values().stream().mapToInt(s -> COST.getOrDefault(s, 0)).sum();
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
