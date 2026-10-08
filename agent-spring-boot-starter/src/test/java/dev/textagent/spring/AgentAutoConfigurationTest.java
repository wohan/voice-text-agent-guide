package dev.textagent.spring;
import dev.textagent.core.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import java.io.ByteArrayInputStream;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
class AgentAutoConfigurationTest {
    final WebApplicationContextRunner runner = new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(AgentAutoConfiguration.class));
    @Test void disabledByDefault() { runner.run(c -> assertThat(c).doesNotHaveBean(AgentController.class)); }
    @Test void installsControllerAndHonorsCustomPlanner() {
        runner.withPropertyValues("text-agent.enabled=true", "text-agent.store=memory", "text-agent.model-options.thinking.type=disabled", "text-agent.model-options[max_tokens]=2048")
            .withBean(OpenApiCatalog.class, () -> { try { return new OpenApiCatalog(new ByteArrayInputStream("openapi: 3.0.3\npaths:\n  /ping:\n    get:\n      operationId: ping\n".getBytes()), Set.of("ping")); } catch (Exception e) { throw new RuntimeException(e); } })
            .withBean(Planner.class, () -> (o,d,m) -> new Planner.Proposal(null, null, "test"))
            .withBean(dev.textagent.core.Executor.class, () -> (o,a,t,c) -> new dev.textagent.core.Executor.Result(200, "ok"))
            .run(c -> { assertThat(c).hasSingleBean(AgentController.class); assertThat(c).hasSingleBean(AgentEngine.class); assertThat(c.getBean(AgentProperties.class).modelOptions()).containsKey("thinking").containsKey("max_tokens"); });
    }
}
