package tau.ui.rooms;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import convex.core.data.ACell;
import net.miginfocom.swing.MigLayout;
import tau.rooms.LoopWatch;
import tau.rooms.Notices;
import tau.rooms.Rooms;
import tau.rooms.Who;
import tau.ui.binding.Watcher;
import tau.ui.components.Borders;
import tau.ui.components.Buttons;
import tau.ui.components.EmptyState;
import tau.ui.components.IdentityMark;
import tau.ui.components.Labels;
import tau.ui.components.Lucide;
import tau.ui.components.ScreenHeader;
import tau.ui.components.Scrolls;
import tau.ui.components.StatusBadge;
import tau.ui.components.Styles;
import tau.ui.components.Theme;
import tau.ui.components.Tone;
import tau.ui.shell.ShellLayout;

/**
 * The Rooms screen: collaboration rooms (docs/ROOMS.md) where you, your agents,
 * and outside agents with an access token post and read. A list of rooms beside
 * the open room, and a details panel that docks on a wide window and opens as a
 * sheet below that; on a narrow one the list and the room are separate steps.
 *
 * <p>Everything others wrote is plain text ({@link PlainText}): posts,
 * descriptions, names, and token names are never rendered as HTML, never carry
 * a live link, and show hidden characters as markers. A post's author line
 * comes from its record, never its text. Long posts fold; new posts never move
 * a reader who has scrolled up; two agents answering each other raise a banner
 * the owner can act on. No character appears anywhere on this screen.</p>
 */
@SuppressWarnings("serial")
public final class RoomsScreen extends JPanel {

	/** What the screen needs from the rest of the app. */
	public interface Host {
		/** The rooms of the acting principal, or null while there is none (locked, starting). */
		Rooms rooms();

		/** The venue's DID, whose users are in your Home. */
		String venueDid();

		/** The acting principal's agents, by id, that a room can tell of new posts. */
		List<String> agents();

		/** An agent's chats, as {id, title}, newest first. */
		List<String[]> chats(String agentId);

		/** The access tokens that reach rooms, as "name (preset)". */
		List<String> roomTokens();

		/** Opens Jobs, where waits on a room can be cancelled. */
		void openJobs();

		/** Opens Vault → Access, where access tokens are changed. */
		void openAccess();

		/** Shows a notification, as plain text. */
		void notify(String text);
	}

	/** On a window at least this wide the details panel docks beside the room. */
	static final int DOCK_DETAILS_FROM = ShellLayout.DOCK_FROM;
	/** Below this width of the screen, the list and the room are one pane at a time. */
	static final int ONE_PANE_BELOW = 640;
	/** The list's width. */
	static final int LIST_WIDTH = 300;
	/** Posts a minute above which the room offers to pause live updates. */
	static final int FLOOD_PER_MINUTE = 30;
	/** The loop banner's icon. */
	static final String LOOP_ICON = "repeat";

	enum Filter {
		ALL("All"), YOURS("Yours"), PUBLIC("Public");

		final String label;

		Filter(String label) {
			this.label = label;
		}
	}

	private final Host host;
	private final DefaultListModel<Rooms.Room> model = new DefaultListModel<>();
	private final JList<Rooms.Room> list = new JList<>(model);
	private final JTextField search = new JTextField();
	private final Map<String, Long> seenEnd = new HashMap<>();
	private Filter filter = Filter.ALL;
	private List<Rooms.Room> all = List.of();
	private Notices notices;
	private Watcher.Watch<ACell> roomsWatch;

	private final JPanel listPane = new JPanel(new BorderLayout());
	private final JPanel roomPane = new JPanel(new CardLayout());
	private final JPanel detailsPane = new JPanel(new BorderLayout());
	private final JPanel body = new JPanel();
	private final ScreenHeader header = new ScreenHeader("Rooms");
	private final JButton back = Buttons.plain("‹ Rooms");
	private RoomView open;
	private JDialog sheet;
	private boolean onePane;
	private boolean showingRoom;
	/** Asks the owner before waits are cancelled: the sheet, or an answer a test gives. */
	java.util.function.BiPredicate<String, Integer> askCancel = (room, waits) -> RoomDialogs.confirmCancel(this, room, waits);

	public RoomsScreen(Host host) {
		super(new BorderLayout());
		this.host = host;
		setOpaque(false);

		JButton newRoom = Buttons.primary("New room");
		newRoom.addActionListener(e -> newRoom());
		back.addActionListener(e -> showList());
		back.setVisible(false);
		header.detail(back);
		header.action(newRoom);
		add(header, BorderLayout.NORTH);

		buildList();
		roomPane.setOpaque(false);
		detailsPane.setOpaque(false);
		detailsPane.setBorder(Borders.hairline(false, true, false, false));
		body.setOpaque(false);
		add(body, BorderLayout.CENTER);
		showNothingOpen();
		addComponentListener(new ComponentAdapter() {
			@Override
			public void componentResized(ComponentEvent e) {
				relayout();
			}
		});
		relayout();
	}

	// ------------------------------------------------------------------
	// Following the rooms
	// ------------------------------------------------------------------

	/**
	 * Follows the acting principal's rooms: the list, unread counts, and
	 * notifications, which keep coming while the window is hidden. Call again
	 * when the acting principal changes; null stops.
	 */
	public void follow(Rooms rooms) {
		if (roomsWatch != null) roomsWatch.close();
		roomsWatch = null;
		close();
		seenEnd.clear();
		all = List.of();
		refreshList();
		if (rooms == null) {
			notices = null;
			return;
		}
		notices = new Notices(rooms.principal(), host.venueDid());
		roomsWatch = Watcher.observe(rooms::view, this::viewChanged);
	}

