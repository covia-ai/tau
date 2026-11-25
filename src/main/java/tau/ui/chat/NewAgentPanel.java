package tau.ui.chat;

import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import java.util.Map;

import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.formdev.flatlaf.FlatClientProperties;

import tau.model.AgentTemplate;
import tau.ui.components.Dialogs;
import tau.ui.components.Labels;
import tau.ui.components.ModelSelector;
import tau.ui.components.Scrolls;
import tau.ui.components.SelectableText;
import tau.ui.components.TextArea;
import net.miginfocom.swing.MigLayout;

/**
 * Name, starting template, instructions and model for a new agent. The
 * instructions start as the template's prompt for the name typed and follow
 * the name and template until the owner edits them; from then on they are the
 * owner's and stay as written.
 */
@SuppressWarnings("serial")
public final class NewAgentPanel extends JPanel {

	/** The choices needed to create and configure an agent. */
	public record Options(String name, AgentTemplate template, String modelOp, String instructions, boolean unrestricted) {

		/** What the agent may do: its template's caps, or Unrestricted when the owner chose it. */
		public convex.core.data.ACell caps() {
			return tau.model.AgentPermissions.caps(template, unrestricted);
		}

		/** The instructions as given, or the template's prompt when they were left blank. */
		public String systemPrompt() {
			return (instructions == null || instructions.isBlank()) ? template.systemPrompt(name) : instructions;
		}
	}

	private final JTextField name = new JTextField(24);
	private final JComboBox<AgentTemplate> template = new JComboBox<>(AgentTemplate.values());
	private final SelectableText templateDescription = descriptionArea();
	private final ModelSelector model = new ModelSelector();
	private final TextArea instructions = new TextArea(6, 24);
	private final javax.swing.JCheckBox unrestricted = new javax.swing.JCheckBox(
		"Unrestricted: let it do everything you can, without asking before deletes (not recommended)");
	private final SelectableText permissions = descriptionArea();
	/** The text last filled from the template: while the area still holds it, the owner has not edited it. */
	private String filled = "";

	/** {@code served}: the models the venue catalogues and serves for each provider, by provider id. */
	public NewAgentPanel(String currentModelOp, Map<String, List<String>> served) {
		super(new MigLayout("insets 8, fillx, wrap 2", "[]14[grow,fill]", ""));
		setOpaque(false);
		name.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "e.g. Research partner");
		name.setToolTipText("The name shown in the agent list");
		template.setToolTipText("A starting role and working style; the agent can grow from here");
		template.addActionListener(e -> {
			updateTemplateDescription();
			refillInstructions();
			updatePermissions();
		});
		instructions.setLineWrap(true);
		instructions.setWrapStyleWord(true);
		instructions.setToolTipText("Who the agent is and what it is for: they open every turn, scheduled runs included");
		name.getDocument().addDocumentListener(new DocumentListener() {
			public void insertUpdate(DocumentEvent e) { refillInstructions(); }
			public void removeUpdate(DocumentEvent e) { refillInstructions(); }
			public void changedUpdate(DocumentEvent e) { refillInstructions(); }
		});
		model.offer(served);
		model.selectModelOp(currentModelOp);

		add(Labels.text("Name"));
		add(name, "growx");
		add(Labels.text("Starting point"));
		add(template, "growx");
		add(templateDescription, "skip, growx, wmin 0, gapbottom 8");
		add(Labels.text("Instructions"), "aligny top");
		add(Scrolls.vertical(instructions), "growx, wmin 0, hmin 100, gapbottom 8");
		add(Labels.text("What it may do"), "aligny top");
		add(permissions, "growx, wmin 0");
		unrestricted.setOpaque(false);
		unrestricted.setToolTipText("Anyone or anything that can drive this agent then acts with your full authority");
		unrestricted.addActionListener(e -> updatePermissions());
		add(unrestricted, "skip, gapbottom 8");
		add(model, "span 2, growx, wmin 0");
		SelectableText note = descriptionArea();
		note.setText("The model uses API credentials already stored in Settings. The instructions can be changed later on the Agent tab.");
		add(note, "span 2, growx, wmin 0, gaptop 8");
		updateTemplateDescription();
		refillInstructions();
		updatePermissions();
		setPreferredSize(new Dimension(500, getPreferredSize().height));
	}

	/** Shows the shared form in a modal dialog, returning null when cancelled. */
	public static Options showDialog(Component parent, String currentModelOp, Map<String, List<String>> served) {
		NewAgentPanel panel = new NewAgentPanel(currentModelOp, served);
		while (true) {
			SwingUtilities.invokeLater(panel.name::requestFocusInWindow);
			if (!Dialogs.form(parent, "New agent", panel)) return null;
			Options options = panel.options();
			if (options != null) return options;
			Dialogs.warn(parent, "New agent", "Give the agent a name and a model.");
		}
	}

	Options options() {
		String chosenName = name.getText().trim();
		AgentTemplate chosenTemplate = (AgentTemplate) template.getSelectedItem();
		String modelOp = model.selectedModelOp();
		return (chosenName.isEmpty() || chosenTemplate == null || modelOp == null)
			? null : new Options(chosenName, chosenTemplate, modelOp, instructions.getText().trim(), unrestricted.isSelected());
	}

	JTextField nameField() {
		return name;
	}

	JComboBox<AgentTemplate> templateField() {
		return template;
	}

	JTextArea instructionsField() {
		return instructions;
	}

	/** Fills the instructions from the template and name, unless the owner has edited them. */
	private void refillInstructions() {
		if (!instructions.getText().equals(filled)) return;
		AgentTemplate selected = (AgentTemplate) template.getSelectedItem();
		filled = (selected != null) ? selected.systemPrompt(name.getText()) : "";
		instructions.setText(filled);
		instructions.setCaretPosition(0);
	}

	/** What the chosen template lets the agent do, in a line, or the Unrestricted warning. */
	private void updatePermissions() {
		AgentTemplate selected = (AgentTemplate) template.getSelectedItem();
		if (selected == null) return;
		java.util.List<String> words = tau.model.AgentPermissions.words(
			tau.model.AgentPermissions.caps(selected, unrestricted.isSelected()));
		permissions.setText(unrestricted.isSelected() ? tau.model.AgentPermissions.UNRESTRICTED_WORDS
			: words.size() + " permissions: reads your workspace and files, keeps notes, asks you through the Inbox"
				+ (words.stream().anyMatch(w -> w.contains("asks you first")) ? "; overwrites and deletes wait for you" : "")
				+ ". The full list is on the agent's page.");
	}

	/** The Unrestricted choice, for tests. */
	javax.swing.JCheckBox unrestrictedField() {
		return unrestricted;
	}

	private void updateTemplateDescription() {
		AgentTemplate selected = (AgentTemplate) template.getSelectedItem();
		templateDescription.setText((selected != null) ? selected.description() : " ");
	}

	/** Two lines of small, muted explanation that never takes focus from the fields. */
	private static SelectableText descriptionArea() {
		SelectableText area = SelectableText.description(" ").small().unfocusable();
		area.setRows(2);
		area.setColumns(20);
		return area;
	}
}
