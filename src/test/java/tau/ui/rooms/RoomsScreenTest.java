package tau.ui.rooms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.sun.net.httpserver.HttpServer;

import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import covia.adapter.token.TokenAdapter;
import covia.grid.Principals;
import covia.venue.Engine;
import covia.venue.RequestContext;
import tau.AppConfig;
import tau.Identity;
import tau.SharedVenue;
import tau.TokenPreset;
import tau.Tokens;
import tau.chat.ChatSession;
import tau.rooms.Rooms;
import tau.ui.components.TauMark;

/**
 * The Rooms screen's mechanism, headless on a real venue: what others wrote is
 * never HTML and fetches nothing; hidden characters show as markers while
 * copying keeps the text; the author line comes from the record; new posts
 * never move a reader who scrolled up; paging back reaches the pruned line;
 * owner actions only for the owner; a refusal names only the refused ability;
 * two agents answering each other raise the banner and the owner can cancel
 * their waits and stop telling; a flood stays responsive, folds long posts,
 * and notifies once; no character anywhere; and the panes at each width.
 */
class RoomsScreenTest {

	@RegisterExtension
	static final SharedVenue shared = new SharedVenue();

	private static final long WAIT = 20;

	private final List<String> notified = Collections.synchronizedList(new ArrayList<>());
	private final List<String> opened = Collections.synchronizedList(new ArrayList<>());
	private final List<RoomsScreen> screens = new ArrayList<>();

	@AfterEach
	void closeScreens() throws Exception {
		edt(() -> {
			for (RoomsScreen s : screens) s.follow(null);
			return null;
		});
	}

	// ------------------------------------------------------------------