	/** The rooms view changed (or first arrived): the list, the open room's facts, and notifications. */
	void viewChanged(ACell view) {
		all = Rooms.rooms(view);
		for (Rooms.Room r : all) seenEnd.putIfAbsent(r.name(), r.end());
		if (open != null) {
			Rooms.Room now = find(open.room.name());
			if (now != null) open.roomChanged(now);
		}
		refreshList();
		if (notices != null) {
			for (Notices.Notice n : notices.offer(view, System.currentTimeMillis())) {
				if (open != null && open.room.name().equals(n.room()) && isShowing()) continue;
				host.notify(n.text());
			}
		}
	}

	private Rooms.Room find(String name) {
		for (Rooms.Room r : all) if (r.name().equals(name)) return r;
		return null;
	}

	// ------------------------------------------------------------------
	// The list
	// ------------------------------------------------------------------

	private void buildList() {
		listPane.setOpaque(false);
		listPane.setPreferredSize(new Dimension(LIST_WIDTH, 0));
		listPane.setBorder(Borders.hairline(false, false, false, true));
		JPanel top = new JPanel(new MigLayout("insets 12 12 6 12, fillx, wrap 1, gapy 6", "[grow,fill]", ""));
		top.setOpaque(false);
		search.putClientProperty("JTextField.placeholderText", "Find a room");
		search.putClientProperty("JTextField.leadingIcon", Lucide.icon("search", 14, Theme::muted));
		search.getDocument().addDocumentListener(new DocumentListener() {
			public void insertUpdate(DocumentEvent e) {
				refreshList();
			}

			public void removeUpdate(DocumentEvent e) {
				refreshList();
			}

			public void changedUpdate(DocumentEvent e) {
				refreshList();
			}
		});
		top.add(search);
		JPanel filters = new JPanel(new MigLayout("insets 0, gap 4", "", ""));
		filters.setOpaque(false);
		ButtonGroup g = new ButtonGroup();
		for (Filter f : Filter.values()) {
			JToggleButton b = new JToggleButton(f.label, f == Filter.ALL);
			b.putClientProperty("JButton.buttonType", "toolBarButton");
			b.addActionListener(e -> {
				filter = f;
				refreshList();
			});
			g.add(b);
			filters.add(b, "wmin pref");
		}
		top.add(filters, "growx 0");
		listPane.add(top, BorderLayout.NORTH);

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setCellRenderer(new RoomRow());
		list.setOpaque(false);
		list.addListSelectionListener(e -> {
			if (e.getValueIsAdjusting()) return;
			Rooms.Room r = list.getSelectedValue();
			if (r != null && (open == null || !open.room.name().equals(r.name()))) openRoom(r);
		});
		JScrollPane sp = Scrolls.vertical(list);
		listPane.add(sp, BorderLayout.CENTER);
	}

	private void refreshList() {
		String q = search.getText().strip().toLowerCase();
		String me = principal();
		Rooms.Room selected = list.getSelectedValue();
		model.clear();
		for (Rooms.Room r : all) {
			if (filter == Filter.YOURS && (me == null || !r.ownedBy(me))) continue;
			if (filter == Filter.PUBLIC && !r.isPublic()) continue;
			if (!q.isEmpty() && !r.name().contains(q)
				&& (r.description() == null || !r.description().toLowerCase().contains(q))) continue;
			model.addElement(r);
		}
		if (selected != null) {
			for (int i = 0; i < model.size(); i++) {
				if (model.get(i).name().equals(selected.name())) {
					list.setSelectedIndex(i);
					break;
				}
			}
		}
		if (open == null && !showingRoom) showNothingOpen();
		list.repaint();
	}

	/** Unread posts in a room since it was last open, "99+" past 99; empty for none. */
	String unread(Rooms.Room r) {
		long seen = seenEnd.getOrDefault(r.name(), r.end());
		long n = Math.max(0, r.end() - seen);
		return n == 0 ? "" : (n > 99 ? "99+" : Long.toString(n));
	}

	private String principal() {
		Rooms rooms = host.rooms();
		return rooms == null ? null : rooms.principal();
	}

	/** One room in the list: name, unread count, description, and whether it is private or public. */
	private final class RoomRow implements ListCellRenderer<Rooms.Room> {
		private final JPanel row = new JPanel(new MigLayout("insets 8 14 8 12, fillx, wrap 2, gapy 2", "[grow,fill][]", ""));
		private final JLabel name = PlainText.label("");
		private final JLabel count = PlainText.label("");
		private final JLabel description = PlainText.small("");
		private final JLabel register = PlainText.small("");

		RoomRow() {
			Styles.style(name, "font: bold");
			Styles.classes(count, Styles.SMALL);
			row.add(name, "wmin 0");
			row.add(count);
			row.add(description, "span 2, wmin 0");
			row.add(register, "span 2");
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends Rooms.Room> l, Rooms.Room r, int index,
				boolean selected, boolean focused) {
			name.setText(PlainText.visible(r.name()));
			count.setText(unread(r));
			description.setText(PlainText.visible(r.description() == null ? "No description" : firstLine(r.description())));
			register.setIcon(Lucide.icon(r.isPublic() ? "globe" : "lock", 12, Theme::muted));
			register.setText(r.anyonePosts() ? "public read and post" : r.anyoneReads() ? "public read" : "private");
			row.setOpaque(selected);
			row.setBackground(selected ? Theme.warmSurface() : l.getBackground());
			return row;
		}
	}

	private static String firstLine(String s) {
		int nl = s.indexOf('\n');
		return nl < 0 ? s : s.substring(0, nl);
	}

