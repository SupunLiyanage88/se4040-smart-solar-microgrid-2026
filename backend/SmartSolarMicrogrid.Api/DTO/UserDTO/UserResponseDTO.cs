// Smart Solar Microgrid Trading System
// Public account information never includes password hashes or session versions.
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
    public bool ActivationPending { get; set; }
    public bool DeactivationRequested { get; set; }
}
