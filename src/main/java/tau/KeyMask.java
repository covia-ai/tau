package tau;

import java.util.regex.Pattern;

/**
 * Hides what looks like a secret in text shown as a title. A key pasted into
 * a chat by mistake would otherwise become the conversation's title and its
 * job's title on the Jobs page. This masks the title only: the message
 * itself is kept as sent.
 */
public final class KeyMask {

	/** Shown in place of a masked run. */
	public static final String MASK = "•••";

	/**
	 * Well-known key prefixes followed by their token, or a long unbroken run
	 * of token characters that mixes letters and digits, as keys and
	 * passwords do and words do not.
	 */
	private static final Pattern KEY = Pattern.compile(
			"(?:sk|pk|rk)[-_][A-Za-z0-9_\\-]{12,}"
			+ "|(?:ghp|gho|ghu|ghs|ghr|github_pat|glpat|xox[abprs])[-_][A-Za-z0-9_\\-]{8,}"
			+ "|AKIA[A-Z0-9]{12,}|AIza[A-Za-z0-9_\\-]{20,}"
			+ "|-----BEGIN [A-Z ]*PRIVATE KEY-----"
			+ "|(?=[A-Za-z0-9_\\-+/=]*[0-9])(?=[A-Za-z0-9_\\-+/=]*[A-Za-z])[A-Za-z0-9_\\-+/=]{32,}");

	private KeyMask() {}

	/** The text with each key-like run replaced by {@link #MASK}; null stays null. */
	public static String mask(String text) {
		return (text == null) ? null : KEY.matcher(text).replaceAll(MASK);
	}
}
