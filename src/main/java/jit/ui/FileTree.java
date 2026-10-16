package jit.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

/**
 * A list of changed files shown as a folder tree (single-child folders are merged, like {@code src/main/java})
 * or as a flat list. Selecting a folder selects every file below it.
 *
 * @param <T> the file type (a working tree status entry or a file changed by a commit)
 */
final class FileTree<T> extends TreeView<FileTree.Node<T>> {

    /** A folder ({@code entry == null}) or a file. */
    record Node<T>(String name, String path, T entry) {
        boolean isFolder() {
            return entry == null;
        }
    }

    private final Function<T, String> pathOf;
    private final Function<T, Character> statusOf;
    private final Function<T, String> oldPathOf;
    private final Set<String> collapsed = new HashSet<>();
    private final Label placeholder;
    private boolean tree = true;
    private List<T> entries = List.of();

    /**
     * @param oldPathOf previous path of renamed files ({@code null} when not renamed)
     */
    FileTree(Function<T, String> pathOf, Function<T, Character> statusOf, Function<T, String> oldPathOf,
             String placeholderText) {
        this.pathOf = pathOf;
        this.statusOf = statusOf;
        this.oldPathOf = oldPathOf;
        this.placeholder = Ui.label(placeholderText, "placeholder-text");
        placeholder.setMouseTransparent(true);
        getStyleClass().addAll("file-list", "file-tree");
        setShowRoot(false);
        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        setCellFactory(v -> new NodeCell());
        setRoot(new TreeItem<>());
    }

    /** A remembered tree / flat preference. */
    static BooleanProperty treeModeSetting(String key) {
        BooleanProperty p = new SimpleBooleanProperty(Boolean.parseBoolean(Settings.get(key, "true")));
        p.addListener((obs, o, n) -> Settings.put(key, n.toString()));
        return p;
    }

    /** Header button switching between folder tree and flat list. */
    static ToggleButton layoutToggle(BooleanProperty treeMode) {
        ToggleButton layout = new ToggleButton();
        layout.getStyleClass().add("layout-toggle");
        layout.setFocusTraversable(false);
        layout.selectedProperty().bindBidirectional(treeMode);
        layout.graphicProperty().bind(Bindings.createObjectBinding(
                () -> Icons.icon(treeMode.get() ? Icons.FOLDER : Icons.LIST, 13), treeMode));
        layout.tooltipProperty().bind(Bindings.createObjectBinding(
                () -> new Tooltip(treeMode.get() ? "Showing folders — click for a flat list"
                        : "Showing a flat list — click for folders"), treeMode));
        return layout;
    }

    /** Label to stack above the tree, visible while it is empty. */
    Label placeholder() {
        return placeholder;
    }

    void setPlaceholderText(String text) {
        placeholder.setText(text);
    }

    List<T> entries() {
        return entries;
    }

    void setTreeMode(boolean tree) {
        if (this.tree != tree) {
            this.tree = tree;
            setEntries(entries);
        }
    }

    /** Replaces the files, keeping selection (by path) and collapsed folders. */
    void setEntries(List<T> newEntries) {
        Set<String> selectedPaths = new LinkedHashSet<>();
        for (TreeItem<Node<T>> item : getSelectionModel().getSelectedItems()) {
            if (item != null && item.getValue() != null) {
                selectedPaths.add(item.getValue().path());
            }
        }
        int oldIndex = getSelectionModel().getSelectedIndex();
        entries = List.copyOf(newEntries);

        TreeItem<Node<T>> root = new TreeItem<>();
        if (tree) {
            Folder<T> top = new Folder<>();
            for (T e : entries) {
                Folder<T> f = top;
                String[] parts = pathOf.apply(e).split("/");
                for (int i = 0; i < parts.length - 1; i++) {
                    f = f.folders.computeIfAbsent(parts[i], k -> new Folder<>());
                }
                f.files.add(e);
            }
            addChildren(root, top, "");
        } else {
            for (T e : entries) {
                root.getChildren().add(new TreeItem<>(new Node<>(pathOf.apply(e), pathOf.apply(e), e)));
            }
        }
        setRoot(root);
        placeholder.setVisible(entries.isEmpty());

        getSelectionModel().clearSelection();
        boolean any = false;
        for (int i = 0; i < getExpandedItemCount(); i++) {
            TreeItem<Node<T>> item = getTreeItem(i);
            if (item != null && item.getValue() != null && selectedPaths.contains(item.getValue().path())) {
                getSelectionModel().select(i);
                any = true;
            }
        }
        if (!any && oldIndex >= 0 && !selectedPaths.isEmpty() && getExpandedItemCount() > 0) {
            getSelectionModel().select(firstFileAtOrAfter(Math.min(oldIndex, getExpandedItemCount() - 1)));
        }
    }

