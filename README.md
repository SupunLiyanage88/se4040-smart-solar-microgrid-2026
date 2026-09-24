# Smart Solar Microgrid Trading System

SE4040 - Enterprise Application Development | Year 4, Semester 2 | Assignment 1, 2026

**Status: authentication, user management and microgrid node management implemented; reservation workflows, QR transfers and maps remain unfinished.**

See [Authentication and user management](docs/auth-user-management.md) for the account rules, existing-user migration, first-officer bootstrap, API contracts and verification commands. This functional implementation is AI-assisted; the guide records the assignment's independent-implementation constraint. The planning checklist below remains historical and is not completion evidence.

- Repository: https://github.com/SupunLiyanage88/se4040-smart-solar-microgrid-2026
- Team: four members; names, IT numbers and GitHub handles to be completed by the team.
- Submission deadline in the brief: **30 September 2026, 11:59 PM**. Confirm the submission portal's time zone.
- Demo video: **TODO - add a YouTube or OneDrive link, maximum 5 minutes.**
- Source: supplied **EAD_SE4040_Assignment_2026.pdf**, pages 1-9. Page references below refer to that brief. The original PDF is not included here.

This README is an initial planning proposal for team review. Assignment requirements are distinguished from proposed design choices and unresolved questions. All implementation and delivery checkboxes remain open.

## Microgrid node management

Backoffice can create/edit grid hubs, set GPS coordinates and an address, define power capacity and battery slots, manage weekly operating hours, and deactivate/reactivate hubs. Grid Operators can view nodes and change physical-slot availability. The web portal now has **Grid nodes** and **User accounts** sections; Grid Operators see only node operations. Authenticated prosumers can read active hubs through the API for future mobile integration.

### Node rules and data

- MongoDB collection: `solar_station_info`. Physical battery slots and schedules are embedded in each hub so a configuration edit is a single atomic document update. Physical slots have stable, server-issued IDs.
- Power capacity uses **kW**, while individual battery storage capacity uses **kWh**. The assignment says `kW/h`; this implementation distinguishes power from stored energy. Confirm the intended units with the lecturer.
- Latitude must be between -90 and 90; longitude between -180 and 180. Positive capacities, unique nonblank slot labels, at least one physical slot and at least one operating day are required.
- Schedules are weekly, in **Asia/Colombo** time: 0=Sunday through 6=Saturday, one `HH:mm` opening/closing interval per selected day. Omitted days are closed; overnight intervals are not supported.
- Deactivation checks the real `energy_reservations` collection. Reservations reference the hub with BSON `NodeId` and the physical slot with `SlotId`; `Status` uses canonical uppercase strings. Only `COMPLETED`, `CANCELLED` and `REJECTED` are terminal. All other/missing states block deactivation, including `PENDING`, `APPROVED` and `IN_PROGRESS`, regardless of dates.
- Active reservations also block changes to capacity, physical slots and schedules. Name/address/GPS edits remain allowed. A slot referenced by booking history cannot be removed; it can be marked unavailable once active reservations finish. Hubs are deactivated rather than deleted, preserving history.
- An unavailable physical slot cannot be offered by the future booking service. The availability flag is an operational setting, not a calculation of free capacity at a particular time.
- Each mutation requires the current `revision`; stale or simultaneous edits return 409 rather than overwriting newer changes. Only Backoffice can administer hubs; operators have a separate, narrow availability endpoint.

**Reservation integration boundary:** This branch implements the deactivation guard and tests it with seeded reservation records; it does not implement booking creation or its UI. The future reservation writer must use the documented collection/field/status contract, enforce active-node/schedule/slot checks, and coordinate reservation writes with node lifecycle changes (for example through transactions with a shared node revision on a MongoDB replica set). The current cross-collection reservation read and node update are not a transaction; optimistic revisions currently protect concurrent node edits, not concurrent future booking inserts. Physical slots are distinct from future time-based `EnergyBookingSlots` records.

### Node endpoints

