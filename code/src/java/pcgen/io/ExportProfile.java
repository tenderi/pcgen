/*
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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

import pcgen.cdom.base.Constants;
import pcgen.core.GameMode;
import pcgen.core.Globals;
import pcgen.core.SettingsHandler;
import pcgen.facade.core.CharacterFacade;
import pcgen.gui2.UIPropertyContext;
import pcgen.gui2.facade.CharacterFacadeImpl;
import pcgen.system.BatchExporter;
import pcgen.system.CharacterManager;
import pcgen.system.ConfigurationSettings;
import pcgen.system.PCGenSettings;
import pcgen.system.PropertyContext;
import pcgen.util.Logging;

import org.apache.commons.lang3.StringUtils;

/**
 * The "Export all" defaults of one game mode: which PDF and text sheets to use, whether to
 * write a copy of the character file, and the folder to write them to. Stored in the UI
 * settings under {@code ExportAll.<game mode>.*}.
 */
public final class ExportProfile
{
	private static final String CONTEXT_NAME = "ExportAll";
	private static final String PDF_SHEET = "pdfSheet";
	private static final String TEXT_SHEET = "textSheet";
	private static final String OUTPUT_DIR = "outputDir";
	private static final String INCLUDE_PDF = "includePdf";
	private static final String INCLUDE_TEXT = "includeText";
	private static final String INCLUDE_PCG = "includePcg";

	private final PropertyContext context;
	private final String gameModeName;
	private final File outputSheetsRoot;
	private File pdfSheet;
	private File textSheet;
	private String outputDir;
	private boolean includePdf;
	private boolean includeText;
	private boolean includePcg;

	private ExportProfile(PropertyContext context, String gameModeName, File outputSheetsRoot)
	{
		this.context = context;
		this.gameModeName = gameModeName;
		this.outputSheetsRoot = outputSheetsRoot;
	}

	/**
	 * @return the profile of the game mode that is currently loaded
	 */
	public static ExportProfile forCurrentGameMode()
	{
		GameMode mode = SettingsHandler.getGameAsProperty().get();
		UIPropertyContext ui = UIPropertyContext.getInstance();
		return load(UIPropertyContext.createContext(CONTEXT_NAME), mode.getName(),
			new File(ConfigurationSettings.getOutputSheetsDir()), ExportUtilities.getGameModeSheetDir(),
			mode::getOutputSheetDefault, ui.getProperty(UIPropertyContext.DEFAULT_PDF_OUTPUT_SHEET),
			ui.getProperty(UIPropertyContext.DEFAULT_HTML_OUTPUT_SHEET));
	}

	/**
	 * Read a profile. A sheet that was never chosen (or no longer exists) falls back to the
	 * last sheet used in the Export dialog if it belongs to this game mode, then to the game
	 * mode's {@code OUTPUTSHEET:DEFAULT.PDF} / {@code DEFAULT.TXT}.
	 *
	 * @param context where the profiles are stored
	 * @param gameModeName the game mode the profile belongs to
	 * @param outputSheetsRoot the outputsheets folder; sheets below it are stored relative to it
	 * @param gameModeSheetDir the game mode's sheet folder (holding pdf/, text/, htmlxml/)
	 * @param gameModeDefaults maps "PDF" / "TXT" to the game mode's default sheet name, or null
	 * @param lastPdfSheet the last PDF sheet used, or null
	 * @param lastOtherSheet the last non-PDF sheet used, or null
	 * @return the profile
	 */
	static ExportProfile load(PropertyContext context, String gameModeName, File outputSheetsRoot,
		File gameModeSheetDir, UnaryOperator<String> gameModeDefaults, String lastPdfSheet, String lastOtherSheet)
	{
		ExportProfile profile = new ExportProfile(context, gameModeName, outputSheetsRoot);
		profile.pdfSheet = profile.resolveSheet(PDF_SHEET, lastPdfSheet, true, gameModeSheetDir,
			defaultSheet(gameModeSheetDir, ExportUtilities.SheetFilter.PDF, gameModeDefaults));
		profile.textSheet = profile.resolveSheet(TEXT_SHEET, lastOtherSheet, false, gameModeSheetDir,
			defaultSheet(gameModeSheetDir, ExportUtilities.SheetFilter.TEXT, gameModeDefaults));
		profile.outputDir = StringUtils.defaultString(context.getProperty(profile.key(OUTPUT_DIR)));
		profile.includePdf = profile.getBoolean(INCLUDE_PDF);
		profile.includeText = profile.getBoolean(INCLUDE_TEXT);
		profile.includePcg = profile.getBoolean(INCLUDE_PCG);
		return profile;
	}

