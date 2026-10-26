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

package net.mcreator.ui.action.impl.workspace;

import net.mcreator.ui.MCreator;
import net.mcreator.ui.action.ActionRegistry;
import net.mcreator.ui.action.BasicAction;
import net.mcreator.ui.init.L10N;
import net.mcreator.util.DesktopUtils;
import net.mcreator.workspace.ShareableZIPManager;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * Exports the workspace to a ZIP in one click: no file chooser, the file is named automatically, copied to the
 * clipboard (so it can be pasted in Discord or an e-mail) and its folder is offered to be opened.
 */
public class ShareWorkspaceAction extends BasicAction {

	public ShareWorkspaceAction(ActionRegistry actionRegistry) {
		super(actionRegistry, L10N.t("action.workspace.share_workspace"), e -> share(actionRegistry.getMCreator()));
	}

	private static void share(MCreator mcreator) {
		File folder = new File(System.getProperty("user.home"), "ModLabShares");
		if (!folder.isDirectory() && !folder.mkdirs()) {
			showError(mcreator);
			return;
		}

		String stamp = new SimpleDateFormat("yyyyMMdd-HHmm").format(new Date());
		File file = new File(folder, mcreator.getWorkspaceFolder().getName() + "-" + stamp + ".zip");

		ShareableZIPManager.exportZIP(L10N.t("dialog.workspace.export_workspace.title"), file, mcreator, true);

		if (!file.isFile() || file.length() == 0) {
			showError(mcreator);
			return;
		}

		try {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new FileSelection(file), null);
		} catch (Exception ignored) {
			// clipboard not available, the file is still saved
		}

		Object[] options = { L10N.t("dialog.share_workspace.open_folder"), UIManager.getString("OptionPane.okButtonText") };
		int choice = JOptionPane.showOptionDialog(mcreator,
				L10N.t("dialog.share_workspace.done", file.getAbsolutePath()),
				L10N.t("dialog.share_workspace.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
				null, options, options[0]);
		if (choice == 0)
			DesktopUtils.openSafe(file, true);
	}

	private static void showError(MCreator mcreator) {
		JOptionPane.showMessageDialog(mcreator, L10N.t("dialog.share_workspace.failed"),
				L10N.t("dialog.share_workspace.title"), JOptionPane.ERROR_MESSAGE);
	}

	private static class FileSelection implements Transferable {

		private final List<File> files;

		FileSelection(File file) {
			this.files = List.of(file);
		}

		@Override public DataFlavor[] getTransferDataFlavors() {
			return new DataFlavor[] { DataFlavor.javaFileListFlavor };
		}

		@Override public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.javaFileListFlavor.equals(flavor);
		}

		@Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if (!isDataFlavorSupported(flavor))
				throw new UnsupportedFlavorException(flavor);
			return files;
		}

	}

}
