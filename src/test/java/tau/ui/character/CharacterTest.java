package tau.ui.character;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.swing.JLabel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tau.ui.character.Presence.Beat;
import tau.ui.character.Presence.Look;
import tau.ui.character.Presence.Moment;
import tau.ui.components.EmptyState;
import tau.ui.components.Tone;

/**
 * The character's mechanism, with a stand-in pack of plain shapes made here
 * (the real art is never in this repository): which pose each beat shows,
 * the fallback to the τ glyph with no art, sizes below 32 px, reduced and no
 * motion, the catch, and that the never-list screens have no way to show it.
 */
class CharacterTest {

	@TempDir
	Path dir;

	@Test
	void eachBeatShowsItsSignedPose() {
		assertEquals("rest", Presence.look(Beat.IDLE).pose());
		assertEquals("thinking", Presence.look(Beat.THINKING).pose(), "the chin glove");
		assertEquals("waiting", Presence.look(Beat.WAITING).pose(), "the clasp");
		assertTrue(Presence.look(Beat.WAITING).warm());
		assertEquals("asleep", Presence.look(Beat.ASLEEP).pose());
		assertEquals("waiting", Presence.look(Beat.TIMED_OUT).pose(), "a waiting-type pose, calm");
		Look done = Presence.look(Beat.DONE);
		assertEquals("rest", done.pose());
		assertEquals(Moment.HOP, done.moment());
		assertTrue(done.squash(), "the pills squash happily");
		Look milestone = Presence.look(Beat.MILESTONE);
		assertEquals("excited", milestone.pose(), "excited at a milestone only");
		assertEquals(Moment.JUMP, milestone.moment());
		assertEquals(Moment.CATCH, Presence.look(Beat.NEW_REQUEST).moment());
		for (Beat b : Beat.values()) {
			if (b != Beat.MILESTONE) assertFalse("excited".equals(Presence.look(b).pose()), b + " is not excited");
		}
		for (Beat b : List.of(Beat.PAUSED, Beat.CANCELLED, Beat.FAILED, Beat.REFUSED, Beat.OFFLINE, Beat.LOCKED)) {
			assertFalse(Presence.look(b).character(), b + ": the glyph and a badge, no character");
		}
	}

	@Test
	void withoutArtEverySlotFallsBackToTheGlyph() throws IOException {
		assertSame(CharacterArt.NONE, CharacterArt.load(dir.resolve("absent")), "a missing pack");
		Files.writeString(dir.resolve("character.json"), "{ not json");
		try (var logs = tau.ExpectedLogs.warn(CharacterArt.class, "Could not read the character pack")) {
			assertSame(CharacterArt.NONE, CharacterArt.load(dir), "an unreadable pack");
		}
		Files.writeString(dir.resolve("character.json"), "{\"poses\": {\"thinking\": {\"base\": \"x.png\"}}}");
		try (var logs = tau.ExpectedLogs.warn(CharacterArt.class, "The character pack has no rest pose")) {
			assertSame(CharacterArt.NONE, CharacterArt.load(dir), "a pack without a rest pose");
		}

		for (int size : new int[] { 24, 32, 96, 104, 160, 176 }) {
			CharacterView v = view(size, CharacterArt.NONE, Motion.FULL, new AtomicLong());
			for (Beat b : Beat.values()) {
				v.setBeat(b);
				assertFalse(v.showsCharacter(), size + " px, " + b);
				paint(v, size);
			}
		}
		// The greeting slots keep today's plain τ.
		assertTrue(CharacterView.orMarkFor(104, CharacterArt.NONE) instanceof JLabel);
		assertFalse(CharacterArt.get().present(), "the build and its tests run without the art");
		EmptyState empty = new EmptyState("inbox", Tone.MUTED, "Nothing is waiting for you", "Words").withMark(104);
		assertFalse(contains(empty, CharacterView.class), "an empty state keeps the plain τ");
	}

