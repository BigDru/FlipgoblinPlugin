package com.flipgoblin;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class GpTest
{
	@Test
	public void shortForm_tiers()
	{
		assertEquals("950", Gp.shortForm(950));
		assertEquals("23.5k", Gp.shortForm(23_500));
		assertEquals("1.4m", Gp.shortForm(1_400_000));
		assertEquals("999.9m", Gp.shortForm(999_900_000));
		// The billions tier starts where the m tier would round up to "1000.0m".
		assertEquals("1.0b", Gp.shortForm(999_950_000));
		assertEquals("5.0b", Gp.shortForm(5_000_000_000L));
		assertEquals("-2.1b", Gp.shortForm(-2_147_483_647L));
	}

	@Test
	public void exact_aboveMaxCash()
	{
		assertEquals("5,000,000,000", Gp.exact(5_000_000_000L));
	}
}
