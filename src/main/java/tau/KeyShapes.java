package tau;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text shaped like a key, caught before it is sent into a chat, where it would
 * stay in the conversation and its job records. The first shape is the
 * model-key test's (provider keys); the rest are well-known prefixes that
 * almost never occur in ordinary words. Conservative on purpose: a miss is
 * the same as today, a false catch costs one question.
 */
public final class KeyShapes {

	/** A key found: what kind it looks like, and the text itself. */
	public record Found(String kind, String text, String secretName) {
		/** The key with its middle masked, for saying which text was meant. */
		public String masked() {
			return text.length() <= 10 ? "•••" : text.substring(0, 6) + "…" + text.substring(text.length() - 2);
		}
	}

	private record Shape(String kind, String secretName, Pattern pattern) {
	}

	private static final List<Shape> SHAPES = List.of(
		new Shape("an Anthropic key", "ANTHROPIC_API_KEY", Pattern.compile("\\bsk-ant-[-_A-Za-z0-9]{12,}")),
		new Shape("an OpenRouter key", "OPENROUTER_API_KEY", Pattern.compile("\\bsk-or-[-_A-Za-z0-9]{12,}")),
		new Shape("an OpenAI key", "OPENAI_API_KEY", Pattern.compile("\\bsk-(proj-)?[-_A-Za-z0-9]{20,}")),
		new Shape("an xAI key", "XAI_API_KEY", Pattern.compile("\\bxai-[-_A-Za-z0-9]{12,}")),
		new Shape("a Groq key", "GROQ_API_KEY", Pattern.compile("\\bgsk_[-_A-Za-z0-9]{12,}")),
		new Shape("a Google API key", "GOOGLE_API_KEY", Pattern.compile("\\bAIza[-_A-Za-z0-9]{30,}")),
		new Shape("an AWS access key", "AWS_ACCESS_KEY_ID", Pattern.compile("\\b(AKIA|ASIA)[0-9A-Z]{16}\\b")),
		new Shape("a GitHub token", "GITHUB_TOKEN", Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})")),
		new Shape("a Slack token", "SLACK_TOKEN", Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}")),
		new Shape("a private key", "PRIVATE_KEY", Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----")));

	private KeyShapes() {
	}

	/** The first key-shaped text in {@code text}, or null when there is none. */
	public static Found find(String text) {
		if (text == null || text.isEmpty()) return null;
		for (Shape s : SHAPES) {
			Matcher m = s.pattern().matcher(text);
			if (m.find()) return new Found(s.kind(), m.group(), s.secretName());
		}
		return null;
	}

	/** {@code text} with every key-shaped part masked. */
	public static String mask(String text) {
		if (text == null) return "";
		String out = text;
		for (Shape s : SHAPES) out = s.pattern().matcher(out).replaceAll("•••");
		return out;
	}
}
