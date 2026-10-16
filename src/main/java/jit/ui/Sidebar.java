package jit.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import jit.git.Ref;
import jit.git.Stash;

/** Left navigation tree: local changes, all commits, branches, remotes, tags and stashes. */
public final class Sidebar extends VBox {

    public enum Kind { LOCAL_CHANGES, ALL_COMMITS, SECTION, FOLDER, BRANCH, REMOTE, REMOTE_BRANCH, TAG, STASH }

    /** A node of the sidebar tree. */
    public static final class Item {
        public final Kind kind;
        public final String key;
        public final String text;
        public Ref ref;
        public Stash stash;
        public String remote;
        public int count;
        public String tooltip;

        Item(Kind kind, String key, String text) {
            this.kind = kind;
            this.key = key;
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private final TreeView<Item> tree = new TreeView<>();
    private final TextField filter = new TextField();
    private final Map<String, Boolean> expanded = new HashMap<>();
    private boolean updating;

    private Consumer<Item> onSelect = i -> { };
    private Consumer<Item> onOpen = i -> { };
    private Function<Item, ContextMenu> menus = i -> null;

    private List<Ref> refs = List.of();
    private Map<String, String> remotes = Map.of();
    private List<Stash> stashes = List.of();
    private int changes;

    public Sidebar() {
        getStyleClass().add("sidebar");
        tree.setShowRoot(false);
        tree.getStyleClass().add("sidebar-tree");
        tree.setCellFactory(t -> new ItemCell());
        tree.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (!updating && n != null && n.getValue() != null) {
                onSelect.accept(n.getValue());
            }
        });
        VBox.setVgrow(tree, Priority.ALWAYS);

        filter.setPromptText("Filter branches, tags…");
        filter.getStyleClass().add("sidebar-filter");
        filter.textProperty().addListener((obs, o, n) -> rebuild());
        HBox filterBox = new HBox(6, Icons.icon(Icons.SEARCH, 13), filter);
        HBox.setHgrow(filter, Priority.ALWAYS);
        filterBox.setAlignment(Pos.CENTER_LEFT);
        filterBox.getStyleClass().add("sidebar-filter-box");

        getChildren().addAll(tree, filterBox);
        expanded.put("sec:branches", true);
        expanded.put("sec:remotes", true);
        expanded.put("sec:tags", false);
        expanded.put("sec:stashes", true);
    }

    public void setOnSelect(Consumer<Item> onSelect) {
        this.onSelect = onSelect;
    }

    public void setOnOpen(Consumer<Item> onOpen) {
        this.onOpen = onOpen;
    }

    public void setMenus(Function<Item, ContextMenu> menus) {
        this.menus = menus;
    }

    public void focusFilter() {
        filter.requestFocus();
    }

    public void update(List<Ref> refs, Map<String, String> remotes, List<Stash> stashes, int changes) {
        this.refs = refs;
        this.remotes = remotes;
        this.stashes = stashes;
        this.changes = changes;
        rebuild();
    }

    /** Selects an item by key without firing the selection callback. */
    public void selectKey(String key) {
        updating = true;
        try {
            TreeItem<Item> item = find(tree.getRoot(), key);
            if (item != null) {
                tree.getSelectionModel().select(item);
            } else {
                tree.getSelectionModel().clearSelection();
            }
        } finally {
            updating = false;
        }
    }

    public void selectLocalChanges() {
        selectKey("local-changes");
    }

    public void selectAllCommits() {
        selectKey("all-commits");
    }

