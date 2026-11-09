package io.aria.conductor.memory;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.aria.conductor.memory.service.MemoryContextContributor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec §5: {@code act-memory} depends on {@code act-common} only. It must not import
 * {@code act-execution}, {@code act-knowledge}, or {@code act-aria} — the memory kernel and
 * its recall slice are injected into the engine as Spring beans, never reached from here.
 *
 * <p>The rule is checked against the compiled production classes of this module, and the
 * non-vacuity tests make an empty or partial import a failure rather than a silent pass:
 * the import must contain the real module classes, and the same ArchUnit engine must reject
 * a dependency that one of those imported classes really has.
 */
class MemoryModuleBoundaryTest {

    private static JavaClasses memoryClasses;

    @BeforeAll
    static void importMemoryProductionClasses() {
        memoryClasses = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.aria.conductor.memory");
    }

    @Test
    void theImportCoversTheRealModuleRatherThanAnEmptyPackage() {
        assertThat(memoryClasses)
                .as("an empty import would make the boundary rule below vacuous")
                .hasSizeGreaterThan(10);
        assertThat(memoryClasses)
                .anyMatch(javaClass -> javaClass.getName().equals(MemoryModule.class.getName()));
        assertThat(memoryClasses)
                .anyMatch(javaClass -> javaClass.getName().equals(MemoryContextContributor.class.getName()));
        assertThat(memoryClasses)
                .allMatch(javaClass -> javaClass.getPackageName().startsWith("io.aria.conductor.memory"));
    }

    @Test
    void memoryDoesNotDependOnExecutionKnowledgeOrAria() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("io.aria.conductor.memory..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(
                        "io.aria.conductor.execution..",
                        "io.aria.conductor.knowledge..",
                        "io.aria.conductor.aria..");

        rule.check(memoryClasses);
    }

    /**
     * The canary proves the assertion above can fail on these exact imported classes: it
     * forbids a dependency {@link MemoryContextContributor} genuinely has, so a rule that
     * passes because the import (or the dependency resolution) is empty fails this test.
     */
    @Test
    void theBoundaryEngineRejectsARealDependencyOfAnImportedClass() {
        ArchRule canary = noClasses()
                .that().haveSimpleName(MemoryContextContributor.class.getSimpleName())
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("io.aria.conductor.common.context.ContextContributor");

        assertThatThrownBy(() -> canary.check(memoryClasses))
                .as("the canary must fail; a pass here means the boundary rule above is vacuous")
                .isInstanceOf(AssertionError.class);
    }
}
