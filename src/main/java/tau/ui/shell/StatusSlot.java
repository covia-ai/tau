package tau.ui.shell;

import static tau.Tau.APP_NAME;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;

import tau.ui.components.Labels;
import tau.ui.components.Styles;
import tau.ui.components.Theme;

/**
 * The status slot at the foot of the sidebar: the τ glyph on a card, saying
 * "Waiting on you" beside it, with an amber dot, while decisions wait, and
 * the glyph alone otherwise. The count rides in the tooltip and the
 * accessible name ("Waiting on you · 1 decision"); collapsed, the words move
 * there too.
 */
@SuppressWarnings("serial")
public final class StatusSlot extends JPanel {

	private static final String WAITING = "Waiting on you";

	private final Glyph glyph = new Glyph();
	private final JLabel label = Labels.small("");
	private int waiting;
	private boolean collapsed;

	public StatusSlot() {
		super(new BorderLayout(UIScale.scale(8), 0));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		Styles.classes(label, Styles.SMALL, Styles.STRONG);
		add(glyph, BorderLayout.WEST);
		add(label, BorderLayout.CENTER);
		update();
	}

	/** How many decisions wait for the owner; none leaves the glyph alone. */
	public void setWaiting(int count) {
		waiting = Math.max(0, count);
		update();
	}

	/** The words the slot shows, or carries as its tooltip when collapsed; null while nothing waits. */
	public String text() {
		return (waiting > 0) ? WAITING + " · " + waiting + (waiting == 1 ? " decision" : " decisions") : null;
	}

	public void setCollapsed(boolean collapsed) {
		this.collapsed = collapsed;
		update();
	}

	private void update() {
		String t = text();
		// The words on the card; the count rides in the tooltip and the accessible name.
		boolean words = t != null && !collapsed;
		label.setText(words ? WAITING : null);
		label.setVisible(words);
		setToolTipText(APP_NAME + ": " + (t != null ? t : "nothing is waiting for you"));
		getAccessibleContext().setAccessibleName((t != null) ? APP_NAME + " is " + tau.Ascii.lower(t) : APP_NAME);
		revalidate();
		repaint();
	}

	@Override
	public Dimension getMaximumSize() {
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	@Override
	protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		try {
			FlatUIUtils.setRenderingHints(g2);
			g2.setColor(Theme.card());
			g2.fill(FlatUIUtils.createComponentRectangle(0, 0, getWidth(), getHeight(), UIScale.scale(20f)));
		} finally {
			g2.dispose();
		}
		super.paintComponent(g);
	}

	/**
	 * The τ glyph, 28 px: navy on a light tile in Light, the glyph on its own
	 * in Dark. Plain type, never the logo's gradient. The waiting state adds
	 * the amber dot, which the label always names too.
	 */
	private final class Glyph extends JComponent {
		@Override
		public Dimension getPreferredSize() {
			int s = UIScale.scale(28);
			return new Dimension(s, s);
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				FlatUIUtils.setRenderingHints(g2);
				int s = Math.min(getWidth(), getHeight());
				int y0 = (getHeight() - s) / 2;
				Color tile = Theme.isDark() ? Theme.chrome() : Theme.warmSurface();
				g2.setColor(tile);
				g2.fillRoundRect(0, y0, s, s, s / 2, s / 2);
				g2.setColor(Theme.foreground());
				g2.setFont(getFont().deriveFont(Font.BOLD, s * 0.62f));
				FontMetrics fm = g2.getFontMetrics();
				g2.drawString("τ", (s - fm.stringWidth("τ")) / 2f, y0 + (s + fm.getAscent() - fm.getDescent()) / 2f);
				if (waiting > 0) {
					int d = UIScale.scale(8);
					g2.setColor(Theme.yours());
					g2.fillOval(s - d, y0, d, d);
				}
			} finally {
				g2.dispose();
			}
		}
	}
}
