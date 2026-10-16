package jit.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import javafx.collections.ListChangeListener;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.skin.VirtualFlow;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import jit.diff.DiffLine;
import jit.diff.DiffParser;
import jit.diff.FileDiff;
import jit.diff.Hunk;
import jit.diff.PatchBuilder;

/** Diff viewer (unified or side by side) with hunk and line staging. */
public final class DiffView extends BorderPane {

    public enum Mode { READ_ONLY, UNSTAGED, STAGED }

    /** Receives patches built from the user's hunk / line selection. */
    public interface PatchHandler {
        void apply(String patch, boolean cached, boolean reverse, String description);
    }

    /** One row of the side-by-side layout: either a header (hunk / meta) or a left/right line pair. */
    private record SplitRow(DiffLine header, DiffLine left, DiffLine right) {
    }

    private static final List<String> ROW_CLASSES =
            List.of("diff-add", "diff-del", "diff-ctx", "diff-hunk", "diff-meta", "diff-nonl", "diff-split");
    private static final List<String> SIDE_CLASSES = List.of("side-add", "side-del", "side-ctx", "side-empty");

    private final Label title = new Label();
    private final Label stats = new Label();
    private final HBox lineActions = new HBox(6);
    private final ListView<DiffLine> unified = new ListView<>();
    private final ListView<SplitRow> split = new ListView<>();
    private final Label placeholder = new Label();
    private final Button stageLines;
    private final Button unstageLines;
    private final Button discardLines;
    private final Button externalButton;
    private final ToggleButton unifiedToggle = new ToggleButton("Unified");
    private final ToggleButton splitToggle = new ToggleButton("Side by side");
    private final HBox header;

    private FileDiff diff = new FileDiff();
    private Mode mode = Mode.READ_ONLY;
    private String path;
    private PatchHandler handler;
    private Runnable externalDiff;
    private boolean sideBySide = "split".equals(Settings.get("diffLayout", "unified"));

