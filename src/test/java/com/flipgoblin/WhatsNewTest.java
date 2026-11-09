package com.flipgoblin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WhatsNewTest
{
	@Test
	public void freshInstallSeesNothing()
	{
		assertFalse(WhatsNew.shouldShow(null, false));
		assertFalse(WhatsNew.shouldShow("", false));
	}

	@Test
	public void olderVersionUserSeesTheFirstNotes()
	{
		assertTrue(WhatsNew.shouldShow(null, true));
	}

	@Test
	public void showsOncePerNotes()
	{
		assertTrue(WhatsNew.shouldShow("2026.10.21", true));
		assertFalse(WhatsNew.shouldShow(WhatsNew.ID, true));
	}
}
