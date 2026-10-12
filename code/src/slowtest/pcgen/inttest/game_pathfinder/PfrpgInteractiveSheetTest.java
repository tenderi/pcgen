package pcgen.inttest.game_pathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import pcgen.LocaleDependentTestCase;
import pcgen.system.Main;
import pcgen.util.GracefulExit;
import pcgen.util.TestHelper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The interactive HTML sheet exports and carries what its session tracker needs: the character
 * data and the markers around every value a session can change. The tracker itself is tested
 * with the scripts in code/tools/interactive-sheet.
 */
class PfrpgInteractiveSheetTest
{
	private static final String TEST_CONFIG_FILE = "config.ini.junit";
	private static final String SHEET = "outputsheets/d20/fantasy/htmlxml/csheet_fantasy_interactive.htm.ftl";

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
	void exportsWithSessionMarkers() throws IOException
	{
		Path settingsDir = Files.createDirectories(tempDir.resolve("testsuite"));
		TestHelper.createDummySettingsFile(tempDir.resolve(TEST_CONFIG_FILE).toString(), settingsDir.toString(),
			TestHelper.findDataFolder());
		Path out = tempDir.resolve("sheet.htm");
		GracefulExit.registerExitFunction(status -> assertEquals(0, status, "export failed"));
		// a racial spellcaster with natural attacks
		Main.main("--character", "code/testsuite/PCGfiles/pf_goldielocks.pcg", "--exportsheet", SHEET,
			"--outputfile", out.toString(), "--settingsdir", tempDir.toString(), "--configfilename", TEST_CONFIG_FILE);

		String html = Files.readString(out);
		assertTrue(html.contains("var PcSheetData = {"), "character data");
		assertTrue(html.contains("hitDice: \"(30d12)"), "hit dice");
		assertTrue(html.contains("PcSession.start("), "tracker script");
		assertTrue(html.contains("data-hp=\"current\""), "current HP box");
		assertEquals(6, count(html, "<input class=\"tmp\""), "TEMP boxes: 3 saves, melee, ranged, CMB");
		for (String role : new String[]{"hp", "ac", "touch", "flat", "init", "save-fortitude", "save-reflex",
			"save-will", "melee", "ranged", "cmb", "cmd", "atk-melee", "dmg-melee", "score-STR", "mod-CHA", "skill-DEX"})
		{
			assertTrue(html.contains("<span class=\"adj\" data-r=\"" + role + "\">"), "marker " + role);
		}
		assertTrue(count(html, "<span class=\"adj\"") > 60, "markers");
		assertTrue(!html.contains("${") && !html.contains("<@adj"), "unprocessed FreeMarker");
	}

	private static int count(String text, String what)
	{
		Matcher m = Pattern.compile(Pattern.quote(what)).matcher(text);
		int n = 0;
		while (m.find())
		{
			n++;
		}
		return n;
	}
}