| Method/path | Permission | Operation |
| --- | --- | --- |
| GET `/api/nodes` | Active account | List hubs; prosumers see active hubs only |
| GET `/api/nodes/{id}` | Active account | Hub, GPS, physical slots and schedule |
| POST `/api/nodes` | Backoffice | Create a hub |
| PUT `/api/nodes/{id}` | Backoffice | Update configuration using current revision |
| PATCH `/api/nodes/{id}/status` | Backoffice | `{isActive, revision}`; active bookings produce 409 on deactivation |
| PATCH `/api/nodes/{id}/slots/{slotId}/availability` | Backoffice / Grid Operator | `{isAvailable, revision}` |

Create/update payload fields: `name`, `address`, `latitude`, `longitude`, `powerCapacityKw`, `batterySlots` (`name`, `capacityKwh`, `isAvailable`) and `schedule` (`dayOfWeek`, `opensAt`, `closesAt`). For updates include the node's `revision` and retain each existing slot's `id`; omit IDs for new slots. Status, timestamps and node identity are server-controlled.

### Node verification

The existing integration runner now covers authentication and node management using a disposable MongoDB database:

```powershell
dotnet build tests/SmartSolarMicrogrid.AuthChecks -o tmp/node-checks -p:UseAppHost=false
dotnet tmp/node-checks/SmartSolarMicrogrid.AuthChecks.dll tmp/node-checks/SmartSolarMicrogrid.Api.dll
npm run build --prefix web
npm run lint --prefix web
```

All **123 integration checks** passed (62 authentication checks and 61 node checks), covering input validation, persistence, role restrictions, metadata/schedule/capacity updates, active-reservation blocking, terminal states, slot-history preservation, inactive-node visibility and concurrent node edits. The API/check runner built without warnings or errors. Web build and lint passed. Browser checks passed for hub creation, capacity/schedule editing, deactivation, operator-only slot availability, and hiding hub administration controls from operators. No application database was modified; test records use a uniquely named disposable database.

## Local development

Prerequisites: .NET 10 SDK, Node.js, and MongoDB listening on `localhost:27017`.

Start the API from the repository root:

```powershell
dotnet restore SmartSolarMicrogrid.slnx
dotnet run --project backend/SmartSolarMicrogrid.Api --launch-profile http
```

Verify the real MongoDB connection at `http://localhost:5086/api/health`. A successful response identifies the `smart_solar_microgrid` database and reports `Healthy`.

Development defaults are stored in `appsettings.Development.json`. The backend `.env.example` documents the equivalent environment variable names for deployment and overrides. The API loads a local `.env` through DotNetEnv without overriding existing process environment variables.

Start the web client in another terminal:

```powershell
Set-Location web
npm install
npm run dev
```

The default local CORS policy permits the Vite client at `http://localhost:5173`. Never commit real credentials, tokens, or production connection strings.

### Android nearby-node map (Google Maps)

1. Create a Google Cloud project, enable billing, and enable **Maps SDK for Android** (follow the official setup guide).
2. Create an API key restricted to Android apps (your debug/release SHA-1 fingerprints + package `com.example.smartsolarmicrogridmobile` / `.debug`) and to the Maps SDK for Android API.
3. Put the key outside Git in `android/local.properties` (already gitignored) or a `MAPS_API_KEY` environment variable:
   `MAPS_API_KEY=YOUR_KEY_HERE`
   Gradle injects it into the manifest at build time; builds without a key compile but render blank tiles.
4. Restart the backend so the new `GET /api/nodes/nearby?latitude=..&longitude=..&radiusKm=..` endpoint is live (it validates coordinates/radius and returns active nodes sorted nearest first). Older servers still work: the app falls back to client-side distance filtering.

## 1. Outcome and required architecture

Deliver an end-to-end system in which solar prosumers reserve energy drop-off/charging slots, Backoffice staff administer users and grid nodes, and Grid Operators manage operations and verify completed transfers.

