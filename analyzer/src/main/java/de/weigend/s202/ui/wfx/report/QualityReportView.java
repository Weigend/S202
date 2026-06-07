/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.ui.wfx.report;

import io.softwareecg.wfx.windowmanager.api.Position;
import io.softwareecg.wfx.windowmanager.api.View;
import io.softwareecg.wfx.windowmanager.api.ViewKind;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.web.WebView;

import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * WFX document view that displays the generated static quality report through
 * JavaFX WebKit. Export is intentionally a context action on the HTML view so
 * report generation and report publication stay separate user steps.
 */
public final class QualityReportView implements View {

    public static final String VIEW_ID_PREFIX = "s202-quality-report-";

    private final String viewId;
    private final String title;
    private final Path reportDirectory;
    private final Path htmlPath;
    private final Consumer<Path> exportHandler;
    private final BorderPane root = new BorderPane();
    private final WebView webView = new WebView();

    public QualityReportView(String viewId,
                             String title,
                             Path reportDirectory,
                             Path htmlPath,
                             Consumer<Path> exportHandler) {
        this.viewId = Objects.requireNonNull(viewId, "viewId");
        this.title = Objects.requireNonNull(title, "title");
        this.reportDirectory = Objects.requireNonNull(reportDirectory, "reportDirectory");
        this.htmlPath = Objects.requireNonNull(htmlPath, "htmlPath");
        this.exportHandler = Objects.requireNonNull(exportHandler, "exportHandler");

        root.getStyleClass().add("quality-report-view");
        configureContextMenu();
        webView.getEngine().load(htmlPath.toUri().toString());
        root.setCenter(webView);
    }

    public Path getReportDirectory() {
        return reportDirectory;
    }

    public Path getHtmlPath() {
        return htmlPath;
    }

    private void configureContextMenu() {
        webView.setContextMenuEnabled(false);

        ContextMenu menu = new ContextMenu();
        MenuItem exportItem = new MenuItem("Export Report...");
        exportItem.setOnAction(event -> exportHandler.accept(reportDirectory));
        menu.getItems().setAll(exportItem);

        webView.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            menu.show(webView, event.getScreenX(), event.getScreenY());
            event.consume();
        });
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
        return "Generated S202 quality report";
    }

    @Override
    public Position getDefaultPosition() {
        return Position.CENTER;
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
        return 1.0;
    }

    @Override
    public ViewKind getKind() {
        return ViewKind.DOCUMENT;
    }
}
