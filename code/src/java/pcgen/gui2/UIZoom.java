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
package pcgen.gui2;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.HierarchyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.CellRendererPane;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

import pcgen.gui3.GuiAssertions;
import pcgen.system.ConfigurationSettings;
import pcgen.util.Logging;

import org.apache.commons.lang3.SystemUtils;

/**
 * Interface zoom, primarily for HiDPI Linux desktops where Java does not pick
 * up the desktop's (often fractional) display scale and renders everything at
 * 1x.
 * <p>
 * The zoom level is a single factor (e.g. 2.25 for 225%) stored in config.ini.
 * On Linux, Java2D only honours whole-number scales, so at startup the integer
 * part is handed to Java2D ({@code sun.java2d.uiScale}) and the remainder is
 * applied by scaling the Swing look-and-feel fonts. JavaFX stages get the full
 * factor via {@code glass.gtk.uiScale}. Changing the zoom at runtime rescales
 * the Swing fonts live; the Java2D and JavaFX scales follow on next start.
 * <p>
 * Without a stored level the default is derived from the desktop DPI
 * ({@code GDK_SCALE} or the X resource {@code Xft.dpi}).
 */
public final class UIZoom
{
	/** config.ini key holding the zoom factor. */
	static final String ZOOM_KEY = "uiZoom"; //$NON-NLS-1$

	static final double MIN_ZOOM = 0.75;
	static final double MAX_ZOOM = 4.0;
	static final double STEP = 0.25;

	private static final double BASE_DPI = 96.0;
	/** Swing's default table row height for its default 12pt font. */
	private static final int BASE_ROW_HEIGHT = 16;
	/** Row height a component asked for before zoom enforced a minimum. */
	private static final String REQUESTED_ROW_HEIGHT = "pcgen.uiZoom.requestedRowHeight"; //$NON-NLS-1$
	private static final Pattern XFT_DPI = Pattern.compile("^Xft\\.dpi:\\s*([0-9.]+)", Pattern.MULTILINE); //$NON-NLS-1$

	/** Whole-number scale given to Java2D at startup; fixed for the JVM's life. */
	private static int pixelScale = 1;
	private static double zoom = 1.0;
	private static double defaultZoom = 1.0;
	/** Look-and-feel fonts before scaling, keyed by UIDefaults key. */
	private static Map<Object, Font> baseFonts;
	private static boolean adjustingRowHeight;

	private UIZoom()
	{
	}

	/**
	 * Resolve the zoom level and configure the Java2D/JavaFX scale properties.
	 * Must run after config.ini is loaded and before AWT or JavaFX initialise.
	 */
	public static void initialize()
	{
		if (!SystemUtils.IS_OS_LINUX)
		{
			// Windows and macOS apply the display scale themselves; zoom is relative to it.
			zoom = storedZoom().orElse(1.0);
			return;
		}
		defaultZoom = detectDesktopZoom();
		zoom = storedZoom().orElse(defaultZoom);

		String explicitScale = System.getProperty("sun.java2d.uiScale"); //$NON-NLS-1$
		OptionalDouble gdkScale = parseScale(System.getenv("GDK_SCALE")); //$NON-NLS-1$
		if (explicitScale != null)
		{
			pixelScale = (int) Math.max(1, parseScale(explicitScale).orElse(1));
		}
		else if (gdkScale.isPresent())
		{
			// Java honours GDK_SCALE natively
			pixelScale = (int) Math.max(1, gdkScale.getAsDouble());
		}
		else
		{
			pixelScale = (int) Math.max(1, Math.floor(zoom));
			System.setProperty("sun.java2d.uiScale", Integer.toString(pixelScale)); //$NON-NLS-1$
		}
		if (System.getProperty("glass.gtk.uiScale") == null) //$NON-NLS-1$
		{
			System.setProperty("glass.gtk.uiScale", Double.toString(zoom)); //$NON-NLS-1$
		}
		Logging.log(Level.INFO, String.format(Locale.ROOT, "UI zoom %.0f%% (Java2D scale %d, font scale %.3f)", //$NON-NLS-1$
			zoom * 100, pixelScale, fontFactor()));
	}

