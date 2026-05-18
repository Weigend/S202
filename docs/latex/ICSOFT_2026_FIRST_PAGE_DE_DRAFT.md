# Consistent Levels for Layered Software Architecture Visualizations

## Subtitle

Separating Architectural Hypothesis, Package Tree, and Local Placement

## Authors

Johannes Weigend, Weigend AM GmbH & Co.KG, Germany/Brazil  
Michael Philippsen, Friedrich-Alexander-Universitaet Erlangen-Nuernberg, Germany

## Keywords

Software architecture visualization, layout invariants, layered 2D architecture visualization, strongly connected components, dependency analysis, software engineering tools

## Abstract

Layered architecture visualizations make dependency structure visible through vertical placement: elements that depend on others are placed higher, while more foundational elements are placed lower. Their practical value rests on a simple promise: an upward dependency should not be an accidental layout artifact, but should reveal an architectural anomaly. If level computation is wrong, the resulting picture may still look plausible and can therefore mislead the viewer.

S202 is an open-source Java tool for analyzing and visualizing software architectures, released under the Apache 2.0 license.[^s202] The architectural view discussed here is closely related to the earlier product Structure101. To the best of our knowledge, however, neither the source code nor the underlying level-assignment algorithm of Structure101 has been publicly documented. S202 makes this style of visualization available as an open algorithm, with reproducible computation and checkable consistency conditions.

The algorithm does not merely produce a drawing. It first computes an architectural hypothesis. The starting point is the Java package tree: packages contain subpackages and classes, and this hierarchical structure is preserved in the visualization. From the weighted mutual dependencies between packages, S202 derives a plausible architectural ordering of this package tree. If one package depends substantially more on another package than vice versa, it is interpreted as a higher-level user; the package it uses more strongly is placed lower as a more foundational building block.

Conceptually, the pipeline has three stages. First, S202 analyzes class dependencies without imposing the package structure: global SCCs are detected, large cycles are cut heuristically, and this yields a global view of feedback in the class graph. Second, S202 computes an architectural hypothesis for the package tree from weighted package dependencies: more heavily used packages are placed lower, and their users higher. Third, S202 computes the visible local placement inside each package container.

The central point is the separation between architectural hypothesis and layout position. The package analysis decides which package directions are considered normal, cyclic, or heuristically cut. The local placement, in contrast, only decides where classes and visible containers appear inside one concrete parent package. This local phase uses only dependencies within the respective container and may assign new numeric row indices. Its goal, however, is not to minimize the number of visible upward edges locally. Its goal is stable placement within the architectural frame that has already been computed: classes and visible containers are placed so that the most plausible package structure is preserved and local cycles are resolved in a traceable way.

The visible arrangement builds on this separation. Local SCCs are detected in the respective sibling graph. For local placement, S202 does not simply cut the first backward edge, nor does it apply a purely edge-wise rank heuristic. Instead, it considers the entire local SCC and prefers a cut whose removal decomposes the cyclic component most strongly. This reduces local disorder as much as possible without allowing a mere layout criterion to overrule the package structure as the primary architectural hypothesis. An upward line is therefore not a silent layout error: it remains explainable either as an architectural violation, as cyclic feedback, or as an explicit heuristic cut. Horizontal ordering may improve readability, but it does not change which direction is considered architecturally normal.

The reason this cannot be implemented as a simple single pass over all elements is the interaction of cycles and feedback between levels. Classes can depend on each other, packages can be cyclically coupled, and local layout decisions must not flow back uncontrolled into the semantic package analysis. S202 therefore handles cycles at the appropriate level: globally for class and package cycles in the architectural hypothesis, and locally for cycles inside one package container. Small or peer-level cycles remain grouped. Larger local SCCs are reduced by cuts that break as much cyclic structure as possible at once; the cut edges remain explicitly classified.

It is equally important what S202 does not do. The algorithm does not search for a global minimum of violated rules, and it does not iteratively try new layouts until the picture looks better. Instead, it follows a fixed pipeline: build the package tree, derive a weighted architectural hypothesis, detect global and local SCCs, explicitly mark likely back edges, compute local orderings, and then check whether the resulting visualization satisfies the expected rules. These invariants distinguish pipeline defects from deliberately tolerated heuristic cuts and from real architectural violations that the visualization is meant to expose.

We demonstrate this pipeline on Minecraft Forge 1.19.2. The system is large and highly cyclic, making it a useful stress test for whether a layered visualization shows more than a plausible picture. Using this example, we show how S202 breaks large SCCs, treats package and class levels separately, and explicitly classifies the remaining upward edges. The contribution therefore demonstrates not only a layout, but a traceable computation: from architectural hypothesis through local placement to machine-checkable consistency.

[^s202]: <https://github.com/jweigend/Structure202>
