package com.flipgoblin;

import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;

/**
 * Widget text scan: {@link #findText} walks a loaded group's whole tree for a text needle —
 * the welcome-screen custody parser's lookup (never a hardcoded deep widget path). Client
 * thread only.
 */
final class WidgetCatalog
{

	/** Groups have no child-count API; probing this many root children covers every interface. */
	private static final int MAX_CHILDREN = 512;

	private WidgetCatalog()
	{
	}


	/**
	 * The FULL text of the first widget in the group whose text contains the needle
	 * (case-insensitive), or null. Feeds the custody parser (needle "last logged in") — scanning
	 * the group survives client layout changes that would break a hardcoded deep widget path.
	 */
	static String findText(Client client, int groupId, String needle)
	{
		for (int child = 0; child < MAX_CHILDREN; child++)
		{
			Widget w = client.getWidget(groupId, child);
			if (w != null)
			{
				String found = subtreeFindText(w, needle);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static String subtreeFindText(Widget w, String needle)
	{
		String text = w.getText();
		if (text != null && text.toLowerCase().contains(needle))
		{
			return text;
		}
		String found = anyFindText(w.getStaticChildren(), needle);
		if (found == null)
		{
			found = anyFindText(w.getDynamicChildren(), needle);
		}
		return found != null ? found : anyFindText(w.getNestedChildren(), needle);
	}

	private static String anyFindText(Widget[] children, String needle)
	{
		if (children == null)
		{
			return null;
		}
		for (Widget c : children)
		{
			if (c != null)
			{
				String found = subtreeFindText(c, needle);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

}
