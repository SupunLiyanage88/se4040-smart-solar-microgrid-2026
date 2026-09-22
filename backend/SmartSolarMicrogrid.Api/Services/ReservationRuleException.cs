// Smart Solar Microgrid Trading System
// Expected reservation rule failures translated into HTTP responses.
namespace SmartSolarMicrogrid.Api.Services;
public sealed class ReservationRuleException(int statusCode, string message) : Exception(message)
{
    public int StatusCode { get; } = statusCode;
}
