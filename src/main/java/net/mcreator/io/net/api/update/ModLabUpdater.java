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

package net.mcreator.io.net.api.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.mcreator.ModLabConfig;
import net.mcreator.preferences.PreferencesManager;
import net.mcreator.ui.component.util.ThreadUtil;
import net.mcreator.ui.init.L10N;
import net.mcreator.ui.init.UIRES;
import net.mcreator.ui.notifications.INotificationConsumer;
import net.mcreator.ui.notifications.NotificationsRenderer;
import net.mcreator.util.DesktopUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import javax.swing.*;
import java.awt.*;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Checks the latest release published on the ModLab GitHub repository (see modlab.conf).
 */
public class ModLabUpdater {

	private static final Logger LOG = LogManager.getLogger("ModLabUpdater");

	/**
	 * @param success true if GitHub could be reached and answered
	 * @param release latest published release, null if the repository has none yet
	 */
	public record CheckResult(boolean success, @Nullable Release release) {
		public boolean updateAvailable() {
			return release != null && isNewer(release.version(), ModLabConfig.version());
		}
	}

	public record Release(String version, String url) {}

	public static CheckResult fetchLatest() {
		String repo = ModLabConfig.updateRepo();
		if (repo.isEmpty())
			return new CheckResult(true, null);

		try {
			HttpURLConnection conn = (HttpURLConnection) new URI(
					"https://api.github.com/repos/" + repo + "/releases/latest").toURL().openConnection();
			conn.setConnectTimeout(8000);
			conn.setReadTimeout(8000);
			conn.setInstanceFollowRedirects(true);
			conn.setRequestProperty("Accept", "application/vnd.github+json");
			conn.setRequestProperty("User-Agent", "ModLab/" + ModLabConfig.version());

			int code = conn.getResponseCode();
			if (code == 404) // no release published yet
				return new CheckResult(true, null);
			if (code != 200) {
				LOG.warn("Update check failed, HTTP {}", code);
				return new CheckResult(false, null);
			}

			try (InputStream in = conn.getInputStream()) {
				JsonObject json = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
						.getAsJsonObject();
				String tag = json.get("tag_name").getAsString();
				String url = json.get("html_url").getAsString();
				return new CheckResult(true, new Release(tag.replaceFirst("^[vV]", ""), url));
			}
		} catch (Exception e) {
			LOG.warn("Update check failed", e);
			return new CheckResult(false, null);
		}
	}

	/**
	 * Silent check at startup, shows a notification if a newer release exists
	 */
	public static <T extends Window & INotificationConsumer> void checkOnStartup(T parent) {
		if (!PreferencesManager.PREFERENCES.notifications.checkAndNotifyForUpdates.get())
			return;

		new Thread(() -> {
			CheckResult result = fetchLatest();
			if (result.success() && result.updateAvailable()) {
				Release release = result.release();
				ThreadUtil.runOnSwingThread(() -> parent.addNotification(UIRES.get("18px.info"),
						L10N.t("notification.modlab_update.msg", "V" + release.version(),
						"V" + ModLabConfig.version()),
						new NotificationsRenderer.ActionButton(L10N.t("dialog.update_notify.open_download_page"),
								e -> DesktopUtils.browseSafe(release.url()))));
			}
		}, "ModLabUpdateCheck").start();
	}

	/**
	 * Check requested by the user from the menu, always answers with a dialog
	 */
	public static void checkInteractive(Window parent) {
		new Thread(() -> {
			CheckResult result = fetchLatest();
			ThreadUtil.runOnSwingThread(() -> {
				if (!result.success()) {
					JOptionPane.showMessageDialog(parent,
							L10N.t("dialog.update_notify.error_failed_check_internet_message"),
							L10N.t("dialog.update_notify.error_failed_check_internet_title"),
							JOptionPane.WARNING_MESSAGE);
				} else if (result.updateAvailable()) {
					Release release = result.release();
					Object[] options = { L10N.t("dialog.update_notify.open_download_page"),
							L10N.t("dialog.update_notify.remind_later") };
					int option = JOptionPane.showOptionDialog(parent,
							L10N.label("dialog.modlab_update.message", "V" + release.version(),
							"V" + ModLabConfig.version()),
							L10N.t("dialog.update_notify.update_title"), JOptionPane.YES_NO_CANCEL_OPTION,
							JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
					if (option == 0)
						DesktopUtils.browseSafe(release.url());
				} else {
					JOptionPane.showMessageDialog(parent, L10N.t("dialog.update_notify.no_update_message"),
							L10N.t("dialog.update_notify.no_update_title"), JOptionPane.INFORMATION_MESSAGE);
				}
			});
		}, "ModLabUpdateCheck").start();
	}

	/**
	 * Compares dotted versions numerically (1.10.0 is newer than 1.9.2)
	 */
	static boolean isNewer(String latest, String current) {
		String[] a = latest.split("[^0-9]+");
		String[] b = current.split("[^0-9]+");
		for (int i = 0; i < Math.max(a.length, b.length); i++) {
			long x = i < a.length && !a[i].isEmpty() ? Long.parseLong(a[i]) : 0;
			long y = i < b.length && !b[i].isEmpty() ? Long.parseLong(b[i]) : 0;
			if (x != y)
				return x > y;
		}
		return false;
	}

}
