# SDD: RentCRM для арендодателей

## 1. Назначение продукта

RentCRM - вертикальная SaaS CRM для малых арендодателей и управляющих, работающих с 5-50 объектами: квартиры, апартаменты, коммерческие помещения, склады, студии, оборудование и прокатные активы.

Продукт должен заменить связку Excel, заметок, мессенджеров и папок с договорами. Главная ценность MVP - ежедневный операционный контроль: кто должен заплатить, где заканчивается договор, по какому объекту есть заявка, сколько объект реально приносит.

## 2. Целевой рынок РФ

### 2.1. Клиентские сегменты

1. Частные владельцы 5-50 квартир или апартаментов.
2. Управляющие посуточной и среднесрочной арендой.
3. Владельцы коммерческих помещений: офисы, павильоны, склады, боксы.
4. Небольшие управляющие компании.
5. Прокатные бизнесы с регулярными платежами и актами.

### 2.2. Боли клиента

1. Платежи и долги ведутся вручную.
2. Договоры лежат в разных местах и теряются.
3. Нет единого календаря оплат, продлений и заявок.
4. Сложно быстро понять доходность по каждому объекту.
5. Коммуникация с арендатором размазана по WhatsApp/Telegram/email.
6. Большие CRM перегружены продажами, воронками и лишней настройкой.

### 2.3. Почему клиент готов платить

1. Экономия времени на регулярном контроле оплат.
2. Снижение просрочек за счет напоминаний.
3. Меньше риска пропустить продление договора.
4. Быстрый доступ к документам и истории объекта.
5. Понятная аналитика по объектам без бухгалтерской сложности.
6. Доступ с телефона и компьютера без самостоятельного администрирования.

### 2.4. Позиционирование

Не "универсальная CRM", а "личный кабинет арендодателя". Интерфейс должен говорить языком аренды: объект, арендатор, договор, платеж, заявка, доходность.

## 3. Регуляторные и эксплуатационные требования РФ

### 3.1. Персональные данные

Система хранит ФИО, телефоны, email, адреса объектов, договоры и платежную историю. Это персональные данные. Для эксплуатации в РФ необходимо:

1. Разместить и принять политику обработки персональных данных.
2. Получать согласие пользователя на обработку персональных данных.
3. Хранить данные российских пользователей на инфраструктуре, соответствующей требованиям локализации персональных данных РФ.
4. Предусмотреть договор оферты и пользовательское соглашение.
5. Вести журнал событий безопасности и административных действий.
6. Настроить резервное копирование и регламент удаления данных.

Официальная точка входа по уведомлению оператора персональных данных: https://pd.rkn.gov.ru/operators-registry/notification/form/

### 3.2. Платежи

MVP фиксирует платежи вручную. Платежная интеграция включается отдельным этапом:

1. СБП QR/ссылка на оплату через банк или платежного провайдера.
2. ЮKassa/CloudPayments/Т-Банк/СберБизнес как провайдеры эквайринга.
3. Вебхуки платежей должны попадать в отдельный API endpoint и быть идемпотентными.
4. Комиссия с платежей может стать дополнительной монетизацией.

### 3.3. Налоги

Приложение не рассчитывает налоговые обязательства пользователя в MVP. В интерфейсе нельзя обещать юридически значимый налоговый расчет. Допустимо показывать экспорт доходов и расходов, а в следующих версиях - интеграции с бухгалтерией или подсказки по режимам налогообложения.

Официальная справочная зона ФНС по НПД: https://npd.nalog.ru/

## 4. MVP Scope

### 4.1. Входит в MVP

1. Регистрация организации/аккаунта арендодателя.
2. Объекты аренды.
3. Арендаторы.
4. Договоры.
5. Платежный календарь.
6. Ручная фиксация платежей.
7. Заявки на обслуживание/ремонт.
8. Документы и ссылки на файлы.
9. Напоминания внутри приложения.
10. Простая аналитика по доходности.
11. Dockerized deployment: frontend, backend, postgres, nginx.
12. Free tier на 3 объекта.
13. Оплата подписки.
14. Расходы по объектам.
15. Объявления и лиды.

