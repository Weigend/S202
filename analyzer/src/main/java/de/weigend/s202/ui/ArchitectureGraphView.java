package de.weigend.s202.ui;

import de.weigend.s202.analysis.ArchitectureModelBuilder.ArchitectureNode;
import de.weigend.s202.analysis.ArchitectureModelBuilder.NodeType;
import de.weigend.s202.analysis.scc.EdgeClassification.ClassifiedEdge;
import de.weigend.s202.analysis.scc.EdgeClassification.EdgeType;
import javafx.geometry.Bounds;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import java.util.*;

/**
 * Visualizes package hierarchy as boxes in top-down dependency order.
 */
public class ArchitectureGraphView extends Canvas {
    private static final double PADDING = 20;
    private static final double BOX_WIDTH = 200;
    private static final double BOX_HEIGHT = 60;
    private static final double VERTICAL_GAP = 80;
    private static final double HORIZONTAL_GAP = 30;

    private ArchitectureNode rootNode;
    private List<PackageBox> packageBoxes;
    private Map<String, PackageBox> boxByPackageName;
    private Map<String, Boolean> expandedState;
    private Set<String> projectPackageNames;
    private List<ClassifiedEdge> classifiedEdges;
    private double contentHeight;
    private double contentWidth;

    public ArchitectureGraphView() {
        this.packageBoxes = new ArrayList<>();
        this.boxByPackageName = new HashMap<>();
        this.expandedState = new HashMap<>();
        this.projectPackageNames = new HashSet<>();
        this.classifiedEdges = new ArrayList<>();
        
        // Bind canvas size to parent
        widthProperty().addListener((obs, oldVal, newVal) -> redraw());
        heightProperty().addListener((obs, oldVal, newVal) -> redraw());
        
        // Click handler for expand/collapse
        setOnMouseClicked(event -> handleCanvasClick(event.getX(), event.getY()));
    }

    /**
     * Sets the root architecture node and triggers visualization.
     */
    public void setArchitectureRoot(ArchitectureNode rootNode) {
        setArchitectureRoot(rootNode, new ArrayList<>());
    }

    /**
     * Sets the root architecture node with classified edges for violation visualization.
     */
    public void setArchitectureRoot(ArchitectureNode rootNode, List<ClassifiedEdge> classifiedEdges) {
        this.rootNode = Objects.requireNonNull(rootNode, "rootNode cannot be null");
        this.classifiedEdges = classifiedEdges != null ? classifiedEdges : new ArrayList<>();
        this.packageBoxes.clear();
        this.boxByPackageName.clear();
        this.expandedState.clear();
        this.projectPackageNames.clear();
        
        // Collect all project package names for dependency filtering
        collectProjectPackageNames(rootNode);
        
        System.out.println("DEBUG: setArchitectureRoot called with rootNode=" + rootNode.getFullName() + 
            " (type=" + rootNode.getType() + "), children=" + rootNode.getChildren().size());
        for (ArchitectureNode child : rootNode.getChildren()) {
            System.out.println("  Child: " + child.getFullName() + " type=" + child.getType());
        }
        
        // Initialize all top-level packages as expanded
        for (ArchitectureNode child : rootNode.getChildren()) {
            if (child.getType() == NodeType.PACKAGE) {
                expandedState.put(child.getFullName(), true);
            }
        }
        
        // Extract packages in dependency order
        extractPackages(rootNode);
        
        System.out.println("DEBUG: Extracted " + packageBoxes.size() + " packages");
        System.out.println("DEBUG: Project packages: " + projectPackageNames);
        System.out.println("DEBUG: Canvas size: " + getWidth() + "x" + getHeight());
        System.out.println("DEBUG: Classified edges: " + classifiedEdges.size());
        
        // Redraw (layout is done during drawing)
        redraw();
    }
    
    /**
     * Recursively collect all package names that belong to this project.
     */
    private void collectProjectPackageNames(ArchitectureNode node) {
        if (node.getType() == NodeType.PACKAGE) {
            projectPackageNames.add(node.getFullName());
        }
        for (ArchitectureNode child : node.getChildren()) {
            collectProjectPackageNames(child);
        }
    }

