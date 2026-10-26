/*
 * MCreator (https://mcreator.net/)
 * Copyright (C) 2020 Pylo and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.mcreator.ui.dialogs.preferences;

import net.mcreator.blockly.data.BlocklyLoader;
import net.mcreator.plugin.MCREvent;
import net.mcreator.plugin.events.ui.PreferencesDialogEvent;
import net.mcreator.preferences.PreferencesEntry;
import net.mcreator.preferences.PreferencesManager;
import net.mcreator.ui.blockly.BlocklyEditorType;
import net.mcreator.ui.component.util.ComponentUtils;
import net.mcreator.ui.component.util.PanelUtils;
import net.mcreator.ui.dialogs.MCreatorDialog;
import net.mcreator.ui.init.L10N;
import net.mcreator.ui.laf.themes.Theme;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class PreferencesDialog extends MCreatorDialog {

	final JPanel preferences = new JPanel();

	private final Map<String, JPanel> sectionPanels = new HashMap<>();

	private final DefaultMutableTreeNode sectionsRoot = new DefaultMutableTreeNode();
	private DefaultMutableTreeNode templatesNode;
	private final DefaultTreeModel sectionsModel = new DefaultTreeModel(sectionsRoot);
	private final JTree sections = new JTree(sectionsModel);
	private final CardLayout preferencesLayout = new CardLayout();

	// ModLab: settings that are not useful anymore (nothing is sent to or fetched from mcreator.net)
	private static final Set<String> OBSOLETE_ENTRIES = Set.of("googleAnalyticsEnable", "showWebsiteNewsNotifications",
			"checkAndNotifyForPatches", "openWhatsNextPage");

	// ModLab: settings that most people never change, hidden until "Show advanced options" is checked
	private static final Set<String> ADVANCED_ENTRIES = Set.of("silentReload", "useGPUAcceleration",
			"translucentBackground", "blockRenderer", "colorSaturation", "colorValue", "useSmartSort",
			"expandCategories", "enableComments", "enableCollapse", "enableTrashcan", "maxTrashContents", "maxScale",
			"minScale", "startScale", "scaleSpeed", "maximumImports", "passLangToMinecraft", "enablePerformanceMonitor",
			"storeMetadata", "mcpEnable", "mcpPort", "autocompleteDocWindow", "useLigatures",
			"localHistorySaveInterval", "workspaceAutosaveInterval", "automatedBackupInterval",
			"numberOfBackupsToStore", "backupOnVersionSwitch", "nativeFileChooser", "expandSectionsByDefault",
			"remindOfUnsavedChanges");

	private static boolean showAdvanced = false;

	private final JTextField searchField = new JTextField();
	private final Map<PreferencesEntry<?>, JComponent> entryRows = new HashMap<>();
	private final Map<PreferencesEntry<?>, String> entryTexts = new HashMap<>();
	@Nullable private Set<String> matchingSections = null;

	private final Map<PreferencesEntry<?>, JComponent> entries = new HashMap<>();
	private final List<Runnable> pageSavers = new ArrayList<>();

	private final JButton apply = L10N.button("action.common.apply");

	private final Window parent;

	public PreferencesDialog(Window parent) {
		this(parent, null);
	}

	public PreferencesDialog(Window parent, @Nullable String selectedTab) {
		super(parent);

		this.parent = parent;

		setModal(true);
		setTitle(L10N.t("dialog.preferences.title_mcreator"));

		sections.setBackground(getBackground());
		sections.setRootVisible(false);
		sections.setShowsRootHandles(true);
		sections.setExpandsSelectedPaths(false);
		sections.setToggleClickCount(1);
		sections.setCellRenderer(new DefaultTreeCellRenderer() {
			@Override
			public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
					boolean leaf, int row, boolean hasFocus) {
				super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
				setBorder(new EmptyBorder(4, 8, 4, 10));
				if (matchingSections != null && !sel && value instanceof DefaultMutableTreeNode node && node.isLeaf()
						&& !matchingSections.contains(String.valueOf(node.getUserObject())))
					setForeground(Color.GRAY); // sections without any result for the current search
				return this;
			}
		});

		preferences.setLayout(preferencesLayout);

		JSplitPane spne = new JSplitPane();
		spne.setRightComponent(preferences);
		searchField.putClientProperty("JTextField.placeholderText", L10N.t("dialog.preferences.search"));
		searchField.putClientProperty("JTextField.showClearButton", true);
		searchField.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) {
				applyFilter();
			}

			@Override public void removeUpdate(DocumentEvent e) {
				applyFilter();
			}

			@Override public void changedUpdate(DocumentEvent e) {
				applyFilter();
			}
		});
		JPanel leftSide = new JPanel(new BorderLayout(0, 6));
		leftSide.setBorder(BorderFactory.createEmptyBorder(6, 6, 0, 0));
		leftSide.add("North", searchField);
		leftSide.add("Center", new JScrollPane(sections));
		spne.setLeftComponent(leftSide);
		spne.setContinuousLayout(true);
		spne.setDividerLocation(205);
		add("Center", spne);

		sections.setBackground(Theme.current().getBackgroundColor());
		ComponentUtils.deriveFont(sections, 13);

		JButton ok = L10N.button("dialog.preferences.save");
		JButton cancel = new JButton(UIManager.getString("OptionPane.cancelButtonText"));

		JButton reset = L10N.button("dialog.preferences.restore_defaults");
		reset.addActionListener(_ -> {
			int option = JOptionPane.showConfirmDialog(null, L10N.t("dialog.preferences.restore_defaults_confirmation"),
					L10N.t("dialog.preferences.restore_defaults"), JOptionPane.YES_NO_OPTION,
					JOptionPane.QUESTION_MESSAGE, null);
			if (option == JOptionPane.YES_OPTION) {
				PreferencesManager.getPreferencesRegistry()
						.forEach((_, entries) -> PreferencesManager.resetFromList(entries));

				dispose();
				new PreferencesDialog(parent, getSelectedSection());
			}
		});

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttons.add(ok);
		buttons.add(cancel);
		buttons.add(apply);
		apply.setEnabled(false);

		JPanel buttonsleft = new JPanel();
		buttonsleft.add(reset);

		JCheckBox advancedBox = new JCheckBox(L10N.t("dialog.preferences.show_advanced"), showAdvanced);
		advancedBox.addActionListener(_ -> {
			showAdvanced = advancedBox.isSelected();
			applyFilter();
		});
		buttonsleft.add(advancedBox);

		add("South", PanelUtils.westAndEastElement(buttonsleft, buttons));

		sections.addTreeSelectionListener(_ -> {
			String section = getSelectedSection();
			if (section != null)
				preferencesLayout.show(preferences, section);
		});

		loadSections();
		applyFilter();

		if (selectedTab != null)
			selectSection(selectedTab);

		ok.addActionListener(_ -> {
			savePreferences();
			dispose();
		});

		apply.addActionListener(_ -> {
			savePreferences();
			apply.setEnabled(false);
		});

		cancel.addActionListener(_ -> dispose());

		pack();
		setSize(Math.max(980, getBounds().width), 570);
		setLocationRelativeTo(parent);
		setVisible(true);
	}

	private void loadSections() {
		// Add preference entries
		PreferencesManager.getPreferencesRegistry().forEach(
				(_, preferences) -> preferences.stream()
						.filter(e -> e.getSection().isVisible() && !OBSOLETE_ENTRIES.contains(e.getID())).toList()
						.forEach(entry -> {
							if (!sectionPanels.containsKey(entry.getSectionKey()))
								createPreferenceSection(entry.getSectionKey());
							entries.put(entry, generateEntryComponent(entry, sectionPanels.get(entry.getSectionKey())));
						}));

		new PluginsPanel(this);

		new ThemesPanel(this);

		new EditTemplatesPanel(this, L10N.t("dialog.preferences.page_ui_backgrounds"), "backgrounds", "png", true);

		templatesNode = new DefaultMutableTreeNode(L10N.t("dialog.preferences.templates"));
		sectionsRoot.add(templatesNode);

		addEditTemplatesPanel("texture_templates", "templates/textures/texturemaker", "png");
		addEditTemplatesPanel("armor_templates", "templates/textures/armormaker", "png");

		BlocklyLoader.INSTANCE.getAllBlockLoaders().keySet().stream().filter(type -> type.extension() != null)
				.forEach(this::addEditTemplatesPanel);

		MCREvent.event(new PreferencesDialogEvent.SectionsLoaded(this));

		sections.collapsePath(new TreePath(templatesNode.getPath()));

		if (sectionsRoot.getChildCount() > 0) {
			DefaultMutableTreeNode first = (DefaultMutableTreeNode) sectionsRoot.getChildAt(0);
			sections.setSelectionPath(new TreePath(first.getPath()));
		}
	}

	/**
	 * Shows only the settings matching the search, and hides advanced settings unless asked otherwise.
	 */
	private void applyFilter() {
		String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
		String[] tokens = query.isEmpty() ? new String[0] : query.split("\\s+");

		Set<String> matches = new HashSet<>();
		entryRows.forEach((entry, row) -> {
			boolean match;
			if (tokens.length == 0) {
				match = showAdvanced || !ADVANCED_ENTRIES.contains(entry.getID());
			} else {
				String text = entryTexts.get(entry);
				match = Arrays.stream(tokens).allMatch(text::contains);
			}
			row.setVisible(match);
			if (match)
				matches.add(L10N.t("preferences.section." + entry.getSectionKey()));
		});

		matchingSections = tokens.length == 0 ? null : matches;

		if (tokens.length > 0 && !matches.isEmpty()) {
			String current = getSelectedSection();
			if (current == null || !matches.contains(current)) {
				for (int i = 0; i < sectionsRoot.getChildCount(); i++) {
					String name = String.valueOf(((DefaultMutableTreeNode) sectionsRoot.getChildAt(i)).getUserObject());
					if (matches.contains(name)) {
						selectSection(name);
						break;
					}
				}
			}
		}

		sections.repaint();
		preferences.revalidate();
		preferences.repaint();
	}

	void addSection(String name) {
		sectionsRoot.add(new DefaultMutableTreeNode(name));
		sectionsModel.reload();
	}

	/**
	 * <p>Adds a custom page to this dialog, shown when the section with the given name is selected. Pages added
	 * this way store their own values, e.g. in {@link PreferencesEntry} instances of a hidden
	 * {@link net.mcreator.preferences.PreferencesSection}, and call {@link #markChanged()} when the user changes
	 * something.</p>
	 *
	 * <p>Java plugins can add pages from {@link PreferencesDialogEvent.SectionsLoaded}. The page can be opened
	 * directly by passing its name as the selected tab to {@link #PreferencesDialog(Window, String)}.</p>
	 *
	 * @param name   Name of the section shown in the list of sections, also used to identify the page
	 * @param page   Component shown when the section is selected
	 * @param onSave Called when preferences are saved, before they are written to the preferences file
	 */
	public void addPage(String name, JComponent page, Runnable onSave) {
		addSection(name);
		preferences.add(page, name);
		pageSavers.add(onSave);
	}

	void addTemplateSection(String name) {
		templatesNode.add(new DefaultMutableTreeNode(name));
		sectionsModel.reload();
	}

	@Nullable private String getSelectedSection() {
		TreePath path = sections.getSelectionPath();
		if (path == null)
			return null;
		DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
		if (!node.isLeaf())
			return null;
		return (String) node.getUserObject();
	}

	private void selectSection(String name) {
		for (int i = 0; i < sectionsRoot.getChildCount(); i++) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) sectionsRoot.getChildAt(i);
			if (node.isLeaf() && name.equals(node.getUserObject())) {
				sections.setSelectionPath(new TreePath(node.getPath()));
				return;
			}
			for (int j = 0; j < node.getChildCount(); j++) {
				DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(j);
				if (name.equals(child.getUserObject())) {
					sections.expandPath(new TreePath(node.getPath()));
					sections.setSelectionPath(new TreePath(child.getPath()));
					return;
				}
			}
		}
	}

	public void addEditTemplatesPanel(BlocklyEditorType type) {
		addEditTemplatesPanel(type.registryName() + "_templates", "templates/" + type.extension(), type.extension());
	}

	public void addEditTemplatesPanel(String translationKey, String folder, String extension) {
		new EditTemplatesPanel(this, L10N.t("dialog.preferences.page_" + translationKey), folder, extension);
	}

	private void createPreferenceSection(String section) {
		String name = L10N.t("preferences.section." + section);
		String description = L10N.t("preferences.section." + section + ".description");
		addSection(name);

		JPanel sectionPanel = new JPanel(new GridBagLayout());
		sectionPanel.setOpaque(false);

		JComponent titlebar = L10N.label("dialog.preferences.description", name, description);
		titlebar.setBorder(BorderFactory.createEmptyBorder(0, 10, 5, 10));

		JScrollPane scrollPane = new JScrollPane(PanelUtils.pullElementUp(sectionPanel));
		scrollPane.getVerticalScrollBar().setUnitIncrement(10);
		preferences.add(PanelUtils.northAndCenterElement(titlebar, scrollPane, 0, 0), name);

		sectionPanels.put(section, sectionPanel);
	}

	private void savePreferences() {
		pageSavers.forEach(Runnable::run);
		PreferencesManager.getPreferencesRegistry().forEach((_, preferences) -> preferences.forEach(entry -> {
			if (entries.containsKey(entry))
				entry.setValueFromComponent(entries.get(entry));
		}));
		PreferencesManager.savePreferences();
	}

	private JComponent generateEntryComponent(PreferencesEntry<?> entry, JPanel placeInside) {
		String name = L10N.t("preferences." + entry.getSectionKey() + "." + entry.getID());
		String description = L10N.t("preferences." + entry.getSectionKey() + "." + entry.getID() + ".description");
		if (description == null)
			description = "";

		JComponent label = L10N.label("dialog.preferences.entry_description", name, description);
		JComponent component = entry.getComponent(parent, _ -> markChanged());
		if (component != null) {
			JPanel wrap = new JPanel(new BorderLayout()) {
				@Override public Dimension getPreferredSize() {
					Dimension retval = super.getPreferredSize();
					retval.height = 32;
					if (!(component instanceof JCheckBox)) {
						retval.width = Math.max(retval.width, 128);
					}
					return retval;
				}
			};
			wrap.add("Center", component);
			JComponent row = PanelUtils.westAndEastElement(label, PanelUtils.pullElementUp(wrap));
			placeInside.add(row, getConstraints());
			entryRows.put(entry, row);
			entryTexts.put(entry, (name + " " + description).toLowerCase(Locale.ROOT));
		} else {
			placeInside.add(L10N.label("dialog.preferences.unknown_property_type", name), getConstraints());
		}
		return component;
	}

	private GridBagConstraints getConstraints() {
		GridBagConstraints cons = new GridBagConstraints();
		cons.anchor = GridBagConstraints.NORTH;
		cons.fill = GridBagConstraints.BOTH;
		cons.gridx = 0;
		cons.weightx = 1;
		cons.weighty = 1;
		cons.insets = new Insets(5, 10, 5, 10);
		return cons;
	}

	public void markChanged() {
		apply.setEnabled(true);
	}

}
