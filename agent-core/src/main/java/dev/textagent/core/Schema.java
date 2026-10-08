package dev.textagent.core;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import java.util.*;

/** JSON Schema validation is delegated to NetworkNT; schemas are compiled once per operation. */
public final class Schema {
    private Schema() {}
    private static final JsonSchemaFactory FACTORY=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    public static JsonSchema compile(JsonNode schema) {
        SchemaValidatorsConfig config=SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        JsonSchema compiled=FACTORY.getSchema(schema,config); compiled.initializeValidators(); return compiled;
    }
    public static void check(JsonNode schema) { compile(schema); }
    public static List<String> validate(JsonNode schema,JsonNode value) { return validate(compile(schema),value); }
    public static List<String> validate(JsonSchema compiled,JsonNode value) {
        return compiled.validate(value).stream().map(m -> m.getMessage()).sorted().limit(40).toList();
    }
    public static JsonNode normalize(JsonNode node,boolean openApi30) {
        if(node.isArray()){ArrayNode a=JsonNodeFactory.instance.arrayNode();node.forEach(v->a.add(normalize(v,openApi30)));return a;}
        if(!node.isObject())return node.deepCopy();
        ObjectNode out=JsonNodeFactory.instance.objectNode();
        node.fields().forEachRemaining(f->{
            String key=f.getKey();
            if(Set.of("$id","$dynamicRef","$recursiveRef").contains(key))throw new IllegalArgumentException("Unsupported schema reference keyword: "+key);
            if(key.equals("$ref")){
                String ref=f.getValue().asText();
                if(!ref.startsWith("#/components/schemas/") && !ref.startsWith("#/$defs/"))throw new IllegalArgumentException("Only local schema references supported");
                out.put(key,ref);
            }else if(Set.of("properties","patternProperties","$defs","dependentSchemas").contains(key)) {
                ObjectNode schemas=out.putObject(key);
                f.getValue().fields().forEachRemaining(entry->schemas.set(entry.getKey(),normalize(entry.getValue(),openApi30)));
            }else if(Set.of("items","additionalProperties","unevaluatedProperties","unevaluatedItems","contains","propertyNames","not","if","then","else","allOf","anyOf","oneOf","prefixItems").contains(key)) {
                out.set(key,normalize(f.getValue(),openApi30));
            }else if(!Set.of("nullable","example","xml","discriminator","$schema").contains(key))out.set(key,f.getValue().deepCopy());
        });
        // readOnly fields must not be submitted, even if the model knows their names.
        if(out.path("properties").isObject()){
            Set<String> readOnly=new HashSet<>();
            out.get("properties").fields().forEachRemaining(f->{if(f.getValue().path("readOnly").asBoolean())readOnly.add(f.getKey());});
            readOnly.forEach(k->((ObjectNode)out.get("properties")).put(k,false));
            if(out.path("required").isArray()){ArrayNode required=out.putArray("required");node.path("required").forEach(v->{if(!readOnly.contains(v.asText()))required.add(v);});}
        }
        if(openApi30){
            for(String bound:List.of("Minimum","Maximum"))if(out.path("exclusive"+bound).isBoolean()){
                boolean exclusive=out.remove("exclusive"+bound).asBoolean();String ordinary=bound.toLowerCase(Locale.ROOT);
                if(exclusive&&out.has(ordinary))out.set("exclusive"+bound,out.remove(ordinary));
            }
            if(node.path("nullable").asBoolean()&&out.has("type")){
                JsonNode type=out.remove("type");ArrayNode types=out.putArray("type");if(type.isArray())type.forEach(types::add);else types.add(type);types.add("null");
            }
        }
        return out;
    }
}
