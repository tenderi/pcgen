/*
 * Copyright 2012 Vincent Lhote
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
package pcgen.gui2.tools;

import java.awt.Desktop;
import java.awt.Desktop.Action;
import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.URI;

import pcgen.gui3.GuiUtility;
import pcgen.system.LanguageBundle;
import pcgen.util.Logging;

import org.apache.commons.lang3.SystemUtils;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;

/**
 * Provide a utility method to open files with {@link Desktop}.
 */
public final class DesktopBrowserLauncher
{

	private static final Desktop DESKTOP = Desktop.getDesktop();

	private DesktopBrowserLauncher()
	{
	}

	/**
	 * View a file (should be browsable) in a browser.
	 *
	 * @param file Path of the file to display in browser.
	 * @throws IOException if file doesn't exist
	 */
	public static void viewInBrowser(File file) throws IOException
	{
		viewInBrowser(file.toURI());
	}

	/**
	 * View a URI in a browser
	 * p.s. JDK 20 will deprecate all public constructors of java.net.URL
	 *
	 * @param uri URI to display in browser.
	 * @throws IOException if the URL is bad or the browser can not be launched
	 */
	@SuppressWarnings("ThrowInsideCatchBlockWhichIgnoresCaughtException")
	public static void viewInBrowser(URI uri) throws IOException
	{
		if (Desktop.isDesktopSupported() && DESKTOP.isSupported(Action.BROWSE))
		{
			DESKTOP.browse(uri);
		}
		else if (!openWithFallback(uri))
		{
			Logging.debugPrint("Unable to browse to " + uri);
			// callers include JavaFX handlers (About dialog) as well as Swing ones
			GuiUtility.runOnJavaFXThreadAndWait(() -> {
				Dialog<ButtonType> alert = new Alert(Alert.AlertType.WARNING);
				alert.setTitle(LanguageBundle.getString("in_err_browser_err"));
				alert.setContentText(LanguageBundle.getFormattedString("in_err_browser_uri", uri));
				return alert.showAndWait();
			});
		}
	}

	/**
	 * Some Linux desktops (e.g. KDE Plasma under XWayland) report BROWSE as
	 * unsupported even though xdg-open, and Desktop OPEN for local files, work.
	 *
	 * @param uri URI to display
	 * @return true if a handler was launched
	 */
	private static boolean openWithFallback(URI uri)
	{
		if (SystemUtils.IS_OS_LINUX)
		{
			try
			{
				new ProcessBuilder("xdg-open", uri.toString()) //$NON-NLS-1$
					.redirectOutput(Redirect.DISCARD)
					.redirectError(Redirect.DISCARD)
					.start();
				return true;
			}
			catch (IOException e)
			{
				Logging.debugPrint("xdg-open failed for " + uri, e); //$NON-NLS-1$
			}
		}
		if ("file".equals(uri.getScheme()) && Desktop.isDesktopSupported() //$NON-NLS-1$
			&& DESKTOP.isSupported(Action.OPEN))
		{
			try
			{
				DESKTOP.open(new File(uri));
				return true;
			}
			catch (IOException | IllegalArgumentException e)
			{
				Logging.debugPrint("Desktop open failed for " + uri, e); //$NON-NLS-1$
			}
		}
		return false;
	}
}