    private int firstFileAtOrAfter(int index) {
        for (int i = index; i < getExpandedItemCount(); i++) {
            if (getTreeItem(i).getValue() != null && !getTreeItem(i).getValue().isFolder()) {
                return i;
            }
        }
        return index;
    }

    private static final class Folder<T> {
        final Map<String, Folder<T>> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        final List<T> files = new ArrayList<>();
    }

    private void addChildren(TreeItem<Node<T>> parent, Folder<T> folder, String prefix) {
        for (Map.Entry<String, Folder<T>> e : folder.folders.entrySet()) {
            String name = e.getKey();
            Folder<T> f = e.getValue();
            // merge chains of folders that only contain one folder
            while (f.files.isEmpty() && f.folders.size() == 1) {
                Map.Entry<String, Folder<T>> only = f.folders.entrySet().iterator().next();
                name = name + "/" + only.getKey();
                f = only.getValue();
            }
            String path = prefix + name + "/";
            TreeItem<Node<T>> item = new TreeItem<>(new Node<>(name, path, null));
            item.setExpanded(!collapsed.contains(path));
            item.expandedProperty().addListener((obs, o, n) -> {
                if (n) {
                    collapsed.remove(path);
                } else {
                    collapsed.add(path);
                }
            });
            addChildren(item, f, path);
            parent.getChildren().add(item);
        }
        for (T file : folder.files) {
            String p = pathOf.apply(file);
            parent.getChildren().add(new TreeItem<>(new Node<>(p.substring(p.lastIndexOf('/') + 1), p, file)));
        }
    }

    /** Files of the selection; a selected folder contributes every file below it. */
    List<T> selectedEntries() {
        Set<T> result = new LinkedHashSet<>();
        for (TreeItem<Node<T>> item : getSelectionModel().getSelectedItems()) {
            collect(item, result);
        }
        return new ArrayList<>(result);
    }

    private void collect(TreeItem<Node<T>> item, Set<T> out) {
        if (item == null || item.getValue() == null) {
            return;
        }
        if (!item.getValue().isFolder()) {
            out.add(item.getValue().entry());
        }
        for (TreeItem<Node<T>> child : item.getChildren()) {
            collect(child, out);
        }
    }

    /** The selected file when exactly one file (and no folder) is selected. */
    T singleEntry() {
        List<TreeItem<Node<T>>> sel = getSelectionModel().getSelectedItems();
        if (sel.size() == 1 && sel.get(0) != null && sel.get(0).getValue() != null
                && !sel.get(0).getValue().isFolder()) {
            return sel.get(0).getValue().entry();
        }
        return null;
    }

    boolean hasSelection() {
        return !getSelectionModel().isEmpty();
    }

    void selectFirstFile() {
        if (getExpandedItemCount() > 0) {
            getSelectionModel().clearAndSelect(firstFileAtOrAfter(0));
        }
    }

    private int countFiles(TreeItem<Node<T>> item) {
        int n = 0;
        for (TreeItem<Node<T>> c : item.getChildren()) {
            n += c.getValue().isFolder() ? countFiles(c) : 1;
        }
        return n;
    }

    private final class NodeCell extends TreeCell<Node<T>> {
        NodeCell() {
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(Node<T> node, boolean empty) {
            super.updateItem(node, empty);
            setText(null);
            if (empty || node == null) {
                setGraphic(null);
                return;
            }
            if (node.isFolder()) {
                Label name = Ui.label(node.name(), "file-name");
                name.setMinWidth(0);
                Label count = Ui.label(Integer.toString(countFiles(getTreeItem())), "file-dir");
                count.setMinWidth(Region.USE_PREF_SIZE);
                HBox box = new HBox(6, Icons.icon(Icons.FOLDER, 14), name, count);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
                return;
            }
            T e = node.entry();
            char status = statusOf.apply(e);
            String oldPath = status == 'R' || status == 'C' ? oldPathOf.apply(e) : null;
            if (tree) {
                Label name = Ui.label(node.name(), "file-name");
                name.setMinWidth(0);
                HBox box = new HBox(6, Ui.statusBadge(status), name);
                if (oldPath != null) {
                    box.getChildren().add(Ui.label("← " + oldPath, "file-dir"));
                }
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
            } else {
                setGraphic(Ui.fileRow(status, pathOf.apply(e), oldPath));
            }
        }
    }
}
