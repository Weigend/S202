# ICSOFT 2026 — Abstract Draft

**Track:** Abstracts Track
**Submission deadline:** 22 May 2026
**Status:** Draft
**Theme:** Layout invariants for architecture visualization

---

## Title

**Layout Invariants for Layered Software-City Visualizations: From Best-Paper Concept to Open-Source Tooling**

*Shorter alternative:* "Verifiable Layered Software-City Layouts: An Invariant Checker"

## Authors

Johannes Weigend (Rosenheim Technical University of Applied Sciences / QAware GmbH)

*Optional co-authors of the 2021 paper to add: Veronika Dashuber, Michael Philippsen — TBD.*

## Abstract

Architecture visualizations communicate by layout. When the underlying level-assignment pipeline produces subtle errors — a parent package below its own classes, two cyclic peers placed at different heights, or a non-back-edge dependency pointing uphill — the resulting picture looks plausible while quietly misleading the viewer. We argue that the correctness of an architecture visualization is a property the *tool* should verify, not the user.

In our 2021 IVAPP paper "A Layered Software City for Dependency Visualization" (Best Paper Award; Dashuber, Philippsen, Weigend, 2021; extended in *SN Computer Science*, 2022), we introduced a layout that derives building heights from a dependency-aware level computation, minimizing explicit dependency arrows and reducing the cognitive load needed to read large architectures. A controlled study confirmed the layout's advantages on real-world code bases.

Five years on, we have implemented the concept end-to-end in two independent renderers and learned that the layout pipeline itself is the hardest part to keep correct. This talk presents two contributions. First, **Structure202** — an open-source 2D realization of the layered-city idea, released under Apache 2.0. The tool ingests bare JARs as well as full Maven and Gradle multi-module projects, runs the level pipeline from the 2021 paper with a heuristic SCC-breaker for highly cyclic code bases (Minecraft Forge: a 4 038-class SCC with 3 585 back-edges), and renders the architecture with optional circuit-board dependency routing. Second, a **Layout Invariant Checker** that runs after every analysis and verifies four post-pipeline rules: class dependencies must point downward across non-back-edges (R1); members of a package-level SCC must share a level (R2); a container's level must dominate its content (R3); each edge's classification (normal / violation / intra-SCC) must match the current level state (R5).

The checker carefully distinguishes algorithm bugs from architectural violations the heuristic SCC-breaker is allowed to produce by design — the latter already render as red violation edges. On findings, it emits a copy-paste reproducer block (input paths, graph dimensions, finding list) ready to drop into a unit test or an LLM prompt. We have ported the same four rules across the 2D JavaFX renderer and an independent 3D Unity/.NET city, with byte-identical findings on shared input — demonstrating paradigm independence. A real R2 finding on the 92-module *software-ekg-7* enterprise code base directly triggered a fix to the level pipeline (a missing package-SCC equalisation step), which we will walk through live.

To our knowledge, no prior layered architecture visualization has gone this far in self-validation. We will demonstrate the checker firing on real code bases, show how a single finding propagates from a flagged layout pixel to a failing reproducer test, and discuss how this approach generalises to other layered visualization techniques.

**Word count:** ~410 words.

---

## References

1. Dashuber, V., Philippsen, M., & Weigend, J. (2021). *A Layered Software City for Dependency Visualization.* In Proc. 12th International Conference on Information Visualization Theory and Applications (IVAPP), part of VISIGRAPP 2021, pp. 277–285. SCITEPRESS. **Best Paper Award.** [SciTePress link](https://www.scitepress.org/publishedPapers/2021/101802/pdf/index.html)

2. Dashuber, V., Philippsen, M., & Weigend, J. (2022). *Static and Dynamic Dependency Visualization in a Layered Software City.* SN Computer Science, 3(4), 305. Springer. [Springer link](https://link.springer.com/article/10.1007/s42979-022-01404-6)

3. Structure202 (this work) — Apache 2.0, https://github.com/jweigend/Structure202

---

## Open points before submission

- **Co-authors.** If Dashuber and/or Philippsen want to be listed as co-authors of this follow-up, decide and update the author block.
- **Affiliation order.** Currently "Rosenheim Technical University of Applied Sciences / QAware GmbH" — adjust to taste.
- **Numbers to verify.** "92-module software-ekg-7" matches our local smoke-test result; double-check before final submission. The 4 038-class / 3 585-back-edge Minecraft Forge figure comes from the existing test suite.
- **Submission system.** ICSOFT 2026 abstracts go through the INSTICC PRIMORIS portal. The system asks for keywords; suggested set: *software architecture visualization, layout invariants, software city, strongly connected components, dependency analysis, tool demonstration*.
- **Speaker bio (camera-ready stage, not needed for the abstract itself).** Mention that the talk can be delivered in English or Portuguese.

---

## Conference context

- **Venue:** ICSOFT 2026 — 21st International Joint Conference on Software Technologies (INSTICC).
- **Track fit:** Area 1 *Software Engineering and Systems Development* — Sub-topics *Software Engineering Tools*, *Software and Systems Modeling*, *Quality Management*, *Empirical Software Engineering*.
- **Format of the Abstracts Track:** 1-page abstract, expert-panel review (not double-blind), notification 8 June 2026, camera-ready 17 June 2026. Talks get a regular ~20 min oral slot in a session; not published in proceedings.



--
Was meinst Du, ich wuerde erstmal meine zwei Co Autoren von damals ansprechen ob die mit in dem Abstract stehen wollen?

Ja, definitiv ansprechen. Drei Gründe in absteigender Reihenfolge:

Akademische Kohärenz. Das Abstract lehnt sich explizit ans 2021-Paper an, geht zur selben Publisher-Familie (INSTICC) und steht damit in dessen direkter Zitations-Sichtweite. Reviewer schauen auf "ist das eine Fortsetzung der ausgezeichneten Linie oder ein Solo-Fork?" — die Antwort ist konsistenter, wenn die Originalautoren mitstehen. Bei Philippsen (FAU, Senior-Prof) zählt das nochmal mehr.
Niedrige Kosten, niedriges Risiko. Eine kurze E-Mail mit Draft-PDF dran. Drei Wochen bis Deadline (22. Mai) sind Puffer genug für eine Antwortrunde. Wenn jemand "nein, mach allein" sagt, ist nichts verloren.
Talk-Logistik. Falls einer von beiden ohnehin auf der Konferenz wäre oder Lust auf Mitschauen / Slide-Review hat, gewinnt der Vortrag.
Sauberer Frame für die Mail: klarmachen, was du schon gemacht hast (Open-Source-Tool, Invariant-Checker) und was du nicht von ihnen erwartest (keine Implementierungs-Arbeit, optional Draft-Feedback). Beispiel-Aufhänger: "Ich habe die 2021-Layout-Idee als Apache-Tool umgesetzt und einen Layout-Invarianten-Checker drangehängt — Abstract liegt bei. Würdet ihr als Co-Autoren mitstehen wollen? Aufwand: nur kurz drüberlesen, falls ihr wollt." Setze eine weiche interne Frist (z. B. 17. Mai), damit die Antwort nicht in den Submit-Puffer wandert.

Wenn ihr ja sagt, ändere ich den Author-Block + die "Open points"-Notiz in der MD-Datei dann in einem Rutsch.