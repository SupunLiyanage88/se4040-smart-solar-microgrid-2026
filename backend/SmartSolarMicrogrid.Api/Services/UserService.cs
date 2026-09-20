// Smart Solar Microgrid Trading System
// MongoDB data access and mapping for users.

using MongoDB.Driver;
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Models;

namespace SmartSolarMicrogrid.Api.Services;

public interface IUserService
{
    Task<User?> GetByIdAsync(string id, CancellationToken ct = default);
    Task<User?> GetByEmailAsync(string email, CancellationToken ct = default);
    Task<List<User>> GetAllAsync(CancellationToken ct = default);
    Task<User> CreateAsync(User user, CancellationToken ct = default);
    Task<bool> SetActivationAsync(string id, bool activation, CancellationToken ct = default);
    Task<bool> ExistsAsync(string email, string nic, CancellationToken ct = default);
    UserResponseDTO ToResponse(User user);
}

public class UserService : IUserService
{
    private readonly IMongoCollection<User> _users;

    public UserService(IMongoDatabase database)
    {
        _users = database.GetCollection<User>("users");

        // Enforce uniqueness of email and NIC at the database level.
        _users.Indexes.CreateMany(
            [
                new CreateIndexModel<User>(
                    Builders<User>.IndexKeys.Ascending(u => u.Email),
                    new CreateIndexOptions { Unique = true }
                ),
                new CreateIndexModel<User>(
                    Builders<User>.IndexKeys.Ascending(u => u.NIC),
                    new CreateIndexOptions { Unique = true }
                ),
            ]
        );
    }

    public async Task<User?> GetByIdAsync(string id, CancellationToken ct = default) =>
        await _users.Find(u => u.Id == id).FirstOrDefaultAsync(ct);

    public async Task<User?> GetByEmailAsync(string email, CancellationToken ct = default)
    {
        var normalized = email.ToLowerInvariant();
        return await _users.Find(u => u.Email == normalized).FirstOrDefaultAsync(ct);
    }

    public async Task<List<User>> GetAllAsync(CancellationToken ct = default) =>
        await _users.Find(_ => true).ToListAsync(ct);

    public async Task<User> CreateAsync(User user, CancellationToken ct = default)
    {
        user.Email = user.Email.ToLowerInvariant();
        await _users.InsertOneAsync(user, cancellationToken: ct);
        return user;
    }

    public async Task<bool> SetActivationAsync(
        string id,
        bool activation,
        CancellationToken ct = default
    )
    {
        var result = await _users.UpdateOneAsync(
            u => u.Id == id,
            Builders<User>.Update.Set(u => u.Activation, activation),
            cancellationToken: ct
        );
        return result.MatchedCount > 0;
    }

    public async Task<bool> ExistsAsync(string email, string nic, CancellationToken ct = default)
    {
        var normalized = email.ToLowerInvariant();
        return await _users.Find(u => u.Email == normalized || u.NIC == nic).AnyAsync(ct);
    }

    public UserResponseDTO ToResponse(User user) =>
        new()
        {
            Id = user.Id ?? string.Empty,
            UserName = user.UserName,
            Email = user.Email,
            NIC = user.NIC,
            Role = user.Role,
            Activation = user.Activation,
        };
}
