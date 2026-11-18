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
package de.weigend.s202.ui.features.report;

import de.weigend.s202.ui.core.events.MenuRequestEvent;
import de.weigend.s202.ui.core.platform.ArchitectureViewManager;
import de.weigend.s202.ui.core.platform.ProgressPublisher;
import de.weigend.s202.ui.core.platform.RecentDirectories;
import io.softwareecg.wfx.lookup.api.Lookup;
import io.softwareecg.wfx.platform.api.EventBus;
import io.softwareecg.wfx.platform.api.Module;
import io.softwareecg.wfx.windowmanager.api.ApplicationWindow;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.EventObject;

/**
 * WFX-Modul der Quality-Report-Fachkomponente: verdrahtet den
 * {@link MenuRequestEvent.ExportQualityReport} auf den
 * {@link QualityReportController}. Selbstregistrierend (@Singleton) — aus
 * S202Module extrahiert, damit die Shell den Report nicht mehr kennt.
 */
@Singleton
public final class QualityReportModule implements Module {

    private final ApplicationWindow applicationWindow;
    private final QualityReportController controller;

    @Inject
    QualityReportModule(ApplicationWindow applicationWindow,
                        ArchitectureViewManager viewManager,
                        ProgressPublisher progressPublisher,
                        RecentDirectories recentDirs) {
        this.applicationWindow = applicationWindow;
        this.controller = new QualityReportController(
                viewManager, progressPublisher, recentDirs,
                () -> applicationWindow.getStage(), this::appVersion);
    }

    @Override
    public String getName() {
        return "Quality Report";
    }

    @Override
    public void preload() {
        // nichts vorzubereiten
    }

    @Override
    @SuppressWarnings("unchecked")
    public void start() {
        EventBus<EventObject> bus = Lookup.lookup(EventBus.class);
        bus.subscribe(MenuRequestEvent.ExportQualityReport.class, ev -> {
            controller.exportQualityReport();
            return true;
        });
    }

    @Override
    public void stop() {
        // nichts freizugeben
    }

    private String appVersion() {
        String version = getClass().getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "dev" : version;
    }
}
