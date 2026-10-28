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

package net.mcreator.ui;

import net.mcreator.plugin.MCREvent;
import net.mcreator.preferences.PreferencesManager;
import net.mcreator.plugin.events.ui.TabEvent;
import net.mcreator.ui.init.L10N;
import net.mcreator.ui.laf.themes.Theme;
import net.mcreator.ui.laf.OpaqueTabStripTabbedPaneUI;
import net.mcreator.ui.views.ViewBase;
import net.mcreator.util.image.EmptyIcon;
import net.mcreator.util.image.IconUtils;
import net.mcreator.util.image.ImageUtils;

import javax.annotation.Nullable;
import javax.swing.*;
import java.awt.Component;
import java.awt.RenderingHints;
import java.awt.Graphics2D;
import java.awt.Graphics;
import java.awt.Cursor;
import java.awt.Color;
import java.awt.BasicStroke;
import java.awt.event.AWTEventListener;
import java.awt.Toolkit;
import java.awt.Point;
import java.awt.AWTEvent;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntConsumer;

public class MCreatorTabs extends JTabbedPane {

	private final List<TabShownListener> tabShownListeners = new CopyOnWriteArrayList<>();

	private final List<Tab> tabs = new ArrayList<>();
	private Tab current;

	// ModLab: tab reordering and pinning
	private boolean reordering = false;
	private int dragTabIndex = -1;
	private boolean dragging = false;

	// Mouse events are watched globally (and filtered to this tab pane), so dragging works whichever
	// internal component of the tab strip receives the mouse
	private final AWTEventListener tabDragListener = event -> {
		if (!(event instanceof MouseEvent e) || !(e.getSource() instanceof Component source))
			return;
		if (source != this && !SwingUtilities.isDescendingFrom(source, this))
			return;

		Point point = SwingUtilities.convertPoint(source, e.getPoint(), this);
		switch (e.getID()) {
		case MouseEvent.MOUSE_PRESSED -> {
			int pressed = SwingUtilities.isLeftMouseButton(e) ? indexAtLocation(point.x, point.y) : -1;
			Tab pressedTab = pressed >= 0 ? getTabForTabIndex(pressed) : null;
			dragTabIndex = pressedTab != null && pressedTab.closeable ? pressed : -1;
		}
		case MouseEvent.MOUSE_RELEASED -> {
			dragTabIndex = -1;
			if (dragging) {
				dragging = false;
				setCursor(Cursor.getDefaultCursor());
				repaint();
			}
		}
		case MouseEvent.MOUSE_DRAGGED -> {
			if (dragTabIndex >= 0 && !dragging) {
				dragging = true;
				setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
			}
			dragTab(point.x);
			if (dragging)
				repaint();
		}
		default -> {
		}
		}
	};

