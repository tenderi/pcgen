package pcgen.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;

import pcgen.system.PropertyContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks how {@link ExportProfile} picks its defaults, stores sheets and names files.
 */
class ExportProfileTest
{
	@TempDir
	private Path root;

	private File sheetsRoot;
	private File modeDir;
	private File bluePdf;
	private File redPdf;
	private File plainText;
	private File statblockHtml;
	private PropertyContext context;
	private final UnaryOperator<String> gameModeDefaults =
		Map.of("PDF", "csheet_blue.xslt", "TXT", "csheet_plain.TXT")::get;

	@BeforeEach
	void setUp() throws IOException
	{
		sheetsRoot = root.resolve("outputsheets").toFile();
		modeDir = new File(sheetsRoot, "d20/fantasy");
		bluePdf = touch(new File(modeDir, "pdf/csheet_blue.xslt"));
		redPdf = touch(new File(modeDir, "pdf/csheet_red.xslt"));
		plainText = touch(new File(modeDir, "text/csheet_plain.TXT"));
		statblockHtml = touch(new File(modeDir, "htmlxml/csheet_statblock.htm"));
		context = new PropertyContext("ExportAll")
		{
		};
	}

	private static File touch(File file) throws IOException
	{
		Files.createDirectories(file.getParentFile().toPath());
		Files.writeString(file.toPath(), "x");
		return file;
	}

	private ExportProfile load(String lastPdf, String lastOther)
	{
		return ExportProfile.load(context, "Pathfinder", sheetsRoot, modeDir, gameModeDefaults, lastPdf, lastOther);
	}

	@Test
	void newProfileUsesGameModeDefaultsAndIncludesEverything()
	{
		ExportProfile profile = load(null, null);
		assertEquals(bluePdf, profile.getPdfSheet());
		assertEquals(plainText, profile.getTextSheet());
		assertEquals("", profile.getOutputDir());
		assertTrue(profile.isIncludePdf());
		assertTrue(profile.isIncludeText());
		assertTrue(profile.isIncludePcg());
	}

	@Test
	void newProfilePrefersLastUsedSheetsOfThisGameMode()
	{
		ExportProfile profile = load(redPdf.getAbsolutePath(), statblockHtml.getAbsolutePath());
		assertEquals(redPdf, profile.getPdfSheet());
		assertEquals(statblockHtml, profile.getTextSheet());
	}

	@Test
	void lastUsedSheetsOfAnotherGameModeOrWrongTypeAreIgnored() throws IOException
	{
		File otherMode = touch(new File(sheetsRoot, "d20/5e/pdf/csheet_5e.xslt"));
		// a PDF sheet is no text sheet
		ExportProfile profile = load(otherMode.getAbsolutePath(), redPdf.getAbsolutePath());
		assertEquals(bluePdf, profile.getPdfSheet());
		assertEquals(plainText, profile.getTextSheet());
	}

	@Test
	void savedProfileRoundTripsAndStoresSheetsRelatively()
	{
		ExportProfile profile = load(null, null);
		profile.setPdfSheet(redPdf);
		profile.setTextSheet(statblockHtml);
		profile.setOutputDir("  /tmp/exports ");
		profile.setIncludePcg(false);
		profile.save();

		assertEquals("d20/fantasy/pdf/csheet_red.xslt", context.getProperty("Pathfinder.pdfSheet"));

		ExportProfile reloaded = load(bluePdf.getAbsolutePath(), plainText.getAbsolutePath());
		assertEquals(redPdf.getAbsoluteFile().toPath().normalize(),
			reloaded.getPdfSheet().getAbsoluteFile().toPath().normalize());
		assertEquals(statblockHtml.getAbsoluteFile().toPath().normalize(),
			reloaded.getTextSheet().getAbsoluteFile().toPath().normalize());
		assertEquals("/tmp/exports", reloaded.getOutputDir());
		assertTrue(reloaded.isIncludePdf());
		assertFalse(reloaded.isIncludePcg());
	}

	@Test
	void profilesAreKeptPerGameMode()
	{
		ExportProfile profile = load(null, null);
		profile.setPdfSheet(redPdf);
		profile.save();

		ExportProfile other =
			ExportProfile.load(context, "35e", sheetsRoot, modeDir, gameModeDefaults, null, null);
		assertEquals(bluePdf, other.getPdfSheet());
	}

	@Test
	void missingStoredSheetFallsBackToDefault() throws IOException
	{
		ExportProfile profile = load(null, null);
		profile.setPdfSheet(redPdf);
		profile.save();
		Files.delete(redPdf.toPath());

		assertEquals(bluePdf, load(null, null).getPdfSheet());
	}

	@Test
	void noSheetWhenNothingExists()
	{
		ExportProfile profile =
			ExportProfile.load(context, "Killshot", sheetsRoot, new File(sheetsRoot, "killshot"), type -> null, null,
				null);
		assertNull(profile.getPdfSheet());
		assertNull(profile.getTextSheet());
	}

	@Test
	void fileBaseNameReplacesCharactersNotAllowedInFileNames()
	{
		assertEquals("Ezren Vaska", ExportProfile.fileBaseName("Ezren Vaska"));
		assertEquals("A_B_C_D", ExportProfile.fileBaseName("A/B\\C:D"));
		assertEquals("hidden", ExportProfile.fileBaseName(".hidden."));
		assertEquals("character", ExportProfile.fileBaseName("  "));
		assertEquals("character", ExportProfile.fileBaseName(null));
	}

	@Test
	void baseNameFollowsTheCharacterFile()
	{
		assertEquals("ezren", ExportProfile.baseNameFor(new File("/x/ezren.pcg"), "Ezren Vaska"));
		assertEquals("Ezren Vaska", ExportProfile.baseNameFor(null, "Ezren Vaska"));
		assertEquals("Ezren Vaska", ExportProfile.baseNameFor(new File(""), "Ezren Vaska"));
	}

	@Test
	void blankFolderMeansNextToTheCharacterFile()
	{
		ExportProfile profile = load(null, null);
		File characterFile = new File(root.toFile(), "chars/ezren.pcg");
		assertEquals(characterFile.getParentFile(), profile.outputFolderFor(characterFile));

		profile.setOutputDir(root.resolve("out").toString());
		assertEquals(root.resolve("out").toFile(), profile.outputFolderFor(characterFile));
	}
}
