// Smart Solar Microgrid Trading System
// Reservation inputs. Clients cannot set status, QR tokens or timestamps.
using System.ComponentModel.DataAnnotations;
namespace SmartSolarMicrogrid.Api.DTO.ReservationDTO;
public sealed class CreateReservationRequestDTO : IValidatableObject
{
    [RegularExpression(@"^(?:[0-9]{9}[vVxX]|[0-9]{12})$", ErrorMessage = "NIC must contain 12 digits or 9 digits followed by V or X.")]
    public string? ProsumerNic { get; set; }
    [Required, StringLength(64, MinimumLength = 8)] public string NodeId { get; set; } = string.Empty;
    [Required, StringLength(64, MinimumLength = 8)] public string SlotId { get; set; } = string.Empty;
    [Required, RegularExpression("^(DROP_OFF|CHARGING)$")] public string Direction { get; set; } = string.Empty;
    [Required, Range(typeof(decimal), "0.01", "100000000")] public decimal? RequestedKwh { get; set; }
    [Required] public DateTime? StartsAtUtc { get; set; }
    [Required] public DateTime? EndsAtUtc { get; set; }
    public IEnumerable<ValidationResult> Validate(ValidationContext context)
    {
        // A window must end after it starts. Staff supply the prosumer NIC; prosumers omit it.
        if (StartsAtUtc is not null && EndsAtUtc is not null && EndsAtUtc <= StartsAtUtc)
            yield return new ValidationResult("The reservation must end after it starts.", [nameof(EndsAtUtc)]);
        if (ProsumerNic is not null && string.IsNullOrWhiteSpace(ProsumerNic))
            yield return new ValidationResult("Enter the prosumer NIC.", [nameof(ProsumerNic)]);
    }
}
public sealed class UpdateReservationRequestDTO : IValidatableObject
{
    [Required, StringLength(64, MinimumLength = 8)] public string NodeId { get; set; } = string.Empty;
    [Required, StringLength(64, MinimumLength = 8)] public string SlotId { get; set; } = string.Empty;
    [Required, RegularExpression("^(DROP_OFF|CHARGING)$")] public string Direction { get; set; } = string.Empty;
    [Required, Range(typeof(decimal), "0.01", "100000000")] public decimal? RequestedKwh { get; set; }
    [Required] public DateTime? StartsAtUtc { get; set; }
    [Required] public DateTime? EndsAtUtc { get; set; }
    public IEnumerable<ValidationResult> Validate(ValidationContext context)
    {
        // The replacement window is checked on its own after the 12-hour notice rule.
        if (StartsAtUtc is not null && EndsAtUtc is not null && EndsAtUtc <= StartsAtUtc)
            yield return new ValidationResult("The reservation must end after it starts.", [nameof(EndsAtUtc)]);
    }
}
public sealed class ReservationDecisionRequestDTO
{
    [Required, RegularExpression("^(APPROVE|REJECT)$")] public string Decision { get; set; } = string.Empty;
}
public sealed class CompleteReservationRequestDTO
{
    [Required, StringLength(128, MinimumLength = 16)] public string QrToken { get; set; } = string.Empty;
}