	@Test
	void withArtTheCharacterShowsFrom32PxAndTheGlyphBelow() throws IOException {
		CharacterArt art = pack();
		assertTrue(art.present());
		AtomicLong now = new AtomicLong(1_000_000);
		assertFalse(view(24, art, Motion.FULL, now).showsCharacter(), "the glyph only below 32 px");
		CharacterView slot = view(32, art, Motion.FULL, now);
		assertTrue(slot.showsCharacter());
		assertEquals("rest", slot.pose());
		slot.setBeat(Beat.THINKING);
		assertEquals("thinking", slot.pose());
		slot.setBeat(Beat.REFUSED);
		now.addAndGet(CharacterView.DWELL);
		slot.tick();
		assertFalse(slot.showsCharacter(), "a refusal: the character leaves the view");
		assertNotNull(art.pose("rest"));
		assertNull(art.pose("excited"), "a pose the pack lacks");
		CharacterView figure = view(96, art, Motion.FULL, now);
		figure.setBeat(Beat.MILESTONE);
		assertFalse(figure.showsCharacter(), "a pose the pack lacks falls back to the glyph");
		paint(slot, 32);
		paint(figure, 96);
	}

	@Test
	void theGlovesCatchTheSparkThenRest() throws IOException {
		CharacterArt art = pack();
		AtomicLong now = new AtomicLong(1_000_000);
		CharacterView v = view(96, art, Motion.FULL, now);
		v.setBeat(Beat.NEW_REQUEST);
		assertEquals("waiting", v.showing(), "the gloves close on the spark: a crossfade to the signed clasp");
		assertEquals("rest", v.pose(), "rest once the catch is over");
		assertTrue(v.moving(now.get()));
		now.addAndGet(Motion.FULL.crossfadeMillis() + CharacterView.CATCH_HOLD + 1);
		v.tick();
		assertEquals("rest", v.showing());
		paint(v, 96);
		// At 32 px nothing is caught: the slot only crossfades and blinks.
		CharacterView slot = view(32, art, Motion.FULL, now);
		slot.setBeat(Beat.NEW_REQUEST);
		assertEquals("rest", slot.showing());
		// Reduced: no catch either.
		CharacterView reduced = view(96, art, Motion.REDUCED, now);
		reduced.setBeat(Beat.NEW_REQUEST);
		assertEquals("rest", reduced.showing());
	}

	@Test
	void reducedMotionHoldsAStillPosePerBeat() throws IOException {
		CharacterArt art = pack();
		AtomicLong now = new AtomicLong(1_000_000);
		CharacterView full = view(160, art, Motion.FULL, now);
		CharacterView reduced = view(160, art, Motion.REDUCED, now);
		CharacterView off = view(160, art, Motion.OFF, now);
		for (CharacterView v : List.of(full, reduced, off)) v.setBeat(Beat.THINKING);
		assertTrue(reduced.moving(now.get()), "reduced keeps the crossfade");
		assertFalse(off.moving(now.get()), "off swaps stills at once");
		long later = now.get() + Motion.FULL.crossfadeMillis() + 1;
		assertFalse(reduced.moving(later), "then holds still: no breathing, no blink");
		assertTrue(full.moving(later), "full breathes and blinks");
		for (int i = 0; i < 90; i++) {
			now.addAndGet(CharacterView.FRAME_MILLIS);
			full.tick();
		}
		assertFalse(full.moving(now.get() + CharacterView.SETTLE + 1), "until a minute with no beat settles it");
		reduced.setBeat(Beat.DONE);
		now.addAndGet(CharacterView.DWELL);
		reduced.tick();
		assertFalse(reduced.moving(now.get() + Motion.REDUCED.crossfadeMillis() + 1), "no hop");
		assertFalse(Sparks.flies(Motion.REDUCED));
		assertFalse(Sparks.flies(Motion.OFF));
		assertTrue(Sparks.flies(Motion.FULL));
		assertEquals(0, Motion.OFF.crossfadeMillis());
	}

	@Test
	void aBeatReplacedWithinItsDwellWaitsItsTurn() throws IOException {
		AtomicLong now = new AtomicLong(1_000_000);
		CharacterView v = view(96, pack(), Motion.FULL, now);
		v.setBeat(Beat.THINKING);
		v.setBeat(Beat.WORKING);
		assertEquals(Beat.THINKING, v.beat(), "thinking shows for its dwell first");
		now.addAndGet(CharacterView.DWELL);
		v.tick();
		assertEquals(Beat.WORKING, v.beat());
	}

