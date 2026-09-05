package io.aria.conductor.mcp.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolAuditAspectTest {

    static class AuditedFixture {
        @Tool(name = "fixture_tool")
        public String call() {
            return ToolResponses.ok("done");
        }

        @Tool(name = "fixture_tool_failing")
        public String fail() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void audit_passesThrough_andIsInvoked() throws Exception {
        ToolAuditAspect aspect = new ToolAuditAspect();
        AuditedFixture fixture = new AuditedFixture();
        org.springframework.aop.aspectj.annotation.AspectJProxyFactory factory =
                new org.springframework.aop.aspectj.annotation.AspectJProxyFactory(fixture);
        factory.addAspect(aspect);
        AuditedFixture proxied = factory.getProxy();

        assertThat(proxied.call()).isEqualTo(ToolResponses.ok("done"));
        assertThatThrownBy(proxied::fail).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }
}
