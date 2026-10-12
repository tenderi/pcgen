package pcgen.inttest.game_pathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

import pcgen.LocaleDependentTestCase;
import pcgen.facade.core.CharacterFacade;
import pcgen.facade.core.SourceSelectionFacade;
import pcgen.facade.core.UIDelegate;
import pcgen.io.ExportProfile;
import pcgen.persistence.SourceFileLoader;
import pcgen.system.CharacterManager;
import pcgen.system.ConsoleUIDelegate;
import pcgen.system.Main;
import pcgen.util.GracefulExit;
import pcgen.util.TestHelper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Export dialog's "Export All": one call writes the PDF, the text sheet and a copy
 * of the .pcg with the game mode's defaults.
 */
class PfrpgExportAllTest
{
	private static final String TEST_CONFIG_FILE = "config.ini.junit";

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
	void exportAllWritesPdfTextAndCharacterFile() throws IOException
	{
		Path source = Paths.get("code/testsuite/PCGfiles/pf_goldielocks.pcg");
		Path charDir = Files.createDirectories(tempDir.resolve("chars"));
		Path original = charDir.resolve("goldie.pcg");
		Files.copy(source, original);

		// A normal batch export sets up settings, game modes and plugins as the app does.
		Path settingsDir = Files.createDirectories(tempDir.resolve("testsuite"));
		TestHelper.createDummySettingsFile(tempDir.resolve(TEST_CONFIG_FILE).toString(), settingsDir.toString(),
			TestHelper.findDataFolder());
		GracefulExit.registerExitFunction(status -> assertTrue(status == 0, "setup export failed: " + status));
		Main.main("--character", original.toString(), "--exportsheet", "code/testsuite/base-xml.ftl",
			"--outputfile", tempDir.resolve("setup.xml").toString(),
			"--settingsdir", tempDir.toString(), "--configfilename", TEST_CONFIG_FILE);

		UIDelegate delegate = new ConsoleUIDelegate();
		SourceSelectionFacade sources = CharacterManager.getRequiredSourcesForCharacter(original.toFile(), delegate);
		SourceFileLoader loader =
				new SourceFileLoader(delegate, sources.getCampaigns(), sources.getGameMode().get().getName());
		loader.run();
		CharacterFacade character = CharacterManager.openCharacter(original.toFile(), delegate,
			loader.getDataSetFacade());
		assertNotNull(character, "could not load " + original);
		String originalContent = Files.readString(original);

		ExportProfile profile = ExportProfile.forCurrentGameMode();
		assertEquals("csheet_fantasy_std_blue.xslt", profile.getPdfSheet().getName(), "Pathfinder DEFAULT.PDF");
		assertEquals("csheet_plain.TXT", profile.getTextSheet().getName(), "Pathfinder DEFAULT.TXT");
		Path out = tempDir.resolve("exports");
		profile.setOutputDir(out.toString());

		character.setName("Goldie Renamed");
		assertTrue(character.isDirty(), "renaming should mark the character as changed");
		ExportProfile.Result result = profile.export(character);
		assertEquals(List.of(), result.errors());
		assertEquals(List.of(out.resolve("goldie.pcg").toFile(), out.resolve("goldie.pdf").toFile(),
			out.resolve("goldie.txt").toFile()), result.written());
		assertTrue(Files.readString(out.resolve("goldie.pdf"), java.nio.charset.StandardCharsets.ISO_8859_1)
			.startsWith("%PDF"), "not a PDF");
		assertTrue(Files.readString(out.resolve("goldie.txt")).contains("Goldie Renamed"),
			"text sheet lacks the character's name");
		assertTrue(Files.readString(out.resolve("goldie.pcg")).contains("CHARACTERNAME:"), "not a .pcg");
		assertTrue(character.isDirty(), "a copy must not mark the character as saved");
		assertEquals(originalContent, Files.readString(original), "the character's own file was touched");
		assertEquals(original.toFile(), character.getFileRef().get(), "the character's file changed");

		// With no folder, everything goes next to the character and its .pcg is a normal save.
		profile.setOutputDir("");
		profile.setIncludePdf(false);
		result = profile.export(character);
		assertEquals(List.of(), result.errors());
		assertEquals(List.of(original.toFile(), charDir.resolve("goldie.txt").toFile()), result.written());
		assertFalse(character.isDirty(), "saving the character's own file clears unsaved changes");
		File[] files = charDir.toFile().listFiles((dir, name) -> !name.endsWith(".bak"));
		assertNotNull(files);
		assertEquals(2, files.length, "only goldie.pcg and goldie.txt expected: " + List.of(files));
	}
}
