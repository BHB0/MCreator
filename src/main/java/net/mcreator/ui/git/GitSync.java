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

package net.mcreator.ui.git;

import net.mcreator.ui.MCreator;
import net.mcreator.ui.MCreatorApplication;
import net.mcreator.ModLabConfig;
import com.google.gson.JsonParser;
import net.mcreator.ui.dialogs.ProgressDialog;
import net.mcreator.ui.init.L10N;
import net.mcreator.util.DesktopUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.net.http.HttpResponse;
import java.net.http.HttpRequest;
import java.net.http.HttpClient;
import java.net.URI;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Supplier;

/**
 * Simple Git synchronization of the workspace, based on the Git installed on the computer.
 * Everything is available from the Git menu: link the workspace to a repository, send changes, get changes.
 */
public final class GitSync {

	private static final Logger LOG = LogManager.getLogger("Git sync");

	private static final long TIMEOUT_SECONDS = 180;

	// Generated or local files that should not be shared (same idea as the shareable ZIP exports)
	private static final String GITIGNORE = String.join("\n", "# ModLab workspace: generated or local files",
			".gradle/", "build/", "run/", "runs/", ".idea/", ".eclipse/", ".settings/", ".mcreator/", "/gradle/",
			"/build.gradle", "/gradlew", "/gradlew.bat", "/mcreator.gradle", "/gradle.properties", "/settings.gradle", "/.classpath",
			"/.project", "*.iml", "");

	private record GitResult(int code, String output) {
		boolean ok() {
			return code == 0;
		}
	}

	private record PullResult(GitResult result, boolean changed, boolean conflict, boolean unrelated) {}

	private final MCreator mcreator;
	private final File dir;

	private GitSync(MCreator mcreator) {
		this.mcreator = mcreator;
		this.dir = mcreator.getWorkspaceFolder();
	}

	public static void connect(MCreator mcreator) {
		new GitSync(mcreator).connect();
	}

	public static void push(MCreator mcreator) {
		new GitSync(mcreator).push();
	}

	public static void pull(MCreator mcreator) {
		new GitSync(mcreator).pull();
	}

	public static void invite(MCreator mcreator) {
		new GitSync(mcreator).invite();
	}

	public static void status(MCreator mcreator) {
		new GitSync(mcreator).status();
	}

	// ---------------------------------------------------------------------------------------------------------

	/**
	 * @return true if the workspace is now linked to a repository
	 */
	private boolean connect() {
		if (!checkGit())
			return false;

		GitResult current = git("remote", "get-url", "origin");
		Object answer = JOptionPane.showInputDialog(mcreator, L10N.t("git.connect.message"),
				L10N.t("git.connect.title"), JOptionPane.QUESTION_MESSAGE, null, null,
				current.ok() ? current.output().trim() : "https://github.com/");
		if (answer == null || answer.toString().isBlank())
			return false;
		String url = answer.toString().trim();

		GitResult result = withProgress(L10N.t("git.connect.title"), () -> {
			if (!new File(dir, ".git").exists()) {
				GitResult init = git("init", "-b", "main");
				if (!init.ok())
					init = git("init"); // older versions of Git do not know -b
				if (!init.ok())
					return init;
			}

			File ignore = new File(dir, ".gitignore");
			if (!ignore.exists()) {
				try {
					Files.writeString(ignore.toPath(), GITIGNORE, StandardCharsets.UTF_8);
				} catch (IOException e) {
					LOG.warn("Failed to write .gitignore", e);
				}
			}

			return current.ok() ? git("remote", "set-url", "origin", url) : git("remote", "add", "origin", url);
		});

		if (result == null || !result.ok()) {
			showOutput(L10N.t("git.connect.title"), explain(result), JOptionPane.ERROR_MESSAGE);
			return false;
		}

		JOptionPane.showMessageDialog(mcreator, L10N.t("git.connect.done", url), L10N.t("git.connect.title"),
				JOptionPane.INFORMATION_MESSAGE);
		return true;
	}

