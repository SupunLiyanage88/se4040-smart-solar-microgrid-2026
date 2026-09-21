// Smart Solar Microgrid Trading System
// Persistence contract for account identities and lifecycle updates.
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Models;
namespace SmartSolarMicrogrid.Api.Interfaces;
public interface IUserInterface
{
    Task<User?> GetByIdAsync(string id, CancellationToken ct = default);
    Task<User?> GetByEmailAsync(string email, CancellationToken ct = default);
    Task<List<User>> GetAllAsync(CancellationToken ct = default);
    Task<User> CreateAsync(User user, CancellationToken ct = default);
    Task<bool> ExistsAsync(string email, string nic, CancellationToken ct = default);
    Task<User?> UpdateProfileAsync(string id, ProfileRequestDTO request, CancellationToken ct = default);
    Task<bool> SetActivationAsync(string id, bool active, CancellationToken ct = default);
    Task<bool> RequestDeactivationAsync(string id, CancellationToken ct = default);
    UserResponseDTO ToResponse(User user);
}
