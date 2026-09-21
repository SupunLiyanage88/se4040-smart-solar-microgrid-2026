// Smart Solar Microgrid Trading System
// Central hub rules and optimistic concurrency for configuration/status/availability changes.
using System.Text.Json;
using MongoDB.Driver;
using SmartSolarMicrogrid.Api.DTO.NodeDTO;
using SmartSolarMicrogrid.Api.Models;
namespace SmartSolarMicrogrid.Api.Services;
public sealed class MicrogridNodeService
{
    public const string CollectionName = "solar_station_info";
    private readonly IMongoCollection<MicrogridNode> _nodes;
    private readonly NodeReservationGuard _reservations;
    public MicrogridNodeService(IMongoDatabase database, NodeReservationGuard reservations)
    {
        // Embedded physical slots and schedules are saved atomically with the hub.
        _nodes = database.GetCollection<MicrogridNode>(CollectionName);
        _reservations = reservations;
    }
    public async Task<List<MicrogridNode>> ListAsync(bool staff, CancellationToken ct)
    {
        // Prosumers see only active hubs; staff can inspect and reactivate inactive hubs.
        var nodes = await _nodes.Find(staff ? Builders<MicrogridNode>.Filter.Empty
            : Builders<MicrogridNode>.Filter.Eq(n => n.IsActive, true)).SortBy(n => n.Name).ToListAsync(ct);
        if (staff)
            foreach (var node in nodes) node.ActiveReservationCount = await _reservations.CountActiveAsync(node.Id, ct);
        return nodes;
    }
    public async Task<MicrogridNode> GetAsync(string id, bool staff, CancellationToken ct)
    {
        // String IDs make malformed lookup values harmless 404s rather than database errors.
        var node = await _nodes.Find(n => n.Id == id).FirstOrDefaultAsync(ct);
        if (node is null || (!staff && !node.IsActive)) throw new NodeRuleException(404, "Grid node not found.");
        if (staff) node.ActiveReservationCount = await _reservations.CountActiveAsync(node.Id, ct);
        return node;
    }
    public async Task<MicrogridNode> CreateAsync(NodeRequestDTO request, CancellationToken ct)
    {
        // Only the service assigns node/slot identities and initial activation state.
        if (request.BatterySlots.Any(s => s.Id is not null)) throw new NodeRuleException(400, "Do not supply slot IDs when creating a node.");
        var node = new MicrogridNode();
        Apply(node, request);
        await _nodes.InsertOneAsync(node, cancellationToken: ct);
        return node;
    }
    public async Task<MicrogridNode> UpdateAsync(string id, UpdateNodeRequestDTO request, CancellationToken ct)
    {
        // Validate references before any write; optimistic revision matching prevents lost changes.
        var node = await GetAsync(id, true, ct);
        EnsureRevision(node, request.Revision!.Value);
        var ids = request.BatterySlots.Where(s => s.Id is not null).Select(s => s.Id!).ToList();
        if (ids.Distinct().Count() != ids.Count || ids.Any(slotId => node.BatterySlots.All(s => s.Id != slotId)))
            throw new NodeRuleException(400, "Existing slot IDs must be unique and belong to this node. Omit the ID for new slots.");
        var removed = node.BatterySlots.Select(s => s.Id).Except(ids).ToList();
        if (removed.Count > 0 && await _reservations.HasSlotHistoryAsync(id, removed, ct))
            throw new NodeRuleException(409, "A battery slot with booking history cannot be removed. Mark it unavailable instead.");
        var previousCapacity = node.PowerCapacityKw;
        var previousSlots = JsonSerializer.Serialize(node.BatterySlots);
        var previousSchedule = JsonSerializer.Serialize(node.Schedule);
        Apply(node, request);
        if (node.ActiveReservationCount > 0 && (node.PowerCapacityKw != previousCapacity
            || JsonSerializer.Serialize(node.BatterySlots) != previousSlots || JsonSerializer.Serialize(node.Schedule) != previousSchedule))
            throw new NodeRuleException(409, "Capacity, battery slots and schedules cannot change while this node has active reservations.");
        return await SaveAsync(node, request.Revision.Value, ct);
    }
    public async Task<MicrogridNode> SetStatusAsync(string id, NodeStatusRequestDTO request, CancellationToken ct)
    {
        // Never deactivate a hub with non-terminal energy reservations.
        var node = await GetAsync(id, true, ct);
        EnsureRevision(node, request.Revision!.Value);
        if (!request.IsActive!.Value && node.ActiveReservationCount > 0)
            throw new NodeRuleException(409, $"Cannot deactivate this node: {node.ActiveReservationCount} active reservation(s) remain.");
        node.IsActive = request.IsActive.Value;
        return await SaveAsync(node, request.Revision.Value, ct);
    }
    public async Task<MicrogridNode> SetSlotAvailabilityAsync(string id, string slotId, SlotAvailabilityRequestDTO request, CancellationToken ct)
    {
        // Operators can change availability without gaining hub administration permissions.
        var node = await GetAsync(id, true, ct);
        EnsureRevision(node, request.Revision!.Value);
        var slot = node.BatterySlots.FirstOrDefault(s => s.Id == slotId)
            ?? throw new NodeRuleException(404, "Battery slot not found.");
        if (!node.IsActive) throw new NodeRuleException(409, "Reactivate the node before changing slot availability.");
        if (slot.IsAvailable != request.IsAvailable!.Value && node.ActiveReservationCount > 0)
            throw new NodeRuleException(409, "Slot availability cannot change while this node has active reservations.");
        slot.IsAvailable = request.IsAvailable.Value;
        return await SaveAsync(node, request.Revision.Value, ct);
    }
    private static void Apply(MicrogridNode node, NodeRequestDTO request)
    {
        // Normalize display fields and canonicalize schedule ordering for stable comparisons.
        node.Name = request.Name.Trim(); node.Address = request.Address.Trim();
        node.Latitude = request.Latitude!.Value; node.Longitude = request.Longitude!.Value;
        node.PowerCapacityKw = request.PowerCapacityKw!.Value;
        node.BatterySlots = request.BatterySlots.Select(s => new BatterySlot {
            Id = s.Id ?? Guid.NewGuid().ToString("N"), Name = s.Name.Trim(),
            CapacityKwh = s.CapacityKwh!.Value, IsAvailable = s.IsAvailable!.Value }).ToList();
        node.Schedule = request.Schedule.OrderBy(s => s.DayOfWeek).Select(s => new NodeOpeningHours {
            DayOfWeek = s.DayOfWeek!.Value, OpensAt = s.OpensAt, ClosesAt = s.ClosesAt }).ToList();
    }
    private static void EnsureRevision(MicrogridNode node, long revision)
    {
        // Reject edits made using an out-of-date version of this node.
        if (node.Revision != revision) throw new NodeRuleException(409, "This node changed. Refresh and retry with the latest details.");
    }
    private async Task<MicrogridNode> SaveAsync(MicrogridNode node, long revision, CancellationToken ct)
    {
        // Compare-and-swap coordinates simultaneous staff edits without requiring a replica set.
        node.Revision = revision + 1; node.UpdatedAtUtc = DateTime.UtcNow;
        var result = await _nodes.ReplaceOneAsync(n => n.Id == node.Id && n.Revision == revision, node, cancellationToken: ct);
        if (result.MatchedCount == 0) throw new NodeRuleException(409, "This node changed. Refresh and retry with the latest details.");
        return node;
    }
    public static async Task InitializeAsync(IMongoDatabase database)
    {
        // Create list/filter indexes once at startup.
        await database.GetCollection<MicrogridNode>(CollectionName).Indexes.CreateOneAsync(
            new CreateIndexModel<MicrogridNode>(Builders<MicrogridNode>.IndexKeys.Ascending(n => n.IsActive).Ascending(n => n.Name)));
        await NodeReservationGuard.InitializeAsync(database);
    }
}
