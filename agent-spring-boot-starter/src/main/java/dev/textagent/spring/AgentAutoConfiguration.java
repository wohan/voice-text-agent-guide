package dev.textagent.spring;

import dev.textagent.core.*;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;

@AutoConfiguration(
    afterName = {
      "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
      "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
    })
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "text-agent", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AgentProperties.class)
public class AgentAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  OpenApiCatalog agentCatalog(AgentProperties p, ResourceLoader resources)
      throws java.io.IOException {
    if (p.spec() == null || !(p.spec().startsWith("classpath:") || p.spec().startsWith("file:")))
      throw new IllegalArgumentException("text-agent.spec must be classpath: or file:");
    try (var input = resources.getResource(p.spec()).getInputStream()) {
      return new OpenApiCatalog(input, p.allowedOperations());
    }
  }

  @Bean
  @ConditionalOnMissingBean(Planner.class)
  Planner agentPlanner(AgentProperties p) {
    return new ChatCompletionPlanner(p.modelEndpoint(), p.model(), p.apiKey(), p.modelOptions());
  }

  @Bean
  @ConditionalOnMissingBean(dev.textagent.core.Executor.class)
  dev.textagent.core.Executor agentExecutor(AgentProperties p) {
    return new HttpExecutor(p.targetBaseUrl());
  }

  @Bean
  @ConditionalOnMissingBean(SessionStore.class)
  @org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization
  SessionStore agentSessionStore(
      AgentProperties p,
      org.springframework.beans.factory.ObjectProvider<javax.sql.DataSource> dataSources) {
    if (p.store().equals("memory")) return new MemorySessionStore();
    if (!p.store().equals("jdbc")) throw new IllegalArgumentException("Unknown text-agent.store");
    var dataSource = dataSources.getIfAvailable();
    if (dataSource == null)
      throw new IllegalArgumentException(
          "Production text agent requires a DataSource; memory must be explicitly enabled for"
              + " development");
    return new JdbcSessionStore(dataSource, new StateCipher(p.stateEncryptionKey()));
  }

  @Bean
  @ConditionalOnMissingBean
  AgentEngine agentEngine(
      OpenApiCatalog catalog,
      Planner planner,
      dev.textagent.core.Executor executor,
      SessionStore store,
      AgentProperties p) {
    return new AgentEngine(
        catalog,
        planner,
        executor,
        store,
        Clock.systemUTC(),
        p.confirmationTtl(),
        p.sessionTtl(),
        p.maxSessions(),
        p.maxSessionsPerOwner(),
        p.requestsPerMinute());
  }

  @Bean(destroyMethod = "shutdown")
  java.util.concurrent.ScheduledExecutorService agentCleanup(AgentEngine engine) {
    var service =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
            r -> {
              var thread = new Thread(r, "text-agent-cleanup");
              thread.setDaemon(true);
              return thread;
            });
    service.scheduleWithFixedDelay(
        () -> {
          try {
            engine.cleanup();
          } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(AgentAutoConfiguration.class)
                .warn("Text agent cleanup failed; storage availability must be checked");
          }
        },
        60,
        60,
        java.util.concurrent.TimeUnit.SECONDS);
    return service;
  }

  @Bean
  @ConditionalOnMissingBean
  AgentController agentController(AgentEngine engine) {
    return new AgentController(engine);
  }
}
