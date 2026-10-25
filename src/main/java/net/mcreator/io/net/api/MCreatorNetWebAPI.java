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

package net.mcreator.io.net.api;

import net.mcreator.io.net.api.update.UpdateInfo;
import net.mcreator.ui.MCreatorApplication;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ModLab version of the web API: it never contacts mcreator.net.
 * News and mod of the week are not available, updates are handled by {@link net.mcreator.io.net.api.update.ModLabUpdater}.
 */
public class MCreatorNetWebAPI implements IWebAPI {

	private static final Logger LOG = LogManager.getLogger("Website API");

	public static final ExecutorService NETWORKING_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable);
		thread.setName("WebAPI-Executor");
		thread.setUncaughtExceptionHandler((_, e) -> LOG.error(e));
		return thread;
	});

	@Override public boolean initAPI() {
		CookieHandler.setDefault(null);

		// Internet connectivity is tested against GitHub, which is where ModLab looks for updates
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress("api.github.com", 443), 3000);
			return true;
		} catch (IOException e) {
			LOG.warn("No internet connection detected");
			return false;
		}
	}

	@Override @Nullable public UpdateInfo getUpdateInfo() {
		return UpdateInfo.empty();
	}

	@Override public String getSearchURL(String searchTerm) {
		return MCreatorApplication.SERVER_DOMAIN + "/search/content?keys=" + searchTerm.replace(' ', '+');
	}

	@Override public void getWebsiteNews(CompletableFuture<String[]> data) {
		data.complete(null);
	}

	@Override public void getModOfTheWeekData(CompletableFuture<String[]> data) {
		data.complete(null);
	}

}