	// ------------------------------------------------------------------
	// Layout at each width
	// ------------------------------------------------------------------

	/** Lays the panes out for the screen's width and the window's: one, two, or three. */
	void relayout() {
		Window w = SwingUtilities.getWindowAncestor(this);
		int windowWidth = (w != null) ? w.getWidth() : getWidth();
		onePane = getWidth() > 0 && getWidth() < ONE_PANE_BELOW;
		boolean dock = windowWidth >= DOCK_DETAILS_FROM && open != null && !onePane;
		body.removeAll();
		body.setLayout(new BorderLayout());
		if (onePane) {
			body.add(showingRoom && open != null ? roomPane : listPane, BorderLayout.CENTER);
		} else {
			body.add(listPane, BorderLayout.WEST);
			body.add(roomPane, BorderLayout.CENTER);
			if (dock) {
				detailsPane.setPreferredSize(new Dimension(ShellLayout.SIDE, 0));
				body.add(detailsPane, BorderLayout.EAST);
			}
		}
		back.setVisible(onePane && showingRoom);
		if (dock && sheet != null) closeSheet();
		if (open != null) open.setDetailsDocked(dock);
		body.revalidate();
		body.repaint();
	}

	/** Whether the details panel is docked beside the room, for tests. */
	boolean detailsDocked() {
		return detailsPane.getParent() == body;
	}

	/** Whether only one pane shows, for tests. */
	boolean onePane() {
		return onePane;
	}

	private void showList() {
		showingRoom = false;
		relayout();
	}

	// ------------------------------------------------------------------
	// States with no room open
	// ------------------------------------------------------------------

	private void showNothingOpen() {
		roomPane.removeAll();
		JComponent state;
		if (all.isEmpty()) {
			state = new EmptyState("messages-square", Tone.MUTED, "No rooms yet",
				"A room is a conversation your agents, outside agents with an access token, and you share.")
				.action("New room", this::newRoom).action("How outside agents join", host::openAccess).bare();
		} else {
			state = new EmptyState("messages-square", Tone.MUTED, "Choose a room",
				"Its posts appear here, and new ones arrive as they are made.").bare();
		}
		roomPane.add(state, "none");
		((CardLayout) roomPane.getLayout()).show(roomPane, "none");
		roomPane.revalidate();
		roomPane.repaint();
	}

	// ------------------------------------------------------------------
	// Opening a room
	// ------------------------------------------------------------------

	/** Opens room {@code name}, as from the palette, a notification, or a link from Jobs. */
	public void open(String name) {
		Rooms.Room r = find(name);
		if (r != null) {
			list.setSelectedValue(r, true);
			openRoom(r);
		}
	}

	private void openRoom(Rooms.Room r) {
		Rooms rooms = host.rooms();
		if (rooms == null) return;
		close();
		seenEnd.put(r.name(), r.end());
		open = new RoomView(rooms, r);
		roomPane.removeAll();
		roomPane.add(open, "room");
		((CardLayout) roomPane.getLayout()).show(roomPane, "room");
		detailsPane.removeAll();
		detailsPane.add(Scrolls.vertical(open.details), BorderLayout.CENTER);
		showingRoom = true;
		relayout();
		open.start();
		list.repaint();
	}

	/** Stops following the open room, as when leaving it or the screen. */
	public void close() {
		if (open != null) open.stop();
		open = null;
		showingRoom = false;
		closeSheet();
	}

	/** The open room's view, for tests. */
	RoomView openRoom() {
		return open;
	}

	private void openDetailsSheet() {
		if (open == null) return;
		if (detailsDocked()) return;
		closeSheet();
		Window w = SwingUtilities.getWindowAncestor(this);
		sheet = new JDialog(w, "About this room", java.awt.Dialog.ModalityType.MODELESS);
		JScrollPane sp = Scrolls.vertical(open.details);
		sheet.getContentPane().add(sp);
		sheet.setSize(ShellLayout.SIDE + 40, Math.max(400, getHeight()));
		if (w != null) sheet.setLocation(w.getX() + w.getWidth() - sheet.getWidth() - 16, w.getY() + 60);
		sheet.setVisible(true);
	}

	private void closeSheet() {
		if (sheet != null) {
			sheet.dispose();
			sheet = null;
		}
		if (open != null && detailsPane.getComponentCount() == 0) {
			detailsPane.add(Scrolls.vertical(open.details), BorderLayout.CENTER);
		}
	}

	// ------------------------------------------------------------------
	// The owner's sheets
	// ------------------------------------------------------------------

	/** The New room sheet, as from the list, File → New room, and the palette. */
	public void newRoom() {
		Rooms rooms = host.rooms();
		if (rooms == null) return;
		RoomDialogs.newRoom(this, rooms, host, all, made -> {
			seenEnd.put(made, 0L);
			SwingUtilities.invokeLater(() -> open(made));
		});
	}

	/** Room settings for the open room, when you own it; else its details. */
	public void openSettings() {
		if (open == null) return;
		if (open.owned()) RoomDialogs.settings(this, host.rooms(), host, open.room);
		else openDetailsSheet();
	}

	/** The room open now, or null. */
	public String openRoomName() {
		return open == null ? null : open.room.name();
	}

	/** Every room's name, for the palette. */
	public List<String> roomNames() {
		List<String> names = new ArrayList<>();
		for (Rooms.Room r : all) names.add(r.name());
		return names;
	}

	// ------------------------------------------------------------------
	// One room
	// ------------------------------------------------------------------

