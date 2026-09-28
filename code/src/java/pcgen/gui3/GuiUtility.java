/*
 * Copyright 2019 (C) Eitan Adler <lists@eitanadler.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.     See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package pcgen.gui3;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import pcgen.system.ConfigurationSettings;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Parent;
import javafx.scene.Scene;

public final class GuiUtility
{
	private GuiUtility()
	{
	}

	/**
	 * During the conversion to JavaFX we have a mix of JavaFX and Swing components
	 * This provides a way to convert from JavaFX to Swing.
	 * Note that the painting happens "eventually" and thus when the function returned
	 * there is no guarantee that the component has any size yet.
	 * @param parent a javafx Parent to be shown as a swing node
	 * @return a jfxpanel that eventually gets painted as the parent container
	 */
	public static JFXPanel wrapParentAsJFXPanel(Parent parent)
	{
		GuiAssertions.assertIsNotJavaFXThread();
		JFXPanel jfxPanel = new JFXPanel();
		Platform.runLater(() -> {
			Scene scene = new Scene(parent);
			jfxPanel.setScene(scene);
		});
		return jfxPanel;
	}

	public static <T> T runOnJavaFXThreadNow(Supplier<T> supplier)
	{
		GuiAssertions.assertIsNotJavaFXThread();
		return CompletableFuture.supplyAsync(supplier, Platform::runLater).join();
	}

	/**
	 * Like {@link #runOnJavaFXThreadNow(Supplier)} but callable from any thread,
	 * including the JavaFX thread itself (where it just runs the supplier).
	 */
	public static <T> T runOnJavaFXThreadAndWait(Supplier<T> supplier)
	{
		return Platform.isFxApplicationThread() ? supplier.get() : runOnJavaFXThreadNow(supplier);
	}

	/**
	 * JavaFX file and directory choosers throw IllegalArgumentException, so the
	 * button that opens them appears to do nothing, when the initial directory is
	 * not an existing folder (a stale setting, a blank path, a folder not created
	 * yet). Use this to sanitise every initial directory.
	 *
	 * @param dir the preferred initial directory, may be null or blank
	 * @return the nearest existing directory at or above {@code dir}, or null to
	 *         let the platform choose its default
	 */
	public static File existingInitialDirectory(File dir)
	{
		if ((dir == null) || dir.getPath().isBlank())
		{
			return null;
		}
		return ConfigurationSettings.nearestExistingDir(dir.getAbsolutePath()).map(Path::toFile).orElse(null);
	}
}
