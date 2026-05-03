package de.weigend.s202.ui.wfx.tangles;

import de.weigend.s202.reader.EdgeKind;
import io.softwareecg.wfx.windowmtg.api.Position;
import io.softwareecg.wfx.windowmtg.api.View;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;

import java.net.URL;
import java.util.List;
import java.util.function.Consumer;

/**
 * Side-panel listing the largest dependency cycles ("tangles" — SCCs of
 * size &gt; 1) in the focused architecture view, scoped to the current
 * selection.
 * <p>
 * The TreeView is three levels deep:
 * <ol>
 *   <li>{@link TangleRow}: rank, size, member preview</li>
 *   <li>{@link EdgeRow}: a from→to edge inside the tangle</li>
 *   <li>{@link KindRow}: one row per relationship kind. {@code CALLS}
 *       expands further into one row per called method name.</li>
 * </ol>
 * Double-clicking a {@link KindRow} triggers the configured
 * {@link #setOnOpenTangle open-tangle handler} so the host shell can spin
 * up a dedicated graph view filtered to that tangle's classes.
 */
public class TopTanglesView implements View {

    public static final String VIEW_ID = "s202-top-tangles";

    /** Sealed model so the cell factory can render rows differently per level. */
    public sealed interface Row permits TangleRow, EdgeRow, KindRow {}
    public record TangleRow(int rank, Tangle tangle) implements Row {}
    public record EdgeRow(String from, String to) implements Row {}
    /**
     * One relationship-kind line beneath an {@link EdgeRow}.
     * {@code methodName} is non-null only for {@link EdgeKind#CALLS} entries.
     * {@code parentTangle} is held so the click handler can identify which
     * tangle to open without traversing back up the tree.
     */
    public record KindRow(EdgeKind kind, String methodName, Tangle parentTangle) implements Row {}

    /** Display data for a single tangle. */
    public record Tangle(int size, List<String> members, List<TangleEdge> edges) {}
    /** A from→to edge inside a tangle, decomposed into per-kind entries. */
    public record TangleEdge(String from, String to, List<KindEntry> entries) {}
    /** One {@code (kind, optional method name)} pair on an edge. */
    public record KindEntry(EdgeKind kind, String methodName) {}

    private final BorderPane root = new BorderPane();
    private final Label scopeLabel = new Label("No architecture loaded");
    private final TreeView<Row> treeView = new TreeView<>();

    /** Carries enough context to open a dedicated tab for the tangle and pre-highlight an edge. */
    public record OpenRequest(Tangle tangle, String fromClass, String toClass) {}

    /** Invoked when the user double-clicks a {@link KindRow}. May be null. */
    private Consumer<OpenRequest> openTangleHandler;

