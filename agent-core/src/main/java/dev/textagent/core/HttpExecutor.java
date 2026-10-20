package dev.textagent.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public final class HttpExecutor implements Executor {
  private static final Duration BACKEND_REQUEST_TIMEOUT = Duration.ofSeconds(20);
  private static final int MAX_BACKEND_RESPONSE_BYTES = 524_288;
  private static final int MAX_RESPONSE_ROWS = 50;
  private static final int MAX_RESPONSE_FIELD_CHARACTERS = 2_000;

  private final URI baseUri;
  private final HttpClient client;

  public HttpExecutor(URI baseUri) {
    if (!Set.of("http", "https").contains(baseUri.getScheme())
        || baseUri.getHost() == null
        || baseUri.getUserInfo() != null
        || baseUri.getQuery() != null
        || baseUri.getFragment() != null
        || !(baseUri.getPath().isEmpty() || baseUri.getPath().equals("/")))
      throw new IllegalArgumentException("Target must be a fixed HTTP(S) origin without a path");
    this.baseUri = baseUri;
    client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @Override
  public Result execute(
      Operation operation, JsonNode arguments, String authorization, String confirmationId) {
    String path = operation.path();
    Iterator<Map.Entry<String, JsonNode>> fields = arguments.path("path").fields();
    while (fields.hasNext()) {
      var pathParameter = fields.next();
      String value = pathParameter.getValue().asText();
      if (!value.matches("[A-Za-z0-9_-]+"))
        throw new AgentException(422, "Недопустимый идентификатор пути");
      path = path.replace("{" + pathParameter.getKey() + "}", value);
    }
    if (path.contains("{") || !path.startsWith("/") || path.startsWith("//"))
      throw new AgentException(422, "Некорректный путь");
    StringJoiner query = new StringJoiner("&");
    arguments
        .path("query")
        .fields()
        .forEachRemaining(
            queryParameter ->
                query.add(
                    encode(queryParameter.getKey())
                        + "="
                        + encode(queryParameter.getValue().asText())));
    URI uri = baseUri.resolve(path + (query.length() == 0 ? "" : "?" + query));
    if (!Objects.equals(uri.getHost(), baseUri.getHost())
        || uri.getPort() != baseUri.getPort()
        || !uri.getScheme().equals(baseUri.getScheme()))
      throw new AgentException(422, "Недопустимый адрес");
    var request =
        HttpRequest.newBuilder(uri)
            .timeout(BACKEND_REQUEST_TIMEOUT)
            .header("Accept", "application/json")
            .header("X-Actor-Channel", "AI_AGENT")
            .header("X-Agent-Intent", operation.id())
            .header("X-Agent-Confirmation-Id", confirmationId)
            .header("Idempotency-Key", confirmationId);
    if (authorization != null && !authorization.isBlank())
      request.header("Authorization", authorization);
    var body =
        arguments.has("body")
            ? HttpRequest.BodyPublishers.ofString(arguments.get("body").toString())
            : HttpRequest.BodyPublishers.noBody();
    if (arguments.has("body")) request.header("Content-Type", "application/json");
    try {
      // Keep only the configured projection; never send backend response bodies to the model.
      var response =
          HttpTransport.send(
              client,
              request.method(op.method(), body).build(),
              HttpTransport.limited(MAX_BACKEND_RESPONSE_BYTES),
              BACKEND_REQUEST_TIMEOUT);
      int status = response.statusCode();
      JsonNode data = null;
      if (status >= 200
          && status < 300
          && !operation.responseFields().isEmpty()
          && response.body().length > 0) {
        try {
          data =
              project(
                  new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()),
                  operation.responseFields());
        } catch (java.io.IOException ignored) {
          /* A malformed response must not make a completed write retryable. */
        }
      }
      String message =
          status == 202
              ? "Запрос принят сервером в обработку"
              : status >= 200 && status < 300
                  ? "Операция выполнена: " + operation.description()
                  : status >= 500
                      ? "Сервер вернул ошибку. Результат операции неизвестен; проверьте его в"
                          + " приложении"
                      : "Сервер отклонил запрос (HTTP " + status + "). Проверьте права и данные";
      return new Result(status, message, data);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new AgentException(502, "Запрос прерван");
    } catch (Exception ex) {
      throw new AgentException(502, "Ответ сервера не получен");
    }
  }

  private static JsonNode project(JsonNode value, Set<String> allowed) {
    var factory = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
    if (value.isArray()) {
      var result = factory.objectNode();
      result.put("total", value.size());
      var rows = result.putArray("items");
      for (int i = 0; i < Math.min(value.size(), MAX_RESPONSE_ROWS); i++)
        rows.add(project(value.get(i), allowed));
      result.put("truncated", value.size() > MAX_RESPONSE_ROWS);
      return result;
    }
    var result = factory.objectNode();
    if (value.isObject())
      for (String field : allowed) {
        JsonNode child = value.get(field);
        if (child != null && child.isValueNode()) {
          if (child.isTextual() && child.asText().length() > MAX_RESPONSE_FIELD_CHARACTERS)
            result.put(field, child.asText().substring(0, MAX_RESPONSE_FIELD_CHARACTERS));
          else result.set(field, child);
        }
      }
    return result;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
