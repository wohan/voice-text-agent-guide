package dev.textagent.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public final class HttpExecutor implements Executor {
    private final URI base; private final HttpClient client;
    public HttpExecutor(URI base) {
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
            || base.getQuery() != null || base.getFragment() != null || !(base.getPath().isEmpty() || base.getPath().equals("/")))
            throw new IllegalArgumentException("Target must be a fixed HTTP(S) origin without a path");
        this.base = base; client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @Override public Result execute(Operation op, JsonNode args, String authorization, String confirmationId) {
        String path = op.path();
        Iterator<Map.Entry<String, JsonNode>> fields = args.path("path").fields();
        while (fields.hasNext()) { var p = fields.next(); String value = p.getValue().asText();
            if (!value.matches("[A-Za-z0-9_-]+")) throw new AgentException(422, "Недопустимый идентификатор пути");
            path = path.replace("{" + p.getKey() + "}", value);
        }
        if (path.contains("{") || !path.startsWith("/") || path.startsWith("//")) throw new AgentException(422, "Некорректный путь");
        StringJoiner query = new StringJoiner("&"); args.path("query").fields().forEachRemaining(p -> query.add(encode(p.getKey()) + "=" + encode(p.getValue().asText())));
        URI uri = base.resolve(path + (query.length() == 0 ? "" : "?" + query));
        if (!Objects.equals(uri.getHost(), base.getHost()) || uri.getPort() != base.getPort() || !uri.getScheme().equals(base.getScheme())) throw new AgentException(422, "Недопустимый адрес");
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Accept", "application/json")
            .header("X-Actor-Channel", "AI_AGENT").header("X-Agent-Intent", op.id()).header("X-Agent-Confirmation-Id", confirmationId)
            .header("Idempotency-Key", confirmationId);
        if (authorization != null && !authorization.isBlank()) request.header("Authorization", authorization);
        var body = args.has("body") ? HttpRequest.BodyPublishers.ofString(args.get("body").toString()) : HttpRequest.BodyPublishers.noBody();
        if (args.has("body")) request.header("Content-Type", "application/json");
        try {
            // Keep only the configured projection; never send backend response bodies to the model.
            var response = HttpTransport.send(client, request.method(op.method(), body).build(), HttpTransport.limited(524288), Duration.ofSeconds(20));
            int status = response.statusCode();
            JsonNode data = null;
            if (status >= 200 && status < 300 && !op.responseFields().isEmpty() && response.body().length > 0) {
                try { data = project(new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()), op.responseFields()); }
                catch (java.io.IOException ignored) { /* A malformed response must not make a completed write retryable. */ }
            }
            String message = status == 202 ? "Запрос принят сервером в обработку" : status >= 200 && status < 300 ? "Операция выполнена: " + op.description()
                : status >= 500 ? "Сервер вернул ошибку. Результат операции неизвестен; проверьте его в приложении"
                : "Сервер отклонил запрос (HTTP " + status + "). Проверьте права и данные";
            return new Result(status, message, data);
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AgentException(502, "Запрос прерван"); }
        catch (Exception ex) { throw new AgentException(502, "Ответ сервера не получен"); }
    }
    private static JsonNode project(JsonNode value, Set<String> allowed) {
        var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        if (value.isArray()) {
            var result = factory.objectNode(); result.put("total", value.size()); var rows = result.putArray("items");
            for (int i=0; i<Math.min(value.size(),50); i++) rows.add(project(value.get(i),allowed));
            result.put("truncated", value.size()>50); return result;
        }
        var result=factory.objectNode();
        if (value.isObject()) for (String field : allowed) {
            JsonNode child=value.get(field);
            if (child!=null&&child.isValueNode()) {
                if(child.isTextual()&&child.asText().length()>2000)result.put(field,child.asText().substring(0,2000));else result.set(field,child);
            }
        }
        return result;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
}
