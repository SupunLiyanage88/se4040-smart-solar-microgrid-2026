// Smart Solar Microgrid Trading System
// Validated node inputs; clients cannot set status, timestamps or server-generated identities.
using System.ComponentModel.DataAnnotations;
using System.Globalization;
namespace SmartSolarMicrogrid.Api.DTO.NodeDTO;
public class NodeRequestDTO : IValidatableObject
{
    [Required, StringLength(120, MinimumLength = 2)] public string Name { get; set; } = string.Empty;
    [Required, StringLength(300, MinimumLength = 2)] public string Address { get; set; } = string.Empty;
    [Required, Range(-90, 90)] public double? Latitude { get; set; }
    [Required, Range(-180, 180)] public double? Longitude { get; set; }
    [Required, Range(typeof(decimal), "0.01", "100000000")] public decimal? PowerCapacityKw { get; set; }
    [Required, MinLength(1), MaxLength(200)] public List<BatterySlotRequestDTO> BatterySlots { get; set; } = [];
    [Required, MinLength(1), MaxLength(7)] public List<OpeningHoursRequestDTO> Schedule { get; set; } = [];
    public IEnumerable<ValidationResult> Validate(ValidationContext context)
    {
        // Reject ambiguous names, null collection entries, duplicate slot labels and duplicate weekdays.
        if ((Name?.Trim().Length ?? 0) < 2 || (Address?.Trim().Length ?? 0) < 2)
            yield return new ValidationResult("Name and address must contain at least two non-padding characters.");
        if (BatterySlots is not null && (BatterySlots.Any(s => s is null) ||
            BatterySlots.Where(s => s is not null).Select(s => s.Name?.Trim().ToUpperInvariant()).Distinct().Count() != BatterySlots.Count))
            yield return new ValidationResult("Battery slots must be present and have distinct names.", [nameof(BatterySlots)]);
        if (Schedule is not null && (Schedule.Any(s => s is null) ||
            Schedule.Where(s => s is not null).Select(s => s.DayOfWeek).Distinct().Count() != Schedule.Count))
            yield return new ValidationResult("Specify each operating day only once.", [nameof(Schedule)]);
    }
}
public sealed class UpdateNodeRequestDTO : NodeRequestDTO
{
    [Required, Range(1, long.MaxValue)] public long? Revision { get; set; }
}
public sealed class BatterySlotRequestDTO : IValidatableObject
{
    [StringLength(32)] public string? Id { get; set; }
    [Required, StringLength(60, MinimumLength = 1)] public string Name { get; set; } = string.Empty;
    [Required, Range(typeof(decimal), "0.01", "100000000")] public decimal? CapacityKwh { get; set; }
    [Required] public bool? IsAvailable { get; set; }
    public IEnumerable<ValidationResult> Validate(ValidationContext context)
    {
        // Empty slot names would make operational selection ambiguous.
        if (string.IsNullOrWhiteSpace(Name)) yield return new ValidationResult("Enter a battery slot name.", [nameof(Name)]);
    }
}
public sealed class OpeningHoursRequestDTO : IValidatableObject
{
    [Required, Range(0, 6)] public int? DayOfWeek { get; set; }
    [Required] public string OpensAt { get; set; } = string.Empty;
    [Required] public string ClosesAt { get; set; } = string.Empty;
    public IEnumerable<ValidationResult> Validate(ValidationContext context)
    {
        // Each day has one same-day interval; omitted weekdays are closed.
        if (!TimeOnly.TryParseExact(OpensAt, "HH:mm", CultureInfo.InvariantCulture, DateTimeStyles.None, out var open)
            || !TimeOnly.TryParseExact(ClosesAt, "HH:mm", CultureInfo.InvariantCulture, DateTimeStyles.None, out var close)
            || close <= open)
            yield return new ValidationResult("Opening hours must use HH:mm, with closing time after opening time on the same day.");
    }
}
public sealed class NodeStatusRequestDTO
{
    [Required] public bool? IsActive { get; set; }
    [Required, Range(1, long.MaxValue)] public long? Revision { get; set; }
}
public sealed class SlotAvailabilityRequestDTO
{
    [Required] public bool? IsAvailable { get; set; }
    [Required, Range(1, long.MaxValue)] public long? Revision { get; set; }
}
