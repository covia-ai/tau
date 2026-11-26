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
 * there too. With character art, the 32 px character stands in for the glyph
 * and plays the beat for what Tau is doing ({@link #setActivity}), except
 * while a quiet screen is open ({@link #setQuietScreenOpen}): the Record, the
 * Vault, the Constellation, an agent's profile, a refusal, or an error.
 */
@SuppressWarnings("serial")
public final class StatusSlot extends JPanel {

	/** What Tau is doing, for the character's beat. The slot's words name only waiting decisions. */
	public enum State {
		RESTING, THINKING, AT_WORK, WAITING, DONE
	}

	private static final String WAITING_WORDS = "Waiting on you";

	private final Glyph glyph = new Glyph();
	/** The character at 32 px, when there is art for it: it only crossfades and blinks. */
	private final tau.ui.character.CharacterView figure;
	private boolean showingFigure;
	private final JLabel label = Labels.small("");
	private State activity = State.RESTING;
	private int waiting;
	private boolean collapsed;
	private boolean quietScreenOpen;

	public StatusSlot() {
		this(tau.ui.character.CharacterArt.get());
	}

	/** With {@code art} for the character, or {@link tau.ui.character.CharacterArt#NONE} for the glyph alone. */
	StatusSlot(tau.ui.character.CharacterArt art) {
		super(new BorderLayout(UIScale.scale(8), 0));
		figure = art.present() ? tau.ui.character.CharacterView.withArt(32, art) : null;
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		Styles.classes(label, Styles.SMALL, Styles.STRONG);
		add(glyph, BorderLayout.WEST);
		add(label, BorderLayout.CENTER);
		update();
	}

	/** How many decisions wait for the owner; none leaves the glyph (or character) without words. */
	public void setWaiting(int count) {
		waiting = Math.max(0, count);
		update();
	}

	/** What Tau is doing, which sets the character's beat; waiting decisions win over it. */
	public void setActivity(State activity) {
		this.activity = (activity != null) ? activity : State.RESTING;
		update();
	}

	/** The beat in force: waiting while decisions wait, else the activity. */
	public State state() {
		return (waiting > 0) ? State.WAITING : activity;
	}

	/** The words the slot shows, or carries as its tooltip when collapsed; null while nothing waits. */
	public String text() {
		return (waiting > 0) ? WAITING_WORDS + " · " + waiting + (waiting == 1 ? " decision" : " decisions") : null;
	}

	public void setCollapsed(boolean collapsed) {
		this.collapsed = collapsed;
		update();
	}

	/** A quiet screen is open: the slot shows the glyph alone. */
	public void setQuietScreenOpen(boolean open) {
		quietScreenOpen = open;
		update();
	}

	public boolean quietScreenOpen() {
		return quietScreenOpen;
	}

	/**
	 * Whether the slot shows anything beyond the τ glyph and its words: the
	 * character, at 32 px, when there is art for it, the slot is open, and no
	 * quiet screen is. Collapsed (below 32 px of room) it is the glyph alone.
	 */
	public boolean showsMoreThanGlyph() {
		return showingFigure;
	}

	/** The beat the character plays for each state. */
	static tau.ui.character.Presence.Beat beat(State s) {
		return switch (s) {
			case RESTING -> tau.ui.character.Presence.Beat.IDLE;
			case THINKING -> tau.ui.character.Presence.Beat.THINKING;
			case AT_WORK -> tau.ui.character.Presence.Beat.WORKING;
			case WAITING -> tau.ui.character.Presence.Beat.WAITING;
			case DONE -> tau.ui.character.Presence.Beat.DONE;
		};
	}

	private void update() {
		String t = text();
		// The words on the card; the count rides in the tooltip and the accessible name.
		boolean words = t != null && !collapsed;
		label.setText(words ? WAITING_WORDS : null);
		label.setVisible(words);
		boolean show = figure != null && !collapsed && !quietScreenOpen;
		java.awt.Component want = show ? figure : glyph;
		java.awt.Component now = ((BorderLayout) getLayout()).getLayoutComponent(BorderLayout.WEST);
		if (now != want) {
			if (now != null) remove(now);
			add(want, BorderLayout.WEST);
		}
		showingFigure = show;
		if (figure != null) figure.setBeat(beat(state()));
		setToolTipText(APP_NAME + ": " + (t != null ? t : "nothing is waiting for you"));
		getAccessibleContext().setAccessibleName((t != null) ? APP_NAME + " is " + t.toLowerCase(java.util.Locale.ROOT) : APP_NAME);
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
