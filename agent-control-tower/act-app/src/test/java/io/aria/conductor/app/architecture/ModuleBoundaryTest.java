package io.aria.conductor.app.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture guardrails for the modular monolith.
 * <p>
 * These rules encode the module conventions from AGENTS.md so drift is caught
 * at build time instead of code review. Frozen violations (legacy debt) are
 * listed explicitly per rule — never add new ones.
 */
class ModuleBoundaryTest {

    private static JavaClasses productionClasses;

    @BeforeAll
    static void importClasses() {
        productionClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.aria.conductor");
    }

    /** act-common is the shared kernel: it must not depend on any downstream module. */
    @Test
    void commonModuleDependsOnNoDownstreamModule() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.aria.conductor.common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "io.aria.conductor.agent..",
                        "io.aria.conductor.execution..",
                        "io.aria.conductor.knowledge..",
                        "io.aria.conductor.aria..",
                        "io.aria.conductor.dashboard..");
        rule.check(productionClasses);
    }

    /** act-app is the composition root: no module may depend back on it. */
    @Test
    void noModuleDependsOnApplicationRoot() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackage("io.aria.conductor.app..")
                .and().doNotHaveFullyQualifiedName("io.aria.conductor.ActApplication")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("io.aria.conductor.ActApplication");
        rule.check(productionClasses);
    }

    /** REST controllers must be named *Controller so the API surface stays discoverable. */
    @Test
    void restControllersAreNamedController() {
        ArchRule rule = classes()
                .that().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .should().haveSimpleNameEndingWith("Controller");
        rule.check(productionClasses);
    }

    /** Spring Data repositories must be named *Repository. */
    @Test
    void jpaRepositoriesAreNamedRepository() {
        ArchRule rule = classes()
                .that().areInterfaces()
                .and().areAssignableTo("org.springframework.data.jpa.repository.JpaRepository")
                .and().doNotHaveFullyQualifiedName("org.springframework.data.jpa.repository.JpaRepository")
                .should().haveSimpleNameEndingWith("Repository");
        rule.check(productionClasses);
    }

    /** Domain events live in act-common/event and are named *Event (AGENTS.md convention). */
    @Test
    void domainEventPackageContainsOnlyEvents() {
        ArchRule rule = classes()
                .that().resideInAPackage("io.aria.conductor.common.event..")
                .and().areTopLevelClasses() // nested payload types (e.g. RunIterationEvent.ToolCallDetail) are fine
                .should().haveSimpleNameEndingWith("Event");
        rule.check(productionClasses);
    }

    /**
     * Constructor injection only. Two legacy classes predate this rule and are
     * frozen; do NOT extend this list — refactor instead.
     */
    @Test
    void noFieldInjectionOutsideFrozenLegacy() {
        DescribedPredicate<JavaClass> frozenLegacy = new DescribedPredicate<>(
                "frozen legacy field-injection classes") {
            @Override
            public boolean test(JavaClass javaClass) {
                String name = javaClass.getFullName();
                return name.equals("io.aria.conductor.execution.circuit.CircuitBreakerProperties")
                        || name.equals("io.aria.conductor.dashboard.report.ReportProperties");
            }
        };
        ArchRule rule = fields()
                .that().areDeclaredInClassesThat(DescribedPredicate.not(frozenLegacy))
                .should().notBeAnnotatedWith("org.springframework.beans.factory.annotation.Autowired");
        rule.check(productionClasses);
    }

    /** Controllers stay thin: no direct EntityManager access from the web layer. */
    @Test
    void controllersDoNotUseEntityManagerDirectly() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Controller")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("jakarta.persistence.EntityManager");
        rule.check(productionClasses);
    }

    /** Credential/crypto code must use SecureRandom, never java.util.Random. */
    @Test
    void cryptoCodeNeverUsesInsecureRandom() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.aria.conductor.execution.credential..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("java.util.Random");
        rule.check(productionClasses);
    }

    /** Production code never calls System.exit — lifecycle belongs to Spring. */
    @Test
    void noSystemExitInProductionCode() {
        ArchRule rule = noClasses()
                .should().callMethod(System.class, "exit", int.class);
        rule.check(productionClasses);
    }

    /** Services must not depend on the web layer (no upward dependency). */
    @Test
    void servicesDoNotDependOnControllers() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Service")
                .should().dependOnClassesThat()
                .haveSimpleNameEndingWith("Controller");
        rule.check(productionClasses);
    }
}
