package tau.ui.chat;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.List;
import java.util.function.Function;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;

import tau.model.AgentRef;
import tau.ui.components.Borders;
import tau.ui.components.Buttons;
import tau.ui.components.Dialogs;
import tau.ui.components.Labels;
import tau.ui.components.Lucide;
import tau.ui.components.Panels;
import tau.ui.components.PressButton;
import tau.ui.components.Scrolls;
import tau.ui.components.Theme;
import tau.ui.peers.Colours;

/**
 * The agents pane (left of the sessions list on the Sessions screen): one row per
 * agent the user owns — e.g. Tau and Bob — each with its own conversations.
 * Selecting one switches the chat (and the sessions list) to that agent; a
 * mouseover shows its id; a right-click offers its Agent view and deletion.
 * The separate button beneath the scrollable list creates another agent.
 *
 * <p>Dumb like {@link ConversationList}: it renders what it's given and reports
 * clicks through a {@link Listener}; {@code Tau} owns switching/creating.
 */
@SuppressWarnings("serial")
public final class AgentList extends JPanel {

	/** Reports the user's intent; the app performs the action. */
	public interface Listener {
		void onSelectAgent(String agentId);

		void onNewAgent();

		/** Show what the agent is: identity, status, model, instructions, capabilities. */
		void onAgentInfo(String agentId);

		/** Delete the agent outright — record, conversations and memory. Already confirmed. */
		void onDeleteAgent(String agentId);
	}

	private static final int WIDTH = 158;

	private final Listener listener;
	private final JPanel rows = Panels.column();
	private String selectedId;
	private String defaultId;
	/** Each agent's colour by id, as the Peers map draws its moon; muted until told. */
	private Function<String, Color> colours = id -> Theme.muted();

	public AgentList(Listener listener) {
		super(new BorderLayout());
		this.listener = listener;
		setOpaque(false);
		setPreferredSize(new Dimension(WIDTH, 0));
		setMinimumSize(new Dimension(0, 0));
		setBorder(Borders.hairlineRight());

		JLabel header = Labels.small("Agents");
		JButton add = Buttons.tinted("Agent", Lucide.icon("plus", 16, Theme::accentText));
		add.setToolTipText("Create a new agent");
		add.addActionListener(e -> listener.onNewAgent());

		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 10));
		top.add(header, BorderLayout.WEST);

		add(top, BorderLayout.NORTH);
		add(Scrolls.vertical(Scrolls.hugTop(rows)), BorderLayout.CENTER);
		JPanel bottom = new JPanel(new BorderLayout());
		bottom.setOpaque(false);
		bottom.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
		bottom.add(add, BorderLayout.CENTER);
		add(bottom, BorderLayout.SOUTH);
	}

	/** How to colour each agent's mini moon, by id, so it matches the Peers map. */
	public void setColours(Function<String, Color> colours) {
		this.colours = colours;
		rows.repaint();
	}

	/** Replace the agent list, highlighting {@code selectedId}; {@code defaultId} is the standard agent, which can't be deleted. */
	public void setAgents(List<AgentRef> agents, String selectedId, String defaultId) {
		this.selectedId = selectedId;
		this.defaultId = defaultId;
		rows.removeAll();
		for (AgentRef a : agents) rows.add(agentRow(a));
		rows.revalidate();
		rows.repaint();
	}

	/** One agent: a {@link PressButton} row, selected when it is the one open. */
	private Component agentRow(AgentRef agent) {
		PressButton row = new PressButton(agent.name());
		row.setIcon(Colours.moon(() -> colours.apply(agent.id()), 14));
		row.setIconTextGap(8);
		row.setHorizontalAlignment(SwingConstants.LEFT);
		row.setMargin(new Insets(9, 12, 9, 12));
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		row.setSelected(agent.id().equals(selectedId));
		row.setToolTipText(details(agent));
		row.onPress(() -> listener.onSelectAgent(agent.id()));
		row.onPopup(() -> menuFor(agent));
		return row;
	}

	/** A row's mouseover: the agent's id, which never changes whatever name it shows. */
	private String details(AgentRef agent) {
		String id = "Agent id: " + agent.id();
		return agent.id().equals(defaultId) ? id + " (your standard assistant)" : id;
	}

	/** The right-click menu for one agent. */
	JPopupMenu menuFor(AgentRef agent) {
		String aid = agent.id();
		JPopupMenu menu = new JPopupMenu();

		JMenuItem open = new JMenuItem("Open");
		open.setEnabled(!aid.equals(selectedId));
		open.addActionListener(e -> listener.onSelectAgent(aid));
		menu.add(open);

		JMenuItem info = new JMenuItem("Agent info");
		info.addActionListener(e -> listener.onAgentInfo(aid));
		menu.add(info);

		menu.addSeparator();

		JMenuItem delete = new JMenuItem("Delete…");
		delete.setEnabled(!aid.equals(defaultId));
		delete.addActionListener(e -> {
			if (Dialogs.confirmDanger(this, "Delete agent",
				"Delete " + agent.name() + "? Its conversations and memory will be removed. This can't be undone.")) {
				listener.onDeleteAgent(aid);
			}
		});
		menu.add(delete);

		return menu;
	}
}
