# Authentication and user management

This branch implements the account-related requirements on assignment pages 1-2, 8 and 9. It does not implement grid nodes, reservations, QR transfers or maps, and does not establish IIS deployment or full-assignment completion.

## Account rules

- Roles: `BACKOFFICE`, `GRID_OPERATOR`, `PROSUMER`. Web accepts Backoffice and Grid Operator; Android accepts Prosumer and Grid Operator.
- NIC is the immutable MongoDB `_id`, returned as both `id` and `nic`. Old NIC suffixes are normalized to uppercase. Email is trimmed and lowercased and has a unique index.
- Public registration always creates an inactive, pending prosumer. A submitted role or activation flag cannot grant privileges.
- Backoffice can create active accounts for any of the three roles, edit profile name/email, and activate, deactivate or reactivate accounts.
- Prosumers can edit their own name/email and request deactivation. A request appears in the Backoffice queue; access remains active until an officer completes it. This resolves the brief's unspecified approval workflow explicitly.
- Only Backoffice can reactivate accounts. An officer cannot deactivate their own account.
- Protected requests recheck the database account, current role, and session version. Every activation/status operation rotates the version, so old tokens cannot regain access after reactivation.
- Profile editing cannot change NIC, role, password or status. Accounts are deactivated rather than deleted to preserve references.
- Passwords use BCrypt; validation limits them to 8-72 characters and at most 72 UTF-8 bytes. Password hashes and session versions are never returned to clients.
- Missing/invalid/expired sessions return 401. Authenticated users without permission receive 403. Duplicate identities return 409, including concurrent inserts.

## Existing databases: migrate once before starting the new API

Do not run migration while an old API instance is accepting requests. Stop the old API first. Set the target MongoDB configuration explicitly and keep your own backup before maintenance.

```powershell
dotnet run --project backend/SmartSolarMicrogrid.Api --no-launch-profile -- --migrate-user-identities
```

This validates all legacy users before changing the collection, then copies them into a new collection with NIC `_id`s, canonical emails/roles, and new session versions. It retains the complete original collection as `users_backup_<unique suffix>`. Invalid/duplicate NICs or emails stop migration before writes; correct those legacy records first. Old role names `PROCUMER` and `GRIDOPARATOR` become `PROSUMER` and `GRID_OPERATOR`.

The original model did not distinguish pending registration from deactivation. Migration classifies legacy inactive users as pending; Backoffice must review their intended state. All previous tokens become invalid. The current scaffold has no reservation references to migrate; if other branches add user-ID references, migrate those references before deploying this identity change.

Migration never runs automatically. Normal startup refuses legacy ObjectId user records with a clear maintenance message. A fresh database needs no migration. Keep the backup until data has been verified. To roll back, stop the API, retain the new collection under a different name, restore the backup namespace and run the old application version.

## Create the first Backoffice account

There is no public administrator-registration endpoint or committed default password. With the API stopped, configure the database and the following temporary process environment variables:

```powershell
$env:Bootstrap__UserName = 'Your officer name'
$env:Bootstrap__Email = 'your-officer-email'
$env:Bootstrap__NIC = 'your-valid-NIC'
$env:Bootstrap__Password = Read-Host 'Initial password' -MaskInput
dotnet run --project backend/SmartSolarMicrogrid.Api --no-launch-profile -- --bootstrap-admin
Remove-Item Env:Bootstrap__Password
```

Run the bootstrap command once, as a single maintenance process. It refuses to run if a Backoffice account already exists. New staff are subsequently created through the authenticated portal. Use a random `Jwt__Key` of at least 32 characters; process environment variables take precedence over local `.env` values.

## Run the clients

API: `dotnet run --project backend/SmartSolarMicrogrid.Api --launch-profile http`. MongoDB must be reachable. Health endpoint: `/api/health`.

Web: copy `web/.env.example` to `web/.env` if the API address differs, run `npm install` then `npm run dev` in `web`. Configure `Cors__AllowedOrigins__0` for the actual web origin. The Bootstrap 5 portal includes staff sign-in, role-specific homes, account creation/editing, search, pending activation and deactivation queues, confirmations and clear API error messages. Its token lasts for the browser tab session; the server revalidates access on every protected request.

Android: build/open the existing native project. The debug app uses a separate `.debug` application ID. Its default API URL is `http://10.0.2.2:5086` for the Android emulator. Enter your computer's reachable API address on a physical device; bind the development API appropriately. Release builds require HTTPS. Only debug builds allow local HTTP.

