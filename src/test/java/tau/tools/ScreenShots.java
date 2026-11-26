package tau.tools;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;

import tau.Tau;
import tau.Inbox;
import tau.ui.MainWindow;
import tau.ui.onboarding.OnboardingWizard;

/**
 * The screenshot harness: boots Tau in a scratch data directory with the
 * offline echo model, walks the screens at each window size, and paints the
 * window into PNGs for side-by-side review between branches. A developer tool,
 * not a test: it puts a window on the desktop while it runs. Never points at
 * {@code ~/.tau} and never uses a real key.
 *
 * <pre>
 * java -cp target/test-classes:target/classes:$(cat target/cp.txt) \
 *     tau.tools.ScreenShots &lt;outDir&gt; &lt;dark|light&gt; [nameFilter]
 * </pre>
 *
 * Screens are reached through the window's own navigation methods
 * ({@link Screens}), so the harness follows the shell as it changes.
 */
public final class ScreenShots {

	/** Window sizes from the state matrix: narrow, today's default, the new default, wide. */
	public static final List<Dimension> SIZES = List.of(
		new Dimension(800, 600), new Dimension(1040, 760), new Dimension(1200, 800), new Dimension(1440, 900));

	/** Only screens whose name contains this are written (third argument); empty for all. */
	static String filter = "";

	private ScreenShots() {
	}

	/**
	 * Runs the walk, then ends the process: 0 when it finished and the scratch
	 * log holds no error that is not listed in {@link #EXPECTED_ERRORS}, else 1,
	 * with the reason. A failure anywhere in the walk ends it too, rather than
	 * leaving the window's threads holding the process open.
	 */
	public static void main(String[] args) {
		int code;
		try {
			Path scratch = walk(args);
			List<String> errors = new ArrayList<>(unexpectedErrors(scratch.resolve("logs").resolve("tau.log")));
			WRONG_SIZE.forEach(w -> errors.add("Window size: " + w));
			if (errors.isEmpty()) {
				System.out.println("No unexpected errors in the log, and every shot at the size asked for.");
				code = 0;
			} else {
				System.out.println("Unexpected errors, or shots not at the size asked for (" + errors.size() + "):");
				errors.forEach(e -> System.out.println("  " + e));
				code = 1;
			}
		} catch (Throwable t) {
			System.out.println("The walk failed: " + t);
			t.printStackTrace(System.out);
			code = 1;
		}
		System.exit(code);
	}

	/**
	 * Errors the walk causes on purpose, so they are not failures: each
	 * pattern is matched against an ERROR line of the scratch log.
	 */
	static final List<java.util.regex.Pattern> EXPECTED_ERRORS = List.of();

