package tau.ui.rooms;

import java.awt.Component;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import net.miginfocom.swing.MigLayout;
import tau.rooms.Rooms;
import tau.ui.components.Dialogs;
import tau.ui.components.Labels;
import tau.ui.components.Styles;

/**
 * The owner's sheets for a room: New room, Room settings, Prune, and Delete,
 * and the confirm for cancelling waits. Each has its one primary button; prune
 * and delete say what goes and that it can't be reversed, and delete needs the
 * room's name typed.
 */
final class RoomDialogs {

	/** What "in a new chat" is in the chat picker. */
	static final String NEW_CHAT = "in a new chat";
	/** What "nobody" is in the agent picker. */
	static final String NOBODY = "Nobody";

	private RoomDialogs() {
	}

	/**
	 * A room name built from a title: lower-case, letters and digits kept,
	 * anything else a single hyphen, trimmed to 64; "Release notes" becomes
	 * {@code release-notes}.
	 */
	static String nameFrom(String title) {
		String s = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
		return s.length() > 64 ? s.substring(0, 64).replaceAll("-+$", "") : s;
	}

	/** Why a name won't do, or null when it will: malformed, or taken. */
	static String nameProblem(String name, List<Rooms.Room> existing) {
		if (name.isEmpty()) return "Give the room a name";
		if (!Rooms.NAME.matcher(name).matches()) return "Lower-case letters, digits, and hyphens, up to 64";
		for (Rooms.Room r : existing) if (r.name().equals(name)) return "There is already a room named " + name;
		return null;
	}

	static void newRoom(Component parent, Rooms rooms, RoomsScreen.Host host, List<Rooms.Room> existing,
			Consumer<String> made) {
		JTextField title = new JTextField(24);
		JLabel name = Labels.small("Lower-case letters, digits, and hyphens, up to 64.");
		JTextField description = new JTextField(24);
		JCheckBox read = new JCheckBox("Anyone can read");
		JCheckBox post = new JCheckBox("Anyone can post");
		JComboBox<String> agent = agents(host);
		JComboBox<String[]> chat = chats(host, agent);
		title.getDocument().addDocumentListener(onChange(() -> {
			String n = nameFrom(title.getText());
			String problem = nameProblem(n, existing);
			name.setText(problem == null ? "Will be named " + n : problem);
			Styles.classes(name, Styles.SMALL, problem == null ? Styles.MUTED : Styles.ERROR);
		}));
		JPanel form = form();
		form.add(Labels.text("Name"));
		form.add(title, "growx");
		form.add(name, "skip, wmin 0");
		form.add(Labels.text("Description (optional)"));
		form.add(description, "growx");
		form.add(Labels.text("Who can take part"), "aligny top");
		form.add(read, "split 2");
		form.add(post);
		form.add(Labels.small("Off: only you, your agents, and access tokens with room access (the Collaborate preset). "
			+ "On: anyone who can reach your Home/venue."), "skip, wmin 0");
		form.add(Labels.text("Tell one of your agents about new posts"), "aligny top");
		form.add(agent, "split 2");
		form.add(chat);
		form.add(Labels.small("It is told of posts others make, never your own or its own."), "skip, wmin 0");
		while (Dialogs.choose(parent, "New room", form, "Create room", "Cancel")) {
			String n = nameFrom(title.getText());
			String problem = nameProblem(n, existing);
			if (problem != null) {
				Dialogs.warn(parent, "New room", problem + ".");
				continue;
			}
			try {
				String[] c = (String[]) chat.getSelectedItem();
				rooms.create(n, description.getText(), read.isSelected(), post.isSelected(), chosenAgent(agent),
					c == null ? null : c[0]);
				made.accept(n);
				return;
			} catch (Exception e) {
				Dialogs.warn(parent, "New room", words(e));
				return;
			}
		}
	}

	static void settings(Component parent, Rooms rooms, RoomsScreen.Host host, Rooms.Room room) {
		if (rooms == null) return;
		JTextField description = new JTextField(room.description() == null ? "" : room.description(), 24);
		PlainText.noHtml(description);
		JCheckBox read = new JCheckBox("Anyone can read", room.anyoneReads());
		JCheckBox post = new JCheckBox("Anyone can post", room.anyonePosts());
		JComboBox<String> agent = agents(host);
		if (room.toldAgent() != null) agent.setSelectedItem(room.toldAgent());
		JComboBox<String[]> chat = chats(host, agent);
		if (room.toldSession() != null) {
			for (int i = 0; i < chat.getItemCount(); i++) {
				String[] c = chat.getItemAt(i);
				if (c != null && room.toldSession().equals(c[0])) chat.setSelectedIndex(i);
			}
		}
		JPanel form = form();
		form.add(Labels.text("Description"));
		form.add(description, "growx");
		form.add(Labels.text("Who can take part"), "aligny top");
		form.add(read, "split 2");
		form.add(post);
		form.add(Labels.small("Access tokens reach every room, so they are changed in Vault → Access."), "skip, wmin 0");
		form.add(Labels.text("Told of new posts"), "aligny top");
		form.add(agent, "split 2");
		form.add(chat);
		if (!Dialogs.choose(parent, "Room settings · " + room.name(), form, "Save", "Cancel")) return;
		try {
			String[] c = (String[]) chat.getSelectedItem();
			rooms.update(room.name(), description.getText(), read.isSelected(), post.isSelected(), chosenAgent(agent),
				c == null ? null : c[0]);
		} catch (Exception e) {
			Dialogs.warn(parent, "Room settings", words(e));
		}
	}

