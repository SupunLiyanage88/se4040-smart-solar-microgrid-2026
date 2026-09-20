// Smart Solar Microgrid Trading System
// Result of a successful login.

using SmartSolarMicrogrid.Api.DTO.UserDTO;

namespace SmartSolarMicrogrid.Api.DTO.AuthDTO;

public class LoginResponseDTO
{
    public string Token { get; set; } = string.Empty;
    public DateTime ExpiresAtUtc { get; set; }
    public UserResponseDTO User { get; set; } = new();
}
