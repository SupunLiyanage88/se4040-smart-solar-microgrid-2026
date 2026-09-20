using SmartSolarMicrogrid.Api.DTO.UserDTO;

namespace SmartSolarMicrogrid.Api.Interfaces;

public interface IBackOfficeInterface
{
    Task<UserResponseDTO?> CreateUserByOfficerAsync(
        UserRequestDTO request,
        CancellationToken ct = default
    );
    Task<UserResponseDTO?> UpdateUserByOfficerAsync(
        string userId,
        UserRequestDTO request,
        CancellationToken ct = default
    );
    Task<bool> ActivateDeactivateUserByOfficerAsync(
        string id,
        bool activation,
        CancellationToken ct = default
    );
    Task<UserResponseDTO?> DeleteUserByOfficerAsync(string userId, CancellationToken ct = default);
}
