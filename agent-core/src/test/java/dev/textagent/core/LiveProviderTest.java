package dev.textagent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.*;
import java.time.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: sends synthetic data to a configured real model. Target API stays local. */
@EnabledIfEnvironmentVariable(named = "AGENT_LIVE_TEST", matches = "true")
class LiveProviderTest {
    @Test void collectCorrectConfirmWithRealModel() throws Exception {
        var calls = new AtomicInteger(); var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tenants", ex -> {
            var body = json.readTree(ex.getRequestBody());
            assertEquals("Тестовый Пользователь", body.path("fullName").asText());
            assertEquals("+15555550123", body.path("phone").asText());
            calls.incrementAndGet(); ex.sendResponseHeaders(200, -1); ex.close();
        }); server.start();
        try {
            var catalog = new OpenApiCatalog(getClass().getResourceAsStream("/rental-openapi.json"), Set.of());
            var planner = new ChatCompletionPlanner(URI.create(System.getenv("AGENT_MODEL_ENDPOINT")), System.getenv("AGENT_MODEL"), System.getenv("AGENT_API_KEY"), java.util.Map.of("thinking", java.util.Map.of("type", "disabled"), "max_tokens", 2048));
            var engine = new AgentEngine(catalog, planner, new HttpExecutor(URI.create("http://127.0.0.1:" + server.getAddress().getPort())),
                Clock.systemUTC(), Duration.ofMinutes(3), Duration.ofMinutes(30), 10);
            var first = engine.message("synthetic-test", null, "Создай арендатора. ФИО: Тестовый Пользователь. Телефон уточню следующим сообщением.");
            assertEquals("COLLECTING_FIELDS", first.state()); assertEquals(0, calls.get());
            var ready = engine.message("synthetic-test", first.sessionId(), "Создай арендатора с ФИО Тестовый Пользователь, телефон +15555550123.");
            assertEquals("READY_FOR_CONFIRMATION", ready.state()); assertEquals(0, calls.get());
            assertEquals("Тестовый Пользователь", ready.arguments().path("body").path("fullName").asText());
            assertEquals("+15555550123", ready.arguments().path("body").path("phone").asText());
            assertEquals("DONE", engine.confirm("synthetic-test", ready.sessionId(), ready.confirmationId(), "Bearer synthetic-user").state());
            engine.confirm("synthetic-test", ready.sessionId(), ready.confirmationId(), "Bearer synthetic-user"); assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
}
