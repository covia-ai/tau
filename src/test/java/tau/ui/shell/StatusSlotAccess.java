package tau.ui.shell;

import tau.ui.character.CharacterArt;

/** A status slot with art of a test's own, without changing the app's (test classes run side by side). */
public final class StatusSlotAccess {

	private StatusSlotAccess() {
	}

	public static StatusSlot withArt(CharacterArt art) {
		return new StatusSlot(art);
	}
}