    public DiffView() {
        getStyleClass().add("diff-view");
        title.getStyleClass().add("diff-title");
        title.setMinWidth(0);
        stats.getStyleClass().add("diff-stats");
        stats.setMinWidth(Region.USE_PREF_SIZE);

        stageLines = Ui.smallButton("Stage lines", Icons.PLUS, () -> applyLines(false, true, "Stage lines"));
        unstageLines = Ui.smallButton("Unstage lines", Icons.MINUS, () -> applyLines(true, true, "Unstage lines"));
        discardLines = Ui.smallButton("Discard lines", Icons.UNDO, () -> {
            if (Dialogs.confirm(getScene().getWindow(), "Discard lines",
                    "Discard the selected lines? This cannot be undone.", "Discard")) {
                applyLines(true, false, "Discard lines");
            }
        });
        discardLines.getStyleClass().add("danger");
        lineActions.setAlignment(Pos.CENTER_RIGHT);

        externalButton = Ui.smallButton("External Diff", Icons.COPY, () -> {
            if (externalDiff != null) {
                externalDiff.run();
            }
        });
        externalButton.setTooltip(new Tooltip("Open this file in the external diff tool (configure in Preferences)"));

        ToggleGroup layout = new ToggleGroup();
        unifiedToggle.setToggleGroup(layout);
        splitToggle.setToggleGroup(layout);
        unifiedToggle.getStyleClass().addAll("segment", "segment-left");
        splitToggle.getStyleClass().addAll("segment", "segment-right");
        unifiedToggle.setFocusTraversable(false);
        splitToggle.setFocusTraversable(false);
        (sideBySide ? splitToggle : unifiedToggle).setSelected(true);
        layout.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == null) {
                o.setSelected(true);
                return;
            }
            setSideBySide(n == splitToggle);
        });
        HBox segments = new HBox(unifiedToggle, splitToggle);
        segments.setAlignment(Pos.CENTER);

        header = new HBox(8, Icons.icon(Icons.FILE, 14), title, stats, Ui.spacer(), lineActions, externalButton,
                segments);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("diff-header");
        setTop(header);

        setupList(unified);
        unified.setCellFactory(v -> new LineCell());
        setupList(split);
        split.setCellFactory(v -> new SplitCell());

        placeholder.getStyleClass().add("placeholder-text");
        StackPane body = new StackPane(unified, split, placeholder);
        setCenter(body);
        clear("Select a file to see its changes");
    }

    private void setupList(ListView<?> list) {
        list.getStyleClass().add("diff-list");
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.getSelectionModel().getSelectedItems().addListener((ListChangeListener<Object>) c -> updateLineActions());
        list.setOnKeyPressed(e -> {
            if (e.isShortcutDown() && e.getCode() == KeyCode.C) {
                copySelection();
                e.consume();
            }
        });
        list.setContextMenu(new ContextMenu(Ui.item("Copy", Icons.COPY, this::copySelection)));
    }

    public void setPatchHandler(PatchHandler handler) {
        this.handler = handler;
    }

    /** Action of the "External Diff" button for the file currently shown; {@code null} hides the button. */
    public void setExternalDiff(Runnable action) {
        this.externalDiff = action;
        externalButton.setVisible(action != null);
        externalButton.setManaged(action != null);
    }

    public void clear(String message) {
        this.path = null;
        this.diff = new FileDiff();
        title.setText("");
        stats.setText("");
        unified.getItems().clear();
        split.getItems().clear();
        unified.setVisible(false);
        split.setVisible(false);
        placeholder.setText(message);
        placeholder.setVisible(true);
        lineActions.getChildren().clear();
        header.setVisible(false);
        header.setManaged(false);
    }

    public void setDiff(String path, String diffText, Mode mode) {
        boolean samePath = path.equals(this.path);
        int firstVisible = samePath ? firstVisibleIndex(sideBySide ? split : unified) : 0;
        this.path = path;
        this.mode = mode;
        this.diff = DiffParser.parse(diffText);
        title.setText(path);
        stats.setText(diff.additions + diff.deletions == 0 ? "" : "+" + diff.additions + "  −" + diff.deletions);
        header.setVisible(true);
        header.setManaged(true);
        unified.getSelectionModel().clearSelection();
        split.getSelectionModel().clearSelection();
        unified.getItems().setAll(diff.rows);
        split.getItems().setAll(splitRows(diff));
        showActiveList();
        if (!diff.isEmpty()) {
            ListView<?> list = sideBySide ? split : unified;
            list.scrollTo(Math.max(0, Math.min(firstVisible, list.getItems().size() - 1)));
        }
        updateLineActions();
    }

    private void setSideBySide(boolean value) {
        if (value == sideBySide) {
            return;
        }
        sideBySide = value;
        Settings.put("diffLayout", value ? "split" : "unified");
        unified.getSelectionModel().clearSelection();
        split.getSelectionModel().clearSelection();
        if (path != null) {
            showActiveList();
        }
        updateLineActions();
    }

    private void showActiveList() {
        if (diff.isEmpty()) {
            unified.setVisible(false);
            split.setVisible(false);
            placeholder.setText(diff.binary ? "Binary file — no textual diff available" : "No changes to display");
            placeholder.setVisible(true);
        } else {
            unified.setVisible(!sideBySide);
            split.setVisible(sideBySide);
            placeholder.setVisible(false);
        }
    }

    /** Pairs deletions with the additions that follow them so they line up side by side. */
    private static List<SplitRow> splitRows(FileDiff diff) {
        List<SplitRow> rows = new ArrayList<>();
        List<DiffLine> dels = new ArrayList<>();
        List<DiffLine> adds = new ArrayList<>();
        Runnable flush = () -> {
            for (int i = 0; i < Math.max(dels.size(), adds.size()); i++) {
                rows.add(new SplitRow(null, i < dels.size() ? dels.get(i) : null, i < adds.size() ? adds.get(i) : null));
            }
            dels.clear();
            adds.clear();
        };
        for (DiffLine l : diff.rows) {
            switch (l.type) {
                case DELETE -> {
                    if (!adds.isEmpty()) {
                        flush.run();
                    }
                    dels.add(l);
                }
                case ADD -> adds.add(l);
                case CONTEXT -> {
                    flush.run();
                    rows.add(new SplitRow(null, l, l));
                }
                case NO_NEWLINE -> {
                    // shown in the unified view only
                }
                default -> {
                    flush.run();
                    rows.add(new SplitRow(l, null, null));
                }
            }
        }
        flush.run();
        return rows;
    }

    private static int firstVisibleIndex(ListView<?> list) {
        if (list.lookup(".virtual-flow") instanceof VirtualFlow<?> flow && flow.getFirstVisibleCell() != null) {
            return flow.getFirstVisibleCell().getIndex();
        }
        return 0;
    }

    private boolean canPatch() {
        return mode != Mode.READ_ONLY && handler != null && diff.isPatchable();
    }

    private Set<DiffLine> selectedLines() {
        Set<DiffLine> lines = new LinkedHashSet<>();
        if (sideBySide) {
            for (SplitRow r : split.getSelectionModel().getSelectedItems()) {
                if (r == null) {
                    continue;
                }
                if (r.left() != null) {
                    lines.add(r.left());
                }
                if (r.right() != null) {
                    lines.add(r.right());
                }
            }
        } else {
            for (DiffLine l : unified.getSelectionModel().getSelectedItems()) {
                if (l != null) {
                    lines.add(l);
                }
            }
        }
        return lines;
    }

    private void updateLineActions() {
        lineActions.getChildren().clear();
        if (!canPatch() || selectedLines().stream().noneMatch(DiffLine::isChange)) {
            return;
        }
        if (mode == Mode.UNSTAGED) {
            lineActions.getChildren().addAll(stageLines, discardLines);
        } else {
            lineActions.getChildren().add(unstageLines);
        }
    }

    private void applyLines(boolean reverse, boolean cached, String what) {
        Set<DiffLine> selected = new HashSet<>(selectedLines());
        apply(selected::contains, reverse, cached, what);
    }

    private void applyHunk(Hunk h, boolean reverse, boolean cached, String what) {
        apply(l -> l.hunk == h, reverse, cached, what);
    }

    private void apply(Predicate<DiffLine> selection, boolean reverse, boolean cached, String what) {
        String patch = PatchBuilder.build(diff, selection, reverse);
        if (patch != null && handler != null) {
            handler.apply(patch, cached, reverse, what);
        }
    }

    private void copySelection() {
        StringBuilder sb = new StringBuilder();
        if (sideBySide) {
            for (SplitRow r : split.getSelectionModel().getSelectedItems()) {
                if (r != null && r.header() == null) {
                    DiffLine l = r.right() != null ? r.right() : r.left();
                    sb.append(l.text).append('\n');
                }
            }
        } else {
            for (DiffLine l : unified.getSelectionModel().getSelectedItems()) {
                if (l != null && l.type != DiffLine.Type.HUNK && l.type != DiffLine.Type.META) {
                    sb.append(l.text).append('\n');
                }
            }
        }
        if (!sb.isEmpty()) {
            Ui.copy(sb.toString());
        }
    }

    /** Header row content shared by both layouts: hunk text plus stage / unstage / discard buttons. */
    private void fillHeader(DiffLine item, Label text, HBox buttons) {
        text.setText(item.text);
        buttons.getChildren().clear();
        if (item.type == DiffLine.Type.HUNK && canPatch()) {
            Hunk h = item.hunk;
            if (mode == Mode.UNSTAGED) {
                Button discard = Ui.smallButton("Discard hunk", Icons.UNDO, () -> {
                    if (Dialogs.confirm(getScene().getWindow(), "Discard hunk",
                            "Discard this hunk? This cannot be undone.", "Discard")) {
                        applyHunk(h, true, false, "Discard hunk");
                    }
                });
                discard.getStyleClass().add("danger");
                buttons.getChildren().addAll(
                        Ui.smallButton("Stage hunk", Icons.PLUS, () -> applyHunk(h, false, true, "Stage hunk")),
                        discard);
            } else {
                buttons.getChildren().add(
                        Ui.smallButton("Unstage hunk", Icons.MINUS, () -> applyHunk(h, true, true, "Unstage hunk")));
            }
        }
    }

    private static String rowClass(DiffLine.Type type) {
        return switch (type) {
            case ADD -> "diff-add";
            case DELETE -> "diff-del";
            case NO_NEWLINE -> "diff-nonl";
            case HUNK -> "diff-hunk";
            case META -> "diff-meta";
            default -> "diff-ctx";
        };
    }

    private final class LineCell extends ListCell<DiffLine> {
        private final Label oldNo = Ui.label("", "line-no");
        private final Label newNo = Ui.label("", "line-no");
        private final Label sign = Ui.label("", "line-sign");
        private final Label text = Ui.label("", "line-text");
        private final HBox lineBox = new HBox(oldNo, newNo, sign, text);
        private final Label hunkText = Ui.label("", "hunk-text");
        private final HBox hunkButtons = new HBox(4);
        private final HBox hunkBox = new HBox(8, hunkText, hunkButtons);

        LineCell() {
            text.setMinWidth(Region.USE_PREF_SIZE);
            hunkText.setMinWidth(Region.USE_PREF_SIZE);
            lineBox.setAlignment(Pos.CENTER_LEFT);
            hunkBox.setAlignment(Pos.CENTER_LEFT);
            hunkButtons.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(DiffLine item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(ROW_CLASSES);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            getStyleClass().add(rowClass(item.type));
            if (item.type == DiffLine.Type.HUNK || item.type == DiffLine.Type.META) {
                fillHeader(item, hunkText, hunkButtons);
                setGraphic(hunkBox);
                return;
            }
            oldNo.setText(item.oldNo > 0 ? Integer.toString(item.oldNo) : "");
            newNo.setText(item.newNo > 0 ? Integer.toString(item.newNo) : "");
            sign.setText(switch (item.type) {
                case ADD -> "+";
                case DELETE -> "−";
                default -> " ";
            });
            text.setText(item.text.isEmpty() ? " " : item.text);
            setGraphic(lineBox);
        }
    }

    private final class SplitCell extends ListCell<SplitRow> {
        private final Label leftNo = Ui.label("", "line-no");
        private final Label leftText = Ui.label("", "line-text");
        private final HBox leftBox = new HBox(leftNo, leftText);
        private final Label rightNo = Ui.label("", "line-no");
        private final Label rightText = Ui.label("", "line-text");
        private final HBox rightBox = new HBox(rightNo, rightText);
        private final HBox lineBox = new HBox(leftBox, rightBox);
        private final Label hunkText = Ui.label("", "hunk-text");
        private final HBox hunkButtons = new HBox(4);
        private final HBox hunkBox = new HBox(8, hunkText, hunkButtons);

        SplitCell() {
            setPrefWidth(0);
            for (HBox side : List.of(leftBox, rightBox)) {
                side.setAlignment(Pos.CENTER_LEFT);
                side.getStyleClass().add("split-side");
                side.setMinWidth(0);
                side.prefWidthProperty().bind(split.widthProperty().subtract(16).divide(2));
                HBox.setHgrow(side, Priority.ALWAYS);
            }
            leftText.setMinWidth(0);
            rightText.setMinWidth(0);
            leftText.getStyleClass().add("split-text");
            rightText.getStyleClass().add("split-text");
            lineBox.setAlignment(Pos.CENTER_LEFT);
            hunkBox.setAlignment(Pos.CENTER_LEFT);
            hunkButtons.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(SplitRow item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll(ROW_CLASSES);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            if (item.header() != null) {
                getStyleClass().add(rowClass(item.header().type));
                fillHeader(item.header(), hunkText, hunkButtons);
                setGraphic(hunkBox);
                return;
            }
            getStyleClass().add("diff-split");
            fillSide(leftBox, leftNo, leftText, item.left(), true);
            fillSide(rightBox, rightNo, rightText, item.right(), false);
            setGraphic(lineBox);
        }

        private void fillSide(Node box, Label no, Label text, DiffLine line, boolean left) {
            box.getStyleClass().removeAll(SIDE_CLASSES);
            if (line == null) {
                box.getStyleClass().add("side-empty");
                no.setText("");
                text.setText(" ");
                return;
            }
            box.getStyleClass().add(switch (line.type) {
                case ADD -> "side-add";
                case DELETE -> "side-del";
                default -> "side-ctx";
            });
            int n = left ? line.oldNo : line.newNo;
            no.setText(n > 0 ? Integer.toString(n) : "");
            text.setText(line.text.isEmpty() ? " " : line.text);
        }
    }
}