| Component | Requirement from the brief | Evidence of completion |
| --- | --- | --- |
| Central service | C# Web API hosted on Windows IIS; FAT Service pattern with all business rules in the central API (pp. 2-3) | Both real clients use the IIS-hosted service for all business operations |
| Server database | NoSQL; MongoDB is explicitly assessed in the rubric (pp. 7-9) | Working MongoDB persistence and consistent references across the four assessed collections |
| Web application | Responsive UI for Backoffice and Grid Operator; Bootstrap 5, Tailwind CSS or React.js listed in the specification (pp. 1-2) | Role-appropriate screens, REST integration and graceful errors |
| Android application | Pure native Android; no cross-platform frameworks; local SQLite for user information/persistence (pp. 2-3, 9) | Prosumer and operator modes, SQLite persistence, REST calls, Maps and QR |
| Boundaries | Both clients are UI layers; neither directly accesses the server database (pp. 2-3, 9) | Authorization, reservation rules and transfer verification remain authoritative in the API |

**Proposed choices to review:** MongoDB for the server; Java or Kotlin with native Android views and SQLite; choose Bootstrap 5 or Tailwind CSS for the web UI to also satisfy the styling rubric on p. 7. React is optional. Record exact framework/runtime versions after checking the team's development machines and IIS host.

Proposed repository layout, to be created by the team during implementation:

- backend/ - C# API and service-owned business rules.
- web/ - web UI and REST integration.
- android/ - native Android project and local SQLite storage.
- docs/ - reviewed diagrams, design decisions, database design, report and deployment notes.
- evidence/ - original UI screenshots and test/demo evidence using synthetic data.

## 2. Requirements and implementation backlog

Task IDs are planning references, not existing GitHub issues. Suggested owners A-D are placeholders, not claims about individual contributions. Resolve decisions in section 4 before dependent work.

| ID | Work to complete | Suggested lead | Depends on | Acceptance criteria | Source |
| --- | --- | --- | --- | --- | --- |
| P01 | Agree scope, role permissions, state transitions and API contracts | All | None | Review this plan; resolve ambiguities; record request/response shapes and errors before client integration | pp. 1-3 |
| P02 | Create projects, MongoDB data model and early IIS deployment | A + B | P01 | API runs on IIS; web and a physical/emulated Android client can reach it; all four assessed collections have synthetic sample data | pp. 2-3, 7 |
| P03 | Authentication and role authorization | A | P01, P02 | API checks roles and account state; web routes Backoffice/Operator appropriately; mobile routes Prosumer/Operator; unauthorized direct API requests fail | pp. 1-2, 8 |
| P04 | Backoffice user administration | B | P03 | Backoffice can create web users with Backoffice or Grid Operator roles; operators cannot perform system administration | pp. 1, 8 |
| P05 | Prosumer lifecycle and activation queue | C + B | P03 | Mobile registration uses unique NIC identity; own-profile editing and deactivation request work; web supports create/update/deactivate; pending activations are visible/actionable; only Backoffice reactivates | pp. 2, 8 |
| P06 | Grid nodes and schedules | B | P02, P03 | Create/update nodes with GPS, capacity and battery slots; update schedules/availability; block deactivation when active reservations exist; resolve deletion wording before destructive operations | pp. 2-3, 8 |
| P07 | Reservation API and capacity rules | A | P05, P06 | Create/update/cancel reservations centrally; enforce 7-day booking horizon and 12-hour change/cancel notice; define approval flow; prevent overbooking | pp. 2, 8; concurrency is proposed hardening |
| P08 | Web reservation operations | B | P07 | Authorized staff create/update/cancel bookings and monitor pending reservations; server rule errors are visible and do not falsely report success | pp. 2-3, 8 |
| P09 | Mobile reservation workflow | C | P07 | Prosumer creates/modifies/cancels own bookings; each successful action shows a summary page; approved reservation displays a secure transaction QR | pp. 2, 8 |
| P10 | Booking views, search and dashboard | C + D | P07 | Current/pending bookings, history and working filters use live API data; show pending count and approved future reservation count; refresh after changes | pp. 2, 9 |
| P11 | Operator QR verification and transfer completion | D + A | P03, P07, P09 | Operator scans QR, verifies server record, finalizes transfer and updates booking to done; invalid/stale/reused QR cannot cause duplicate completion | pp. 2, 9; replay checks are proposed hardening |
| P12 | Nearby node map | D | P06 | Google Maps plots stored latitude/longitude from API data; selecting a nearby node shows details; handle denied location permission and empty results | pp. 2, 9 |
| P13 | Android SQLite and integration resilience | C + D | P03, P05, P10 | Login-related profile/reference data persists across app restart; API remains source of truth; expired sessions and unavailable server give clear recovery; no raw passwords cached | pp. 2-3, 9; secure storage handling is proposed |
| P14 | End-to-end tests and deployment rehearsal | All | P04-P13 | Test section 6 on IIS with both clients; fix failures; verify no client directly accesses MongoDB; document reproducible setup | pp. 7-9 |
| P15 | Report, video, contribution evidence and submission | All | P14 | Complete every item in section 7 and rehearse individual viva explanations | pp. 4-6, 8 |

