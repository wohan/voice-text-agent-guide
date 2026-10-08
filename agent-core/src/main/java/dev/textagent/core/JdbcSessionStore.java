package dev.textagent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.sql.*;
import java.util.UUID;

/** PostgreSQL/H2 CAS storage; no database transaction spans model or business HTTP calls. */
public final class JdbcSessionStore implements SessionStore {
    private final DataSource dataSource; private final StateCipher cipher;
    private final ObjectMapper mapper = new ObjectMapper();
    public JdbcSessionStore(DataSource dataSource, StateCipher cipher) {
        this.dataSource = dataSource; this.cipher = cipher;
        transaction(c -> {
            try(var statement=c.createStatement()) {
                try(var rows=statement.executeQuery("SELECT id FROM agent_admission WHERE id=1")) {
                    if(!rows.next())throw new AgentException(503,"Не применена миграция хранилища агента");
                }
                for(String table:java.util.List.of("agent_sessions","agent_audit","agent_rate_limits"))
                    statement.executeQuery("SELECT * FROM "+table+" WHERE 1=0").close();
            }
            return null;
        });
    }
    public void create(SessionState state, long now, int globalLimit, int ownerLimit) {
        transaction(c -> {
            try (var lock = c.prepareStatement("SELECT id FROM agent_admission WHERE id=1 FOR UPDATE")) { lock.executeQuery().close(); }
            try (var delete = c.prepareStatement("DELETE FROM agent_sessions WHERE expires_at<=?")) { delete.setLong(1,now); delete.executeUpdate(); }
            try (var count = c.prepareStatement("SELECT COUNT(*), COALESCE(SUM(CASE WHEN owner_hash=? THEN 1 ELSE 0 END),0) FROM agent_sessions")) {
                count.setString(1,cipher.ownerHash(state.owner())); try(var rs=count.executeQuery()) {
                    rs.next(); if (rs.getInt(1)>=globalLimit || rs.getInt(2)>=ownerLimit) throw new AgentException(429,"Лимит сессий. Попробуйте позже");
                }
            }
            try(var stmt=c.prepareStatement("INSERT INTO agent_sessions(id,owner_hash,revision,expires_at,payload) VALUES(?,?,?,?,?)")) {
                stmt.setString(1,state.id());stmt.setString(2,cipher.ownerHash(state.owner()));stmt.setLong(3,state.revision());stmt.setLong(4,state.expiresAt());stmt.setString(5,encode(state));stmt.executeUpdate();
            }
            audit(c,state);return null;
        });
    }
    public SessionState get(String owner,String id,long now) {
        return transaction(c -> {
            try(var stmt=c.prepareStatement("SELECT payload FROM agent_sessions WHERE id=? AND owner_hash=? AND expires_at>?")) {
                stmt.setString(1,id);stmt.setString(2,cipher.ownerHash(owner));stmt.setLong(3,now);
                try(var rs=stmt.executeQuery()) { if(!rs.next()) throw new AgentException(404,"Сессия не найдена"); return mapper.readValue(cipher.decrypt(id,rs.getString(1)),SessionState.class); }
            }
        });
    }
    public boolean replace(SessionState old,SessionState next) {
        return transaction(c -> {
            try(var stmt=c.prepareStatement("UPDATE agent_sessions SET revision=?,expires_at=?,payload=? WHERE id=? AND owner_hash=? AND revision=?")) {
                stmt.setLong(1,next.revision());stmt.setLong(2,next.expiresAt());stmt.setString(3,encode(next));stmt.setString(4,old.id());stmt.setString(5,cipher.ownerHash(old.owner()));stmt.setLong(6,old.revision());
                if(stmt.executeUpdate()!=1) return false;
            }
            audit(c,next);return true;
        });
    }
    public void consume(String owner,long now,int limit) {
        transaction(c -> {
            // Admission lock makes first-use insertion portable and bounds cardinality to active buckets.
            try(var lock=c.prepareStatement("SELECT id FROM agent_admission WHERE id=1 FOR UPDATE")) { lock.executeQuery().close(); }
            long bucket=now/60000; String hash=cipher.ownerHash(owner);
            try(var del=c.prepareStatement("DELETE FROM agent_rate_limits WHERE bucket<?")) { del.setLong(1,bucket);del.executeUpdate(); }
            int used=0;
            try(var get=c.prepareStatement("SELECT requests FROM agent_rate_limits WHERE owner_hash=?")) { get.setString(1,hash);try(var rs=get.executeQuery()){if(rs.next())used=rs.getInt(1);} }
            if(used>=limit)throw new AgentException(429,"Слишком много запросов. Подождите минуту");
            if(used==0) { try(var add=c.prepareStatement("INSERT INTO agent_rate_limits(owner_hash,bucket,requests) VALUES(?,?,1)")){add.setString(1,hash);add.setLong(2,bucket);add.executeUpdate();} }
            else {try(var update=c.prepareStatement("UPDATE agent_rate_limits SET requests=requests+1 WHERE owner_hash=?")){update.setString(1,hash);update.executeUpdate();}}
            return null;
        });
    }
    public int cleanup(long now) {
        return transaction(c -> {
            try(var audit=c.prepareStatement("DELETE FROM agent_audit WHERE event_time<?")){audit.setLong(1,now-30L*86400000);audit.executeUpdate();}
            try(var rate=c.prepareStatement("DELETE FROM agent_rate_limits WHERE bucket<?")){rate.setLong(1,now/60000);rate.executeUpdate();}
            try(var stmt=c.prepareStatement("DELETE FROM agent_sessions WHERE expires_at<=?")){stmt.setLong(1,now);return stmt.executeUpdate();}
        });
    }
    private String encode(SessionState state)throws Exception{return cipher.encrypt(state.id(),mapper.writeValueAsBytes(state));}
    private void audit(Connection c,SessionState s)throws SQLException{
        try(var stmt=c.prepareStatement("INSERT INTO agent_audit(id,session_id,owner_hash,event_time,phase,operation_id,confirmation_id,http_status) VALUES(?,?,?,?,?,?,?,?)")){
            stmt.setString(1,UUID.randomUUID().toString());stmt.setString(2,s.id());stmt.setString(3,cipher.ownerHash(s.owner()));stmt.setLong(4,s.updatedAt());stmt.setString(5,s.phase());stmt.setString(6,s.operationId());stmt.setString(7,s.confirmationId());stmt.setObject(8,s.httpStatus());stmt.executeUpdate();
        }
    }
    private interface Work<T>{T run(Connection c)throws Exception;}
    private <T>T transaction(Work<T> work){
        try(var c=dataSource.getConnection()){
            c.setAutoCommit(false);
            try{T result=work.run(c);c.commit();return result;}
            catch(Exception e){c.rollback();if(e instanceof AgentException a)throw a;throw new AgentException(503,"Хранилище агента недоступно");}
        }catch(SQLException e){throw new AgentException(503,"Хранилище агента недоступно");}
    }
}