	private static File defaultSheet(File gameModeSheetDir, ExportUtilities.SheetFilter filter,
		UnaryOperator<String> gameModeDefaults)
	{
		String name = gameModeDefaults.apply(filter.getTag());
		if (StringUtils.isBlank(name))
		{
			return null;
		}
		return new File(new File(gameModeSheetDir, filter.getPath()), name);
	}

	private File resolveSheet(String key, String lastUsed, boolean pdf, File gameModeSheetDir, File gameModeDefault)
	{
		String stored = context.getProperty(key(key));
		if (StringUtils.isNotBlank(stored))
		{
			File sheet = fromStoredPath(stored);
			if (sheet.isFile())
			{
				return sheet;
			}
		}
		if (StringUtils.isNotBlank(lastUsed))
		{
			File sheet = new File(lastUsed);
			if (sheet.isFile() && isInside(gameModeSheetDir, sheet)
				&& ExportUtilities.isPdfTemplate(sheet) == pdf)
			{
				return sheet;
			}
		}
		if (gameModeDefault != null && gameModeDefault.isFile())
		{
			return gameModeDefault;
		}
		return null;
	}

	private static boolean isInside(File dir, File file)
	{
		return file.getAbsoluteFile().toPath().normalize().startsWith(dir.getAbsoluteFile().toPath().normalize());
	}

	private File fromStoredPath(String stored)
	{
		File file = new File(stored);
		return file.isAbsolute() ? file : new File(outputSheetsRoot, stored);
	}

	/**
	 * Sheets below the outputsheets folder are stored relative to it, so the setting
	 * survives moving the installation.
	 */
	String toStoredPath(File sheet)
	{
		if (isInside(outputSheetsRoot, sheet))
		{
			Path relative = outputSheetsRoot.getAbsoluteFile().toPath().normalize()
				.relativize(sheet.getAbsoluteFile().toPath().normalize());
			return relative.toString().replace(File.separatorChar, '/');
		}
		return sheet.getAbsolutePath();
	}

	private String key(String name)
	{
		return gameModeName + '.' + name;
	}

	private boolean getBoolean(String name)
	{
		return context.getBoolean(key(name), true);
	}

	/**
	 * Write the profile back to the settings.
	 */
	public void save()
	{
		context.setProperty(key(PDF_SHEET), (pdfSheet == null) ? null : toStoredPath(pdfSheet));
		context.setProperty(key(TEXT_SHEET), (textSheet == null) ? null : toStoredPath(textSheet));
		context.setProperty(key(OUTPUT_DIR), outputDir);
		context.setBoolean(key(INCLUDE_PDF), includePdf);
		context.setBoolean(key(INCLUDE_TEXT), includeText);
		context.setBoolean(key(INCLUDE_PCG), includePcg);
	}

	public String getGameModeName()
	{
		return gameModeName;
	}

	public File getPdfSheet()
	{
		return pdfSheet;
	}

	public void setPdfSheet(File pdfSheet)
	{
		this.pdfSheet = pdfSheet;
	}

	public File getTextSheet()
	{
		return textSheet;
	}

	public void setTextSheet(File textSheet)
	{
		this.textSheet = textSheet;
	}

	/**
	 * @return the output folder; blank means the folder of the character's file
	 */
	public String getOutputDir()
	{
		return outputDir;
	}

	public void setOutputDir(String outputDir)
	{
		this.outputDir = StringUtils.defaultString(outputDir).strip();
	}

	public boolean isIncludePdf()
	{
		return includePdf;
	}

	public void setIncludePdf(boolean includePdf)
	{
		this.includePdf = includePdf;
	}

	public boolean isIncludeText()
	{
		return includeText;
	}

	public void setIncludeText(boolean includeText)
	{
		this.includeText = includeText;
	}

	public boolean isIncludePcg()
	{
		return includePcg;
	}

	public void setIncludePcg(boolean includePcg)
	{
		this.includePcg = includePcg;
	}

	/**
	 * Turn a character name into a file name: characters that are not allowed in file
	 * names on Windows, macOS or Linux become '_'.
	 *
	 * @param name the character's name
	 * @return a usable base file name, without extension
	 */
	static String fileBaseName(String name)
	{
		String base = StringUtils.defaultString(name).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
		// a leading dot would hide the file, a trailing one is dropped by Windows
		base = StringUtils.strip(base, ". ");
		return base.isEmpty() ? "character" : base;
	}

