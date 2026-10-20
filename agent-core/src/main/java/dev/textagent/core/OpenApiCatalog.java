package dev.textagent.core;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.*;
import java.security.*;
import java.util.*;

/**
 * Immutable versioned catalogue. Inventory includes every operation; execution is explicit opt-in.
 */
public final class OpenApiCatalog {
  public record Entry(
      String id, String description, String method, String path, boolean enabled, String reason) {}

  private final Map<String, Operation> operations = new LinkedHashMap<>();
  private final List<Entry> inventory = new ArrayList<>();
  private final String version;

  public OpenApiCatalog(InputStream input, Set<String> allowed) throws IOException {
    byte[] bytes = input.readNBytes(4_000_001);
    if (bytes.length > 4_000_000) throw new IllegalArgumentException("OpenAPI exceeds 4 MB");
    JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(bytes);
    if (root == null || !root.path("openapi").asText().matches("3\\.[01]\\.[0-9]+"))
      throw new IllegalArgumentException("OpenAPI 3.0.x or 3.1.x required");
    try {
      version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    boolean v30 = root.path("openapi").asText().startsWith("3.0.");
    Set<String> selected = allowed == null ? Set.of() : Set.copyOf(allowed);
    if (selected.contains("*"))
      throw new IllegalArgumentException(
          "Wildcard execution is disabled; use x-agent-enabled or operation IDs");
    Set<String> ids = new HashSet<>();
    root.path("paths")
        .fields()
        .forEachRemaining(
            path -> {
              JsonNode pathItem = reference(root, path.getValue(), new HashSet<>());
              pathItem
                  .fields()
                  .forEachRemaining(
                      method -> {
                        if (!Set.of(
                                "get", "post", "put", "patch", "delete", "head", "options", "trace")
                            .contains(method.getKey())) return;
                        JsonNode op = method.getValue();
                        String id =
                            op.path("operationId").asText(method.getKey() + "_" + path.getKey());
                        if (!ids.add(id))
                          throw new IllegalArgumentException("Duplicate operationId: " + id);
                        String verb = method.getKey().toUpperCase(Locale.ROOT),
                            summary = op.path("summary").asText(id);
                        boolean enabled =
                            selected.isEmpty()
                                ? op.path("x-agent-enabled").asBoolean(false)
                                : selected.contains(id);
                        String reason =
                            enabled
                                ? "enabled"
                                : op.path("x-agent-disabled-reason")
                                    .asText("not approved for agent execution");
                        if (enabled) {
                          if (!op.hasNonNull("operationId") || !id.matches("[A-Za-z0-9_.-]{1,160}"))
                            throw new IllegalArgumentException("Stable operationId required");
                          if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(verb))
                            throw new IllegalArgumentException(
                                "Unsupported enabled method: " + verb);
                          if (!path.getKey().matches("/[A-Za-z0-9_/{}/-]+")
                              || path.getKey().contains("//"))
                            throw new IllegalArgumentException(
                                "Unsupported path: " + path.getKey());
                          ObjectNode schema = objectSchema(),
                              props = (ObjectNode) schema.get("properties");
                          ArrayNode required = (ArrayNode) schema.get("required");
                          ObjectNode pathSchema = objectSchema(), querySchema = objectSchema();
                          Map<String, JsonNode> parameters = new LinkedHashMap<>();
                          for (JsonNode list :
                              List.of(pathItem.path("parameters"), op.path("parameters")))
                            for (JsonNode raw : list) {
                              JsonNode p = reference(root, raw, new HashSet<>());
                              parameters.put(
                                  p.path("in").asText() + ":" + p.path("name").asText(), p);
                            }
                          for (JsonNode p : parameters.values()) {
                            String in = p.path("in").asText(), name = p.path("name").asText();
                            if (!Set.of("path", "query").contains(in)
                                || !name.matches("[A-Za-z_][A-Za-z0-9_-]*"))
                              throw new IllegalArgumentException(
                                  "Enabled operations support only path/query parameters");
                            JsonNode ps = reference(root, p.path("schema"), new HashSet<>());
                            if (!Set.of("string", "integer", "number", "boolean")
                                    .contains(ps.path("type").asText())
                                || p.path("allowReserved").asBoolean())
                              throw new IllegalArgumentException(
                                  "Unsupported parameter serialization");
                            if (p.has("style")
                                && !p.path("style")
                                    .asText()
                                    .equals(in.equals("path") ? "simple" : "form"))
                              throw new IllegalArgumentException("Unsupported style");
                            ObjectNode target = in.equals("path") ? pathSchema : querySchema;
                            ((ObjectNode) target.get("properties"))
                                .set(name, Schema.normalize(ps, v30));
                            if (in.equals("path") || p.path("required").asBoolean())
                              ((ArrayNode) target.get("required")).add(name);
                          }
                          var matcher =
                              java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(path.getKey());
                          Set<String> names = new HashSet<>();
                          while (matcher.find()) names.add(matcher.group(1));
                          Set<String> defined = new HashSet<>();
                          pathSchema.path("properties").fieldNames().forEachRemaining(defined::add);
                          if (!names.equals(defined))
                            throw new IllegalArgumentException("Path template mismatch");
                          props.set("path", pathSchema);
                          props.set("query", querySchema);
                          if (!names.isEmpty()) required.add("path");
                          if (!querySchema.path("required").isEmpty()) required.add("query");
                          if (op.has("requestBody")) {
                            if (verb.equals("GET"))
                              throw new IllegalArgumentException("GET request bodies unsupported");
                            JsonNode body = reference(root, op.get("requestBody"), new HashSet<>());
                            if (!body.path("content").has("application/json"))
                              throw new IllegalArgumentException(
                                  "Enabled operation requires JSON body: " + id);
                            props.set(
                                "body",
                                Schema.normalize(
                                    op.has("x-agent-input-schema")
                                        ? op.get("x-agent-input-schema")
                                        : body.path("content")
                                            .path("application/json")
                                            .path("schema"),
                                    v30));
                            if (body.path("required").asBoolean()) required.add("body");
                          }
                          // Include only referenced definitions; unrelated unsupported schemas do
                          // not block the approved catalogue.
                          copyDefinitions(root, schema, schema, v30, new HashSet<>());
                          Set<String> fields = new LinkedHashSet<>();
                          op.path("x-agent-response-fields").forEach(f -> fields.add(f.asText()));
                          operations.put(
                              id, new Operation(id, summary, verb, path.getKey(), schema, fields));
                        }
                        inventory.add(new Entry(id, summary, verb, path.getKey(), enabled, reason));
                      });
            });
    if (!operations.keySet().containsAll(selected))
      throw new IllegalArgumentException("Allowed operation missing from OpenAPI");
    if (operations.isEmpty())
      throw new IllegalArgumentException(
          "No operations approved: set allowed-operations or x-agent-enabled");
  }