	// the tab being dragged is highlighted with the accent color
	@Override public void paint(Graphics g) {
		super.paint(g);

		if (dragging && dragTabIndex >= 0 && dragTabIndex < getTabCount()) {
			Rectangle bounds = getBoundsAt(dragTabIndex);
			if (bounds != null) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				Color accent = Theme.current().getInterfaceAccentColor();
				int arc = "Square".equals(PreferencesManager.PREFERENCES.ui.interfaceCornerStyle.get()) ? 0 : 10;
				g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 55));
				g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, arc, arc);
				g2.setColor(accent);
				g2.setStroke(new BasicStroke(2f));
				g2.drawRoundRect(bounds.x + 1, bounds.y + 1, bounds.width - 3, bounds.height - 3, arc, arc);
				g2.dispose();
			}
		}
	}

	@Override public void addNotify() {
		super.addNotify();
		Toolkit.getDefaultToolkit().addAWTEventListener(tabDragListener,
				AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
	}

	@Override public void removeNotify() {
		Toolkit.getDefaultToolkit().removeAWTEventListener(tabDragListener);
		super.removeNotify();
	}

	MCreatorTabs() {
		putClientProperty("JTabbedPane.tabCloseCallback", (IntConsumer) tabIndex -> {
			Tab toClose = getTabForTabIndex(tabIndex);
			if (toClose != null) {
				closeTab(toClose);
			}
		});

		setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

		setFocusable(true);

		addChangeListener(_ -> {
			if (reordering)
				return;

			int selectedIndex = getSelectedIndex();
			if (selectedIndex >= 0) {
				Tab toNotifyOfShow = getTabForTabIndex(selectedIndex);
				if (toNotifyOfShow != null) {
					showTabOrGetExisting(toNotifyOfShow.identifier, true, false);
				}
			}

			SwingUtilities.invokeLater(this::requestFocusInWindow);
		});

		addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				showTabPopup(e);
			}

			@Override public void mouseReleased(MouseEvent e) {
				if (showTabPopup(e))
					return;

				int tabIndex = indexAtLocation(e.getX(), e.getY());
				if (tabIndex >= 0) {
					Tab clickedTab = getTabForTabIndex(tabIndex);
					if (clickedTab != null) {
						if (SwingUtilities.isMiddleMouseButton(e)) {
							closeTab(clickedTab);
						}
					}
				}
			}
		});

		setUI(new OpaqueTabStripTabbedPaneUI());
	}

	private void dragTab(int x) {
		if (dragTabIndex < 0)
			return;

		// only the horizontal position matters, so the pointer can drift above or below the tab strip
		int target = tabIndexAtX(x);
		if (target < 0 || target == dragTabIndex)
			return;

		// swap only once the pointer passed the middle of the other tab, otherwise tabs of
		// different widths would flip back and forth
		Rectangle bounds = getBoundsAt(target);
		if (bounds == null)
			return;
		int middle = bounds.x + bounds.width / 2;
		if (target > dragTabIndex ? x < middle : x > middle)
			return;

		Tab dragged = getTabForTabIndex(dragTabIndex);
		Tab targetTab = getTabForTabIndex(target);
		if (dragged == null || !dragged.closeable) {
			dragTabIndex = -1;
			return;
		}

		// pinned tabs move among pinned tabs, other tabs among other tabs
		if (targetTab == null || !targetTab.closeable || targetTab.pinned != dragged.pinned)
			return;

		moveTab(dragTabIndex, target);
		dragTabIndex = target;
	}

	private boolean canMoveTab(Tab tab, int delta) {
		if (!tab.closeable)
			return false;
		int target = tab.getIndex() + delta;
		if (target < 0 || target >= getTabCount())
			return false;
		Tab other = getTabForTabIndex(target);
		return other != null && other.closeable && other.pinned == tab.pinned;
	}

	private int tabIndexAtX(int x) {
		for (int i = 0; i < getTabCount(); i++) {
			Rectangle bounds = getBoundsAt(i);
			if (bounds != null && x >= bounds.x && x < bounds.x + bounds.width)
				return i;
		}
		return -1;
	}

	private static boolean isFixed(Tab tab) {
		return tab.pinned || !tab.closeable;
	}

	private void moveTab(int from, int to) {
		if (from == to || from < 0 || to < 0 || from >= getTabCount() || to >= getTabCount())
			return;

		reordering = true;
		try {
			Component component = getComponentAt(from);
			String title = getTitleAt(from);
			Icon icon = getIconAt(from);
			String tip = getToolTipTextAt(from);
			boolean wasSelected = getSelectedIndex() == from;

			removeTabAt(from);
			insertTab(title, icon, component, tip, to);

			if (wasSelected)
				setSelectedIndex(to);
		} finally {
			reordering = false;
		}
	}

	private void setTabPinned(Tab tab, boolean pin) {
		if (!tab.closeable || tab.pinned == pin)
			return;

		tab.pinned = pin;
		tab.content.putClientProperty("JTabbedPane.tabClosable", !pin);
		tab.setText(tab.getText()); // refreshes the title with or without the pin mark

		int fixedOthers = 0;
		for (Tab t : tabs)
			if (t != tab && isFixed(t))
				fixedOthers++;
		moveTab(tab.getIndex(), fixedOthers);

		revalidate();
		repaint();
	}

	private boolean showTabPopup(MouseEvent e) {
		if (!e.isPopupTrigger())
			return false;

		int tabIndex = indexAtLocation(e.getX(), e.getY());
		Tab tab = tabIndex >= 0 ? getTabForTabIndex(tabIndex) : null;
		if (tab == null)
			return false;

		JPopupMenu menu = new JPopupMenu();

		JMenuItem pin = new JMenuItem(L10N.t(tab.pinned ? "tabs.unpin" : "tabs.pin"));
		pin.setEnabled(tab.closeable);
		pin.addActionListener(_ -> setTabPinned(tab, !tab.pinned));
		menu.add(pin);

		JMenuItem moveLeft = new JMenuItem(L10N.t("tabs.move_left"));
		moveLeft.setEnabled(canMoveTab(tab, -1));
		moveLeft.addActionListener(_ -> moveTab(tab.getIndex(), tab.getIndex() - 1));
		menu.add(moveLeft);

		JMenuItem moveRight = new JMenuItem(L10N.t("tabs.move_right"));
		moveRight.setEnabled(canMoveTab(tab, 1));
		moveRight.addActionListener(_ -> moveTab(tab.getIndex(), tab.getIndex() + 1));
		menu.add(moveRight);

		menu.addSeparator();

		JMenuItem close = new JMenuItem(L10N.t("tabs.close"));
		close.setEnabled(tab.closeable && !tab.pinned);
		close.addActionListener(_ -> closeTab(tab));
		menu.add(close);

		JMenuItem closeOthers = new JMenuItem(L10N.t("tabs.close_others"));
		closeOthers.addActionListener(_ -> new ArrayList<>(tabs).stream().filter(t -> t != tab).forEach(this::closeTab));
		menu.add(closeOthers);

		menu.show(e.getComponent(), e.getX(), e.getY());
		return true;
	}

	private Tab getTabForTabIndex(int index) {
		for (Tab tab : tabs) {
			if (tab.getIndex() == index) {
				return tab;
			}
		}
		return null;
	}

	public void addTab(Tab tab) {
		addTab(tab, true);
	}

	public void addTab(final Tab tab, boolean showTab) {
		tabs.add(tab);
		tab.addImpl(this);

		MCREvent.event(new TabEvent.Added(tab));

		if (showTab)
			showTab(tab);
	}

	/**
	 * @param tab Tab to show
	 * @return true, if Tab with this identifier already exists
	 */
	public boolean showTab(Tab tab) {
		return this.showTabOrGetExisting(tab.identifier) != null;
	}

	public void showTabNoNotify(Tab tab) {
		this.showTabOrGetExisting(tab.identifier, false, true);
	}

	public Tab showTabOrGetExisting(Tab tab) {
		return this.showTabOrGetExisting(tab.identifier);
	}

	public Tab showTabOrGetExisting(Object identifier) {
		return this.showTabOrGetExisting(identifier, true, true);
	}

	private Tab showTabOrGetExisting(Object identifier, boolean notify, boolean performUIAction) {
		Tab previous = this.current;

		Tab existing = null;
		for (Tab tab : tabs) {
			if (tab.identifier.equals(identifier) || tab.identifier.toString().toLowerCase(Locale.ROOT)
					.equals(identifier.toString().toLowerCase(Locale.ROOT))) {
				if (tab == this.current) {
					return tab; // Tab is already shown, no need to do anything
				}

				SwingUtilities.invokeLater(() -> {
					if (performUIAction) {
						setSelectedIndex(tab.getIndex());
					}

					if (notify) {
						tabShownListeners.forEach(l -> l.tabShown(tab));
						if (tab.tabShownListener != null)
							tab.tabShownListener.tabShown(tab);
					}

					tab.wasPreviouslyShown = true;
				});

				this.current = tab;
				existing = tab;

				MCREvent.event(new TabEvent.Shown(tab));

				break;
			}
		}

		if (previous != null && !previous.equals(this.current)) {
			if (previous.tabHiddenListener != null)
				previous.tabHiddenListener.tabHidden(previous);
		}

		return existing;
	}

	public void closeTab(Tab tab) {
		closeTab(tab, false);
	}

	public void closeTab(Tab tab, boolean force) {
		if (!tab.closeable || (tab.pinned && !force))
			return;

		if (force || tab.tabClosingListener == null || tab.tabClosingListener.tabClosing(tab)) {
			MCREvent.event(new TabEvent.Closed(tab));

			SwingUtilities.invokeLater(() -> {
				int tabIndex = tab.getIndex();
				if (tabIndex >= 0)
					removeTabAt(tabIndex);
			});
			// identity check so a stale tab can not evict a live one
			this.tabs.removeIf(t -> t == tab);

			if (tab.tabClosedListener != null) {
				tab.tabClosedListener.tabClosed(tab);
			}
		}
	}

	public void closeAllTabs(boolean force) {
		// make copy so we don't foreach the original which is changed during iterations
		List<Tab> toClose = new ArrayList<>(tabs);
		toClose.forEach(tab -> closeTab(tab, force));
	}

	public List<Tab> getTabs() {
		return tabs;
	}

	public Tab getCurrentTab() {
		return current;
	}

	public void addTabShownListener(TabShownListener tabShownListener) {
		this.tabShownListeners.add(tabShownListener);
	}

	public static class Tab {

		private static final int ICON_SIZE = 16;

		private MCreatorTabs container;

		private final boolean closeable;
		private boolean pinned = false;

		private final Object identifier;
		private final JComponent content;

		private TabClosedListener tabClosedListener;
		private TabClosingListener tabClosingListener;
		private TabShownListener tabShownListener;
		private TabHiddenListener tabHiddenListener;

		private String text;
		@Nullable private ImageIcon icon;

		private boolean wasPreviouslyShown = false;

		public Tab(ViewBase content) {
			this(content.getViewName(), content.getViewIcon(), content,
					content.getViewName() + System.currentTimeMillis(), true);
		}

		public Tab(ViewBase content, Object identifier) {
			this(content.getViewName(), content.getViewIcon(), content, identifier, true);
		}

		public Tab(String name, JPanel content, Object identifier, boolean closeable) {
			this(name, null, content, identifier, closeable);
		}

		public Tab(String text, @Nullable ImageIcon icon, JComponent content, Object identifier, boolean closeable) {
			this.content = content;
			this.identifier = identifier;
			this.closeable = closeable;

			this.text = text;
			this.icon = icon;

			if (closeable) {
				content.putClientProperty("JTabbedPane.tabClosable", true);
			}
		}

		private void addImpl(MCreatorTabs container) {
			if (this.container != null)
				return; // Tab already added to a container

			this.container = container;

			container.addTab(text, icon, content);

			setText(text);
			setIcon(icon);
		}

		private int getIndex() {
			return container.indexOfComponent(content);
		}

		public String getText() {
			return text;
		}

		public void setText(String name) {
			this.text = name;
			if (container != null)
				container.setTitleAt(getIndex(), pinned ? "\u25CF " + name : name);
		}

		public void setIcon(@Nullable ImageIcon icon) {
			if (icon != null) {
				if (icon.getIconWidth() > ICON_SIZE || icon.getIconHeight() > ICON_SIZE)
					icon = IconUtils.resize(icon, ICON_SIZE, ICON_SIZE);
				else if (icon.getIconWidth() < ICON_SIZE || icon.getIconHeight() < ICON_SIZE) {
					icon = ImageUtils.drawOver(new EmptyIcon.ImageIcon(ICON_SIZE, ICON_SIZE), icon,
							(int) (ICON_SIZE / 2.0 - icon.getIconWidth() / 2.0),
							(int) (ICON_SIZE / 2.0 - icon.getIconHeight() / 2.0), icon.getIconWidth(),
							icon.getIconHeight());
				}
			}

			this.icon = icon;
			if (container != null)
				container.setIconAt(getIndex(), icon);
		}

		public void setTabClosedListener(TabClosedListener tabClosedListener) {
			this.tabClosedListener = tabClosedListener;
		}

		public void setTabClosingListener(TabClosingListener tabClosingListener) {
			this.tabClosingListener = tabClosingListener;
		}

		public void setTabShownListener(TabShownListener tabShownListener) {
			this.tabShownListener = tabShownListener;
		}

		public void setTabHiddenListener(TabHiddenListener tabHiddenListener) {
			this.tabHiddenListener = tabHiddenListener;
		}

		public JComponent getContent() {
			return content;
		}

		public TabClosedListener getTabClosedListener() {
			return tabClosedListener;
		}

		public boolean wasPreviouslyShown() {
			return wasPreviouslyShown;
		}

		@Override public boolean equals(Object o) {
			if (o instanceof Tab)
				return ((Tab) o).identifier.equals(identifier);
			return false;
		}

		@Override public int hashCode() {
			return identifier.hashCode();
		}

		@Override public String toString() {
			return text + " (" + identifier + ")";
		}

	}

	public interface TabShownListener {
		void tabShown(Tab tab);
	}

	public interface TabHiddenListener {
		void tabHidden(Tab tab);
	}

	public interface TabClosedListener {
		void tabClosed(Tab tab);
	}

	public interface TabClosingListener {
		boolean tabClosing(Tab tab);
	}

}
