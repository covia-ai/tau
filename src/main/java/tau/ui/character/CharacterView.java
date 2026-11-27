package tau.ui.character;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.HierarchyEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

import javax.accessibility.AccessibleContext;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.Timer;

import com.formdev.flatlaf.util.UIScale;

import tau.ui.character.Presence.Beat;
import tau.ui.character.Presence.Look;
import tau.ui.character.Presence.Moment;
import tau.ui.components.TauMark;

/**
 * The τ character at one size, playing the v16 beats (docs/CHARACTER.md):
 * signed poses crossfaded, never rotated; the eyes as screen-blend sprites
 * over an eyeless base, so they can look, squash, and blink; the one moment a
 * beat plays (the catch, the hop, the milestone jump); breathing on large
 * slots; and a floor shadow of its own that follows the hop.
 *
 * <p>Below 32 points, with no art ({@link CharacterArt#NONE}), for a status
 * beat, or for a pose the pack lacks, it shows its fallback, the τ glyph.
 * Sizes: 32 only crossfades and blinks; from 96 the eyes move, the figure
 * breathes, and moments play; from 128 it floats too. Reduced motion keeps
 * the crossfade only, and Off swaps stills ({@link Motion}). The timer runs
 * only while the view is showing and something moves, and the loops stop a
 * minute after the last beat.
 */
@SuppressWarnings("serial")
public final class CharacterView extends JComponent {

	/** Below this many points the glyph stands in. */
	public static final int MIN_CHARACTER = 32;
	/** From this size the eyes move, the figure breathes, and moments play. */
	public static final int FULL_FIGURE = 96;
	static final int FLOAT_FROM = 128;

	static final int FRAME_MILLIS = 33;
	static final long DWELL = 600;
	static final long SETTLE = 60_000;
	static final long BLINK = 160;
	static final long CATCH_HOLD = 600;
	static final long SHIVER = 240;
	static final double BREATHE_PERIOD = 4800;

	private final int size;
	private final Icon fallback;
	private CharacterArt art;
	private LongSupplier clock = System::currentTimeMillis;
	/** The motion for this view alone, or null for the app's: for tests, which run side by side. */
	private Motion motion;
	private final Random random = new Random();
	private final Timer timer;

	private Beat beat = Beat.IDLE;
	private Look look = Presence.look(Beat.IDLE);
	private Beat pending;
	private long beatAt;
	private long lastEvent;

	/** The crossfade: from one shown pose (null: the glyph) to another. */
	private String fromPose;
	private String toPose = "rest";
	private long fadeAt = Long.MIN_VALUE / 2;
	private int fadeMillis;
	/** After a catch, the pose to settle back to, and when. */
	private String afterCatch;
	private long catchUntil;

	private double gazeX, gazeY, gazeToX, gazeToY;
	private double squash = 1, squashTo = 1;
	private Moment moment = Moment.NONE;
	private long momentAt;
	private long nextBlink;
	private long blinkAt = Long.MIN_VALUE / 2;
	private boolean doubleBlink;

	private final Map<String, BufferedImage> scaled = new HashMap<>();
	private int scaledFor;

	/** The character at {@code size} points, with the plain τ as its fallback. */
	public CharacterView(int size) {
		this(size, null);
	}

	/** {@code fallback}: what shows with no character, or null for the plain τ at the same size. */
	public CharacterView(int size, Icon fallback) {
		this(size, fallback, CharacterArt.get());
	}

	/** The character at {@code size} with {@code art} rather than the app's: for a slot given its art. */
	public static CharacterView withArt(int size, CharacterArt art) {
		return new CharacterView(size, null, art);
	}

