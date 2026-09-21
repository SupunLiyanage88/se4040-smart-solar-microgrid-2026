// Smart Solar Microgrid Trading System
// Backoffice account administration operations.
using SmartSolarMicrogrid.Api.DTO.UserDTO;
namespace SmartSolarMicrogrid.Api.Interfaces;
public interface IBackOfficeInterface
{
    Task<UserResponseDTO?> CreateUserByOfficerAsync(StaffUserRequestDTO request, CancellationToken ct = default);
    Task<UserResponseDTO?> UpdateUserByOfficerAsync(string userId, ProfileRequestDTO request, CancellationToken ct = default);
    Task<bool> ActivateDeactivateUserByOfficerAsync(string userId, bool activation, CancellationToken ct = default);
}
