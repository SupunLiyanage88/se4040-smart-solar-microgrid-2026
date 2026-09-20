// Smart Solar Microgrid Trading System
// Payload used to register a new user.

using System.ComponentModel.DataAnnotations;

namespace SmartSolarMicrogrid.Api.DTO.UserDTO;

public class UserRequestDTO
{
    [Required, StringLength(100, MinimumLength = 2)]
    public string UserName { get; set; } = string.Empty;

    [Required, EmailAddress]
    public string Email { get; set; } = string.Empty;

    [Required, StringLength(20, MinimumLength = 5)]
    public string NIC { get; set; } = string.Empty;

    [Required, StringLength(100, MinimumLength = 8)]
    public string Password { get; set; } = string.Empty;
}
