package io.aria.conductor.common.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JPA-correctness contract tests for the {@code @Embeddable} composite key classes. Hibernate
 * relies on a correct {@code equals}/{@code hashCode} pair (over ALL key columns) to identify
 * rows in the persistence context, so these verify that behaviour rather than trivial accessors:
 * equal keys must be {@code equals} and share a {@code hashCode}; keys differing in any single
 * column must not be equal.
 */
class CompositeIdContractTest {

    @Test
    void agentSkillId_equalWhenAllColumnsMatch() {
        AgentSkillId a = new AgentSkillId("agent-1", "skill-1");
        AgentSkillId b = new AgentSkillId("agent-1", "skill-1");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isEqualTo(a); // reflexive
    }

    @Test
    void agentSkillId_differsWhenAnyColumnDiffers() {
        AgentSkillId base = new AgentSkillId("agent-1", "skill-1");
        assertThat(base).isNotEqualTo(new AgentSkillId("agent-2", "skill-1"));
        assertThat(base).isNotEqualTo(new AgentSkillId("agent-1", "skill-2"));
        assertThat(base).isNotEqualTo(null);
        assertThat(base).isNotEqualTo("not-an-id");
    }

    @Test
    void agentToolId_equalityAndHashCode() {
        AgentToolId a = new AgentToolId("agent-1", "tool-1");
        AgentToolId b = new AgentToolId("agent-1", "tool-1");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new AgentToolId("agent-1", "tool-2"));
        assertThat(a).isNotEqualTo(new AgentToolId("agent-2", "tool-1"));
    }

    @Test
    void skillToolId_equalityAndHashCode() {
        SkillToolId a = new SkillToolId("skill-1", "tool-1");
        SkillToolId b = new SkillToolId("skill-1", "tool-1");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new SkillToolId("skill-2", "tool-1"));
        assertThat(a).isNotEqualTo(new SkillToolId("skill-1", "tool-2"));
    }

    @Test
    void roleSkillTemplateId_equalityAndHashCode() {
        RoleSkillTemplateId a = new RoleSkillTemplateId("ANALYST", "skill-1");
        RoleSkillTemplateId b = new RoleSkillTemplateId("ANALYST", "skill-1");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new RoleSkillTemplateId("REVIEWER", "skill-1"));
        assertThat(a).isNotEqualTo(new RoleSkillTemplateId("ANALYST", "skill-2"));
    }

    @Test
    void roleToolTemplateId_equalityAndHashCode() {
        RoleToolTemplateId a = new RoleToolTemplateId("ANALYST", "tool-1");
        RoleToolTemplateId b = new RoleToolTemplateId("ANALYST", "tool-1");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(new RoleToolTemplateId("REVIEWER", "tool-1"));
        assertThat(a).isNotEqualTo(new RoleToolTemplateId("ANALYST", "tool-2"));
    }

    @Test
    void hashCode_isStableAcrossRepeatedCalls() {
        AgentSkillId id = new AgentSkillId("agent-9", "skill-9");
        int first = id.hashCode();
        assertThat(id.hashCode()).isEqualTo(first);
        assertThat(id.hashCode()).isEqualTo(first);
    }

    @Test
    void noArgsConstructedKeys_withNullColumns_areEqual() {
        // Newly-instantiated (pre-populated) keys must still satisfy the equals contract.
        assertThat(new AgentSkillId()).isEqualTo(new AgentSkillId());
        assertThat(new AgentSkillId()).hasSameHashCodeAs(new AgentSkillId());
    }
}
