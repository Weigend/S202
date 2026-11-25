# Open Points

## 1. Inner Classes

**Status:** Counted for their outer class, not visualized

Inner classes (for example `MyClass$InnerClass`), local and anonymous ones included, are not visualized separately. What they depend on counts for their outer class, just like a dependency on them. Inheritance with an inner class at one end is an edge of its own kind (*nested inheritance*): it counts as a dependency but does not make the outer class a subtype.

**Reason:** Simplifies the representation and prevents double counting.

**Possible extension:** Optionally show inner classes as child nodes.
