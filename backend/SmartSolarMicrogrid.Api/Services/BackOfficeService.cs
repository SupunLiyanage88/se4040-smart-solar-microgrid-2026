using MongoDB.Driver;
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Interfaces;
using SmartSolarMicrogrid.Api.Models;

public class BackOfficeService : IBackOfficeInterface
{

    private readonly IMongoCollection<User> _users;

    public BackOfficeService(IMongoDatabase database) =>
        _users = database.GetCollection<User>("users");

    public async Task<bool> ActivateDeactivateUserByOfficerAsync(
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

    public Task<UserResponseDTO?> CreateUserByOfficerAsync(
        UserRequestDTO request,
        CancellationToken ct = default
    )
    {
        return Task.FromResult<UserResponseDTO?>(null);
    }

    public Task<UserResponseDTO?> DeleteUserByOfficerAsync(
        string userId,
        CancellationToken ct = default
    )
    {
        throw new NotImplementedException();
    }

    public Task<UserResponseDTO?> UpdateUserByOfficerAsync(
        string userId,
        UserRequestDTO request,
        CancellationToken ct = default
    )
    {
        throw new NotImplementedException();
    }
}
