// Smart Solar Microgrid Trading System
// Credentials submitted to log in.

using System.ComponentModel.DataAnnotations;

namespace SmartSolarMicrogrid.Api.DTO.AuthDTO;

public class LoginRequestDTO
{
    [Required, EmailAddress]
    public string Email { get; set; } = string.Empty;

    [Required]
    public string Password { get; set; } = string.Empty;
}
