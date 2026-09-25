package io.aria.conductor.app.e2e;

import io.aria.conductor.ActApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

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
 * <p>Harness environment defaults (Task 19; default properties, so an explicit
 * command-line or environment value always wins): the approval gate window is
 * shortened to {@value #DEFAULT_APPROVALS_TIMEOUT_MS} ms so the SDD resubmit
 * case can observe exactly {@code EXPIRED} within a spec's budget (the
 * production default is 30 minutes), and the mock-peer control token is left to
 * the environment ({@code ARIA_PEER_CONTROL_TOKEN}, required by the harness
 * configuration and by the peers themselves).
 */
public final class CoreE2eApplication {

    /** The harness approval window: a spec observes the exact EXPIRED state within budget. */
    static final String DEFAULT_APPROVALS_TIMEOUT_MS = "60000";

    private CoreE2eApplication() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(ActApplication.class, CoreE2eConfiguration.class)
                .profiles("h2", "core-e2e")
                .properties("approvals.timeout-ms=" + DEFAULT_APPROVALS_TIMEOUT_MS)
                .run(args);
    }
}
