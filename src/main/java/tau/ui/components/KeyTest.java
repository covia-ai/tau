package tau.ui.components;

import java.awt.Component;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingWorker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tau.KeyCheck;

/**
 * A <b>Test</b> button for a model and its key, and the answer beneath it: the
 * check ({@link KeyCheck}) runs off the event thread, then a line in a tone
 * says how it went and, under it, the venue's own reason for a failure, which
 * can be selected and copied. What to test is read on the event thread when
 * the button is pressed ({@code prepare}). A change to what the answer
 * describes clears it ({@link #reset}), and drops one still on its way.
 */
@SuppressWarnings("serial")
public final class KeyTest extends JPanel {

	private static final Logger log = LoggerFactory.getLogger(KeyTest.class);

	private final JButton button = Buttons.small("Test");
	private final StatusLabel status = new StatusLabel(" ", null, Tone.MUTED);
	/** As wide as a settings field may grow, so a long reason wraps within it. */
	private final SelectableText reason = SelectableText.description("").small().wrapAt(400);
	private final Supplier<Supplier<KeyCheck.Result>> prepare;
	private boolean wanted = true;
	private boolean running;
	/** Bumped whenever what the answer describes changes, so an answer for the old one is dropped. */
	private int generation;

	public KeyTest(Supplier<Supplier<KeyCheck.Result>> prepare) {
		this.prepare = prepare;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setOpaque(false);
		button.setToolTipText("Send the model one short request to check that it and the key work");
		button.addActionListener(e -> run());
		button.setAlignmentX(Component.LEFT_ALIGNMENT);
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		add(button);
		add(Box.createVerticalStrut(6));
		add(status);
		add(Box.createVerticalStrut(2));
		add(reason);
		reason.setVisible(false);
	}

	/** Enables the button, unless a test is already running. */
	public void setTestEnabled(boolean on) {
		wanted = on;
		button.setEnabled(on && !running);
	}

	/** Clears the answer: the model or key it described has changed. One still running is not shown. */
	public void reset() {
		generation++;
		answer(" ", null, Tone.MUTED, null);
	}

	private void run() {
		Supplier<KeyCheck.Result> test = prepare.get();
		int asked = ++generation;
		running = true;
		button.setEnabled(false);
		answer("Testing…", "loader-circle", Tone.MUTED, null);
		new SwingWorker<KeyCheck.Result, Void>() {
			@Override
			protected KeyCheck.Result doInBackground() {
				return test.get();
			}

			@Override
			protected void done() {
				running = false;
				button.setEnabled(wanted);
				if (asked != generation) return;
				try {
					KeyCheck.Result r = get();
					answer(headline(r), r.ok() ? "circle-check" : "circle-alert", r.ok() ? Tone.SUCCESS : Tone.ERROR,
						r.message());
				} catch (ExecutionException e) {
					log.warn("The model test could not run", e.getCause());
					answer("The test couldn't run.", "circle-alert", Tone.ERROR, null);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}.execute();
	}

	/** What the outcome means for the owner; the venue's reason follows it. */
	static String headline(KeyCheck.Result r) {
		return switch (r.outcome()) {
			case WORKS -> String.format(Locale.ROOT, "Works: the model replied in %.1f s.", r.millis() / 1000.0);
			case SETUP -> "Not working: the key or the model needs fixing.";
			case UNAVAILABLE -> "The provider couldn't answer just now. Try again later.";
			case FAILED -> "The test call failed.";
		};
	}

	private void answer(String text, String icon, Tone tone, String detail) {
		status.setText(text);
		status.setIcon((icon != null) ? Lucide.icon(icon, 14, () -> status.tone().colour()) : null);
		status.tone(tone);
		reason.setText((detail != null) ? detail : "");
		reason.setVisible(detail != null);
		revalidate();
		repaint();
	}
}
