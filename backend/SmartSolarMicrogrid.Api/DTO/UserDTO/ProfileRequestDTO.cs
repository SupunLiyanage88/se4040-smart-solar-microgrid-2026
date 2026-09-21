// Smart Solar Microgrid Trading System
// Editable profile fields exclude NIC, role, password and activation status.
using System.ComponentModel.DataAnnotations;
namespace SmartSolarMicrogrid.Api.DTO.UserDTO;
public class ProfileRequestDTO : IValidatableObject
{
    [Required, StringLength(100, MinimumLength = 2)]
    public string UserName { get; set; } = string.Empty;
    [Required, EmailAddress, StringLength(254)]
    public string Email { get; set; } = string.Empty;
    public IEnumerable<ValidationResult> Validate(ValidationContext context)
    {
        // Prevent names consisting only of padding from being saved.
        if (UserName.Trim().Length < 2)
            yield return new ValidationResult("Name must contain at least two non-padding characters.", [nameof(UserName)]);
    }
}
