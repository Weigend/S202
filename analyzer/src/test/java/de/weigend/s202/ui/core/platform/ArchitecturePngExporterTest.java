package de.weigend.s202.ui.core.platform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArchitecturePngExporterTest {

    @Test
    void preservesNaturalSizeWhenItFits() {
        assertEquals(1.0, ArchitecturePngExporter.exportScale(2_000, 1_000));
    }

    @Test
    void scalesTheWholeGraphicToDimensionLimit() {
        assertEquals(0.5, ArchitecturePngExporter.exportScale(32_768, 1_000));
    }

    @Test
    void scalesTheWholeGraphicToPixelLimit() {
        assertEquals(0.8, ArchitecturePngExporter.exportScale(10_000, 10_000), 0.000_001);
    }

    @Test
    void rejectsInvalidDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> ArchitecturePngExporter.exportScale(Double.NaN, 100));
    }
}
