# AI Агенты на Java + Spring: Полное руководство

## Архитектура, фреймворки, production-практики

**Дата:** 16 марта 2026  
**Стек:** Java 21, Spring Boot 3.5+, Spring AI / LangChain4j

---

## Оглавление

1. [Аналитика: рынок AI-агентов в 2026](#1-аналитика)
2. [Архитектура production-grade AI-агента](#2-архитектура)
3. [Java-фреймворки для AI](#3-фреймворки)
4. [Реализация: код с примерами](#4-реализация)
5. [Интеграции: паттерны подключения](#5-интеграции)
6. [Отказоустойчивость и мониторинг](#6-отказоустойчивость)
7. [Примеры с GitHub](#7-примеры-github)
8. [Чек-лист для production](#8-чек-лист)

---

## 1. Аналитика

### Что такое AI-агент

AI-агент — это система на базе LLM, которая:
- **Воспринимает** контекст (текст, API, данные)
- **Принимает решения** (планирование, выбор действий)
- **Действует** (вызывает API, пишет код, управляет системами)
- **Учится** на результатах (память, обратная связь)

### Уровни автономности

| Уровень | Описание | Пример |
|---------|----------|--------|
| L1 | Реактивный (prompt → response) | Чат-бот |
| L2 | С инструментами (function calling) | Ассистент с доступом к API |
| L3 | Планирующий (multi-step) | Research-агент |
| L4 | Мультиагентный (cooperating agents) | Crew/система агентов |
| L5 | Автономный (self-improving) | Experimental |

### Рынок фреймворков (2026)

**Python-доминирование:**
- LangGraph — оркестрация графами, production-ready
- CrewAI — role-playing multi-agent, 100k+ разработчиков
- OpenAI Agents SDK — легковесный, от OpenAI
- AutoGen — от Microsoft, переходит в Agent Framework

**Java-экосистема:**
- **Spring AI** — официальный Spring-проект, enterprise-ready
- **LangChain4j** — 20+ LLM провайдеров, 30+ векторных БД
- Quarkus LangChain4j — нативная компиляция, GraalVM

**Тренды:**
- MCP (Model Context Protocol) — стандарт интеграций
- Tool calling — базовая функция всех фреймворков
- RAG (Retrieval Augmented Generation) — стандарт для работы с данными
- Human-in-the-loop — критично для production
- Multi-agent — растущий спрос

---

## 2. Архитектура

### Ключевые компоненты production-агента

```
┌─────────────────────────────────────────────────┐
│                   AI Agent                       │
├─────────────┬──────────────┬────────────────────┤
│  Perception │  Reasoning   │  Action            │
│  (вход)     │  (LLM)       │  (инструменты)     │
├─────────────┴──────────────┴────────────────────┤
│              Memory & State                     │
├─────────────────────────────────────────────────┤
│         Guardrails & Validation                 │
├─────────────────────────────────────────────────┤
│    Observability (tracing, metrics, logs)       │
└─────────────────────────────────────────────────┘
```

### 1. Perception Layer (Восприятие)

```java
// Входные каналы агента
public interface PerceptionLayer {
    // Текстовые сообщения
    String processText(String input);
    
    // Структурированные данные
    <T> T processStructured(String json, Class<T> type);
    
    // API/webhook события
    void processEvent(AgentEvent event);
    
    // Документы для RAG
    List<Document> processDocuments(List<byte[]> files);
}
```

### 2. Reasoning Layer (Рассуждение)

```java
// Ядро агента — взаимодействие с LLM
public interface ReasoningLayer {
    // Простой вызов
    String think(String prompt);
    
    // С инструментами
    AgentResponse thinkWithTools(String prompt, List<Tool> tools);
    
    // С контекстом (память)
    AgentResponse thinkWithContext(String prompt, ConversationMemory memory);
    
    // Структурированный вывод
    <T> T thinkStructured(String prompt, Class<T> outputType);
}
```

### 3. Action Layer (Действия)

```java
// Инструменты, которые агент может использовать
@Tool("Получить погоду по координатам")
public WeatherData getWeather(double lat, double lon) {
    return weatherApi.getCurrent(lat, lon);
}

@Tool("Отправить уведомление пользователю")
public void sendNotification(
    @P("ID пользователя") String userId,
    @P("Текст сообщения") String message
) {
    notificationService.send(userId, message);
}
```

### 4. Memory Layer (Память)

```java
// Двухуровневая память
public interface MemoryLayer {
    // Краткосрочная — текущая сессия
    void addToShortTerm(Message message);
    List<Message> getShortTerm(int lastN);
    
    // Долгосрочная — между сессиями (векторная БД)
    void addToLongTerm(String key, String value, float[] embedding);
    List<MemoryEntry> searchLongTerm(String query, int limit);
    
    // Компрессия — обобщение старых сообщений
    String compressHistory(List<Message> messages);
}
```

### 5. Guardrails Layer (Безопасность)

```java
public interface Guardrail {
    GuardrailResult check(String input);   // до LLM
    GuardrailResult validate(String output); // после LLM
}

// Реализации
public class InputValidationGuardrail implements Guardrail {
    // Фильтрация prompt injection
    // Проверка PII (персональных данных)
    // Rate limiting
    // Длина контекста
}

public class OutputValidationGuardrail implements Guardrail {
    // Проверка фактов (hallucination detection)
    // Фильтрация токсичного контента
    // Валидация структуры ответа
}
```

---

## 3. Фреймворки

### Spring AI

**Версия:** Spring AI 2.x (Spring Boot 4.x) / 1.1.x (Spring Boot 3.5.x)  
**Лицензия:** Apache 2.0  
**GitHub:** github.com/spring-projects/spring-ai

**Преимущества:**
- Официальный Spring-проект
- Spring Boot Auto Configuration
- Portable API (переключение LLM без переписывания кода)
- Vector Store интеграции (15+ БД)
- Structured Outputs → POJO
- Observability через Micrometer
- Tool/Function Calling

**Зависимости (Maven):**
```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>org.springframework.ai</groupId>
            <artifactId>spring-ai-bom</artifactId>
            <version>1.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai-spring-boot-starter</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pgvector-store-spring-boot-starter</artifactId>
</dependency>
```

**application.yml:**
```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        options:
          model: gpt-4o
          temperature: 0.7
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
        dimensions: 1536
```

### LangChain4j

**Версия:** 0.36+  
**Лицензия:** Apache 2.0  
**GitHub:** github.com/langchain4j/langchain4j

**Преимущества:**
- 20+ LLM провайдеров (OpenAI, Anthropic, Google, Ollama...)
- 30+ embedding stores
- Tool calling (включая MCP)
- Интеграция с Spring Boot, Quarkus, Micronaut, Helidon
- AI Services (декларативный API)
- RAG pipeline

**Зависимости (Maven):**
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-open-ai-spring-boot-starter</artifactId>
    <version>0.36.2</version>
</dependency>

<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j</artifactId>
    <version>0.36.2</version>
</dependency>

<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-easy-rag</artifactId>
    <version>0.36.2</version>
</dependency>
```

### Сравнение

| Критерий | Spring AI | LangChain4j |
|----------|-----------|-------------|
| Spring Boot интеграция | ✅ Нативная | ✅ Starter |
| Количество LLM | ~15 | 20+ |
| Vector Stores | 15+ | 30+ |
| Tool Calling | ✅ | ✅ + MCP |
| Structured Output | ✅ → POJO | ✅ |
| RAG | ✅ | ✅ |
| Observability | Micrometer | ✅ |
| Quarkus | ❌ | ✅ |
| AI Services (декларативный) | ❌ | ✅ |
| Зрелость | 1.1.x (GA) | 0.36+ (stable) |

**Рекомендация:** Spring AI для чистого Spring Boot стека. LangChain4j если нужна максимальная гибкость и поддержка Quarkus.

---

## 4. Реализация

### 4.1 Базовый AI-агент на Spring AI

```java
// ===== ChatClient — основа агента =====

@Service
public class WeatherAgent {
    
    private final ChatClient chatClient;
    private final WeatherService weatherService;
    private final ConversationMemory memory;
    
    public WeatherAgent(ChatClient.Builder builder, 
                        WeatherService weatherService,
                        ConversationMemory memory) {
        this.chatClient = builder
            .defaultSystem("""
                Ты — погодный ассистент. Отвечаешь кратко и по делу.
                Используй инструменты для получения актуальных данных.
                Если не уверен — честно скажи.
                """)
            .defaultAdvisors(new MessageChatMemoryAdvisor(memory))
            .build();
        this.weatherService = weatherService;
        this.memory = memory;
    }
    
    // Простой запрос
    public String chat(String userId, String message) {
        return chatClient.prompt()
            .user(message)
            .advisors(a -> a.param("chat_memory_retriever_key", userId))
            .call()
            .content();
    }
    
    // С tool calling
    public String chatWithTools(String userId, String message) {
        return chatClient.prompt()
            .user(message)
            .tools(weatherService)  // Spring AI автоматически создаст tools
            .advisors(a -> a.param("chat_memory_retriever_key", userId))
            .call()
            .content();
    }
    
    // Структурированный вывод
    public WeatherForecast getForecast(String city) {
        return chatClient.prompt()
            .user("Прогноз погоды для: " + city)
            .tools(weatherService)
            .call()
            .entity(WeatherForecast.class);  // → POJO
    }
}
```

### 4.2 Service с Tool Calling

```java
@Service
public class WeatherService {
    
    private final WeatherApiClient weatherApi;
    
    // @Tool — Spring AI автоматически зарегистрирует метод как tool
    @Tool("Получить текущую погоду по названию города")
    public CurrentWeather getCurrentWeather(
        @P("Название города на английском") String city
    ) {
        return weatherApi.getCurrent(city);
    }
    
    @Tool("Получить прогноз погоды на 5 дней")
    public Forecast getForecast(
        @P("Название города") String city,
        @P("Количество дней (1-5)") int days
    ) {
        return weatherApi.getForecast(city, days);
    }
    
    @Tool("Получить координаты города")
    public Coordinates geocode(@P("Название города") String city) {
        return geocodingApi.resolve(city);
    }
}
```

### 4.3 AI Agent с планированием (ReAct паттерн)

```java
@Service
public class PlanningAgent {
    
    private final ChatClient chatClient;
    private final List<AgentTool> tools;
    private final int maxIterations = 10;
    
    public AgentResult execute(String task) {
        List<Message> history = new ArrayList<>();
        history.add(new SystemMessage("""
            Ты — автономный агент. Работай по паттерну ReAct:
            
            1. Thought: проанализируй задачу
            2. Action: выбери инструмент и выполни действие
            3. Observation: получи результат
            4. Повтори直到 получишь финальный ответ
            5. Answer: дай итоговый ответ пользователю
            
            Формат: {"thought": "...", "action": "...", "action_input": "..."}
            """));
        
        history.add(new UserMessage(task));
        
        for (int i = 0; i < maxIterations; i++) {
            // Получить ответ от LLM
            ChatResponse response = chatClient.prompt()
                .messages(history)
                .tools(tools)
                .call()
                .chatResponse();
            
            AssistantMessage assistantMsg = response.getResult().getOutput();
            history.add(assistantMsg);
            
            // Проверить — есть ли вызовы инструментов
            List<ToolCall> toolCalls = assistantMsg.getToolCalls();
            
            if (toolCalls.isEmpty()) {
                // Агент завершил — вернуть ответ
                return AgentResult.success(assistantMsg.getText());
            }
            
            // Выполнить инструменты
            for (ToolCall toolCall : toolCalls) {
                ToolResponse result = executeTool(toolCall);
                history.add(new ToolResponseMessage(
                    toolCall.id(), 
                    result.content()
                ));
            }
        }
        
        return AgentResult.error("Превышен лимит итераций");
    }
    
    private ToolResponse executeTool(ToolCall call) {
        try {
            AgentTool tool = findTool(call.name());
            String result = tool.execute(call.arguments());
            return ToolResponse.success(call.id(), result);
        } catch (Exception e) {
            return ToolResponse.error(call.id(), e.getMessage());
        }
    }
}
```

### 4.4 Multi-Agent система

```java
// ===== Оркестратор агентов =====

@Service
public class AgentOrchestrator {
    
    private final ResearchAgent researchAgent;
    private final AnalysisAgent analysisAgent;
    private final WriterAgent writerAgent;
    private final ChatClient router;
    
    public AgentOrchestrator(ChatClient.Builder builder, ...) {
        this.router = builder
            .defaultSystem("""
                Ты — роутер задач. Анализируй запрос пользователя
                и определяй какому агенту его направить:
                
                - "research" — поиск информации, факты, данные
                - "analysis" — анализ, выводы, сравнения
                - "writer" — генерация текстов, отчётов, статей
                
                Отвечай ТОЛЬКО одним словом: research, analysis, или writer.
                """)
            .build();
    }
    
    public String handle(String task) {
        // Шаг 1: Определяем нужного агента
        String agentType = router.prompt()
            .user(task)
            .call()
            .content()
            .trim()
            .toLowerCase();
        
        // Шаг 2: Делегируем
        return switch (agentType) {
            case "research" -> researchAgent.research(task);
            case "analysis" -> analysisAgent.analyze(task);
            case "writer"   -> writerAgent.write(task);
            default -> "Не удалось определить агента";
        };
    }
}

// ===== Специализированный агент-исследователь =====

@Service
public class ResearchAgent {
    
    private final ChatClient chatClient;
    private final WebSearchService searchService;
    private final VectorStore vectorStore;
    
    public ResearchAgent(ChatClient.Builder builder, ...) {
        this.chatClient = builder
            .defaultSystem("""
                Ты — исследователь. Твоя задача:
                1. Найти релевантные источники
                2. Извлечь ключевые факты
                3. Проверить достоверность
                4. Предоставить структурированный ответ с источниками
                """)
            .build();
    }
    
    public String research(String question) {
        // Поиск в векторной БД (свои документы)
        List<Document> docs = vectorStore.similaritySearch(
            SearchRequest.query(question).withTopK(5)
        );
        
        // Поиск в интернете
        List<SearchResult> webResults = searchService.search(question);
        
        // Синтез ответа
        return chatClient.prompt()
            .user(u -> u.text("""
                Вопрос: {question}
                
                Контекст из документов:
                {docs}
                
                Результаты веб-поиска:
                {web}
                
                Дай полный ответ с указанием источников.
                """)
                .param("question", question)
                .param("docs", formatDocs(docs))
                .param("web", formatWebResults(webResults))
            )
            .call()
            .content();
    }
}
```

### 4.5 AI Service (LangChain4j декларативный подход)

```java
// ===== Декларативный AI-сервис (LangChain4j) =====

// Интерфейс — реализация генерируется автоматически
public interface Assistant {
    
    // Простой чат
    String chat(String userMessage);
    
    // С памятью
    @SystemMessage("""
        Ты полезный ассистент. Отвечай кратко и по делу.
        Язык: русский.
        """)
    String chatWithMemory(
        @MemoryId String userId,
        @UserMessage String message
    );
    
    // С инструментами
    @SystemMessage("Используй инструменты для ответа на вопросы.")
    String chatWithTools(
        @UserMessage String message,
        @V("context") String context
    );
    
    // Структурированный вывод
    @SystemMessage("Извлеки информацию о компании из текста.")
    CompanyInfo extractCompanyInfo(@UserMessage String text);
    
    // Streaming
    TokenStream streamChat(@UserMessage String message);
}

// Конфигурация
@Configuration
public class AgentConfig {
    
    @Bean
    public Assistant assistant(
        ChatLanguageModel model,
        ChatMemoryStore memoryStore,
        ToolProvider toolProvider
    ) {
        return AiServices.builder(Assistant.class)
            .chatLanguageModel(model)
            .chatMemoryProvider(userId -> 
                MessageWindowChatMemory.builder()
                    .maxMessages(20)
                    .chatMemoryStore(memoryStore)
                    .build()
            )
            .toolProvider(toolProvider)
            .build();
    }
    
    @Bean
    public ChatLanguageModel chatModel() {
        return OpenAiChatModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("gpt-4o")
            .temperature(0.7)
            .maxRetries(3)
            .timeout(Duration.ofSeconds(60))
            .build();
    }
}
```

### 4.6 RAG Pipeline

```java
@Configuration
public class RagConfig {
    
    // Документ → чанки → embeddings → векторная БД
    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .modelName("text-embedding-3-small")
            .build();
    }
    
    @Bean
    public VectorStore vectorStore(
        EmbeddingModel embeddingModel,
        JdbcTemplate jdbcTemplate
    ) {
        return PgVectorStore.builder()
            .jdbcTemplate(jdbcTemplate)
            .embeddingModel(embeddingModel)
            .dimensions(1536)
            .indexType(HNSW)
            .distanceType(COSINE_DISTANCE)
            .build();
    }
    
    // Загрузка документов
    @Bean
    public DocumentLoader documentLoader(VectorStore vectorStore) {
        return (resources) -> {
            for (Resource resource : resources) {
                // 1. Загрузка
                List<Document> docs = new TextDocumentParser()
                    .parse(resource);
                
                // 2. Разбиение на чанки
                List<Document> chunks = new DocumentSplitter()
                    .split(docs, 500, 50); // размер, overlap
                
                // 3. Создание embeddings и сохранение
                vectorStore.add(chunks);
            }
        };
    }
}
```

---

## 5. Интеграции

### 5.1 MCP (Model Context Protocol)

MCP — стандарт для подключения внешних инструментов к AI-агентам.

```java
// MCP Client — подключение к MCP серверам
@Configuration
public class McpConfig {
    
    @Bean
    public McpClient mcpClient() {
        return McpClient.builder()
            .serverUrl("http://localhost:3000/mcp")
            .build();
    }
    
    @Bean
    public ToolProvider mcpToolProvider(McpClient mcpClient) {
        return ToolProvider.builder()
            .addToolProvider(mcpClient.asToolProvider())
            .build();
    }
}
```

### 5.2 Интеграция с внешними API

```java
@Service
public class ExternalApiIntegration {
    
    // Retry + Circuit Breaker
    @Retryable(
        retryFor = {WebClientResponseException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2)
    )
    @CircuitBreaker(name = "externalApi", fallbackMethod = "fallback")
    public Mono<ApiResponse> callExternalApi(Request request) {
        return webClient.post()
            .uri("/api/endpoint")
            .bodyValue(request)
            .retrieve()
            .bodyToMono(ApiResponse.class)
            .timeout(Duration.ofSeconds(30));
    }
    
    public ApiResponse fallback(Request request, Exception e) {
        log.warn("External API unavailable, using cache", e);
        return cacheService.getOrDefault(request.key(), ApiResponse.empty());
    }
}
```

### 5.3 Webhook приём

```java
@RestController
@RequestMapping("/webhooks")
public class WebhookController {
    
    private final AgentOrchestrator agent;
    private final EventQueue eventQueue;
    
    @PostMapping("/telegram")
    public ResponseEntity<Void> handleTelegram(
        @RequestBody TelegramUpdate update,
        @RequestHeader("X-Telegram-Bot-Api-Secret-Token") String token
    ) {
        // Валидация
        if (!tokenService.isValid(token)) {
            return ResponseEntity.status(403).build();
        }
        
        // Async обработка
        eventQueue.publish(new AgentEvent(
            source = "telegram",
            userId = update.message().from().id(),
            message = update.message().text()
        ));
        
        return ResponseEntity.accepted().build();
    }
    
    @PostMapping("/github")
    public ResponseEntity<Void> handleGithub(
        @RequestBody GithubEvent event,
        @RequestHeader("X-Hub-Signature-256") String signature
    ) {
        // Верификация подписи
        if (!githubService.verifySignature(event, signature)) {
            return ResponseEntity.status(401).build();
        }
        
        eventQueue.publish(event.toAgentEvent());
        return ResponseEntity.accepted().build();
    }
}
```

---

## 6. Отказоустойчивость

### 6.1 Retry + Exponential Backoff

```java
@Configuration
public class ResilienceConfig {
    
    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig config = RetryConfig.custom()
            .maxRetries(3)
            .waitDuration(Duration.ofSeconds(1))
            .intervalFunction(IntervalFunction.ofExponentialBackoff(
                Duration.ofSeconds(1),  // начальная задержка
                2.0                      // множитель
            ))
            .retryOnException(e -> 
                e instanceof RateLimitException ||
                e instanceof TimeoutException ||
                e instanceof ServiceUnavailableException
            )
            .ignoreExceptions(ValidationException.class)
            .build();
        
        return RetryRegistry.of(config);
    }
    
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(50)           // 50% ошибок → разрыв
            .waitDurationInOpenState(Duration.ofSeconds(30))
            .slidingWindowSize(10)
            .minimumNumberOfCalls(5)
            .permittedNumberOfCallsInHalfOpenState(3)
            .build();
        
        return CircuitBreakerRegistry.of(config);
    }
}
```

### 6.2 LLM Provider Failover

```java
@Service
public class ResilientLlmService {
    
    private final List<LlmProvider> providers;
    private final RetryTemplate retryTemplate;
    
    // Приоритет: OpenAI → Anthropic → Groq → Local
    public ResilientLlmService(
        OpenAiProvider openAi,
        AnthropicProvider anthropic,
        GroqProvider groq,
        OllamaProvider local
    ) {
        this.providers = List.of(openAi, anthropic, groq, local);
    }
    
    public String complete(String prompt) {
        List<LlmProvider> available = new ArrayList<>(providers);
        
        while (!available.isEmpty()) {
            LlmProvider provider = available.get(0);
            try {
                return retryTemplate.execute(ctx -> 
                    provider.complete(prompt)
                );
            } catch (Exception e) {
                log.warn("Provider {} failed: {}", provider.name(), e.getMessage());
                available.remove(0);
                metrics.incrementCounter("llm.failover", provider.name());
            }
        }
        
        throw new AllProvidersUnavailableException();
    }
}
```

### 6.3 Rate Limiting

```java
@Service
public class RateLimitedAgent {
    
    private final RateLimiter userLimiter;
    private final RateLimiter globalLimiter;
    
    public RateLimitedAgent() {
        // Per-user: 10 запросов в минуту
        this.userLimiter = RateLimiter.builder()
            .limitForPeriod(10)
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .timeoutDuration(Duration.ofSeconds(5))
            .build();
        
        // Global: 100 запросов в минуту
        this.globalLimiter = RateLimiter.builder()
            .limitForPeriod(100)
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .timeoutDuration(Duration.ofSeconds(10))
            .build();
    }
    
    public Response process(String userId, String message) {
        userLimiter.acquirePermission(userId);
        globalLimiter.acquirePermission("global");
        
        return agent.execute(message);
    }
}
```

### 6.4 Сага-паттерн для длинных workflow

```java
@Service
public class SagaAgent {
    
    private final SagaOrchestrator saga;
    
    public SagaAgent(SagaOrchestrator saga) {
        this.saga = saga;
    }
    
    public Result executeWorkflow(String task) {
        return saga.start("agent-workflow")
            .step("research", 
                () -> researchAgent.research(task),
                () -> researchAgent.rollback(task)  // компенсация
            )
            .step("analysis",
                ctx -> analysisAgent.analyze(ctx.get("research")),
                () -> analysisAgent.cleanup()
            )
            .step("write",
                ctx -> writerAgent.write(ctx.get("analysis")),
                () -> writerAgent.deleteDraft()
            )
            .execute();
    }
}
```

---

## 7. Мониторинг

### 7.1 Observability

```java
@Configuration
public class ObservabilityConfig {
    
    // Micrometer metrics для Spring AI
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metrics() {
        return registry -> {
            // LLM latency
            Timer.builder("llm.request.duration")
                .description("LLM API request duration")
                .register(registry);
            
            // Token usage
            Counter.builder("llm.tokens.total")
                .description("Total tokens used")
                .tag("type", "input")
                .register(registry);
            
            // Tool calls
            Counter.builder("agent.tool.calls")
                .description("Tool invocations")
                .register(registry);
            
            // Errors
            Counter.builder("agent.errors")
                .description("Agent errors")
                .register(registry);
        };
    }
    
    // Distributed tracing
    @Bean
    public Tracer tracer() {
        return JaegerTracer.builder()
            .withServiceName("ai-agent")
            .withSampler(SamplerConst.RATE_LIMITING)
            .build();
    }
}
```

### 7.2 Логирование

```java
// Structured logging для агента
@Slf4j
@Service
public class InstrumentedAgent {
    
    public Response execute(Request request) {
        String traceId = MDC.get("traceId");
        
        log.info("agent.request", 
            kv("traceId", traceId),
            kv("userId", request.userId()),
            kv("messageLength", request.message().length()),
            kv("tools", request.tools())
        );
        
        Stopwatch timer = Stopwatch.createStarted();
        try {
            Response response = agent.execute(request);
            
            log.info("agent.response",
                kv("traceId", traceId),
                kv("duration_ms", timer.elapsed().toMillis()),
                kv("tokens_input", response.usage().inputTokens()),
                kv("tokens_output", response.usage().outputTokens()),
                kv("tool_calls", response.toolCalls().size()),
                kv("success", true)
            );
            
            return response;
            
        } catch (Exception e) {
            log.error("agent.error",
                kv("traceId", traceId),
                kv("duration_ms", timer.elapsed().toMillis()),
                kv("error", e.getClass().getSimpleName()),
                kv("message", e.getMessage()),
                e
            );
            throw e;
        }
    }
}
```

---

## 8. Примеры с GitHub

### Java / Spring

| Проект | Описание | Ссылка |
|--------|----------|--------|
| **Spring AI** | Официальный Spring AI фреймворк | github.com/spring-projects/spring-ai |
| **Spring AI Examples** | Примеры использования Spring AI | github.com/spring-projects/spring-ai-examples |
| **LangChain4j** | Java AI фреймворк (20+ LLM) | github.com/langchain4j/langchain4j |
| **LangChain4j Examples** | Примеры с Spring Boot, Quarkus | github.com/langchain4j/langchain4j-examples |
| **Quarkus LangChain4j** | Quarkus интеграция | github.com/quarkiverse/quarkus-langchain4j |
| **Awesome Spring AI** | Кураторский список ресурсов | github.com/spring-ai-community/awesome-spring-ai |
| **Spring AI Community** | Community проекты | github.com/spring-ai-community |

### Python (для сравнения)

| Проект | Описание | GitHub |
|--------|----------|--------|
| **LangGraph** | Оркестрация графами | github.com/langchain-ai/langgraph |
| **CrewAI** | Multi-agent фреймворк | github.com/crewAIInc/crewAI |
| **OpenAI Agents SDK** | Легковесный SDK от OpenAI | github.com/openai/openai-agents-python |
| **AutoGen** | Multi-agent от Microsoft | github.com/microsoft/autogen |
| **Deep Agents** | Планирующие агенты | github.com/langchain-ai/deepagents |

### Архитектурные паттерны (примеры)

| Паттерн | Где найти |
|---------|-----------|
| ReAct (Reasoning + Acting) | LangChain4j examples, Spring AI tool calling |
| RAG Pipeline | Spring AI RAG guide, LangChain4j easy-rag |
| Multi-Agent orchestration | CrewAI, AutoGen |
| Human-in-the-loop | LangGraph interrupts, Spring AI advisors |
| Circuit Breaker + Retry | Resilience4j + Spring Boot |
| Event-Driven Agent | Spring Events + async processing |

---

## 9. Production чек-лист

### Безопасность
- [ ] Валидация входных данных (prompt injection защита)
- [ ] Rate limiting (per-user + global)
- [ ] API ключи в vault/env, не в коде
- [ ] PII фильтрация (логи не должны содержать персональные данные)
- [ ] Аудит всех LLM вызовов
- [ ] Guardrails на входе и выходе

### Отказоустойчивость
- [ ] Retry с exponential backoff для LLM API
- [ ] Circuit breaker для внешних сервисов
- [ ] Fallback на резервный LLM провайдер
- [ ] Таймауты на все вызовы
- [ ] Graceful degradation при недоступности инструментов
- [ ] Saga/компенсация для длинных workflow

### Мониторинг
- [ ] Метрики: latency, tokens, errors, tool calls
- [ ] Distributed tracing (Jaeger/Zipkin)
- [ ] Structured logging с correlation ID
- [ ] Алёрты на высокий error rate
- [ ] Дашборд: cost tracking (расход на LLM)

### Тестирование
- [ ] Unit тесты для tools
- [ ] Integration тесты с mock LLM
- [ ] E2E тесты критических сценариев
- [ ] Eval тесты (качество ответов агента)
- [ ] Chaos тестирование (отказы внешних сервисов)

### Масштабирование
- [ ] Stateless агент (состояние в Redis/DB)
- [ ] Async обработка длинных задач
- [ ] Connection pooling для LLM API
- [ ] Кэширование повторяющихся запросов
- [ ] Horizontal scaling (несколько инстансов)

---

## 10. Рекомендуемая архитектура для Java + Spring

```
┌──────────────────────────────────────────────────┐
│                  API Gateway                      │
│         (rate limit, auth, routing)               │
├──────────────────────────────────────────────────┤
│              Agent Controller                     │
│        (REST / WebSocket / Webhook)               │
├──────────────────────────────────────────────────┤
│             Agent Orchestrator                    │
│      (routing, planning, multi-agent)             │
├────────┬──────────┬──────────┬───────────────────┤
│Research│ Analysis │  Writer  │  Custom Agent...  │
│ Agent  │  Agent   │  Agent   │                   │
├────────┴──────────┴──────────┴───────────────────┤
│              LLM Service Layer                   │
│    (retry, failover, rate limit, caching)        │
├──────────────────────────────────────────────────┤
│         Spring AI / LangChain4j                  │
│    (ChatClient, Tools, RAG, Memory)              │
├──────────────────────────────────────────────────┤
│    PostgreSQL + pgvector │ Redis │ S3            │
│    (vector store)        │(cache)│(documents)     │
├──────────────────────────────────────────────────┤
│   Observability: Micrometer + Jaeger + ELK       │
└──────────────────────────────────────────────────┘
```

---

## Заключение

### Когда использовать Java для AI-агентов:
- Enterprise окружение (уже Spring стек)
- Требования к типобезопасности и надёжности
- Интеграция с существующими Java-системами
- Команда владеет Java лучше чем Python

### Когда Python предпочтительнее:
- Быстрые прототипы
- Доступ к cutting-edge фреймворкам (LangGraph)
- Data Science / ML pipeline
- Больше примеров и community

### Оптимальный подход:
**Backend на Java + Spring** (отказоустойчивость, интеграции, мониторинг)  
**+ Python микросервисы** для ML-специфичных задач (fine-tuning, eval)

---

*Документ создан 16 марта 2026*