	/** The open room: its header, any banner, its posts, the new-posts pill, and the composer. */
	final class RoomView extends JPanel {
		final Rooms rooms;
		Rooms.Room room;
		final JPanel details = new JPanel(new MigLayout("insets 18 18 18 18, fillx, wrap 1, gapy 6", "[grow,fill]", ""));
		private final JPanel posts = new JPanel();
		private final JScrollPane scroll;
		private final JLabel headerName = PlainText.label("");
		private final JLabel headerFacts = PlainText.small("");
		private final JPanel banner = new JPanel(new MigLayout("insets 10 14 10 14, fillx, wrap 1, gapy 4", "[grow,fill]", ""));
		private final JLabel rate = Labels.small(" ");
		private final JCheckBox pause = new JCheckBox("Pause live updates");
		private final JButton pill = Buttons.small(" ");
		private final tau.ui.components.PressButton earlier = Buttons.link("Show earlier posts", this::earlier);
		private final JLabel prunedNote = Labels.small(" ");
		private final JTextArea draft = new JTextArea(3, 20);
		private final JLabel composerHint = Labels.small(" ");
		private final JLabel counter = Labels.small(" ");
		private final JButton postButton = Buttons.primary("Post");
		/** Room settings for the owner; the room's details for anyone else. */
		private final JButton settingsButton = Buttons.plain("Room settings");
		private final JPanel status = new JPanel(new MigLayout("insets 6 14 6 14, fillx, wrap 1, gapy 2", "[grow,fill]", ""));
		private final ArrayDeque<Rooms.Post> shown = new ArrayDeque<>();
		private long firstShown = -1;
		private long lastN = -1;
		private int missed;
		private boolean gone;
		private boolean detailsDocked;
		private CompletableFuture<Rooms.Page> waiting;
		private volatile boolean running;
		/** Told on the event thread whenever posts are shown: for tests to wait on, never a pause. */
		Consumer<List<Rooms.Post>> onShown = posts -> {
		};
		/** Told on the event thread when a post is made or fails (null when made): for tests. */
		Consumer<Throwable> onPosted = e -> {
		};

		RoomView(Rooms rooms, Rooms.Room room) {
			super(new BorderLayout());
			this.rooms = rooms;
			this.room = room;
			setOpaque(false);

			JPanel top = new JPanel(new MigLayout("insets 12 18 8 18, fillx, wrap 2, gapy 2", "[grow,fill][]", ""));
			top.setOpaque(false);
			Styles.classes(headerName, Styles.LEAD);
			top.add(headerName, "wmin 0");
			settingsButton.setIcon(Lucide.icon("settings", 14, Theme::muted));
			settingsButton.addActionListener(e -> openSettings());
			top.add(settingsButton, "spany 2, aligny top");
			top.add(headerFacts, "wmin 0");
			banner.setVisible(false);
			banner.setOpaque(true);
			banner.setBackground(Theme.warmSurface());
			JPanel north = new JPanel();
			north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
			north.setOpaque(false);
			north.add(top);
			north.add(banner);
			JPanel rateRow = new JPanel(new MigLayout("insets 0 18 4 18, fillx", "[grow,fill][]", ""));
			rateRow.setOpaque(false);
			rateRow.add(rate, "wmin 0");
			pause.setOpaque(false);
			pause.addActionListener(e -> {
				if (pause.isSelected()) stopWaiting();
				else follow();
			});
			rateRow.add(pause);
			rateRow.setVisible(false);
			rateRow.setName("rate");
			north.add(rateRow);
			add(north, BorderLayout.NORTH);

			posts.setLayout(new BoxLayout(posts, BoxLayout.Y_AXIS));
			posts.setOpaque(false);
			JPanel column = new JPanel(new BorderLayout());
			column.setOpaque(false);
			JPanel head = new JPanel(new MigLayout("insets 6 18 2 18, wrap 1, gapy 2", "[]", ""));
			head.setOpaque(false);
			head.add(earlier);
			head.add(prunedNote);
			column.add(head, BorderLayout.NORTH);
			column.add(posts, BorderLayout.CENTER);
			scroll = Scrolls.vertical(Scrolls.hugTop(column));
			scroll.getVerticalScrollBar().addAdjustmentListener(e -> {
				if (atBottom()) clearPill();
			});
			add(scroll, BorderLayout.CENTER);

			JPanel south = new JPanel(new MigLayout("insets 6 18 12 18, fillx, wrap 2, gapy 4", "[grow,fill][]", ""));
			south.setOpaque(false);
			pill.setVisible(false);
			pill.addActionListener(e -> {
				scrollToEnd();
				clearPill();
			});
			south.add(pill, "span 2, align center, growx 0");
			status.setOpaque(false);
			status.setVisible(false);
			south.add(status, "span 2");
			PlainText.noHtml(draft);
			draft.setLineWrap(true);
			draft.setWrapStyleWord(true);
			draft.getDocument().addDocumentListener(new DocumentListener() {
				public void insertUpdate(DocumentEvent e) {
					count();
				}

				public void removeUpdate(DocumentEvent e) {
					count();
				}

				public void changedUpdate(DocumentEvent e) {
					count();
				}
			});
			JScrollPane draftScroll = new JScrollPane(draft);
			south.add(draftScroll, "span 2, h 64::120");
			south.add(composerHint, "wmin 0");
			JPanel right = new JPanel(new MigLayout("insets 0, gap 10", "", ""));
			right.setOpaque(false);
			right.add(counter);
			postButton.addActionListener(e -> post());
			right.add(postButton);
			south.add(right);
			add(south, BorderLayout.SOUTH);
			roomChanged(room);
			count();
		}

		boolean owned() {
			return room.ownedBy(rooms.principal());
		}

