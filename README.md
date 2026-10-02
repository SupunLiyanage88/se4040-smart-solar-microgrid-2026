# Smart Solar Microgrid Trading System

SE4040 Enterprise Application Development | Year 4, Semester 2 | Assignment 1, 2026

An end-to-end client-server system for trading energy slots on solar microgrid nodes:

- a **C# ASP.NET Core Web API** (FAT service pattern, MongoDB) that owns every business rule,
- a **React + Bootstrap 5 web portal** for Backoffice staff and Grid Operators,
- a **pure native Android app (Kotlin, SQLite)** for Solar Prosumers and Grid Operators.

Both clients are user-interface layers only. They talk to the API exclusively over REST and never touch the database.

| Item | Link |
| --- | --- |
| Git repository | https://github.com/SupunLiyanage88/se4040-smart-solar-microgrid-2026 |
| Demo video (max 5 min) | **TODO: add the YouTube or OneDrive link** |
| Report | Submitted in the ZIP together with the source (see section 12) |

## Contents

1. [Team and individual contributions](#1-team-and-individual-contributions)
2. [System architecture](#2-system-architecture)
3. [Features by role](#3-features-by-role)
4. [Business rules and where they are enforced](#4-business-rules-and-where-they-are-enforced)
5. [Data model (MongoDB)](#5-data-model-mongodb)
6. [REST API reference](#6-rest-api-reference)
7. [Repository layout](#7-repository-layout)
8. [Running the system locally](#8-running-the-system-locally)
9. [Hosting the API on IIS](#9-hosting-the-api-on-iis)
10. [Testing and quality gates](#10-testing-and-quality-gates)
11. [Known limitations and decisions](#11-known-limitations-and-decisions)
12. [Submission checklist](#12-submission-checklist)
13. [AI assistance disclosure](#13-ai-assistance-disclosure)

## 1. Team and individual contributions

Contributions below are taken from the Git history (non-merge commits on `dev`/`main`) so they can be checked with
`git log --author=<name>`. Each member must replace the TODO fields and add their own reflection in the report.
Every member is assessed individually on the whole system in the viva, so this table is a record of authorship,
not a limit on what each person must understand.

| Member | Name / IT number | GitHub identity (commits) | Main areas of work |
| --- | --- | --- | --- |
| 1 | L.S.B Hemarathne / IT22134776 | Supun Liyanage (`SupunLiyanage88`), 21 commits | Repository and API scaffolding, authentication and user management rewrite (`f987550`), microgrid node management API and web portal (`ca3d42e`), node deletion (`f1621bf`), nearby-node map and discovery (`a1fb788`, `7f5e736`), Android booking summaries, search and operator history (`61f1d4c`), Android navigation component and screen structure (`c5f960c`), CI workflow (`1ee6a9c`), planning and README |
| 2 | B.K.H.M.B.L Herath / IT22557056 | Buddhila-Herath, 7 commits | Prosumer account control with SQLite session persistence (`8797520`), reservation and QR dispatch for prosumers and operators on Android (`43c829f`), Android UI styling (`6e7bed1`), web reservation management screen (`c641e6e`), dead-code cleanup (`e5b1904`) |
| 3 | A.M Senarathne / IT22262554 | akilaManu, 5 commits | First JWT authentication and authorization (`381fb90`), authentication and user management services and interfaces (`750e0a7`), unauthorized response handling (`a520624`), activation response (`f7b5d90`), Swagger UI with JWT support (`e95706e`) |
| 4 | H.I.B Wickramarathne / IT22239198 | harithew / HaritheW (same e-mail), 6 commits | Reservation implementation (`5b04d84`), QR duplicate fix (`239ba9e`), front-end and error label fixes (`5aaa946`, `02ca223`, `c509fd3`), scan-completed feature (`6b64e86`) |

Post-merge hardening in this working tree (documented in the report): Android 12-hour-notice messaging, prefilled
reservation editing with readable node names, SQLite node cache with offline fallback, reservation
integration checks (the suite grew to 190), removal of the template `WeatherForecast` files, and this README.

## 2. System architecture

```
 +--------------------+          +----------------------+
 |  Web portal        |          |  Android app         |
 |  React 19 + TS     |          |  Kotlin, native views|
 |  Bootstrap 5       |          |  SQLite (session +   |
 |  Backoffice /      |          |  node cache)         |
 |  Grid Operator     |          |  Prosumer / Operator |
 +---------+----------+          +----------+-----------+
           |  HTTPS / JSON (REST + JWT)     |
           +---------------+----------------+
                           v
              +---------------------------+
              |  C# ASP.NET Core Web API  |   hosted on Windows IIS
              |  FAT service layer:       |
              |  validation, roles, 7-day |
              |  and 12-hour rules, QR,   |
              |  capacity, concurrency    |
              +-------------+-------------+
                            |  MongoDB driver
                            v
              +---------------------------+
              |  MongoDB (NoSQL)          |
              |  users, solar_station_info|
              |  energy_booking_slots,    |
              |  energy_reservations      |
              +---------------------------+
```

- **FAT service pattern.** Controllers only map HTTP, authorization attributes and delegation. Rules live in `Services/`.
  A CI script (`.github/scripts/check-fat-service.py`) fails the build if a controller touches MongoDB or if the web
  app performs raw HTTP outside `web/src/api.ts`.
- **Authentication.** Login returns a JWT (HMAC-SHA256, 60-minute default). Every authenticated request re-reads the
  account and compares a per-user `session_version`, so deactivating or reactivating an account immediately kills old
  tokens.
- **Native Android only.** Kotlin with Activities, Fragments and the Navigation component. No Flutter, React Native
  or Xamarin. Compose is declared in Gradle but the screens are built from Views and Fragments.
- **Local persistence on Android.** SQLite (`account.db`) stores the signed-in profile and the session token, encrypted
  with AES-GCM using an Android Keystore key, plus a read-only cache of the last node list. Passwords are never stored.
  The API stays the source of truth for all business data.

## 3. Features by role

### Backoffice (web)
- Sign in with role-based access; prosumers are redirected to the Android app.
- User administration: create Backoffice, Grid Operator or Prosumer accounts, edit profiles, search, activate,
  deactivate and reactivate; **pending activation** and **deactivation request** queues.
- Microgrid nodes: create and edit hubs with GPS, power capacity (kW), battery slots (kWh) and weekly schedule;
  deactivate and reactivate (blocked while active reservations exist); delete a hub that has no booking history;
  mark slots available or unavailable.
- Reservations: create on behalf of a prosumer, edit, cancel, approve or reject, filter by view, search.

### Grid Operator (web and Android)
- Web: view nodes and update battery-slot availability; monitor, approve or reject reservations; complete a transfer by
  pasting the QR token.
- Android: pending approvals, approval detail, booking list with current, pending and history views and search,
  and **Complete a transaction** by scanning the prosumer's QR code with the camera (zxing), verified by the server.

### Solar Prosumer (Android)
- Register with NIC as the primary key (account starts pending until Backoffice activates it), sign in, edit profile,
  request deactivation.
- Reserve, modify or cancel energy drop-off or charging slots through node picker, slot picker and form; a summary page
  appears after every action and states the 12-hour notice.
- Dashboard with pending and upcoming approved counts; current, pending and history lists; search.
- Nearby grid nodes on Google Maps, plotted from stored latitude and longitude, with node details and a shortcut to book.
- Approved bookings display a secure transaction QR code.

## 4. Business rules and where they are enforced

All rules are enforced in the API (`backend/SmartSolarMicrogrid.Api/Services`). The clients only display the results.

| Rule | Enforced in |
| --- | --- |
| NIC is the unique primary key (Mongo `_id`); email unique | `UserService`, MongoDB index |
| Public registration always creates a pending, inactive Prosumer | `AuthService.RegisterAsync` |
| Only Backoffice creates staff accounts and activates, deactivates or reactivates accounts | `BackOfficeController`, `BackOfficeService` |
| Prosumer deactivation is a request; Backoffice completes it | `UserService.RequestDeactivationAsync` |
| Old tokens die when account status changes | `session_version` check in `Program.cs` |
| Node deactivation blocked while non-terminal reservations exist | `MicrogridNodeService.SetStatusAsync`, `NodeReservationGuard` |
| Capacity, slot and schedule edits blocked while reservations are active; slots with booking history cannot be removed; a node with any history cannot be deleted | `MicrogridNodeService` |
| Concurrent node edits cannot overwrite each other (optimistic `Revision`) | `MicrogridNodeService.SaveAsync` |
| Reservations must start in the future and **within 7 days** | `ReservationService.RequireBookableSlotAsync` |
| Reservations must fit the node's weekly opening hours (Asia/Colombo, same local day) | `ReservationService.RequireOpen` |
| Requested kWh must fit slot capacity, node power for the duration, and remaining capacity of overlapping bookings | `ReservationService` |
| **Updates and cancellations need at least 12 hours' notice** before the existing start | `ReservationService.RequireNotice` |
| An edited approved reservation returns to pending and its QR is revoked | `ReservationService.UpdateAsync` |
| Approval generates a random 256-bit QR token, visible only to the owning prosumer | `ReservationService.DecideAsync`, `ToResponse` |
| A QR token completes a transfer exactly once (atomic find-and-update) | `ReservationService.CompleteAsync` |

## 5. Data model (MongoDB)

Database: `smart_solar_microgrid` (configurable). The four assessed collections:

| Collection | Key fields | Notes |
| --- | --- | --- |
| `users` (User's detail) | `_id` = **NIC**, `UserName`, `Email` (unique), `PasswordHash` (BCrypt), `Role` (`BACKOFFICE`, `GRID_OPERATOR`, `PROSUMER`), `Activation`, `ActivationPending`, `DeactivationRequested`, `SessionVersion` | NIC is the primary key |
| `solar_station_info` (SolarStationInfo) | `_id`, `Name`, `Address`, `Latitude`, `Longitude`, `PowerCapacityKw`, embedded `BatterySlots[]` (`Id`, `Name`, `CapacityKwh`, `IsAvailable`), embedded `Schedule[]` (`DayOfWeek`, `OpensAt`, `ClosesAt`), `TimeZone`, `IsActive`, `Revision` | Slots and schedule embedded so one edit is one atomic write |
| `energy_booking_slots` (EnergyBookingSlots) | `_id`, `NodeId`, `SlotId`, `ReservationId`, `CapacityKwh`, `StartsAtUtc`, `EndsAtUtc` | The booked time window; indexed by node and slot |
| `energy_reservations` (Energy Reservation) | `_id`, `ProsumerNic`, `NodeId`, `SlotId`, `BookingSlotId`, `Status` (`PENDING`, `APPROVED`, `COMPLETED`, `CANCELLED`, `REJECTED`), `Direction` (`DROP_OFF`, `CHARGING`), `RequestedKwh`, `StartsAtUtc`, `EndsAtUtc`, `QrToken` (unique, sparse), timestamps | References the prosumer by NIC and the hub and slot by id |

References: `ProsumerNic` points to `users._id`; `NodeId` and `SlotId` point into `solar_station_info`;
`BookingSlotId` points to `energy_booking_slots._id`, which points back through `ReservationId`.

## 6. REST API reference

Base path `/api`. Bearer JWT unless marked public. Swagger UI is served at `/swagger` in the Development environment.

| Method and path | Roles | Purpose |
| --- | --- | --- |
| POST `/register` | public | Register a pending prosumer |
| POST `/login` | public | Returns token and profile |
| GET, PATCH `/user` | any signed-in | Own profile, edit name and email |
| POST `/user/deactivation` | Prosumer | Request deactivation |
| GET `/users`, `/users/{nic}` | Backoffice | Directory and queues |
| POST `/back-office/users` | Backoffice | Create an active account of any role |
| PATCH `/back-office/{nic}` | Backoffice | Edit a profile |
| PATCH `/back-office/{nic}/status?active=` | Backoffice | Activate or deactivate |
| GET `/nodes`, `/nodes/{id}`, `/nodes/nearby` | any signed-in | List, detail, radius search (nearest first) |
| POST `/nodes`, PUT `/nodes/{id}`, DELETE `/nodes/{id}?revision=` | Backoffice | Create, update, delete (no history) |
| PATCH `/nodes/{id}/status` | Backoffice | Activate or deactivate (guarded) |
| PATCH `/nodes/{id}/slots/{slotId}/availability` | Backoffice, Grid Operator | Slot availability |
| POST `/reservations` | Prosumer, staff | Create (staff supply the prosumer NIC) |
| GET `/reservations?view=&search=` | any signed-in | `current`, `pending`, `history`, `all` plus search |
| GET `/reservations/summary` | any signed-in | Pending and approved-future counts |
| GET, PUT `/reservations/{id}` | owner or staff | Detail, update |
| POST `/reservations/{id}/cancel` | owner or staff | Cancel |
| POST `/reservations/{id}/decision` | Backoffice, Grid Operator | `APPROVE` or `REJECT` |
| POST `/reservations/complete` | Grid Operator | Finalize a transfer from a QR token |
| GET `/health` | public | Pings MongoDB |

Errors are JSON: `{ "message": "..." }` with 400 (validation), 401, 403, 404, 409 (rule or concurrency conflict) or 503.

## 7. Repository layout

```
backend/SmartSolarMicrogrid.Api/   C# Web API (Controllers, Services, Models, DTO, Interfaces, Configuration)
web/                               React 19 + TypeScript + Vite + Bootstrap 5 portal
android/                           Native Android (Kotlin) project
tests/SmartSolarMicrogrid.AuthChecks/  HTTP + MongoDB integration checks
.github/                           CI workflow and architecture guard script
docs/                              Diagrams and design notes
evidence/                          Screenshots and test evidence (add before submission)
SmartSolarMicrogrid.slnx           Solution file
```

## 8. Running the system locally

Prerequisites: .NET 10 SDK, Node.js 22, MongoDB on `localhost:27017`, Android Studio (for the app).

### API
```powershell
Copy-Item backend/SmartSolarMicrogrid.Api/.env.example backend/SmartSolarMicrogrid.Api/.env
# edit .env: set Jwt__Key to a random value of at least 32 characters
dotnet run --project backend/SmartSolarMicrogrid.Api --launch-profile http
```
Check `http://localhost:5086/api/health`. The API loads `.env` but real environment variables win. Create the first
Backoffice account once with `Bootstrap__UserName`, `Bootstrap__Email`, `Bootstrap__NIC`, `Bootstrap__Password` set, then:
```powershell
dotnet run --project backend/SmartSolarMicrogrid.Api -- --bootstrap-admin
```
Remove the bootstrap values afterwards. `--migrate-user-identities` upgrades an old pre-NIC users collection.

### Web portal
```powershell
cd web
npm install
npm run dev        # http://localhost:5173 (allowed by the default CORS policy)
```
`VITE_API_URL` (see `web/.env.example`) selects the API address; the default is `http://localhost:5086`.

### Android app
1. Open `android/` in Android Studio and let Gradle sync.
2. Debug builds call `http://10.0.2.2:5086` (the emulator's route to the host API). Release builds use `https://`.
   Set the real service URL in `android/app/build.gradle.kts` (`API_BASE_URL`) before building a release.
3. Google Maps: create a key for the Maps SDK for Android and put `MAPS_API_KEY=...` in `android/local.properties`
   (gitignored) or the `MAPS_API_KEY` environment variable. Without a key the app builds but map tiles are blank.
4. Run on an emulator or device. Camera permission is needed for QR scanning, location permission for the nearby map.

## 9. Hosting the API on IIS

1. Install the **.NET 10 Hosting Bundle** and enable IIS with the ASP.NET Core Module V2.
2. Publish: `dotnet publish backend/SmartSolarMicrogrid.Api -c Release -o C:\inetpub\microgrid-api`
3. Create an IIS site pointing at that folder with an **No Managed Code** application pool.
4. Set machine or pool environment variables: `MongoDb__ConnectionString`, `MongoDb__DatabaseName`, `Jwt__Key`,
   `Cors__AllowedOrigins__0` (the web portal origin), and `ASPNETCORE_ENVIRONMENT=Production`.
5. Bind HTTPS with a certificate, then browse to `/api/health` to confirm MongoDB connectivity.
6. Point `VITE_API_URL` (web build) and `API_BASE_URL` (Android release) at the site.

**TODO before submission:** run these steps on the team's IIS host and add screenshots of the site, application pool and
a `/api/health` response to `evidence/` and the report. This README does not claim an IIS deployment has been verified.

## 10. Testing and quality gates

`tests/SmartSolarMicrogrid.AuthChecks` starts the real API against a uniquely named disposable MongoDB database and
exercises it over HTTP with real JWTs. It currently has **190 checks** covering accounts, roles, NIC and activation,
nodes, concurrency, and reservations (7-day horizon, 12-hour notice, capacity, approval, QR issue and one-time
completion, privacy between prosumers). Last full run: 190 of 190 passed against MongoDB 7.

```powershell
dotnet build backend/SmartSolarMicrogrid.Api -c Release -o tmp/auth-api
dotnet run --project tests/SmartSolarMicrogrid.AuthChecks -- tmp/auth-api/SmartSolarMicrogrid.Api.dll
```
Set `TEST_MONGO_CONNECTION_STRING` to use a different MongoDB. GitHub Actions
(`.github/workflows/clean-code-architecture.yml`) runs a warning-free API build, `dotnet format`, front-end `tsc`,
ESLint and Vite build, and the fat-service architecture guard on every branch and pull request.

The Android project compiles (`./gradlew compileDebugKotlin`). The Android instrumentation test `AccountFlowTest`
has not been executed on a device, and the device behaviour of Maps, GPS and the camera scanner still needs a manual run.

## 11. Known limitations and decisions

- **Node deletion.** The brief says deactivate, while the rubric mentions deleting stations. Both exist: nodes are
  deactivated normally and can be deleted only when they have no booking history.
- **Units.** The brief writes "kW/h". Power capacity is stored in **kW** and battery slot capacity in **kWh**.
- **Schedules** are one same-day opening interval per weekday in Asia/Colombo time; overnight intervals are unsupported.
- Reservation writes and node lifecycle changes are not wrapped in a multi-document transaction; capacity is re-checked
  after saving and rolled back on conflict.
- The web reservation form lets Backoffice create bookings; Grid Operators view, approve and reject.
- The Android release `API_BASE_URL` is a placeholder until the IIS address is fixed.

## 12. Submission checklist

- [ ] ZIP named with the IT number (for example `IT15895623.zip`) containing all directories, the report and a screenshot of the app's opening screen.
- [ ] Report: screenshots of every UI, high-level, use case and DFD diagrams, database design, source code pasted as text, references, repository link, individual contributions, challenges.
- [ ] Every `.cs` file has a header comment block and a comment at the start of every method.
- [ ] README final: member names and IT numbers, contribution table, demo video link (maximum 5 minutes).
- [ ] IIS deployment and MongoDB sample-data evidence added to `evidence/`.
- [ ] All members can explain the whole system for the compulsory viva.

## 13. AI assistance disclosure

The assignment brief permits AI only for initial planning and expects independent implementation. This repository was
not produced under that condition and should not be described as wholly independently authored:

- AI tools helped write the initial requirements summary and plan.
- AI assistance was also used during implementation. Earlier repository documentation recorded this for the
  authentication and user-management rewrite, the Android summary, loading and Maps fixes.
- The latest hardening (Android notice and edit-form fixes, SQLite node cache, reservation integration checks,
  template clean-up, this README) was written with Claude Code and reviewed by the team.

Each member must state in their own contribution section which parts they wrote, which parts were AI-assisted, and what
they reviewed and tested themselves.