	/**
	 * Scale the Swing look-and-feel fonts and start tracking table/tree row
	 * heights. Call on the Swing thread once the look and feel is installed and
	 * before the main window is built.
	 */
	public static void installSwingZoom()
	{
		applyFonts();
		Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
			if (event instanceof HierarchyEvent he
				&& (he.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
				&& he.getComponent().isShowing())
			{
				trackRowHeight(he.getComponent());
			}
		}, AWTEvent.HIERARCHY_EVENT_MASK);
	}

	public static double getZoom()
	{
		return zoom;
	}

	public static void zoomIn()
	{
		setZoom(zoom + STEP);
	}

	public static void zoomOut()
	{
		setZoom(zoom - STEP);
	}

	/** Return to the desktop-derived default and stop storing an explicit level. */
	public static void resetZoom()
	{
		setZoom(defaultZoom);
		ConfigurationSettings.setSystemProperty(ZOOM_KEY, null);
	}

	private static void setZoom(double newZoom)
	{
		GuiAssertions.assertIsSwingThread();
		double clamped = clamp(roundToStep(newZoom));
		if (clamped == zoom)
		{
			return;
		}
		zoom = clamped;
		ConfigurationSettings.setSystemProperty(ZOOM_KEY, Double.toString(zoom));
		applyFonts();
		for (Window window : Window.getWindows())
		{
			SwingUtilities.updateComponentTreeUI(window);
			updateRowHeights(window);
		}
	}

	static double fontFactor()
	{
		return zoom / pixelScale;
	}

	private static void applyFonts()
	{
		if (baseFonts == null)
		{
			if (fontFactor() == 1.0)
			{
				// Nothing to scale yet; leave the look and feel's own fonts untouched
				// (e.g. native Aqua fonts on macOS at the default zoom).
				return;
			}
			baseFonts = new HashMap<>();
			UIDefaults defaults = UIManager.getLookAndFeelDefaults();
			for (Object key : Collections.list(defaults.keys()))
			{
				if (defaults.get(key) instanceof Font font)
				{
					baseFonts.put(key, font);
				}
			}
		}
		float factor = (float) fontFactor();
		baseFonts.forEach((key, font) -> UIManager.put(key, new FontUIResource(font.deriveFont(font.getSize2D() * factor))));
	}

	private static void updateRowHeights(Component component)
	{
		trackRowHeight(component);
		if (component instanceof Container container)
		{
			for (Component child : container.getComponents())
			{
				updateRowHeights(child);
			}
		}
	}

	/**
	 * Keep a table's or tree's row height at least as tall as the scaled font
	 * needs. Row heights hard-coded for the default font would otherwise clip.
	 */
	private static void trackRowHeight(Component component)
	{
		if (!(component instanceof JTable || component instanceof JTree)
			// a JTreeTable's renderer tree follows its table
			|| SwingUtilities.getAncestorOfClass(CellRendererPane.class, component) != null)
		{
			return;
		}
		JComponent c = (JComponent) component;
		if (c.getClientProperty(REQUESTED_ROW_HEIGHT) == null)
		{
			c.putClientProperty(REQUESTED_ROW_HEIGHT, getRowHeight(c));
			c.addPropertyChangeListener("rowHeight", e -> { //$NON-NLS-1$
				if (!adjustingRowHeight)
				{
					c.putClientProperty(REQUESTED_ROW_HEIGHT, e.getNewValue());
					applyRowHeight(c);
				}
			});
		}
		applyRowHeight(c);
	}

	private static void applyRowHeight(JComponent c)
	{
		int requested = (Integer) c.getClientProperty(REQUESTED_ROW_HEIGHT);
		if (requested <= 0)
		{
			// variable-height tree rows size themselves from the renderer
			return;
		}
		int height = Math.max(requested, minimumRowHeight());
		if (height == getRowHeight(c))
		{
			return;
		}
		adjustingRowHeight = true;
		try
		{
			if (c instanceof JTable table)
			{
				table.setRowHeight(height);
			}
			else
			{
				((JTree) c).setRowHeight(height);
			}
		}
		finally
		{
			adjustingRowHeight = false;
		}
	}

	private static int getRowHeight(JComponent c)
	{
		return c instanceof JTable table ? table.getRowHeight() : ((JTree) c).getRowHeight();
	}

	static int minimumRowHeight()
	{
		return (int) Math.ceil(BASE_ROW_HEIGHT * fontFactor());
	}

	private static OptionalDouble storedZoom()
	{
		OptionalDouble stored = parseScale(ConfigurationSettings.getSystemProperty(ZOOM_KEY));
		return stored.isPresent() ? OptionalDouble.of(clamp(stored.getAsDouble())) : stored;
	}

	private static double detectDesktopZoom()
	{
		OptionalDouble gdkScale = parseScale(System.getenv("GDK_SCALE")); //$NON-NLS-1$
		if (gdkScale.isPresent())
		{
			return clamp(gdkScale.getAsDouble());
		}
		OptionalDouble dpi = parseXftDpi(queryXResources());
		return dpi.isPresent() ? clamp(roundToStep(dpi.getAsDouble() / BASE_DPI)) : 1.0;
	}

	private static String queryXResources()
	{
		try
		{
			Process process = new ProcessBuilder("xrdb", "-query").redirectErrorStream(true).start(); //$NON-NLS-1$ //$NON-NLS-2$
			try (InputStream in = process.getInputStream())
			{
				String output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				process.waitFor(2, TimeUnit.SECONDS);
				return output;
			}
		}
		catch (IOException e)
		{
			Logging.debugPrint("Could not query X resources for Xft.dpi", e); //$NON-NLS-1$
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		return "";
	}

	static OptionalDouble parseXftDpi(String xrdbOutput)
	{
		Matcher m = XFT_DPI.matcher(xrdbOutput);
		return m.find() ? parseScale(m.group(1)) : OptionalDouble.empty();
	}

	/**
	 * Parse a scale such as "2", "2.25", "2x" or "225%".
	 */
	static OptionalDouble parseScale(String value)
	{
		if (value == null || value.isBlank())
		{
			return OptionalDouble.empty();
		}
		String s = value.trim().toLowerCase(Locale.ROOT);
		double divisor = 1.0;
		if (s.endsWith("%")) //$NON-NLS-1$
		{
			s = s.substring(0, s.length() - 1);
			divisor = 100.0;
		}
		else if (s.endsWith("x")) //$NON-NLS-1$
		{
			s = s.substring(0, s.length() - 1);
		}
		try
		{
			double parsed = Double.parseDouble(s) / divisor;
			return parsed > 0 && Double.isFinite(parsed) ? OptionalDouble.of(parsed) : OptionalDouble.empty();
		}
		catch (NumberFormatException e)
		{
			return OptionalDouble.empty();
		}
	}

	static double roundToStep(double value)
	{
		return Math.round(value / STEP) * STEP;
	}

	static double clamp(double value)
	{
		return Math.clamp(value, MIN_ZOOM, MAX_ZOOM);
	}
}
