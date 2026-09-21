// Smart Solar Microgrid Trading System
// Grid hub aggregate: physical battery slots and recurring local operating hours.
using MongoDB.Bson.Serialization.Attributes;
namespace SmartSolarMicrogrid.Api.Models;
public sealed class MicrogridNode
{
    [BsonId] public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = string.Empty;
    public string Address { get; set; } = string.Empty;
    public double Latitude { get; set; }
    public double Longitude { get; set; }
    public decimal PowerCapacityKw { get; set; }
    public List<BatterySlot> BatterySlots { get; set; } = [];
    public List<NodeOpeningHours> Schedule { get; set; } = [];
    public string TimeZone { get; set; } = "Asia/Colombo";
    public bool IsActive { get; set; } = true;
    public long Revision { get; set; } = 1;
    public DateTime CreatedAtUtc { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAtUtc { get; set; } = DateTime.UtcNow;
    [BsonIgnore] public long ActiveReservationCount { get; set; }
}
public sealed class BatterySlot
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = string.Empty;
    public decimal CapacityKwh { get; set; }
    public bool IsAvailable { get; set; } = true;
}
public sealed class NodeOpeningHours
{
    public int DayOfWeek { get; set; }
    public string OpensAt { get; set; } = string.Empty;
    public string ClosesAt { get; set; } = string.Empty;
}
