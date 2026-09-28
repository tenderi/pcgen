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
package pcgen.core.bonus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import pcgen.rules.context.LoadContext;

import org.junit.jupiter.api.Test;

/**
 * A bonus must be computed after the bonuses to every variable its formula
 * reads, so the formula's variable names have to be found reliably.
 */
class BonusObjDependencyTest
{
	private static BonusObj bonusWithFormula(String formula)
	{
		BonusObj bonus = new BonusObj()
		{
			@Override
			protected boolean parseToken(LoadContext context, String token)
			{
				return true;
			}

			@Override
			protected String unparseToken(Object obj)
			{
				return "";
			}

			@Override
			public String getBonusHandled()
			{
				return "VAR";
			}
		};
		bonus.setValue(formula);
		return bonus;
	}

	/** Pathfinder's Rakshasa bloodline level 3 power (Mind Reader) was randomly missing. */
	@Test
	void findsVariablesAroundLogicalAnd()
	{
		BonusObj bonus = bonusWithFormula(
			"if((Sorcerer_CF_BloodlinePower3==0&&Sorcerer_Rakshasa_BloodlineProgressionLVL>=3),1,0)");

		assertTrue(bonus.getDependsOn("SORCERER_RAKSHASA_BLOODLINEPROGRESSIONLVL"));
		assertTrue(bonus.getDependsOn("SORCERER_CF_BLOODLINEPOWER3"));
		assertFalse(bonus.getDependsOn("0&&SORCERER_RAKSHASA_BLOODLINEPROGRESSIONLVL"));
	}

	@Test
	void findsVariablesAroundLogicalOrNotAndSpaces()
	{
		BonusObj bonus = bonusWithFormula("if((A>1||!B) && C == 2, D + 1, 0)");

		for (String variable : new String[]{"A", "B", "C", "D"})
		{
			assertTrue(bonus.getDependsOn(variable), variable);
		}
	}
}
