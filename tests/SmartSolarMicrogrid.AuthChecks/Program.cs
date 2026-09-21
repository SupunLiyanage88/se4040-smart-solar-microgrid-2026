// Smart Solar Microgrid Trading System
// End-to-end account checks using a disposable MongoDB database and real HTTP/JWT authentication.
using System.Diagnostics;
using System.IdentityModel.Tokens.Jwt;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Net.Sockets;
using System.Security.Claims;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.IdentityModel.Tokens;
using MongoDB.Bson;
using MongoDB.Driver;
using SmartSolarMicrogrid.Api.Services;

var apiPath = Path.GetFullPath(args.FirstOrDefault() ?? "tmp/auth-api/SmartSolarMicrogrid.Api.dll");
if (!File.Exists(apiPath)) throw new Exception("Build the API into tmp/auth-api before running these checks.");
var name = "smart_solar_auth_checks_" + Guid.NewGuid().ToString("N");
var connection = Environment.GetEnvironmentVariable("TEST_MONGO_CONNECTION_STRING") ?? "mongodb://localhost:27017/?serverSelectionTimeoutMS=5000";
var mongo = new MongoClient(connection);
var database = mongo.GetDatabase(name);
var signingKey = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
var listener = new TcpListener(IPAddress.Loopback, 0);
listener.Start();
var port = ((IPEndPoint)listener.LocalEndpoint).Port;
listener.Stop();
var baseUrl = $"http://127.0.0.1:{port}";
var checks = 0;
Process? server = null;
Task<string>? stdout = null, stderr = null;
using var client = new HttpClient { BaseAddress = new Uri(baseUrl), Timeout = TimeSpan.FromSeconds(15) };

