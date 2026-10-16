package jit.ui;

import java.util.Optional;

import javafx.beans.value.ObservableBooleanValue;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Message boxes and a small form builder for operation dialogs. */
public final class Dialogs {
    private Dialogs() {
    }

    private static void prepare(Dialog<?> d, Window owner, String title) {
        if (owner != null) {
            d.initOwner(owner);
        }
        d.setTitle(title);
        d.getDialogPane().getStylesheets().setAll(Theme.stylesheets());
        d.getDialogPane().getStyleClass().add("jit-dialog");
    }

    public static boolean confirm(Window owner, String title, String message, String okText) {
        Dialog<ButtonType> d = new Dialog<>();
        prepare(d, owner, title);
        d.setHeaderText(title);
        Label l = new Label(message);
        l.setWrapText(true);
        l.setMaxWidth(460);
        d.getDialogPane().setContent(l);
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
        return d.showAndWait().filter(b -> b == ok).isPresent();
    }

    public static Optional<String> input(Window owner, String title, String prompt, String initial, String okText) {
        Form form = new Form();
        TextField field = form.text(prompt, initial);
        Platform.runLater(() -> {
            field.requestFocus();
            field.selectAll();
        });
        if (form.show(owner, title, null, okText, field.textProperty().isNotEmpty())) {
            return Optional.of(field.getText().strip());
        }
        return Optional.empty();
    }

    public static void error(Window owner, String title, String message) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        prepare(a, owner, title);
        a.setHeaderText(title);
        TextArea text = new TextArea(message == null ? "Unknown error" : message.strip());
        text.setEditable(false);
        text.setWrapText(true);
        text.setPrefRowCount(Math.min(14, Math.max(3, text.getText().split("\n").length + 1)));
        text.setPrefColumnCount(60);
        text.getStyleClass().add("mono");
        a.getDialogPane().setContent(text);
        a.showAndWait();
    }

    /** Shows the output of a command in a scrollable, copyable text area. */
    public static void output(Window owner, String title, String command, String output) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        prepare(a, owner, title);
        a.setHeaderText(command);
        TextArea text = new TextArea(output == null || output.isBlank() ? "(no output)" : output);
        text.setEditable(false);
        text.setPrefRowCount(Math.min(24, Math.max(4, text.getText().split("\n").length + 1)));
        text.setPrefColumnCount(90);
        text.getStyleClass().add("mono");
        a.getDialogPane().setContent(text);
        a.setResizable(true);
        a.showAndWait();
    }

    public static void info(Window owner, String title, String message) {
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        prepare(a, owner, title);
        a.setHeaderText(title);
        a.setContentText(message);
        a.showAndWait();
    }

    /** Two-column form shown in a dialog. */
    public static final class Form {
        private final GridPane grid = new GridPane();
        private int row;

        public Form() {
            grid.setHgap(12);
            grid.setVgap(10);
            grid.setPadding(new Insets(16, 20, 8, 20));
            ColumnConstraints c1 = new ColumnConstraints();
            ColumnConstraints c2 = new ColumnConstraints();
            c2.setHgrow(Priority.ALWAYS);
            c2.setMinWidth(320);
            grid.getColumnConstraints().addAll(c1, c2);
        }

        public <T extends Node> T add(String label, T node) {
            if (label != null) {
                Label l = new Label(label);
                l.getStyleClass().add("form-label");
                grid.add(l, 0, row);
                grid.add(node, 1, row);
            } else {
                grid.add(node, 1, row);
            }
            row++;
            return node;
        }

        public TextField text(String label, String value) {
            TextField f = new TextField(value == null ? "" : value);
            return add(label, f);
        }

        public CheckBox check(String text, boolean selected) {
            CheckBox cb = new CheckBox(text);
            cb.setSelected(selected);
            return add(null, cb);
        }

        public Label note(String text) {
            Label l = new Label(text);
            l.setWrapText(true);
            l.getStyleClass().add("form-note");
            l.setMaxWidth(420);
            return add(null, l);
        }

        /** The form's layout, for embedding in other containers. */
        public Node node() {
            return grid;
        }

        public boolean show(Window owner, String title, String header, String okText, ObservableBooleanValue valid) {
            Dialog<ButtonType> d = new Dialog<>();
            prepare(d, owner, title);
            d.setHeaderText(header == null ? title : header);
            VBox box = new VBox(grid);
            d.getDialogPane().setContent(box);
            ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
            d.getDialogPane().getButtonTypes().addAll(ok, ButtonType.CANCEL);
            if (valid != null) {
                d.getDialogPane().lookupButton(ok).disableProperty().bind(javafx.beans.binding.Bindings.not(valid));
            }
            return d.showAndWait().filter(b -> b == ok).isPresent();
        }
    }
}
