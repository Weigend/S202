/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.weigend.s202.ui.core.platform;

import de.weigend.s202.ui.core.canvas.ArchitectureCanvas;
import jakarta.inject.Singleton;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
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

/** Exports the complete architecture graphic, not only its visible viewport. */
@Singleton
public final class ArchitecturePngExporter {

    static final int MAX_DIMENSION = 16_384;
    static final long MAX_PIXELS = 64_000_000L;

    public void export(ArchitectureCanvas canvas, Path target) throws IOException {
        if (!Platform.isFxApplicationThread()) {
            throw new IOException("Architecture graphics must be exported on the JavaFX application thread");
        }
        if (canvas == null) {
            throw new IOException("No architecture graphic is active");
        }
        if (target == null) {
            throw new IOException("No export file was selected");
        }

        canvas.prepareForSnapshot();
        Node content = canvas.snapshotContentNode();
        Bounds bounds = canvas.snapshotContentBounds();
        if (!usable(bounds)) {
            throw new IOException("The current architecture graphic has no exportable content");
        }

        double width = Math.ceil(bounds.getWidth());
        double height = Math.ceil(bounds.getHeight());
        double scale = exportScale(width, height);
        int imageWidth = Math.max(1, (int) Math.ceil(width * scale));
        int imageHeight = Math.max(1, (int) Math.ceil(height * scale));

        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.WHITE);
        parameters.setViewport(new Rectangle2D(bounds.getMinX(), bounds.getMinY(), width, height));
        parameters.setTransform(Transform.scale(scale, scale));

        write(content.snapshot(parameters, new WritableImage(imageWidth, imageHeight)), target);
    }

    /** Writes an image a view took of itself. */
    public void write(WritableImage image, Path target) throws IOException {
        if (target == null) {
            throw new IOException("No export file was selected");
        }
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!ImageIO.write(toBufferedImage(image), "png", target.toFile())) {
            throw new IOException("No PNG writer is available");
        }
    }

    static double exportScale(double width, double height) {
        if (!(width > 0.0) || !(height > 0.0)
                || !Double.isFinite(width) || !Double.isFinite(height)) {
            throw new IllegalArgumentException("Image dimensions must be finite and positive");
        }
        double dimensionScale = Math.min(1.0, MAX_DIMENSION / Math.max(width, height));
        double pixelScale = Math.min(1.0, Math.sqrt(MAX_PIXELS / (width * height)));
        return Math.min(dimensionScale, pixelScale);
    }

    private static boolean usable(Bounds bounds) {
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
        BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                output.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        return output;
    }
}
