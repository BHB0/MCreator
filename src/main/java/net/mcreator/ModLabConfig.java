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

package net.mcreator;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Settings specific to ModLab, read from modlab.conf in the resources.
 */
public class ModLabConfig {

	private static final Properties PROPERTIES = new Properties();

	static {
		try (InputStream in = ModLabConfig.class.getResourceAsStream("/modlab.conf")) {
			if (in != null)
				PROPERTIES.load(in);
		} catch (IOException ignored) {
		}
	}

	public static String version() {
		return PROPERTIES.getProperty("version", "0.0.0").trim();
	}

	/**
	 * @return GitHub repository in owner/name format, empty if update checks are disabled
	 */
	public static String updateRepo() {
		return PROPERTIES.getProperty("update.repo", "").trim();
	}

	/**
	 * @return Discord application ID, empty if Rich Presence is not configured
	 */
	public static String discordAppId() {
		return PROPERTIES.getProperty("discord.appid", "").trim();
	}

}
