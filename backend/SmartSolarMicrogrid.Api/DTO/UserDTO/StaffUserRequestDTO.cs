// Smart Solar Microgrid Trading System
// Backoffice may create staff or prosumer accounts through the protected endpoint.
using System.ComponentModel.DataAnnotations;
using SmartSolarMicrogrid.Api.Models;
namespace SmartSolarMicrogrid.Api.DTO.UserDTO;
public class StaffUserRequestDTO : UserRequestDTO
{
    [Required, EnumDataType(typeof(UserRole))]
    public UserRole? Role { get; set; }
}
