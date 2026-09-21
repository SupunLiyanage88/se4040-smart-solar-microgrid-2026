// Smart Solar Microgrid Trading System
// Explicit maintenance commands and account-index initialization.
using System.ComponentModel.DataAnnotations;
using System.Text.RegularExpressions;
using MongoDB.Bson;
using MongoDB.Driver;
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Models;
namespace SmartSolarMicrogrid.Api.Services;
public static class AccountSetup
{
    public static async Task InitializeAsync(IMongoDatabase database)
    {
        // Fail clearly on the former ObjectId schema instead of silently losing accounts.
        var raw = database.GetCollection<BsonDocument>("users");
        if (await raw.Find(new BsonDocument("_id", new BsonDocument("$not", new BsonDocument("$type", "string")))).AnyAsync())
            throw new InvalidOperationException("Legacy user identities found. Stop the API and run --migrate-user-identities first. See docs/auth-user-management.md.");
        await database.GetCollection<User>("users").Indexes.CreateOneAsync(
            new CreateIndexModel<User>(Builders<User>.IndexKeys.Ascending(u => u.Email),
                new CreateIndexOptions { Unique = true }));
    }

    public static async Task BootstrapAsync(IMongoDatabase database, IConfiguration config)
    {
        // Bootstrap is a local maintenance command, never a public registration endpoint.
        await InitializeAsync(database);
        var users = database.GetCollection<User>("users");
        if (await users.Find(u => u.Role == UserRole.BACKOFFICE).AnyAsync())
            throw new InvalidOperationException("A Backoffice account already exists; use its authenticated creation flow.");
        var request = config.GetSection("Bootstrap").Get<UserRequestDTO>()
            ?? throw new InvalidOperationException("Set Bootstrap__UserName, Bootstrap__Email, Bootstrap__NIC and Bootstrap__Password.");
        Validator.ValidateObject(request, new ValidationContext(request), validateAllProperties: true);
        await new UserService(database).CreateAsync(new User {
            NIC = request.NIC, UserName = request.UserName, Email = request.Email,
            PasswordHash = BCrypt.Net.BCrypt.HashPassword(request.Password),
            Role = UserRole.BACKOFFICE, Activation = true, ActivationPending = false });
        Console.WriteLine("Initial Backoffice account created. Remove Bootstrap credentials from the environment.");
    }

    public static async Task MigrateAsync(IMongoDatabase database)
    {
        // Run while the API is stopped; preserve the complete original collection as a backup.
        var users = database.GetCollection<BsonDocument>("users");
        var originals = await users.Find(FilterDefinition<BsonDocument>.Empty).ToListAsync();
        if (originals.Count == 0 || originals.All(d => d["_id"].IsString && !d.Contains("NIC")))
        {
            Console.WriteLine("No legacy users need migration.");
            return;
        }
        var migrated = new List<BsonDocument>();
        var identities = new HashSet<string>();
        var emails = new HashSet<string>();
        foreach (var original in originals)
        {
            // Validate the full batch before making any database changes.
            var doc = original.DeepClone().AsBsonDocument;
            var nic = doc.GetValue("NIC", doc["_id"]).ToString()!.Trim().ToUpperInvariant();
            var email = doc.GetValue("Email", "").AsString.Trim().ToLowerInvariant();
            if (!Regex.IsMatch(nic, @"^(?:[0-9]{9}[VX]|[0-9]{12})$") || !identities.Add(nic)
                || !new EmailAddressAttribute().IsValid(email) || !emails.Add(email))
                throw new InvalidOperationException("Migration stopped: invalid or duplicate NIC/email. Correct legacy records before retrying; no data was changed.");
            var role = doc.GetValue("Role", "PROCUMER").AsString switch {
                "PROCUMER" => "PROSUMER", "GRIDOPARATOR" => "GRID_OPERATOR", var value => value };
            if (!Enum.TryParse<UserRole>(role, out var parsedRole) || !Enum.IsDefined(parsedRole))
                throw new InvalidOperationException("Migration stopped: unknown user role; no data was changed.");
            doc["_id"] = nic;
            doc.Remove("NIC");
            doc["Email"] = email;
            doc["Role"] = role;
            doc["ActivationPending"] = !doc.GetValue("Activation", false).AsBoolean;
            doc["DeactivationRequested"] = false;
            doc["SessionVersion"] = Guid.NewGuid().ToString("N");
            migrated.Add(doc);
        }
        var suffix = Guid.NewGuid().ToString("N");
        var staging = "users_migration_" + suffix;
        var backup = "users_backup_" + suffix;
        await database.GetCollection<BsonDocument>(staging).InsertManyAsync(migrated);
        await database.GetCollection<BsonDocument>(staging).Indexes.CreateOneAsync(
            new CreateIndexModel<BsonDocument>(Builders<BsonDocument>.IndexKeys.Ascending("Email"), new CreateIndexOptions { Unique = true }));
        await database.RenameCollectionAsync("users", backup);
        try
        {
            // Promote the validated copy without deleting the original records.
            await database.RenameCollectionAsync(staging, "users");
        }
        catch
        {
            // Restore the original namespace if promotion fails.
            await database.RenameCollectionAsync(backup, "users");
            throw;
        }
        Console.WriteLine($"Migrated {migrated.Count} users. Original collection retained as {backup}. All previous tokens are invalid.");
    }
}
