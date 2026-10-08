package dev.textagent.core;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class RentalCatalogTest {
    @Test void loadsFullSnapshotAndValidatesRealConstraints() throws Exception {
        var catalog=new OpenApiCatalog(getClass().getResourceAsStream("/rental-openapi.json"),Set.of());
        var json=new ObjectMapper();
        assertTrue(catalog.inventory().size()>=50);assertEquals(19,catalog.operations().size());
        var tenant=catalog.require("createTenant");
        assertTrue(tenant.validate(json.readTree("{\"body\":{\"fullName\":\"Иван\",\"phone\":\"+79001112233\"}}")).isEmpty());
        assertFalse(tenant.validate(json.readTree("{\"body\":{\"fullName\":\"Иван\",\"phone\":\"+79001112233\",\"accountId\":\"other\"}}")).isEmpty());
        assertFalse(tenant.validate(json.readTree("{\"body\":{\"fullName\":\"Иван\",\"phone\":\"+79001112233\",\"email\":\"invalid\"}}")).isEmpty());
        assertThrows(AgentException.class,()->catalog.require("deleteTenant"));
        assertFalse(catalog.require("createContract").validate(json.readTree("{\"body\":{\"paymentDay\":32}}")).isEmpty());
        assertTrue(catalog.require("listTenants").validate(json.readTree("{}")).isEmpty());
    }
}
