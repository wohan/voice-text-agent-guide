package dev.textagent.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.*;

@ConfigurationProperties("text-agent")
public record AgentProperties(boolean enabled,String spec,Set<String> allowedOperations,URI targetBaseUrl,
    URI modelEndpoint,String model,String apiKey,Map<String,Object> modelOptions,Duration confirmationTtl,
    Duration sessionTtl,Integer maxSessions,String store,String stateEncryptionKey,Integer maxSessionsPerOwner,Integer requestsPerMinute){
    public AgentProperties{
        if(modelOptions==null)modelOptions=Map.of();if(confirmationTtl==null)confirmationTtl=Duration.ofMinutes(3);
        if(sessionTtl==null)sessionTtl=Duration.ofHours(24);if(maxSessions==null)maxSessions=10000;
        if(store==null)store="jdbc";if(maxSessionsPerOwner==null)maxSessionsPerOwner=20;if(requestsPerMinute==null)requestsPerMinute=20;
    }
    @Override public String toString(){return "AgentProperties[enabled="+enabled+", store="+store+", secrets=REDACTED]";}
}
