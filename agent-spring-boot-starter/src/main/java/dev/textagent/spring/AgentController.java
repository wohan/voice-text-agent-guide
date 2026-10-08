package dev.textagent.spring;

import dev.textagent.core.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Uses the host application's authenticated Principal. Never accepts an owner ID from JSON. */
@RestController
@RequestMapping("${text-agent.api-path:/api/text-agent}")
public class AgentController {
    private final AgentEngine engine;
    public AgentController(AgentEngine engine) { this.engine = engine; }
    public record Message(String sessionId, String message) {}
    public record Confirmation(String confirmationId) {}
    @GetMapping("/operations")
    public Map<String,Object> operations(HttpServletRequest request) {
        owner(request); return Map.of("version",engine.catalogVersion(),"operations",engine.operations());
    }
    @PostMapping("/messages")
    public AgentEngine.View message(@RequestBody Message input, HttpServletRequest request) {
        return engine.message(owner(request), input.sessionId(), input.message());
    }
    @GetMapping("/sessions/{id}")
    public AgentEngine.View status(@PathVariable String id, HttpServletRequest request) { return engine.status(owner(request), id); }
    @PostMapping("/sessions/{id}/confirm")
    public AgentEngine.View confirm(@PathVariable String id, @RequestBody Confirmation input, HttpServletRequest request) {
        String owner = owner(request);
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ") || auth.length() > 8192) throw new AgentException(401, "Для выполнения нужен Bearer-токен пользователя");
        return engine.confirm(owner, id, input.confirmationId(), auth);
    }
    @PostMapping("/sessions/{id}/cancel")
    public AgentEngine.View cancel(@PathVariable String id, HttpServletRequest request) { return engine.cancel(owner(request), id); }
    @ExceptionHandler(AgentException.class)
    ResponseEntity<Map<String, String>> error(AgentException ex) { return ResponseEntity.status(ex.status()).body(Map.of("message", ex.getMessage())); }
    private String owner(HttpServletRequest request) {
        if (request.getUserPrincipal() == null) throw new AgentException(401, "Требуется авторизация");
        String owner = request.getUserPrincipal().getName();
        if (owner == null || owner.isBlank() || owner.equals("anonymousUser")) throw new AgentException(401, "Требуется авторизация");
        return owner;
    }
}