### 4.2. Не входит в MVP

1. Полноценный электронный документооборот.
2. Автоматический эквайринг и СБП.
3. Бухгалтерский учет и налоговые декларации.
4. Мобильное приложение.
5. Распознавание договоров и платежек.
6. Мультивалютность.

## 5. Роли

### 5.1. Owner

Владелец аккаунта. Может управлять объектами, арендаторами, договорами, платежами, заявками, пользователями и тарифом.

### 5.2. Manager

Операционный управляющий. Может работать с объектами, арендаторами, заявками и платежами, но не управляет тарифом и пользователями.

### 5.3. Tenant

Арендатор. В MVP внешний кабинет арендатора не реализуется. Система хранит карточку арендатора и контакты для уведомлений.

## 6. Основные сущности

### 6.1. Account

Организация или личный кабинет арендодателя.

Поля: id, name, tariff, objectsLimit, createdAt, updatedAt.

### 6.2. User

Пользователь системы.

Поля: id, accountId, email, passwordHash, fullName, role, enabled, createdAt.

### 6.3. RentalObject

Объект аренды.

Поля:

1. id
2. accountId
3. title
4. type: APARTMENT, COMMERCIAL, WAREHOUSE, STUDIO, EQUIPMENT, OTHER
5. address
6. areaSqm
7. cadastralNumber
8. status: VACANT, OCCUPIED, MAINTENANCE, ARCHIVED
9. monthlyRent
10. depositAmount
11. notes
12. createdAt, updatedAt

### 6.4. Tenant

Арендатор.

Поля: id, accountId, fullName, phone, email, passportMasked, inn, telegramChatId, whatsappPhone, notificationsEnabled, notes, createdAt, updatedAt.

В MVP паспорт хранится только в маскированном виде или как текстовое поле с предупреждением. Полное хранение паспортных данных требует отдельной проработки безопасности.

### 6.5. LeaseContract

Договор аренды.

Поля: id, accountId, objectId, tenantId, number, startDate, endDate, rentAmount, paymentDay, depositAmount, status, documentUrl, notes.

Статусы: DRAFT, ACTIVE, EXPIRING, ENDED, TERMINATED.

### 6.6. Payment

Платеж или начисление.

Поля: id, accountId, objectId, tenantId, contractId, dueDate, paidDate, amount, paidAmount, status, type, method, comment.

Статусы: PLANNED, DUE, OVERDUE, PAID, PARTIAL, CANCELLED.

Типы: RENT, DEPOSIT, UTILITIES, PENALTY, OTHER.

### 6.7. MaintenanceRequest

Заявка на обслуживание.

Поля: id, accountId, objectId, tenantId, title, description, priority, status, cost, createdAt, dueDate, closedAt.

Приоритеты: LOW, MEDIUM, HIGH, URGENT.

Статусы: NEW, IN_PROGRESS, WAITING_TENANT, DONE, CANCELLED.

### 6.8. Document

Документ или ссылка на файл.

Поля: id, accountId, objectId, tenantId, contractId, name, type, fileUrl, expiresAt, createdAt.

Типы: CONTRACT, ACT, RECEIPT, PASSPORT_COPY, PHOTO, OTHER.

### 6.9. NotificationSettings

Настройки уведомлений аккаунта.

Поля: id, accountId, enabled, remindDaysBefore, reminderTime, telegramEnabled, telegramBotToken, telegramDefaultChatId, smsRuEnabled, smsRuApiId, smsRuSender, smsRuTestMode, whatsappEnabled, whatsappApiUrl, whatsappToken, whatsappDefaultRecipient, messageTemplate, createdAt, updatedAt.

Каналы MVP:

1. Telegram Bot API: бесплатный канал, требует bot token и chat_id арендатора или default chat_id.
2. SMS.RU: SMS-канал для РФ, требует api_id, поддерживает тестовый режим `test=1`.
3. WhatsApp webhook: универсальный HTTP-адаптер для подключения Wazzup, edna или другого WhatsApp Business provider.

Официальные документы каналов:

1. Telegram Bot API: https://core.telegram.org/bots/api
2. SMS.RU API: https://sms.ru/api/send
3. WhatsApp Cloud API: https://developers.facebook.com/docs/whatsapp/cloud-api/reference/messages

### 6.10. NotificationDelivery

Журнал отправки уведомлений.

Поля: id, accountId, paymentId, channel, remindDaysBefore, recipient, status, response, createdAt.

Статусы: SENT, SKIPPED, FAILED.

### 6.11. BillingSettings

Настройки приема оплаты подписки.

Поля: id, accountId, provider, yookassaShopId, yookassaSecretKey, cloudPaymentsPublicId, cloudPaymentsApiSecret, robokassaMerchantLogin, robokassaPassword1, genericPaymentUrl, testMode, createdAt, updatedAt.

Провайдеры: YOOKASSA, CLOUDPAYMENTS, ROBOKASSA, GENERIC_PAYMENT_LINK.

### 6.12. BillingInvoice

Счет на оплату подписки.

Поля: id, accountId, tariff, amount, objectsLimit, provider, status, providerPaymentId, confirmationUrl, providerResponse, createdAt.

### 6.13. Expense

Расход по объекту.

Поля: id, accountId, objectId, expenseDate, category, amount, vendor, documentUrl, comment, createdAt, updatedAt.

### 6.14. Listing

Объявление по объекту.

Поля: id, accountId, objectId, title, description, price, publicUrl, published, publishedAt, createdAt, updatedAt.

### 6.15. Lead

Потенциальный арендатор из объявления, звонка или ручного добавления.

Поля: id, accountId, listingId, objectId, fullName, phone, email, source, status, comment, createdAt, updatedAt.

## 7. Пользовательские сценарии

### 7.1. Добавить объект

1. Пользователь открывает раздел "Объекты".
2. Нажимает "Добавить".
3. Вводит название, тип, адрес, ставку, депозит и примечания.
4. Система проверяет лимит тарифа.
5. Объект появляется в списке и аналитике.

### 7.2. Создать арендатора и договор

1. Пользователь создает арендатора.
2. Выбирает объект.
3. Создает договор с датами, днем оплаты и суммой.
4. Система переводит объект в OCCUPIED.
5. Система создает ближайшие платежи по договору.

### 7.3. Контроль оплат

1. Пользователь открывает dashboard.
2. Видит платежи на ближайшие 14 дней, просрочки и сумму к получению.
3. Отмечает платеж как оплаченный.
4. Система обновляет статус платежа и аналитику.

### 7.4. Заявка на ремонт

1. Пользователь создает заявку по объекту.
2. Указывает приоритет, срок и стоимость.
3. В процессе меняет статус.
4. После закрытия стоимость учитывается как расход объекта.

### 7.5. Продление договора

1. Dashboard показывает договоры, истекающие в течение 30 дней.
2. Пользователь связывается с арендатором.
3. Меняет endDate или завершает договор.

## 8. API MVP

Base URL: `/api`

### 8.1. Auth

1. `POST /api/auth/register`
2. `POST /api/auth/login`
3. `GET /api/me`

В текущей реализации MVP допускается упрощенный JWT HS256. Для промышленной эксплуатации секрет хранится только в переменных окружения.

### 8.2. Dashboard

`GET /api/dashboard`

Возвращает:

1. Количество объектов.
2. Количество занятых объектов.
3. Сумму оплаченных платежей за месяц.
4. Сумму просроченных платежей.
5. Платежи на ближайшие 14 дней.
6. Договоры, истекающие за 30 дней.
7. Заявки в работе.

### 8.3. CRUD endpoints