	@Test
	void theNeverListScreensHaveNoWayToShowTheCharacter() throws IOException {
		// Only these may make the character: the slots of R8 (Chats, the sidebar status, first launch,
		// the greeting empty states, About). The Record, the Vault, the Constellation, Rooms, agent rows
		// and profiles, refusals, and errors are not among them, and the empty states they show have no mark.
		Set<String> allowed = Set.of("ui/chat/ChatPanel.java", "ui/chat/EmptyChatState.java", "ui/shell/StatusSlot.java",
			"ui/onboarding/OnboardingWizard.java", "ui/MainWindow.java", "ui/components/EmptyState.java");
		Set<String> neverMarked = Set.of("ui/rooms/", "ui/vault/", "ui/record/", "ui/peers/", "ui/agents/",
			"ui/inspect/", "ui/settings/AccessPanel.java");
		Path main = Path.of("src/main/java/tau");
		try (Stream<Path> files = Files.walk(main)) {
			for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				String rel = main.relativize(f).toString().replace('\\', '/');
				if (rel.startsWith("ui/character/")) continue;
				String src = Files.readString(f);
				// Saying where the pack is (Tau.java) shows nothing; making a view or reading the art does.
				if (src.contains("CharacterView") || src.contains("CharacterArt.get()")) {
					assertTrue(allowed.contains(rel), rel + " makes the character but is not one of its slots");
				}
				if (src.contains(".withMark(")) {
					for (String never : neverMarked) assertFalse(rel.startsWith(never), rel + " puts the mark on the never-list");
				}
			}
		}
		// The status slot shows the glyph alone while a quiet screen (Rooms, the Record, the Vault...) is open.
		CharacterArt art = pack();
		tau.ui.shell.StatusSlot slot = tau.ui.shell.StatusSlotAccess.withArt(art);
		assertTrue(slot.showsMoreThanGlyph(), "the 32 px character on an ordinary screen");
		slot.setQuietScreenOpen(true);
		assertFalse(slot.showsMoreThanGlyph(), "the glyph alone on a quiet screen");
		assertFalse(contains(slot, CharacterView.class));
		slot.setQuietScreenOpen(false);
		slot.setCollapsed(true);
		assertFalse(slot.showsMoreThanGlyph(), "collapsed, below 32 px of room: the glyph");
	}

	// ------------------------------------------------------------------

	private static CharacterView view(int size, CharacterArt art, Motion motion, AtomicLong now) {
		CharacterView v = CharacterView.withArt(size, art);
		v.testWith(now::get, art, motion);
		return v;
	}

	/** A stand-in pack: plain shapes for rest, thinking, waiting, and asleep (no excited), eyes on two. */
	private CharacterArt pack() throws IOException {
		for (String pose : List.of("rest", "thinking", "waiting", "asleep")) {
			BufferedImage b = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
			Graphics2D g = b.createGraphics();
			g.setColor(new Color(0x6B, 0x46, 0xC1));
			g.fillRoundRect(16, 8, 32, 48, 12, 12);
			g.dispose();
			ImageIO.write(b, "png", dir.resolve(pose + ".png").toFile());
		}
		BufferedImage eye = new BufferedImage(6, 10, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = eye.createGraphics();
		g.setColor(Color.WHITE);
		g.fillRect(2, 2, 2, 6);
		g.dispose();
		ImageIO.write(eye, "png", dir.resolve("eye.png").toFile());
		String eyes = "\"eyes\": [{\"box\": [0.40, 0.30, 0.48, 0.45], \"sprite\": \"eye.png\"}, "
			+ "{\"box\": [0.52, 0.30, 0.60, 0.45], \"sprite\": \"eye.png\"}]";
		Files.writeString(dir.resolve("character.json"), "{\"version\": 1, \"size\": 64, \"poses\": {"
			+ "\"rest\": {\"base\": \"rest.png\", " + eyes + "}, \"thinking\": {\"base\": \"thinking.png\", " + eyes + "}, "
			+ "\"waiting\": {\"base\": \"waiting.png\", " + eyes + "}, \"asleep\": {\"base\": \"asleep.png\"}}}");
		return CharacterArt.load(dir);
	}

	private static void paint(CharacterView v, int size) {
		v.setSize(size, size);
		BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try {
			v.paint(g);
		} finally {
			g.dispose();
		}
	}

	private static boolean contains(Component c, Class<?> type) {
		if (type.isInstance(c)) return true;
		if (c instanceof Container k) for (Component child : k.getComponents()) if (contains(child, type)) return true;
		return false;
	}
}
