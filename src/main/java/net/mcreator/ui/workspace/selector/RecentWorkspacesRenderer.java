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

package net.mcreator.ui.workspace.selector;

import net.mcreator.preferences.PreferencesManager;
import net.mcreator.generator.GeneratorFlavor;
import net.mcreator.ui.component.util.PanelUtils;
import net.mcreator.ui.init.L10N;
import net.mcreator.ui.laf.themes.Theme;
import net.mcreator.util.StringUtils;
import net.mcreator.util.image.ImageUtils;

import javax.swing.*;
import java.awt.*;
import java.util.Objects;

/**
 * Renders a recent workspace as a rounded card (ModLab home page)
 */
class RecentWorkspacesRenderer extends JPanel implements ListCellRenderer<RecentWorkspaceEntry> {

	private String version = null;
	private boolean isSelected = false;

	private final JLabel nameLabel = new JLabel();
	private final JLabel pathLabel = new JLabel();
	private final JLabel iconLabel = new JLabel();

	RecentWorkspacesRenderer() {
		super(new BorderLayout(12, 0));
		setOpaque(false);

		nameLabel.setFont(getFont().deriveFont(Font.BOLD, 15.0f));
		pathLabel.setFont(getFont().deriveFont(11.0f));

		add("West", iconLabel);
		add("Center", PanelUtils.northAndCenterElement(nameLabel, pathLabel));
	}

	@Override
	public Component getListCellRendererComponent(JList<? extends RecentWorkspaceEntry> list,
			RecentWorkspaceEntry value, int index, boolean isSelected, boolean cellHasFocus) {
		this.version = value.getMCRVersion();
		this.isSelected = isSelected;

		String path = value.getPath().getParentFile().getAbsolutePath().replace("\\", "/");

		Color accent = Theme.current().getInterfaceAccentColor();
		nameLabel.setForeground(isSelected ? accent : Theme.current().getForegroundColor());
		pathLabel.setForeground(Theme.current().getAltForegroundColor());

		setToolTipText(L10N.t("dialog.workspace_selector.recent_workspace", value.getName(), path,
				Objects.requireNonNullElse(value.getMCRVersion(), L10N.t("common.not_applicable"))));
		setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 16));

		if (value.getType() != GeneratorFlavor.UNKNOWN) {
			ImageIcon icon = new ImageIcon(
					ImageUtils.darken(ImageUtils.toBufferedImage(value.getType().getIcon().getImage())));

			iconLabel.setIcon(isSelected ? ImageUtils.colorize(icon, accent, false) : icon);
			nameLabel.setText(StringUtils.abbreviateString(value.getName(), 18));
			pathLabel.setText(StringUtils.abbreviateStringInverse(path, 30));
		} else {
			iconLabel.setIcon(null);
			nameLabel.setText(StringUtils.abbreviateString(value.getName(), 21));
			pathLabel.setText(StringUtils.abbreviateStringInverse(path, 36));
		}

		return this;
	}

	@Override protected void paintComponent(Graphics g) {
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		Color accent = Theme.current().getInterfaceAccentColor();
		int x = 8, y = 4, w = getWidth() - 16, h = getHeight() - 8;
		int arc = "Square".equals(PreferencesManager.PREFERENCES.ui.interfaceCornerStyle.get()) ? 0 : 14;

		g2.setColor(isSelected ?
				new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 45) :
				Theme.current().getAltBackgroundColor());
		g2.fillRoundRect(x, y, w, h, arc, arc);

		if (isSelected) {
			g2.setColor(accent);
			g2.setStroke(new BasicStroke(1.5f));
			g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
		}

		g2.dispose();
		super.paintComponent(g);
	}

}