1. `/api/objects`
2. `/api/tenants`
3. `/api/contracts`
4. `/api/payments`
5. `/api/maintenance`
6. `/api/documents`
7. `/api/expenses`
8. `/api/listings`
9. `/api/leads`
10. `/api/notifications/settings`
11. `/api/notifications/deliveries`
12. `/api/billing/settings`
13. `/api/billing/invoices`

Каждый endpoint должен фильтровать данные по accountId текущего пользователя.

## 9. Архитектура

### 9.1. Компоненты

1. `frontend`: React + TypeScript + MobX + Vite.
2. `backend`: Java 21 + Spring Boot + Spring Web + Spring Data JPA + Spring Security.
3. `postgres`: основная БД.
4. `nginx`: reverse proxy и отдача frontend.

### 9.2. Deployment

Приложение запускается командой:

```bash
docker compose up -d --build
```

Порты:

1. 80 - nginx/frontend/API proxy.
2. 8080 - backend внутри docker network.
3. 5432 - postgres внутри docker network.

### 9.3. Переменные окружения

1. `POSTGRES_DB`
2. `POSTGRES_USER`
3. `POSTGRES_PASSWORD`
4. `SPRING_DATASOURCE_URL`
5. `SPRING_DATASOURCE_USERNAME`
6. `SPRING_DATASOURCE_PASSWORD`
7. `APP_JWT_SECRET`
8. `APP_CORS_ORIGINS`

## 10. Безопасность MVP

1. Все API, кроме auth, требуют JWT.
2. Пароли хранятся через BCrypt.
3. Все запросы фильтруются по accountId.
4. CORS ограничивается доменом приложения.
5. Не логировать пароли, токены и полные персональные данные.
6. Документы в MVP хранятся как URL. Файловое хранилище подключается отдельным этапом через S3-compatible storage.
7. Для production включить HTTPS на reverse proxy.
8. Регулярные backup Postgres.

## 11. Тарифы

### 11.1. Start

0 руб/мес, до 3 объектов. Free tier нужен для быстрого входа в продукт.

### 11.2. Start

1490 руб/мес, до 10 объектов.

### 11.3. Pro

2990 руб/мес, до 25 объектов.

### 11.4. Business

4990 руб/мес, до 50 объектов.

### 11.5. Дополнительная монетизация

1. Шаблоны договоров.
2. Комиссия с платежей.
3. SMS/мессенджер-уведомления.
4. Расширенное хранилище документов.

## 12. Метрики продукта

1. Activation: создано 3 объекта и 1 договор.
2. Weekly active accounts.
3. Количество платежей, отмеченных через систему.
4. Количество предотвращенных просрочек: оплачено после напоминания.
5. Churn by object count.
6. MRR и ARPA.

## 13. Критерии приемки MVP

1. Пользователь может зарегистрироваться и войти.
2. Пользователь видит только данные своего account.
3. Можно создать объект, арендатора, договор, платеж, заявку, документ, расход, объявление и лид.
4. Dashboard показывает операционные показатели.
5. Docker Compose поднимает все компоненты одной командой.
6. Postgres сохраняет данные между рестартами.
7. Frontend корректно работает на desktop и mobile.
8. API возвращает валидационные ошибки понятным JSON.
9. При создании договора создается платежный календарь на срок договора.
10. Пользователь может выполнять базовые операционные действия: отметить платеж оплаченным, закрыть заявку, поменять статус лида, опубликовать/снять объявление, удалить ошибочно созданные записи.
11. Сквозные Playwright-тесты проверяют основной путь клиента через frontend и backend.

## 14. Backlog после MVP

1. Кабинет арендатора.
2. Интеграция СБП.
3. Генерация договоров из шаблонов.
4. Email-уведомления.
5. S3/MinIO для документов.
6. OCR договоров и платежек.
7. Экспорт в Excel.
8. Интеграции с календарями.
9. Управление задачами подрядчиков.
10. White-label для управляющих компаний.
11. Production webhooks платежных провайдеров и автоматическое продление подписки.
