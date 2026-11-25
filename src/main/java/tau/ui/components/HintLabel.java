package tau.ui.components;

import java.awt.Cursor;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * A field label with a small ⓘ beside it whose hover text explains the field
 * — the explanation is there for whoever wants it without taking up the form.
 * Both the text and the hint can change with the app's state.
 */
@SuppressWarnings("serial")
public final class HintLabel extends JPanel {

	private static final int ICON = 14;

	private final JLabel text;
	private final JLabel icon;
	/** The hint shown in full elsewhere on the form, kept to it; null when there is none. */
	private SelectableText shown;

	public HintLabel(String label, String hint) {
		setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
		setOpaque(false);
		text = Labels.muted(label);
		icon = Labels.icon(Lucide.icon("info", ICON, Theme::muted));
		icon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		add(text);
		add(Box.createHorizontalStrut(5));
		add(icon);
		setHint(hint);
	}

	public void setText(String label) {
		text.setText(label);
	}

	/** The label at full strength rather than muted: a row's label on a card. */
	public HintLabel unmuted() {
		Styles.classes(text);
		return this;
	}

	/** The hover text; null hides the ⓘ, and the hint shown in full with it. */
	public void setHint(String hint) {
		boolean any = hint != null && !hint.isBlank();
		icon.setToolTipText(hint);
		icon.setVisible(any);
		if (shown != null) {
			shown.setText(any ? hint : "");
			shown.setVisible(any);
		}
	}

	/** {@code text} shows the hint in full, such as under the field, and follows it as it changes. */
	public HintLabel showsHintIn(SelectableText text) {
		shown = text;
		setHint(icon.getToolTipText());
		return this;
	}
}
