package io.aria.conductor.app.e2e;

import io.aria.conductor.ActApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

/**
 * Launcher of the deterministic core E2E harness (Task 16).
 *
 * <p>Runs the production application classes ({@link ActApplication}) with the
 * harness configuration ({@link CoreE2eConfiguration}) on an ordinary JVM
 * classpath -- the extracted {@code backend-e2e-harness} tree's
 * {@code app/ + harness/ + lib/*} -- never on the untouched production
 * {@code backend-jar}:
 *
 * <pre>
 * java --enable-preview -cp "&lt;harness&gt;/app:&lt;harness&gt;/harness:&lt;harness&gt;/lib/*" \
 *     io.aria.conductor.app.e2e.CoreE2eApplication --e2e.assets="&lt;harness&gt;"
 * </pre>
 *
 * <p>Profiles: {@code h2} (persistent file database, same profile the local
 * stack uses) and {@code core-e2e} (the harness configuration is only active
 * under this profile, so an accidental harness class on a production classpath
 * changes nothing). The mock protocol executables and the built Qoder ACP
 * bridge are resolved as assets from {@code e2e.assets}, never from the
 * classpath.
 *
 * <p>Harness environment defaults (Task 19; an explicit command-line or
 * environment value always wins): the approval gate window is shortened to
 * {@value #DEFAULT_APPROVALS_TIMEOUT_MS} ms so the SDD resubmit case can
 * observe exactly {@code EXPIRED} within a spec's budget (the production
 * default is 30 minutes), the run admission limit is disabled (see below),
 * and the mock-peer control token is left to the environment
 * ({@code ARIA_PEER_CONTROL_TOKEN}, required by the harness configuration and
 * by the peers themselves). The admission limit needs a different mechanism
 * than default properties: {@code application.yml} defines
 * {@code aria.runs.max-active} with an {@code ${ARIA_RUNS_MAX_ACTIVE:6}}
 * placeholder, and any yml value beats default properties -- so the harness
 * value is installed as a property source placed just below the system
 * environment, which still loses to explicit env vars and {@code -D} flags.
 *
 * <p>The admission limit ({@code aria.runs.max-active}/{@code aria-reserved})
 * is unlimited here because the suite's pause/approval specs intentionally
 * leave PAUSED runs behind, and PAUSED holds a slot by design -- with the
 * production cap (6+1) a long shard saturates its worker slots and later specs'
 * runs stay PENDING and time out. The harness runs a stub bridge with no
 * sandbox daemons (nothing the cap protects against), and the cap has its own
 * dedicated unit + integration coverage.
 */
public final class CoreE2eApplication {

    /** The harness approval window: a spec observes the exact EXPIRED state within budget. */
    static final String DEFAULT_APPROVALS_TIMEOUT_MS = "60000";

    /**
     * Paused-holder saturation guard: specs across the suite leave PAUSED runs
     * behind (kanban pause/approval journeys) and each holds a slot by design;
     * the production cap would starve later specs in a serial shard.
     */
    static void disableRunAdmissionLimit(ConfigurableEnvironment environment) {
        environment.getPropertySources().addAfter(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new MapPropertySource("coreE2eAdmissionDefaults", Map.of(
                        "aria.runs.max-active", "0",
                        "aria.runs.aria-reserved", "0")));
    }

    private CoreE2eApplication() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(ActApplication.class, CoreE2eConfiguration.class)
                .profiles("h2", "core-e2e")
                .properties("approvals.timeout-ms=" + DEFAULT_APPROVALS_TIMEOUT_MS)
                .initializers(ctx -> disableRunAdmissionLimit(ctx.getEnvironment()))
                .run(args);
    }
}
