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
    Console.WriteLine($"Completed {checks} account integration checks.");
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

