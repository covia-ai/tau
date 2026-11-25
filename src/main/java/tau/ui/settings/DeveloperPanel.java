package tau.ui.settings;

import static tau.Tau.APP_NAME;

import java.nio.file.Path;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import net.miginfocom.swing.MigLayout;
import tau.Diagnostics;
import tau.ui.components.Buttons;
import tau.ui.components.Clipboard;
import tau.ui.components.Labels;
import tau.ui.components.SelectableText;
import tau.ui.components.Styles;
import tau.ui.components.TextArea;
import tau.ui.components.Toast;
import tau.ui.components.Tone;

/**
 * The <b>Developer</b> settings page: what a developer needs to see what the
 * app and its venue are doing, in one place (docs/CONFIGURATION.md,
 * "Developer"). The log with a level and text filter; the venue's address,
 * binding, adapters and health; the versions; the configuration file; the raw
 * context of the chat on screen; the dashboard; and Copy diagnostics, a
 * redacted report for a bug ({@link Diagnostics}).
 */
@SuppressWarnings("serial")
public final class DeveloperPanel extends SettingsPage {

	/** What the page reads and does; calls may come off the event thread. */
	public interface Host {
		String tauVersion();

		String coviaVersion();

		/** The venue's address, or null while it is not running. */
		String venueUrl();

		/** The address the venue is bound to. */
		String bind();

		/** The venue's adapters, one line each ("http: running"), empty while it is not running. */
		List<String> adapters();

		/** The configuration file. */
		Path configFile();

		/** The current log file. */
		Path logFile();

		/** The safe-defaults posture, or null while the venue is not running. */
		Diagnostics.Posture posture();

		void openDashboard();

		void openConfigFile();

		void openLogsFolder();

		/** Shows what the model receives for the chat on screen (the Context tab's raw view). */
		void showRawContext();
	}

	private final Host host;
	private final JLabel venue = Labels.small(" ");
	private final JLabel versions = Labels.small(" ");
	private final SelectableText adapters = new SelectableText(" ").mono().small();
	private final SelectableText config = new SelectableText(" ").mono().small();
	private final JComboBox<String> level = new JComboBox<>(new String[] { "All", "INFO", "WARN", "ERROR" });
	private final JTextField filter = new JTextField(16);
	private final TextArea log = new TextArea(14, 60).placeholder("No log yet");
	private String tail;

	public DeveloperPanel(Host host) {
		super(null);
		this.host = host;
		build();
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) refresh();
		});
	}

	private void build() {
		group("Venue");
		addValueRow("Status", "Where the venue answers, and what it is bound to", venue);
		addValueRow("Versions", null, versions);
		addFieldTop("Adapters", adapters);
		JPanel tools = row();
		tools.add(button("Open dashboard", host::openDashboard));
		tools.add(button("Show raw context", host::showRawContext));
		addSpanLeft(tools);

		group("Files");
		addValueRow("Settings file", "Where " + APP_NAME + "'s config.json is", config);
		JPanel files = row();
		files.add(button("Open settings file", host::openConfigFile));
		files.add(button("Open logs folder", host::openLogsFolder));
		addSpanLeft(files);

		group("Log");
		JPanel filters = new JPanel(new MigLayout("insets 0", "[]8[]16[]8[grow,fill]8[]", ""));
		filters.setOpaque(false);
		filters.add(Labels.small("Level"));
		filters.add(level);
		filters.add(Labels.small("Contains"));
		filter.putClientProperty("JTextField.placeholderText", "Text to find");
		filters.add(filter);
		filters.add(button("Refresh", this::refresh));
		addSpan(filters);
		log.setEditable(false);
		Styles.classes(log, Styles.MONOSPACED);
		addSpan(new JScrollPane(log), "h 280!");
		level.addActionListener(e -> showLog());
		filter.getDocument().addDocumentListener(new DocumentListener() {
			public void insertUpdate(DocumentEvent e) {
				showLog();
			}

			public void removeUpdate(DocumentEvent e) {
				showLog();
			}

			public void changedUpdate(DocumentEvent e) {
				showLog();
			}
		});

		group("Report a problem");
		addSpan(Labels.allowHtml(Labels.small("<html>" + Labels.escape("Copies the versions, your platform, the safe "
			+ "defaults, the adapters, and the last " + Diagnostics.LOG_LINES + " log lines, with keys, tokens, logins, "
			+ "seeds, passphrases, and email addresses taken out, ready to paste into a bug report.") + "</html>")));
		JButton copy = Buttons.secondary("Copy diagnostics");
		copy.addActionListener(e -> copyDiagnostics(copy));
		addSpanLeft(copy);
		endGroup();
	}

	/** Reads everything again, off the event thread: the page calls this when it shows. */
	public void refresh() {
		new SwingWorker<Object[], Void>() {
			@Override
			protected Object[] doInBackground() {
				return new Object[] { host.adapters(), Diagnostics.tail(host.logFile(), 500) };
			}

			@Override
			@SuppressWarnings("unchecked")
			protected void done() {
				try {
					Object[] got = get();
					List<String> a = (List<String>) got[0];
					tail = (String) got[1];
					adapters.setText(a.isEmpty() ? "Not running" : String.join("\n", a));
				} catch (Exception e) {
					adapters.setText("Couldn't read the adapters.");
				}
				String url = host.venueUrl();
				venue.setText(url == null ? "Not running" : "Running at " + url + " · bound to " + host.bind());
				versions.setText(APP_NAME + " " + host.tauVersion() + " · Covia " + host.coviaVersion());
				config.setText(String.valueOf(host.configFile()));
				showLog();
			}
		}.execute();
	}

	private void showLog() {
		String shown = Diagnostics.filter(tail, (String) level.getSelectedItem(), filter.getText());
		log.setText(shown.isEmpty() ? "" : shown);
		log.setCaretPosition(log.getDocument().getLength());
	}

	/** The log as the viewer shows it now, for tests. */
	String shownLog() {
		return log.getText();
	}

	/** Filters the viewer as the controls would, for tests. */
	void filterLog(String tailText, String levelName, String text) {
		tail = tailText;
		level.setSelectedItem(levelName);
		filter.setText(text);
		showLog();
	}

	/** The redacted report Copy diagnostics copies. */
	String diagnostics() {
		String logTail = Diagnostics.tail(host.logFile(), Diagnostics.LOG_LINES);
		return Diagnostics.report(host.tauVersion(), host.coviaVersion(), host.venueUrl(), host.posture(), host.adapters(),
			logTail);
	}

	private void copyDiagnostics(JButton from) {
		from.setEnabled(false);
		new SwingWorker<String, Void>() {
			@Override
			protected String doInBackground() {
				return diagnostics();
			}

			@Override
			protected void done() {
				from.setEnabled(true);
				try {
					Clipboard.copy(get());
					Toast.show(from, "Copied, with secrets taken out", "check", Tone.SUCCESS);
				} catch (Exception e) {
					setNote("Couldn't make the report. " + tau.ui.Problems.describe(e).line(), true);
				}
			}
		}.execute();
	}

	private static JPanel row() {
		JPanel p = new JPanel(new MigLayout("insets 0", "[]8[]8[]", ""));
		p.setOpaque(false);
		return p;
	}

	private static JButton button(String label, Runnable action) {
		JButton b = Buttons.small(label);
		b.addActionListener(e -> action.run());
		return b;
	}
}
