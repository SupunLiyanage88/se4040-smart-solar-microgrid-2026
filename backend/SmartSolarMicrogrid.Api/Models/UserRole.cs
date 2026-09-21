// Smart Solar Microgrid Trading System
// Canonical role names shared by the API and clients.
using System.Text.Json.Serialization;
namespace SmartSolarMicrogrid.Api.Models;
[JsonConverter(typeof(JsonStringEnumConverter))]
public enum UserRole { BACKOFFICE, GRID_OPERATOR, PROSUMER }
