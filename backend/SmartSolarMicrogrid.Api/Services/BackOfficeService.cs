// Smart Solar Microgrid Trading System
// Central account creation and lifecycle rules for authorized Backoffice staff.
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Interfaces;
using SmartSolarMicrogrid.Api.Models;
namespace SmartSolarMicrogrid.Api.Services;
public class BackOfficeService : IBackOfficeInterface
{
    private readonly IUserInterface _users;
    public BackOfficeService(IUserInterface users)
    {
        // Reuse the account repository and its uniqueness rules.
        _users = users;
    }
    public async Task<UserResponseDTO?> CreateUserByOfficerAsync(StaffUserRequestDTO request, CancellationToken ct = default)
    {
        // Backoffice-created accounts are approved immediately, including prosumers.
        if (await _users.ExistsAsync(request.Email, request.NIC, ct)) return null;
        var user = new User { NIC = request.NIC, UserName = request.UserName, Email = request.Email,
            PasswordHash = BCrypt.Net.BCrypt.HashPassword(request.Password), Role = request.Role!.Value,
            Activation = true, ActivationPending = false };
        return _users.ToResponse(await _users.CreateAsync(user, ct));
    }
    public async Task<UserResponseDTO?> UpdateUserByOfficerAsync(string userId, ProfileRequestDTO request, CancellationToken ct = default)
    {
        // NIC and role remain immutable to preserve identity and authorization history.
        var user = await _users.UpdateProfileAsync(userId, request, ct);
        return user is null ? null : _users.ToResponse(user);
    }
    public Task<bool> ActivateDeactivateUserByOfficerAsync(string userId, bool activation, CancellationToken ct = default)
    {
        // The protected controller restricts this operation to Backoffice.
        return _users.SetActivationAsync(userId, activation, ct);
    }
}
