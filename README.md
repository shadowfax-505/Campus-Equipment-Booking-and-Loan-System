# Campus Equipment Booking and Loan System

A full-stack system that lets students reserve and borrow campus equipment, lets lab staff run approvals, collections, returns and payments, and lets admins manage inventory, policies, credit scoring and reports.

- **Backend:** Spring Boot 3.4 (Java 21), PostgreSQL 16, Spring Data JPA, Flyway, Spring Security with JWT access and refresh tokens, Spring scheduling, Swagger/OpenAPI.
- **Frontend:** React 18 and Vite, with role-based pages for students, staff and admins.
- **Ops:** Docker and Docker Compose.

## Team

See [TEAM.md](TEAM.md) for who owns which part of the code and how we work with branches and pull requests.

## Quick start (Docker Compose)

```bash
cp .env.example .env        # optional; every value has a development default
docker compose up --build
```

| What | URL |
|---|---|
| Web app | http://localhost:3000 |
| API | http://localhost:8080/api |
| Swagger UI | http://localhost:8080/swagger-ui.html (also proxied at http://localhost:3000/swagger-ui.html) |
| Health | http://localhost:8080/actuator/health |

On first start against an empty database, Flyway creates the schema and the seeder adds demo data. Set `SEED_DEMO_DATA=false` to start with only the admin account, the default policy and the scoring configuration.

## Local development (without Docker)

You need Java 21, Maven 3.9+, Node 20+ and PostgreSQL 16.

```bash
# 1. Database
createuser -P campus            # password: campus
createdb -O campus campus_equipment

# 2. Backend (port 8080)
cd backend
mvn spring-boot:run

# 3. Frontend (port 5173, proxies /api to :8080)
cd frontend
npm install
npm run dev
```

Run the tests with `cd backend && mvn test`. The suite has 51 tests: unit tests for the strategies and state machines, plus integration tests on an in-memory H2 database in PostgreSQL mode with a controllable clock.

## Demo accounts

| Role | Email | Password | Notes |
|---|---|---|---|
| Admin | admin@campus.edu | Admin12345 | Set with `ADMIN_EMAIL` / `ADMIN_PASSWORD` |
| Lab staff | staff.lab@campus.edu | Staff12345 | CSE and EEE labs, all staff permissions |
| Lab staff | staff.media@campus.edu | Staff12345 | Media Lab, can verify payments and assess charges |
| Student | alice@student.campus.edu | Student123 | Clean borrowing history |
| Student | bilal@student.campus.edu | Student123 | One late return with a paid fine |
| Student | chen@student.campus.edu | Student123 | New student, neutral score |
| Student | daniela@student.campus.edu | Student123 | New student, neutral score |

The login page also has one-click buttons for these accounts.

## Configuration

All settings are environment variables. See `.env.example` and `backend/src/main/resources/application.yml`.

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | local Postgres, `campus`/`campus` | Database connection |
| `JWT_SECRET` | dev value | HMAC key, at least 32 bytes. **Change in production.** |
| `JWT_ACCESS_TTL`, `JWT_REFRESH_TTL` | `PT15M`, `P7D` | Token lifetimes |
| `CAMPUS_TIMEZONE` | `UTC` | Time zone used in notifications and reports |
| `MAIL_PROVIDER` | `log` | `log` prints emails to the backend log; `smtp` sends them with the `SMTP_*` settings |
| `PAYMENT_PROVIDER` | `mock` | Online payment adapter |
| `PAYMENT_WEBHOOK_SECRET` | dev value | Shared secret checked on payment webhooks (`X-Webhook-Secret` header) |
| `PAYMENT_CURRENCY` | `USD` | Currency label for fines and payments |
| `JOBS_ENABLED` | `true` | Turns the scheduled jobs on or off |
| `SEED_DEMO_DATA` | `true` | Seeds demo data into an empty database |

## Architecture

```
backend/src/main/java/edu/campus/equipment
├── config/      app properties, Clock bean, seeder
├── security/    JWT service and filter, current-user helper, security rules
├── domain/      JPA entities and state enums
├── repo/        Spring Data repositories
├── service/     business logic: reservations, loans, fines, payments, credit, reports…
│   ├── policy/  strategy implementations (fines, scoring, allocation, limits)
│   ├── payment/ payment gateway adapter and payment service
│   └── notify/  notification service and email adapters
└── web/         REST controllers, request and view DTOs, error handling
```

### Design patterns

- **State.** `ReservationStatus` and `LoanStatus` define the allowed transitions. Every status change goes through `transitionTo`, and an illegal move returns HTTP 409.
- **Strategy.** The following are interfaces with swappable implementations:
  - fine calculation (`DailyOverdueFineStrategy`)
  - credit scoring (`WeightedCreditScoreStrategy`)
  - allocation ranking (`CreditPriorityAllocationStrategy`)
  - borrowing limits
- **Adapter.**
  - `EmailSender` has `LoggingEmailSender` and `SmtpEmailSender`.
  - `PaymentGateway` has `MockPaymentGateway`. A real provider plugs in by implementing the interface and its webhook verification.
- **Events.** Returns, fines, payments, missed collections and cancellations publish a `StudentActivityEvent`. A listener recalculates the student's credit score and records a history entry with the reason.

### Integrity and concurrency

