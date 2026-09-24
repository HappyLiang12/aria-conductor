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
 */
public final class CoreE2eApplication {

    private CoreE2eApplication() {
    }

    public static void main(String[] args) {
        new SpringApplicationBuilder(ActApplication.class, CoreE2eConfiguration.class)
                .profiles("h2", "core-e2e").run(args);
    }
}
