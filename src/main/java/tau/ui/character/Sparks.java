package tau.ui.character;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Point2D;

import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The sparks of a task (v16): at most three, never more. One flies in from
 * your message when a request starts and the gloves catch it; one flies out
 * to each agent Tau asks; none comes back, and none at hand-back. A spark is
 * a small point of light flown along an arc over the window, on its layered
 * pane, then gone. Reduced and Off motion show none, and nothing flies
 * between views that are not showing.
 */
public final class Sparks {

	/** How long one flight takes. */
	static final int FLIGHT_MILLIS = 640;

	private Sparks() {
	}

	/**
	 * Flies one spark from {@code from}'s centre to {@code to}'s catch point.
	 *
	 * @return whether a spark flew
	 */
	public static boolean fly(Component from, Component to) {
		if (!flies(Motion.get()) || from == null || to == null || !from.isShowing() || !to.isShowing()) return false;
		JRootPane root = SwingUtilities.getRootPane(to);
		if (root == null || SwingUtilities.getRootPane(from) != root) return false;
		JLayeredPane layers = root.getLayeredPane();
		Point a = SwingUtilities.convertPoint(from, from.getWidth() / 2, from.getHeight() / 2, layers);
		Point b = SwingUtilities.convertPoint(to, to.getWidth() / 2, (int) (to.getHeight() * 0.62), layers);
		Spark s = new Spark(a, b);
		s.setBounds(new Rectangle(0, 0, layers.getWidth(), layers.getHeight()));
		layers.add(s, JLayeredPane.DRAG_LAYER);
		s.start(() -> {
			layers.remove(s);
			layers.repaint();
		});
		return true;
	}

	/** Whether sparks fly at this motion: only at Full. */
	static boolean flies(Motion motion) {
		return motion.loops();
	}

	@SuppressWarnings("serial")
	private static final class Spark extends JComponent {
		private final Point a;
		private final Point b;
		private long startedAt;
		private Timer timer;

		Spark(Point a, Point b) {
			this.a = a;
			this.b = b;
			setOpaque(false);
		}

		void start(Runnable done) {
			startedAt = System.currentTimeMillis();
			timer = new Timer(CharacterView.FRAME_MILLIS, e -> {
				if (System.currentTimeMillis() - startedAt >= FLIGHT_MILLIS || !isShowing()) {
					timer.stop();
					done.run();
				} else {
					repaint();
				}
			});
			timer.start();
		}

		@Override
		public boolean contains(int x, int y) {
			return false; // never takes a click
		}

		@Override
		protected void paintComponent(Graphics g) {
			double t = Math.min(1, (System.currentTimeMillis() - startedAt) / (double) FLIGHT_MILLIS);
			double e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
			// A gentle arc above the straight line between the two points.
			double mx = (a.x + b.x) / 2.0, my = Math.min(a.y, b.y) - Math.abs(b.x - a.x) * 0.18 - 24;
			double x = (1 - e) * (1 - e) * a.x + 2 * (1 - e) * e * mx + e * e * b.x;
			double y = (1 - e) * (1 - e) * a.y + 2 * (1 - e) * e * my + e * e * b.y;
			float r = 11f;
			float fade = (float) (t < 0.85 ? 1 : (1 - t) / 0.15);
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setPaint(new RadialGradientPaint(new Point2D.Double(x, y), r, new float[] { 0f, 0.25f, 1f },
					new Color[] { new Color(255, 255, 255, Math.round(255 * fade)),
						new Color(0xC8, 0xBA, 0xFF, Math.round(200 * fade)), new Color(0x8D, 0x99, 0xFC, 0) }));
				g2.fill(new java.awt.geom.Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
			} finally {
				g2.dispose();
			}
		}
	}
}
