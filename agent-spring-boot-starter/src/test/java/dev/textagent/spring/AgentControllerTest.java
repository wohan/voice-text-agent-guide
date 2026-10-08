package dev.textagent.spring;

import dev.textagent.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AgentControllerTest {
    @Test void authenticatedRoundTripAndAnonymousRejection() throws Exception {
        var json = new ObjectMapper(); var token = new AtomicReference<String>();
        var catalog = new OpenApiCatalog(new ByteArrayInputStream("openapi: 3.0.3\npaths:\n  /ping:\n    get:\n      operationId: ping\n".getBytes()), Set.of("ping"));
        var engine = new AgentEngine(catalog, (o,d,m) -> new Planner.Proposal("ping", json.createObjectNode(), null),
            (o,a,t,c) -> { token.set(t); return new dev.textagent.core.Executor.Result(200, "ok"); }, Clock.systemUTC(), Duration.ofMinutes(3), Duration.ofMinutes(30), 10);
        var mvc = MockMvcBuilders.standaloneSetup(new AgentController(engine)).addPlaceholderValue("text-agent.api-path", "/api/text-agent").build();
        mvc.perform(post("/api/text-agent/messages").contentType("application/json").content("{\"message\":\"hello\"}")).andExpect(status().isUnauthorized());
        var result = mvc.perform(post("/api/text-agent/messages").principal(() -> "alice").contentType("application/json").content("{\"message\":\"hello\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var view = json.readTree(result); assertNull(token.get());
        String route = "/api/text-agent/sessions/" + view.get("sessionId").asText() + "/confirm";
        String body = json.createObjectNode().put("confirmationId", view.get("confirmationId").asText()).put("owner", "bob").toString();
        mvc.perform(post(route).principal(() -> "bob").header("Authorization", "Bearer bob").contentType("application/json").content(body)).andExpect(status().isNotFound());
        mvc.perform(post(route).principal(() -> "alice").header("Authorization", "Bearer current").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("DONE"));
        assertEquals("Bearer current", token.get());
    }
}
