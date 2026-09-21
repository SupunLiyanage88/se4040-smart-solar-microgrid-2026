// Smart Solar Microgrid Trading System
// Read-only contract with energy reservations; booking creation is a separate feature.
using MongoDB.Bson;
using MongoDB.Driver;
namespace SmartSolarMicrogrid.Api.Services;
public sealed class NodeReservationGuard
{
    public const string CollectionName = "energy_reservations";
    private readonly IMongoCollection<BsonDocument> _reservations;
    private static readonly string[] TerminalStates = ["COMPLETED", "CANCELLED", "REJECTED"];
    public NodeReservationGuard(IMongoDatabase database)
    {
        // Unknown/missing statuses are treated conservatively as active, never ignored.
        _reservations = database.GetCollection<BsonDocument>(CollectionName);
    }
    public async Task<long> CountActiveAsync(string nodeId, CancellationToken ct)
    {
        // Pending, approved and in-progress reservations block even when their dates are past.
        var filter = Builders<BsonDocument>.Filter.Eq("NodeId", nodeId)
            & Builders<BsonDocument>.Filter.Nin("Status", TerminalStates);
        return await _reservations.CountDocumentsAsync(filter, cancellationToken: ct);
    }
    public async Task<bool> HasSlotHistoryAsync(string nodeId, IEnumerable<string> slotIds, CancellationToken ct)
    {
        // Preserve slot identifiers referenced by any historical booking.
        var filter = Builders<BsonDocument>.Filter.Eq("NodeId", nodeId)
            & Builders<BsonDocument>.Filter.In("SlotId", slotIds);
        return await _reservations.Find(filter).AnyAsync(ct);
    }
    public static async Task InitializeAsync(IMongoDatabase database)
    {
        // These indexes also support the future reservation module's node/slot queries.
        var collection = database.GetCollection<BsonDocument>(CollectionName);
        await collection.Indexes.CreateManyAsync([
            new CreateIndexModel<BsonDocument>(Builders<BsonDocument>.IndexKeys.Ascending("NodeId").Ascending("Status")),
            new CreateIndexModel<BsonDocument>(Builders<BsonDocument>.IndexKeys.Ascending("NodeId").Ascending("SlotId"))]);
    }
}