		void setDetailsDocked(boolean docked) {
			detailsDocked = docked;
		}

		/** The room's settings changed, or it gained posts: the header, the details, the composer's hint. */
		void roomChanged(Rooms.Room now) {
			room = now;
			headerName.setText(PlainText.visible(now.name()));
			settingsButton.setText(owned() ? "Room settings" : "About this room");
			headerName.setIcon(Lucide.icon(now.isPublic() ? "globe" : "lock", 14, Theme::muted));
			String register = now.anyonePosts() ? "Public read and post" : now.anyoneReads() ? "Public read" : "Private";
			String owner = owned() ? "owner: you" : "owner: " + ownerName(now.owner());
			String told = now.toldAgent() == null ? "" : " · " + now.toldAgent() + " is told of new posts";
			headerFacts.setText(PlainText.visible(register + " · " + owner + told));
			composerHint.setText(PlainText.visible("Posting as you" + (now.toldAgent() == null ? ""
				: " · " + now.toldAgent() + " is not told of your own posts")));
			draft.putClientProperty("JTextField.placeholderText", "Post in " + now.name() + " as you…");
			buildDetails();
			boolean atFirstKept = firstShown >= 0 && firstShown <= now.first();
			prunedNote.setText(atFirstKept && now.first() > 0 ? "Posts before #" + now.first() + " were pruned by the owner"
				: " ");
			earlier.setVisible(firstShown > now.first());
		}

		private String ownerName(String did) {
			if (did == null) return "unknown";
			Who w = Who.of(new Rooms.Post(0, 0, did, null, ""), rooms.principal(), host.venueDid());
			return w.kind() == Who.Kind.YOU ? "you" : w.name();
		}

		private void buildDetails() {
			details.removeAll();
			details.add(Labels.section("About this room"));
			JLabel name = PlainText.label(room.name());
			Styles.style(name, "font: bold");
			details.add(name);
			if (room.description() != null) details.add(PlainText.body(room.description()), "wmin 0");
			details.add(PlainText.small("Owner: " + (owned() ? "you" : ownerName(room.owner()))
				+ (room.created() > 0 ? " · created " + new SimpleDateFormat("d MMM").format(new Date(room.created())) : "")));
			details.add(Labels.section("Who can take part"), "gaptop 10");
			details.add(Labels.small("Anyone can read: " + (room.anyoneReads() ? "on" : "off") + " · Anyone can post: "
				+ (room.anyonePosts() ? "on" : "off")));
			List<String> tokens = host.roomTokens();
			details.add(PlainText.small(tokens.isEmpty() ? "No access token reaches rooms"
				: "Access tokens that reach every room: " + String.join(", ", tokens)), "wmin 0");
			details.add(Buttons.link("Manage in Vault → Access", host::openAccess), "growx 0");
			details.add(Labels.section("Told of new posts"), "gaptop 10");
			details.add(PlainText.small(room.toldAgent() == null ? "Nobody" : room.toldAgent()
				+ (room.toldSession() == null ? "" : ", in its chat")));
			details.add(Labels.section("Waiting on this room"), "gaptop 10");
			List<Rooms.Wait> waits = rooms.waits(room.name());
			details.add(Labels.small(waits.isEmpty() ? "No waits recorded as jobs"
				: waits.size() + (waits.size() == 1 ? " job" : " jobs") + " waiting"));
			if (!waits.isEmpty()) details.add(Buttons.link("Show in Jobs", host::openJobs), "growx 0");
			details.add(Labels.section("Posts kept"), "gaptop 10");
			details.add(Labels.small(room.kept() == 0 ? "None" : "#" + room.first() + " to #" + (room.end() - 1)));
			if (owned()) {
				JPanel actions = new JPanel(new MigLayout("insets 0, gap 8, wrap 2", "", ""));
				actions.setOpaque(false);
				JButton settings = Buttons.secondary("Room settings");
				settings.addActionListener(e -> RoomDialogs.settings(RoomsScreen.this, rooms, host, room));
				JButton prune = Buttons.plain("Prune…");
				prune.addActionListener(e -> RoomDialogs.prune(RoomsScreen.this, rooms, room));
				JButton delete = Buttons.plain("Delete room…");
				delete.addActionListener(e -> RoomDialogs.delete(RoomsScreen.this, rooms, room, () -> {
					close();
					showNothingOpen();
				}));
				actions.add(settings);
				actions.add(prune);
				actions.add(delete);
				actions.setName("owner-actions");
				details.add(actions, "gaptop 12");
			}
			details.revalidate();
			details.repaint();
		}

		/** The owner's actions shown in the details: for tests. */
		boolean showsOwnerActions() {
			return java.util.Arrays.stream(details.getComponents()).anyMatch(c -> "owner-actions".equals(c.getName()));
		}

		// -------- reading --------

		void start() {
			running = true;
			status("Reading " + room.name() + "…", false);
			Thread t = new Thread(() -> {
				try {
					Rooms.Page p = rooms.read(room.name(), null, Rooms.PAGE);
					SwingUtilities.invokeLater(() -> {
						clearStatus();
						showFirst(p);
						follow();
					});
				} catch (Exception e) {
					SwingUtilities.invokeLater(() -> failed(e, false));
				}
			}, "tau-room-read");
			t.setDaemon(true);
			t.start();
		}

		void stop() {
			running = false;
			stopWaiting();
		}

		private void stopWaiting() {
			CompletableFuture<Rooms.Page> w = waiting;
			waiting = null;
			if (w != null) w.cancel(true);
		}

		/** Waits on the room that failed and were retried; for the following state a test reports. */
		private int waitFailures;

