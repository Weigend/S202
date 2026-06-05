/*
 * Copyright 2026 Weigend AM GmbH & Co.KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.weigend.s202.analysis.input;

import de.weigend.s202.domain.DomainModel;
import de.weigend.s202.domain.architecture.LevelCalculator;
import de.weigend.s202.reader.DependencyModel;
import de.weigend.s202.reader.EdgeKind;
import de.weigend.s202.reader.PythonSourceAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PythonSourceAnalyzerTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsPythonModulesToClassInfoAndPackageHierarchy() throws IOException {
        write("src/shop/orders/__init__.py", "");
        write("src/shop/orders/model.py", "class Order:\n    pass\n");
        write("src/shop/orders/service.py", "from .model import Order\n\nOrder()\n");

        DependencyModel model = new PythonSourceAnalyzer().analyze(tempDir);

        assertNotNull(model.getClass("shop.orders.service"));
        assertEquals("shop.orders", model.getClass("shop.orders.service").packageName);
        assertEquals("service", model.getClass("shop.orders.service").simpleName);
        assertNotNull(model.getClass("shop.orders.__init__"));
        assertTrue(model.getPackage("shop").childPackages.contains("shop.orders"));
        assertTrue(model.getPackage("shop.orders").classNames.contains("shop.orders.service"));
    }

    @Test
    void resolvesRelativeImportsAndConstructorCalls() throws IOException {
        write("src/shop/orders/model.py", """
                class Order:
                    def __init__(self, data):
                        self.data = data
                """);
        write("src/shop/orders/service.py", """
                from .model import Order

                def build(data):
                    return Order(data)
                """);

        DependencyModel model = new PythonSourceAnalyzer().analyze(tempDir);
        DependencyModel.ClassInfo service = model.getClass("shop.orders.service");

        assertTrue(service.dependencies.contains("shop.orders.model"));
        assertTrue(service.getKinds("shop.orders.model").contains(EdgeKind.INSTANTIATES));

        DependencyModel.MethodInfo build = service.getMethod("build", "(data)");
        assertNotNull(build);
        assertEquals(1, build.methodCalls.get("shop.orders.model.Order.__init__"));
        assertEquals(Set.of("(self, data)"),
                build.methodCallDescriptors.get("shop.orders.model.Order.__init__"));
    }

    @Test
    void resolvesTypedSelfFieldMethodCalls() throws IOException {
        write("src/shop/orders/repository.py", """
                class OrderRepository:
                    def save(self, order):
                        pass
                """);
        write("src/shop/events/bus.py", """
                def publish(event):
                    pass
                """);
        write("src/shop/orders/service.py", """
                from .repository import OrderRepository
                from shop.events.bus import publish

                class OrderService:
                    def __init__(self, repo: OrderRepository):
                        self.repo = repo

                    def place(self, order):
                        self.repo.save(order)
                        publish(order)
                """);

        DependencyModel model = new PythonSourceAnalyzer().analyze(tempDir);
        DependencyModel.ClassInfo service = model.getClass("shop.orders.service");

        assertTrue(service.getKinds("shop.orders.repository").contains(EdgeKind.CALLS));
        assertTrue(service.getKinds("shop.events.bus").contains(EdgeKind.CALLS));

        DependencyModel.MethodInfo place = service.getMethod("OrderService.place", "(self, order)");
        assertNotNull(place);
        assertEquals(1, place.methodCalls.get("shop.orders.repository.OrderRepository.save"));
        assertEquals(1, place.methodCalls.get("shop.events.bus.publish"));
    }

    @Test
    void mapsInheritanceToExtendsDependency() throws IOException {
        write("src/app/base.py", "class BaseService:\n    pass\n");
        write("src/app/service.py", """
                from .base import BaseService

                class Service(BaseService):
                    pass
                """);

        DependencyModel model = new PythonSourceAnalyzer().analyze(tempDir);
        DependencyModel.ClassInfo service = model.getClass("app.service");

        assertTrue(service.dependencies.contains("app.base"));
        assertTrue(service.getKinds("app.base").contains(EdgeKind.EXTENDS));
    }

    @Test
    void usesSyntheticPackageForTopLevelFiles() throws IOException {
        write("main.py", "def run():\n    pass\n");

        DependencyModel model = new PythonSourceAnalyzer().analyze(tempDir);
        String mainModule = model.getAllClassNames().stream()
                .filter(name -> name.endsWith(".main"))
                .findFirst()
                .orElseThrow();

        assertFalse(model.getClass(mainModule).packageName.isBlank());
        assertNotNull(model.getPackage(model.getClass(mainModule).packageName));
    }

    @Test
    void producedModelRunsThroughLevelCalculator() throws IOException {
        write("src/shop/orders/model.py", "class Order:\n    pass\n");
        write("src/shop/orders/service.py", """
                from .model import Order

                def build(data):
                    return Order(data)
                """);

        DependencyModel raw = new PythonSourceAnalyzer().analyze(tempDir);
        DomainModel calculated = new LevelCalculator().calculate(raw);

        assertNotNull(calculated.getClass("shop.orders.service"));
        assertNotNull(calculated.getPackage("shop.orders"));
    }

    @Test
    void discoversLibSourceRootForRepositoryLayoutsLikeAnsible() throws IOException {
        write("lib/ansible/module_utils/common/text/converters.py", """
                def to_text(value):
                    return str(value)
                """);
        write("lib/ansible/playbook/base.py", """
                from ansible.module_utils.common.text.converters import to_text

                def name(value):
                    return to_text(value)
                """);

        DependencyModel model = new PythonSourceAnalyzer().analyze(tempDir);

        assertNotNull(model.getClass("ansible.playbook.base"));
        assertNull(model.getClass("lib.ansible.playbook.base"));

        DependencyModel.ClassInfo base = model.getClass("ansible.playbook.base");
        assertTrue(base.dependencies.contains("ansible.module_utils.common.text.converters"));
        assertTrue(base.getKinds("ansible.module_utils.common.text.converters").contains(EdgeKind.IMPORTS));
        assertTrue(base.getKinds("ansible.module_utils.common.text.converters").contains(EdgeKind.CALLS));
        assertEquals(1, base.getMethod("name", "(value)").methodCalls.get(
                "ansible.module_utils.common.text.converters.to_text"));
    }

    @Test
    void selectedInstalledPackageDirectoryKeepsPackageNameInModuleFqns() throws IOException {
        write("usr/lib/python3/dist-packages/ansible/module_utils/common/text/converters.py", """
                def to_text(value):
                    return str(value)
                """);
        write("usr/lib/python3/dist-packages/ansible/playbook/base.py", """
                from ansible.module_utils.common.text.converters import to_text

                def name(value):
                    return to_text(value)
                """);

        Path selectedPackageDir = tempDir.resolve("usr/lib/python3/dist-packages/ansible");
        DependencyModel model = new PythonSourceAnalyzer().analyze(selectedPackageDir);

        assertNotNull(model.getClass("ansible.playbook.base"));
        assertNull(model.getClass("playbook.base"));

        DependencyModel.ClassInfo base = model.getClass("ansible.playbook.base");
        assertTrue(base.dependencies.contains("ansible.module_utils.common.text.converters"));
        assertTrue(base.getKinds("ansible.module_utils.common.text.converters").contains(EdgeKind.IMPORTS));
        assertEquals(1, base.getMethod("name", "(value)").methodCalls.get(
                "ansible.module_utils.common.text.converters.to_text"));
    }

    @Test
    void resolvesPackageImportsToInitModule() throws IOException {
        write("src/ansible/errors/__init__.py", """
                class AnsibleError(Exception):
                    pass
                """);
        write("src/ansible/playbook/base.py", """
                from ansible.errors import AnsibleError

                def fail():
                    raise AnsibleError("bad")
                """);

        DependencyModel model = new PythonSourceAnalyzer().analyze(tempDir);
        DependencyModel.ClassInfo base = model.getClass("ansible.playbook.base");

        assertTrue(base.dependencies.contains("ansible.errors.__init__"));
        assertTrue(base.getKinds("ansible.errors.__init__").contains(EdgeKind.IMPORTS));
        assertTrue(base.getKinds("ansible.errors.__init__").contains(EdgeKind.INSTANTIATES));
        assertEquals(1, base.getMethod("fail", "()").methodCalls.get(
                "ansible.errors.__init__.AnsibleError.__init__"));
    }

    @Test
    void importOnlyDependenciesAffectArchitectureLevels() throws IOException {
        write("src/app/settings.py", "VALUE = 1\n");
        write("src/app/service.py", "from app import settings\n\nNAME = settings.VALUE\n");

        DependencyModel raw = new PythonSourceAnalyzer().analyze(tempDir);
        DependencyModel.ClassInfo service = raw.getClass("app.service");
        assertTrue(service.getKinds("app.settings").contains(EdgeKind.IMPORTS));

        DomainModel calculated = new LevelCalculator().calculate(raw);

        assertEquals(1, calculated.getClass("app.service").architectureLevel);
        assertEquals(0, calculated.getClass("app.settings").architectureLevel);
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
