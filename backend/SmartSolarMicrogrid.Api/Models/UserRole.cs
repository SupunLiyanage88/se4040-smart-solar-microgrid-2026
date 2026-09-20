// Smart Solar Microgrid Trading System
// Roles a user can hold within the platform.

using System.Text.Json.Serialization;

namespace SmartSolarMicrogrid.Api.Models;

[JsonConverter(typeof(JsonStringEnumConverter))]
public enum UserRole
{
    BACKOFFICE,
    GRIDOPARATOR,
    PROCUMER
}
