// Smart Solar Microgrid Trading System
// NIC is the immutable MongoDB primary key for each account.
using MongoDB.Bson.Serialization.Attributes;
namespace SmartSolarMicrogrid.Api.Models;
public class User
{
    [BsonId] public string NIC { get; set; } = string.Empty;
    [BsonIgnore] public string Id => NIC;
    public string UserName { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string PasswordHash { get; set; } = string.Empty;
    [BsonRepresentation(MongoDB.Bson.BsonType.String)]
    public UserRole Role { get; set; } = UserRole.PROSUMER;
    public bool Activation { get; set; }
    public bool ActivationPending { get; set; } = true;
    public bool DeactivationRequested { get; set; }
    public string SessionVersion { get; set; } = Guid.NewGuid().ToString("N");
}
