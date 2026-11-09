package io.aria.conductor.execution.context.contributor;

import io.aria.conductor.common.context.ContextContributor;
import io.aria.conductor.common.context.ContextRequest;
import io.aria.conductor.common.context.ContextSection;
import io.aria.conductor.common.model.Sensitivity;
import io.aria.conductor.common.tool.ToolCatalogProvider;
import io.aria.conductor.common.tool.ToolDescriptor;
import io.aria.conductor.common.tool.ToolPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The generated tool section (spec P7): every catalog entry except the
 * operator-only ones, as {@code - name (POLICY): description}. The catalog is
 * the one classification, so the prompt cannot disagree with the policy
 * registry.
 *
 * <p>The catalog provider is a seam other lanes implement; when none is wired
 * the section is simply absent instead of failing the envelope.
 */
@Component
public class ToolCatalogContextContributor implements ContextContributor {

    private final ToolCatalogProvider catalog;

    @Autowired
    public ToolCatalogContextContributor(ObjectProvider<ToolCatalogProvider> catalogs) {
        this.catalog = catalogs.getIfAvailable();
    }

    /** Test seam: a catalog supplied directly. */
    public ToolCatalogContextContributor(ToolCatalogProvider catalog) {
        this.catalog = catalog;
    }

    @Override
    public String id() {
        return "tools";
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public List<ContextSection> contribute(ContextRequest request) {
        if (catalog == null) {
            return List.of();
        }
        List<ToolDescriptor> visible = catalog.tools().stream()
                .filter(tool -> tool.policy() != ToolPolicy.OPERATOR_ONLY)
                .toList();
        if (visible.isEmpty()) {
            return List.of();
        }
        StringBuilder content = new StringBuilder();
        for (ToolDescriptor tool : visible) {
            content.append("- ").append(tool.name())
                    .append(" (").append(tool.policy()).append("): ")
                    .append(tool.description()).append('\n');
        }
        List<String> sources = visible.stream().map(ToolDescriptor::name).toList();
        return List.of(new ContextSection(id(), "Tools", content.toString(),
                Sensitivity.INTERNAL, sources, false, null));
    }
}
