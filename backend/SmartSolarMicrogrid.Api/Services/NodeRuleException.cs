// Smart Solar Microgrid Trading System
// Expected node validation and conflict failures translated into HTTP responses.
namespace SmartSolarMicrogrid.Api.Services;
public sealed class NodeRuleException(int statusCode, string message) : Exception(message)
{
    public int StatusCode { get; } = statusCode;
}
