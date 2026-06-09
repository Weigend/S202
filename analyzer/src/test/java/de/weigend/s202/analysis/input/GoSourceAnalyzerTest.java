package de.weigend.s202.analysis.input;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.impl.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.impl.golang.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Go Reader concept points.
 * All tests use an in-memory GoAstProvider — no external Go installation required.
 */
class GoSourceAnalyzerTest {

    @TempDir
    Path tempDir;

    // ── Helper: build a ParsedGoFile with one type ────────────────────────────

    private static ParsedGoFile fileWithType(String filePath, String pkgName,
                                              String importPath, String typeName, String typeKind) {
        return new ParsedGoFile(filePath, pkgName, importPath,
                List.of(),
                List.of(new ParsedGoFile.TypeDecl(typeName, typeKind, List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of());
    }

    private static DependencyModel resolve(String moduleName, List<ParsedGoFile> files) {
        return new GoDependencyResolver(moduleName, files).resolve();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 1: Exported Go type → Typ-ClassInfo
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void mapsExportedTypeToTypClassInfo() {
        ParsedGoFile file = fileWithType(
                "client/v3/client.go", "v3", "go.etcd.io/etcd/v3/client/v3", "Client", "struct");

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        DependencyModel.ClassInfo ci = model.getClass("client.v3.Client");
        assertNotNull(ci, "Typ-ClassInfo for Client must exist");
        assertEquals("Client",    ci.simpleName);
        assertEquals("client.v3", ci.packageName);
        assertFalse(ci.interfaceType);
    }

    @Test
    void mapsInterfaceTypeWithInterfaceFlag() {
        ParsedGoFile file = fileWithType(
                "client/v3/watch.go", "v3", "go.etcd.io/etcd/v3/client/v3", "Watcher", "interface");

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        assertTrue(model.getClass("client.v3.Watcher").interfaceType);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 2: Constructor function assigned to return type
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void assignsConstructorFunctionToReturnType() {
        ParsedGoFile file = new ParsedGoFile(
                "client/v3/client.go", "v3", "go.etcd.io/etcd/v3/client/v3",
                List.of(),
                List.of(new ParsedGoFile.TypeDecl("Client", "struct", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.FunctionDecl("New", "", List.of(),
                        List.of("*Config"), List.of("*Client"))),
                List.of(), List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        assertNotNull(model.getClass("client.v3.Client").getMethod("New", "(*Config)"),
                "New() must be a MethodInfo of Client");
        assertNull(model.getClass("client.v3.client"), "No DefaultClass should be created");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 3: error return type is filtered out
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void filtersErrorFromReturnTuple() {
        ParsedGoFile file = new ParsedGoFile(
                "client/v3/client.go", "v3", "go.etcd.io/etcd/v3/client/v3",
                List.of(),
                List.of(new ParsedGoFile.TypeDecl("Client", "struct", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.FunctionDecl("New", "", List.of(),
                        List.of("*Config"), List.of("*Client", "error"))),
                List.of(), List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        assertNotNull(model.getClass("client.v3.Client").getMethod("New", "(*Config)"),
                "New() must still be assigned to Client after filtering error");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 4: slice/pointer unwrapping in return type
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void unwrapsSliceAndPointerInReturnType() {
        ParsedGoFile file = new ParsedGoFile(
                "client/v3/client.go", "v3", "go.etcd.io/etcd/v3/client/v3",
                List.of(),
                List.of(new ParsedGoFile.TypeDecl("Client", "struct", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.FunctionDecl("NewList", "", List.of(),
                        List.of(), List.of("[]*Client"))),
                List.of(), List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        assertNotNull(model.getClass("client.v3.Client").getMethod("NewList", "()"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 5: first parameter fallback when no local return type
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void firstParamFallbackWhenNoLocalReturnType() {
        ParsedGoFile file = new ParsedGoFile(
                "client/v3/client.go", "v3", "go.etcd.io/etcd/v3/client/v3",
                List.of(),
                List.of(new ParsedGoFile.TypeDecl("Config", "struct", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.FunctionDecl("processConfig", "", List.of(),
                        List.of("*Config"), List.of("error"))),
                List.of(), List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        assertNotNull(model.getClass("client.v3.Config").getMethod("processConfig", "(*Config)"),
                "processConfig must be assigned to Config via first param");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 6: return type wins over first parameter
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void returnTypeWinsOverFirstParam() {
        // Convert(a *Config) *Client — both Config and Client are local
        ParsedGoFile file = new ParsedGoFile(
                "client/v3/client.go", "v3", "go.etcd.io/etcd/v3/client/v3",
                List.of(),
                List.of(
                        new ParsedGoFile.TypeDecl("Client", "struct", List.of(), List.of(), List.of()),
                        new ParsedGoFile.TypeDecl("Config", "struct", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.FunctionDecl("Convert", "", List.of(),
                        List.of("*Config"), List.of("*Client"))),
                List.of(), List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        assertNotNull(model.getClass("client.v3.Client").getMethod("Convert", "(*Config)"),
                "Convert must be on Client (return wins over param)");
        assertNull(findMethod(model.getClass("client.v3.Config"), "Convert"),
                "Convert must NOT be on Config");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 7: qualified type is not matched locally → DefaultClass
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void qualifiedTypeNotMatchedLocallyGoesToDefaultClass() {
        ParsedGoFile file = new ParsedGoFile(
                "client/v3/client.go", "v3", "go.etcd.io/etcd/v3/client/v3",
                List.of(),
                List.of(new ParsedGoFile.TypeDecl("Client", "struct", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.FunctionDecl("doXY", "", List.of(),
                        List.of("*otherpkg.Server"), List.of())),
                List.of(), List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        DependencyModel.ClassInfo dc = model.getClass("client.v3.client");
        assertNotNull(dc, "DefaultClass client.v3.client must exist for doXY");
        assertNotNull(dc.getMethod("doXY", "(*otherpkg.Server)"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 8: file with only functions → DefaultClass
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void fileWithOnlyFunctionsCreatesDefaultClass() {
        ParsedGoFile file = new ParsedGoFile(
                "server/etcdserver/api/v3rpc/grpc.go", "v3rpc",
                "go.etcd.io/etcd/v3/server/etcdserver/api/v3rpc",
                List.of(), List.of(),
                List.of(new ParsedGoFile.FunctionDecl("responseHeader", "", List.of(),
                        List.of(), List.of("string"))),
                List.of(), List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        DependencyModel.ClassInfo dc = model.getClass("server.etcdserver.api.v3rpc.grpc");
        assertNotNull(dc, "DefaultClass grpc must exist");
        assertEquals("grpc", dc.simpleName);
        assertEquals("server.etcdserver.api.v3rpc", dc.packageName);
        assertNotNull(dc.getMethod("responseHeader", "()"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 9: init() goes to DefaultPackageClass
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void initFunctionGoesToDefaultPackageClass() {
        // File name "config.go" ≠ package name "embed" → no merge → separate DefaultPackageClass
        ParsedGoFile file = new ParsedGoFile(
                "server/embed/config.go", "embed", "go.etcd.io/etcd/v3/server/embed",
                List.of(),
                List.of(new ParsedGoFile.TypeDecl("Config", "struct", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.FunctionDecl("init", "", List.of(), List.of(), List.of())),
                List.of(), List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        DependencyModel.ClassInfo dp = model.getClass("server.embed.embed");
        assertNotNull(dp, "DefaultPackageClass server.embed.embed must exist for init()");
        assertNotNull(dp.getMethod("init", "()"));
        // Config ClassInfo still exists
        assertNotNull(model.getClass("server.embed.Config"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 10: package-level var creates DefaultPackageClass with USES
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void packageLevelVarCreatesDefaultPackageClassWithUses() {
        // Logger is defined in another package
        ParsedGoFile loggerFile = fileWithType(
                "pkg/logging/logger.go", "logging",
                "go.etcd.io/etcd/v3/pkg/logging", "Logger", "struct");

        ParsedGoFile file = new ParsedGoFile(
                "server/embed/server.go", "embed", "go.etcd.io/etcd/v3/server/embed",
                List.of(new ParsedGoFile.ImportDecl("logging", "go.etcd.io/etcd/v3/pkg/logging")),
                List.of(),
                List.of(),
                List.of(new ParsedGoFile.VarDecl("defaultLogger", "logging.Logger",
                        "go.etcd.io/etcd/v3/pkg/logging")),
                List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(loggerFile, file));

        DependencyModel.ClassInfo dp = model.getClass("server.embed.embed");
        assertNotNull(dp, "DefaultPackageClass must exist");
        assertTrue(dp.dependencies.contains("pkg.logging.Logger"),
                "DefaultPackageClass must USES pkg.logging.Logger");
        assertTrue(dp.getKinds("pkg.logging.Logger").contains(EdgeKind.USES));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 11: merge rule — filename == package name
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void mergesDefaultClassAndDefaultPackageClassWhenFilenameMatchesPackageName() {
        // embed.go in package embed: merge rule applies
        ParsedGoFile file = new ParsedGoFile(
                "server/embed/embed.go", "embed", "go.etcd.io/etcd/v3/server/embed",
                List.of(), List.of(),
                List.of(new ParsedGoFile.FunctionDecl("helper", "", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.VarDecl("x", "string", "")),
                List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        // Only ONE node — not two separate ones
        assertNotNull(model.getClass("server.embed.embed"), "Merged node must exist");
        assertNull(model.getClass("server.embed.embed_extra"), "No extra node");
        // helper() is a MethodInfo on the merged node
        assertNotNull(model.getClass("server.embed.embed").getMethod("helper", "()"));
    }

    @Test
    void noMergeWhenFilenameDoesNotMatchPackageName() {
        // config.go in package embed: no merge
        ParsedGoFile file = new ParsedGoFile(
                "server/embed/config.go", "embed", "go.etcd.io/etcd/v3/server/embed",
                List.of(), List.of(),
                List.of(new ParsedGoFile.FunctionDecl("helper", "", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.VarDecl("x", "string", "")),
                List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        // DefaultClass "config" for the free function
        assertNotNull(model.getClass("server.embed.config"), "DefaultClass config must exist");
        assertNotNull(model.getClass("server.embed.config").getMethod("helper", "()"));
        // DefaultPackageClass "embed" for the var
        assertNotNull(model.getClass("server.embed.embed"), "DefaultPackageClass embed must exist");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 12: struct embedding → EXTENDS edge
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void createsExtendsEdgeForStructEmbedding() {
        ParsedGoFile etcdserverFile = fileWithType(
                "server/etcdserver/server.go", "etcdserver",
                "go.etcd.io/etcd/v3/server/etcdserver", "EtcdServer", "struct");

        ParsedGoFile embedFile = new ParsedGoFile(
                "server/embed/etcd.go", "embed", "go.etcd.io/etcd/v3/server/embed",
                List.of(new ParsedGoFile.ImportDecl("etcdserver", "go.etcd.io/etcd/v3/server/etcdserver")),
                List.of(new ParsedGoFile.TypeDecl("Etcd", "struct", List.of(),
                        List.of("*etcdserver.EtcdServer"), List.of())),
                List.of(), List.of(), List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(etcdserverFile, embedFile));

        DependencyModel.ClassInfo etcd = model.getClass("server.embed.Etcd");
        assertNotNull(etcd);
        assertTrue(etcd.dependencies.contains("server.etcdserver.EtcdServer"),
                "Etcd must EXTENDS EtcdServer");
        assertTrue(etcd.getKinds("server.etcdserver.EtcdServer").contains(EdgeKind.EXTENDS));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 13: struct field types → USES edge
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void createsUsesEdgeForFieldTypes() {
        ParsedGoFile clientFile = fileWithType(
                "client/v3/client.go", "v3",
                "go.etcd.io/etcd/v3/client/v3", "Client", "struct");

        ParsedGoFile embedFile = new ParsedGoFile(
                "server/embed/etcd.go", "embed", "go.etcd.io/etcd/v3/server/embed",
                List.of(new ParsedGoFile.ImportDecl("clientv3", "go.etcd.io/etcd/v3/client/v3")),
                List.of(new ParsedGoFile.TypeDecl("Etcd", "struct", List.of(), List.of(),
                        List.of(new ParsedGoFile.FieldDecl(
                                "Clients", "[]*clientv3.Client", "go.etcd.io/etcd/v3/client/v3")))),
                List.of(), List.of(), List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(clientFile, embedFile));

        DependencyModel.ClassInfo etcd = model.getClass("server.embed.Etcd");
        assertNotNull(etcd);
        assertTrue(etcd.dependencies.contains("client.v3.Client"),
                "Etcd must USES client.v3.Client");
        assertTrue(etcd.getKinds("client.v3.Client").contains(EdgeKind.USES));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 14: function call → CALLS edge
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void createsCallsEdge() {
        ParsedGoFile serverFile = new ParsedGoFile(
                "server/etcdserver/server.go", "etcdserver",
                "go.etcd.io/etcd/v3/server/etcdserver",
                List.of(),
                List.of(new ParsedGoFile.TypeDecl("EtcdServer", "struct", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.FunctionDecl("NewServer", "", List.of(),
                        List.of("*Config"), List.of("*EtcdServer"))),
                List.of(), List.of());

        ParsedGoFile embedFile = new ParsedGoFile(
                "server/embed/etcd.go", "embed", "go.etcd.io/etcd/v3/server/embed",
                List.of(new ParsedGoFile.ImportDecl("etcdserver", "go.etcd.io/etcd/v3/server/etcdserver")),
                List.of(new ParsedGoFile.TypeDecl("Etcd", "struct", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.FunctionDecl("StartEtcd", "", List.of(),
                        List.of("*Config"), List.of("*Etcd"))),
                List.of(),
                List.of(new ParsedGoFile.CallRef(
                        "StartEtcd",
                        "go.etcd.io/etcd/v3/server/etcdserver",
                        "NewServer",
                        false)));

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(serverFile, embedFile));

        DependencyModel.ClassInfo etcd = model.getClass("server.embed.Etcd");
        assertNotNull(etcd);
        assertTrue(etcd.dependencies.contains("server.etcdserver.EtcdServer"),
                "StartEtcd (assigned to Etcd) must CALLS EtcdServer (owner of NewServer)");
        assertTrue(etcd.getKinds("server.etcdserver.EtcdServer").contains(EdgeKind.CALLS));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 15: New* call pattern → INSTANTIATES edge
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void createsInstantiatesEdgeForNewPattern() {
        ParsedGoFile clientFile = new ParsedGoFile(
                "client/v3/client.go", "v3", "go.etcd.io/etcd/v3/client/v3",
                List.of(),
                List.of(new ParsedGoFile.TypeDecl("Client", "struct", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.FunctionDecl("New", "", List.of(),
                        List.of("*Config"), List.of("*Client"))),
                List.of(), List.of());

        ParsedGoFile callerFile = new ParsedGoFile(
                "server/embed/etcd.go", "embed", "go.etcd.io/etcd/v3/server/embed",
                List.of(new ParsedGoFile.ImportDecl("clientv3", "go.etcd.io/etcd/v3/client/v3")),
                List.of(new ParsedGoFile.TypeDecl("Etcd", "struct", List.of(), List.of(), List.of())),
                List.of(new ParsedGoFile.FunctionDecl("StartEtcd", "", List.of(),
                        List.of(), List.of("*Etcd"))),
                List.of(),
                List.of(new ParsedGoFile.CallRef(
                        "StartEtcd",
                        "go.etcd.io/etcd/v3/client/v3",
                        "New",
                        true)));  // isNewPattern = true

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(clientFile, callerFile));

        DependencyModel.ClassInfo etcd = model.getClass("server.embed.Etcd");
        assertTrue(etcd.getKinds("client.v3.Client").contains(EdgeKind.INSTANTIATES),
                "New() call with isNewPattern must produce INSTANTIATES edge");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 16: package hierarchy built from directory structure
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void buildsPackageHierarchyFromDirectoryStructure() {
        ParsedGoFile f1 = fileWithType("server/embed/etcd.go",     "embed",
                "go.etcd.io/etcd/v3/server/embed",      "Etcd",       "struct");
        ParsedGoFile f2 = fileWithType("server/etcdserver/srv.go", "etcdserver",
                "go.etcd.io/etcd/v3/server/etcdserver", "EtcdServer", "struct");

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(f1, f2));

        assertNotNull(model.getPackage("server"),       "top-level package server");
        assertNotNull(model.getPackage("server.embed"), "nested package server.embed");
        assertNotNull(model.getPackage("server.etcdserver"));
        assertTrue(model.getPackage("server").childPackages.contains("server.embed"));
        assertTrue(model.getPackage("server").childPackages.contains("server.etcdserver"));
        assertTrue(model.getPackage("server.embed").classNames.contains("server.embed.Etcd"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 17: module prefix is stripped from FQNs
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void stripsModulePrefixFromFqn() {
        ParsedGoFile file = fileWithType(
                "discovery/dns/dns.go", "dns",
                "github.com/prometheus/prometheus/discovery/dns", "DNS", "struct");

        DependencyModel model = resolve("github.com/prometheus/prometheus", List.of(file));

        assertNotNull(model.getClass("discovery.dns.DNS"),
                "FQN must strip module prefix");
        assertNull(model.getClass("github.com/prometheus/prometheus/discovery/dns.DNS"),
                "raw import path must not appear in FQN");
    }

    @Test
    void rootPackageUsesLastModuleSegment() {
        ParsedGoFile file = fileWithType(
                "main.go", "main",
                "github.com/prometheus/prometheus", "Main", "struct");

        DependencyModel model = resolve("github.com/prometheus/prometheus", List.of(file));

        assertNotNull(model.getClass("prometheus.Main"),
                "Root package class must use last module segment as package FQN");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 18: multiple types per file — each becomes a separate ClassInfo
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void multipleTypesInOneFileEachBecomeOwnClassInfo() {
        ParsedGoFile file = new ParsedGoFile(
                "client/v3/client.go", "v3", "go.etcd.io/etcd/v3/client/v3",
                List.of(),
                List.of(
                        new ParsedGoFile.TypeDecl("Client",     "struct", List.of(), List.of(), List.of()),
                        new ParsedGoFile.TypeDecl("Config",     "struct", List.of(), List.of(), List.of()),
                        new ParsedGoFile.TypeDecl("AuthConfig", "struct", List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of());

        DependencyModel model = resolve("go.etcd.io/etcd/v3", List.of(file));

        assertNotNull(model.getClass("client.v3.Client"));
        assertNotNull(model.getClass("client.v3.Config"));
        assertNotNull(model.getClass("client.v3.AuthConfig"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 19: model is compatible with LevelCalculator
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void modelRunsThroughLevelCalculator() {
        ParsedGoFile f1 = fileWithType("pkg/model/label.go", "model",
                "github.com/prometheus/prometheus/pkg/model", "Label", "struct");
        ParsedGoFile f2 = new ParsedGoFile(
                "rules/rule.go", "rules",
                "github.com/prometheus/prometheus/rules",
                List.of(new ParsedGoFile.ImportDecl("model", "github.com/prometheus/prometheus/pkg/model")),
                List.of(new ParsedGoFile.TypeDecl("Rule", "struct", List.of(), List.of(),
                        List.of(new ParsedGoFile.FieldDecl("label", "model.Label",
                                "github.com/prometheus/prometheus/pkg/model")))),
                List.of(), List.of(), List.of());

        DependencyModel model = resolve("github.com/prometheus/prometheus", List.of(f1, f2));

        assertDoesNotThrow(() -> {
            DomainModel domain = new LevelCalculator().calculate(model);
            assertNotNull(domain);
        }, "LevelCalculator must accept the Go DependencyModel");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 20: vendor/ directory is excluded by GoFileDiscovery
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void excludesVendorDirectory() throws IOException {
        Path vendor = tempDir.resolve("vendor/github.com/some/lib");
        Files.createDirectories(vendor);
        Files.writeString(vendor.resolve("lib.go"), "package lib\n");
        Files.writeString(tempDir.resolve("main.go"), "package main\n");

        GoFileDiscovery discovery = new GoFileDiscovery();
        List<Path> found = discovery.discover(tempDir);

        assertTrue(found.stream().noneMatch(p -> p.toString().contains("vendor")),
                "vendor/ must be excluded");
        assertTrue(found.stream().anyMatch(p -> p.getFileName().toString().equals("main.go")),
                "main.go at root must be included");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Concept point 21: go.mod parsing
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void parsesGoModForModuleNameAndVersion() throws IOException {
        Path goMod = tempDir.resolve("go.mod");
        Files.writeString(goMod, """
                module go.etcd.io/etcd/v3

                go 1.21

                require (
                    github.com/stretchr/testify v1.8.0
                )
                """);

        GoModuleInfo info = new GoModuleReader().parse(goMod);

        assertEquals("go.etcd.io/etcd/v3", info.moduleName());
        assertEquals("1.21",               info.goVersion());
        assertEquals(tempDir,              info.moduleRoot());
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helper: unwrapBaseType unit tests
    // ══════════════════════════════════════════════════════════════════════════
    @Test
    void unwrapBaseTypeHandlesAllWrapperForms() {
        assertEquals("Client",     GoDependencyResolver.unwrapBaseType("*Client"));
        assertEquals("Client",     GoDependencyResolver.unwrapBaseType("**Client"));
        assertEquals("Client",     GoDependencyResolver.unwrapBaseType("[]*Client"));
        assertEquals("Client",     GoDependencyResolver.unwrapBaseType("[]Client"));
        assertEquals("Client",     GoDependencyResolver.unwrapBaseType("map[string]*Client"));
        assertEquals("Set",        GoDependencyResolver.unwrapBaseType("Set[T]"));
        assertEquals("Set",        GoDependencyResolver.unwrapBaseType("[]*Set[T]"));
        assertNull(GoDependencyResolver.unwrapBaseType(null));
        assertNull(GoDependencyResolver.unwrapBaseType(""));
    }

    // ── Private helper ────────────────────────────────────────────────────────

    private static DependencyModel.MethodInfo findMethod(DependencyModel.ClassInfo ci, String name) {
        if (ci == null) return null;
        return ci.methods.values().stream().filter(m -> m.name.equals(name)).findFirst().orElse(null);
    }
}
