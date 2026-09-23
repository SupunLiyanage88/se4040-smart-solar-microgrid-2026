// Smart Solar Microgrid Trading System
// Booking document. NodeId and SlotId match the hub deactivation guard.
using MongoDB.Bson;
using MongoDB.Bson.Serialization.Attributes;
namespace SmartSolarMicrogrid.Api.Models;
public sealed class EnergyReservation
{
    public const string CollectionName = "energy_reservations";
    [BsonId] public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string ProsumerNic { get; set; } = string.Empty;
    public string NodeId { get; set; } = string.Empty;
    public string SlotId { get; set; } = string.Empty;
    public string BookingSlotId { get; set; } = string.Empty;
    [BsonRepresentation(BsonType.String)]
    public ReservationStatus Status { get; set; } = ReservationStatus.PENDING;
    public string Direction { get; set; } = string.Empty;
    public decimal RequestedKwh { get; set; }
    public DateTime StartsAtUtc { get; set; }
    public DateTime EndsAtUtc { get; set; }
    [BsonIgnoreIfNull] public string? QrToken { get; set; }
    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAtUtc { get; set; } = DateTime.UtcNow;
}
