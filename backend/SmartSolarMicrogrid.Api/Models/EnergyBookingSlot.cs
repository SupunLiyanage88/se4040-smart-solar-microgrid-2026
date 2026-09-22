// Smart Solar Microgrid Trading System
// Bookable time window. This is separate from the physical battery slot on a hub.
using MongoDB.Bson.Serialization.Attributes;
namespace SmartSolarMicrogrid.Api.Models;
public sealed class EnergyBookingSlot
{
    public const string CollectionName = "energy_booking_slots";
    [BsonId] public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string NodeId { get; set; } = string.Empty;
    public string SlotId { get; set; } = string.Empty;
    public string? ReservationId { get; set; }
    public decimal CapacityKwh { get; set; }
    public DateTime StartsAtUtc { get; set; }
    public DateTime EndsAtUtc { get; set; }
}
