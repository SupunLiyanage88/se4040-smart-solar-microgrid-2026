// Smart Solar Microgrid Trading System
// MongoDB document representing a platform user.

using MongoDB.Bson;
using MongoDB.Bson.Serialization.Attributes;

namespace SmartSolarMicrogrid.Api.Models;

public class User
{
    [BsonId]
    [BsonRepresentation(BsonType.ObjectId)]
    public string? Id { get; set; }

    public string UserName { get; set; } = string.Empty;

    public string Email { get; set; } = string.Empty;

    public string NIC { get; set; } = string.Empty;

    public string PasswordHash { get; set; } = string.Empty;

    [BsonRepresentation(BsonType.String)]
    public UserRole Role { get; set; } = UserRole.PROCUMER;

    public bool Activation { get; set; }
}
