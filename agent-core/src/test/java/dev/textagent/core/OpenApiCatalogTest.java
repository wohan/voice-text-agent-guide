package dev.textagent.core;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class OpenApiCatalogTest{
    String spec()throws IOException{try(var in=getClass().getResourceAsStream("/agent-openapi.yaml")){return new String(in.readAllBytes(),StandardCharsets.UTF_8);}}
    OpenApiCatalog load(String text,Set<String> allow)throws IOException{return new OpenApiCatalog(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),allow);}
    @Test void loadsLocalRefAndSupports31()throws Exception{assertEquals(1,load(spec(),Set.of("createTenant")).operations().size());assertEquals(1,load(spec().replace("3.0.3","3.1.0"),Set.of("createTenant")).operations().size());}
    @Test void rejectsExternalReferencesMissingAndImplicitApproval()throws Exception{
        String text=spec();assertThrows(IllegalArgumentException.class,()->load(text.replace("#/components/schemas/TenantInput","https://evil/schema"),Set.of("createTenant")));
        assertThrows(IllegalArgumentException.class,()->load(text,Set.of()));assertThrows(IllegalArgumentException.class,()->load(text,Set.of("missing")));assertThrows(IllegalArgumentException.class,()->load(text,Set.of("*")));
    }
    @Test void inventoriesDisabledOperationsButOnlyExposesApprovedSchemas()throws Exception{
        String text=spec().replace("      operationId: createTenant","      operationId: createTenant\n      x-agent-enabled: true").replace("    post:","    delete:\n      operationId: deleteTenants\n    post:");
        var catalog=load(text,Set.of());assertEquals(2,catalog.inventory().size());assertEquals(1,catalog.operations().size());assertThrows(AgentException.class,()->catalog.require("deleteTenants"));
    }
    @Test void readOnlyFieldsCannotBeSubmitted()throws Exception{
        var catalog=load(spec().replace("        email:","        secret:\n          type: string\n          readOnly: true\n        email:"),Set.of("createTenant"));
        var value=new ObjectMapper().readTree("{\"body\":{\"fullName\":\"Test\",\"phone\":\"+15555550123\",\"secret\":\"bad\"}}");
        assertFalse(catalog.require("createTenant").validate(value).isEmpty());
    }
    @Test void compositionsAndNullableWork()throws Exception{
        String text=spec().replace("          type: string\n          format: email","          allOf:\n            - type: string\n              format: email\n          nullable: false");
        var op=load(text,Set.of("createTenant")).require("createTenant");var json=new ObjectMapper();
        assertFalse(op.validate(json.readTree("{\"body\":{\"fullName\":\"Test\",\"phone\":\"+15555550123\",\"email\":\"invalid\"}}")).isEmpty());
    }
    @Test void referencesInPropertiesNamedLikeSchemaKeywordsAreResolved() throws Exception {
        String text="""
          openapi: 3.1.0
          paths:
            /test:
              post:
                operationId: test
                x-agent-enabled: true
                requestBody:
                  required: true
                  content:
                    application/json:
                      schema:
                        type: object
                        properties:
                          default:
                            $ref: '#/components/schemas/Name'
          components:
            schemas:
              Name:
                type: string
                minLength: 3
          """;
        var op=load(text,Set.of()).require("test");
        assertFalse(op.validate(new ObjectMapper().readTree("{\"body\":{\"default\":\"x\"}}")).isEmpty());
    }
}
