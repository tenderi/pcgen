package pcgen.gui3.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import pcgen.core.GameMode;
import pcgen.core.SettingsHandler;
import pcgen.gui2.UIPropertyContext;
import pcgen.system.LanguageBundle;

import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TitledPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The Export dialog's "Export All" section loads from the FXML, shows the game mode's
 * default sheets and remembers changes per game mode.
 */
@ExtendWith(ApplicationExtension.class)
class ExportDialogExportAllTest
{
	private static final String MODE = "ExportAllTestMode";
	private static GameMode previousMode;

	@Start
	private void start(Stage stage) throws IOException
	{
		previousMode = SettingsHandler.getGameAsProperty().get();
		GameMode mode = new GameMode(MODE);
		mode.setOutputSheetDirectory("d20/fantasy");
		mode.setOutputSheetDefault("PDF", "csheet_fantasy_std_blue.xslt");
		mode.setOutputSheetDefault("TXT", "csheet_plain.TXT");
		SettingsHandler.getGameAsProperty().set(mode);

		FXMLLoader loader = new FXMLLoader();
		URL resource = ExportDialogController.class.getResource("ExportDialog.fxml");
		loader.setLocation(resource);
		LanguageBundle.getString("");
		loader.setResources(LanguageBundle.getBundle());
		Scene scene = loader.load();
		stage.setScene(scene);
		stage.show();
	}

	@AfterAll
	static void restore()
	{
		SettingsHandler.getGameAsProperty().set(previousMode);
	}

	@Test
	void showsTheGameModeDefaultsAndRemembersChanges(FxRobot robot)
	{
		TitledPane pane = robot.lookup("#exportAllPane").queryAs(TitledPane.class);
		assertTrue(pane.getText().contains(MODE), pane.getText());

		@SuppressWarnings("unchecked")
		ComboBox<File> pdf = robot.lookup("#exportAllPdfSheet").queryAs(ComboBox.class);
		@SuppressWarnings("unchecked")
		ComboBox<File> text = robot.lookup("#exportAllTextSheet").queryAs(ComboBox.class);
		assertEquals("csheet_fantasy_std_blue.xslt", pdf.getValue().getName());
		assertEquals("csheet_plain.TXT", text.getValue().getName());
		assertEquals("pdf/csheet_fantasy_std_blue.xslt", pdf.getConverter().toString(pdf.getValue()));
		assertTrue(pdf.getItems().stream().allMatch(f -> f.getParentFile().getName().equals("pdf")));
		assertTrue(text.getItems().stream().anyMatch(f -> f.getName().endsWith(".htm")),
			"the text slot also offers HTML sheets");

		CheckBox includePdf = robot.lookup("#exportAllPdf").queryAs(CheckBox.class);
		assertTrue(includePdf.isSelected());
		// no characters are open
		assertTrue(robot.lookup("#doExportAll").queryAs(Button.class).isDisabled());

		robot.interact(() -> includePdf.setSelected(false));
		WaitForAsyncUtils.waitForFxEvents();
		assertTrue(pdf.isDisabled(), "the sheet is disabled with its format");
		assertFalse(UIPropertyContext.createContext("ExportAll").getBoolean(MODE + ".includePdf", true),
			"the change is remembered for the game mode");
		assertEquals("d20/fantasy/text/csheet_plain.TXT",
			UIPropertyContext.createContext("ExportAll").getProperty(MODE + ".textSheet"));
	}
}
