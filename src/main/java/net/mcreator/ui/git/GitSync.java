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
import net.mcreator.ui.dialogs.ProgressDialog;
import net.mcreator.ui.init.L10N;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
			"/build.gradle", "/gradlew", "/gradlew.bat", "/mcreator.gradle", "/.classpath", "/.project", "*.iml", "");

	private record GitResult(int code, String output) {
		boolean ok() {
			return code == 0;
		}
	}

	private record PullResult(GitResult result, boolean changed, boolean conflict) {}

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

		PullResult pull = withProgress(L10N.t("git.pull.title"), () -> {
			String before = git("rev-parse", "HEAD").output().trim();

			// never lose local work: unsent changes are saved in a commit before merging
			GitResult add = git("add", "-A");
			if (!add.ok())
				return new PullResult(add, false, false);
			if (!git("status", "--porcelain").output().isBlank()) {
				GitResult commit = git("commit", "-m", "ModLab: backup before getting changes");
				if (!commit.ok())
					return new PullResult(commit, false, false);
			}

			String branch = git("rev-parse", "--abbrev-ref", "HEAD").output().trim();
			GitResult merge = git("pull", "--no-rebase", "--no-edit", "origin", branch);
			String after = git("rev-parse", "HEAD").output().trim();

			boolean conflict = merge.output().contains("CONFLICT") || merge.output().contains("Automatic merge failed");
			return new PullResult(merge, !before.equals(after), conflict);
		});

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
			int choice = JOptionPane.showConfirmDialog(mcreator, L10N.t("git.pull.reload"), L10N.t("git.pull.title"),
					JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
			if (choice == JOptionPane.YES_OPTION)
				mcreator.closeThisMCreator(mcreator.getApplication().getOpenMCreatorsCount() <= 1);
		} else {
			JOptionPane.showMessageDialog(mcreator, L10N.t("git.pull.up_to_date"), L10N.t("git.pull.title"),
					JOptionPane.INFORMATION_MESSAGE);
		}
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
		if (lower.contains("tell me who you are") || lower.contains("author identity unknown"))
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
		List<String> command = new ArrayList<>();
		command.add("git");
		command.addAll(Arrays.asList(args));

		try {
			ProcessBuilder builder = new ProcessBuilder(command).directory(dir).redirectErrorStream(true);
			builder.environment().put("GIT_TERMINAL_PROMPT", "0"); // never wait for a hidden password prompt
			Process process = builder.start();

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
			return new GitResult(-2, "Git not found: " + e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new GitResult(-3, "Interrupted");
		}
	}

}
