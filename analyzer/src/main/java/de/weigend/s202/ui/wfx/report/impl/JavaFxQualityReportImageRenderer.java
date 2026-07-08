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
package de.weigend.s202.ui.wfx.report.impl;

import de.weigend.s202.domain.DependencyEdge;
import de.weigend.s202.report.quality.QualityReportImageRenderer;
import de.weigend.s202.report.quality.QualityReportInput;
import de.weigend.s202.report.quality.QualityReportModel;
import de.weigend.s202.ui.ArchitectureView;
import de.weigend.s202.ui.ArchitectureViewStyle;
import de.weigend.s202.ui.core.model.ArchitectureNode;
import de.weigend.s202.ui.core.model.ArchitectureNodeCloner;
import de.weigend.s202.ui.views.tangle.TangleFilter;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Creates report evidence images by rendering real {@link ArchitectureView}
 * instances off-screen and snapshotting them as PNG files.
 */
public final class JavaFxQualityReportImageRenderer implements QualityReportImageRenderer {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 820;
    private static final int MAX_SNAPSHOT_DIMENSION = 4096;

    private final ArchitectureNode sourceRoot;
    private final Set<DependencyEdge> cycleBreakEdges;
    private final Set<DependencyEdge> appliedCutEdges;

    public JavaFxQualityReportImageRenderer(ArchitectureNode sourceRoot,
                                            Set<DependencyEdge> cycleBreakEdges,
                                            Set<DependencyEdge> appliedCutEdges) {
        this.sourceRoot = sourceRoot;
        this.cycleBreakEdges = cycleBreakEdges == null ? Set.of() : Set.copyOf(cycleBreakEdges);
        this.appliedCutEdges = appliedCutEdges == null ? Set.of() : Set.copyOf(appliedCutEdges);
    }

    @Override
    public void renderImages(QualityReportModel model,
                             QualityReportInput input,
                             Path outputDirectory) throws IOException {
        if (!Platform.isFxApplicationThread()) {
            throw new IOException("JavaFX report screenshots must be rendered on the JavaFX application thread");
        }
        if (sourceRoot == null) {
            return;
        }
        for (RenderJob job : renderJobs(model, input, outputDirectory)) {
            job.render();
        }
    }