## 3. Proposed design work

These are discussion starting points, not implemented schemas or final diagrams.

### Data model to develop

The rubric names four data groups (p. 7). Agree concrete collection names, fields, validation and indexes, and show their references in the report.

| Assessed data group | Candidate information to consider |
| --- | --- |
| User's detail | Role, account state, authentication metadata; NIC as primary identity for prosumers; profile and activation/deactivation information |
| SolarStationInfo | Node identity, name, latitude/longitude, documented capacity units, operating schedule and active state |
| EnergyBookingSlots | Node reference, start/end times, battery/storage slot identifiers and availability/capacity |
| Energy Reservation | Prosumer NIC reference, node/slot reference, trading direction, requested capacity, lifecycle status, timestamps and transfer confirmation |

Propose unique NIC enforcement for prosumers, consistent reservation references, and concurrency-safe slot allocation. Store canonical times consistently on the server and document conversions for display. Use synthetic NICs and demo accounts in evidence; keep passwords, tokens, connection strings and unrestricted API keys out of Git.

### API contract decisions

Before implementing clients, document operations for authentication, web users, prosumers/activations, nodes/schedules, slots, reservations/approval, booking queries/dashboard counts, QR verification and completion. For each operation, agree allowed roles, ownership rules, validation, response data, error cases and repeat-request behavior.

Proposed lifecycle for team review: pending reservation -> approved -> completed, with separate rejected/cancelled outcomes. Define which states consume capacity and block node deactivation. A QR should encode an opaque server-issued reference or verifiable token; Android renders it after approval, and the API verifies it before completing a transfer. Do not encode a NIC or trusted business state in a freely editable QR payload.

### Screens to plan

- Web: home/login; Backoffice dashboard; web-user administration; prosumer list/detail/edit; pending activations; grid node list/create/edit; schedules and availability; booking list/detail/create/edit/cancel; operator dashboard.
- Prosumer Android: registration/login; role home; profile/edit/deactivation request; dashboard; nearby-node map/detail; booking creation/edit/cancellation; action summary; current/pending/history/search; approved booking QR.
- Operator Android: login/home; operational booking views; scanner; server verification details; completion result and failure states.

## 4. Decisions to resolve before implementation

The team should record each answer, who confirmed it, and the effect on design. Do not treat the proposals below as new assignment requirements.

