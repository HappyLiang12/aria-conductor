package io.aria.conductor.memory;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.aria.conductor.memory.fixture.MemoryBoundaryFixture;
import io.aria.conductor.memory.service.MemoryContextContributor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec §5: {@code act-memory} depends on {@code act-common} only. It must not import
 * {@code act-execution}, {@code act-knowledge}, or {@code act-aria} — the memory kernel and
 * its recall slice are injected into the engine as Spring beans, never reached from here.
 *
 * <p>The rule is checked against the compiled production classes of this module, and the
 * non-vacuity tests make an empty or partial import a failure rather than a silent pass:
 * the import must contain the real module classes, and the very same rule instance must
 * reject a fixture that depends on {@code act-execution} while allowing the fixture's
 * {@code act-common} dependency. That fixture canary is what a typo in the rule reddens;
 * checking a canary against an unrelated predicate cannot.
 */
class MemoryModuleBoundaryTest {

    /**
     * The rule used both for the real module classes and for the fixture canary, so the
     * canary exercises the exact package selectors the boundary depends on.
     */
    private static final ArchRule MEMORY_BOUNDARY = noClasses()
            .that().resideInAPackage("io.aria.conductor.memory..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "io.aria.conductor.execution..",
                    "io.aria.conductor.knowledge..",
                    "io.aria.conductor.aria..");

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
        MEMORY_BOUNDARY.check(memoryClasses);
    }

    /**
     * Replays the real rule against a test-only fixture that depends on one banned module
     * ({@code io.aria.conductor.execution.context.ContextAssembler}) and on the allowed
     * {@code act-common} ({@link io.aria.conductor.common.context.ContextSection}). Exactly
     * one violation, naming the fixture and the execution type, proves the rule selector is
     * live and not overreaching; a broken selector fails here instead of leaving the real
     * check green over a module that could now import the engine.
     */
    @Test
    void theRealBoundaryRuleRejectsAnExecutionDependencyAndAllowsAnActCommonOne() {
        JavaClasses fixture = new ClassFileImporter()
                .importPackages("io.aria.conductor.memory.fixture");

        assertThat(fixture)
                .as("the canary must import its fixture, or it passes vacuously")
                .hasSize(1);

        EvaluationResult result = MEMORY_BOUNDARY.evaluate(fixture);
        List<String> violations = result.getFailureReport().getDetails();

        assertThat(result.hasViolation())
                .as("the fixture's ContextAssembler field is a banned execution dependency")
                .isTrue();
        assertThat(violations)
                .as("only the execution field violates; the act-common field must stay allowed")
                .hasSize(1);
        assertThat(violations.get(0))
                .contains(MemoryBoundaryFixture.class.getName())
                .contains("io.aria.conductor.execution.context.ContextAssembler")
                .doesNotContain("ContextSection");
    }
}