void Check(bool condition, string description)
{
    // Stop at the first regression; successful checks produce a compact reproducible log.
    if (!condition) throw new Exception("FAIL: " + description);
    checks++;
    Console.WriteLine("PASS: " + description);
}
Process Start(params string[] command)
{
    // Explicit configuration isolates checks from all local .env files and existing accounts.
    var info = new ProcessStartInfo("dotnet") { RedirectStandardOutput = true, RedirectStandardError = true, UseShellExecute = false };
    info.ArgumentList.Add(apiPath);
    foreach (var argument in command) info.ArgumentList.Add(argument);
    info.Environment["ASPNETCORE_ENVIRONMENT"] = "Development";
    info.Environment["ASPNETCORE_URLS"] = baseUrl;
    info.Environment["MongoDb__ConnectionString"] = connection;
    info.Environment["MongoDb__DatabaseName"] = name;
    info.Environment["Jwt__Key"] = signingKey;
    info.Environment["Jwt__Issuer"] = "SmartSolarMicrogrid";
    info.Environment["Jwt__Audience"] = "SmartSolarMicrogrid.Clients";
    info.Environment["Bootstrap__UserName"] = "Test Officer";
    info.Environment["Bootstrap__Email"] = "officer@example.test";
    info.Environment["Bootstrap__NIC"] = "200000000001";
    info.Environment["Bootstrap__Password"] = "Synthetic-test-pass-42";
    return Process.Start(info)!;
}
async Task<JsonNode> Request(string path, HttpStatusCode expected, string method = "GET", object? body = null, string? token = null)
{
    // Use real tokens so middleware and role restrictions are covered by every HTTP check.
    using var request = new HttpRequestMessage(new HttpMethod(method), "/api" + path);
    if (body != null) request.Content = JsonContent.Create(body);
    if (token != null) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
    using var response = await client.SendAsync(request);
    var text = await response.Content.ReadAsStringAsync();
    Check(response.StatusCode == expected, $"{method} {path}: {(int)expected}");
    return JsonNode.Parse(text) ?? new JsonObject();
}
async Task<string> Login(string email, string password = "Synthetic-test-pass-42")
{
    // Return credentials only to the test harness; never print them.
    return (await Request("/login", HttpStatusCode.OK, "POST", new { email, password }))["token"]!.GetValue<string>();
}
async Task CheckNodes(string officer, string op, string prosumer)
{
    // Exercise real HTTP authorization, persistence and reservation guards without application data.
    object ValidNode(string name = "Colombo Solar Hub") => new {
        name, address = "Synthetic test address, Colombo", latitude = 6.9271, longitude = 79.8612,
        powerCapacityKw = 25m,
        batterySlots = new[] { new { name = "Battery A", capacityKwh = 20m, isAvailable = true }, new { name = "Battery B", capacityKwh = 15m, isAvailable = false } },
        schedule = new[] { new { dayOfWeek = 1, opensAt = "08:00", closesAt = "18:00" } }
    };
    JsonObject Payload() => System.Text.Json.JsonSerializer.SerializeToNode(ValidNode())!.AsObject();
    await Request("/nodes", HttpStatusCode.Unauthorized);
    await Request("/nodes", HttpStatusCode.Forbidden, "POST", ValidNode(), op);
    await Request("/nodes", HttpStatusCode.Forbidden, "POST", ValidNode(), prosumer);
    var invalid = Payload(); invalid["latitude"] = 91;
    await Request("/nodes", HttpStatusCode.BadRequest, "POST", invalid, officer);
    invalid = Payload(); invalid.Remove("longitude");
    await Request("/nodes", HttpStatusCode.BadRequest, "POST", invalid, officer);
    invalid = Payload(); invalid["powerCapacityKw"] = 0;
    await Request("/nodes", HttpStatusCode.BadRequest, "POST", invalid, officer);
    invalid = Payload(); invalid["schedule"]![0]!["closesAt"] = "07:00";
    await Request("/nodes", HttpStatusCode.BadRequest, "POST", invalid, officer);
    invalid = Payload(); invalid["schedule"]!.AsArray().Add(invalid["schedule"]![0]!.DeepClone());
    await Request("/nodes", HttpStatusCode.BadRequest, "POST", invalid, officer);
    invalid = Payload(); invalid["schedule"] = new JsonArray();
    await Request("/nodes", HttpStatusCode.BadRequest, "POST", invalid, officer);
    invalid = Payload(); invalid["batterySlots"] = new JsonArray();
    await Request("/nodes", HttpStatusCode.BadRequest, "POST", invalid, officer);
    invalid = Payload(); invalid["batterySlots"]![0] = null;
    await Request("/nodes", HttpStatusCode.BadRequest, "POST", invalid, officer);
    invalid = Payload(); invalid["batterySlots"]![0]!["name"] = null;
    await Request("/nodes", HttpStatusCode.BadRequest, "POST", invalid, officer);
    invalid = Payload(); invalid["batterySlots"]![1]!["name"] = " battery a ";
    await Request("/nodes", HttpStatusCode.BadRequest, "POST", invalid, officer);
    invalid = Payload(); invalid["batterySlots"]![0]!["capacityKwh"] = -1;
    await Request("/nodes", HttpStatusCode.BadRequest, "POST", invalid, officer);
    invalid = Payload(); invalid["batterySlots"]![0]!["id"] = "invented";
    await Request("/nodes", HttpStatusCode.BadRequest, "POST", invalid, officer);
    var node = await Request("/nodes", HttpStatusCode.Created, "POST", ValidNode(), officer);
    var id = node["id"]!.GetValue<string>();
    var slotId = node["batterySlots"]![0]!["id"]!.GetValue<string>();
    var path = "/nodes/" + id;
    var slotPath = path + "/slots/" + slotId + "/availability";
    Check(node["isActive"]!.GetValue<bool>() && node["revision"]!.GetValue<long>() == 1, "New node starts active at revision 1");
    var persisted = await database.GetCollection<BsonDocument>(MicrogridNodeService.CollectionName).Find(new BsonDocument("_id", id)).SingleAsync();
    Check(persisted["BatterySlots"].AsBsonArray.Count == 2 && persisted["Latitude"].AsDouble == 6.9271, "GPS and physical slots persist in MongoDB");
    await Request(path, HttpStatusCode.OK, token: op);
    await Request(path, HttpStatusCode.OK, token: prosumer);
    await Request("/nodes/missing", HttpStatusCode.NotFound, token: officer);
    await Request(path, HttpStatusCode.Forbidden, "PUT", node, op);
    await Request(path + "/status", HttpStatusCode.Forbidden, "PATCH", new { isActive = false, revision = 1 }, op);
    await Request(slotPath, HttpStatusCode.Forbidden, "PATCH", new { isAvailable = false, revision = 1 }, prosumer);
    await Request(slotPath, HttpStatusCode.BadRequest, "PATCH", new { revision = 1 }, op);
    await Request(path + "/status", HttpStatusCode.BadRequest, "PATCH", new { revision = 1 }, officer);
    await Request(path + "/slots/missing/availability", HttpStatusCode.NotFound, "PATCH", new { isAvailable = true, revision = 1 }, op);
    node = await Request(slotPath, HttpStatusCode.OK, "PATCH", new { isAvailable = false, revision = 1 }, op);
    Check(!node["batterySlots"]![0]!["isAvailable"]!.GetValue<bool>(), "Operator availability update is persisted");
    await Request(path + "/status", HttpStatusCode.Conflict, "PATCH", new { isActive = false, revision = 1 }, officer);
    var update = node.DeepClone(); update["name"] = "Updated Solar Hub"; update["powerCapacityKw"] = 30;
    update["schedule"]![0]!["opensAt"] = "09:00";
    node = await Request(path, HttpStatusCode.OK, "PUT", update, officer);
    Check(node["schedule"]![0]!["opensAt"]!.GetValue<string>() == "09:00" && node["powerCapacityKw"]!.GetValue<decimal>() == 30, "Capacity and weekly schedule updates persist");
    update = node.DeepClone(); update["batterySlots"]![0]!["id"] = "foreign-slot";
    await Request(path, HttpStatusCode.BadRequest, "PUT", update, officer);
    var reservations = database.GetCollection<BsonDocument>(NodeReservationGuard.CollectionName);
    foreach (var state in new[] { "PENDING", "APPROVED", "IN_PROGRESS", "UNKNOWN" })
    {
        // A reservation blocks deactivation regardless of a stale or absent booking date.
        await reservations.InsertOneAsync(new BsonDocument { ["_id"] = state, ["NodeId"] = id, ["SlotId"] = slotId, ["Status"] = state });
        await Request(path + "/status", HttpStatusCode.Conflict, "PATCH", new { isActive = false, revision = node["revision"]!.GetValue<long>() }, officer);
        var stillActive = await Request(path, HttpStatusCode.OK, token: officer);
        Check(stillActive["isActive"]!.GetValue<bool>() && stillActive["activeReservationCount"]!.GetValue<long>() == 1, state + " reservation blocks deactivation without modifying the node");
        if (state == "PENDING")
        {
            update = node.DeepClone(); update["powerCapacityKw"] = 40;
            await Request(path, HttpStatusCode.Conflict, "PUT", update, officer);
            update = node.DeepClone(); update["schedule"]![0]!["closesAt"] = "19:00";
            await Request(path, HttpStatusCode.Conflict, "PUT", update, officer);
            await Request(slotPath, HttpStatusCode.Conflict, "PATCH", new { isAvailable = true, revision = node["revision"]!.GetValue<long>() }, op);
            update = node.DeepClone(); update["address"] = "Updated metadata while reserved";
            node = await Request(path, HttpStatusCode.OK, "PUT", update, officer);
        }
        await reservations.DeleteOneAsync(new BsonDocument("_id", state));
    }
    foreach (var state in new[] { "COMPLETED", "CANCELLED", "REJECTED" })
        await reservations.InsertOneAsync(new BsonDocument { ["_id"] = state, ["NodeId"] = id, ["SlotId"] = slotId, ["Status"] = state });
    update = node.DeepClone(); update["batterySlots"]!.AsArray().RemoveAt(0);
    await Request(path, HttpStatusCode.Conflict, "PUT", update, officer);
    await reservations.InsertOneAsync(new BsonDocument { ["_id"] = "other-hub", ["NodeId"] = "other-node", ["Status"] = "PENDING" });
    node = await Request(path + "/status", HttpStatusCode.OK, "PATCH", new { isActive = false, revision = node["revision"]!.GetValue<long>() }, officer);
    await Request(path, HttpStatusCode.NotFound, token: prosumer);
    var visible = (await Request("/nodes", HttpStatusCode.OK, token: prosumer)).AsArray();
    Check(visible.All(item => item!["id"]!.GetValue<string>() != id), "Inactive nodes are hidden from prosumers");
    await Request(path, HttpStatusCode.OK, token: op);
    await Request(slotPath, HttpStatusCode.Conflict, "PATCH", new { isAvailable = true, revision = node["revision"]!.GetValue<long>() }, op);
    node = await Request(path + "/status", HttpStatusCode.OK, "PATCH", new { isActive = true, revision = node["revision"]!.GetValue<long>() }, officer);
    await Request(path, HttpStatusCode.OK, token: prosumer);
    async Task<HttpStatusCode> ConcurrentUpdate(bool active)
    {
        // Two edits using one revision must never both succeed.
        using var message = new HttpRequestMessage(HttpMethod.Patch, "/api" + path + "/status");
        message.Headers.Authorization = new AuthenticationHeaderValue("Bearer", officer);
        message.Content = JsonContent.Create(new { isActive = active, revision = node["revision"]!.GetValue<long>() });
        using var response = await client.SendAsync(message);
        return response.StatusCode;
    }
    var outcomes = await Task.WhenAll(ConcurrentUpdate(true), ConcurrentUpdate(false));
    Check(outcomes.Count(code => code == HttpStatusCode.OK) == 1 && outcomes.Count(code => code == HttpStatusCode.Conflict) == 1, "Concurrent node edits preserve optimistic concurrency");
    node = await Request(path, HttpStatusCode.OK, token: officer);
    await Request(path + "/status", HttpStatusCode.OK, "PATCH", new { isActive = true, revision = node["revision"]!.GetValue<long>() }, officer);
}

