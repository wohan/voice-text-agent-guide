package dev.textagent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JdbcSessionStoreTest {
    javax.sql.DataSource db; String pgSchema; JdbcSessionStore first, second;
    final ObjectMapper json=new ObjectMapper();
    final AgentEngineTest.TestClock clock=new AgentEngineTest.TestClock();
    OpenApiCatalog catalog; AtomicInteger calls=new AtomicInteger();
    @BeforeEach void setup() throws Exception {
        String pgUrl=System.getenv("AGENT_TEST_PG_URL");
        if(pgUrl==null) {
            var h2=new JdbcDataSource();h2.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");db=h2;
        }else {
            var pg=new org.postgresql.ds.PGSimpleDataSource();pg.setURL(pgUrl);
            pg.setUser(System.getenv("AGENT_TEST_PG_USER"));pg.setPassword(System.getenv("AGENT_TEST_PG_PASSWORD"));
            pgSchema="agent_test_"+UUID.randomUUID().toString().replace("-","");
            try(var c=pg.getConnection();var statement=c.createStatement()){statement.execute("CREATE SCHEMA "+pgSchema);}
            pg.setCurrentSchema(pgSchema);db=pg;
        }
        try(var c=db.getConnection();var s=c.createStatement()) {
            String sql=new String(getClass().getResourceAsStream("/dev/textagent/schema.sql").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            for(String statement:sql.split(";"))if(!statement.isBlank())s.execute(statement);
        }
        String key=Base64.getEncoder().encodeToString(new byte[32]);
        first=new JdbcSessionStore(db,new StateCipher(key));second=new JdbcSessionStore(db,new StateCipher(key));
        catalog=new OpenApiCatalog(getClass().getResourceAsStream("/agent-openapi.yaml"),Set.of("createTenant"));
    }
    @AfterEach void cleanupDatabase() throws Exception {
        if(pgSchema!=null)try(var c=db.getConnection();var s=c.createStatement()){s.execute("DROP SCHEMA "+pgSchema+" CASCADE");}
    }
    AgentEngine engine(SessionStore store,Executor executor) {
        return new AgentEngine(catalog,(o,d,m)->{try{return new Planner.Proposal("createTenant",json.readTree(m),null);}catch(Exception e){throw new RuntimeException(e);}},
            executor,store,clock,Duration.ofMinutes(3),Duration.ofHours(24),100,20,100);
    }
    Executor executor(){return (o,a,t,c)->{calls.incrementAndGet();return new Executor.Result(200,"Готово");};}
    String valid(){return "{\"body\":{\"fullName\":\"Private Name\",\"phone\":\"+79001112233\"}}";}
    @Test void survivesRestartAndDoesNotReplayCompletedCommand() throws Exception {
        var ready=engine(first,executor()).message("private-owner",null,valid());
        var restarted=engine(second,executor());
        assertEquals("READY_FOR_CONFIRMATION",restarted.status("private-owner",ready.sessionId()).state());
        assertEquals("DONE",restarted.confirm("private-owner",ready.sessionId(),ready.confirmationId(),"secret-token").state());
        assertEquals("DONE",engine(first,executor()).confirm("private-owner",ready.sessionId(),ready.confirmationId(),"secret-token").state());
        assertEquals(1,calls.get());
        try(var c=db.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT owner_hash,payload FROM agent_sessions")) {
            assertTrue(rows.next());assertNotEquals("private-owner",rows.getString(1));
            for(String secret:List.of("Private Name","+79001112233","secret-token","private-owner"))assertFalse(rows.getString(2).contains(secret));
        }
        try(var c=db.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT COUNT(*) FROM agent_audit")){rows.next();assertEquals(5,rows.getInt(1));}
        assertEquals(404,assertThrows(AgentException.class,()->restarted.status("other",ready.sessionId())).status());
    }
    @Test void twoInstancesDispatchOnlyOnceWhileFirstIsBlocked() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var a=engine(first,(o,args,t,id)->{calls.incrementAndGet();entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return new Executor.Result(200,"ok");});
        var b=engine(second,executor());var ready=a.message("owner",null,valid());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var attempt=pool.submit(()->a.confirm("owner",ready.sessionId(),ready.confirmationId(),"token"));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            try {assertEquals("EXECUTING",b.confirm("owner",ready.sessionId(),ready.confirmationId(),"token").state());assertEquals(1,calls.get());}
            finally {release.countDown();}
            assertEquals("DONE",attempt.get(3,TimeUnit.SECONDS).state());
        }
    }
    @Test void crashedExecutionBecomesUnknownWithoutDispatch() {
        var engine=engine(first,executor());var ready=engine.message("owner",null,valid());
        var s=first.get("owner",ready.sessionId(),clock.millis());
        var executing=new SessionState(s.id(),s.owner(),s.revision()+1,s.updatedAt(),s.expiresAt(),"EXECUTING",s.operationId(),s.catalogVersion(),s.arguments(),s.confirmationId(),s.confirmationExpiresAt(),"",List.of(),null,null);
        assertTrue(first.replace(s,executing));assertFalse(second.replace(s,executing));
        clock.now=clock.now.plusSeconds(121);
        assertEquals("UNKNOWN",engine(second,executor()).confirm("owner",ready.sessionId(),ready.confirmationId(),"token").state());assertEquals(0,calls.get());
    }
    @Test void quotasAreSharedAndExpire() {
        first.consume("owner",clock.millis(),2);second.consume("owner",clock.millis(),2);
        assertEquals(429,assertThrows(AgentException.class,()->first.consume("owner",clock.millis(),2)).status());
        second.consume("other",clock.millis(),2);clock.now=clock.now.plusSeconds(60);first.consume("owner",clock.millis(),2);
    }
    @Test void changedCatalogInvalidatesPendingConfirmation() throws Exception {
        var ready=engine(first,executor()).message("owner",null,valid());
        String spec=new String(getClass().getResourceAsStream("/agent-openapi.yaml").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)+"\n# changed version\n";
        catalog=new OpenApiCatalog(new java.io.ByteArrayInputStream(spec.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Set.of("createTenant"));
        assertEquals("EXPIRED",engine(second,executor()).status("owner",ready.sessionId()).state());
        assertThrows(AgentException.class,()->engine(second,executor()).confirm("owner",ready.sessionId(),ready.confirmationId(),"token"));assertEquals(0,calls.get());
    }
    @Test void wrongEncryptionKeyFailsClosed() {
        var ready=engine(first,executor()).message("owner",null,valid());
        byte[] key=new byte[32];Arrays.fill(key,(byte)1);
        var cipher=new StateCipher(Base64.getEncoder().encodeToString(key));
        // Cipher authentication fails even if an encrypted row is copied under another owner index.
        String payload;
        try(var c=db.getConnection();var s=c.createStatement();var rows=s.executeQuery("SELECT payload FROM agent_sessions")){rows.next();payload=rows.getString(1);}catch(Exception e){throw new RuntimeException(e);}
        String stored=payload;assertThrows(AgentException.class,()->cipher.decrypt(ready.sessionId(),stored));assertEquals(0,calls.get());
    }
}
