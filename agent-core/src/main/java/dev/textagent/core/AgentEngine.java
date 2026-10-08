package dev.textagent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.*;
import java.util.*;

/** State transitions use optimistic CAS; no lock or transaction spans external HTTP calls. */
public final class AgentEngine {
    public record View(String sessionId,String state,String reply,List<String> errors,String confirmationId,
        String expiresAt,String operationId,String method,String path,JsonNode arguments,Integer httpStatus,
        JsonNode result,String catalogVersion) {}
    private static final Set<String> TERMINAL=Set.of("DONE","FAILED","UNKNOWN","CANCELLED","EXPIRED");
    private final OpenApiCatalog catalog;private final Planner planner;private final Executor executor;private final SessionStore store;
    private final Clock clock;private final Duration confirmationTtl,sessionTtl;private final int maxSessions,ownerLimit,requestsPerMinute;
    public AgentEngine(OpenApiCatalog catalog,Planner planner,Executor executor,Clock clock,Duration confirmationTtl,Duration sessionTtl,int maxSessions){
        this(catalog,planner,executor,new MemorySessionStore(),clock,confirmationTtl,sessionTtl,maxSessions,20,60);
    }
    public AgentEngine(OpenApiCatalog catalog,Planner planner,Executor executor,SessionStore store,Clock clock,
                       Duration confirmationTtl,Duration sessionTtl,int maxSessions,int ownerLimit,int requestsPerMinute){
        if(confirmationTtl.isNegative()||confirmationTtl.isZero()||sessionTtl.compareTo(confirmationTtl)<0||sessionTtl.toMillis()<180000||maxSessions<1||ownerLimit<1||requestsPerMinute<1)
            throw new IllegalArgumentException("Invalid session limits");
        this.catalog=catalog;this.planner=planner;this.executor=executor;this.store=store;this.clock=clock;this.confirmationTtl=confirmationTtl;
        this.sessionTtl=sessionTtl;this.maxSessions=maxSessions;this.ownerLimit=ownerLimit;this.requestsPerMinute=requestsPerMinute;
    }
    public List<OpenApiCatalog.Entry> operations(){return catalog.inventory();}
    public String catalogVersion(){return catalog.version();}
    public View message(String owner,String sessionId,String message){
        requireOwner(owner);
        if(message==null||message.isBlank()||message.length()>8000)throw new AgentException(400,"Введите сообщение длиной до 8000 символов");
        store.consume("command:"+owner,now(),requestsPerMinute);
        SessionState s=sessionId==null?create(owner):recover(store.get(owner,sessionId,now()));
        if(Set.of("PLANNING","EXECUTING").contains(s.phase()))throw new AgentException(409,"Дождитесь завершения текущего запроса");
        JsonNode previous=JsonNodeFactory.instance.objectNode();
        if(s.arguments()!=null&&!TERMINAL.contains(s.phase()))previous=JsonNodeFactory.instance.objectNode().put("operationId",s.operationId()).set("arguments",s.arguments());
        SessionState planning=transition(s,"PLANNING",null,s.operationId(),s.arguments(),"Уточняю запрос",List.of(),null,null);
        save(s,planning); // Persist invalidation BEFORE talking to the model.
        try{
            Planner.Proposal p=planner.plan(catalog.operations(),previous,message);
            if(p==null)throw new AgentException(502,"Модель не вернула предложение");
            SessionState next;
            if(p.operationId()==null||p.operationId().isBlank())next=transition(planning,"COLLECTING_FIELDS",null,null,null,question(p.question()),List.of(),null,null);
            else{
                Operation op=catalog.require(p.operationId());JsonNode args=p.arguments();
                if(args==null||!args.isObject()||args.toString().length()>32000)throw new AgentException(502,"Некорректный ответ модели");
                List<String> errors=op.validate(args);
                next=errors.isEmpty()?transition(planning,"READY_FOR_CONFIRMATION",UUID.randomUUID().toString(),op.id(),args,"Подтвердите: "+op.description(),List.of(),null,null)
                    :transition(planning,"COLLECTING_FIELDS",null,op.id(),args,"Уточните данные для операции «"+op.description()+"»",errors,null,null);
            }
            save(planning,next);return view(next);
        }catch(RuntimeException ex){
            store.replace(planning,transition(planning,"COLLECTING_FIELDS",null,planning.operationId(),planning.arguments(),"Не удалось подготовить запрос. Попробуйте ещё раз",List.of(),null,null));
            throw ex;
        }
    }
    public View confirm(String owner,String sessionId,String confirmationId,String authorization){
        requireOwner(owner);store.consume("command:"+owner,now(),requestsPerMinute);
        SessionState s=recover(store.get(owner,sessionId,now()));
        if(confirmationId==null||!confirmationId.equals(s.confirmationId()))throw new AgentException(409,"Подтверждение устарело");
        if(TERMINAL.contains(s.phase())||s.phase().equals("EXECUTING"))return view(s);
        if(!s.phase().equals("READY_FOR_CONFIRMATION"))throw new AgentException(409,"Действие не готово");
        if(s.confirmationExpiresAt()<=now()||!catalog.version().equals(s.catalogVersion())){
            store.replace(s,transition(s,"EXPIRED",null,s.operationId(),s.arguments(),"Контракт или срок подтверждения изменился. Отправьте запрос заново",List.of(),null,null));
            throw new AgentException(409,"Подтверждение устарело. Отправьте запрос заново");
        }
        Operation op=catalog.require(s.operationId());
        if(!op.validate(s.arguments()).isEmpty())throw new AgentException(422,"Данные не прошли проверку");
        SessionState executing=transition(s,"EXECUTING",confirmationId,s.operationId(),s.arguments(),"Выполняется",List.of(),null,null);
        if(!store.replace(s,executing))return status(owner,sessionId);
        // Only the CAS winner may execute. Crashed/expired EXECUTING states are NEVER retried.
        SessionState result;
        try{
            Executor.Result response=executor.execute(op,s.arguments(),authorization,confirmationId);
            String phase=response.status()>=200&&response.status()<300?"DONE":response.status()>=500?"UNKNOWN":"FAILED";
            result=transition(executing,phase,confirmationId,s.operationId(),s.arguments(),response.message(),List.of(),response.status(),response.data());
        }catch(RuntimeException ex){result=transition(executing,"UNKNOWN",confirmationId,s.operationId(),s.arguments(),"Ответ не получен. Проверьте результат в приложении перед новой операцией",List.of(),null,null);}
        save(executing,result);return view(result);
    }
    public View cancel(String owner,String sessionId){
        requireOwner(owner);store.consume("command:"+owner,now(),requestsPerMinute);SessionState s=recover(store.get(owner,sessionId,now()));
        if(s.phase().equals("EXECUTING")||TERMINAL.contains(s.phase())&&!s.phase().equals("CANCELLED"))throw new AgentException(409,"Выполненную попытку нельзя отменить");
        SessionState next=transition(s,"CANCELLED",null,null,null,"Действие отменено",List.of(),null,null);save(s,next);return view(next);
    }
    public View status(String owner,String sessionId){requireOwner(owner);store.consume("status:"+owner,now(),requestsPerMinute*3);return view(recover(store.get(owner,sessionId,now())));}
    public int cleanup(){return store.cleanup(now());}
    private SessionState recover(SessionState s){
        String phase=null,reply=null;
        if(s.phase().equals("EXECUTING")&&now()-s.updatedAt()>120000){phase="UNKNOWN";reply="Попытка выполнения прервана. Проверьте результат в приложении; автоматического повтора не будет";}
        else if(s.phase().equals("PLANNING")&&now()-s.updatedAt()>120000){phase="COLLECTING_FIELDS";reply="Подготовка запроса прервана. Отправьте сообщение заново";}
        else if(s.phase().equals("READY_FOR_CONFIRMATION")&&(s.confirmationExpiresAt()<=now()||!s.catalogVersion().equals(catalog.version()))){phase="EXPIRED";reply="Подтверждение устарело. Отправьте запрос заново";}
        if(phase==null)return s;
        SessionState next=transition(s,phase,phase.equals("UNKNOWN")?s.confirmationId():null,s.operationId(),s.arguments(),reply,List.of(),s.httpStatus(),s.result());
        return store.replace(s,next)?next:store.get(s.owner(),s.id(),now());
    }
    private SessionState create(String owner){
        long now=now();SessionState s=new SessionState(UUID.randomUUID().toString(),owner,0,now,now+sessionTtl.toMillis(),"IDLE",null,catalog.version(),null,null,0,"",List.of(),null,null);
        store.create(s,now,maxSessions,ownerLimit);return s;
    }
    private SessionState transition(SessionState old,String phase,String confirmation,String operation,JsonNode args,String reply,List<String> errors,Integer code,JsonNode result){
        long now=now();return new SessionState(old.id(),old.owner(),old.revision()+1,now,now+sessionTtl.toMillis(),phase,operation,catalog.version(),args,confirmation,
            phase.equals("READY_FOR_CONFIRMATION")?now+confirmationTtl.toMillis():old.confirmationExpiresAt(),reply,errors,code,result);
    }
    private void save(SessionState old,SessionState next){if(!store.replace(old,next))throw new AgentException(409,"Состояние изменилось. Обновите сессию");}
    private View view(SessionState s){
        Operation op=null;try{if(s.operationId()!=null)op=catalog.require(s.operationId());}catch(AgentException ignored){}
        return new View(s.id(),s.phase(),s.reply(),s.errors(),s.confirmationId(),s.confirmationId()==null?null:Instant.ofEpochMilli(s.confirmationExpiresAt()).toString(),
            s.operationId(),op==null?null:op.method(),op==null?null:op.path(),s.arguments(),s.httpStatus(),s.result(),s.catalogVersion());
    }
    private long now(){return clock.millis();}
    private static void requireOwner(String owner){if(owner==null||owner.isBlank())throw new AgentException(401,"Требуется авторизация");}
    private static String question(String q){return q==null||q.isBlank()?"Какое действие нужно выполнить?":q.substring(0,Math.min(q.length(),2000));}
}