try
{
    // Migration keeps the original documents and normalizes identities/legacy role names.
    var legacy = new BsonDocument { ["_id"] = ObjectId.GenerateNewId(), ["NIC"] = "999999999v",
        ["Email"] = "Legacy@Example.test", ["UserName"] = "Legacy User", ["Role"] = "GRIDOPARATOR",
        ["PasswordHash"] = BCrypt.Net.BCrypt.HashPassword("Synthetic-test-pass-42"), ["Activation"] = true };
    await database.GetCollection<BsonDocument>("users").InsertOneAsync(legacy);
    await AccountSetup.MigrateAsync(database);
    var migrated = await database.GetCollection<BsonDocument>("users").Find(new BsonDocument("_id", "999999999V")).SingleAsync();
    Check(migrated["Role"] == "GRID_OPERATOR" && migrated["Email"] == "legacy@example.test", "Migration canonicalizes NIC, email and role");
    var collections = await (await database.ListCollectionNamesAsync()).ToListAsync();
    Check(collections.Any(value => value.StartsWith("users_backup_")), "Migration retains original backup collection");
    await AccountSetup.MigrateAsync(database);

    using (var bootstrap = Start("--bootstrap-admin"))
    {
        // Bootstrap must complete before normal requests, and cannot overwrite an existing officer.
        var output = bootstrap.StandardOutput.ReadToEndAsync(); var errors = bootstrap.StandardError.ReadToEndAsync();
        await bootstrap.WaitForExitAsync(); await output; await errors;
        Check(bootstrap.ExitCode == 0, "Initial Backoffice bootstrap succeeds");
    }
    server = Start(); stdout = server.StandardOutput.ReadToEndAsync(); stderr = server.StandardError.ReadToEndAsync();
    for (var attempt = 0; attempt < 60; attempt++)
    {
        // Wait for startup without coupling checks to machine speed.
        if (server.HasExited) throw new Exception("API failed to start: " + await stderr);
        try { if ((await client.GetAsync("/api/health")).IsSuccessStatusCode) break; } catch (HttpRequestException) { }
        await Task.Delay(250);
    }
    await Request("/users", HttpStatusCode.Unauthorized);
    await Request("/user", HttpStatusCode.Unauthorized);
    await Request("/register", HttpStatusCode.BadRequest, "POST", new { userName = "A", email = "bad", nic = "bad", password = "short" });
    var profile = new { userName = "Test Prosumer", email = "prosumer@example.test", nic = "200000000002", password = "Synthetic-test-pass-42", role = "BACKOFFICE", activation = true };
    var registered = await Request("/register", HttpStatusCode.Created, "POST", profile);
    Check(registered["role"]!.GetValue<string>() == "PROSUMER" && !registered["activation"]!.GetValue<bool>(), "Public registration cannot select role or activate itself");
    Check(registered["id"]!.GetValue<string>() == profile.nic && registered["passwordHash"] == null, "NIC is the public identity and no password hash leaks");
    var document = await database.GetCollection<BsonDocument>("users").Find(new BsonDocument("_id", profile.nic)).SingleAsync();
    Check(BCrypt.Net.BCrypt.Verify(profile.password, document["PasswordHash"].AsString) && !document.Contains("NIC"), "MongoDB primary key is NIC and password is hashed");
    await Request("/register", HttpStatusCode.Conflict, "POST", profile);
    await Request("/register", HttpStatusCode.Conflict, "POST", new { userName = "Other User", email = "PROSUMER@example.test", nic = "200000000099", password = profile.password });
    await Request("/login", HttpStatusCode.Unauthorized, "POST", new { email = profile.email, password = "wrong" });
    await Request("/login", HttpStatusCode.Forbidden, "POST", new { email = profile.email, password = profile.password });
    var officer = await Login("officer@example.test");
    var list = (await Request("/users", HttpStatusCode.OK, token: officer)).AsArray();
    Check(list.Any(u => u!["nic"]!.GetValue<string>() == profile.nic && u["activationPending"]!.GetValue<bool>()), "Backoffice can see pending activation");
    await Request("/back-office/users", HttpStatusCode.BadRequest, "POST", new { userName = "No Role", email = "missing@example.test", nic = "200000000098", password = profile.password }, officer);
    foreach (var entry in new[] { ("GRID_OPERATOR", "operator", "200000000003"), ("BACKOFFICE", "second-officer", "200000000004"), ("PROSUMER", "managed", "200000000005") })
    {
        // All assignment roles can be provisioned only by the officer.
        var created = await Request("/back-office/users", HttpStatusCode.Created, "POST",
            new { userName = entry.Item2, email = entry.Item2 + "@example.test", nic = entry.Item3, password = profile.password, role = entry.Item1 }, officer);
        Check(created["activation"]!.GetValue<bool>() && created["role"]!.GetValue<string>() == entry.Item1, "Backoffice creates active " + entry.Item1);
    }
    var op = await Login("operator@example.test");
    await Request("/users", HttpStatusCode.Forbidden, token: op);
    await Request("/back-office/users", HttpStatusCode.Forbidden, "POST", profile, op);
    await Request("/back-office/200000000002/status?active=true", HttpStatusCode.Forbidden, "PATCH", token: op);
    await Request("/back-office/200000000002/status", HttpStatusCode.BadRequest, "PATCH", token: officer);
    await Request("/back-office/200000000001/status?active=false", HttpStatusCode.Conflict, "PATCH", token: officer);
    await Request("/back-office/200000000002/status?active=true", HttpStatusCode.OK, "PATCH", token: officer);
    var prosumer = await Login(profile.email);
    await Request("/users", HttpStatusCode.Forbidden, token: prosumer);
    await Request("/users/200000000001", HttpStatusCode.Forbidden, token: prosumer);
    await Request("/back-office/200000000003", HttpStatusCode.Forbidden, "PATCH", new { userName = "Hacked User", email = "hacked@example.test" }, prosumer);
    var edited = await Request("/user", HttpStatusCode.OK, "PATCH", new { userName = "Edited Prosumer", email = "edited@example.test", nic = "200000000001", role = "BACKOFFICE", activation = false }, prosumer);
    Check(edited["nic"]!.GetValue<string>() == profile.nic && edited["role"]!.GetValue<string>() == "PROSUMER" && edited["activation"]!.GetValue<bool>(), "Own-profile editing cannot alter NIC, role, or activation");
    await Request("/user", HttpStatusCode.Conflict, "PATCH", new { userName = "Edited Prosumer", email = "officer@example.test" }, prosumer);
    await Request("/back-office/200000000002", HttpStatusCode.OK, "PATCH", new { userName = "Officer Edited", email = "edited@example.test" }, officer);
    await Request("/user/deactivation", HttpStatusCode.Forbidden, "POST", token: op);
    await Request("/user/deactivation", HttpStatusCode.OK, "POST", token: prosumer);
    await Request("/user/deactivation", HttpStatusCode.OK, "POST", token: prosumer);
    var pending = await Request("/users/200000000002", HttpStatusCode.OK, token: officer);
    Check(pending["deactivationRequested"]!.GetValue<bool>(), "Deactivation request is visible to Backoffice");
    await Request("/back-office/200000000002/status?active=false", HttpStatusCode.OK, "PATCH", token: officer);
    await Request("/user", HttpStatusCode.Unauthorized, token: prosumer);
    await Request("/login", HttpStatusCode.Forbidden, "POST", new { email = "edited@example.test", password = profile.password });
    await Request("/back-office/200000000002/status?active=true", HttpStatusCode.Forbidden, "PATCH", token: op);
    await Request("/back-office/200000000002/status?active=true", HttpStatusCode.OK, "PATCH", token: officer);
    await Request("/user", HttpStatusCode.Unauthorized, token: prosumer);
    var renewed = await Login("edited@example.test");
    await Request("/user", HttpStatusCode.OK, token: renewed);
    var secondOfficer = await Login("second-officer@example.test");
    await Request("/back-office/200000000004/status?active=false", HttpStatusCode.OK, "PATCH", token: officer);
    await Request("/users", HttpStatusCode.Unauthorized, token: secondOfficer);
    await Request("/users/not-an-object-id", HttpStatusCode.NotFound, token: officer);
    var race = new { userName = "Race Check", email = "race@example.test", nic = "200000000006", password = profile.password };
    var raceResponses = await Task.WhenAll(client.PostAsJsonAsync("/api/register", race), client.PostAsJsonAsync("/api/register", race));
    Check(raceResponses.Count(r => r.StatusCode == HttpStatusCode.Created) == 1 && raceResponses.Count(r => r.StatusCode == HttpStatusCode.Conflict) == 1, "Concurrent duplicate registration returns one 201 and one 409");
    foreach (var response in raceResponses) response.Dispose();
    var oldNic = new { userName = "Old NIC", email = "oldnic@example.test", nic = "123456789v", password = profile.password };
    await Request("/register", HttpStatusCode.Created, "POST", oldNic);
    await Request("/register", HttpStatusCode.Conflict, "POST", new { userName = "Same NIC", email = "other@example.test", nic = "123456789V", password = profile.password });
    await Request("/register", HttpStatusCode.BadRequest, "POST", new { userName = "Unicode Password", email = "unicode@example.test", nic = "200000000009", password = new string('\u00e9', 40) });
    var claims = new[] { new Claim(JwtRegisteredClaimNames.Sub, profile.nic), new Claim(ClaimTypes.Role, "PROSUMER"), new Claim("session_version", "invalid") };
    var expired = new JwtSecurityToken("SmartSolarMicrogrid", "SmartSolarMicrogrid.Clients", claims,
        notBefore: DateTime.UtcNow.AddMinutes(-10), expires: DateTime.UtcNow.AddMinutes(-5),
        signingCredentials: new SigningCredentials(new SymmetricSecurityKey(Encoding.UTF8.GetBytes(signingKey)), SecurityAlgorithms.HmacSha256));
    await Request("/user", HttpStatusCode.Unauthorized, token: new JwtSecurityTokenHandler().WriteToken(expired));
    await Request("/user", HttpStatusCode.Unauthorized, token: "invalid.jwt.token");
    await CheckNodes(officer, op, renewed);
    Console.WriteLine($"Completed {checks} authentication and node integration checks.");
    var stopArgument = args.FirstOrDefault(value => value.StartsWith("--serve-until="));
    if (stopArgument is not null)
    {
        // Optional browser verification reuses only this disposable database and synthetic users.
        Console.WriteLine("Preview API: " + baseUrl);
        var stopFile = stopArgument["--serve-until=".Length..];
        var deadline = DateTime.UtcNow.AddMinutes(20);
        while (!File.Exists(stopFile) && DateTime.UtcNow < deadline) await Task.Delay(500);
    }
}
finally
{
    // Only the uniquely named test database is removed; application data is never touched.
    if (server is not null) { if (!server.HasExited) server.Kill(entireProcessTree: true); await server.WaitForExitAsync(); server.Dispose(); }
    if (stdout is not null) await stdout;
    if (stderr is not null) await stderr;
    if (name.StartsWith("smart_solar_auth_checks_")) await mongo.DropDatabaseAsync(name);
}

