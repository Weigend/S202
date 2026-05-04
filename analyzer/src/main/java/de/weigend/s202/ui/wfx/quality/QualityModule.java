package de.weigend.s202.ui.wfx.quality;

import de.weigend.s202.analysis.quality.QualityMetrics;
import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.wfx.ArchitectureWfxView;
import de.weigend.s202.ui.wfx.outline.OutlineExplorerView;
import io.softwareecg.wfx.lookup.Lookup;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.platform.api.exceptions.PlatformException;
import io.softwareecg.wfx.windowmtg.api.View;
import io.softwareecg.wfx.windowmtg.api.WindowManager;
import jakarta.annotation.Priority;
import jakarta.inject.Singleton;
import javafx.beans.value.ChangeListener;

/**
 * WFX module providing the Quality view — a 2D fat-vs-tangled plot rendered
 * below the outline explorer in the LEFT dock area.
 * <p>
 * Tracks {@link WindowManager#focusedViewProperty()}: when an
 * {@link ArchitectureWfxView} gains focus, the plot mirrors that view's
 * metrics. When the user selects a package in the chart, the plot scopes its
 * metrics to that package's classes; selecting a class or clearing falls back
 * to JAR-level metrics.
 */
@Singleton
@Priority(20)
public class QualityModule implements Module {

    private QualityView qualityView;

    private ArchitectureView boundView;
    private ChangeListener<QualityMetrics> metricsListener;
    private ChangeListener<String> selectionListener;

    @Override
    public String getName() {
        return "Quality";
    }

    @Override
    public void preload() throws PlatformException {
        waitForDemoPreloader();
        qualityView = new QualityView();
    }

    private void waitForDemoPreloader() throws PlatformException {
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PlatformException("Interrupted while delaying Quality preload", e);
        }
    }

    @Override
    public void start() {
        WindowManager wm = Lookup.lookup(WindowManager.class);

        // Stack under the outline (vertical split inside the LEFT region) when
        // it's available; fall back to a plain LEFT registration otherwise.
        View outline = wm.findView(OutlineExplorerView.VIEW_ID);
        if (outline != null) {
            wm.register(qualityView, outline);
        } else {
            wm.register(qualityView);
        }

        wm.focusedViewProperty().addListener((obs, was, isNow) -> rebindToFocusedView());
        rebindToFocusedView();
    }

    @Override
    public void stop() {
        unbind();
    }

    private void rebindToFocusedView() {
        ArchitectureWfxView focused = focusedArchitectureView();
        ArchitectureView newBound = focused == null ? null : focused.getArchitectureView();

        // Same fix as OutlineExplorerModule: focus moving onto a side panel
        // shouldn't trigger a re-bind to the same chart.
        if (newBound == boundView) {
            return;
        }

        unbind();

        if (newBound == null) {
            qualityView.setMetrics(null, null);
            return;
        }

        boundView = newBound;
        applyCurrentScope();

        metricsListener = (obs, was, isNow) -> applyCurrentScope();
        selectionListener = (obs, was, isNow) -> applyCurrentScope();
        newBound.qualityMetricsProperty().addListener(metricsListener);
        newBound.selectedFullNameProperty().addListener(selectionListener);
    }

    /**
     * Pick the right scope based on the current selection and push it to the
     * view. Package selection ⇒ recompute on the package's class subset.
     * Class selection or no selection ⇒ JAR-level metrics from the chart.
     */
    private void applyCurrentScope() {
        if (boundView == null) {
            return;
        }
        DomainModel model = boundView.getDomainModel();
        String selected = boundView.getSelectedFullName();

        if (model != null && selected != null && model.getClass(selected) == null) {
            // Selection is not a known class → treat as a package scope.
            QualityMetrics scoped = QualityMetrics.computeForPackage(model, selected);
            qualityView.setMetrics(scoped, selected);
        } else {
            qualityView.setMetrics(boundView.getQualityMetrics(), null);
        }
    }

    private void unbind() {
        if (boundView != null) {
            if (metricsListener != null) {
                boundView.qualityMetricsProperty().removeListener(metricsListener);
            }
            if (selectionListener != null) {
                boundView.selectedFullNameProperty().removeListener(selectionListener);
            }
        }
        boundView = null;
        metricsListener = null;
        selectionListener = null;
    }

    private ArchitectureWfxView focusedArchitectureView() {
        WindowManager wm = Lookup.lookup(WindowManager.class);
        ArchitectureWfxView focused = wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .filter(v -> v == wm.getFocusedView())
                .findFirst()
                .orElse(null);
        if (focused != null) {
            return focused;
        }

        ArchitectureWfxView current = wrapperFor(boundView);
        if (current != null) {
            return current;
        }

        return wm.getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .findFirst()
                .orElse(null);
    }

    private ArchitectureWfxView wrapperFor(ArchitectureView view) {
        if (view == null) {
            return null;
        }
        return Lookup.lookup(WindowManager.class).getRegisteredViews().stream()
                .filter(ArchitectureWfxView.class::isInstance)
                .map(ArchitectureWfxView.class::cast)
                .filter(wrapper -> wrapper.getArchitectureView() == view)
                .findFirst()
                .orElse(null);
    }
}
