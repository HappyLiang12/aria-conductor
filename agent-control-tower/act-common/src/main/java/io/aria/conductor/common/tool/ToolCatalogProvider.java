package io.aria.conductor.common.tool;

import java.util.List;

/**
 * Supplies the tool catalog a context envelope renders (spec §8.1, "Tools").
 */
public interface ToolCatalogProvider {

    List<ToolDescriptor> tools();
}
