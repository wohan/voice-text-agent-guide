package dev.textagent.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Persisted state. Tokens and raw conversation history never enter this object. */
public record SessionState(
    String id,
    String owner,
    long revision,
    long updatedAt,
    long expiresAt,
    String phase,
    String operationId,
    String catalogVersion,
    JsonNode arguments,
    String confirmationId,
    long confirmationExpiresAt,
    String reply,
    List<String> errors,
    Integer httpStatus,
    JsonNode result) {
  public SessionState {
    arguments = arguments == null ? null : arguments.deepCopy();
    result = result == null ? null : result.deepCopy();
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  @Override
  public JsonNode arguments() {
    return arguments == null ? null : arguments.deepCopy();
  }

  @Override
  public JsonNode result() {
    return result == null ? null : result.deepCopy();
  }
}