  public String version() {
    return version;
  }

  public List<Entry> inventory() {
    return List.copyOf(inventory);
  }

  public List<Operation> operations() {
    return List.copyOf(operations.values());
  }

  public Operation require(String id) {
    Operation op = operations.get(id);
    if (op == null) throw new AgentException(422, "Операция не разрешена");
    return op;
  }

  private static ObjectNode objectSchema() {
    ObjectNode o = JsonNodeFactory.instance.objectNode();
    o.put("type", "object");
    o.putObject("properties");
    o.putArray("required");
    o.put("additionalProperties", false);
    return o;
  }

  private static JsonNode reference(JsonNode root, JsonNode node, Set<String> visited) {
    if (!node.has("$ref")) return node;
    String ref = node.path("$ref").asText();
    if (!ref.startsWith("#/components/") || !visited.add(ref))
      throw new IllegalArgumentException("Only local acyclic structural references supported");
    JsonNode result = root.at(ref.substring(1));
    if (result.isMissingNode()) throw new IllegalArgumentException("Unresolved reference");
    return reference(root, result, visited);
  }

  private static void copyDefinitions(
      JsonNode root, JsonNode node, ObjectNode target, boolean v30, Set<String> seen) {
    if (node.has("$ref")) {
      String ref = node.get("$ref").asText();
      String prefix =
          ref.startsWith("#/components/schemas/") ? "#/components/schemas/" : "#/$defs/";
      String key = ref.substring(prefix.length()).split("/", 2)[0];
      String definition = prefix + key;
      if (seen.add(definition)) {
        JsonNode original = root.at(definition.substring(1));
        if (original.isMissingNode() || root.at(ref.substring(1)).isMissingNode())
          throw new IllegalArgumentException("Unresolved schema: " + ref);
        JsonNode normalized = Schema.normalize(original, v30);
        ObjectNode container =
            prefix.equals("#/$defs/")
                ? target.withObject("/$defs")
                : target.withObject("/components/schemas");
        container.set(key.replace("~1", "/").replace("~0", "~"), normalized);
        copyDefinitions(root, normalized, target, v30, seen);
      }
    }
    // Walk schema positions, not property names or arbitrary example/default data.
    List<JsonNode> children = new ArrayList<>();
    for (String keyword : List.of("properties", "patternProperties", "dependentSchemas"))
      node.path(keyword).forEach(children::add);
    for (String keyword :
        List.of(
            "items",
            "additionalProperties",
            "unevaluatedProperties",
            "unevaluatedItems",
            "contains",
            "propertyNames",
            "not",
            "if",
            "then",
            "else")) if (node.has(keyword)) children.add(node.get(keyword));
    for (String keyword : List.of("allOf", "anyOf", "oneOf", "prefixItems"))
      node.path(keyword).forEach(children::add);
    children.forEach(child -> copyDefinitions(root, child, target, v30, seen));
  }
}
