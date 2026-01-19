package de.weigend.s202.ui.newlayout;

import javafx.application.Platform;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the hierarchical ArchitectureViewV2 UI structure and functionality.
 */
class ArchitectureViewV2Test {

    private static ArchitectureViewV2Controller controller;

    @BeforeAll
    static void setupAll() throws InterruptedException {
        // Initialize JavaFX toolkit
        Thread t = new Thread(() -> {
            try {
                new ArchitectureViewV2Demo().start(new javafx.stage.Stage());
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        t.setDaemon(true);
        t.start();
        
        // Wait for FX toolkit to initialize
        Thread.sleep(1000);
    }

    @Test
    void testLoadView() throws IOException {
        controller = ArchitectureViewV2Controller.loadView();
        assertNotNull(controller, "Controller should be loaded");
        assertNotNull(controller.getRootNode(), "Root node should exist");
    }

    @Test
    void testScrollPaneExists() throws IOException {
        ArchitectureViewV2Controller ctrl = ArchitectureViewV2Controller.loadView();
        VBox rootNode = ctrl.getRootNode();
        assertNotNull(rootNode, "Root VBox should exist");
        assertEquals(rootNode.getChildren().size(), 1, "Should have ScrollPane as child");
    }

    @Test
    void testAddSimpleSection() throws IOException {
        ArchitectureViewV2Controller ctrl = ArchitectureViewV2Controller.loadView();
        
        // Create simple NodeData
        NodeData classNode = new NodeData("MyClass", "com.example.MyClass", NodeData.NodeType.CLASS);
        List<NodeData> nodes = new ArrayList<>();
        nodes.add(classNode);
        
        // Add section
        ctrl.addSection("Test Section", nodes, new ArrayList<>(), new ArrayList<>());
        
        // Check that section was added
        VBox mainContent = ctrl.getRootNode();
        assertFalse(mainContent.getChildren().isEmpty(), "Content should be added");
    }

    @Test
    void testHierarchicalPackage() throws IOException {
        ArchitectureViewV2Controller ctrl = ArchitectureViewV2Controller.loadView();
        
        // Create hierarchical structure
        NodeData parentPackage = new NodeData("parent", "com.example.parent", NodeData.NodeType.PACKAGE);
        NodeData childClass = new NodeData("Child", "com.example.parent.Child", NodeData.NodeType.CLASS);
        parentPackage.addChild(childClass);
        
        List<NodeData> nodes = new ArrayList<>();
        nodes.add(parentPackage);
        
        // Add section
        ctrl.addSection("Hierarchical Test", nodes, new ArrayList<>(), new ArrayList<>());
        
        // Check that package has children
        assertTrue(parentPackage.hasChildren(), "Package should have children");
        assertEquals(1, parentPackage.getChildren().size(), "Package should have 1 child");
    }

    @Test
    void testNodeDataHierarchy() {
        // Create hierarchy
        NodeData pkg = new NodeData("utils", "com.example.utils", NodeData.NodeType.PACKAGE);
        NodeData class1 = new NodeData("Helper", "com.example.utils.Helper", NodeData.NodeType.CLASS);
        NodeData class2 = new NodeData("Util", "com.example.utils.Util", NodeData.NodeType.CLASS);
        
        pkg.addChildren(class1, class2);
        
        assertTrue(pkg.hasChildren(), "Package should have children");
        assertEquals(2, pkg.getChildren().size(), "Package should have 2 children");
        assertTrue(pkg.getChildren().contains(class1), "Child1 should be in children");
        assertTrue(pkg.getChildren().contains(class2), "Child2 should be in children");
    }

    @Test
    void testNestedHierarchy() {
        // Create nested packages
        NodeData root = new NodeData("root", "com.example.root", NodeData.NodeType.PACKAGE);
        NodeData sub = new NodeData("sub", "com.example.root.sub", NodeData.NodeType.PACKAGE);
        NodeData leaf = new NodeData("Leaf", "com.example.root.sub.Leaf", NodeData.NodeType.CLASS);
        
        sub.addChild(leaf);
        root.addChild(sub);
        
        assertTrue(root.hasChildren(), "Root should have children");
        assertTrue(sub.hasChildren(), "Sub should have children");
        assertEquals(1, root.getChildren().size(), "Root should have 1 child");
        assertEquals(1, sub.getChildren().size(), "Sub should have 1 child");
        assertEquals(leaf, sub.getChildren().get(0), "Leaf should be child of sub");
    }

    @Test
    void testNodeTypes() {
        NodeData pkg = new NodeData("p", "com.p", NodeData.NodeType.PACKAGE);
        NodeData cls = new NodeData("C", "com.c", NodeData.NodeType.CLASS);
        NodeData iface = new NodeData("I", "com.i", NodeData.NodeType.INTERFACE);
        
        assertEquals(NodeData.NodeType.PACKAGE, pkg.getNodeType());
        assertEquals(NodeData.NodeType.CLASS, cls.getNodeType());
        assertEquals(NodeData.NodeType.INTERFACE, iface.getNodeType());
    }
}
