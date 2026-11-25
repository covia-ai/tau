package tau;

/**
 * Case changes of the ASCII letters A–Z alone, the same on every computer.
 * Java's own {@code toLowerCase()} and {@code toUpperCase()} follow the
 * computer's language: in Turkish, I lower-cases to a dotless ı and i
 * upper-cases to a dotted İ, which breaks recovery words, names and ids.
 * Every other character stays as it is.
 */
public final class Ascii {

	private Ascii() {
	}

	/** {@code s} with A–Z made a–z; null stays null. */
	public static String lower(String s) {
		if (s == null) return null;
		char[] c = s.toCharArray();
		for (int i = 0; i < c.length; i++) {
			if (c[i] >= 'A' && c[i] <= 'Z') c[i] += 'a' - 'A';
		}
		return new String(c);
	}

	/** {@code s} with a–z made A–Z; null stays null. */
	public static String upper(String s) {
		if (s == null) return null;
		char[] c = s.toCharArray();
		for (int i = 0; i < c.length; i++) {
			if (c[i] >= 'a' && c[i] <= 'z') c[i] -= 'a' - 'A';
		}
		return new String(c);
	}
}