		/** How the room is being followed, in a line: for a test that waits on it to say why it stopped. */
		String followState() {
			return "lastN=" + lastN + ", waiting=" + (waiting != null) + ", running=" + running + ", gone=" + gone
				+ ", paused=" + pause.isSelected() + ", failed waits=" + waitFailures + ", status='" + statusText() + "'";
		}

		/** One wait at a time on the room from the last post shown, issued again on each answer. */
		void follow() {
			if (!running || gone || pause.isSelected() || waiting != null) return;
			// Always from the last post shown (-1 for a room read empty): a wait given no number would start
			// from the room's next post as of the wait, skipping any made since the room was read.
			CompletableFuture<Rooms.Page> w = rooms.await(room.name(), lastN, 60_000);
			waiting = w;
			w.whenComplete((page, error) -> SwingUtilities.invokeLater(() -> {
				// A wait this view cancelled itself is no longer the one waiting: nothing to do.
				if (waiting != w) return;
				waiting = null;
				if (!running) return;
				if (error != null) {
					waitFailures++;
					// Any other failure is retried: following stops only when the room is gone, which the rooms
					// list must confirm, never on a "not found" from one wait alone.
					Rooms.Failure f = Rooms.failure(error);
					if (f.kind() == Rooms.Kind.GONE && find(room.name()) == null) {
						deleted();
						return;
					}
					status("New posts will appear when your Home/venue answers again.", false);
					javax.swing.Timer again = new javax.swing.Timer(5000, e -> follow());
					again.setRepeats(false);
					again.start();
					return;
				}
				clearStatus();
				append(page.posts());
				follow();
			}));
		}

		private void showFirst(Rooms.Page p) {
			posts.removeAll();
			shown.clear();
			if (p.posts().isEmpty()) {
				posts.add(emptyRoom());
				firstShown = p.end();
				lastN = p.end() - 1;
			} else {
				for (Rooms.Post post : p.posts()) addPost(post, false);
				firstShown = p.posts().get(0).n();
				lastN = p.posts().get(p.posts().size() - 1).n();
			}
			roomChanged(room);
			posts.revalidate();
			SwingUtilities.invokeLater(this::scrollToEnd);
			checkLoop();
			onShown.accept(p.posts());
		}

		private JComponent emptyRoom() {
			JPanel p = new JPanel(new MigLayout("insets 24 18 24 18, wrap 1", "[grow,fill]", ""));
			p.setOpaque(false);
			p.setName("empty");
			p.add(Labels.lead("Room is empty"));
			p.add(PlainText.small("No posts in " + room.name() + " yet. Post the first one, or give an outside agent a "
				+ "Collaborate token."));
			return p;
		}

		/** Posts arrived: added at the end; the page scrolls only if you were already at the newest post. */
		void append(List<Rooms.Post> fresh) {
			if (fresh.isEmpty()) return;
			boolean follow = atBottom();
			if (posts.getComponentCount() == 1 && "empty".equals(posts.getComponent(0).getName())) posts.removeAll();
			int added = 0;
			for (Rooms.Post p : fresh) {
				if (p.n() <= lastN) continue;
				addPost(p, false);
				lastN = p.n();
				added++;
			}
			seenEnd.put(room.name(), lastN + 1);
			posts.revalidate();
			posts.repaint();
			if (follow) SwingUtilities.invokeLater(this::scrollToEnd);
			else if (added > 0) {
				missed += added;
				pill.setText("↓ " + (missed == 1 ? "1 new post" : missed + " new posts"));
				pill.setVisible(true);
			}
			checkLoop();
			checkRate();
			onShown.accept(fresh);
		}

		private void addPost(Rooms.Post p, boolean atTop) {
			PostView v = new PostView(p, rooms.principal(), host.venueDid());
			if (atTop) {
				posts.add(v, 0);
				shown.addFirst(p);
			} else {
				posts.add(v);
				shown.addLast(p);
			}
		}

		private void earlier() {
			long before = firstShown;
			long start = Math.max(room.first(), before - Rooms.PAGE);
			int limit = (int) Math.max(1, before - start);
			Thread t = new Thread(() -> {
				try {
					Rooms.Page p = rooms.read(room.name(), start, limit);
					SwingUtilities.invokeLater(() -> {
						List<Rooms.Post> older = p.posts();
						for (int i = older.size() - 1; i >= 0; i--) {
							if (older.get(i).n() < before) addPost(older.get(i), true);
						}
						if (!older.isEmpty()) firstShown = Math.min(firstShown, older.get(0).n());
						roomChanged(room);
						posts.revalidate();
						posts.repaint();
						onShown.accept(older);
					});
				} catch (Exception e) {
					SwingUtilities.invokeLater(() -> failed(e, false));
				}
			}, "tau-room-earlier");
			t.setDaemon(true);
			t.start();
		}

		// -------- posting --------

		private void count() {
			int n = draft.getText().strip().length();
			counter.setText(n > Rooms.MAX_TEXT - 500 ? String.format("%,d / %,d", n, Rooms.MAX_TEXT) : " ");
			postButton.setEnabled(n > 0 && !gone);
		}

		void post() {
			String text = draft.getText().strip();
			if (text.isEmpty() || gone) return;
			if (text.length() > Rooms.MAX_TEXT) {
				status(String.format("Posts are up to %,d characters; this one has %,d. Nothing was posted.",
					Rooms.MAX_TEXT, text.length()), true);
				return;
			}
			postButton.setEnabled(false);
			Thread t = new Thread(() -> {
				try {
					rooms.post(room.name(), text);
					SwingUtilities.invokeLater(() -> {
						draft.setText("");
						clearStatus();
						count();
						onPosted.accept(null);
					});
				} catch (Exception e) {
					SwingUtilities.invokeLater(() -> {
						failed(e, true);
						count();
						onPosted.accept(e);
					});
				}
			}, "tau-room-post");
			t.setDaemon(true);
			t.start();
		}

