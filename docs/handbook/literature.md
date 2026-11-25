# Literature

[Handbook home](README.md) · [Why architecture matters](why-architecture.md) ·
[Architecture smells](architecture-smells.md) · [Reference](reference.md)

The handbook is practical, but its central ideas have a long history. These references explain the
principles behind S202 and give names to the design questions raised by its views.

## Architecture and change

- Len Bass, Paul Clements, Rick Kazman: *Software Architecture in Practice*, 4th edition,
  Addison-Wesley, 2021. Chapters 1 and 2 introduce architectural structures and explain how
  architecture enables or inhibits quality attributes, change, communication and incremental
  development. [Publisher page](https://www.pearson.com/en-gb/subject-catalog/p/software-architecture-in-practice/P200000000111/9780136886020)
- Philippe Kruchten, Robert Nord, Ipek Ozkaya: *Managing Technical Debt: Reducing Friction in
  Software Development*, Addison-Wesley, 2019. It connects short-term design choices with future
  rework and provides a vocabulary for deciding which debt to remove, reduce or accept.
  [SEI book page](https://www.sei.cmu.edu/library/managing-technical-debt-reducing-friction-in-software-development/)
- Neal Ford, Rebecca Parsons, Patrick Kua, Pramod Sadalage: *Building Evolutionary Architectures*,
  2nd edition, O'Reilly, 2022. Architectural fitness functions turn important characteristics into
  explicit, repeatable checks as a system changes.
  [Book page](https://www.thoughtworks.com/en-au/insights/books/building-evolutionaryarchitectures-second-edition)

## Modules, components and dependency rules

- David L. Parnas: "On the Criteria to Be Used in Decomposing Systems into Modules", *Communications
  of the ACM* 15(12), 1972, pp. 1053-1058. The classic argument for decomposing a system around
  decisions likely to change and hiding them behind interfaces.
  [DOI](https://doi.org/10.1145/361598.361623)
- Robert C. Martin: *Clean Architecture*, Pearson, 2017. Part IV describes component cohesion and
  coupling, including the Acyclic Dependencies, Stable Dependencies and Stable Abstractions
  principles. [Publisher page](https://www.pearson.com/en-gb/subject-catalog/p/Martin-Clean-Architecture-A-Craftsman-s-Guide-to-Software-Structure-and-Design/P200000009528)
- John Lakos: *Large-Scale C++ Software Design*, Addison-Wesley, 1996; and *Large-Scale C++ Volume I:
  Process and Architecture*, 2020. Despite the language in the title, the treatment of physical
  dependencies, cycles, levelization and components applies broadly to compiled systems.
  [Publisher page](https://www.pearson.com/en-gb/subject-catalog/p/large-scale-c-process-and-architecture-volume-1/P200000009513)

## Dependency analysis and visualization

- Structure101, a commercial tool, acquired by Sonar. Its levelized structure map - containers within
  containers, ordered by their dependencies - is the model for the layers of S202.
  [Product page](https://www.sonarsource.com/structure101/)
- Neeraj Sangal, Ev Jordan, Vineet Sinha, Daniel Jackson: "Using Dependency Models to Manage Complex
  Software Architecture", OOPSLA 2005, pp. 167-176. Dependencies are extracted from code, arranged
  to expose structural patterns and combined with design rules that capture architectural intent.
  [DOI](https://doi.org/10.1145/1094811.1094824) ·
  [Author PDF](https://groups.csail.mit.edu/sdg/pubs/2005/oopsla05-dsm.pdf)
- Veronika Dashuber, Michael Philippsen, Johannes Weigend: "A Layered Software City for Dependency
  Visualization", IVAPP 2021, pp. 15-26. A related layout in three dimensions: layered graph
  drawing arranges a software city by dependencies as well as by packages.
  [DOI](https://doi.org/10.5220/0010180200150026) ·
  [Publication page](https://www.scitepress.org/PublicationsDetail.aspx?ID=60MwJRH53eU%3D&t=1)
- Veronika Dashuber, Michael Philippsen: "Static and Dynamic Dependency Visualization in a Layered
  Software City", *SN Computer Science* 3, 511, 2022. It extends the layout to static and dynamic
  dependencies. [DOI](https://doi.org/10.1007/s42979-022-01404-6)

## Architecture smells

- Rick Kazman, Yuanfang Cai and colleagues: work on the architectural roots of technical debt links
  cyclic dependencies, modularity violations and unstable interfaces with maintenance consequences
  in industrial systems.
  [SEI case study](https://www.sei.cmu.edu/blog/a-case-study-in-locating-the-architectural-roots-of-technical-debt/)
- The Software Engineering Institute's overview of architecture smells distinguishes dependency-
  based smells such as dependency cycles and link overload from change- and concern-based smells.
  That distinction is also important for understanding the limits of S202.
  [SEI overview](https://www.sei.cmu.edu/blog/when-your-softwares-check-engine-light-is-on-identifying-design-problems-that-impact-software-failure/)

## Further material in this repository

- [Software architecture as a layered representation](../concept/Software-Architektur-als-Schichtendarstellung-en.pdf)
  describes S202's level calculation, cycle handling and layout invariants in detail.
- [The Tool That Fixed Itself](../s202/CASE_STUDY.md) tells what happened when S202 was applied to
  its own codebase.

[Back to handbook home →](README.md)