	private void push() {
		if (!checkGit())
			return;
		if (!hasRepo() || !hasRemote()) {
			if (!connect())
				return;
		}

		mcreator.getWorkspace().getFileManager().saveWorkspaceDirectlyAndWait();

		String defaultMessage = "ModLab: " + new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
		Object answer = JOptionPane.showInputDialog(mcreator, L10N.t("git.push.message"), L10N.t("git.push.title"),
				JOptionPane.QUESTION_MESSAGE, null, null, defaultMessage);
		if (answer == null)
			return;
		String message = answer.toString().isBlank() ? defaultMessage : answer.toString().trim();

		GitResult result = withProgress(L10N.t("git.push.title"), () -> {
			GitResult add = git("add", "-A");
			if (!add.ok())
				return add;

			if (!git("status", "--porcelain").output().isBlank()) {
				GitResult commit = git("commit", "-m", message);
				if (!commit.ok())
					return commit;
			}

			return git("push", "-u", "origin", "HEAD");
		});

		if (result != null && result.ok()) {
			JOptionPane.showMessageDialog(mcreator, L10N.t("git.push.done"), L10N.t("git.push.title"),
					JOptionPane.INFORMATION_MESSAGE);
		} else {
			showOutput(L10N.t("git.push.title"), explain(result), JOptionPane.ERROR_MESSAGE);
		}
	}