    public TopTanglesView() {
        root.getStyleClass().add("top-tangles-view");
        scopeLabel.getStyleClass().add("top-tangles-scope");

        treeView.setShowRoot(false);
        treeView.getStyleClass().add("top-tangles-tree");
        treeView.setCellFactory(tv -> new RowCell());
        treeView.setRoot(new TreeItem<>(null));

        // EventFilter on MOUSE_PRESSED — TreeCellBehavior's expand/collapse on
        // double-click runs in MOUSE_PRESSED of the *second* press (it's
        // tied to clickCount, not the later MOUSE_CLICKED event). Filtering
        // CLICKED was too late: the toggle had already fired. Filtering
        // PRESSED with clickCount==2 catches it before the behaviour runs.
        // Selection from the first press is already established, so our
        // open-logic still has the right item.
        treeView.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, ev -> {
            if (ev.getButton() != MouseButton.PRIMARY || ev.getClickCount() != 2) {
                return;
            }
            TreeItem<Row> selected = treeView.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getValue() instanceof KindRow kr
                    && selected.getParent() != null
                    && selected.getParent().getValue() instanceof EdgeRow er
                    && openTangleHandler != null) {
                openTangleHandler.accept(new OpenRequest(kr.parentTangle(), er.from(), er.to()));
            }
            // Always consume — never let the default behaviour collapse the
            // row the user just acted on. The disclosure arrow is still
            // available for explicit expand/collapse.
            ev.consume();
        });

        root.setTop(scopeLabel);
        root.setCenter(treeView);
    }

    /**
     * Replace the displayed tangles. Pass an empty list to show "no cycles".
     *
     * @param scopeName  user-facing scope description (e.g. "All classes",
     *                   "com.foo.bar"), shown in the header.
     * @param tangles    top-N tangles, already ranked. May be empty; never null.
     */
    public void setData(String scopeName, List<Tangle> tangles) {
        scopeLabel.setText(scopeName == null || scopeName.isEmpty()
                ? "Scope: All classes"
                : "Scope: " + scopeName);

        TreeItem<Row> rootItem = new TreeItem<>(null);
        int rank = 1;
        for (Tangle t : tangles) {
            TreeItem<Row> tangleItem = new TreeItem<>(new TangleRow(rank++, t));
            for (TangleEdge edge : t.edges()) {
                TreeItem<Row> edgeItem = new TreeItem<>(new EdgeRow(edge.from(), edge.to()));
                for (KindEntry entry : edge.entries()) {
                    edgeItem.getChildren().add(new TreeItem<>(
                            new KindRow(entry.kind(), entry.methodName(), t)));
                }
                tangleItem.getChildren().add(edgeItem);
            }
            rootItem.getChildren().add(tangleItem);
        }
        treeView.setRoot(rootItem);
    }

    public void clear() {
        scopeLabel.setText("No architecture loaded");
        treeView.setRoot(new TreeItem<>(null));
    }

    /**
     * Programmatically select the row matching {@code (from, to)} in any
     * tangle. Expands the parent tangle and the matching {@code EdgeRow},
     * then selects the first {@code KindRow} under it (the actual method /
     * relationship line). Falls back to selecting the {@code EdgeRow} when
     * no KindRow exists. No-op if no matching edge is found.
     * Programmatic selection doesn't fire the double-click handler, so this
     * cannot loop back into the open-tangle path.
     */
    public void selectEdgeRow(String from, String to) {
        if (from == null || to == null) {
            return;
        }
        TreeItem<Row> root = treeView.getRoot();
        if (root == null) {
            return;
        }
        for (TreeItem<Row> tangleItem : root.getChildren()) {
            for (TreeItem<Row> edgeItem : tangleItem.getChildren()) {
                if (edgeItem.getValue() instanceof EdgeRow er
                        && from.equals(er.from()) && to.equals(er.to())) {
                    tangleItem.setExpanded(true);
                    edgeItem.setExpanded(true);
                    // Drill into the first KindRow so the user sees the
                    // method/relationship line itself selected, not just the
                    // class-pair header.
                    TreeItem<Row> target = edgeItem;
                    if (!edgeItem.getChildren().isEmpty()) {
                        target = edgeItem.getChildren().get(0);
                    }
                    treeView.getSelectionModel().select(target);
                    int row = treeView.getRow(target);
                    if (row >= 0) {
                        treeView.scrollTo(row);
                    }
                    return;
                }
            }
        }
    }

    /**
     * Set the handler invoked on double-click of a {@link KindRow}. Pass
     * {@code null} to detach. The request carries both the tangle and the
     * specific from→to edge that backed the clicked row.
     */
    public void setOnOpenTangle(Consumer<OpenRequest> handler) {
        this.openTangleHandler = handler;
    }

    private static String simple(String fqn) {
        if (fqn == null) return "";
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    private static String renderKind(KindRow k) {
        if (k.kind() == EdgeKind.CALLS && k.methodName() != null) {
            return "calls: " + k.methodName();
        }
        return k.kind().label();
    }

    private static String previewMembers(List<String> members) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(2, members.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(simple(members.get(i)));
        }
        if (members.size() > n) {
            sb.append(", …");
        }
        return sb.toString();
    }

    private static final class RowCell extends TreeCell<Row> {
        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                getStyleClass().removeAll("top-tangles-tangle-row",
                        "top-tangles-edge-row", "top-tangles-kind-row");
                return;
            }
            getStyleClass().removeAll("top-tangles-tangle-row",
                    "top-tangles-edge-row", "top-tangles-kind-row");
            switch (item) {
                case TangleRow t -> {
                    setText("#" + t.rank() + "  size " + t.tangle().size()
                            + "  (" + previewMembers(t.tangle().members()) + ")");
                    getStyleClass().add("top-tangles-tangle-row");
                }
                case EdgeRow e -> {
                    setText(simple(e.from()) + " → " + simple(e.to()));
                    getStyleClass().add("top-tangles-edge-row");
                }
                case KindRow k -> {
                    setText(renderKind(k));
                    getStyleClass().add("top-tangles-kind-row");
                }
            }
        }
    }

    @Override
    public String getViewId() {
        return VIEW_ID;
    }

    @Override
    public String getTitle() {
        return "Top Tangles";
    }

    @Override
    public String getToolTipInfo() {
        return "Largest dependency cycles (SCCs) in the current scope. "
                + "Double-click a method/kind row to open the tangle.";
    }

    @Override
    public Position getDefaultPosition() {
        return Position.BOTTOM;
    }

    @Override
    public Parent getRootNode() {
        return root;
    }

    @Override
    public URL getViewImagePath() {
        return null;
    }

    @Override
    public double getViewAreaSize() {
        return 0.20;
    }
}
