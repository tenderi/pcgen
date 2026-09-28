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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;

class UIZoomTest
{
	@Test
	void parseScaleAcceptsPlainPercentAndSuffixForms()
	{
		assertEquals(OptionalDouble.of(2.0), UIZoom.parseScale("2"));
		assertEquals(OptionalDouble.of(2.25), UIZoom.parseScale(" 2.25 "));
		assertEquals(OptionalDouble.of(2.0), UIZoom.parseScale("2x"));
		assertEquals(OptionalDouble.of(2.25), UIZoom.parseScale("225%"));
	}

	@Test
	void parseScaleRejectsJunk()
	{
		assertTrue(UIZoom.parseScale(null).isEmpty());
		assertTrue(UIZoom.parseScale("").isEmpty());
		assertTrue(UIZoom.parseScale("big").isEmpty());
		assertTrue(UIZoom.parseScale("0").isEmpty());
		assertTrue(UIZoom.parseScale("-1").isEmpty());
		assertTrue(UIZoom.parseScale("NaN").isEmpty());
	}

	@Test
	void parseXftDpiFindsTheDpiLine()
	{
		String xrdb = "Xcursor.size:\t48\nXft.antialias:\t1\nXft.dpi:\t216\nXft.hinting:\t1\n";
		assertEquals(OptionalDouble.of(216), UIZoom.parseXftDpi(xrdb));
		assertTrue(UIZoom.parseXftDpi("Xcursor.size:\t48\n").isEmpty());
		assertTrue(UIZoom.parseXftDpi("").isEmpty());
	}

	@Test
	void zoomIsRoundedToStepsAndClamped()
	{
		assertEquals(2.25, UIZoom.roundToStep(216 / 96.0));
		assertEquals(1.5, UIZoom.roundToStep(1.4));
		assertEquals(UIZoom.MIN_ZOOM, UIZoom.clamp(0.1));
		assertEquals(UIZoom.MAX_ZOOM, UIZoom.clamp(10));
		assertEquals(2.25, UIZoom.clamp(2.25));
	}
}