	private void pull() {
		if (!checkGit())
			return;
		if (!hasRepo() || !hasRemote()) {
			if (!connect())
				return;
		}

		mcreator.getWorkspace().getFileManager().saveWorkspaceDirectlyAndWait();

		PullResult pull = doPull(false);

		// the repository has its own history, unrelated to this workspace: ask before merging both
		if (pull != null && pull.unrelated()) {
			int choice = JOptionPane.showConfirmDialog(mcreator, L10N.t("git.pull.unrelated"),
					L10N.t("git.pull.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (choice != JOptionPane.YES_OPTION)
				return;
			pull = doPull(true);
		}

		if (pull == null) {
			showOutput(L10N.t("git.pull.title"), explain(null), JOptionPane.ERROR_MESSAGE);
		} else if (pull.conflict()) {
			Object[] options = { L10N.t("git.pull.abort_merge"), L10N.t("git.pull.keep_merge") };
			int choice = JOptionPane.showOptionDialog(mcreator, L10N.t("git.pull.conflict"),
					L10N.t("git.pull.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options,
					options[0]);
			if (choice == 0)
				git("merge", "--abort");
		} else if (!pull.result().ok()) {
			String output = pull.result().output();
			if (output.contains("couldn't find remote ref") || output.contains("Couldn't find remote ref")) {
				JOptionPane.showMessageDialog(mcreator, L10N.t("git.pull.empty_remote"), L10N.t("git.pull.title"),
						JOptionPane.INFORMATION_MESSAGE);
			} else {
				showOutput(L10N.t("git.pull.title"), explain(pull.result()), JOptionPane.ERROR_MESSAGE);
			}
		} else if (pull.changed()) {
			JOptionPane.showMessageDialog(mcreator, L10N.t("git.pull.reload"), L10N.t("git.pull.title"),
					JOptionPane.INFORMATION_MESSAGE);
			reloadWorkspace();
		} else {
			JOptionPane.showMessageDialog(mcreator, L10N.t("git.pull.up_to_date"), L10N.t("git.pull.title"),
					JOptionPane.INFORMATION_MESSAGE);
		}
	}

	private PullResult doPull(boolean allowUnrelatedHistories) {
		return withProgress(L10N.t("git.pull.title"), () -> {
			String before = git("rev-parse", "HEAD").output().trim();

			// never lose local work: unsent changes are saved in a commit before merging
			GitResult add = git("add", "-A");
			if (!add.ok())
				return new PullResult(add, false, false, false);
			if (!git("status", "--porcelain").output().isBlank()) {
				GitResult commit = git("commit", "-m", "ModLab: backup before getting changes");
				if (!commit.ok())
					return new PullResult(commit, false, false, false);
			}

			String branch = git("rev-parse", "--abbrev-ref", "HEAD").output().trim();
			List<String> args = new ArrayList<>(List.of("pull", "--no-rebase", "--no-edit"));
			if (allowUnrelatedHistories)
				args.add("--allow-unrelated-histories");
			args.add("origin");
			args.add(branch);

			GitResult merge = git(args.toArray(new String[0]));
			String after = git("rev-parse", "HEAD").output().trim();

			String output = merge.output();
			boolean conflict = output.contains("CONFLICT") || output.contains("Automatic merge failed");
			boolean unrelated = !merge.ok() && output.contains("unrelated histories");
			return new PullResult(merge, !before.equals(after), conflict, unrelated);
		});
	}

	/**
	 * Closes the workspace and opens it again, so the changes downloaded by Git are loaded.
	 */
	private void reloadWorkspace() {
		File workspaceFile = mcreator.getWorkspace().getFileManager().getWorkspaceFile();
		MCreatorApplication application = mcreator.getApplication();

		byte[] downloaded;
		try {
			downloaded = Files.readAllBytes(workspaceFile.toPath());
		} catch (IOException e) {
			LOG.error("Failed to read the downloaded workspace file", e);
			JOptionPane.showMessageDialog(mcreator, L10N.t("git.pull.reload_failed"), L10N.t("git.pull.title"),
					JOptionPane.WARNING_MESSAGE);
			return;
		}

		// Closing a workspace saves the version kept in memory, which would erase the downloaded changes.
		// So the downloaded workspace file is written back right after closing, then the workspace is opened again.
		if (!mcreator.closeThisMCreator(true))
			return;

		try {
			Files.write(workspaceFile.toPath(), downloaded);
		} catch (IOException e) {
			LOG.error("Failed to restore the downloaded workspace file", e);
			JOptionPane.showMessageDialog(null, L10N.t("git.pull.reload_failed"), L10N.t("git.pull.title"),
					JOptionPane.WARNING_MESSAGE);
			return;
		}

		application.openWorkspaceInMCreator(workspaceFile, true);
	}

	/**
	 * Invites someone to the GitHub repository of the workspace, and offers to make the repository private first.
	 * It uses the GitHub account that Git already uses on this computer, nothing else has to be installed.
	 */
	private void invite() {
		String title = L10N.t("git.invite.title");

		if (!checkGit())
			return;
		if (!hasRepo() || !hasRemote()) {
			JOptionPane.showMessageDialog(mcreator, L10N.t("git.status.no_repo"), title,
					JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		String slug = githubSlug();
		if (slug == null) {
			JOptionPane.showMessageDialog(mcreator, L10N.t("git.invite.no_slug"), title,
					JOptionPane.WARNING_MESSAGE);
			return;
		}

		String token = githubToken();
		if (token == null) {
			int choice = JOptionPane.showConfirmDialog(mcreator, L10N.t("git.invite.manual", slug), title,
					JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
				DesktopUtils.browseSafe("https://github.com/" + slug + "/settings/access");
			return;
		}

		GitResult repository = withProgress(title, () -> github("GET", "/repos/" + slug, token, null));
		if (repository == null || !repository.ok()) {
			showOutput(title, explain(repository), JOptionPane.ERROR_MESSAGE);
			return;
		}

		boolean isPrivate = false;
		try {
			isPrivate = JsonParser.parseString(repository.output()).getAsJsonObject().get("private").getAsBoolean();
		} catch (Exception e) {
			LOG.warn("Could not read the visibility of the repository", e);
		}

		if (!isPrivate) {
			Object[] options = { L10N.t("git.invite.make_private"), L10N.t("git.invite.keep_public"),
					UIManager.getString("OptionPane.cancelButtonText") };
			int choice = JOptionPane.showOptionDialog(mcreator, L10N.t("git.invite.public", slug), title,
					JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
			if (choice == 0) {
				GitResult edit = withProgress(title,
						() -> github("PATCH", "/repos/" + slug, token, "{\"private\":true}"));
				if (edit == null || !edit.ok()) {
					showOutput(title, explain(edit), JOptionPane.ERROR_MESSAGE);
					return;
				}
			} else if (choice != 1) {
				return;
			}
		}

		Object answer = JOptionPane.showInputDialog(mcreator, L10N.t("git.invite.user"), title,
				JOptionPane.QUESTION_MESSAGE, null, null, "");
		if (answer == null)
			return;
		String user = answer.toString().trim().replaceFirst("^@", "");
		if (!user.matches("[A-Za-z0-9-]{1,39}")) {
			JOptionPane.showMessageDialog(mcreator, L10N.t("git.invite.invalid_user"), title,
					JOptionPane.WARNING_MESSAGE);
			return;
		}

		GitResult result = withProgress(title, () -> github("PUT", "/repos/" + slug + "/collaborators/" + user, token,
				"{\"permission\":\"push\"}"));
		if (result != null && result.ok()) {
			JOptionPane.showMessageDialog(mcreator, L10N.t("git.invite.done", user), title,
					JOptionPane.INFORMATION_MESSAGE);
		} else {
			showOutput(title, explain(result), JOptionPane.ERROR_MESSAGE);
		}
	}

	/**
	 * Asks Git for the GitHub credentials it already uses to push (Git Credential Manager). The token is only kept
	 * in memory for the request, ModLab never stores it.
	 *
	 * @return the token, or null if Git has no GitHub credentials
	 */
	private String githubToken() {
		GitResult credentials = exec("protocol=https\nhost=github.com\n\n", "git", "credential", "fill");
		if (!credentials.ok())
			return null;

		for (String line : credentials.output().split("\\R")) {
			if (line.startsWith("password=") && line.length() > "password=".length())
				return line.substring("password=".length()).trim();
		}
		return null;
	}

	/**
	 * @return a result with code 0 on success, otherwise the HTTP status code (or a negative number if the request failed)
	 */
	private GitResult github(String method, String path, String token, String jsonBody) {
		try {
			HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://api.github.com" + path))
					.timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
					.header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
					.header("User-Agent", "ModLab/" + ModLabConfig.version());
			if (jsonBody != null) {
				request.header("Content-Type", "application/json")
						.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
			} else {
				request.method(method, HttpRequest.BodyPublishers.noBody());
			}

			HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
			HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
			int code = response.statusCode();
			return new GitResult(code >= 200 && code < 300 ? 0 : code, response.body());
		} catch (IOException e) {
			return new GitResult(-2, "GitHub request failed: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new GitResult(-3, "Interrupted");
		}
	}

	/**
	 * @return owner/repository of the GitHub remote, or null if the remote is not on GitHub
	 */
	private String githubSlug() {
		GitResult remote = git("remote", "get-url", "origin");
		if (!remote.ok())
			return null;
		Matcher matcher = Pattern.compile("github\\.com[:/]+([^/]+)/([^/]+?)(?:\\.git)?/?$")
				.matcher(remote.output().trim());
		return matcher.find() ? matcher.group(1) + "/" + matcher.group(2) : null;
	}

	private void status() {
		if (!checkGit())
			return;
		if (!hasRepo()) {
			JOptionPane.showMessageDialog(mcreator, L10N.t("git.status.no_repo"), L10N.t("git.status.title"),
					JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		String text = withProgress(L10N.t("git.status.title"), () -> {
			StringBuilder sb = new StringBuilder();
			sb.append(git("remote", "get-url", "origin").output().trim()).append("\n\n");
			sb.append(git("status", "--short", "--branch").output().trim()).append("\n\n");
			sb.append(git("log", "-5", "--format=%h  %s  (%cr)").output().trim());
			return sb.toString();
		});

		showOutput(L10N.t("git.status.title"), text == null ? "" : text, JOptionPane.INFORMATION_MESSAGE);
	}

	// ---------------------------------------------------------------------------------------------------------

	private boolean checkGit() {
		if (git("--version").ok())
			return true;

		JOptionPane.showMessageDialog(mcreator, L10N.t("git.no_git"), L10N.t("git.title"), JOptionPane.ERROR_MESSAGE);
		return false;
	}

	private boolean hasRepo() {
		return new File(dir, ".git").exists();
	}

	private boolean hasRemote() {
		return git("remote", "get-url", "origin").ok();
	}

	/**
	 * Turns the output of a failed Git command into a message that tells the user what to do
	 */
	private String explain(GitResult result) {
		if (result == null)
			return L10N.t("git.error.generic");

		String output = result.output();
		String lower = output.toLowerCase();
		String hint = null;
		if (result.code() == 401 || result.code() == 403 || result.code() == 404 || result.code() == 422)
			hint = L10N.t("git.error.api_access");
		else if (lower.contains("tell me who you are") || lower.contains("author identity unknown"))
			hint = L10N.t("git.error.identity");
		else if (lower.contains("rejected") || lower.contains("non-fast-forward") || lower.contains("fetch first"))
			hint = L10N.t("git.error.rejected");
		else if (lower.contains("authentication failed") || lower.contains("terminal prompts disabled")
				|| lower.contains("could not read username") || lower.contains("permission denied")
				|| lower.contains("repository not found"))
			hint = L10N.t("git.error.auth");

		return (hint == null ? "" : hint + "\n\n") + "--- Git ---\n" + output.trim();
	}

	private void showOutput(String title, String text, int messageType) {
		JTextArea area = new JTextArea(text);
		area.setEditable(false);
		area.setLineWrap(true);
		area.setWrapStyleWord(true);
		area.setCaretPosition(0);
		JScrollPane scroll = new JScrollPane(area);
		scroll.setPreferredSize(new Dimension(560, 260));
		JOptionPane.showMessageDialog(mcreator, scroll, title, messageType);
	}

	private <T> T withProgress(String title, Supplier<T> job) {
		AtomicReference<T> result = new AtomicReference<>();
		ProgressDialog dial = new ProgressDialog(mcreator, title);
		Thread thread = new Thread(() -> {
			ProgressDialog.ProgressUnit unit = new ProgressDialog.ProgressUnit(title);
			dial.addProgressUnit(unit);
			try {
				result.set(job.get());
				unit.markStateOk();
			} catch (Exception e) {
				LOG.error("Git operation failed", e);
				unit.markStateError();
			}
			dial.hideDialog();
		}, "GitSync");
		thread.start();
		dial.setVisible(true);
		return result.get();
	}

	private GitResult git(String... args) {
		return exec(null, "git", args);
	}

	private GitResult exec(String stdin, String executable, String... args) {
		List<String> command = new ArrayList<>();
		command.add(executable);
		command.addAll(Arrays.asList(args));

		try {
			ProcessBuilder builder = new ProcessBuilder(command).directory(dir).redirectErrorStream(true);
			builder.environment().put("GIT_TERMINAL_PROMPT", "0"); // never wait for a hidden password prompt
			Process process = builder.start();

			if (stdin != null) {
				try (OutputStream out = process.getOutputStream()) {
					out.write(stdin.getBytes(StandardCharsets.UTF_8));
				} catch (IOException ignored) {
				}
			} else {
				process.getOutputStream().close();
			}

			StringBuffer output = new StringBuffer();
			Thread reader = new Thread(() -> {
				try {
					output.append(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
				} catch (IOException ignored) {
				}
			}, "GitSync-reader");
			reader.start();

			if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				reader.join(2000);
				return new GitResult(-1, "Timeout: " + String.join(" ", command));
			}
			reader.join(5000);

			return new GitResult(process.exitValue(), output.toString());
		} catch (IOException e) {
			return new GitResult(-2, executable + " not found: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new GitResult(-3, "Interrupted");
		}
	}

}
