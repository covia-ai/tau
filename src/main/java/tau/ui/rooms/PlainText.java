package tau.ui.rooms;

import java.awt.Font;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.text.PlainDocument;

import tau.ui.components.Labels;
import tau.ui.components.Styles;

/**
 * Text that others wrote, shown as text and nothing else: a room's posts, its
 * description, a poster's name, a token's name, and notifications. Swing reads
 * any label or tooltip text that starts with {@code <html>} as HTML, which could
 * load a remote image (a request from this computer) or fake the screen's own
 * layout, so such text never reaches a renderer that would do that: bodies go
 * into a read-only text area over a plain document, and labels have HTML turned
 * off. Hidden characters are made visible, so a right-to-left override cannot
 * pass {@code invoice_<RLO>fdp.exe} off as a PDF.
 */
public final class PlainText {

	/** Posts fold after this many lines. */
	public static final int FOLD_LINES = 12;
	/** Or after this many characters. */
	public static final int FOLD_CHARS = 1200;

	private PlainText() {
	}

	/**
	 * {@code text} with every bidirectional override and isolate, zero-width
	 * character, and control character other than a line break or a tab shown
	 * as a small marker, such as {@code ‹RLO›} or {@code ‹U+0007›}. Everything
	 * else is kept as it is.
	 */
	public static String visible(String text) {
		if (text == null) return "";
		StringBuilder out = new StringBuilder(text.length());
		text.codePoints().forEach(c -> {
			String name = marker(c);
			if (name != null) out.append('‹').append(name).append('›');
			else out.appendCodePoint(c);
		});
		return out.toString();
	}

	/** The marker's name for a character that must be made visible, or null for one shown as it is. */
	static String marker(int c) {
		return switch (c) {
			case 0x202A -> "LRE";
			case 0x202B -> "RLE";
			case 0x202C -> "PDF";
			case 0x202D -> "LRO";
			case 0x202E -> "RLO";
			case 0x2066 -> "LRI";
			case 0x2067 -> "RLI";
			case 0x2068 -> "FSI";
			case 0x2069 -> "PDI";
			case 0x200B -> "ZWSP";
			case 0x200C -> "ZWNJ";
			case 0x200D -> "ZWJ";
			case 0x2060 -> "WJ";
			case 0xFEFF -> "BOM";
			case 0x200E -> "LRM";
			case 0x200F -> "RLM";
			case 0x061C -> "ALM";
			default -> (c == '\n' || c == '\t') ? null
				: (Character.getType(c) == Character.CONTROL || Character.getType(c) == Character.FORMAT)
					? String.format("U+%04X", c) : null;
		};
	}

	/** Whether a post should fold: more than {@link #FOLD_LINES} lines or {@link #FOLD_CHARS} characters. */
	public static boolean folds(String text) {
		if (text == null) return false;
		return text.length() > FOLD_CHARS || text.chars().filter(c -> c == '\n').count() >= FOLD_LINES;
	}

	/** The folded part of a post: its first {@link #FOLD_LINES} lines, and no more than {@link #FOLD_CHARS} characters. */
	public static String folded(String text) {
		if (text == null) return "";
		String[] lines = text.split("\n", -1);
		String head = (lines.length > FOLD_LINES) ? String.join("\n", java.util.Arrays.copyOf(lines, FOLD_LINES)) : text;
		return head.length() > FOLD_CHARS ? head.substring(0, FOLD_CHARS) : head;
	}

	/**
	 * A label for text others wrote: HTML off, hidden characters visible. Use
	 * it for names, token names, descriptions on one line, and anything else
	 * from a room.
	 */
	public static JLabel label(String text) {
		JLabel l = new JLabel();
		noHtml(l);
		l.setText(visible(text));
		return l;
	}

	/** A small muted label for text others wrote. */
	public static JLabel small(String text) {
		JLabel l = label(text);
		Styles.classes(l, Styles.SMALL, Styles.MUTED);
		return l;
	}

	/** Turns Swing's HTML rendering off on {@code c}, its tooltip included. */
	public static <C extends JComponent> C noHtml(C c) {
		c.putClientProperty("html.disable", Boolean.TRUE);
		return c;
	}

	/**
	 * A post's body: a read-only, wrapping text area over a plain document,
	 * which Swing never treats as HTML. Text can be selected and copied; copying
	 * gives the visible form, with its markers.
	 */
	public static JTextArea body(String text) {
		JTextArea a = new JTextArea(new PlainDocument());
		noHtml(a);
		a.setEditable(false);
		a.setLineWrap(true);
		a.setWrapStyleWord(true);
		a.setOpaque(false);
		a.setBorder(null);
		a.setFocusable(true);
		Font f = UIManager.getFont("Label.font");
		if (f != null) a.setFont(f);
		a.setText(visible(text));
		a.setCaretPosition(0);
		return a;
	}

	/** A label for Tau's own words (never others' text): the house label. */
	static JLabel own(String text) {
		return Labels.text(text);
	}
}
