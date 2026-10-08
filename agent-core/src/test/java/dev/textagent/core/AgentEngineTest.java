package dev.textagent.core;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class AgentEngineTest {
    final ObjectMapper json = new ObjectMapper();
    OpenApiCatalog catalog;
    TestClock clock = new TestClock();
    AtomicInteger calls = new AtomicInteger();
    AgentEngine engine;
    @BeforeEach void setup() throws Exception {
        catalog = new OpenApiCatalog(getClass().getResourceAsStream("/agent-openapi.yaml"), Set.of("createTenant"));
        engine = engine((op, args, auth, id) -> { calls.incrementAndGet(); return new Executor.Result(200, "Готово"); });
    }
    AgentEngine engine(Executor executor) {
        return new AgentEngine(catalog, (ops, draft, message) -> {
            try { return new Planner.Proposal("createTenant", json.readTree(message), null); }
            catch (Exception ex) { throw new AgentException(502, "bad model"); }
        }, executor, clock, Duration.ofMinutes(3), Duration.ofMinutes(30), 10);
    }
    String valid() { return "{\"body\":{\"fullName\":\"Мария Иванова\",\"phone\":\"+79001112233\"}}"; }
    @Test void collectValidateAndConfirm() {
        var draft = engine.message("alice", null, "{\"body\":{\"fullName\":\"Мария\"}}");
        assertEquals("COLLECTING_FIELDS", draft.state()); assertNull(draft.confirmationId()); assertEquals(0, calls.get());
        assertTrue(draft.errors().stream().anyMatch(e -> e.contains("phone")));
        var ready = engine.message("alice", draft.sessionId(), valid());
        assertEquals("READY_FOR_CONFIRMATION", ready.state()); assertEquals(0, calls.get());
        assertEquals("DONE", engine.confirm("alice", ready.sessionId(), ready.confirmationId(), "Bearer user").state());
        assertEquals(1, calls.get());
        assertEquals("DONE", engine.confirm("alice", ready.sessionId(), ready.confirmationId(), "Bearer user").state());
        assertEquals(1, calls.get());
    }
    @Test void rejectsUnknownFieldsAndWrongTypes() {
        var result = engine.message("alice", null, "{\"body\":{\"fullName\":42,\"phone\":\"bad\",\"accountId\":\"other\"}}");
        assertEquals("COLLECTING_FIELDS", result.state()); assertEquals(3, result.errors().size()); assertEquals(0, calls.get());
    }
    @Test void isolatesOwners() {
        var ready = engine.message("alice", null, valid());
        assertEquals(404, assertThrows(AgentException.class, () -> engine.confirm("bob", ready.sessionId(), ready.confirmationId(), "Bearer bob")).status());
        assertThrows(AgentException.class, () -> engine.message("bob", ready.sessionId(), valid()));
        assertThrows(AgentException.class, () -> engine.cancel("bob", ready.sessionId()));
        assertThrows(AgentException.class, () -> engine.status("bob", ready.sessionId())); assertEquals(0, calls.get());
    }
    @Test void editAndCancelInvalidateOldConfirmations() {
        var first = engine.message("alice", null, valid());
        var second = engine.message("alice", first.sessionId(), valid());
        assertThrows(AgentException.class, () -> engine.confirm("alice", first.sessionId(), first.confirmationId(), "token"));
        engine.cancel("alice", second.sessionId());
        assertThrows(AgentException.class, () -> engine.confirm("alice", second.sessionId(), second.confirmationId(), "token")); assertEquals(0, calls.get());
    }
    @Test void plannerFailureAlsoInvalidatesConfirmation() {
        var first = engine.message("alice", null, valid());
        assertThrows(AgentException.class, () -> engine.message("alice", first.sessionId(), "bad"));
        assertThrows(AgentException.class, () -> engine.confirm("alice", first.sessionId(), first.confirmationId(), "token"));
    }
    @Test void expiresConfirmation() {
        var ready = engine.message("alice", null, valid()); clock.now = clock.now.plusSeconds(180);
        assertThrows(AgentException.class, () -> engine.confirm("alice", ready.sessionId(), ready.confirmationId(), "token")); assertEquals(0, calls.get());
    }
    @Test void uncertainResultNeverRetriesAutomatically() {
        engine = engine((op, args, auth, id) -> { calls.incrementAndGet(); throw new AgentException(502, "timeout"); });
        var ready = engine.message("alice", null, valid());
        assertEquals("UNKNOWN", engine.confirm("alice", ready.sessionId(), ready.confirmationId(), "token").state());
        assertEquals("UNKNOWN", engine.confirm("alice", ready.sessionId(), ready.confirmationId(), "token").state()); assertEquals(1, calls.get());
    }
    @Test void concurrentConfirmationsExecuteOnce() throws Exception {
        var ready = engine.message("alice", null, valid());
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Future<AgentEngine.View>>();
            for (int i = 0; i < 20; i++) jobs.add(pool.submit(() -> engine.confirm("alice", ready.sessionId(), ready.confirmationId(), "token")));
            for (var job : jobs) assertTrue(Set.of("DONE", "EXECUTING").contains(job.get(5, TimeUnit.SECONDS).state()));
        }
        assertEquals(1, calls.get());
    }
    @Test void modelCannotChooseUnlistedOperation() {
        var unsafe = new AgentEngine(catalog, (o,d,m) -> new Planner.Proposal("deleteTenant", json.createObjectNode(), null),
            (o,a,t,c) -> { calls.incrementAndGet(); return new Executor.Result(200, "bad"); }, clock, Duration.ofMinutes(3), Duration.ofMinutes(30), 10);
        assertThrows(AgentException.class, () -> unsafe.message("alice", null, "delete")); assertEquals(0, calls.get());
    }
    @Test void previewMutationCannotChangeExecutedPayload() {
        engine = engine((op,args,auth,id) -> { assertEquals("Мария Иванова", args.path("body").path("fullName").asText()); return new Executor.Result(200, "ok"); });
        var ready = engine.message("alice", null, valid());
        ((com.fasterxml.jackson.databind.node.ObjectNode) ready.arguments().get("body")).put("fullName", "attacker");
        assertEquals("DONE", engine.confirm("alice", ready.sessionId(), ready.confirmationId(), "token").state());
    }
    @Test void boundsSessionsAndExpiresThem() {
        for (int i = 0; i < 10; i++) engine.message("alice", null, valid());
        assertEquals(429, assertThrows(AgentException.class, () -> engine.message("alice", null, valid())).status());
        clock.now = clock.now.plusSeconds(1801);
        assertEquals("READY_FOR_CONFIRMATION", engine.message("alice", null, valid()).state());
    }
    @Test void unrelatedSessionsDoNotWaitForSlowModel() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var concurrent = new AgentEngine(catalog, (ops, draft, message) -> {
            if (message.equals("slow")) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }
            return new Planner.Proposal(null, null, "Уточните");
        }, (o,a,t,c) -> new Executor.Result(200, "ok"), clock, Duration.ofMinutes(3), Duration.ofMinutes(30), 10);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var slow = pool.submit(() -> concurrent.message("alice", null, "slow"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            try { assertEquals("COLLECTING_FIELDS", pool.submit(() -> concurrent.message("bob", null, "fast")).get(1, TimeUnit.SECONDS).state()); }
            finally { release.countDown(); }
            slow.get(2, TimeUnit.SECONDS);
        }
    }
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-07T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; } public Clock withZone(ZoneId z) { return this; } public Instant instant() { return now; }
    }
}
