package dev.textagent.core;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpExecutorTest {
    @Test void sendsExactBodyAndUserTokenButDoesNotFollowRedirect() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> body = new AtomicReference<>(), token = new AtomicReference<>(), marker = new AtomicReference<>(), key = new AtomicReference<>();
        AtomicInteger redirects = new AtomicInteger();
        server.createContext("/api/tenants", ex -> {
            body.set(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            token.set(ex.getRequestHeaders().getFirst("Authorization")); marker.set(ex.getRequestHeaders().getFirst("X-Actor-Channel")); key.set(ex.getRequestHeaders().getFirst("Idempotency-Key"));
            ex.getResponseHeaders().set("Location", "/stolen"); ex.sendResponseHeaders(302, -1); ex.close();
        });
        server.createContext("/stolen", ex -> { redirects.incrementAndGet(); ex.sendResponseHeaders(200, -1); ex.close(); }); server.start();
        try {
            var json = new ObjectMapper(); var args = json.readTree("{\"body\":{\"fullName\":\"Мария\"}}");
            var op = new Operation("createTenant", "Создать", "POST", "/api/tenants", json.createObjectNode());
            var result = new HttpExecutor(URI.create("http://127.0.0.1:" + server.getAddress().getPort())).execute(op, args, "Bearer alice", "confirmation");
            assertEquals(302, result.status()); assertEquals(args.get("body").toString(), body.get()); assertEquals("Bearer alice", token.get());
            assertEquals("AI_AGENT", marker.get()); assertEquals("confirmation", key.get()); assertEquals(0, redirects.get());
        } finally { server.stop(0); }
    }
    @Test void onlyApprovedResponseFieldsLeaveExecutor() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/rows",ex->{
            byte[] bytes="[{\"id\":\"1\",\"name\":\"Alice\",\"secret\":\"hidden\",\"nested\":{\"token\":\"hidden\"}}]".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);ex.close();
        });server.start();
        try {
            var json=new ObjectMapper();var op=new Operation("list","List","GET","/rows",json.createObjectNode(),java.util.Set.of("id","name","nested"));
            var result=new HttpExecutor(URI.create("http://127.0.0.1:"+server.getAddress().getPort())).execute(op,json.createObjectNode(),"Bearer user","test");
            assertEquals(1,result.data().path("total").asInt());assertEquals("Alice",result.data().path("items").get(0).path("name").asText());
            assertFalse(result.data().toString().contains("hidden"));assertFalse(result.data().path("items").get(0).has("nested"));
        }finally{server.stop(0);}
    }
    @Test void blocksPathTraversalBeforeNetworkCall() throws Exception {
        var json = new ObjectMapper(); var executor = new HttpExecutor(URI.create("http://127.0.0.1:1"));
        var op = new Operation("update", "Update", "PUT", "/api/tenants/{id}", json.createObjectNode());
        for (String id : new String[]{"..", "../admin", "%2e%2e", "x/y", "x?y"}) {
            var args = json.createObjectNode(); args.putObject("path").put("id", id);
            assertEquals(422, assertThrows(AgentException.class, () -> executor.execute(op, args, "token", "id")).status());
        }
    }
}
