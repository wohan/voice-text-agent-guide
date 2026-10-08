# Rental Management

Полный контракт — `rental-openapi.json` (66 операций, 19 разрешены). `agent-openapi.yaml` сохранён как минимальный учебный пример одного запроса.

В Rental Management контракт генерируется через springdoc из контроллеров и валидации моделей. Авторизованный endpoint: `/api/openapi`. Проверка и обновление snapshot:

```sh
mvn -f backend/pom.xml -Dtest=OpenApiContractTest -Dagent.openapi.export=true test
```

Обычный тест без export проверяет отсутствие расхождений. Готовая интеграция находится в ветке `codex/text-agent-production` репозитория rental-crm. Она включает Flyway V10, JDBC, шифрование и новую React-зависимость. Инструкция развёртывания: `docs/TEXT_AGENT_DEPLOYMENT.md` в том репозитории.

Разрешены list/create для арендаторов, объектов, договоров, платежей, расходов, обслуживания, объявлений, лидов и документов, плюс markPaymentPaid. ID связанных сущностей необходимо указать явно. PUT/DELETE, auth, billing и технические endpoints не включены. Правила задаёт OpenApiConfiguration хост-приложения; ядро не содержит Rental-специфики.

Конфигурация в `application-text-agent.yml` требует применённой SQL-миграции, DataSource и серверных переменных. Ключи не включены. Проверки выполняются на синтетических данных. Для другого проекта используйте его собственный OpenAPI, review разрешённых операций и общую инструкцию в корневом README.