    /**
     * Extracts all packages recursively, starting from root.
     * This creates a hierarchical tree structure.
     */
    private void extractPackages(ArchitectureNode node) {
        if (node.getType() == NodeType.PACKAGE) {
            PackageBox box = new PackageBox(node);
            packageBoxes.add(box);
            boxByPackageName.put(node.getFullName(), box);
            System.out.println("DEBUG: Added package: " + node.getFullName() + 
                " with " + node.getChildren().size() + " children");
        }
        
        // Recursively extract all child packages
        for (ArchitectureNode child : node.getChildren()) {
            extractPackages(child);
        }
    }

    /**
     * Calculates layout positions for packages in top-down dependency order.
     * This is a TWO-PASS algorithm: First pass calculates heights, second pass positions.
     * Only visible (non-collapsed) packages are positioned.
     */
    /**
     * Sorts packages by dependency hierarchy (packages with more external dependencies on top).
     */
    private List<PackageBox> sortPackagesByDependencies(List<PackageBox> boxes) {
        List<PackageBox> sorted = new ArrayList<>(boxes);
        
        sorted.sort((b1, b2) -> {
            // Count EXTERNAL dependencies (not in this project)
            int deps1 = countExternalDependencies(b1.node);
            int deps2 = countExternalDependencies(b2.node);
            
            // Packages with MORE external dependencies come FIRST (top of diagram)
            return Integer.compare(deps2, deps1);
        });
        
        return sorted;
    }
    
    /**
     * Counts only EXTERNAL dependencies (those not in projectPackageNames).
     */
    private int countExternalDependencies(ArchitectureNode node) {
        Set<String> deps = node.getDependencies();
        if (deps == null) return 0;
        
        return (int) deps.stream()
            .filter(dep -> !projectPackageNames.contains(dep))
            .count();
    }

    /**
     * Redraws the entire graph.
     */
    private void redraw() {
        GraphicsContext gc = getGraphicsContext2D();
        
        // Clear canvas
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, getWidth(), getHeight());
        
        if (rootNode == null || rootNode.getChildren().isEmpty()) {
            drawEmptyMessage(gc);
            return;
        }
        
        // First, draw dependency lines (so they appear behind boxes)
        drawDependencyLines(gc);
        
        // Then draw violation lines (red dashed lines)
        drawViolationLines(gc);
        
        // Finally, draw package boxes on top
        double[] yPos = {PADDING};  // Use array to pass by reference in recursive call
        for (ArchitectureNode child : rootNode.getChildren()) {
            if (child.getType() == NodeType.PACKAGE) {
                yPos[0] = drawPackageBoxRecursive(gc, child, PADDING, yPos[0], 0);
            }
        }
        