- A PostgreSQL `EXCLUDE` constraint (btree_gist) makes overlapping approved reservations for the same item impossible at the database level.
- Approvals, issues and extensions take a row lock on the equipment and re-read it. Entities also carry `@Version` optimistic locking.
- Each loan stores a snapshot of the policy it was issued under, so later policy edits don't change existing loans.
- Fines carry a dedupe key, so a repeated job run never charges twice.
- Payment webhooks are idempotent: a payment that is already settled ignores repeat notifications.
- Refresh tokens are opaque, stored hashed, and rotated on every use. Reusing an old token revokes every session for that user.
- All time logic uses an injectable `Clock`, which the tests move forward to simulate due dates and overdue days.

## Business rules

### Credit score

`S = 0.40·T + 0.25·C + 0.15·P + 0.10·R + 0.10·H`, plus manual adjustments, clamped to 0–100.

Each component is 0–100 and is computed over the recent-history period (180 days by default). A component with no relevant history uses the neutral value (70).

| Component | Meaning | How it is computed |
|---|---|---|
| T, punctuality | On-time returns | Per closed loan: 100 if returned within due time plus grace. Otherwise 15 points off per started late day. A lost item scores 0. |
| C, care | Condition on return | 100 for normal condition or wear, 50 if accessories were missing, 0 for verified damage or loss |
| P, payment | Paying fines promptly | 100 if a fine is settled within 7 days, then 5 points off per extra day. Unpaid fines older than 7 days score 0. No fines but some loans gives 100. |
| R, reliability | Collecting what you book | 100 if collected, 0 for a missed collection, 40 for a late cancellation |
| H, history | Track record | Grows from the neutral value to 100 as clean loans approach 10 |

Admins can change the weights and parameters, and add or void manual adjustments with a reason. Students see their components, history and the reasons for each change.

### Allocation

- Competing requests for the same item and overlapping time are grouped. Each request opens a **decision window** (60 minutes by default, and never past the booking start).
- When the window closes, staff see the group ranked by credit score, with ties broken by the earliest request.
- Approving anyone other than the top-ranked student, or approving before the window closes, needs the `OVERRIDE_ALLOCATION` permission and a written reason. The override is audited.
- Unsuccessful requests are **waitlisted** (a policy setting). If the approved booking is cancelled or missed, the next eligible waitlisted student is promoted automatically.
- Students can see their own rank and outcome, but never other students' scores or identities.

### Fines

`F_late = min(cap, rate × ceil(max(0, t_return − t_due − grace) / 24h))`

- The rate, grace and cap come from the policy snapshot on the loan. The late fine is assessed when the item is returned; a staff-recorded loss is charged separately.
- Staff with `ASSESS_CHARGES` can add damage, missing-accessory or loss charges.
- Admins can waive or correct a fine with a reason.

### Payments

- Payments can be partial. They go to the fines the student picked first, then to the oldest remaining fines.
- **Online (mock).** The student starts a payment and gets a checkout reference. The built-in mock checkout then calls the webhook, which settles the payment.
- **External.** The student records a payment made elsewhere (cash desk, bank) with a reference. It only counts after staff with `VERIFY_PAYMENTS` verify it.

### Blocking rules

Unpaid fines block requesting, approval, collection and extensions. The following are policy decisions and are configurable per policy:

- **Disputed fines still block by default** (`disputedFinesBlock = true`). Set it to false to let students borrow while a dispute is open.
- **An overdue loan blocks new requests** (`overdueLoanBlocks = true`).
- **Waitlisting** of unsuccessful requests is on (`waitlistUnsuccessful = true`).

Other policy settings:

- maximum loan length
- maximum advance booking
- maximum active bookings
- collection window
- early collection
- cancellation cut-off
- maximum extensions

### Scheduled jobs

These jobs run on a schedule and can be disabled with `JOBS_ENABLED=false`:

- expire missed collections and promote waitlists
- send overdue notices
- send return and collection reminders, and start scheduled maintenance
- deliver queued emails
- run a nightly full score recalculation

### Notifications

In-app notifications are stored in the database and shown on the Notifications page with an unread count in the header. The same table works as an email outbox, which the delivery job sends through the configured `EmailSender`.

### Reports and audit

Admins have the following reports:

- utilization
- student activity
- most requested items
- overdue items
- finances
- incidents
- maintenance history
- overrides and score adjustments

Every sensitive action is written to the audit log, which is searchable from the admin UI. Examples are approvals, overrides, waivers, payment verification and role changes.

## API overview

The full, interactive reference is in Swagger UI.

| Area | Base path | Who |
|---|---|---|
| Auth | `/api/auth` (register, login, refresh, logout, me) | Public or signed in |
| Catalog | `/api/equipment`, `/api/categories`, `/api/locations` | Signed in |
| Student | `/api/me/...` (reservations, loans, extensions, fines, payments, credit, disputes, notifications, dashboard) | Student |
| Staff | `/api/staff/...` (queue, approvals, collections, returns, charges, extensions, payment verification, maintenance) | Staff or admin, limited to their labs and permissions |
| Admin | `/api/admin/...` (users, inventory, policies, credit config, fines, disputes, reports, audit) | Admin |
| Webhooks | `/api/payments/webhook/{provider}` | Payment provider, checked by shared secret |

Errors always have the same shape: `{timestamp, status, code, message, fields}`.
