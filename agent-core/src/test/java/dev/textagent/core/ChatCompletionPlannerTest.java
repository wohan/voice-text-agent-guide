package dev.textagent.core;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatCompletionPlannerTest {
    @Test void selectsFromIndexBeforeSendingOnlyChosenSchema() throws Exception {
        var json=new ObjectMapper();var requests=new java.util.concurrent.CopyOnWriteArrayList<JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/model",ex->{
            requests.add(json.readTree(ex.getRequestBody()));
            var proposal=json.createObjectNode().put("operationId","op3");proposal.putObject("arguments");
            var result=json.createObjectNode();var choice=result.putArray("choices").addObject().put("finish_reason","stop");
            choice.putObject("message").put("content",proposal.toString());byte[] bytes=json.writeValueAsBytes(result);
            ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);ex.close();
        });server.start();
        try {
            var operations=new ArrayList<Operation>();
            for(int i=0;i<12;i++)operations.add(new Operation("op"+i,"description"+i,"POST","/records",json.createObjectNode().put("type","object").put("description","schemaMarker"+i)));
            var planner=new ChatCompletionPlanner(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/model"),"stub",null);
            assertEquals("op3",planner.plan(operations,json.createObjectNode(),"test").operationId());
            assertEquals(2,requests.size());String first=requests.get(0).toString(),second=requests.get(1).toString();
            assertTrue(first.contains("op11"));assertFalse(first.contains("schemaMarker"));
            assertTrue(second.contains("schemaMarker3"));assertFalse(second.contains("schemaMarker2"));
        }finally{server.stop(0);}
    }
    @Test void textThroughModelAndConfirmationToRealHttp() throws Exception {
        var json = new ObjectMapper(); var calls = new AtomicInteger(); var requestBody = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", ex -> {
            String request = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(request.contains("createTenant")); assertFalse(request.contains("Bearer end-user"));
            var proposal = json.createObjectNode().put("operationId", "createTenant");
            proposal.putObject("arguments").putObject("body").put("fullName", "Мария Иванова").put("phone", "+79001112233");
            var result = json.createObjectNode(); var choice = result.putArray("choices").addObject(); choice.put("finish_reason", "stop");
            choice.putObject("message").put("content", proposal.toString());
            byte[] bytes = json.writeValueAsBytes(result); ex.sendResponseHeaders(200, bytes.length); ex.getResponseBody().write(bytes); ex.close();
        });
        server.createContext("/api/tenants", ex -> {
            calls.incrementAndGet(); requestBody.set(json.readTree(ex.getRequestBody()));
            assertEquals("Bearer end-user", ex.getRequestHeaders().getFirst("Authorization"));
            ex.sendResponseHeaders(200, -1); ex.close();
        }); server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var catalog = new OpenApiCatalog(getClass().getResourceAsStream("/agent-openapi.yaml"), Set.of("createTenant"));
            var engine = new AgentEngine(catalog, new ChatCompletionPlanner(base.resolve("/chat/completions"), "test-model", "test-provider-key"),
                new HttpExecutor(base), Clock.systemUTC(), Duration.ofMinutes(3), Duration.ofMinutes(30), 10);
            var preview = engine.message("alice", null, "Создай арендатора Марию Иванову, телефон +79001112233");
            assertEquals("READY_FOR_CONFIRMATION", preview.state()); assertEquals(0, calls.get());
            assertEquals("DONE", engine.confirm("alice", preview.sessionId(), preview.confirmationId(), "Bearer end-user").state());
            assertEquals(preview.arguments().get("body"), requestBody.get());
            engine.confirm("alice", preview.sessionId(), preview.confirmationId(), "Bearer end-user"); assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }
}