	/**
	 * The exported files are named after the character's own file when it has one, so that
	 * "Ezren.pcg" gives "Ezren.pdf" and the .pcg written next to it is the file itself.
	 *
	 * @param characterFile the character's own file, or null when it was never saved
	 * @param characterName the character's name
	 * @return the base file name, without extension
	 */
	static String baseNameFor(File characterFile, String characterName)
	{
		if (characterFile != null && StringUtils.isNotBlank(characterFile.getName()))
		{
			String fileName = characterFile.getName();
			if (StringUtils.endsWithIgnoreCase(fileName, Constants.EXTENSION_CHARACTER_FILE))
			{
				fileName = fileName.substring(0, fileName.length() - Constants.EXTENSION_CHARACTER_FILE.length());
			}
			if (StringUtils.isNotBlank(fileName))
			{
				return fileName;
			}
		}
		return fileBaseName(characterName);
	}

	/**
	 * @param characterFile the character's own file, or null when it was never saved
	 * @return the folder this profile writes to for the character
	 */
	File outputFolderFor(File characterFile)
	{
		if (StringUtils.isNotBlank(outputDir))
		{
			return new File(outputDir);
		}
		if (characterFile != null && characterFile.getParentFile() != null
			&& StringUtils.isNotBlank(characterFile.getName()))
		{
			return characterFile.getParentFile();
		}
		return new File(PCGenSettings.getPcgDir());
	}

	/**
	 * The outcome of exporting one character.
	 *
	 * @param written the files written
	 * @param errors what failed, one message each
	 */
	public record Result(List<File> written, List<String> errors)
	{
	}

	/**
	 * Export the character with every format this profile includes. Existing files are
	 * overwritten. A failing format does not stop the others.
	 *
	 * @param character the character to export
	 * @return the files written and the errors
	 */
	public Result export(CharacterFacade character)
	{
		List<File> written = new ArrayList<>();
		List<String> errors = new ArrayList<>();
		String name = character.getTabNameRef().get();
		if (StringUtils.isEmpty(name))
		{
			name = character.getNameRef().get();
		}
		File ownFile = character.getFileRef().get();
		String base = baseNameFor(ownFile, name);
		File dir = outputFolderFor(ownFile);
		if (!dir.isDirectory() && !dir.mkdirs())
		{
			errors.add(name + ": cannot create folder " + dir);
			return new Result(written, errors);
		}

		if (includePcg)
		{
			File target = new File(dir, base + Constants.EXTENSION_CHARACTER_FILE);
			if (isSameFile(ownFile, target))
			{
				// the character's own file: a normal save, which also clears "unsaved changes"
				addResult(CharacterManager.saveCharacter(character), target, name, written, errors);
			}
			else if (character instanceof CharacterFacadeImpl impl)
			{
				try
				{
					impl.saveCopy(target);
					written.add(target);
				}
				catch (UncheckedIOException e)
				{
					Logging.errorPrint("Export all: could not write " + target, e);
					errors.add(name + ": could not write " + target.getName());
				}
			}
			else
			{
				errors.add(name + ": cannot save this kind of character");
			}
		}
		if (includePdf)
		{
			if (pdfSheet == null)
			{
				errors.add(name + ": no PDF sheet chosen");
			}
			else
			{
				File target = new File(dir, base + ".pdf");
				boolean ok = BatchExporter.exportCharacterToPDF(character, target, pdfSheet);
				if (ok)
				{
					Globals.executePostExportCommandPDF(target.getAbsolutePath());
				}
				addResult(ok, target, name, written, errors);
			}
		}
		if (includeText)
		{
			if (textSheet == null)
			{
				errors.add(name + ": no text sheet chosen");
			}
			else
			{
				String extension =
					ExportUtilities.getOutputExtension(textSheet.getName(), false).toLowerCase(Locale.ROOT);
				File target = new File(dir, base + '.' + extension);
				boolean ok = BatchExporter.exportCharacterToNonPDF(character, target, textSheet);
				if (ok)
				{
					Globals.executePostExportCommandStandard(target.getAbsolutePath());
				}
				addResult(ok, target, name, written, errors);
			}
		}
		return new Result(Collections.unmodifiableList(written), Collections.unmodifiableList(errors));
	}

	private static void addResult(boolean ok, File target, String name, List<File> written, List<String> errors)
	{
		if (ok)
		{
			written.add(target);
		}
		else
		{
			errors.add(name + ": could not write " + target.getName());
		}
	}

	static boolean isSameFile(File a, File b)
	{
		if (a == null || b == null || StringUtils.isBlank(a.getName()))
		{
			return false;
		}
		try
		{
			return a.getCanonicalFile().equals(b.getCanonicalFile());
		}
		catch (IOException e)
		{
			return a.getAbsoluteFile().equals(b.getAbsoluteFile());
		}
	}
}