Android includes native registration/login/profile/request screens and role-specific homes. SQLite persists profile/reference data, expiry and an AES-GCM encrypted token; the key is stored in Android Keystore. Passwords are never persisted, account data is excluded from backup/transfer, and logout clears the session row. Restored sessions must pass API validation before showing an authenticated home. Returning to the app refreshes account state; network failures do not authorize offline operations.

## API contract

| Method and path | Access | Purpose |
| --- | --- | --- |
| POST `/api/register` | Public | Pending prosumer registration: userName, email, nic, password |
| POST `/api/login` | Public | email/password -> token, expiresAtUtc, user |
| GET `/api/user` | Active account | Current authoritative profile |
| PATCH `/api/user` | Active account | Own userName/email only |
| POST `/api/user/deactivation` | Active Prosumer | Queue own deactivation request |
| GET `/api/users` | Backoffice | Directory including activation/request flags |
| GET `/api/users/{nic}` | Backoffice | Account detail |
| POST `/api/back-office/users` | Backoffice | userName, email, nic, password, role |
| PATCH `/api/back-office/{nic}` | Backoffice | Update userName/email |
| PATCH `/api/back-office/{nic}/status?active=true` | Backoffice | Activate/reactivate; false deactivates and invalidates sessions |

## Reproducible verification

The HTTP check runner uses real MongoDB and signed JWTs, creates a uniquely named `smart_solar_auth_checks_*` database, and deletes only that test database in its cleanup. It does not use application accounts. Set `TEST_MONGO_CONNECTION_STRING` if MongoDB is not on localhost:27017.

```powershell
dotnet build backend/SmartSolarMicrogrid.Api -o tmp/auth-api -p:UseAppHost=false
dotnet build tests/SmartSolarMicrogrid.AuthChecks -o tmp/auth-checks -p:UseAppHost=false
dotnet tmp/auth-checks/SmartSolarMicrogrid.AuthChecks.dll tmp/auth-api/SmartSolarMicrogrid.Api.dll
```

Coverage includes migration/backup, bootstrap, validation, primary-key storage, role injection, role access denials, profile ownership, duplicate and concurrent registration, activation queues, deactivation requests, inactive login rejection, immediate session revocation, reactivation, expired and malformed tokens.

Web checks: `npm run build` and `npm run lint` in `web`.

Android checks: `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest` in `android`. The `AccountFlowTest` instrumentation tests cover native login, editing, recreation/session restoration, encrypted SQLite storage, logout, deactivation request and Grid Operator routing. They require the disposable API fixture and an emulator/device. To hold that fixture open for testing:

```powershell
dotnet tmp/auth-checks/SmartSolarMicrogrid.AuthChecks.dll tmp/auth-api/SmartSolarMicrogrid.Api.dll --serve-until=tmp/auth-preview.stop
# In another terminal, use the printed API port (replace PORT below):
# From android/:
./gradlew :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.apiUrl=http://10.0.2.2:PORT'
# From repository root when finished:
New-Item tmp/auth-preview.stop -ItemType File
```

Use a new/nonexistent stop-file path for each fixture run. The fixture also closes automatically after 20 minutes. Tests without `apiUrl` skip the two account-flow cases rather than touching an arbitrary application server.

## Assessment provenance

These changes were implemented with AI assistance at the user's request. The assignment's page 6 permits AI only for initial planning and requires independent implementation. Therefore functional coverage does not establish compliance with that independent-work condition. Do not describe this implementation as independently authored. This note records actual provenance, not a claim of lecturer approval.

## Verification performed on this branch

- API and integration-check runner built with zero warnings/errors.
- All 62 real HTTP/MongoDB integration checks passed against a disposable database.
- Web production build and ESLint passed.
- Browser verification passed for Backoffice sign-in, directory display, profile editing, activation approval/queue updates, logout, and Grid Operator routing without administration controls.
- Android debug APK, instrumentation APK and existing unit-test task built/passed. The existing unit tests are scaffold tests; they do not validate the new account flow.
- The two new device account-flow tests compiled but were not executed: the local emulator remained unauthorized in ADB after a cold-start retry. Run them on an authorized emulator/device using the commands above. Runtime SQLite/Keystore behavior and native UI flows are therefore not yet verified on a device.
- The existing application database was not migrated or modified, and the previously running application was not stopped. Apply the documented migration during maintenance before running this branch against legacy data.

Framework references consulted: [ASP.NET Core JWT bearer authentication](https://learn.microsoft.com/aspnet/core/security/authentication/configure-jwt-bearer-authentication), [Android SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper), and [Android HTTP connection implementation](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/refs/heads/androidx-constraintlayout-release/android-35/com/android/okhttp/internal/huc/HttpURLConnectionImpl.java).