| Topic | Ambiguity or decision | Proposed starting point |
| --- | --- | --- |
| Capacity units | Specification uses kW/h; intended power/energy units are unclear | Confirm with lecturer; distinguish kW power from kWh energy in the final model |
| 7-day horizon | Calendar days vs rolling 168 hours, time zone and exact endpoint are unspecified | Use server time; propose a rolling inclusive horizon and document the agreed boundary |
| 12-hour notice | Which start time governs a moved reservation? | Require sufficient notice before the existing slot and validate the destination independently; confirm with lecturer |
| Activation | Rubric adds a pending-activation web view; initial activation workflow is not fully defined | Propose Backoffice activation after registration; define pending-account login/view restrictions |
| Deactivation | Mobile requests deactivation; treatment of active bookings is unspecified | Agree approval/cancellation behavior and enforce it consistently in the API |
| Approval and permissions | Who approves reservations and which nodes an operator can control are not fully specified | Define an explicit role/ownership matrix before UI development |
| Node deletion | Specification describes deactivation, but rubric mentions deleting stations and slots | Clarify expected deletion; preserve booking history and enforce active-reservation guard |
| QR lifetime | Token format, expiry and behavior after rescheduling are unspecified | Invalidate old QR references after changes and prevent replay; agree the lifecycle |
| Transfer completion | Metered quantity, trading direction and capacity accounting are not detailed | Agree minimum transfer data and resulting slot/booking updates; financial settlement is not specified |
| Android libraries | Pure Android/no frameworks wording may affect support libraries | Confirm allowed native libraries and choose Java/Kotlin; do not use Flutter or React Native |
| SQLite login data | Rubric requests login details/reference persistence without security specifics | Persist profile/session metadata as appropriate; avoid raw password storage; keep server authentication authoritative |
| Web styling | React is listed in specification, but rubric explicitly assesses Bootstrap/Tailwind | Use one of those CSS frameworks with the chosen web UI approach |

## 5. Four-person delivery plan

Suggested work allocation only. Replace placeholders after agreement. Every member should contribute meaningful code, tests, review and report evidence and understand the complete system for the viva; the assignment awards individual marks separately.

| Member | Name / IT number / GitHub | Proposed primary responsibility | Evidence to fill during development |
| --- | --- | --- | --- |
| A | TODO | API, authentication, reservation rules, IIS integration | Commit/PR links, tests, design decisions, report sections |
| B | TODO | Web UI, staff/prosumer administration, nodes and schedules | Commit/PR links, screenshots, integration tests, report sections |
| C | TODO | Native Android prosumer flows, SQLite, bookings/dashboard | Commit/PR links, device tests, screenshots, report sections |
| D | TODO | Native Android operator mode, QR, maps, shared integration/evidence | Commit/PR links, device tests, screenshots, report sections |

| Target dates (2026) | Planned milestone | Exit check |
| --- | --- | --- |
| Sep 14-16 | Scope, contracts, data design, team split and project setup | Decisions recorded; early IIS API reachable by both clients |
| Sep 17-20 | Authentication, accounts, nodes, SQLite and basic booking flow | First integrated prosumer-to-node reservation works |
| Sep 21-24 | Rules, approval, booking views, QR completion and maps | Full approved booking-to-completion flow demonstrated |
| Sep 25-27 | Integration, boundary tests, role/security checks, UI completion | Section 6 checked with reproducible evidence |
| Sep 28-29 | Report, screenshots, video, contribution review and ZIP rehearsal | Package opens on a clean machine; links work |
| Sep 30, before 11:59 PM | Final submission with buffer | Correctly named ZIP submitted and receipt retained |

Use short feature branches and descriptive commits. Each implementation PR should state task IDs, behavior changed, validation performed, contributor ownership and relevant references. Record actual contributions as work happens; do not backfill invented claims. Keep the initial planning PR separate from implementation.

## 6. Acceptance and test checklist

Planned checks, **not executed tests**. Record actual results and evidence during implementation.

