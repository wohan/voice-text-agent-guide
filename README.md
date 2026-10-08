# Text Agent for REST APIs

Текст → выбор REST-операции → сбор данных → проверка OpenAPI → предпросмотр → подтверждение → выполнение от имени пользователя.

Java-ядро, Spring Boot starter и React/TypeScript-виджет для подключения к существующему приложению. Версия **0.2.0-SNAPSHOT** (React **0.2.0**) добавляет полный каталог OpenAPI, JSON Schema validation, JDBC-состояние, аудит и защиту от повторной отправки между экземплярами. Это кандидат для staging-проверки; голос и маскирование перед облачной моделью отложены.

## Сборка

```sh
mvn clean install
cd packages/react
npm ci
npm test
npm run build
npm pack
```

Maven/npm пакеты пока не опубликованы. Starter подключается из локальной сборки, React — из `text-agent-react-0.2.0.tgz`. Для публичного выпуска владельцу ещё нужно выбрать лицензию, package coordinates и registry.

## Backend

```xml
<dependency>
  <groupId>dev.textagent</groupId>
  <artifactId>agent-spring-boot-starter</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
```

Java 21+, Spring Boot 3 servlet. Ядро не зависит от Spring и допускает собственные адаптеры `Planner`, `Executor`, `SessionStore`.

Положите OpenAPI JSON/YAML в ресурсы. Пример: [полный Rental Management](examples/rental-crm/rental-openapi.json). Примените [SQL-миграцию](agent-core/src/main/resources/dev/textagent/schema.sql) через Flyway/Liquibase хоста и настройте DataSource.

```yaml
text-agent:
  enabled: true
  spec: classpath:rental-openapi.json
  # По умолчанию используются x-agent-enabled: true.
  # Можно задать явный список: allowed-operations: [createTenant]
  target-base-url: http://127.0.0.1:8080
  model-endpoint: ${AGENT_MODEL_ENDPOINT}
  model: ${AGENT_MODEL}
  api-key: ${AGENT_API_KEY:}
  store: jdbc
  state-encryption-key: ${AGENT_STATE_ENCRYPTION_KEY}
  confirmation-ttl: 3m
  session-ttl: 24h
  max-sessions: 10000
  max-sessions-per-owner: 20
  requests-per-minute: 20
```

Ключ состояния — Base64 от 32 случайных байтов, например `openssl rand -base64 32`. Храните его в менеджере секретов, сохраняйте между deploy и используйте одинаковый на всех репликах. Состояние шифруется AES-256-GCM, индекс владельца — HMAC. Токены не сохраняются. Audit содержит только метаданные и очищается через 30 дней. `store: memory` предназначен только для разработки. По умолчанию отсутствие БД, миграции или ключа останавливает запуск.

`target-base-url` — фиксированный origin без пути. OpenAPI servers игнорируется. Контроллер требует Principal из существующей аутентификации хоста; встроенное выполнение требует текущий Bearer JWT. Хост отвечает за Spring Security, CSRF/CORS, права и ограничения аккаунта. Для другого способа авторизации нужен адаптер.

## Frontend

```tsx
import { AgentChat } from '@text-agent/react';
import '@text-agent/react/style.css';

<AgentChat
  key={currentUser.id}
  storageKey={`agent:my-backend:${currentUser.id}`}
  baseUrl="/api/text-agent"
  getHeaders={() => ({ Authorization: `Bearer ${getCurrentToken()}` })}
  fieldLabels={{ fullName: 'ФИО', phone: 'Телефон' }}
  onExecuted={() => refreshData()}
/>
```

Установите пакет через `npm install /path/to/text-agent-react-0.2.0.tgz`. Рендерите только после входа, меняйте key и storageKey при смене пользователя. В sessionStorage сохраняется только ID сессии. После обновления страницы виджет восстанавливает состояние, без повторного выполнения. При UNKNOWN новый запрос заблокирован до явного подтверждения пользователем, что он проверил результат в приложении.

## Протокол

Пути относительно `/api/text-agent`:

| Метод | Путь | Назначение |
| --- | --- | --- |
| GET | `/operations` | Полный каталог, доступность операций и версия |
| POST | `/messages` | `{ "sessionId": null, "message": "Создай арендатора…" }` |
| GET | `/sessions/{id}` | Состояние своей сессии |
| POST | `/sessions/{id}/confirm` | `{ "confirmationId": "..." }` |
| POST | `/sessions/{id}/cancel` | Отмена невыполненного действия |

Ответ: state, reply, errors, sessionId, предпросмотр arguments, confirmationId, expiresAt, result и catalogVersion. Подтверждение не принимает изменяемый payload. Все действия, включая GET, требуют кнопки; текст «да» ничего не выполняет.

## OpenAPI

Файл читается один раз при старте, схемы компилируются и кэшируются. Подтверждение связано с SHA-256 файла; изменение контракта аннулирует старые подтверждения. Полный каталог доступен для просмотра, выполнение разрешают `x-agent-enabled: true` или явный список operationId. Wildcard `*` запрещён.

Поддерживается профиль OpenAPI 3.0.x / 3.1.x: JSON body, скалярные path/query, локальные ссылки, вложенные объекты/массивы, allOf/oneOf/anyOf, required, enum, границы, pattern и форматы через NetworkNT JSON Schema. readOnly запрещены на ввод. Нет поддержки Swagger 2, multipart/файлов, header/cookie параметров, сложной сериализации, внешних refs, $id и динамических refs. Неизвестные расширения сами по себе не являются проверяемыми ограничениями. Контракт — доверенная конфигурация администратора, а не произвольная загрузка пользователя.

`x-agent-input-schema` сужает входную схему операции. `x-agent-response-fields` задаёт поля ответа для пользователя (до 50 строк); ответы backend не передаются модели. Для каталога больше восьми операций модель сначала получает краткий индекс, затем схему выбранной операции.

[Rental Management](examples/rental-crm/README.md): 66 операций в каталоге, 19 включены. PUT отключён до реализации чтения текущего объекта и защиты от конкурентного изменения. DELETE, auth и billing требуют отдельной политики. Автоматических цепочек и поиска связанных записей по имени пока нет — ID нужно указать или предварительно получить запросом списка.

## Гарантии и оставшаяся работа

JDBC CAS не позволяет повторно отправить один confirmation ID после перезапуска или с другой реплики. При потере ответа/5xx либо прерванной попытке — UNKNOWN, без retry. Это **не exactly-once**: Rental Management пока не обрабатывает Idempotency-Key транзакционно. Новое предложение после проверки результата является новой попыткой.

Квоты и ограничения сессий общие для реплик. Проверка статуса имеет отдельную квоту в три раза выше. SQL-транзакции не удерживаются во время HTTP-вызовов. Бизнес-API повторно проверяет права и доменные правила: OpenAPI-валидация этого не заменяет.

Перед широким production-доступом нужны нагрузочная проверка, оценка качества модели на реальных формулировках, мониторинг ошибок/стоимости и резервирование БД с ключом. Следующий этап: прикладная идемпотентность, выделенные agent scopes, поиск связей. [Подробная архитектура и ограничения](docs/implementation/architecture.md).

## DeepSeek и проверки

В Rental заданы endpoint `https://api.deepseek.com/chat/completions`, модель `deepseek-flash`, options `thinking.type=disabled`, `max_tokens=2048`. Адаптер ожидает JSON chat-completions; параметры модели настраиваются через `text-agent.model-options`. Ключ задаётся только на сервере через AGENT_API_KEY.

Обычные тесты используют локальную модель-заглушку. JDBC-тесты работают с H2 и PostgreSQL; для PostgreSQL задайте AGENT_TEST_PG_URL, AGENT_TEST_PG_USER, AGENT_TEST_PG_PASSWORD (только отдельная тестовая база). CI проверяет оба варианта. Живой opt-in тест отправляет только синтетические данные, REST-назначение остаётся локальным:

```sh
set -a
source .env.deepseek
set +a
AGENT_LIVE_TEST=true mvn -pl agent-core -Dtest=LiveProviderTest test
```

`.env.deepseek` игнорируется Git, права должны быть 600. Spring не читает его автоматически.

Исходная аналитика сохранена в `docs/rentcrm`, `docs/general`, `docs/audio`.
