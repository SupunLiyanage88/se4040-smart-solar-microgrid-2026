// Smart Solar Microgrid Trading System
// Status values stored as uppercase strings so hub deactivation can read them.
using System.Text.Json.Serialization;
namespace SmartSolarMicrogrid.Api.Models;
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum ReservationStatus
{
    PENDING,
    APPROVED,
    COMPLETED,
    CANCELLED,
    REJECTED
}