    public void renderImagesAsync(QualityReportModel model,
                                  QualityReportInput input,
                                  Path outputDirectory,
                                  BiConsumer<String, Double> progressSink,
                                  Runnable onComplete,
                                  Consumer<Throwable> onFailed) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> renderImagesAsync(model, input, outputDirectory,
                    progressSink, onComplete, onFailed));
            return;
        }
        if (sourceRoot == null) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        List<RenderJob> jobs = renderJobs(model, input, outputDirectory);
        if (jobs.isEmpty() && progressSink != null) {
            progressSink.accept("No report evidence images to render", 1.0);
        }
        Platform.runLater(() -> renderNext(jobs, 0, progressSink, onComplete, onFailed));
    }

    private void renderNext(List<RenderJob> jobs,
                            int index,
                            BiConsumer<String, Double> progressSink,
                            Runnable onComplete,
                            Consumer<Throwable> onFailed) {
        if (index >= jobs.size()) {
            if (onComplete != null) {
                onComplete.run();
            }
            return;
        }
        RenderJob job = jobs.get(index);
        if (progressSink != null) {
            progressSink.accept(
                    "Rendering report image " + (index + 1) + "/" + jobs.size() + ": " + job.label(),
                    (double) index / jobs.size());
        }
        try {
            job.render();
        } catch (Throwable t) {
            if (onFailed != null) {
                onFailed.accept(t);
            }
            return;
        }
        if (progressSink != null) {
            progressSink.accept(
                    "Rendered report image " + (index + 1) + "/" + jobs.size() + ": " + job.label(),
                    (double) (index + 1) / jobs.size());
        }
        Platform.runLater(() -> renderNext(jobs, index + 1, progressSink, onComplete, onFailed));
    }

    private List<RenderJob> renderJobs(QualityReportModel model,
                                       QualityReportInput input,
                                       Path outputDirectory) {
        List<RenderJob> jobs = new ArrayList<>();
        for (QualityReportModel.ViolationFinding finding : model.layeredViolations()) {
            if (isPng(finding.imagePath())) {
                jobs.add(new RenderJob(finding.id(),
                        () -> renderViolation(input, finding, ArchitectureViewStyle.LAYERED, outputDirectory)));
            }
        }
        for (QualityReportModel.CycleFinding finding : model.packageCycles()) {
            if (isPng(finding.imagePath())) {
                jobs.add(new RenderJob(finding.id(), () -> renderPackageCycle(input, finding, outputDirectory)));
            }
        }
        for (QualityReportModel.CycleFinding finding : model.classCycles()) {
            if (isPng(finding.imagePath())) {
                jobs.add(new RenderJob(finding.id(), () -> renderClassCycle(input, finding, outputDirectory)));
            }
        }
        QualityReportModel.ComponentFindings componentFindings = model.componentFindings();
        if (componentFindings != null && componentFindings.reportable()) {
            for (QualityReportModel.ViolationFinding finding : componentFindings.violations()) {
                if (isPng(finding.imagePath())) {
                    jobs.add(new RenderJob(finding.id(),
                            () -> renderViolation(input, finding, ArchitectureViewStyle.COMPONENT, outputDirectory)));
                }
            }
        }
        return jobs;
    }

    private void renderViolation(QualityReportInput input,
                                 QualityReportModel.ViolationFinding finding,
                                 ArchitectureViewStyle style,
                                 Path outputDirectory) throws IOException {
        if (!isPng(finding.imagePath())) {
            return;
        }
        Set<String> keepClasses = new LinkedHashSet<>();
        for (QualityReportModel.EdgeSample sample : finding.samples()) {
            keepClasses.add(sample.source());
            keepClasses.add(sample.target());
        }
        ArchitectureNode root = filterByClasses(sourceRoot, keepClasses);
        root = focusOnPackage(root, commonPackage(finding.sourceScope(), finding.targetScope()));
        if (root == null) {
            root = filterByPackages(sourceRoot, Set.of(finding.sourceScope(), finding.targetScope()));
            root = focusOnPackage(root, commonPackage(finding.sourceScope(), finding.targetScope()));
        }
        if (root == null) {
            root = ArchitectureNodeCloner.cloneTree(sourceRoot);
        }

        ArchitectureView view = configuredView(input, style, root);
        view.setShowWhatIfViolations(true);
        if (!keepClasses.isEmpty()) {
            view.selectByFullName(keepClasses.iterator().next());
        }
        snapshot(view, outputDirectory.resolve(finding.imagePath()));
    }

    private void renderPackageCycle(QualityReportInput input,
                                    QualityReportModel.CycleFinding finding,
                                    Path outputDirectory) throws IOException {
        if (!isPng(finding.imagePath())) {
            return;
        }
        ArchitectureNode root = filterByPackages(sourceRoot, Set.copyOf(finding.members()));
        if (root == null) {
            root = ArchitectureNodeCloner.cloneTree(sourceRoot);
        }
        ArchitectureView view = configuredView(input, ArchitectureViewStyle.LAYERED, root);
        view.setShowPackageScc(true);
        if (!finding.members().isEmpty()) {
            view.selectByFullName(finding.members().getFirst());
        }
        snapshot(view, outputDirectory.resolve(finding.imagePath()));
    }

    private void renderClassCycle(QualityReportInput input,
                                  QualityReportModel.CycleFinding finding,
                                  Path outputDirectory) throws IOException {
        if (!isPng(finding.imagePath())) {
            return;
        }
        Set<String> members = new TreeSet<>(finding.members());
        ArchitectureNode root = TangleFilter.filter(sourceRoot, members);
        if (root == null) {
            root = filterByClasses(sourceRoot, members);
        }
        if (root == null) {
            root = ArchitectureNodeCloner.cloneTree(sourceRoot);
        }
        ArchitectureView view = configuredView(input, ArchitectureViewStyle.LAYERED, root);
        List<DependencyEdge> edges = finding.samples().stream()
                .map(sample -> new DependencyEdge(sample.source(), sample.target()))
                .filter(edge -> members.contains(edge.from()) && members.contains(edge.to()))
                .distinct()
                .toList();
        view.setShowScc(true);
        view.setTangleVisualization(edges, null, null);
        if (!members.isEmpty()) {
            view.selectByFullName(members.iterator().next());
        }
        snapshot(view, outputDirectory.resolve(finding.imagePath()));
    }

    private ArchitectureView configuredView(QualityReportInput input,
                                            ArchitectureViewStyle style,
                                            ArchitectureNode root) {
        ArchitectureView view = new ArchitectureView();
        view.setViewStyle(style);
        view.setPackageDepth(12);
        view.setSkipTransparentTopLevelPackages(false);
        view.setArchitectureAnnotations(input.annotations());
        view.setRawDependencyModel(input.rawModel());
        view.setDomainModel(input.domainModel());
        view.setCycleBreakEdges(cycleBreakEdges);
        view.setAppliedTangleCutEdges(appliedCutEdges);
        view.setArchitectureRoot(root);
        view.setZoom(1.0);
        return view;
    }

    private void snapshot(ArchitectureView view, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        view.setMinSize(WIDTH, HEIGHT);
        view.setPrefSize(WIDTH, HEIGHT);
        view.setMaxSize(WIDTH, HEIGHT);
        Scene scene = new Scene(view, WIDTH, HEIGHT, Color.WHITE);
        var css = JavaFxQualityReportImageRenderer.class.getResource("/de/weigend/s202/ui/styles.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        view.resize(WIDTH, HEIGHT);
        view.prepareForSnapshot();
        Node snapshotNode = view.snapshotContentNode();
        Bounds bounds = view.snapshotContentBounds();
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.WHITE);
        WritableImage image;
        if (isUsable(bounds)) {
            double width = Math.ceil(bounds.getWidth());
            double height = Math.ceil(bounds.getHeight());
            double scale = Math.min(1.0, MAX_SNAPSHOT_DIMENSION / Math.max(width, height));
            int imageWidth = Math.max(1, (int) Math.ceil(width * scale));
            int imageHeight = Math.max(1, (int) Math.ceil(height * scale));
            parameters.setViewport(new Rectangle2D(bounds.getMinX(), bounds.getMinY(), width, height));
            parameters.setTransform(Transform.scale(scale, scale));
            image = snapshotNode.snapshot(parameters, new WritableImage(imageWidth, imageHeight));
        } else {
            image = view.snapshot(parameters, new WritableImage(WIDTH, HEIGHT));
        }
        ImageIO.write(toBufferedImage(image), "png", path.toFile());
    }

    private static boolean isUsable(Bounds bounds) {
        return bounds != null
                && Double.isFinite(bounds.getMinX())
                && Double.isFinite(bounds.getMinY())
                && Double.isFinite(bounds.getWidth())
                && Double.isFinite(bounds.getHeight())
                && bounds.getWidth() > 1.0
                && bounds.getHeight() > 1.0;
    }

    private static BufferedImage toBufferedImage(WritableImage image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return out;
    }

    private static ArchitectureNode filterByClasses(ArchitectureNode node, Set<String> keepClasses) {
        if (keepClasses == null || keepClasses.isEmpty()) {
            return null;
        }
        return filter(node, className -> keepClasses.contains(className));
    }

    private static ArchitectureNode filterByPackages(ArchitectureNode node, Set<String> packageScopes) {
        Set<String> scopes = packageScopes == null ? Set.of() : packageScopes.stream()
                .filter(scope -> scope != null && !scope.isBlank())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        if (scopes.isEmpty()) {
            return null;
        }
        return filter(node, className -> scopes.stream().anyMatch(scope -> className.startsWith(scope + ".")));
    }

    private static ArchitectureNode focusOnPackage(ArchitectureNode root, String packageName) {
        if (root == null || packageName == null || packageName.isBlank()) {
            return root;
        }
        ArchitectureNode scope = findPackage(root, packageName);
        if (scope == null) {
            return root;
        }
        ArchitectureNode focusedRoot = new ArchitectureNode(
                "root",
                "root",
                ArchitectureNode.NodeType.PACKAGE,
                true,
                0);
        focusedRoot.addChild(scope);
        return focusedRoot;
    }

    private static ArchitectureNode findPackage(ArchitectureNode node, String packageName) {
        if (node.getType() == ArchitectureNode.NodeType.PACKAGE
                && packageName.equals(node.getFullName())) {
            return node;
        }
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode found = findPackage(child, packageName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static String commonPackage(String left, String right) {
        if (left == null || left.isBlank() || right == null || right.isBlank()) {
            return "";
        }
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int max = Math.min(leftParts.length, rightParts.length);
        int count = 0;
        while (count < max && leftParts[count].equals(rightParts[count])) {
            count++;
        }
        if (count == 0) {
            return "";
        }
        return String.join(".", java.util.Arrays.copyOf(leftParts, count));
    }

    private static ArchitectureNode filter(ArchitectureNode node,
                                           java.util.function.Predicate<String> keepClass) {
        if (node.getType() == ArchitectureNode.NodeType.CLASS) {
            return keepClass.test(node.getFullName()) ? cloneShallow(node) : null;
        }
        ArchitectureNode copy = cloneShallow(node);
        for (ArchitectureNode child : node.getChildren()) {
            ArchitectureNode kept = filter(child, keepClass);
            if (kept != null) {
                copy.addChild(kept);
            }
        }
        if (copy.getChildren().isEmpty() && !node.getFullName().isEmpty()) {
            return null;
        }
        return copy;
    }

    private static ArchitectureNode cloneShallow(ArchitectureNode src) {
        ArchitectureNode copy = new ArchitectureNode(
                src.getFullName(),
                src.getSimpleName(),
                src.getType(),
                src.isAutoExpanded(),
                src.getLevel(),
                src.isInterfaceType());
        copy.setArchitectureLevel(src.getArchitectureLevel());
        copy.setHorizontalLayoutOrder(src.getHorizontalLayoutOrder());
        copy.setDependencies(src.getDependencies());
        copy.setDependents(src.getDependents());
        return copy;
    }

    private static boolean isPng(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".png");
    }

    @FunctionalInterface
    private interface ImageRenderAction {
        void render() throws IOException;
    }

    private record RenderJob(String label, ImageRenderAction action) {
        void render() throws IOException {
            action.render();
        }
    }
}
