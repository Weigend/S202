package de.weigend.s202.analysis;

import de.weigend.s202.model.JavaPackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ArchitectureModelBuilderTest {

    private ArchitectureModelBuilder builder;
    private JavaPackage testPackage;

    @BeforeEach
    void setUp() {
        builder = new ArchitectureModelBuilder();
        testPackage = new JavaPackage("com.example");
    }

    @Test
    void testBuildModelSimple() {
        var model = builder.buildModel(testPackage, 3);

        assertNotNull(model);
        assertEquals("com.example", model.getFullName());
        assertEquals("example", model.getSimpleName());
    }

    @Test
    void testBuildModelNullThrows() {
        assertThrows(NullPointerException.class, () -> builder.buildModel(null, 3));
    }

    @Test
    void testBuildModelNegativeDepthThrows() {
        assertThrows(IllegalArgumentException.class, () -> builder.buildModel(testPackage, -1));
    }

    @Test
    void testNodeType() {
        assertEquals(0, ArchitectureModelBuilder.NodeType.PACKAGE.getOrder());
        assertEquals(1, ArchitectureModelBuilder.NodeType.CLASS.getOrder());
    }

    @Test
    void testArchitectureNodeCreation() {
        var node = new ArchitectureModelBuilder.ArchitectureNode(
            "com.example",
            "example",
            ArchitectureModelBuilder.NodeType.PACKAGE,
            true
        );

        assertEquals("com.example", node.getFullName());
        assertEquals("example", node.getSimpleName());
        assertEquals(ArchitectureModelBuilder.NodeType.PACKAGE, node.getType());
        assertTrue(node.isAutoExpanded());
    }

    @Test
    void testArchitectureNodeAddChild() {
        var parentNode = new ArchitectureModelBuilder.ArchitectureNode(
            "com.example",
            "example",
            ArchitectureModelBuilder.NodeType.PACKAGE,
            true
        );

        var childNode = new ArchitectureModelBuilder.ArchitectureNode(
            "com.example.MyClass",
            "MyClass",
            ArchitectureModelBuilder.NodeType.CLASS,
            false
        );

        parentNode.addChild(childNode);

        assertEquals(1, parentNode.getChildCount());
        assertTrue(parentNode.hasChildren());
        assertTrue(parentNode.getChildren().contains(childNode));
    }

    @Test
    void testArchitectureNodeAddChildNullThrows() {
        var node = new ArchitectureModelBuilder.ArchitectureNode(
            "com.example",
            "example",
            ArchitectureModelBuilder.NodeType.PACKAGE,
            true
        );

        assertThrows(NullPointerException.class, () -> node.addChild(null));
    }

    @Test
    void testArchitectureNodeDependencies() {
        var node = new ArchitectureModelBuilder.ArchitectureNode(
            "com.example",
            "example",
            ArchitectureModelBuilder.NodeType.PACKAGE,
            false
        );

        assertTrue(node.getDependencies().isEmpty());

        node.setDependencies(Set.of("com.other"));

        assertEquals(1, node.getDependencies().size());
        assertTrue(node.getDependencies().contains("com.other"));
    }

    @Test
    void testArchitectureNodeConstructorNullThrows() {
        assertThrows(NullPointerException.class, () -> new ArchitectureModelBuilder.ArchitectureNode(
            null, "example", ArchitectureModelBuilder.NodeType.PACKAGE, true));

        assertThrows(NullPointerException.class, () -> new ArchitectureModelBuilder.ArchitectureNode(
            "com.example", null, ArchitectureModelBuilder.NodeType.PACKAGE, true));

        assertThrows(NullPointerException.class, () -> new ArchitectureModelBuilder.ArchitectureNode(
            "com.example", "example", null, true));
    }
}
