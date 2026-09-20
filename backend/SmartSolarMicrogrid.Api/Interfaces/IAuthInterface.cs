using SmartSolarMicrogrid.Api.DTO.AuthDTO;
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Services;

namespace SmartSolarMicrogrid.Api.Interfaces;

public interface IAuthInterface
{
    /// <returns>The created user, or null if the email or NIC is already registered.</returns>
    Task<UserResponseDTO?> RegisterAsync(UserRequestDTO request, CancellationToken ct = default);
    Task<LoginResult> LoginAsync(LoginRequestDTO request, CancellationToken ct = default);
    Task<UserResponseDTO?> GetCurrentUserAsync(string userId, CancellationToken ct = default);
}