- [ ] Both clients reach the IIS-hosted C# API and changes survive API/database restart.
- [ ] Unauthenticated requests fail; each role lands on the correct home; operators cannot administer users or reactivate accounts; prosumers cannot access another person's reservations.
- [ ] Duplicate NIC registration fails; own-profile edit succeeds; pending activation is actionable in web; deactivation and Backoffice-only reactivation follow agreed rules.
- [ ] Create/update node coordinates, capacity, schedules and battery slots; invalid data fails; deactivation with active reservations is blocked.
- [ ] Test reservation creation in the past, inside the 7-day horizon, at its agreed boundary and immediately beyond it using controlled server time.
- [ ] Test update and cancel at 12 hours exactly, just before and just after that cutoff; also validate any new target slot against horizon and availability.
- [ ] Simultaneous requests for the last available capacity cannot overbook; retries cannot double-book or double-complete a transfer.
- [ ] Successful mobile create/update/cancel shows the correct summary; rejected changes preserve the prior reservation.
- [ ] Current, pending, history, search filters, pending count and approved future count match API records; handle empty lists and pagination if introduced.
- [ ] Only approved bookings expose valid QR dispatch; operator verifies with server; test altered, cancelled, outdated and already-used QR data.
- [ ] Successful completion updates booking/history and agreed capacity accounting; server failure does not show false completion.
- [ ] Google Maps uses actual stored node coordinates and details; test location denied, no nearby nodes and unavailable API.
- [ ] SQLite retains intended user/reference data after restart; logout/account changes clear stale identity; local edits cannot bypass service rules.
- [ ] Web and Android handle loading, empty results, validation failures, expired sessions and network errors.
- [ ] Review each C# file for a header comment block and a comment at the beginning of every method as required on p. 4.
- [ ] Fresh-machine deployment instructions work; secrets are externalized; screenshots and sample data are original and suitable for sharing.

## 7. Submission and viva checklist

Requirements from pp. 4-6 and documentation rubric on p. 8:

- [ ] One ZIP containing all project directories/files, detailed report and main opening menu/screen screenshot.
- [ ] ZIP filename includes the required IT number, e.g. IT15895623.zip; confirm which team member's number to use.
- [ ] Report includes unique screenshots of every UI, high-level architecture diagram, use case diagram and DFD.
- [ ] Report includes database design, source code pasted as text (not screenshots), all references and repository link.
- [ ] Report identifies individual contributions with real Git evidence and discusses actual challenges.
- [ ] README has the final repository URL, completed contribution table and working YouTube/OneDrive demo link of no more than 5 minutes.
- [ ] All C# file header and method-start comments are present; externally sourced snippets are attributed in code as required by the brief.
- [ ] Reproducible IIS, MongoDB, web and Android setup instructions and safe sample data are supplied.
- [ ] Each member discloses planning-stage AI use and writes their own reflection in the contribution section.
- [ ] All four members can explain and modify their own work and attend the compulsory supervised viva.
- [ ] Submit by 30 September 2026, 11:59 PM and retain submission confirmation.

## 8. Marking coverage

| Group criteria (35 marks) | Marks | Planning coverage |
| --- | --- | --- |
| Service architecture / IIS / MongoDB | 8 | P01-P03, P14 |
| Database design and four collections | 4 | P02, section 3 |
| Native Android/SQLite and web UI architecture | 12 | P08-P13, P14 |
| UI/UX and page completeness | 6 | P04-P13, screen inventory |
| Documentation and deployment | 5 | P14-P15, section 7 |

| Individual criteria (65 marks) | Marks | Planning coverage |
| --- | --- | --- |
| Web features and business rules | 18 | P03-P08 |
| Mobile authentication and account management | 9 | P03, P05, P13 |
| Reservation workflow and action summaries | 9 | P07, P09 |
| Booking views and dashboards | 10 | P10 |
| Operator verification and map features | 7 | P11-P12 |
| Service integration, SQLite and device capabilities | 12 | P08-P14 |

The brief states a total of 100 marks: 35 group and 65 individual, with the assignment contributing 20% to the module. Do not treat the suggested work split as an entitlement to marks; individual assessment depends on demonstrated understanding and contribution.

## 9. Planning provenance and team review

This initial requirements summary and work plan were prepared with OpenAI Codex from the supplied assignment PDF. No application implementation, database scripts or final submission report is included in this planning change.

The assignment's AI guidance on p. 6 permits AI during initial planning and requires independent implementation. Team members must critically evaluate and refine this plan, record their own design decisions and disclose actual planning-stage AI use in their individual report sections. This paragraph is a provenance record, not a substitute for each member's reflection.

- [ ] All four members reviewed requirements against the original brief.
- [ ] Team identities and ownership agreed; unresolved decisions recorded.
- [ ] Each member recorded which planning suggestions were accepted, revised or rejected and why.
- [ ] Team independently develops and verifies the final solution.