		// -------- states --------

		/** A read or a post failed: refusals name only the ability; the draft always stays in the box. */
		void failed(Throwable e, boolean posting) {
			Rooms.Failure f = Rooms.failure(e);
			switch (f.kind()) {
				case REFUSED -> {
					status.removeAll();
					status.add(new StatusBadge("Refused" + (f.ability() == null ? "" : ": " + f.ability()), "ban",
						Tone.REFUSED), "growx 0");
					String doing = "room/post".equals(f.ability()) ? "post in" : "admin/room".equals(f.ability())
						? "change" : "read";
					status.add(PlainText.small("You can't " + doing + " " + room.name() + "."
						+ (f.ability() == null ? "" : " That needs " + f.ability() + " on this room.")
						+ (posting ? " Your text is still in the box." : "")));
					status.setVisible(true);
					status.revalidate();
				}
				case GONE -> deleted();
				case INVALID -> status(f.message() == null ? "That wasn't accepted. Nothing was posted."
					: f.message() + ". Nothing was posted.", true);
				default -> status((posting ? "Couldn't post. " : "Couldn't read the room. ")
					+ "The room didn't answer: your Home/venue isn't ready. Nothing was posted, and your draft is still "
					+ "in the box.", true);
			}
		}

		private void deleted() {
			gone = true;
			stopWaiting();
			draft.setEnabled(false);
			postButton.setEnabled(false);
			status.removeAll();
			status.add(PlainText.small(room.name() + " was deleted by its owner. Posts already shown stay on screen until you "
				+ "leave."));
			status.add(Buttons.link("Back to Rooms", () -> {
				close();
				showNothingOpen();
				showList();
			}), "growx 0");
			status.setVisible(true);
			status.revalidate();
		}

		/** The composer's text, for tests. */
		String draft() {
			return draft.getText();
		}

		void draft(String text) {
			draft.setText(text);
		}

		/** The pruned note's words, blank when none shows; the flood line's; for tests. */
		String prunedText() {
			return prunedNote.getText().strip();
		}

		String rateText() {
			return rate.getText().strip();
		}

		void showEarlier() {
			earlier();
		}

		boolean isGone() {
			return gone;
		}

		private void status(String text, boolean error) {
			status.removeAll();
			JLabel l = PlainText.small(text);
			if (error) Styles.classes(l, Styles.SMALL, Styles.ERROR);
			status.add(l, "wmin 0");
			status.setVisible(true);
			status.revalidate();
			status.repaint();
		}

		private void clearStatus() {
			if (gone) return;
			status.removeAll();
			status.setVisible(false);
		}

		/** The status line's words, for tests. */
		String statusText() {
			StringBuilder sb = new StringBuilder();
			for (Component c : status.getComponents()) {
				if (c instanceof JLabel l) sb.append(l.getText()).append(' ');
				if (c instanceof StatusBadge b) sb.append(b.getText()).append(' ');
			}
			return sb.toString().strip();
		}

		// -------- scrolling and the pill --------

		boolean atBottom() {
			javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
			return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 24;
		}

		private void scrollToEnd() {
			javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
			bar.setValue(bar.getMaximum());
			clearPill();
		}

		private void clearPill() {
			missed = 0;
			pill.setVisible(false);
		}

		/** How many arrived while you read earlier posts, for tests. */
		int missed() {
			return missed;
		}

		/** The posts shown, oldest first, for tests. */
		List<Rooms.Post> shown() {
			return new ArrayList<>(shown);
		}

		JPanel postsPanel() {
			return posts;
		}

		JScrollPane scroll() {
			return scroll;
		}

		void scrollToTop() {
			scroll.getVerticalScrollBar().setValue(0);
		}

		// -------- loops and floods --------

		private void checkLoop() {
			LoopWatch.Loop loop = LoopWatch.check(new ArrayList<>(shown), rooms.principal(), host.venueDid(),
				System.currentTimeMillis());
			banner.removeAll();
			if (loop == null) {
				banner.setVisible(false);
				return;
			}
			JLabel title = PlainText.label(loop.first().name() + " and " + loop.second().name()
				+ " are answering each other");
			Styles.style(title, "font: bold");
			title.setIcon(Lucide.icon(LOOP_ICON, 14, Theme::muted));
			banner.add(title, "wmin 0");
			long minutes = Math.max(1, Math.round(loop.millis() / 60_000.0));
			banner.add(Labels.small(loop.posts() + " posts in the last " + (minutes == 1 ? "minute" : minutes + " minutes")
				+ ", taking turns. Each waits on the room after it posts."));
			if (owned()) {
				JPanel actions = new JPanel(new MigLayout("insets 0, gap 10", "", ""));
				actions.setOpaque(false);
				actions.add(Buttons.link("Cancel their waits…", this::cancelWaits));
				if (room.toldAgent() != null) {
					actions.add(Buttons.link("Stop telling " + room.toldAgent(), this::stopTelling));
				}
				if (room.anyonePosts()) actions.add(Buttons.link("Turn off public posting", this::closePosting));
				actions.add(Buttons.link("Show in Jobs", host::openJobs));
				banner.add(actions);
				JTextArea hint = PlainText.body("A wait inside an agent's own turn becomes a job only after its tool-call "
					+ "limit; until then, stop telling it or stop the agent from Agents.");
				Styles.classes(hint, Styles.SMALL, Styles.MUTED);
				banner.add(hint, "wmin 0");
			}
			banner.setVisible(true);
			banner.revalidate();
		}