    private void rebuild() {
        String selectedKey = tree.getSelectionModel().getSelectedItem() == null ? null
                : tree.getSelectionModel().getSelectedItem().getValue().key;
        String f = filter.getText() == null ? "" : filter.getText().strip().toLowerCase();
        boolean filtering = !f.isEmpty();

        TreeItem<Item> root = new TreeItem<>();
        Item lc = new Item(Kind.LOCAL_CHANGES, "local-changes", "Local Changes");
        lc.count = changes;
        root.getChildren().add(new TreeItem<>(lc));
        root.getChildren().add(new TreeItem<>(new Item(Kind.ALL_COMMITS, "all-commits", "All Commits")));

        // Branches
        TreeItem<Item> branches = section("branches", "Branches");
        for (Ref r : refs) {
            if (r.kind == Ref.Kind.LOCAL && matches(r.name, f)) {
                Item it = new Item(Kind.BRANCH, "ref:" + r.fullName, leaf(r.name));
                it.ref = r;
                it.tooltip = r.name + (r.upstream != null ? "  →  " + r.upstream : "");
                insert(branches, "branches", r.name, it, filtering);
            }
        }
        addSection(root, branches, filtering);

        // Remotes
        TreeItem<Item> remotesSection = section("remotes", "Remotes");
        Set<String> remoteNames = new LinkedHashSet<>(remotes.keySet());
        for (Ref r : refs) {
            if (r.kind == Ref.Kind.REMOTE) {
                remoteNames.add(r.remote());
            }
        }
        for (String remote : remoteNames) {
            Item ri = new Item(Kind.REMOTE, "remote:" + remote, remote);
            ri.remote = remote;
            ri.tooltip = remotes.getOrDefault(remote, "");
            TreeItem<Item> remoteItem = treeItem(ri, true, filtering);
            for (Ref r : refs) {
                if (r.kind == Ref.Kind.REMOTE && r.remote().equals(remote) && matches(r.name, f)) {
                    Item it = new Item(Kind.REMOTE_BRANCH, "ref:" + r.fullName, leaf(r.remoteBranch()));
                    it.ref = r;
                    it.remote = remote;
                    it.tooltip = r.name;
                    insert(remoteItem, "remote:" + remote, r.remoteBranch(), it, filtering);
                }
            }
            if (!filtering || !remoteItem.getChildren().isEmpty()) {
                remotesSection.getChildren().add(remoteItem);
            }
        }
        addSection(root, remotesSection, filtering);

        // Tags
        TreeItem<Item> tags = section("tags", "Tags");
        List<Ref> tagRefs = new ArrayList<>();
        for (Ref r : refs) {
            if (r.kind == Ref.Kind.TAG && matches(r.name, f)) {
                tagRefs.add(r);
            }
        }
        tagRefs.sort((a, b) -> compareVersions(b.name, a.name));
        for (Ref r : tagRefs) {
            Item it = new Item(Kind.TAG, "ref:" + r.fullName, r.name);
            it.ref = r;
            tags.getChildren().add(new TreeItem<>(it));
        }
        tags.getValue().count = tagRefs.size();
        addSection(root, tags, filtering);

        // Stashes
        TreeItem<Item> stashSection = section("stashes", "Stashes");
        for (Stash s : stashes) {
            if (matches(s.message(), f)) {
                Item it = new Item(Kind.STASH, "stash:" + s.hash(), s.message());
                it.stash = s;
                it.tooltip = s.ref() + " — " + Ui.formatDate(s.time());
                stashSection.getChildren().add(new TreeItem<>(it));
            }
        }
        stashSection.getValue().count = stashSection.getChildren().size();
        addSection(root, stashSection, filtering);

        updating = true;
        try {
            tree.setRoot(root);
            if (selectedKey != null) {
                TreeItem<Item> sel = find(root, selectedKey);
                if (sel != null) {
                    tree.getSelectionModel().select(sel);
                }
            }
        } finally {
            updating = false;
        }
    }

    private static boolean matches(String text, String filter) {
        return filter.isEmpty() || text.toLowerCase().contains(filter);
    }

    private static String leaf(String name) {
        int i = name.lastIndexOf('/');
        return i < 0 ? name : name.substring(i + 1);
    }

    private TreeItem<Item> section(String id, String title) {
        Item it = new Item(Kind.SECTION, "sec:" + id, title);
        return treeItem(it, false, false);
    }

    private void addSection(TreeItem<Item> root, TreeItem<Item> section, boolean filtering) {
        if (filtering) {
            if (section.getChildren().isEmpty()) {
                return;
            }
            section.setExpanded(true);
        }
        root.getChildren().add(section);
    }

    private TreeItem<Item> treeItem(Item it, boolean defaultExpanded, boolean forceExpanded) {
        TreeItem<Item> ti = new TreeItem<>(it);
        ti.setExpanded(forceExpanded || expanded.getOrDefault(it.key, defaultExpanded));
        ti.expandedProperty().addListener((obs, o, n) -> {
            if (!updating) {
                expanded.put(it.key, n);
            }
        });
        return ti;
    }

