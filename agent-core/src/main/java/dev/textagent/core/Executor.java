package dev.textagent.core;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface Executor {
  Result execute(
      Operation operation, JsonNode arguments, String authorization, String confirmationId);

  record Result(int status, String message, JsonNode data) {
    public Result(int status, String message) {
      this(status, message, null);
    }

    public Result {
      data = data == null ? null : data.deepCopy();
    }

    @Override
    public JsonNode data() {
      return data == null ? null : data.deepCopy();
    }
  }
}
