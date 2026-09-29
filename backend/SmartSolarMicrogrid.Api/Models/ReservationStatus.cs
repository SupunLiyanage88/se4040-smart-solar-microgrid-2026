// Smart Solar Microgrid Trading System
// Booking status names.
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