	static void prune(Component parent, Rooms rooms, Rooms.Room room) {
		if (room.kept() == 0) {
			Dialogs.info(parent, "Prune " + room.name(), "There are no posts to prune.");
			return;
		}
		SpinnerNumberModel before = new SpinnerNumberModel(room.first() + 1, room.first() + 1, room.end(), 1);
		JSpinner spinner = new JSpinner(before);
		JLabel what = Labels.small(" ");
		Runnable say = () -> {
			long b = ((Number) before.getValue()).longValue();
			long n = b - room.first();
			what.setText("This removes " + n + (n == 1 ? " post" : " posts") + " (#" + room.first() + " to #" + (b - 1)
				+ ") from the room for everyone. It can't be reversed. Posts from #" + b + " stay, with their numbers.");
		};
		spinner.addChangeListener(e -> say.run());
		say.run();
		JPanel form = form();
		form.add(Labels.text("Remove posts before #"));
		form.add(spinner);
		form.add(what, "span 2, wmin 0, w 360");
		long b = -1;
		if (Dialogs.choose(parent, "Prune " + room.name() + "?", form, "Prune", "Cancel")) {
			b = ((Number) before.getValue()).longValue();
		}
		if (b < 0) return;
		try {
			rooms.prune(room.name(), b);
		} catch (Exception e) {
			Dialogs.warn(parent, "Prune", words(e));
		}
	}

	static void delete(Component parent, Rooms rooms, Rooms.Room room, Runnable after) {
		JTextField typed = new JTextField(20);
		JPanel form = form();
		form.add(Labels.small("The room and its " + room.kept() + (room.kept() == 1 ? " post" : " posts")
			+ " go for everyone. Anyone waiting on it is told it was deleted"
			+ (room.toldAgent() == null ? "" : ", and " + room.toldAgent() + " stops being told")
			+ ". It can't be reversed."), "span 2, wmin 0, w 360");
		form.add(Labels.text("Type the room's name to confirm"), "span 2");
		form.add(typed, "span 2, growx");
		while (Dialogs.confirmDanger(parent, "Delete " + room.name() + "?", form)) {
			if (!room.name().equals(typed.getText().strip())) {
				Dialogs.warn(parent, "Delete room", "Type " + room.name() + " exactly to delete it.");
				continue;
			}
			try {
				rooms.delete(room.name());
				after.run();
			} catch (Exception e) {
				Dialogs.warn(parent, "Delete room", words(e));
			}
			return;
		}
	}

	static boolean confirmCancel(Component parent, String room, int waits) {
		return Dialogs.choose(parent, "Cancel " + waits + (waits == 1 ? " wait" : " waits") + " on " + room + "?",
			"They stop waiting for new posts. What they posted stays in the room.", "Cancel waits", "Keep them");
	}

	// ------------------------------------------------------------------

	private static JPanel form() {
		return new JPanel(new MigLayout("insets 0, wrap 2, gapy 8", "[]14[grow,fill]", ""));
	}

	private static JComboBox<String> agents(RoomsScreen.Host host) {
		JComboBox<String> box = new JComboBox<>();
		PlainText.noHtml(box);
		box.addItem(NOBODY);
		for (String a : host.agents()) box.addItem(a);
		return box;
	}

	private static String chosenAgent(JComboBox<String> agent) {
		Object a = agent.getSelectedItem();
		return (a == null || NOBODY.equals(a)) ? null : a.toString();
	}

	private static JComboBox<String[]> chats(RoomsScreen.Host host, JComboBox<String> agent) {
		JComboBox<String[]> box = new JComboBox<>();
		PlainText.noHtml(box);
		box.setRenderer((list, value, index, selected, focused) -> {
			JLabel l = PlainText.label(value == null ? NEW_CHAT : "chat “" + value[1] + "”");
			l.setOpaque(selected);
			return l;
		});
		Runnable fill = () -> {
			box.removeAllItems();
			box.addItem(null);
			String a = chosenAgent(agent);
			box.setEnabled(a != null);
			if (a != null) for (String[] c : host.chats(a)) box.addItem(c);
		};
		agent.addActionListener(e -> fill.run());
		fill.run();
		return box;
	}

	private static String words(Exception e) {
		Rooms.Failure f = Rooms.failure(e);
		return switch (f.kind()) {
			case REFUSED -> "Refused" + (f.ability() == null ? "." : ": " + f.ability() + ".");
			case GONE -> "That room is no longer there.";
			case INVALID -> f.message() == null ? "That wasn't accepted." : f.message() + ".";
			default -> "The room didn't answer: your Home/venue isn't ready. Nothing was changed.";
		};
	}

	private static DocumentListener onChange(Runnable r) {
		return new DocumentListener() {
			public void insertUpdate(DocumentEvent e) {
				r.run();
			}

			public void removeUpdate(DocumentEvent e) {
				r.run();
			}

			public void changedUpdate(DocumentEvent e) {
				r.run();
			}
		};
	}
}