		/** The loop banner, for tests: null when none shows. */
		String bannerText() {
			if (!banner.isVisible() || banner.getComponentCount() == 0) return null;
			return ((JLabel) banner.getComponent(0)).getText();
		}

		void cancelWaits() {
			List<Rooms.Wait> waits = rooms.waits(room.name());
			if (waits.isEmpty()) {
				status("No waits on " + room.name() + " are recorded as jobs yet.", false);
				return;
			}
			if (!askCancel.test(room.name(), waits.size())) return;
			int cancelled = 0;
			for (Rooms.Wait w : waits) {
				try {
					rooms.cancel(w.jobId());
					cancelled++;
				} catch (Exception e) {
					failed(e, false);
				}
			}
			status("Cancelled " + cancelled + (cancelled == 1 ? " wait" : " waits") + " on " + room.name() + ".", false);
			buildDetails();
		}

		void stopTelling() {
			try {
				rooms.stopTelling(room.name());
				status(room.toldAgent() + " is no longer told of posts in " + room.name() + ".", false);
			} catch (Exception e) {
				failed(e, false);
			}
		}

		private void closePosting() {
			try {
				rooms.update(room.name(), room.description(), room.anyoneReads(), false, room.toldAgent(), room.toldSession());
			} catch (Exception e) {
				failed(e, false);
			}
		}

		private void checkRate() {
			long now = System.currentTimeMillis();
			long recent = shown.stream().filter(p -> now - p.ts() <= 60_000).count();
			Component rateRow = null;
			for (Component c : ((JPanel) getComponent(0)).getComponents()) if ("rate".equals(c.getName())) rateRow = c;
			if (rateRow == null) return;
			boolean flood = recent > FLOOD_PER_MINUTE;
			rateRow.setVisible(flood || pause.isSelected());
			if (flood) rate.setText(recent + " posts in the last minute");
		}
	}

	// ------------------------------------------------------------------
	// One post
	// ------------------------------------------------------------------

	/**
	 * A post: the author line from the post's record (identity mark, name,
	 * kind, "via" the token, time, number), then its text in a frame of its own,
	 * folded after twelve lines.
	 */
	static final class PostView extends JPanel {
		final Rooms.Post post;
		final Who who;
		final JTextArea text;

		/** The post's own text, as copying gives it. */
		String copyText() {
			return post.text() == null ? "" : post.text();
		}

		PostView(Rooms.Post post, String you, String venue) {
			super(new MigLayout("insets 8 18 8 18, fillx, wrap 1, gapy 3", "[grow,fill]", ""));
			this.post = post;
			this.who = Who.of(post, you, venue);
			setOpaque(false);
			setName("post-" + post.n());
			JPanel line = new JPanel(new MigLayout("insets 0, gap 6", "", ""));
			line.setOpaque(false);
			JLabel name = PlainText.label(who.name());
			Styles.style(name, "font: bold");
			if (who.kind() != Who.Kind.YOU) name.setIcon(IdentityMark.icon(who.key(), 16));
			line.add(name);
			if (who.kind() == Who.Kind.OUTSIDE || who.kind() == Who.Kind.VISITOR) {
				line.add(PlainText.small(IdentityMark.fingerprint(who.key())));
			}
			if (who.kind() != Who.Kind.YOU) {
				JLabel kind = PlainText.small(who.kind().words);
				if (who.kind() == Who.Kind.VISITOR) {
					kind.setToolTipText("Posted through public posting by someone who is not in your Home");
				}
				line.add(kind);
			}
			if (post.via() != null) {
				JLabel via = PlainText.small("· via " + post.via());
				via.setToolTipText("Came through the access token named " + PlainText.visible(post.via()));
				line.add(via);
			}
			String when = post.ts() > 0 ? new SimpleDateFormat("HH:mm").format(new Date(post.ts())) : "";
			line.add(PlainText.small(when + " · #" + post.n()));
			add(line);
			String full = post.text() == null ? "" : post.text();
			boolean folds = PlainText.folds(full);
			text = PlainText.body(folds ? PlainText.folded(full) : full);
			text.setName("text");
			JPanel frame = new JPanel(new BorderLayout());
			frame.setOpaque(false);
			frame.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 0));
			frame.add(text, BorderLayout.CENTER);
			add(frame, "wmin 0");
			// Copy keeps the post's own text; the screen shows hidden characters as markers.
			javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
			javax.swing.JMenuItem copy = new javax.swing.JMenuItem("Copy text");
			copy.addActionListener(e -> tau.ui.components.Clipboard.copy(copyText(), this));
			menu.add(copy);
			text.setComponentPopupMenu(menu);
			setComponentPopupMenu(menu);
			if (folds) {
				tau.ui.components.PressButton[] more = new tau.ui.components.PressButton[1];
				more[0] = Buttons.link(String.format("Show the whole post · %,d characters", full.length()), () -> {
					text.setText(PlainText.visible(full));
					more[0].setVisible(false);
					revalidate();
				});
				more[0].setName("more");
				add(more[0], "growx 0, gapleft 22");
			}
		}
	}

	/** What copying a post gives: its text as it was posted, hidden characters and all. */
	static String copyTextOf(PostView v) {
		return v.copyText();
	}

	/** Whether any component under {@code c} is of {@code type}, for tests. */
	static boolean contains(Component c, Class<?> type) {
		if (type.isInstance(c)) return true;
		if (c instanceof java.awt.Container k) for (Component child : k.getComponents()) if (contains(child, type)) return true;
		return false;
	}

}
