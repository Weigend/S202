package de.weigend.s202.ui.wfx.tangles;

import de.weigend.s202.reader.EdgeKind;
import io.softwareecg.wfx.windowmtg.api.Position;
import io.softwareecg.wfx.windowmtg.api.View;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;

import java.net.URL;
import java.util.List;
import java.util.function.BiConsumer;
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
 *   <li>{@link KindRow}: one row per relationship kind.</li>
 * </ol>
 * Double-clicking a {@link TangleRow} triggers the configured
 * {@link #setOnOpenTangle open-tangle handler} so the host shell can spin
 * up a dedicated graph view filtered to that tangle's classes.
 */
public class TopTanglesView implements View {

    public static final String VIEW_ID = "s202-top-tangles";

    /** Sealed model so the cell factory can render rows differently per level. */
    public sealed interface Row permits TangleRow, EdgeRow, KindRow, RefactoringPreviewRow, HotspotHeaderRow, HotspotEntryRow, HotspotCallerRow {}
    public record TangleRow(int rank, Tangle tangle) implements Row {}
    public record EdgeRow(String from, String to, boolean cycleBreakEdge, boolean cutApplied) implements Row {}
    /** One relationship-kind line beneath an {@link EdgeRow}. */
    public record KindRow(EdgeKind kind, String detail) implements Row {}
    /** A cut edge that is removed from the SCC graph in the current preview. */
    public record RefactoringPreviewRow(String from, String to) implements Row {}
    /** Section header separating the method-hotspot list from the tangle list. */
    public record HotspotHeaderRow() implements Row {}
    /** One method call that eliminates the most tangle edges when removed. */
    public record HotspotEntryRow(String label, int edgeCount, List<HotspotCallerRow> callerRows) implements Row {}
    /** One caller edge (with FQNs) shown as child of a {@link HotspotEntryRow}. */
    public record HotspotCallerRow(String from, String to) implements Row {}

    /** Display data for a single tangle. */
    public record Tangle(int size, String key, String title, List<String> members, List<TangleEdge> edges) {}
    /** A from→to edge inside a tangle, decomposed into per-kind entries. */
    public record TangleEdge(String from, String to, List<KindEntry> entries,
                             boolean cycleBreakEdge, boolean cutApplied) {}
    /** One relationship kind on an edge. */
    public record KindEntry(EdgeKind kind, String detail) {}
    /** A from→to edge removed from the graph for the current refactoring preview. */
    public record RefactoringPreviewEdge(String from, String to) {}

    private final BorderPane root = new BorderPane();
    private final Label scopeLabel = new Label("No architecture loaded");
    private final TreeView<Row> treeView = new TreeView<>();

    /** Carries enough context to open a dedicated tab for the tangle. */
    public record OpenRequest(Tangle tangle) {}

    /** Invoked when the user double-clicks a {@link TangleRow}. May be null. */
    private Consumer<OpenRequest> openTangleHandler;
    private BiConsumer<String, String> cutEdgeHandler = (from, to) -> {};
    private BiConsumer<String, String> restoreEdgeHandler = (from, to) -> {};
    private Consumer<Tangle> cutAllHandler = tangle -> {};

    public TopTanglesView() {
        root.getStyleClass().add("top-tangles-view");
        scopeLabel.getStyleClass().add("top-tangles-scope");

        treeView.setShowRoot(false);
        treeView.getStyleClass().add("top-tangles-tree");
        treeView.setCellFactory(tv -> new RowCell());
        treeView.setRoot(new TreeItem<>(null));

        // EventFilter on MOUSE_PRESSED: TreeCellBehavior's expand/collapse on
        // double-click runs in MOUSE_PRESSED of the second press. Catch only
        // TangleRow double-clicks before the default behaviour toggles them.
        treeView.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, ev -> {
            if (ev.getButton() != MouseButton.PRIMARY || ev.getClickCount() != 2) {
                return;
            }
            TreeItem<Row> selected = treeView.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getValue() instanceof TangleRow tr
                    && openTangleHandler != null) {
                openTangleHandler.accept(new OpenRequest(tr.tangle()));
                ev.consume();
            }
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
        setData(scopeName, tangles, List.of());
    }

    public void setData(String scopeName, List<Tangle> tangles,
                        List<RefactoringPreviewEdge> refactoringPreviewEdges) {
        setData(scopeName, tangles, refactoringPreviewEdges, List.of());
    }

    public void setData(String scopeName, List<Tangle> tangles,
                        List<RefactoringPreviewEdge> refactoringPreviewEdges,
                        List<HotspotEntryRow> hotspots) {
        scopeLabel.setText(scopeName == null || scopeName.isEmpty()
                ? "Scope: All classes"
                : "Scope: " + scopeName);

        TreeItem<Row> rootItem = new TreeItem<>(null);
        for (RefactoringPreviewEdge edge : refactoringPreviewEdges) {
            rootItem.getChildren().add(new TreeItem<>(new RefactoringPreviewRow(edge.from(), edge.to())));
        }
        int rank = 1;
        for (Tangle t : tangles) {
            TreeItem<Row> tangleItem = new TreeItem<>(new TangleRow(rank++, t));
            for (TangleEdge edge : t.edges()) {
                TreeItem<Row> edgeItem = new TreeItem<>(
                        new EdgeRow(edge.from(), edge.to(), edge.cycleBreakEdge(), edge.cutApplied()));
                for (KindEntry entry : edge.entries()) {
                    edgeItem.getChildren().add(new TreeItem<>(new KindRow(entry.kind(), entry.detail())));
                }
                tangleItem.getChildren().add(edgeItem);
            }
            rootItem.getChildren().add(tangleItem);
        }
        if (!hotspots.isEmpty()) {
            rootItem.getChildren().add(new TreeItem<>(new HotspotHeaderRow()));
            for (HotspotEntryRow h : hotspots) {
                TreeItem<Row> hotspotItem = new TreeItem<>(h);
                for (HotspotCallerRow caller : h.callerRows()) {
                    hotspotItem.getChildren().add(new TreeItem<>(caller));
                }
                rootItem.getChildren().add(hotspotItem);
            }
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
     * Mark a tangle edge as an applied cut without rebuilding the tree, so the
     * user's expansion state survives the interaction from the tangle view.
     */
    public void markCutEdge(String from, String to) {
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
                    edgeItem.setValue(new EdgeRow(er.from(), er.to(), er.cycleBreakEdge(), true));
                }
            }
        }
    }

    /**
     * Set the handler invoked on double-click of a {@link TangleRow}. Pass
     * {@code null} to detach.
     */
    public void setOnOpenTangle(Consumer<OpenRequest> handler) {
        this.openTangleHandler = handler;
    }

    public void setOnCutEdge(BiConsumer<String, String> handler) {
        this.cutEdgeHandler = handler == null ? (from, to) -> {} : handler;
    }

    public void setOnRestoreEdge(BiConsumer<String, String> handler) {
        this.restoreEdgeHandler = handler == null ? (from, to) -> {} : handler;
    }

    public void setOnCutAll(Consumer<Tangle> handler) {
        this.cutAllHandler = handler == null ? tangle -> {} : handler;
    }

    private static String simple(String fqn) {
        if (fqn == null) return "";
        int i = fqn.lastIndexOf('.');
        return i < 0 ? fqn : fqn.substring(i + 1);
    }

    private static String renderKind(KindRow k) {
        return k.detail() == null || k.detail().isBlank()
                ? k.kind().label()
                : k.kind().label() + " " + k.detail();
    }

    private final class RowCell extends TreeCell<Row> {
        @Override
        protected void updateItem(Row item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setContextMenu(null);
                getStyleClass().removeAll("top-tangles-tangle-row",
                        "top-tangles-edge-row", "top-tangles-cut-edge-row",
                        "top-tangles-applied-cut-edge-row", "top-tangles-refactoring-preview-row",
                        "top-tangles-kind-row", "top-tangles-hotspot-header-row",
                        "top-tangles-hotspot-entry-row", "top-tangles-hotspot-caller-row");
                return;
            }
            getStyleClass().removeAll("top-tangles-tangle-row",
                    "top-tangles-edge-row", "top-tangles-cut-edge-row",
                    "top-tangles-applied-cut-edge-row", "top-tangles-refactoring-preview-row",
                    "top-tangles-kind-row", "top-tangles-hotspot-header-row",
                    "top-tangles-hotspot-entry-row", "top-tangles-hotspot-caller-row");
            switch (item) {
                case TangleRow t -> {
                    setText(t.tangle().title());
                    setContextMenu(tangleContextMenu(t.tangle()));
                    getStyleClass().add("top-tangles-tangle-row");
                }
                case EdgeRow e -> {
                    setText((e.cycleBreakEdge() ? "CUT " : "") + simple(e.from()) + " → " + simple(e.to()));
                    setContextMenu(edgeContextMenu(e));
                    if (e.cutApplied()) {
                        getStyleClass().add("top-tangles-applied-cut-edge-row");
                    } else {
                        getStyleClass().add(e.cycleBreakEdge()
                                ? "top-tangles-cut-edge-row"
                                : "top-tangles-edge-row");
                    }
                }
                case KindRow k -> {
                    setText(renderKind(k));
                    setContextMenu(null);
                    getStyleClass().add("top-tangles-kind-row");
                }
                case RefactoringPreviewRow p -> {
                    setText("Refactoring Preview " + simple(p.from()) + " → " + simple(p.to()));
                    setContextMenu(restoreContextMenu(p.from(), p.to()));
                    getStyleClass().add("top-tangles-refactoring-preview-row");
                }
                case HotspotHeaderRow h -> {
                    setText("Top Cut Targets");
                    setContextMenu(null);
                    getStyleClass().add("top-tangles-hotspot-header-row");
                }
                case HotspotEntryRow e -> {
                    String suffix = e.edgeCount() == 1 ? " edge" : " edges";
                    setText(e.label() + "  [" + e.edgeCount() + suffix + "]");
                    setContextMenu(hotspotCutAllMenu(e));
                    getStyleClass().add("top-tangles-hotspot-entry-row");
                }
                case HotspotCallerRow c -> {
                    setText(simple(c.from()) + " → " + simple(c.to()));
                    setContextMenu(null);
                    getStyleClass().add("top-tangles-hotspot-caller-row");
                }
            }
        }

        private ContextMenu edgeContextMenu(EdgeRow row) {
            if (row.cutApplied()) {
                return restoreContextMenu(row.from(), row.to());
            }
            if (!row.cycleBreakEdge()) {
                return null;
            }
            MenuItem cut = new MenuItem("Cut");
            cut.setOnAction(e -> cutEdgeHandler.accept(row.from(), row.to()));
            return new ContextMenu(cut);
        }

        private ContextMenu hotspotCutAllMenu(HotspotEntryRow row) {
            MenuItem cutAll = new MenuItem("Cut All");
            cutAll.setOnAction(e -> row.callerRows()
                    .forEach(c -> cutEdgeHandler.accept(c.from(), c.to())));
            return new ContextMenu(cutAll);
        }

        private ContextMenu tangleContextMenu(Tangle tangle) {
            boolean hasCutCandidate = tangle.edges().stream()
                    .anyMatch(edge -> edge.cycleBreakEdge() && !edge.cutApplied());
            if (!hasCutCandidate) {
                return null;
            }
            MenuItem cutAll = new MenuItem("Cut All");
            cutAll.setOnAction(e -> cutAllHandler.accept(tangle));
            return new ContextMenu(cutAll);
        }

        private ContextMenu restoreContextMenu(String from, String to) {
            MenuItem restore = new MenuItem("Restore");
            restore.setOnAction(e -> restoreEdgeHandler.accept(from, to));
            return new ContextMenu(restore);
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
                + "Double-click a tangle row to open the tangle.";
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