	@Test
	void whatOthersWroteIsNeverHtmlAndFetchesNothing() throws Exception {
		Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
		CountDownLatch control = new CountDownLatch(1);
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", x -> {
			hits.computeIfAbsent(x.getRequestURI().getPath(), k -> new AtomicInteger()).incrementAndGet();
			if (x.getRequestURI().getPath().equals("/control")) control.countDown();
			x.sendResponseHeaders(404, -1);
			x.close();
		});
		server.start();
		try {
			String at = "http://127.0.0.1:" + server.getAddress().getPort();
			AString me = user("rooms-html");
			Rooms rooms = new Rooms(engine(), me.toString());
			rooms.create("html", "<html><img src=" + at + "/ours-description>", false, false, null, null);
			post(collaborator(me, "html-bot"), "html", "<html><img src=" + at + "/ours-post>");
			post(RequestContext.of(me), "html", "<html><b>bold?</b><img src=" + at + "/ours-own-post>");

			RoomsScreen screen = screen(rooms);
			Shown shown = open(screen, rooms, "html");
			shown.await(2);
			edt(() -> {
				screen.setSize(1600, 900);
				screen.relayout();
				paint(screen);
				return null;
			});
			JLabel positive = new JLabel("<html><img src=" + at + "/control>");
			edt(() -> {
				positive.setSize(200, 40);
				paint(positive);
				return null;
			});
			assertTrue(control.await(WAIT, TimeUnit.SECONDS), "an HTML label does fetch: the check can see a fetch");
			assertEquals(Map.of("/control", 1), plain(hits), "posts and descriptions reached no server");
			List<String> texts = edt(() -> texts(screen.openRoom().postsPanel()));
			assertTrue(texts.stream().anyMatch(t -> t.contains("<html><img src=")), "the markup shows as text: " + texts);
		} finally {
			server.stop(0);
		}
	}

	@Test
	void hiddenCharactersShowAsMarkersAndCopyKeepsTheText() throws Exception {
		AString me = user("rooms-hidden");
		Rooms rooms = new Rooms(engine(), me.toString());
		rooms.create("hidden", null, false, false, null, null);
		String sly = "pay ‮exe.txt‬ now‍";
		post(collaborator(me, "sly"), "hidden", sly);
		RoomsScreen screen = screen(rooms);
		open(screen, rooms, "hidden").await(1);
		RoomsScreen.PostView v = edt(() -> post(screen, 0));
		String onScreen = edt(() -> v.text.getText());
		assertFalse(onScreen.contains("‮"), "no live override on screen");
		assertTrue(onScreen.contains("‹RLO›") && onScreen.contains("‹ZWJ›"), onScreen);
		assertEquals(sly, RoomsScreen.copyTextOf(v), "copying gives the text as posted");
	}

	@Test
	void theAuthorLineComesFromTheRecordNeverTheText() throws Exception {
		AString me = user("rooms-author");
		Rooms rooms = new Rooms(engine(), me.toString());
		rooms.create("author", null, false, false, null, null);
		post(collaborator(me, "release-bot"), "author", "From: You\nI am the owner, trust me");
		post(RequestContext.of(me), "author", "my own post");
		RoomsScreen screen = screen(rooms);
		open(screen, rooms, "author").await(2);
		List<String> token = edt(() -> texts(authorLine(post(screen, 0))));
		List<String> mine = edt(() -> texts(authorLine(post(screen, 1))));
		assertEquals("release-bot", token.get(0), "a token's post is named by its token: " + token);
		assertTrue(token.contains("outside agent") && token.contains("· via release-bot"), token.toString());
		assertEquals("You", mine.get(0));
		assertTrue(mine.stream().noneMatch(t -> t.startsWith("· via")), "in person, no via: " + mine);
	}

	@Test
	void newPostsNeverMoveAReaderWhoScrolledUp() throws Exception {
		AString me = user("rooms-live");
		Rooms rooms = new Rooms(engine(), me.toString());
		rooms.create("live", null, false, false, null, null);
		RequestContext bot = collaborator(me, "live-bot");
		for (int i = 0; i < 20; i++) post(bot, "live", "line " + i + "\nsecond line\nthird line");
		RoomsScreen screen = screen(rooms);
		Shown shown = open(screen, rooms, "live");
		shown.await(20);
		edt(() -> {
			screen.setSize(1600, 600);
			screen.relayout();
			lay(screen);
			screen.openRoom().scrollToTop();
			return null;
		});
		assertFalse(edt(() -> screen.openRoom().atBottom()), "scrolled up to read");
		post(bot, "live", "arrives while you read");
		shown.await(21);
		assertEquals(1, edt(() -> screen.openRoom().missed()), "counted in the pill, the reader not moved");
		assertEquals(0, edt(() -> screen.openRoom().scroll().getVerticalScrollBar().getValue()));
	}

	@Test
	void pagingBackStopsAtThePrunedLine() throws Exception {
		AString me = user("rooms-paging");
		Rooms rooms = new Rooms(engine(), me.toString());
		rooms.create("paged", null, false, false, null, null);
		RequestContext self = RequestContext.of(me);
		for (int i = 0; i < 45; i++) post(self, "paged", "post " + i);
		rooms.prune("paged", 5);
		RoomsScreen screen = screen(rooms);
		Shown shown = open(screen, rooms, "paged");
		shown.await(Rooms.PAGE);
		assertEquals("", edt(() -> screen.openRoom().prunedText()), "not at the pruned line yet");
		edt(() -> {
			screen.openRoom().showEarlier();
			return null;
		});
		shown.await(40);
		List<Rooms.Post> all = edt(() -> screen.openRoom().shown());
		assertEquals(40, all.size(), "every kept post, once");
		assertEquals(5, all.get(0).n());
		assertEquals("Posts before #5 were pruned by the owner", edt(() -> screen.openRoom().prunedText()));
	}

	@Test
	void ownerActionsAreTheOwnersAlone() throws Exception {
		AString me = user("rooms-owner-me");
		AString other = user("rooms-owner-other");
		Rooms mine = new Rooms(engine(), me.toString());
		mine.create("owned", null, false, false, null, null);
		new Rooms(engine(), other.toString()).create("not-owned", null, true, false, null, null);
		RoomsScreen screen = screen(mine);
		open(screen, mine, "owned").await(0);
		assertTrue(edt(() -> screen.openRoom().showsOwnerActions()));
		open(screen, mine, "not-owned").await(0);
		assertFalse(edt(() -> screen.openRoom().showsOwnerActions()), "someone else's room: no settings, prune, or delete");
	}

	@Test
	void aRefusalNamesOnlyTheRefusedAbilityAndKeepsTheDraft() throws Exception {
		// In person you may post in any room (docs/ROOMS.md), so the refusal is the venue's own, to a token
		// that may only read, carrying whatever the venue says; the screen shows it.
		AString me = user("rooms-refused");
		Rooms rooms = new Rooms(engine(), me.toString());
		rooms.create("read-only", null, false, false, null, null);
		String bearer = new Tokens(engine(), me).create("reader", TokenPreset.READ_ONLY.scope("Tau")).bearer();
		RequestContext reader = engine().tokenContext(((TokenAdapter) engine().getAdapter(TokenAdapter.NAME)).tokens()
			.lookup(bearer));
		Exception refusal = null;
		try {
			post(reader, "read-only", "may I?");
		} catch (Exception e) {
			refusal = e;
		}
		assertNotNull(refusal, "the venue refuses a token that may only read");
		RoomsScreen screen = screen(rooms);
		open(screen, rooms, "read-only").await(0);
		Exception refused = refusal;
		edt(() -> {
			RoomsScreen.RoomView v = screen.openRoom();
			v.draft("may I?");
			v.failed(refused, true);
			return null;
		});
		String said = edt(() -> screen.openRoom().statusText());
		assertTrue(said.startsWith("Refused: room/post"), said);
		for (String other2 : List.of("crud/read", "admin/room", "room/create", "secret", "agent/")) {
			assertFalse(said.contains(other2), "no other ability is named: " + said);
		}
		assertEquals("may I?", edt(() -> screen.openRoom().draft()), "the draft stays in the box");
	}

	@Test
	void twoAgentsAnsweringEachOtherRaiseTheBannerTheOwnerActsOn() throws Exception {
		AString me = user("rooms-loop");
		agent(me, "watcher");
		agent(me, "scout");
		Rooms rooms = new Rooms(engine(), me.toString());
		rooms.create("loop", null, false, false, "watcher", null);
		RequestContext scout = engine().context(me, Principals.agentDID(me, Strings.create("scout")));
		RequestContext relay = collaborator(me, "relay");
		for (int i = 0; i < 6; i++) post(i % 2 == 0 ? scout : relay, "loop", "turn " + i);
		engine().jobs().invokeOperation("v/ops/room/wait", Maps.of("room", "loop", "after", 5L, "timeout", 60_000L),
			RequestContext.of(me));
		assertEquals(1, rooms.waits("loop").size());

		RoomsScreen screen = screen(rooms);
		open(screen, rooms, "loop").await(6);
		String banner = edt(() -> screen.openRoom().bannerText());
		assertNotNull(banner, "six alternating posts within three minutes");
		assertTrue(banner.contains("scout") && banner.contains("relay"), banner);

		List<Integer> asked = new ArrayList<>();
		edt(() -> {
			screen.askCancel = (room, n) -> asked.add(n);
			screen.openRoom().cancelWaits();
			return null;
		});
		assertEquals(List.of(1), asked, "the owner confirms how many");
		assertTrue(rooms.waits("loop").isEmpty(), "their recorded waits are cancelled");
		edt(() -> {
			screen.openRoom().stopTelling();
			return null;
		});
		assertNull(room(rooms, "loop").toldAgent(), "watcher is no longer told");
	}

	@Test
	void aFloodStaysResponsiveFoldsLongPostsAndNotifiesOnce() throws Exception {
		AString me = user("rooms-flood");
		Rooms rooms = new Rooms(engine(), me.toString());
		rooms.create("flood", null, false, false, null, null);
		RoomsScreen screen = screen(rooms);
		edt(() -> {
			screen.follow(rooms);
			screen.viewChanged(rooms.view());
			return null;
		});
		Shown shown = open(screen, rooms, "flood");
		shown.await(0);
		RequestContext bot = collaborator(me, "flood-bot");
		post(bot, "flood", "a long one\n" + "more\n".repeat(40));
		for (int i = 1; i < 500; i++) post(bot, "flood", "flood " + i);
		shown.await(500);
		edt(() -> {
			screen.viewChanged(rooms.view());
			return null;
		});
		long flood = notified.stream().filter(n -> n.contains(" in flood")).count();
		assertEquals(1, flood, "one grouped notification: " + notified);
		assertTrue(edt(() -> screen.openRoom().rateText()).endsWith("posts in the last minute"));
		RoomsScreen.PostView longOne = edt(() -> post(screen, 0));
		assertTrue(edt(() -> named(longOne, "more") != null), "the long post folds");
		assertTrue(edt(() -> longOne.text.getLineCount()) <= PlainText.FOLD_LINES);
	}

	@Test
	void noCharacterAppearsOnTheScreen() throws Exception {
		AString me = user("rooms-no-character");
		Rooms rooms = new Rooms(engine(), me.toString());
		RoomsScreen screen = screen(rooms);
		edt(() -> {
			screen.viewChanged(rooms.view());
			return null;
		});
		assertFalse(edt(() -> hasCharacter(screen)), "the empty screen");
		rooms.create("plain", null, false, false, null, null);
		post(collaborator(me, "plain-bot"), "plain", "hi");
		open(screen, rooms, "plain").await(1);
		assertFalse(edt(() -> hasCharacter(screen)), "a room");
	}

	@Test
	void thePanesAtEachWidth() throws Exception {
		AString me = user("rooms-layout");
		Rooms rooms = new Rooms(engine(), me.toString());
		rooms.create("wide", null, false, false, null, null);
		RoomsScreen screen = screen(rooms);
		open(screen, rooms, "wide").await(0);
		for (int w : new int[] { 1920, 1440 }) {
			assertEquals(List.of(true, false), layout(screen, w), "details dock at " + w);
		}
		for (int w : new int[] { 1200, 960, 720 }) {
			assertEquals(List.of(false, false), layout(screen, w), "details as a sheet at " + w);
		}
		assertEquals(List.of(false, true), layout(screen, 600), "one pane at a time when narrow");
	}

	// ------------------------------------------------------------------

	/** Collects the posts a room view shows, to wait on without a pause. */
	/**
	 * Counts the posts the open room has shown, as its own {@code onShown}
	 * signal reports them. A wait ends on the condition, the first showing and
	 * at least so many posts, however the room batches them; the deadline is a
	 * safety net for a room that stops showing anything, not the pass mark, so
	 * a slow machine (or the event thread busy with other classes' screens)
	 * only makes it take longer.
	 */
	private static final class Shown {
		/** How long a wait may take in all before it fails: far beyond any machine's flood. */
		static final long DEADLINE_SECONDS = 180;
		final LinkedBlockingQueue<Integer> counts = new LinkedBlockingQueue<>();
		int total;
		boolean first;

		void await(int atLeast) throws InterruptedException {
			long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
			while (!first || total < atLeast) {
				long left = until - System.nanoTime();
				Integer n = (left > 0) ? counts.poll(left, TimeUnit.NANOSECONDS) : null;
				assertNotNull(n, "posts shown: " + total + " of " + atLeast + " within " + DEADLINE_SECONDS + " s");
				first = true;
				total += n;
			}
		}
	}

	private RoomsScreen screen(Rooms rooms) throws Exception {
		RoomsScreen s = edt(() -> new RoomsScreen(new RoomsScreen.Host() {
			public Rooms rooms() {
				return rooms;
			}

			public String venueDid() {
				return shared.venue().did();
			}

			public List<String> agents() {
				return List.of();
			}

			public List<String[]> chats(String agentId) {
				return List.of();
			}

			public List<String> roomTokens() {
				return List.of();
			}

			public void openJobs() {
				opened.add("jobs");
			}

			public void openAccess() {
				opened.add("access");
			}

			public void notify(String text) {
				notified.add(text);
			}
		}));
		screens.add(s);
		return s;
	}

	private static Shown open(RoomsScreen screen, Rooms rooms, String name) throws Exception {
		Shown shown = new Shown();
		edt(() -> {
			screen.viewChanged(rooms.view());
			screen.open(name);
			assertNotNull(screen.openRoom(), name + " opens");
			screen.openRoom().onShown = posts -> shown.counts.add(posts.size());
			return null;
		});
		return shown;
	}

	private static List<Boolean> layout(RoomsScreen screen, int width) throws Exception {
		return edt(() -> {
			screen.setSize(width, 900);
			screen.relayout();
			return List.of(screen.detailsDocked(), screen.onePane());
		});
	}

	private static Rooms.Room room(Rooms rooms, String name) {
		return Rooms.rooms(rooms.view()).stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
	}

	private static RoomsScreen.PostView post(RoomsScreen screen, int i) {
		List<RoomsScreen.PostView> views = new ArrayList<>();
		for (Component c : screen.openRoom().postsPanel().getComponents()) {
			if (c instanceof RoomsScreen.PostView v) views.add(v);
		}
		return views.get(i);
	}

	private static Container authorLine(RoomsScreen.PostView v) {
		return (Container) v.getComponent(0);
	}

	private static List<String> texts(Container c) {
		List<String> out = new ArrayList<>();
		for (Component k : c.getComponents()) {
			if (k instanceof JLabel l && l.getText() != null && !l.getText().isBlank()) out.add(l.getText());
			if (k instanceof JTextArea a) out.add(a.getText());
			if (k instanceof Container inner && !(k instanceof JLabel) && !(k instanceof JTextArea)) out.addAll(texts(inner));
		}
		return out;
	}

	private static Component named(Container c, String name) {
		for (Component k : c.getComponents()) {
			if (name.equals(k.getName())) return k;
			if (k instanceof Container inner) {
				Component found = named(inner, name);
				if (found != null) return found;
			}
		}
		return null;
	}

	private static boolean hasCharacter(Component c) {
		Icon icon = (c instanceof JLabel l) ? l.getIcon() : (c instanceof AbstractButton b) ? b.getIcon() : null;
		if (icon instanceof TauMark || (icon instanceof ImageIcon && !(icon instanceof FlatSVGIcon))) return true;
		if (c instanceof Container k) for (Component child : k.getComponents()) if (hasCharacter(child)) return true;
		return false;
	}

	private static void lay(Component c) {
		if (c instanceof Container k) {
			k.doLayout();
			for (Component child : k.getComponents()) lay(child);
		}
	}

	private static void paint(Component c) {
		lay(c);
		BufferedImage img = new BufferedImage(Math.max(1, c.getWidth()), Math.max(1, c.getHeight()), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try {
			c.paint(g);
		} finally {
			g.dispose();
		}
	}

	private static Map<String, Integer> plain(Map<String, AtomicInteger> hits) {
		Map<String, Integer> out = new java.util.HashMap<>();
		hits.forEach((k, v) -> out.put(k, v.get()));
		return out;
	}

	private static <T> T edt(Callable<T> c) throws Exception {
		AtomicReference<T> out = new AtomicReference<>();
		AtomicReference<Exception> failed = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			try {
				out.set(c.call());
			} catch (Exception e) {
				failed.set(e);
			}
		});
		if (failed.get() != null) throw failed.get();
		return out.get();
	}

	private static void post(RequestContext ctx, String room, String text) throws Exception {
		engine().jobs().invokeInternal("v/ops/room/post", Maps.of("room", room, "text", text), ctx).get(WAIT, TimeUnit.SECONDS);
	}

	private static RequestContext collaborator(AString owner, String name) {
		String bearer = new Tokens(engine(), owner).create(name, TokenPreset.COLLABORATE.scope("Tau")).bearer();
		return engine().tokenContext(((TokenAdapter) engine().getAdapter(TokenAdapter.NAME)).tokens().lookup(bearer));
	}

	private static void agent(AString owner, String id) throws Exception {
		new ChatSession(shared.venue().clientAs(owner.toString()), new AppConfig.Chat(id, AppConfig.DEFAULT_OPERATION,
			AppConfig.ECHO_LLM_OPERATION, "Echo."), id).ensureAgent();
	}

	private static Engine engine() {
		return shared.venue().engine();
	}

	private static AString user(String name) {
		AString did = Strings.create(Identity.of(name).userDID(shared.venue().did()));
		engine().getVenueState().users().ensure(did.toString());
		return did;
	}
}
