package de.weigend.s202.reader.impl.golang;

import java.nio.file.Path;

/**
 * Parsed content of a {@code go.mod} file.
 */
public record GoModuleInfo(
        String moduleName,  // e.g. "go.etcd.io/etcd/v3"
        String goVersion,   // e.g. "1.21"
        Path moduleRoot) {  // directory containing go.mod
}
