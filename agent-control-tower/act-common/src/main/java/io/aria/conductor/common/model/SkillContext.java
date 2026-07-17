package io.aria.conductor.common.model;

/**
 * Immutable skill snapshot carried across the act-common / act-knowledge seam.
 * act-execution cannot reference SkillDefinition (it lives in act-knowledge),
 * so the provider projects the fields it needs into this record.
 */
public record SkillContext(String name, String description, String template, String stage) {
}
