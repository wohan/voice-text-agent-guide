package dev.textagent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import java.util.*;

public final class Operation {
  private final String id, description, method, path;
  private final JsonNode schema;
  private final JsonSchema compiled;
  private final Set<String> responseFields;

  public Operation(String id, String description, String method, String path, JsonNode schema) {
    this(id, description, method, path, schema, Set.of());
  }

  public Operation(
      String id,
      String description,
      String method,
      String path,
      JsonNode schema,
      Set<String> responseFields) {
    this.id = id;
    this.description = description;
    this.method = method;
    this.path = path;
    this.schema = schema.deepCopy();
    compiled = Schema.compile(this.schema);
    this.responseFields = Set.copyOf(responseFields);
  }

  public String id() {
    return id;
  }

  public String description() {
    return description;
  }

  public String method() {
    return method;
  }

  public String path() {
    return path;
  }

  public JsonNode schema() {
    return schema.deepCopy();
  }

  public Set<String> responseFields() {
    return responseFields;
  }

  public List<String> validate(JsonNode arguments) {
    return Schema.validate(compiled, arguments);
  }
}
