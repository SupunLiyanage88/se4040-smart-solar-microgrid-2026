// Smart Solar Microgrid Trading System
// Reservation data returned to clients. The QR token is included only for the owning prosumer.
namespace SmartSolarMicrogrid.Api.DTO.ReservationDTO;
public sealed class ReservationResponseDTO
{
    public string Id { get; set; } = string.Empty;
    public string ProsumerNic { get; set; } = string.Empty;
    public string NodeId { get; set; } = string.Empty;
    public string SlotId { get; set; } = string.Empty;
    public string BookingSlotId { get; set; } = string.Empty;
    public string Status { get; set; } = string.Empty;
    public string Direction { get; set; } = string.Empty;
    public decimal RequestedKwh { get; set; }
    public DateTime StartsAtUtc { get; set; }
    public DateTime EndsAtUtc { get; set; }
    public string? QrToken { get; set; }
    public DateTime CreatedAtUtc { get; set; }
    public DateTime UpdatedAtUtc { get; set; }
}
public sealed class ReservationSummaryDTO
{
    public long PendingCount { get; set; }
    public long ApprovedFutureCount { get; set; }
}
