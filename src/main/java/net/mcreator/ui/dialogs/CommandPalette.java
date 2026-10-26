/*
 * MCreator (https://mcreator.net/)
 * Copyright (C) 2012-2020, Pylo
 * Copyright (C) 2020-2026, Pylo, opensource contributors
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

package net.mcreator.ui.dialogs;

import net.mcreator.ui.MCreator;
import net.mcreator.ui.init.L10N;
import net.mcreator.ui.laf.themes.Theme;
import net.mcreator.ui.workspace.WorkspacePanel;
import net.mcreator.workspace.elements.ModElement;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/**
 * Command palette (Ctrl+K): one search box to find menu commands and mod elements of the workspace.
 */
public class CommandPalette extends JDialog {

	private static final int MAX_RESULTS = 60;

	private record Entry(String title, String subtitle, String kind, int priority, @Nullable Runnable action) {}

	private final MCreator mcreator;

	private final List<Entry> all = new ArrayList<>();
	private final DefaultListModel<Entry> model = new DefaultListModel<>();
	private final JList<Entry> list = new JList<>(model);
	private final JTextField field = new JTextField();

	public static void show(MCreator mcreator) {
		new CommandPalette(mcreator).setVisible(true);
	}

	private CommandPalette(MCreator mcreator) {
		super(mcreator, false);
		this.mcreator = mcreator;

		setUndecorated(true);
		setSize(620, 420);
		setLocation(mcreator.getX() + (mcreator.getWidth() - getWidth()) / 2, mcreator.getY() + 90);

		collectEntries();

		Color accent = Theme.current().getInterfaceAccentColor();

		field.putClientProperty("JTextField.placeholderText", L10N.t("command_palette.placeholder"));
		field.setFont(field.getFont().deriveFont(16.0f));
		field.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

		list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		list.setFixedCellHeight(46);
		list.setCellRenderer(new EntryRenderer());
		list.addMouseListener(new MouseAdapter() {
			@Override public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() >= 1 && list.locationToIndex(e.getPoint()) >= 0)
					runSelected();
			}
		});

		JScrollPane scroll = new JScrollPane(list);
		scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.current().getSecondAltBackgroundColor()));
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

		JPanel content = new JPanel(new BorderLayout());
		content.setBorder(BorderFactory.createLineBorder(accent, 1));
		content.add("North", field);
		content.add("Center", scroll);
		setContentPane(content);

		field.getDocument().addDocumentListener(new DocumentListener() {
			@Override public void insertUpdate(DocumentEvent e) {
				refresh();
			}

			@Override public void removeUpdate(DocumentEvent e) {
				refresh();
			}

			@Override public void changedUpdate(DocumentEvent e) {
				refresh();
			}
		});

		bindKey("DOWN", () -> moveSelection(1));
		bindKey("UP", () -> moveSelection(-1));
		bindKey("ENTER", this::runSelected);
		bindKey("ESCAPE", this::dispose);

		addWindowFocusListener(new WindowAdapter() {
			@Override public void windowLostFocus(WindowEvent e) {
				dispose();
			}
		});

		refresh();
		SwingUtilities.invokeLater(field::requestFocusInWindow);
	}

	private void bindKey(String keyStroke, Runnable action) {
		String name = "palette." + keyStroke;
		field.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyStroke), name);
		field.getActionMap().put(name, new AbstractAction() {
			@Override public void actionPerformed(ActionEvent e) {
				action.run();
			}
		});
	}

	private void moveSelection(int delta) {
		if (model.isEmpty())
			return;
		int next = Math.max(0, Math.min(model.size() - 1, list.getSelectedIndex() + delta));
		list.setSelectedIndex(next);
		list.ensureIndexIsVisible(next);
	}

	private void runSelected() {
		Entry selected = list.getSelectedValue();
		if (selected == null || selected.action() == null)
			return;
		dispose();
		SwingUtilities.invokeLater(selected.action());
	}

	private void collectEntries() {
		// every item of the menu bar is a command, so new menu items automatically show up here
		collectMenu(mcreator.getMainMenuBar(), "", new HashSet<>());

		// mod elements of the workspace
		if (mcreator.getWorkspacePanel() instanceof WorkspacePanel workspacePanel) {
			for (ModElement element : mcreator.getWorkspace().getModElements()) {
				all.add(new Entry(element.getName(), element.getType().getReadableName(),
						L10N.t("command_palette.kind.element"), 0,
						() -> workspacePanel.editCurrentlySelectedModElement(element, workspacePanel, 0, 0)));
			}
		}
	}

	private void collectMenu(MenuElement element, String path, Set<Object> seen) {
		for (MenuElement child : element.getSubElements()) {
			if (child instanceof JMenu menu) {
				if (menu.isVisible())
					collectMenu(menu, path.isEmpty() ? menu.getText() : path + " \u203A " + menu.getText(), seen);
			} else if (child instanceof JMenuItem item) {
				String text = item.getText();
				if (text == null || text.isBlank() || !item.isVisible() || !item.isEnabled())
					continue;
				if (!seen.add(item.getAction() != null ? item.getAction() : item))
					continue; // same action in two menus
				all.add(new Entry(text, path, L10N.t("command_palette.kind.command"), 1, item::doClick));
			} else if (child instanceof JPopupMenu popup) {
				collectMenu(popup, path, seen);
			}
		}
	}

	private void refresh() {
		String[] tokens = field.getText().trim().toLowerCase(Locale.ROOT).split("\\s+");

		List<Entry> matches = new ArrayList<>();
		for (Entry entry : all) {
			String title = entry.title().toLowerCase(Locale.ROOT);
			String haystack = title + " " + entry.subtitle().toLowerCase(Locale.ROOT);
			boolean ok = true;
			for (String token : tokens) {
				if (!token.isEmpty() && !haystack.contains(token)) {
					ok = false;
					break;
				}
			}
			if (ok)
				matches.add(entry);
		}

		String first = tokens[0];
		matches.sort(Comparator.comparingInt((Entry e) -> {
			String title = e.title().toLowerCase(Locale.ROOT);
			if (first.isEmpty() || title.startsWith(first))
				return 0;
			return title.contains(first) ? 1 : 2;
		}).thenComparingInt(Entry::priority).thenComparing(e -> e.title().length()).thenComparing(Entry::title));

		model.clear();
		for (int i = 0; i < Math.min(matches.size(), MAX_RESULTS); i++)
			model.addElement(matches.get(i));

		if (model.isEmpty())
			model.addElement(new Entry(L10N.t("command_palette.no_results"), "", "", 9, null));

		list.setSelectedIndex(0);
	}

	private static class EntryRenderer extends JPanel implements ListCellRenderer<Entry> {

		private final JLabel title = new JLabel();
		private final JLabel subtitle = new JLabel();
		private final JLabel kind = new JLabel();
		private boolean selected;

		EntryRenderer() {
			super(new BorderLayout(10, 0));
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(5, 18, 5, 18));

			title.setFont(getFont().deriveFont(Font.BOLD, 13.0f));
			subtitle.setFont(getFont().deriveFont(11.0f));
			subtitle.setForeground(Theme.current().getAltForegroundColor());
			kind.setFont(getFont().deriveFont(10.0f));
			kind.setForeground(Theme.current().getAltForegroundColor());

			JPanel texts = new JPanel(new GridLayout(2, 1));
			texts.setOpaque(false);
			texts.add(title);
			texts.add(subtitle);
			add("Center", texts);
			add("East", kind);
		}

		@Override
		public Component getListCellRendererComponent(JList<? extends Entry> list, Entry value, int index,
				boolean isSelected, boolean cellHasFocus) {
			selected = isSelected;
			title.setText(value.title());
			subtitle.setText(value.subtitle());
			kind.setText(value.kind());
			title.setForeground(isSelected ? Theme.current().getInterfaceAccentColor() : Theme.current().getForegroundColor());
			return this;
		}

		@Override protected void paintComponent(Graphics g) {
			if (selected) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				Color accent = Theme.current().getInterfaceAccentColor();
				g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 45));
				g2.fillRoundRect(6, 2, getWidth() - 12, getHeight() - 4, 12, 12);
				g2.dispose();
			}
			super.paintComponent(g);
		}

	}

}
