package de.weigend.s202.ui.shell;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchitectureImageExportControllerTest {

    @Test
    void addsPngExtensionWhenMissing() {
        assertEquals(new File("diagram.png"),
                ArchitectureImageExportController.withPngExtension(new File("diagram")));
    }

    @Test
    void keepsPngExtensionCaseInsensitively() {
        assertEquals(new File("diagram.PNG"),
                ArchitectureImageExportController.withPngExtension(new File("diagram.PNG")));
    }

    @Test
    void theViewIsPartOfTheFileName() {
        assertEquals("app.jar-Component-View.png",
                ArchitectureImageExportController.fileName("app.jar · Component View", ".png"));
        assertEquals("architecture.png", ArchitectureImageExportController.fileName(null, ".png"));
    }
}
