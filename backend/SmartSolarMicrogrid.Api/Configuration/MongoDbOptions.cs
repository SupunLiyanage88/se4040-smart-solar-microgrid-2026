// Smart Solar Microgrid Trading System
// Defines the service-owned MongoDB connection settings.

namespace SmartSolarMicrogrid.Api.Configuration;

public sealed class MongoDbOptions
{
    public const string SectionName = "MongoDb";

    public string ConnectionString { get; init; } = string.Empty;

    public string DatabaseName { get; init; } = string.Empty;
}