        contentHeight = yPos[0] + PADDING;
        contentWidth = getWidth();
    }
    
    /**
     * Recursively draws a package box and its sub-packages.
     * Returns the Y position after drawing this box and its children.
     */
    private double drawPackageBoxRecursive(GraphicsContext gc, ArchitectureNode node, 
                                           double xPos, double yPos, int depth) {
        boolean isExpanded = expandedState.getOrDefault(node.getFullName(), true);
        
        // Calculate width based on depth (nested indentation)
        double boxWidth = BOX_WIDTH - (depth * 20);
        
        // Draw header with expand/collapse toggle
        double headerHeight = 40;
        drawBoxHeader(gc, node, xPos, yPos, boxWidth, headerHeight, isExpanded);
        
        double currentY = yPos + headerHeight;
        
        if (isExpanded) {
            // Draw sub-packages recursively inside this box
            List<ArchitectureNode> subPackages = new ArrayList<>();
            for (ArchitectureNode child : node.getChildren()) {
                if (child.getType() == NodeType.PACKAGE) {
                    subPackages.add(child);
                }
            }
            
            // Add padding inside the box
            currentY += 10;
            
            for (ArchitectureNode subPkg : subPackages) {
                currentY = drawPackageBoxRecursive(gc, subPkg, xPos + 20, currentY, depth + 1);
            }
            
            // Add padding below content
            currentY += 10;
        }
        
        // Draw box border around entire content
        double boxHeight = currentY - yPos;
        gc.setStroke(Color.web("#0066CC"));
        gc.setLineWidth(2);
        gc.strokeRect(xPos, yPos, boxWidth, boxHeight);
        gc.setFill(Color.web("#E8F4F8"));
        gc.fillRect(xPos, yPos, boxWidth, boxHeight);
        
        // Store box position for click detection
        PackageBox box = boxByPackageName.get(node.getFullName());
        if (box != null) {
            box.x = xPos;
            box.y = yPos;
            box.height = boxHeight;
            box.width = boxWidth;
        }
        
        return currentY + VERTICAL_GAP;
    }
    
    /**
     * Draws the header of a package box.
     */
    private void drawBoxHeader(GraphicsContext gc, ArchitectureNode node, 
                               double xPos, double yPos, double boxWidth, 
                               double headerHeight, boolean isExpanded) {
        // Header background
        gc.setFill(Color.web("#E3F2FD"));
        gc.fillRect(xPos, yPos, boxWidth, headerHeight);
        
        // Expand/Collapse toggle
        String toggleText = node.hasChildren() ? (isExpanded ? "▼ " : "▶ ") : "  ";
        gc.setFont(Font.font("Monospace", 12));
        gc.setFill(Color.web("#0066CC"));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText(toggleText + node.getSimpleName(), xPos + 8, yPos + 15);
        
        // External dependency count
        int depCount = countExternalDependencies(node);
        gc.setFont(Font.font("Monospace", 9));
        gc.setFill(Color.web("#666666"));
        gc.setTextAlign(TextAlignment.LEFT);
        gc.fillText("(" + depCount + " ext.deps)", xPos + 8, yPos + 30);
    }

    /**
     * Draws a single package box with sub-packages and classes.
     */
    /**
     * Draws dependency lines between packages.
     */
    private void drawDependencyLines(GraphicsContext gc) {
        gc.setStroke(Color.web("#CCCCCC"));
        gc.setLineWidth(1);
        
        for (PackageBox sourceBox : packageBoxes) {
            for (String depName : sourceBox.node.getDependencies()) {
                PackageBox targetBox = findBoxByPackageName(depName);
                if (targetBox != null && targetBox != sourceBox) {
                    drawArrow(gc, sourceBox, targetBox);
                }
            }
        }
    }

    /**
     * Draws an arrow from source to target package.
     */
    private void drawArrow(GraphicsContext gc, PackageBox source, PackageBox target) {
        // Start point: center bottom of source box
        double x1 = source.x + BOX_WIDTH / 2;
        double y1 = source.y + BOX_HEIGHT;
        
        // End point: center top of target box
        double x2 = target.x + BOX_WIDTH / 2;
        double y2 = target.y;
        
        // Draw line (skip if target is not below source for clarity)
        if (y2 > y1) {
            gc.setStroke(Color.web("#AAAAAA"));
            gc.setLineWidth(1);
            
            // Curved line
            double controlY = (y1 + y2) / 2;
            gc.strokeLine(x1, y1, x1, controlY);
            gc.strokeLine(x1, controlY, x2, controlY);
            gc.strokeLine(x2, controlY, x2, y2);
            
            // Arrow head
            drawArrowHead(gc, x2, y2);
        }
    }

    /**
     * Draws a small arrow head.
     */
    private void drawArrowHead(GraphicsContext gc, double x, double y) {
        double arrowSize = 8;
        gc.setFill(Color.web("#AAAAAA"));
        gc.fillPolygon(
            new double[]{x, x - arrowSize / 2, x + arrowSize / 2},
            new double[]{y, y + arrowSize, y + arrowSize},
            3
        );
    }

    /**
     * Draws violation lines (red dashed lines for upward dependencies).
     */
    private void drawViolationLines(GraphicsContext gc) {
        // Filter for VIOLATION edges only
        for (ClassifiedEdge edge : classifiedEdges) {
            if (edge.type == EdgeType.VIOLATION) {
                PackageBox sourceBox = findBoxByPackageName(edge.from);
                PackageBox targetBox = findBoxByPackageName(edge.to);
                
                if (sourceBox != null && targetBox != null && sourceBox != targetBox) {
                    drawViolationArrow(gc, sourceBox, targetBox);
                }
            }
        }
    }

    /**
     * Draws a red dashed arrow for a violation (upward dependency).
     */
    private void drawViolationArrow(GraphicsContext gc, PackageBox source, PackageBox target) {
        // Start point: right side of source box
        double x1 = source.x + source.width;
        double y1 = source.y + source.height / 2;
        
        // End point: right side of target box  
        double x2 = target.x + target.width;
        double y2 = target.y + target.height / 2;
        
        // Set red color and dashed stroke
        gc.setStroke(Color.web("#FF0000"));  // Red
        gc.setLineWidth(2);
        gc.setLineDashes(5, 5);  // Dashed line pattern
        
        // Curved line going around the right side
        double controlX = Math.max(x1, x2) + 50;
        
        // Draw the curved violation line
        gc.beginPath();
        gc.moveTo(x1, y1);
        gc.bezierCurveTo(
            controlX, y1,      // Control point 1
            controlX, y2,      // Control point 2
            x2, y2             // End point
        );
        gc.stroke();
        
        // Draw arrow head (red)
        double arrowSize = 10;
        gc.setFill(Color.web("#FF0000"));
        
        // Calculate direction for arrow head
        double angle = Math.atan2(y2 - y1, x2 - x1);
        double arrowX = x2 - arrowSize * Math.cos(angle);
        double arrowY = y2 - arrowSize * Math.sin(angle);
        
        // Draw arrowhead as a filled polygon
        double[] xs = {
            x2,
            arrowX - arrowSize * Math.sin(angle) / 2,
            arrowX + arrowSize * Math.sin(angle) / 2
        };
        double[] ys = {
            y2,
            arrowY + arrowSize * Math.cos(angle) / 2,
            arrowY - arrowSize * Math.cos(angle) / 2
        };
        gc.fillPolygon(xs, ys, 3);
        
        // Reset line dashes to solid
        gc.setLineDashes();
    }

    /**
     * Finds a package box by package name.
     */
    private PackageBox findBoxByPackageName(String packageName) {
        for (PackageBox box : packageBoxes) {
            if (box.node.getFullName().startsWith(packageName)) {
                return box;
            }
        }
        return null;
    }

    /**
     * Draws empty state message.
     */
    private void drawEmptyMessage(GraphicsContext gc) {
        gc.setFill(Color.web("#999999"));
        gc.setFont(Font.font("System", 14));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText("Load a JAR file to see package structure", getWidth() / 2, getHeight() / 2);
    }

    /**
     * Represents a package box in the visualization.
     */
    private static class PackageBox {
        ArchitectureNode node;
        double x;
        double y;
        double height;
        double width;

        PackageBox(ArchitectureNode node) {
            this.node = node;
            this.x = 0;
            this.y = 0;
            this.height = 60;
            this.width = 200;
        }
    }
    
    /**
     * Handles canvas clicks to expand/collapse packages.
     */
    private void handleCanvasClick(double clickX, double clickY) {
        for (PackageBox box : packageBoxes) {
            // Check if click is within the box header (top 40 pixels of the box)
            if (clickX >= box.x && clickX <= box.x + box.width &&
                clickY >= box.y && clickY <= box.y + 40) {
                
                // Only toggle if the box has children
                if (!box.node.hasChildren()) {
                    return;
                }
                
                // Toggle expanded state
                String pkgName = box.node.getFullName();
                boolean currentState = expandedState.getOrDefault(pkgName, true);
                expandedState.put(pkgName, !currentState);
                
                System.out.println("DEBUG: Toggled " + pkgName + " to " + !currentState);
                
                // Redraw
                redraw();
                return;
            }
        }
    }
}
