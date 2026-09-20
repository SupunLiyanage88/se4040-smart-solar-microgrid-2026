// Smart Solar Microgrid Trading System
// User data returned to API clients (never includes the password hash).

using SmartSolarMicrogrid.Api.Models;

namespace SmartSolarMicrogrid.Api.DTO.UserDTO;

public class UserResponseDTO
{
    public string Id { get; set; } = string.Empty;
    public string UserName { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string NIC { get; set; } = string.Empty;
    public UserRole Role { get; set; }
    public bool Activation { get; set; }
}