	/**
	 * {@code -Dtau.shots.largestWindow=1044x788} keeps the window itself no larger than that, as
	 * a small screen does (a Windows runner's), to check locally that shots do not depend on it.
	 */
	static Dimension largestWindow() {
		String v = System.getProperty("tau.shots.largestWindow");
		if (v == null || !v.matches("\\d+x\\d+")) return null;
		String[] wh = v.split("x");
		return new Dimension(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
	}

	/** Shots not painted at the size asked for. */
	static final List<String> WRONG_SIZE = java.util.Collections.synchronizedList(new ArrayList<>());

	/** The log's ERROR lines that no {@link #EXPECTED_ERRORS} pattern explains. */
	static List<String> unexpectedErrors(Path log) throws java.io.IOException {
		if (!Files.exists(log)) return List.of("no log at " + log);
		List<String> found = new ArrayList<>();
		for (String line : Files.readAllLines(log)) {
			if (!line.contains(" ERROR ")) continue;
			if (EXPECTED_ERRORS.stream().noneMatch(p -> p.matcher(line).find())) found.add(line);
		}
		return found;
	}

	/** Walks every screen, writing the shots, and returns the scratch data directory. */
	static Path walk(String[] args) throws Exception {
		Path out = Path.of(args.length > 0 ? args[0] : "target/shots");
		String mode = args.length > 1 ? args[1] : "dark";
		filter = args.length > 2 ? args[2] : "";
		Files.createDirectories(out);
		Path scratch = Files.createTempDirectory("tau-shots-");
		int port;
		try (ServerSocket s = new ServerSocket(0)) {
			port = s.getLocalPort();
		}
		Path config = scratch.resolve("config.json");
		Files.writeString(config, """
			{
				"theme": "%s",
				"venue": { "name": "Tau", "port": %d },
				"chat": { "llmOperation": "v/test/ops/llm" }
			}
			""".formatted(mode, port));
		System.out.println("Scratch data directory: " + scratch);

		Tau.main(new String[] { config.toString() });
		MainWindow window = waitFor(() -> frame(MainWindow.class), 30_000);
		Screens screens = new Screens(window);

		// Onboarding, every card that needs no venue.
		for (Dimension size : SIZES) {
			screens.resize(size);
			for (String step : screens.onboardingSteps()) {
				screens.onboardingStep(step);
				shoot(window, out, mode, "onboarding-" + step.toLowerCase(), size);
			}
		}

		// The character's first-launch slot: onboarding's greeting card.
		for (Dimension size : SIZES) {
			screens.resize(size);
			screens.onboardingStep(screens.onboardingSteps().get(0));
			Thread.sleep(1200);
			shoot(window, out, mode, "character-first-launch", size);
		}

		// Finish onboarding as a new person called Sam; the model is chosen afterwards.
		char[] pass = "scratch-only-passphrase".toCharArray();
		String seed = "11".repeat(32);
		OnboardingWizard.Setup setup = new OnboardingWizard.Setup(pass, seed, "Sam");
		Tau app = screens.app();
		SwingUtilities.invokeAndWait(() -> app.onOnboardingComplete(setup));
		waitFor(() -> screens.chatReady() ? Boolean.TRUE : null, 120_000);
		Thread.sleep(1500);

		for (Dimension size : SIZES) {
			screens.resize(size);
			for (Map.Entry<String, Runnable> e : screens.loggedIn().entrySet()) {
				e.getValue().run();
				shoot(window, out, mode, e.getKey(), size);
			}
		}
		// Trust and recovery: Stop, the Developer tab, Back up now, an agent's caps, and a change waiting for approval.
		if ("trust".contains(filter) || filter.startsWith("trust")) {
			screens.dialog(out.resolve(mode).resolve("trust-stop-confirm.png"), () -> {
				tau.ui.chat.ChatPanel chat = (tau.ui.chat.ChatPanel) Screens.field(window, "chatPanel");
				Object ask = Screens.field(chat, "askToStop");
				((java.util.function.BooleanSupplier) ask).getAsBoolean();
				return null;
			});
			for (Dimension size : SIZES) {
				screens.resize(size);
				screens.home();
				screens.stageBeat("WORKING");
				Object chat = Screens.field(window, "chatPanel");
				Object bubble = Screens.field(chat, "thinkingBubble");
				Method summary = bubble.getClass().getDeclaredMethod("setSummary", String.class);
				summary.setAccessible(true);
				Screens.onEdt(() -> {
					summary.invoke(bubble, "Stopping…");
					return null;
				});
				Thread.sleep(500);
				shoot(window, out, mode, "trust-stopping", size);
				screens.endStage();
				Screens.onEdt(() -> {
					((tau.ui.chat.ChatPanel) chat).appendSystem("Stopped. Steps already finished keep their results.");
					return null;
				});
				Thread.sleep(500);
				shoot(window, out, mode, "trust-stopped", size);
				screens.settings("DEVELOPER");
				Thread.sleep(2500);
				shoot(window, out, mode, "trust-developer", size);
				screens.backUpNow(size == SIZES.get(0));
				shoot(window, out, mode, "trust-backup", size);
			}
			String agent = screens.templateAgent();
			screens.overwriteAsAgent(agent);
			for (Dimension size : SIZES) {
				screens.resize(size);
				screens.inspect("AGENT");
				shoot(window, out, mode, "trust-caps", size);
				screens.inbox();
				Thread.sleep(800);
				shoot(window, out, mode, "trust-confirm-inbox", size);
				screens.home();
				Thread.sleep(800);
				shoot(window, out, mode, "trust-confirm-chat", size);
			}
			// Back to the standard agent, so what follows runs as it would without this block.
			Tau standard = screens.app();
			Screens.onEdt(() -> {
				standard.switchAgent("Tau");
				return null;
			});
			Thread.sleep(3000);
			// The main assistant's page: the scoped set of a new install, then Unrestricted (an older install's)
			// with its one-click offer, and both confirmations. The scratch venue makes it on its first message.
			screens.sendMessage("Hello");
			String me = standard.actingPrincipal();
			waitFor(() -> standard.venue().agentRecord(me, "Tau"), 60_000);
			Thread.sleep(2000);
			for (Dimension size : SIZES) {
				screens.resize(size);
				screens.inspect("AGENT");
				screens.scrollToText("What it may do");
				shoot(window, out, mode, "trust-caps-assistant", size);
				screens.scrollToText("Needs an extra grant");
				shoot(window, out, mode, "trust-caps-assistant-more", size);
			}
			screens.dialog(out.resolve(mode).resolve("trust-caps-make-unrestricted.png"), () -> {
				tau.ui.components.Dialogs.confirmDanger(window, "Make Tau Unrestricted?",
					tau.ui.inspect.AgentInspector.unrestrictedWarning("Tau"));
				return null;
			});
			standard.setAgentPermissions("Tau", true);
			Thread.sleep(3000);
			for (Dimension size : SIZES) {
				screens.resize(size);
				screens.inspect("OVERVIEW");
				screens.inspect("AGENT");
				screens.scrollToText("What it may do");
				shoot(window, out, mode, "trust-caps-unrestricted", size);
			}
			screens.dialog(out.resolve(mode).resolve("trust-caps-use-scoped.png"), () -> {
				tau.ui.components.Dialogs.confirm(window, "Use the scoped set for Tau?",
					tau.ui.inspect.AgentInspector.scopedChanges("Tau"));
				return null;
			});
			standard.setAgentPermissions("Tau", false);
			Thread.sleep(3000);
		}

		// The developer loop: Connect a tool (a real token on the scratch venue, its settings, the test), and two
		// failures as the chat shows them (a refused model key, and a refusal with its Details open).
		for (Dimension size : SIZES) {
			screens.resize(size);
			screens.connectTool(size == SIZES.get(0));
			shoot(window, out, mode, "devloop-connect", size);
			screens.errorStates(size == SIZES.get(0));
			shoot(window, out, mode, "devloop-errors", size);
		}

		// The character's slots (with a pack named by tau.character.dir), and the never-list screens beside them.
		for (Dimension size : SIZES) {
			screens.resize(size);
			for (String[] place : new String[][] { { "home-empty", "HOME" }, { "inbox-empty", "INBOX" } }) {
				screens.tab(place[1]);
				Thread.sleep(800);
				shoot(window, out, mode, "character-" + place[0], size);
			}
			for (String d : List.of("FEED", "SKILLS", "RECORD", "VAULT", "AGENTS", "PEERS", "ROOMS")) {
				onEdtGo(window, d);
				Thread.sleep(800);
				shoot(window, out, mode, "character-" + d.toLowerCase(), size);
			}
			screens.home();
			for (String beat : List.of("NEW_REQUEST", "THINKING", "WORKING", "DELEGATING", "WAITING")) {
				screens.stageBeat(beat);
				shoot(window, out, mode, "character-chat-" + beat.toLowerCase().replace('_', '-'), size);
			}
			screens.endStage();
		}
		screens.about(out.resolve(mode).resolve("character-about.png"));

		// A conversation with the echo model, then Home again with it in view.
		screens.sendMessage("Hello, what can you do?");
		Thread.sleep(4000);
		for (Dimension size : SIZES) {
			screens.resize(size);
			screens.home();
			shoot(window, out, mode, "home-chat", size);
			screens.sessions();
			shoot(window, out, mode, "sessions-chat", size);
			for (String view : List.of("AGENT", "RECORD")) {
				screens.inspect(view);
				shoot(window, out, mode, "inspect-" + view.toLowerCase(), size);
			}
		}
		// The Inbox with seeded requests: one, twelve and over a hundred waiting.
		for (int n : new int[] { 1, 12, 120 }) {
			screens.seedInbox(n);
			for (Dimension size : SIZES) {
				screens.resize(size);
				screens.inbox();
				shoot(window, out, mode, "inbox-" + n, size);
				screens.home();
				shoot(window, out, mode, "home-inbox-" + n, size);
			}
		}
		// A job that ran out of time: a status of its own, beside the job list.
		String timedOut = screens.seedTimedOutJob();
		for (Dimension size : SIZES) {
			screens.resize(size);
			screens.openJob(timedOut);
			shoot(window, out, mode, "jobs-timedout", size);
		}
		// Rooms: none yet, then seeded rooms (an outside agent's posts, markup and hidden characters shown as
		// text, a long post folded, two agents answering each other, and someone else's public room).
		for (Dimension size : SIZES) {
			screens.resize(size);
			screens.room(null);
			shoot(window, out, mode, "rooms-empty", size);
		}
		screens.seedRooms();
		for (String room : List.of("release", "standup", "ci-watch")) {
			for (Dimension size : SIZES) {
				screens.resize(size);
				screens.room(room);
				shoot(window, out, mode, "rooms-" + room, size);
			}
		}
		System.out.println("Screenshots written to " + out.toAbsolutePath());
		return scratch;
	}

	/** The content size of a window of {@code size}: the root pane inside its frame. */
	static Dimension content(JFrame window, Dimension size) {
		java.awt.Insets in = window.getInsets();
		return new Dimension(size.width - in.left - in.right, size.height - in.top - in.bottom);
	}

	/**
	 * Lays the window's content out at {@code size}, as a window of that size would show it,
	 * even when the screen kept the window itself smaller (a CI runner's 1024 by 768 display):
	 * the root pane takes the size, and the window's own layout pass follows it. Painting is
	 * offscreen, so the shot does not depend on the screen.
	 */
	static void fit(JFrame window, Dimension size) throws Exception {
		Dimension want = content(window, size);
		SwingUtilities.invokeAndWait(() -> {
			javax.swing.JRootPane root = window.getRootPane();
			if (root.getWidth() == want.width && root.getHeight() == want.height) return;
			root.setSize(want);
			root.validate();
			if (window instanceof MainWindow) {
				try {
					Method relayout = MainWindow.class.getDeclaredMethod("relayout");
					relayout.setAccessible(true);
					relayout.invoke(window);
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("MainWindow.relayout is needed to fit a shot", e);
				}
			}
		});
		// Let the resize events the new size raised run, then lay out what they changed.
		SwingUtilities.invokeAndWait(() -> {
		});
		SwingUtilities.invokeAndWait(() -> window.getRootPane().validate());
	}

	static void onEdtGo(MainWindow window, String destination) throws Exception {
		Screens.onEdt(() -> {
			window.go(tau.ui.shell.Destination.valueOf(destination));
			return null;
		});
	}

	/** Debug: how a button with this text is sized and measured (set -Dshots.probe=text). */
	static void probe(java.awt.Component c) {
		String want = System.getProperty("shots.probe");
		if (want == null) return;
		if (c instanceof javax.swing.AbstractButton b && want.equals(b.getText()) && b.isShowing()) {
			java.awt.FontMetrics fm = b.getFontMetrics(b.getFont());
			System.out.println("PROBE " + want + " w=" + b.getWidth() + " pref=" + b.getPreferredSize().width + " text="
				+ fm.stringWidth(want) + " insets=" + b.getInsets() + " font=" + b.getFont() + " frc=" + fm.getFontRenderContext());
		}
		if (c instanceof java.awt.Container k) for (java.awt.Component ch : k.getComponents()) probe(ch);
	}

	/** Paints the window's root pane, as laid out at {@code size}, into a PNG. */
	static void shoot(JFrame window, Path out, String mode, String name, Dimension size) throws Exception {
		if (!name.contains(filter)) return;
		Thread.sleep(400);
		fit(window, size);
		SwingUtilities.invokeAndWait(() -> probe(window));
		AtomicReference<BufferedImage> img = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() -> {
			JComponent root = window.getRootPane();
			root.validate();
			// Paint at the screen's own scale (2 on a Retina display): text is laid out with
			// that scale's metrics, and painting it at another scale cuts bold text short.
			double scale = window.getGraphicsConfiguration().getDefaultTransform().getScaleX();
			BufferedImage b = new BufferedImage((int) Math.ceil(root.getWidth() * scale),
				(int) Math.ceil(root.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
			Graphics2D g = b.createGraphics();
			g.scale(scale, scale);
			root.printAll(g);
			g.dispose();
			img.set(b);
		});
		// The shot is the window's content laid out at the size asked for (fit), whatever the
		// screen allowed the window itself: a shot at another size fails the walk rather than
		// be named for a size it was not taken at.
		java.awt.Dimension painted = content(window, size);
		double shotScale = window.getGraphicsConfiguration().getDefaultTransform().getScaleX();
		int shotW = (int) Math.round(img.get().getWidth() / shotScale), shotH = (int) Math.round(img.get().getHeight() / shotScale);
		if (Math.abs(shotW - painted.width) > 1 || Math.abs(shotH - painted.height) > 1) {
			WRONG_SIZE.add(name + " at " + size.width + "x" + size.height + " came out " + shotW + "x" + shotH
				+ " of content, not " + painted.width + "x" + painted.height);
		}
		Path file = out.resolve(mode).resolve(name + "-" + size.width + "x" + size.height + ".png");
		Files.createDirectories(file.getParent());
		ImageIO.write(img.get(), "png", file.toFile());
		System.out.println("  " + file);
		if (blank(img.get())) System.out.println("  BLANK: " + name + " at " + size.width + "x" + size.height
			+ " has nothing below its header");
	}

	/**
	 * Whether a shot's content (right of the sidebar, below the header) is one
	 * flat colour: a screen that shows only its header, as a page left hidden
	 * when it moved would.
	 */
	static boolean blank(BufferedImage im) {
		int w = im.getWidth(), h = im.getHeight();
		java.util.Set<Integer> colours = new java.util.HashSet<>();
		for (int x = (int) (w * 0.17); x < w; x += 6) {
			for (int y = (int) (h * 0.13); y < h - h * 0.03; y += 6) {
				int c = im.getRGB(x, y);
				colours.add(((c >> 20) & 0xF) << 8 | ((c >> 12) & 0xF) << 4 | ((c >> 4) & 0xF));
			}
		}
		return colours.size() < 6;
	}

	@SuppressWarnings("unchecked")
	static <T extends Frame> T frame(Class<T> type) {
		for (Frame f : Frame.getFrames()) if (type.isInstance(f) && f.isShowing()) return (T) f;
		return null;
	}

	interface Probe<T> {
		T get() throws Exception;
	}

	static <T> T waitFor(Probe<T> probe, long millis) throws Exception {
		long end = System.currentTimeMillis() + millis;
		while (System.currentTimeMillis() < end) {
			T t = probe.get();
			if (t != null) return t;
			Thread.sleep(200);
		}
		throw new IllegalStateException("Timed out waiting");
	}

	/**
	 * How the harness reaches each screen: through the window's own methods,
	 * by reflection, so production code carries no test hooks.
	 */
	static final class Screens {
		final MainWindow window;

		Screens(MainWindow window) {
			this.window = window;
		}

		Tau app() throws Exception {
			return (Tau) field(window, "app");
		}

		void resize(Dimension size) throws Exception {
			Dimension most = largestWindow();
			Dimension allowed = (most == null) ? size
				: new Dimension(Math.min(size.width, most.width), Math.min(size.height, most.height));
			SwingUtilities.invokeAndWait(() -> {
				window.setExtendedState(Frame.NORMAL);
				window.setSize(allowed);
				window.validate();
			});
			Thread.sleep(300);
		}

		List<String> onboardingSteps() throws Exception {
			List<String> out = new ArrayList<>();
			Class<?> step = Class.forName("tau.ui.onboarding.OnboardingWizard$Step");
			for (Object s : step.getEnumConstants()) out.add(((Enum<?>) s).name());
			return out;
		}

		void onboardingStep(String name) throws Exception {
			Object wizard = field(window, "onboarding");
			Class<?> stepType = Class.forName("tau.ui.onboarding.OnboardingWizard$Step");
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object step = Enum.valueOf((Class) stepType, name);
			Method show = wizard.getClass().getDeclaredMethod("show", stepType);
			show.setAccessible(true);
			onEdt(() -> show.invoke(wizard, step));
		}

		boolean chatReady() throws Exception {
			return "chat".equals(field(window, "currentCard")) && field(window, "venue") != null;
		}

		/** Every screen and Settings page reachable after login, by name, in order. */
		Map<String, Runnable> loggedIn() throws Exception {
			Map<String, Runnable> out = new java.util.LinkedHashMap<>();
			Class<?> tabType = Class.forName("tau.ui.NavBar$Tab");
			for (Object t : tabType.getEnumConstants()) {
				String n = ((Enum<?>) t).name();
				if (n.equals("SETTINGS")) continue; // each page below
				out.put(n.toLowerCase(), () -> quietly(() -> { tab(n); return null; }));
			}
			// The sidebar's destinations that have no bottom-bar tab of their own.
			for (String d : List.of("AGENTS", "FEED", "JOBS", "RECORD", "VAULT")) {
				out.put("rail-" + d.toLowerCase(), () -> quietly(() -> {
					Class<?> destType = Class.forName("tau.ui.shell.Destination");
					@SuppressWarnings({ "unchecked", "rawtypes" })
					Object dest = Enum.valueOf((Class) destType, d);
					Method go = MainWindow.class.getMethod("go", destType);
					onEdt(() -> go.invoke(window, dest));
					return null;
				}));
			}
			Class<?> pageType = Class.forName("tau.ui.settings.SettingsScreen$Tab");
			for (Object p : pageType.getEnumConstants()) {
				String n = ((Enum<?>) p).name();
				out.put("settings-" + n.toLowerCase(), () -> quietly(() -> { settings(n); return null; }));
			}
			return out;
		}

		void home() throws Exception {
			tab("HOME");
		}

		void sessions() throws Exception {
			tab("SESSIONS");
		}

		void inbox() throws Exception {
			tab("INBOX");
		}

		void tab(String name) throws Exception {
			Class<?> tabType = Class.forName("tau.ui.NavBar$Tab");
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object tab = Enum.valueOf((Class) tabType, name);
			Method m = MainWindow.class.getDeclaredMethod("selectTab", tabType);
			m.setAccessible(true);
			onEdt(() -> m.invoke(window, tab));
		}

		void settings(String name) throws Exception {
			Class<?> pageType = Class.forName("tau.ui.settings.SettingsScreen$Tab");
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object page = Enum.valueOf((Class) pageType, name);
			Method m = MainWindow.class.getDeclaredMethod("showSettings", pageType);
			m.setAccessible(true);
			onEdt(() -> m.invoke(window, page));
		}

		/** The chat with its inspect views, one brought forward. */
		void inspect(String view) throws Exception {
			sessions();
			Object views = field(window, "views");
			Class<?> viewType = Class.forName("tau.ui.inspect.SessionViews$View");
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object v = Enum.valueOf((Class) viewType, view);
			Method select = views.getClass().getMethod("select", viewType);
			onEdt(() -> select.invoke(views, v));
			Thread.sleep(800);
		}

		void sendMessage(String text) throws Exception {
			home();
			Object chat = field(window, "chatPanel");
			JTextComponent input = (JTextComponent) field(chat, "input");
			AbstractButton send = (AbstractButton) field(chat, "send");
			onEdt(() -> {
				input.setText(text);
				send.doClick();
				return null;
			});
		}

		/** One sample request from the identity the app acts as (sample only; it never reaches the venue). */
		/**
		 * A job that ran out of time, as Covia would record one: a job that never
		 * finishes by itself, marked TIMEOUT. Returns its id.
		 */
		String seedTimedOutJob() throws Exception {
			tau.EmbeddedVenue v = app().venue();
			String me = app().actingPrincipal();
			covia.grid.Job j = v.clientAs(me).invoke("v/test/ops/never", convex.core.data.Maps.empty())
				.get(30, java.util.concurrent.TimeUnit.SECONDS);
			covia.grid.Job live = v.engine().jobs().getJob(j.getID());
			live.setStatus(covia.grid.Status.TIMEOUT);
			Thread.sleep(500);
			return j.getID().toHexString();
		}

		/** The sidebar's Jobs, with job {@code id} open beside the list. */
		/**
		 * The reply in flight with the figure beside its step line, at {@code beat}:
		 * the chat's own thinking row, staged without a model call.
		 */
		void stageBeat(String beat) throws Exception {
			Object chat = field(window, "chatPanel");
			if (field(chat, "thinkingBubble") == null) {
				Method show = chat.getClass().getDeclaredMethod("showThinking");
				show.setAccessible(true);
				onEdt(() -> show.invoke(chat));
				Thread.sleep(1600);
			}
			Object figure = field(chat, "figure");
			Object bubble = field(chat, "thinkingBubble");
			String words = switch (beat) {
				case "THINKING" -> "Thinking…";
				case "WORKING" -> "Reading offsite-notes.md…";
				case "DELEGATING" -> "Asking Odin…";
				case "WAITING" -> "Waiting on you · Approve sending the summary?";
				default -> "Preparing…";
			};
			Method summary = bubble.getClass().getDeclaredMethod("setSummary", String.class);
			summary.setAccessible(true);
			onEdt(() -> {
				summary.invoke(bubble, words);
				if ("WAITING".equals(beat)) chat.getClass().getMethod("setWaitingOnYou", boolean.class).invoke(chat, true);
				if (figure != null) {
					figure.getClass().getMethod("setBeat", tau.ui.character.Presence.Beat.class).invoke(figure,
						tau.ui.character.Presence.Beat.valueOf(beat));
				}
				return null;
			});
			Thread.sleep(1500);
		}

		void endStage() throws Exception {
			Object chat = field(window, "chatPanel");
			Method hide = chat.getClass().getDeclaredMethod("hideThinking");
			hide.setAccessible(true);
			onEdt(() -> {
				chat.getClass().getMethod("setWaitingOnYou", boolean.class).invoke(chat, false);
				hide.invoke(chat);
				Method empty = chat.getClass().getDeclaredMethod("showEmptyState");
				empty.setAccessible(true);
				empty.invoke(chat);
				return null;
			});
		}

		/** Opens About and writes its dialog to {@code file}. */
		void about(Path file) throws Exception {
			Method m = MainWindow.class.getDeclaredMethod("showAbout");
			m.setAccessible(true);
			SwingUtilities.invokeLater(() -> {
				try {
					m.invoke(window);
				} catch (Exception e) {
					System.err.println("  could not open About: " + e);
				}
			});
			javax.swing.JDialog d = waitFor(() -> {
				for (java.awt.Window w : java.awt.Window.getWindows()) {
					if (w instanceof javax.swing.JDialog j && j.isShowing()) return j;
				}
				return null;
			}, 10_000);
			Thread.sleep(1500);
			AtomicReference<BufferedImage> img = new AtomicReference<>();
			SwingUtilities.invokeAndWait(() -> {
				JComponent root = d.getRootPane();
				double scale = d.getGraphicsConfiguration().getDefaultTransform().getScaleX();
				BufferedImage b = new BufferedImage((int) Math.ceil(root.getWidth() * scale),
					(int) Math.ceil(root.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
				Graphics2D g = b.createGraphics();
				g.scale(scale, scale);
				root.printAll(g);
				g.dispose();
				img.set(b);
			});
			Files.createDirectories(file.getParent());
			ImageIO.write(img.get(), "png", file.toFile());
			System.out.println("  " + file);
			SwingUtilities.invokeAndWait(d::dispose);
		}

		/** Settings → Access with a tool connected and tested; {@code make} makes the token, else shows it again. */
		void connectTool(boolean make) throws Exception {
			settings("ACCESS");
			tau.ui.settings.AccessPanel panel = find(window, tau.ui.settings.AccessPanel.class);
			Class<?> type = panel.getClass();
			if (make) {
				Method choose = type.getDeclaredMethod("chooseClient", tau.ConnectTool.Client.class);
				Method connect = type.getDeclaredMethod("connectNow");
				choose.setAccessible(true);
				connect.setAccessible(true);
				onEdt(() -> {
					choose.invoke(panel, tau.ConnectTool.Client.CURSOR);
					connect.invoke(panel);
					return null;
				});
				Thread.sleep(2500);
				Method test = type.getDeclaredMethod("testNow");
				test.setAccessible(true);
				onEdt(() -> test.invoke(panel));
			}
			Thread.sleep(2500);
			onEdt(() -> {
				for (java.awt.Component c : allComponents(panel)) {
					if (c instanceof javax.swing.JScrollPane sp && sp.getParent() == panel) sp.getVerticalScrollBar().setValue(0);
				}
				return null;
			});
			Thread.sleep(400);
		}

		/** Two failures in the chat as the owner sees them, the second with its Details open; {@code add} adds them. */
		void errorStates(boolean add) throws Exception {
			home();
			tau.ui.chat.ChatPanel chat = (tau.ui.chat.ChatPanel) field(window, "chatPanel");
			if (add) onEdt(() -> {
				chat.appendProblem(tau.ui.Problems.classify("HTTP 401 authentication_error: invalid x-api-key "
					+ "sk-ant-api03-EXAMPLEEXAMPLEEXAMPLE"));
				chat.appendProblem(tau.ui.Problems.classify("Capability denied: requires secret/use on "
					+ "did:key:z6MkExample:u:sam/s/MODEL_PROVIDER_KEY. Your capabilities are: invoke on v/ops/, "
					+ "crud/read on w/"));
				tau.ui.components.Disclosure details = null;
				for (java.awt.Component c : allComponents(chat)) {
					if (c instanceof tau.ui.components.Disclosure d && "details".equals(d.getName())) details = d;
				}
				if (details != null) details.setExpanded(true);
				return null;
			});
			Thread.sleep(1200);
		}

		static List<java.awt.Component> allComponents(java.awt.Container c) {
			List<java.awt.Component> out = new ArrayList<>();
			for (java.awt.Component k : c.getComponents()) {
				out.add(k);
				if (k instanceof java.awt.Container kc) out.addAll(allComponents(kc));
			}
			return out;
		}

		/** Runs {@code open} (which shows a modal dialog) and writes the dialog to {@code file}, then closes it. */
		void dialog(Path file, Call open) throws Exception {
			SwingUtilities.invokeLater(() -> {
				try {
					open.call();
				} catch (Exception e) {
					System.err.println("  could not open the dialog: " + e);
				}
			});
			javax.swing.JDialog d = waitFor(() -> {
				for (java.awt.Window w : java.awt.Window.getWindows()) {
					if (w instanceof javax.swing.JDialog j && j.isShowing()) return j;
				}
				return null;
			}, 10_000);
			Thread.sleep(800);
			AtomicReference<BufferedImage> img = new AtomicReference<>();
			SwingUtilities.invokeAndWait(() -> {
				JComponent root = d.getRootPane();
				double scale = d.getGraphicsConfiguration().getDefaultTransform().getScaleX();
				BufferedImage b = new BufferedImage((int) Math.ceil(root.getWidth() * scale),
					(int) Math.ceil(root.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
				Graphics2D g = b.createGraphics();
				g.scale(scale, scale);
				root.printAll(g);
				g.dispose();
				img.set(b);
			});
			Files.createDirectories(file.getParent());
			ImageIO.write(img.get(), "png", file.toFile());
			System.out.println("  " + file);
			SwingUtilities.invokeAndWait(d::dispose);
		}

		/** Settings → General scrolled to the data store, after Back up now when {@code press}. */
		void backUpNow(boolean press) throws Exception {
			settings("GENERAL");
			Thread.sleep(800);
			tau.ui.settings.GeneralPanel general = find(window, tau.ui.settings.GeneralPanel.class);
			JComponent button = (JComponent) field(general, "backupButton");
			if (press) {
				Method m = general.getClass().getDeclaredMethod("backUpNow");
				m.setAccessible(true);
				onEdt(() -> m.invoke(general));
				Thread.sleep(2500);
			}
			onEdt(() -> {
				button.scrollRectToVisible(new java.awt.Rectangle(0, -200, button.getWidth(), button.getHeight() + 400));
				return null;
			});
			Thread.sleep(600);
		}

		/** Scrolls the first label or text on screen that contains {@code text} into view, near the top. */
		void scrollToText(String text) throws Exception {
			onEdt(() -> {
				JComponent hit = findText(window.getContentPane(), text);
				if (hit != null) {
					javax.swing.JViewport port = (javax.swing.JViewport) SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, hit);
					if (port != null) {
						java.awt.Point at = SwingUtilities.convertPoint(hit.getParent(), hit.getLocation(), port.getView());
						if (hit instanceof JTextComponent tc) {
							// One text holding the whole page: scroll to where the words are inside it.
							java.awt.geom.Rectangle2D r = tc.modelToView2D(tc.getText().indexOf(text));
							if (r != null) at = SwingUtilities.convertPoint(tc, new java.awt.Point(0, (int) r.getY()), port.getView());
						}
						port.setViewPosition(new java.awt.Point(0, Math.max(0, Math.min(at.y - 12,
							port.getView().getHeight() - port.getExtentSize().height))));
					}
				}
				return null;
			});
			Thread.sleep(500);
		}

		private static JComponent findText(java.awt.Container in, String text) {
			for (java.awt.Component c : in.getComponents()) {
				if (!c.isShowing()) continue;
				String t = (c instanceof javax.swing.JLabel l) ? l.getText()
					: (c instanceof JTextComponent tc) ? tc.getText() : null;
				if (t != null && t.contains(text)) return (JComponent) c;
				if (c instanceof java.awt.Container k) {
					JComponent hit = findText(k, text);
					if (hit != null) return hit;
				}
			}
			return null;
		}

		/** Makes a Writing agent with its template's caps and opens its chat; returns its id. */
		String templateAgent() throws Exception {
			Tau app = app();
			onEdt(() -> {
				app.createAgent("Editor", app.currentModelOp(), tau.model.AgentTemplate.WRITING.systemPrompt("Editor"),
					tau.model.AgentTemplate.WRITING.skill(), tau.model.AgentPermissions.caps(tau.model.AgentTemplate.WRITING));
				return null;
			});
			String me = app.actingPrincipal();
			return waitFor(() -> {
				for (String id : new String[] { "editor", "Editor" }) {
					if (app.venue().agentRecord(me, id) != null) return id;
				}
				return null;
			}, 60_000);
		}

		/** As agent {@code id}, writes a note and then overwrites it: the overwrite waits for approval in the Inbox. */
		void overwriteAsAgent(String id) throws Exception {
			Tau app = app();
			covia.venue.Engine engine = app.venue().engine();
			convex.core.data.AString me = convex.core.data.Strings.create(app.actingPrincipal());
			covia.venue.RequestContext as = engine.context(me,
				covia.grid.Principals.agentDID(me, convex.core.data.Strings.create(id)));
			engine.jobs().invokeInternal("v/ops/covia/write",
				convex.core.data.Maps.of("path", "w/notes/plan.md", "value", "Draft one"), as).get(20,
					java.util.concurrent.TimeUnit.SECONDS);
			Thread.ofVirtual().start(() -> {
				try {
					engine.jobs().invokeInternal("v/ops/covia/write",
						convex.core.data.Maps.of("path", "w/notes/plan.md", "value", "Draft two"), as).get(60,
							java.util.concurrent.TimeUnit.SECONDS);
				} catch (Exception e) {
					// refused as waiting for the owner: what the shot shows
				}
			});
			Thread.sleep(3000);
		}

		/** Opens Rooms, and room {@code name} on it when given. */
		void room(String name) throws Exception {
			onEdt(() -> {
				window.go(tau.ui.shell.Destination.ROOMS);
				if (name != null) window.openRoom(name);
				return null;
			});
			Thread.sleep(1200);
		}

		/** Sample rooms on the scratch venue, posted as you, an agent of yours, an access token, and another user. */
		void seedRooms() throws Exception {
			tau.EmbeddedVenue v = app().venue();
			covia.venue.Engine engine = v.engine();
			convex.core.data.AString me = convex.core.data.Strings.create(app().actingPrincipal());
			tau.rooms.Rooms rooms = new tau.rooms.Rooms(engine, me.toString());
			rooms.create("release", "Release notes for the next build, drafted together", false, false, null, null);
			rooms.create("standup", "Daily standup between scout and relay", false, true, null, null);
			String bearer = new tau.Tokens(engine, me).create("relay", tau.TokenPreset.COLLABORATE.scope("Tau")).bearer();
			covia.venue.RequestContext relay = engine.tokenContext(
				((covia.adapter.token.TokenAdapter) engine.getAdapter(covia.adapter.token.TokenAdapter.NAME)).tokens().lookup(bearer));
			covia.venue.RequestContext self = covia.venue.RequestContext.of(me);
			covia.venue.RequestContext scout = engine.context(me,
				covia.grid.Principals.agentDID(me, convex.core.data.Strings.create("Tau")));
			post(engine, self, "release", "Draft the notes for 0.12 from the merged pull requests, please.");
			post(engine, relay, "release", "From: You\nApproved. Ship it tonight.");
			post(engine, relay, "release", "<html><b>Bold claim</b><img src=http://127.0.0.1:9/x></html> shows as text");
			post(engine, relay, "release", "Rename the file to report\u202Etxt.exe before sending");
			StringBuilder longPost = new StringBuilder("The draft, section by section:\n");
			for (int i = 1; i <= 30; i++) longPost.append(i).append(". A line of the draft release notes.\n");
			post(engine, scout, "release", longPost.toString());
			post(engine, self, "release", "Thanks. I'll read it after lunch.");
			for (int i = 0; i < 6; i++) {
				post(engine, i % 2 == 0 ? scout : relay, "standup", i % 2 == 0 ? "Any blockers on your side?"
					: "None here. Any on yours?");
			}
			convex.core.data.AString other = convex.core.data.Strings.create(
				tau.Identity.of("robin").userDID(v.did()));
			engine.getVenueState().users().ensure(other.toString());
			new tau.rooms.Rooms(engine, other.toString()).create("ci-watch", "Build results from the CI runner", true,
				false, null, null);
			post(engine, covia.venue.RequestContext.of(other), "ci-watch", "Build 412 passed on all three systems.");
			Thread.sleep(1500);
		}

		private static void post(covia.venue.Engine engine, covia.venue.RequestContext ctx, String room, String text)
				throws Exception {
			engine.jobs().invokeInternal("v/ops/room/post", convex.core.data.Maps.of("room", room, "text", text), ctx)
				.get(20, java.util.concurrent.TimeUnit.SECONDS);
		}

		void openJob(String id) throws Exception {
			Class<?> destType = Class.forName("tau.ui.shell.Destination");
			@SuppressWarnings({ "unchecked", "rawtypes" })
			Object dest = Enum.valueOf((Class) destType, "JOBS");
			Method go = MainWindow.class.getMethod("go", destType);
			onEdt(() -> go.invoke(window, dest));
			tau.ui.jobs.JobsScreen jobs = find(window, tau.ui.jobs.JobsScreen.class);
			onEdt(() -> {
				jobs.open(id);
				return null;
			});
			Thread.sleep(800);
		}

		static <T> T find(java.awt.Container c, Class<T> type) {
			for (java.awt.Component k : c.getComponents()) {
				if (type.isInstance(k)) return type.cast(k);
				if (k instanceof java.awt.Container kc) {
					T f = find(kc, type);
					if (f != null) return f;
				}
			}
			return null;
		}

		/** Sample requests only: they never reach the venue. */
		void seedInbox(int n) throws Exception {
			List<Inbox.Request> requests = new ArrayList<>();
			long now = System.currentTimeMillis();
			for (int i = 0; i < n; i++) {
				Inbox.Ask ask = new Inbox.Ask("a" + i, "approval", "Send the weekly summary to two recipients?", true,
					true, List.of(), List.of(), List.of());
				requests.add(new Inbox.Request("r" + i, "did:key:z6MkSample" + i, "Release notes",
					"Send email to 2 recipients", "The weekly summary is ready to send.", List.of(ask), covia.grid.hitl.Hitl.OPEN.toString(),
					now - i * 60_000L, now + 28 * 60_000L, null, null));
			}
			onEdt(() -> {
				window.setInbox(requests);
				return null;
			});
		}

		interface Call {
			Object call() throws Exception;
		}

		static void onEdt(Call c) throws Exception {
			AtomicReference<Exception> err = new AtomicReference<>();
			SwingUtilities.invokeAndWait(() -> {
				try {
					c.call();
				} catch (Exception e) {
					err.set(e);
				}
			});
			if (err.get() != null) throw err.get();
		}

		static void quietly(Call c) {
			try {
				c.call();
			} catch (Exception e) {
				System.err.println("  could not reach screen: " + e);
			}
		}

		static Object field(Object o, String name) throws Exception {
			Class<?> c = o.getClass();
			while (c != null) {
				try {
					Field f = c.getDeclaredField(name);
					f.setAccessible(true);
					return f.get(o);
				} catch (NoSuchFieldException e) {
					c = c.getSuperclass();
				}
			}
			throw new NoSuchFieldException(name);
		}
	}
}
