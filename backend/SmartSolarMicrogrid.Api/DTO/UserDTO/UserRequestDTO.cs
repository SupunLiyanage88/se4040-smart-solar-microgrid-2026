// Smart Solar Microgrid Trading System
// Public registration cannot select a role or account status.
using System.ComponentModel.DataAnnotations;
using System.Text;
namespace SmartSolarMicrogrid.Api.DTO.UserDTO;
public class UserRequestDTO : IValidatableObject
{
    [Required, StringLength(100, MinimumLength = 2)]
    public string UserName { get; set; } = string.Empty;
    [Required, EmailAddress, StringLength(254)]
    public string Email { get; set; } = string.Empty;
    [Required, RegularExpression(@"^(?:[0-9]{9}[vVxX]|[0-9]{12})$", ErrorMessage = "NIC must contain 12 digits or 9 digits followed by V or X.")]
    public string NIC { get; set; } = string.Empty;
    [Required, StringLength(72, MinimumLength = 8)]
    public string Password { get; set; } = string.Empty;
    public IEnumerable<ValidationResult> Validate(ValidationContext context)
    {
        // BCrypt only incorporates the first 72 UTF-8 bytes; reject longer secrets.
        if (Encoding.UTF8.GetByteCount(Password) > 72)
            yield return new ValidationResult("Password must not exceed 72 UTF-8 bytes.", [nameof(Password)]);
        if (UserName.Trim().Length < 2)
            yield return new ValidationResult("Name must contain at least two non-padding characters.", [nameof(UserName)]);
    }
}
