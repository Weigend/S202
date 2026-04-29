package de.weigend.s202.ui.wfx;

import de.weigend.s202.ui.ArchitectureView;
import io.softwareecg.wfx.windowmtg.api.Position;
import io.softwareecg.wfx.windowmtg.api.View;
import javafx.scene.Parent;

import java.net.URL;

/**
 * WFX {@link View} wrapper around the programmatic {@link ArchitectureView}.
 * Lets WFX dock the existing UI without forcing it into an FXML round-trip.
 */
public class ArchitectureWfxView implements View {

    public static final String VIEW_ID_PREFIX = "s202-architecture-";

    private final String viewId;
    private final String title;
    private final ArchitectureView architectureView;

    public ArchitectureWfxView(String viewId, String title, ArchitectureView architectureView) {
        this.viewId = viewId;
        this.title = title;
        this.architectureView = architectureView;
    }

    public ArchitectureView getArchitectureView() {
        return architectureView;
    }

    @Override
    public String getViewId() {
        return viewId;
    }

    @Override
    public String getTitle() {
        return title;
    }

    @Override
    public String getToolTipInfo() {
        return "Java bytecode architecture view";
    }

    @Override
    public Position getDefaultPosition() {
        return Position.CENTER;
    }

    @Override
    public Parent getRootNode() {
        return architectureView;
    }

    @Override
    public URL getViewImagePath() {
        return null;
    }

    @Override
    public double getViewAreaSize() {
        return 1.0;
    }
}