	private CharacterView(int size, Icon fallback, CharacterArt art) {
		this.size = size;
		this.fallback = (fallback != null) ? fallback : TauMark.plain(Math.max(16, size * 2 / 3));
		this.art = (art == null) ? CharacterArt.NONE : art;
		setOpaque(false);
		timer = new Timer(FRAME_MILLIS, e -> tick());
		timer.setCoalesce(true);
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
				if (isShowing()) wake();
				else timer.stop();
			}
		});
		long now = clock.getAsLong();
		beatAt = now;
		lastEvent = now;
		nextBlink = now + blinkGap();
		toPose = shownPose(look);
		describe();
	}

	/**
	 * The mark for a greeting slot (an empty state, the empty chat): the
	 * character at rest when there is art, else today's plain τ at the same size.
	 */
	public static JComponent orMark(int size) {
		return orMarkFor(size, CharacterArt.get());
	}

	static JComponent orMarkFor(int size, CharacterArt art) {
		if (!art.present()) {
			javax.swing.JLabel mark = new javax.swing.JLabel(TauMark.plain(size));
			mark.setName("tau-mark");
			return mark;
		}
		return new CharacterView(size, TauMark.plain(size), art);
	}

	// ------------------------------------------------------------------
	// Beats
	// ------------------------------------------------------------------

	/** Moves to {@code next}; a beat replaced within its dwell waits its turn. */
	public void setBeat(Beat next) {
		if (next == null || next == beat && pending == null) return;
		long now = clock.getAsLong();
		if (now - beatAt < DWELL && motion() != Motion.OFF) {
			pending = next;
			wake();
			return;
		}
		pending = null;
		enter(next, now);
	}

	private void enter(Beat next, long now) {
		Beat was = beat;
		beat = next;
		look = Presence.look(next);
		beatAt = now;
		lastEvent = now;
		String target = shownPose(look);
		boolean big = size >= FULL_FIGURE && motion().loops();
		moment = big ? look.moment() : Moment.NONE;
		momentAt = now;
		afterCatch = null;
		if (moment == Moment.CATCH && art.has(Presence.CATCH_POSE)) {
			// The gloves catch the spark: a crossfade to the clasp, held, then back.
			fadeTo(Presence.CATCH_POSE, now);
			afterCatch = target;
			catchUntil = now + motion().crossfadeMillis() + CATCH_HOLD;
		} else {
			fadeTo(target, now);
		}
		gazeToX = big ? look.gaze().dx : 0;
		gazeToY = big ? look.gaze().dy : 0;
		if (!big) {
			gazeX = gazeToX;
			gazeY = gazeToY;
		}
		squashTo = look.squash() ? 0.5 : 1;
		if (!motion().loops()) squash = squashTo;
		if (was != next) describe();
		wake();
		repaint();
	}

	private void fadeTo(String pose, long now) {
		if (java.util.Objects.equals(pose, toPose) && now - fadeAt >= fadeMillis) return;
		fromPose = toPose;
		toPose = pose;
		fadeAt = now;
		fadeMillis = motion().crossfadeMillis();
	}

	/** The pose shown for {@code l} at this size with this art, or null for the glyph. */
	private String shownPose(Look l) {
		if (size < MIN_CHARACTER || !art.present() || !l.character() || !art.has(l.pose())) return null;
		return l.pose();
	}

	public Beat beat() {
		return beat;
	}

	/** Whether the character (not the glyph) is what shows once any crossfade ends. */
	public boolean showsCharacter() {
		return toPose != null;
	}

	/** The pose shown once any crossfade and catch end, or null for the glyph. */
	public String pose() {
		return afterCatch != null ? afterCatch : toPose;
	}

	/** The pose the crossfade is heading to now (the clasp during a catch), or null for the glyph. */
	public String showing() {
		return toPose;
	}

	/** Whether the timer is running: only while showing and something moves. */
	public boolean animating() {
		return timer.isRunning();
	}

	// ------------------------------------------------------------------
	// Time
	// ------------------------------------------------------------------

	private void wake() {
		if (isShowing() && moving(clock.getAsLong())) timer.start();
	}

	/** Advances one frame: for the timer, and for tests with their own clock. */
	void tick() {
		long now = clock.getAsLong();
		advance(now);
		repaint();
		if (!moving(now) || !isShowing()) timer.stop();
	}

	/** Moves the state on to {@code now}: a waiting beat, the catch's return, the gaze, the squash, the blink. */
	private void advance(long now) {
		if (pending != null && now - beatAt >= DWELL) {
			Beat b = pending;
			pending = null;
			enter(b, now);
		}
		if (afterCatch != null && now >= catchUntil) {
			fadeTo(afterCatch, now);
			afterCatch = null;
		}
		if (motion().loops()) {
			double k = Math.min(1, FRAME_MILLIS / 90.0);
			gazeX += (gazeToX - gazeX) * k;
			gazeY += (gazeToY - gazeY) * k;
			squash += (squashTo - squash) * Math.min(1, FRAME_MILLIS / 120.0);
			if (blinks() && now >= nextBlink) {
				blinkAt = now;
				doubleBlink = random.nextInt(6) == 0;
				nextBlink = now + blinkGap();
			}
		}
	}

	/** Whether anything still moves at {@code now}. */
	boolean moving(long now) {
		if (pending != null || afterCatch != null) return true;
		if (now - fadeAt < fadeMillis) return true;
		if (!motion().loops() || !showsCharacter()) return false;
		if (now - momentAt < momentLength()) return true;
		if (Math.abs(gazeToX - gazeX) > 1e-4 || Math.abs(gazeToY - gazeY) > 1e-4 || Math.abs(squashTo - squash) > 1e-3) {
			return true;
		}
		if (now - lastEvent >= SETTLE) return false;
		return blinks() || breathes();
	}

	private boolean blinks() {
		return motion().loops() && size >= MIN_CHARACTER && showsCharacter() && hasEyes(toPose);
	}

	private boolean breathes() {
		return motion().loops() && size >= FULL_FIGURE && look.breathes();
	}

	private long momentLength() {
		return switch (moment) {
			case SHIVER -> SHIVER;
			case CATCH -> motion().crossfadeMillis() * 2L + CATCH_HOLD;
			case HOP -> 720;
			case JUMP -> 920;
			case NONE -> 0;
		};
	}

	private long blinkGap() {
		return 3500 + random.nextInt(3500);
	}

	private Motion motion() {
		return motion != null ? motion : Motion.get();
	}

	// ------------------------------------------------------------------
	// Painting
	// ------------------------------------------------------------------

	@Override
	public Dimension getPreferredSize() {
		int s = UIScale.scale(size);
		return new Dimension(s, s);
	}

	@Override
	public Dimension getMinimumSize() {
		return getPreferredSize();
	}

	@Override
	public Dimension getMaximumSize() {
		return getPreferredSize();
	}

	@Override
	protected void paintComponent(Graphics g) {
		// Centred in whatever room a layout gives it.
		int s = Math.min(getWidth(), getHeight());
		paintFrame((Graphics2D) g, (getWidth() - s) / 2, (getHeight() - s) / 2, s, clock.getAsLong());
	}

	/** Paints the current frame in a square of {@code s} pixels at ({@code x}, {@code y}). */
	void paintFrame(Graphics2D g0, int x, int y, int s, long now) {
		if (s <= 0) return;
		Graphics2D g = (Graphics2D) g0.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			double t = fadeMillis <= 0 ? 1 : Math.min(1, Math.max(0, (now - fadeAt) / (double) fadeMillis));
			t = easeOut(t);
			if (fromPose != null || toPose != null) paintShadow(g, x, y, s, now, toPose != null ? t : 1 - t);
			if (t < 1) paintPose(g, fromPose, x, y, s, now, 1 - t);
			paintPose(g, toPose, x, y, s, now, t);
		} finally {
			g.dispose();
		}
	}

	private void paintPose(Graphics2D g, String pose, int x, int y, int s, long now, double alpha) {
		if (alpha <= 0.01) return;
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
			if (pose == null) {
				fallback.paintIcon(this, g2, x + (s - fallback.getIconWidth()) / 2, y + (s - fallback.getIconHeight()) / 2);
				return;
			}
			double scale = deviceScale(g);
			int px = (int) Math.round(s * scale);
			BufferedImage frame = frame(pose, px, now);
			if (frame == null) {
				fallback.paintIcon(this, g2, x + (s - fallback.getIconWidth()) / 2, y + (s - fallback.getIconHeight()) / 2);
				return;
			}
			g2.transform(body(x, y, s, now));
			g2.drawImage(frame, x, y, s, s, null);
		} finally {
			g2.dispose();
		}
	}

	/** The whole figure's transform: breathe, float, shiver, and the hop or jump, about the base. */
	private AffineTransform body(int x, int y, int s, long now) {
		double[] m = motionAt(s, now);
		double ax = x + s / 2.0, ay = y + s * 0.86;
		AffineTransform at = new AffineTransform();
		at.translate(ax + m[2], ay + m[3]);
		at.scale(m[0], m[1]);
		at.translate(-ax, -ay);
		return at;
	}

	/** {scale x, scale y, shift x, shift y} of the whole figure at {@code now}. */
	private double[] motionAt(int s, long now) {
		double sx = 1, sy = 1, dx = 0, dy = 0;
		long since = now - momentAt;
		if (moment == Moment.HOP || moment == Moment.JUMP) {
			double air = moment == Moment.HOP ? 220 : 420, rise = moment == Moment.HOP ? 0.06 : 0.18;
			if (since < 160) {
				double k = Math.sin(Math.PI / 2 * since / 160.0);
				sy = 1 - 0.06 * k;
				sx = 1 + 0.04 * k;
			} else if (since < 300) {
				double k = (since - 160) / 140.0;
				sy = 0.94 + 0.10 * k;
				sx = 1.04 - 0.04 * k;
				dy = -rise * s * k * 0.4;
			} else if (since < 300 + air) {
				double k = (since - 300) / air;
				dy = -rise * s * (0.4 + 0.6 * Math.sin(Math.PI * k));
				sy = 1.04 - 0.04 * k;
			} else if (since < 500 + air) {
				double k = (since - 300 - air) / 200.0;
				double d = Math.sin(Math.PI * k);
				sy = 1 - 0.04 * d;
				sx = 1 + 0.03 * d;
			}
		} else if (moment == Moment.SHIVER && since < SHIVER) {
			dx = Math.sin(since / 18.0) * 0.012 * s * (1 - since / (double) SHIVER);
		}
		if (breathes() && now - lastEvent < SETTLE) {
			double phase = (now % (long) BREATHE_PERIOD) / BREATHE_PERIOD;
			double b = 0.006 * (1 - Math.cos(2 * Math.PI * phase));
			sx *= 1 + b;
			sy *= 1 + b;
			if (size >= FLOAT_FROM) dy -= 0.012 * s * 0.5 * (1 - Math.cos(2 * Math.PI * phase));
		}
		return new double[] { sx, sy, dx, dy };
	}

	/** Tau's own floor shadow: narrower as the figure rises, amber while it waits on you. */
	private void paintShadow(Graphics2D g, int x, int y, int s, long now, double alpha) {
		if (size < FULL_FIGURE || alpha <= 0.01) return;
		double lift = Math.min(0, motionAt(s, now)[3]) / s;
		double w = s * 0.44 * (1 + lift * 1.5), h = s * 0.06;
		double cx = x + s / 2.0, cy = y + s * 0.885;
		Color c = look.warm() ? new Color(0xF5, 0xB3, 0x3C) : new Color(0x7B, 0x5C, 0xF0);
		float a = (float) (0.30 * alpha);
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), (float) (w / 2), new float[] { 0f, 1f },
				new Color[] { new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.round(255 * a)),
					new Color(c.getRed(), c.getGreen(), c.getBlue(), 0) }));
			g2.transform(AffineTransform.getScaleInstance(1, h / w));
			g2.fill(new java.awt.geom.Ellipse2D.Double(cx - w / 2, (cy - h / 2) * w / h, w, w));
		} finally {
			g2.dispose();
		}
	}

	/** The pose at {@code px} pixels with its eyes screened on where they look now, or null when it can't be read. */
	private BufferedImage frame(String pose, int px, long now) {
		CharacterArt.Pose p = art.pose(pose);
		if (p == null) return null;
		BufferedImage base = base(p, px);
		if (p.sprites().isEmpty()) return base;
		BufferedImage out = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
		int[] dst = ((java.awt.image.DataBufferInt) out.getRaster().getDataBuffer()).getData();
		base.getRGB(0, 0, px, px, dst, 0, px);
		double open = eyeOpen(now) * (pose.equals(toPose) || pose.equals(fromPose) ? squash : 1);
		List<CharacterArt.Eye> eyes = p.spec().eyes();
		for (int i = 0; i < eyes.size(); i++) {
			double[] b = eyes.get(i).box();
			double bx = (b[0] + gazeX) * px, by = (b[1] + gazeY) * px;
			double bw = (b[2] - b[0]) * px, bh = (b[3] - b[1]) * px;
			double h = Math.max(0, bh * open);
			if (h < 0.5) continue;
			screen(dst, px, p.sprites().get(i), bx, by + (bh - h) / 2, bw, h);
		}
		return out;
	}

	/** How open the eyes are now: 1 open, near 0 at the middle of a blink. */
	private double eyeOpen(long now) {
		if (!motion().loops()) return 1;
		long since = now - blinkAt;
		long length = doubleBlink ? BLINK * 2 + 80 : BLINK;
		if (since < 0 || since >= length) return 1;
		long within = since % (BLINK + 40);
		if (within >= BLINK) return 1;
		return 0.08 + 0.92 * Math.abs(Math.cos(Math.PI * within / BLINK));
	}

	/** Screens {@code sprite}, scaled into the box, onto the opaque body in {@code dst}. */
	private static void screen(int[] dst, int px, BufferedImage sprite, double bx, double by, double bw, double bh) {
		int x0 = (int) Math.floor(bx), y0 = (int) Math.floor(by), x1 = (int) Math.ceil(bx + bw), y1 = (int) Math.ceil(by + bh);
		int sw = sprite.getWidth(), sh = sprite.getHeight();
		for (int y = Math.max(0, y0); y < Math.min(px, y1); y++) {
			double v = (y + 0.5 - by) / bh * sh;
			if (v < 0 || v >= sh) continue;
			for (int x = Math.max(0, x0); x < Math.min(px, x1); x++) {
				double u = (x + 0.5 - bx) / bw * sw;
				if (u < 0 || u >= sw) continue;
				int s = sprite.getRGB((int) u, (int) v);
				int d = dst[y * px + x];
				if ((d >>> 24) == 0) continue;
				int r = 255 - (255 - ((d >> 16) & 255)) * (255 - ((s >> 16) & 255)) / 255;
				int gg = 255 - (255 - ((d >> 8) & 255)) * (255 - ((s >> 8) & 255)) / 255;
				int bb = 255 - (255 - (d & 255)) * (255 - (s & 255)) / 255;
				dst[y * px + x] = (d & 0xFF000000) | (r << 16) | (gg << 8) | bb;
			}
		}
	}

	/** The pose's base at {@code px} pixels, scaled once in halving steps and kept. */
	private BufferedImage base(CharacterArt.Pose p, int px) {
		if (scaledFor != px) {
			scaled.clear();
			scaledFor = px;
		}
		return scaled.computeIfAbsent(p.spec().name(), k -> {
			BufferedImage cur = p.base();
			while (cur.getWidth() / 2 >= px) cur = draw(cur, cur.getWidth() / 2);
			return cur.getWidth() == px ? copy(cur) : draw(cur, px);
		});
	}

	private static BufferedImage draw(BufferedImage src, int px) {
		BufferedImage b = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = b.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		g.drawImage(src, 0, 0, px, px, null);
		g.dispose();
		return b;
	}

	private static BufferedImage copy(BufferedImage src) {
		return draw(src, src.getWidth());
	}

	private static double deviceScale(Graphics2D g) {
		double s = g.getTransform().getScaleX();
		return s > 0 ? s : 1;
	}

	private static double easeOut(double t) {
		return 1 - Math.pow(1 - t, 3);
	}

	private boolean hasEyes(String pose) {
		CharacterArt.Pose p = (pose == null) ? null : art.pose(pose);
		return p != null && !p.sprites().isEmpty();
	}

	// ------------------------------------------------------------------
	// Words and tests
	// ------------------------------------------------------------------

	private void describe() {
		AccessibleContext ac = getAccessibleContext();
		ac.setAccessibleName("Tau");
		ac.setAccessibleDescription(beat.description);
	}

	@Override
	public AccessibleContext getAccessibleContext() {
		if (accessibleContext == null) {
			accessibleContext = new AccessibleJComponent() {
				@Override
				public javax.accessibility.AccessibleRole getAccessibleRole() {
					return javax.accessibility.AccessibleRole.ICON;
				}
			};
		}
		return accessibleContext;
	}

	/** Uses another clock and art: for tests. */
	void testWith(LongSupplier clock, CharacterArt art, Motion motion) {
		this.clock = clock;
		this.motion = motion;
		this.art = (art == null) ? CharacterArt.NONE : art;
		scaled.clear();
		long now = clock.getAsLong();
		beatAt = now - DWELL;
		lastEvent = now;
		fadeAt = Long.MIN_VALUE / 2;
		fromPose = null;
		toPose = shownPose(look);
	}

	/**
	 * The view as an icon, for a dialog's icon slot (About): it paints the
	 * current frame and keeps the dialog repainting while it moves.
	 */
	public Icon asIcon() {
		return new Icon() {
			@Override
			public void paintIcon(Component c, Graphics g, int x, int y) {
				int s = getIconWidth();
				long now = clock.getAsLong();
				advance(now);
				paintFrame((Graphics2D) g, x, y, s, now);
				if (c != null && motion().loops() && showsCharacter() && moving(clock.getAsLong())) {
					Timer again = new Timer(FRAME_MILLIS, e -> c.repaint());
					again.setRepeats(false);
					again.start();
				}
			}

			@Override
			public int getIconWidth() {
				return UIScale.scale(size);
			}

			@Override
			public int getIconHeight() {
				return UIScale.scale(size);
			}
		};
	}
}