    /** Inserts a branch below folders derived from its slash separated name. */
    private void insert(TreeItem<Item> parent, String keyPrefix, String name, Item leaf, boolean filtering) {
        String[] parts = name.split("/");
        TreeItem<Item> current = parent;
        StringBuilder path = new StringBuilder(keyPrefix);
        for (int i = 0; i < parts.length - 1; i++) {
            path.append('/').append(parts[i]);
            String key = "folder:" + path;
            TreeItem<Item> folder = null;
            for (TreeItem<Item> child : current.getChildren()) {
                if (child.getValue().key.equals(key)) {
                    folder = child;
                    break;
                }
            }
            if (folder == null) {
                folder = treeItem(new Item(Kind.FOLDER, key, parts[i]), true, filtering);
                // keep folders before plain branches
                int pos = 0;
                while (pos < current.getChildren().size()
                        && current.getChildren().get(pos).getValue().kind == Kind.FOLDER) {
                    pos++;
                }
                current.getChildren().add(pos, folder);
            }
            current = folder;
        }
        current.getChildren().add(new TreeItem<>(leaf));
    }

    private static TreeItem<Item> find(TreeItem<Item> node, String key) {
        if (node == null) {
            return null;
        }
        if (node.getValue() != null && key.equals(node.getValue().key)) {
            return node;
        }
        for (TreeItem<Item> child : node.getChildren()) {
            TreeItem<Item> r = find(child, key);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** Orders tags like version numbers: v1.10 after v1.9. */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        String[] pb = b.split("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)");
        for (int i = 0; i < Math.min(pa.length, pb.length); i++) {
            int c;
            if (Character.isDigit(pa[i].charAt(0)) && Character.isDigit(pb[i].charAt(0)) && pa[i].length() < 18
                    && pb[i].length() < 18) {
                c = Long.compare(Long.parseLong(pa[i]), Long.parseLong(pb[i]));
            } else {
                c = pa[i].compareToIgnoreCase(pb[i]);
            }
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(pa.length, pb.length);
    }

    private final class ItemCell extends TreeCell<Item> {
        ItemCell() {
            setPrefWidth(0);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null) {
                    onOpen.accept(getItem());
                }
            });
            setOnContextMenuRequested(e -> {
                Item it = getItem();
                if (it == null) {
                    return;
                }
                ContextMenu menu = menus.apply(it);
                if (menu != null) {
                    menu.show(this, e.getScreenX(), e.getScreenY());
                }
                e.consume();
            });
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("section-cell", "head-cell");
            setText(null);
            setTooltip(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            Label text = new Label(item.text);
            text.getStyleClass().add("sidebar-text");
            text.setMinWidth(0);
            HBox box = new HBox(6);
            box.setAlignment(Pos.CENTER_LEFT);
            Node icon = switch (item.kind) {
                case LOCAL_CHANGES -> Icons.icon(Icons.EDIT, 15);
                case ALL_COMMITS -> Icons.icon(Icons.LIST, 15);
                case SECTION -> null;
                case FOLDER -> Icons.icon(Icons.FOLDER, 14);
                case BRANCH -> item.ref.head ? Icons.icon(Icons.CHECK, 14, "accent") : Icons.icon(Icons.BRANCH, 14);
                case REMOTE -> Icons.icon(Icons.CLOUD, 14);
                case REMOTE_BRANCH -> Icons.icon(Icons.BRANCH, 14);
                case TAG -> Icons.icon(Icons.TAG, 14);
                case STASH -> Icons.icon(Icons.STASH, 14);
            };
            if (icon != null) {
                box.getChildren().add(icon);
            }
            box.getChildren().add(text);
            box.getChildren().add(Ui.spacer());
            switch (item.kind) {
                case SECTION -> {
                    getStyleClass().add("section-cell");
                    if (item.count > 0) {
                        box.getChildren().add(Ui.label(Integer.toString(item.count), "sidebar-count"));
                    }
                }
                case LOCAL_CHANGES -> {
                    if (item.count > 0) {
                        box.getChildren().add(Ui.label(Integer.toString(item.count), "count-badge"));
                    }
                }
                case BRANCH -> {
                    Ref r = item.ref;
                    if (r.head) {
                        getStyleClass().add("head-cell");
                    }
                    if (r.ahead > 0) {
                        box.getChildren().add(Ui.label("↑" + r.ahead, "track-label"));
                    }
                    if (r.behind > 0) {
                        box.getChildren().add(Ui.label("↓" + r.behind, "track-label"));
                    }
                    if (r.upstreamGone) {
                        box.getChildren().add(Ui.label("gone", "track-label"));
                    }
                }
                default -> {
                }
            }
            if (item.tooltip != null && !item.tooltip.isEmpty()) {
                setTooltip(new Tooltip(item.tooltip));
            }
            setGraphic(box);
        }
    }
}
