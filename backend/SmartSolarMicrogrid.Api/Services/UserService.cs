// Smart Solar Microgrid Trading System
// MongoDB persistence; updates explicitly whitelist mutable fields.
using MongoDB.Driver;
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Interfaces;
using SmartSolarMicrogrid.Api.Models;
namespace SmartSolarMicrogrid.Api.Services;
public class UserService : IUserInterface
{
    private readonly IMongoCollection<User> _users;
    public UserService(IMongoDatabase database)
    {
        // Index creation belongs to startup rather than every authenticated request.
        _users = database.GetCollection<User>("users");
    }
    public async Task<User?> GetByIdAsync(string id, CancellationToken ct = default)
    {
        // NIC is immutable and normalized before lookup.
        return await _users.Find(u => u.NIC == id.Trim().ToUpperInvariant()).FirstOrDefaultAsync(ct);
    }
    public async Task<User?> GetByEmailAsync(string email, CancellationToken ct = default)
    {
        // Email lookups use the same canonical form as writes.
        var normalized = email.Trim().ToLowerInvariant();
        return await _users.Find(u => u.Email == normalized).FirstOrDefaultAsync(ct);
    }
    public async Task<List<User>> GetAllAsync(CancellationToken ct = default)
    {
        // Only authorized Backoffice operations expose this list.
        return await _users.Find(_ => true).SortBy(u => u.UserName).ToListAsync(ct);
    }
    public async Task<User> CreateAsync(User user, CancellationToken ct = default)
    {
        // MongoDB primary-key and email indexes enforce uniqueness atomically.
        user.NIC = user.NIC.Trim().ToUpperInvariant();
        user.Email = user.Email.Trim().ToLowerInvariant();
        user.UserName = user.UserName.Trim();
        await _users.InsertOneAsync(user, cancellationToken: ct);
        return user;
    }
    public async Task<bool> ExistsAsync(string email, string nic, CancellationToken ct = default)
    {
        // Provide a friendly early conflict; the indexes also cover concurrent requests.
        var normalizedEmail = email.Trim().ToLowerInvariant();
        var normalizedNic = nic.Trim().ToUpperInvariant();
        return await _users.Find(u => u.Email == normalizedEmail || u.NIC == normalizedNic).AnyAsync(ct);
    }
    public async Task<User?> UpdateProfileAsync(string id, ProfileRequestDTO request, CancellationToken ct = default)
    {
        // Do not allow profile editing to modify identity, role or activation.
        return await _users.FindOneAndUpdateAsync(u => u.NIC == id,
            Builders<User>.Update.Set(u => u.UserName, request.UserName.Trim())
                .Set(u => u.Email, request.Email.Trim().ToLowerInvariant()),
            new FindOneAndUpdateOptions<User> { ReturnDocument = ReturnDocument.After }, ct);
    }
    public async Task<bool> SetActivationAsync(string id, bool active, CancellationToken ct = default)
    {
        // Rotate the version on every status transition so old tokens never revive.
        var result = await _users.UpdateOneAsync(u => u.NIC == id,
            Builders<User>.Update.Set(u => u.Activation, active)
                .Set(u => u.ActivationPending, false).Set(u => u.DeactivationRequested, false)
                .Set(u => u.SessionVersion, Guid.NewGuid().ToString("N")), cancellationToken: ct);
        return result.MatchedCount > 0;
    }
    public async Task<bool> RequestDeactivationAsync(string id, CancellationToken ct = default)
    {
        // Queue an idempotent request; Backoffice completes deactivation.
        var result = await _users.UpdateOneAsync(u => u.NIC == id && u.Role == UserRole.PROSUMER && u.Activation,
            Builders<User>.Update.Set(u => u.DeactivationRequested, true), cancellationToken: ct);
        return result.MatchedCount > 0;
    }
    public UserResponseDTO ToResponse(User user)
    {
        // Only return the fields clients require to display and manage accounts.
        return new UserResponseDTO { Id = user.Id, NIC = user.NIC, Email = user.Email,
            UserName = user.UserName, Role = user.Role, Activation = user.Activation,
            ActivationPending = user.ActivationPending, DeactivationRequested = user.DeactivationRequested };
    }
}
