// Smart Solar Microgrid Trading System
// JWT signing and validation settings.

namespace SmartSolarMicrogrid.Api.Configuration;

public sealed class JwtOptions
{
    public const string SectionName = "Jwt";

    public string Key { get; init; } = string.Empty;

    public string Issuer { get; init; } = "SmartSolarMicrogrid";

    public string Audience { get; init; } = "SmartSolarMicrogrid.Clients";

    public int ExpiryMinutes { get; init; } = 60;
}
