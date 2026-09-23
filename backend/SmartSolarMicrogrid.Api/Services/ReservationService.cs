// Smart Solar Microgrid Trading System
// Reservation rules: 7-day horizon, 12-hour notice, slot capacity and QR completion.
using System.Security.Cryptography;
using MongoDB.Bson;
using MongoDB.Driver;
using SmartSolarMicrogrid.Api.DTO.ReservationDTO;
using SmartSolarMicrogrid.Api.Interfaces;
using SmartSolarMicrogrid.Api.Models;
namespace SmartSolarMicrogrid.Api.Services;
public sealed class ReservationService
{
    private static readonly ReservationStatus[] OpenStatuses = [ReservationStatus.PENDING, ReservationStatus.APPROVED];
    private static readonly TimeZoneInfo Colombo = ResolveColombo();
    private readonly IMongoCollection<EnergyReservation> _reservations;
    private readonly IMongoCollection<EnergyBookingSlot> _bookingSlots;
    private readonly IMongoCollection<MicrogridNode> _nodes;
    private readonly IUserInterface _users;
    public ReservationService(IMongoDatabase database, IUserInterface users)
    {
        // Reservations share the collection the hub guard already reads.
        _reservations = database.GetCollection<EnergyReservation>(NodeReservationGuard.CollectionName);
        _bookingSlots = database.GetCollection<EnergyBookingSlot>(EnergyBookingSlot.CollectionName);
        _nodes = database.GetCollection<MicrogridNode>(MicrogridNodeService.CollectionName);
        _users = users;
    }
    public async Task<ReservationResponseDTO> CreateAsync(CreateReservationRequestDTO request, string callerNic, bool staff, CancellationToken ct)
    {
        // New bookings wait for approval and do not issue a QR token yet.
        var nic = await ResolveProsumerAsync(request.ProsumerNic, callerNic, staff, ct);
        var start = AsUtc(request.StartsAtUtc!.Value);
        var end = AsUtc(request.EndsAtUtc!.Value);
        var slot = await RequireBookableSlotAsync(request.NodeId, request.SlotId, start, end, request.RequestedKwh!.Value, ct);
        var reservation = new EnergyReservation
        {
            ProsumerNic = nic,
            NodeId = slot.Node.Id,
            SlotId = slot.Battery.Id,
            BookingSlotId = Guid.NewGuid().ToString("N"),
            Status = ReservationStatus.PENDING,
            Direction = request.Direction,
            RequestedKwh = request.RequestedKwh.Value,
            StartsAtUtc = start,
            EndsAtUtc = end,
            CreatedAtUtc = DateTime.UtcNow,
            UpdatedAtUtc = DateTime.UtcNow
        };
        var bookingSlot = BookingSlotFrom(reservation);
        await _bookingSlots.InsertOneAsync(bookingSlot, cancellationToken: ct);
        await _reservations.InsertOneAsync(reservation, cancellationToken: ct);
        try
        {
            await EnsureStoredCapacityAsync(slot.Node.Id, slot.Battery.Id, slot.Battery.CapacityKwh, start, end, ct);
        }
        catch
        {
            await _reservations.DeleteOneAsync(r => r.Id == reservation.Id, ct);
            await _bookingSlots.DeleteOneAsync(s => s.Id == bookingSlot.Id, ct);
            throw;
        }
        return ToResponse(reservation, includeQr: false);
    }
    public async Task<List<ReservationResponseDTO>> ListAsync(string callerNic, bool staff, string? view, string? search, CancellationToken ct)
    {
        // Prosumers only receive their own bookings. Staff can monitor every booking.
        var filter = staff
            ? Builders<EnergyReservation>.Filter.Empty
            : Builders<EnergyReservation>.Filter.Eq(r => r.ProsumerNic, callerNic);
        filter &= ViewFilter(view);
        if (!string.IsNullOrWhiteSpace(search))
        {
            var term = new MongoDB.Bson.BsonRegularExpression(System.Text.RegularExpressions.Regex.Escape(search.Trim()), "i");
            filter &= Builders<EnergyReservation>.Filter.Or(
                Builders<EnergyReservation>.Filter.Regex(r => r.Id, term),
                Builders<EnergyReservation>.Filter.Regex(r => r.ProsumerNic, term),
                Builders<EnergyReservation>.Filter.Regex(r => r.NodeId, term),
                Builders<EnergyReservation>.Filter.Regex(r => r.SlotId, term),
                Builders<EnergyReservation>.Filter.Regex(r => r.Direction, term));
        }
        var rows = await _reservations.Find(filter).SortByDescending(r => r.StartsAtUtc).Limit(200).ToListAsync(ct);
        return rows.Select(r => ToResponse(r, includeQr: !staff && r.ProsumerNic == callerNic)).ToList();
    }
    public async Task<ReservationSummaryDTO> SummaryAsync(string callerNic, bool staff, CancellationToken ct)
    {
        // Dashboard counts: pending bookings, and approved bookings that have not started.
        var owner = staff
            ? Builders<EnergyReservation>.Filter.Empty
            : Builders<EnergyReservation>.Filter.Eq(r => r.ProsumerNic, callerNic);
        var pending = owner & Builders<EnergyReservation>.Filter.Eq(r => r.Status, ReservationStatus.PENDING);
        var approvedFuture = owner
            & Builders<EnergyReservation>.Filter.Eq(r => r.Status, ReservationStatus.APPROVED)
            & Builders<EnergyReservation>.Filter.Gt(r => r.StartsAtUtc, DateTime.UtcNow);
        return new ReservationSummaryDTO
        {
            PendingCount = await _reservations.CountDocumentsAsync(pending, cancellationToken: ct),
            ApprovedFutureCount = await _reservations.CountDocumentsAsync(approvedFuture, cancellationToken: ct)
        };
    }
    public async Task<ReservationResponseDTO> GetAsync(string id, string callerNic, bool staff, CancellationToken ct)
    {
        // Hide another prosumer's booking instead of returning it.
        var reservation = await RequireAsync(id, ct);
        EnsureCanRead(reservation, callerNic, staff);
        return ToResponse(reservation, includeQr: !staff && reservation.ProsumerNic == callerNic);
    }
    public async Task<ReservationResponseDTO> UpdateAsync(string id, UpdateReservationRequestDTO request, string callerNic, bool staff, CancellationToken ct)
    {
        // Notice is measured against the current start. The new window is validated separately.
        var reservation = await RequireChangeableAsync(id, callerNic, staff, ct);
        RequireNotice(reservation.StartsAtUtc);
        var start = AsUtc(request.StartsAtUtc!.Value);
        var end = AsUtc(request.EndsAtUtc!.Value);
        var slot = await RequireBookableSlotAsync(request.NodeId, request.SlotId, start, end, request.RequestedKwh!.Value, ct);
        await EnsureProposedCapacityAsync(slot.Node.Id, slot.Battery.Id, slot.Battery.CapacityKwh, start, end, request.RequestedKwh.Value, reservation.Id, ct);
        var previous = Copy(reservation);
        var previousSlot = await _bookingSlots.Find(s => s.Id == reservation.BookingSlotId).FirstOrDefaultAsync(ct);
        reservation.NodeId = slot.Node.Id;
        reservation.SlotId = slot.Battery.Id;
        reservation.Direction = request.Direction;
        reservation.RequestedKwh = request.RequestedKwh.Value;
        reservation.StartsAtUtc = start;
        reservation.EndsAtUtc = end;
        reservation.Status = ReservationStatus.PENDING;
        reservation.QrToken = null;
        reservation.UpdatedAtUtc = DateTime.UtcNow;
        await ReplaceReservationAsync(reservation, ct);
        var bookingSlot = BookingSlotFrom(reservation);
        await _bookingSlots.ReplaceOneAsync(s => s.Id == bookingSlot.Id, bookingSlot, new ReplaceOptions { IsUpsert = true }, ct);
        try
        {
            await EnsureStoredCapacityAsync(slot.Node.Id, slot.Battery.Id, slot.Battery.CapacityKwh, start, end, ct);
        }
        catch
        {
            await ReplaceReservationAsync(previous, ct);
            if (previousSlot is not null)
                await _bookingSlots.ReplaceOneAsync(s => s.Id == previousSlot.Id, previousSlot, cancellationToken: ct);
            throw;
        }
        return ToResponse(reservation, includeQr: false);
    }
    public async Task<ReservationResponseDTO> CancelAsync(string id, string callerNic, bool staff, CancellationToken ct)
    {
        // Cancellation is terminal and removes any QR token already issued.
        var reservation = await RequireChangeableAsync(id, callerNic, staff, ct);
        RequireNotice(reservation.StartsAtUtc);
        reservation.Status = ReservationStatus.CANCELLED;
        reservation.QrToken = null;
        reservation.UpdatedAtUtc = DateTime.UtcNow;
        await ReplaceReservationAsync(reservation, ct);
        return ToResponse(reservation, includeQr: false);
    }
    public async Task<ReservationResponseDTO> DecideAsync(string id, string decision, CancellationToken ct)
    {
        // Approval issues a new opaque token. Rejection closes the booking.
        var reservation = await RequireAsync(id, ct);
        if (reservation.Status != ReservationStatus.PENDING)
            throw new ReservationRuleException(409, "Only a pending reservation can be approved or rejected.");
        if (decision == "APPROVE")
        {
            reservation.Status = ReservationStatus.APPROVED;
            reservation.QrToken = Convert.ToHexString(RandomNumberGenerator.GetBytes(32));
        }
        else if (decision == "REJECT")
        {
            reservation.Status = ReservationStatus.REJECTED;
            reservation.QrToken = null;
        }
        else throw new ReservationRuleException(400, "Decision must be APPROVE or REJECT.");
        reservation.UpdatedAtUtc = DateTime.UtcNow;
        await ReplaceReservationAsync(reservation, ct);
        return ToResponse(reservation, includeQr: false);
    }
    public async Task<ReservationResponseDTO> CompleteAsync(string qrToken, CancellationToken ct)
    {
        // A token can complete a transfer only once, and only while the booking is approved.
        var updated = await _reservations.FindOneAndUpdateAsync(
            r => r.QrToken == qrToken && r.Status == ReservationStatus.APPROVED,
            Builders<EnergyReservation>.Update
                .Set(r => r.Status, ReservationStatus.COMPLETED)
                .Unset(r => r.QrToken)
                .Set(r => r.UpdatedAtUtc, DateTime.UtcNow),
            new FindOneAndUpdateOptions<EnergyReservation> { ReturnDocument = ReturnDocument.After },
            ct);
        if (updated is null)
            throw new ReservationRuleException(409, "This transaction QR cannot be completed.");
        return ToResponse(updated, includeQr: false);
    }
    public static async Task InitializeAsync(IMongoDatabase database)
    {
        // An empty token must not occupy the unique index, so each pending booking can be saved.
        var reservations = database.GetCollection<EnergyReservation>(NodeReservationGuard.CollectionName);
        await reservations.UpdateManyAsync(
            Builders<EnergyReservation>.Filter.Eq("QrToken", BsonNull.Value),
            Builders<EnergyReservation>.Update.Unset(r => r.QrToken));
        await reservations.Indexes.CreateManyAsync([
            new CreateIndexModel<EnergyReservation>(Builders<EnergyReservation>.IndexKeys.Ascending(r => r.ProsumerNic).Ascending(r => r.Status)),
            new CreateIndexModel<EnergyReservation>(Builders<EnergyReservation>.IndexKeys.Ascending(r => r.QrToken), new CreateIndexOptions { Unique = true, Sparse = true })]);
        await database.GetCollection<EnergyBookingSlot>(EnergyBookingSlot.CollectionName).Indexes.CreateOneAsync(
            new CreateIndexModel<EnergyBookingSlot>(Builders<EnergyBookingSlot>.IndexKeys.Ascending(s => s.NodeId).Ascending(s => s.SlotId)));
    }
    private async Task<string> ResolveProsumerAsync(string? requestedNic, string callerNic, bool staff, CancellationToken ct)
    {
        // Staff book on behalf of an active prosumer. A prosumer can book only for themselves.
        if (!staff)
        {
            if (!string.IsNullOrWhiteSpace(requestedNic) && !string.Equals(requestedNic.Trim(), callerNic, StringComparison.OrdinalIgnoreCase))
                throw new ReservationRuleException(403, "You can only create a reservation for your own account.");
            return callerNic;
        }
        if (string.IsNullOrWhiteSpace(requestedNic))
            throw new ReservationRuleException(400, "Enter the prosumer NIC.");
        var user = await _users.GetByIdAsync(requestedNic, ct);
        if (user is null || user.Role != UserRole.PROSUMER || !user.Activation)
            throw new ReservationRuleException(400, "An active prosumer account is required.");
        return user.NIC;
    }
    private async Task<(MicrogridNode Node, BatterySlot Battery)> RequireBookableSlotAsync(string nodeId, string slotId, DateTime start, DateTime end, decimal requestedKwh, CancellationToken ct)
    {
        // The hub, physical slot, local operating hours, horizon and power limit must all allow the window.
        if (start <= DateTime.UtcNow)
            throw new ReservationRuleException(400, "Reservations must start in the future.");
        if (start > DateTime.UtcNow.AddDays(7))
            throw new ReservationRuleException(400, "Reservations must be scheduled within 7 days.");
        var node = await _nodes.Find(n => n.Id == nodeId).FirstOrDefaultAsync(ct);
        if (node is null || !node.IsActive)
            throw new ReservationRuleException(404, "This grid node is not available for reservations.");
        var battery = node.BatterySlots.FirstOrDefault(s => s.Id == slotId);
        if (battery is null)
            throw new ReservationRuleException(404, "Battery slot not found.");
        if (!battery.IsAvailable)
            throw new ReservationRuleException(409, "This battery slot is not available.");
        if (requestedKwh > battery.CapacityKwh)
            throw new ReservationRuleException(409, "The requested energy exceeds this battery slot capacity.");
        var hours = (decimal)(end - start).TotalHours;
        if (requestedKwh > node.PowerCapacityKw * hours)
            throw new ReservationRuleException(409, "The requested energy exceeds this node's power capacity for the selected duration.");
        RequireOpen(node, start, end);
        return (node, battery);
    }
    private static void RequireOpen(MicrogridNode node, DateTime start, DateTime end)
    {
        // Operating hours are weekly Asia/Colombo intervals and do not cross midnight.
        var startLocal = TimeZoneInfo.ConvertTimeFromUtc(start, Colombo);
        var endLocal = TimeZoneInfo.ConvertTimeFromUtc(end, Colombo);
        if (startLocal.Date != endLocal.Date)
            throw new ReservationRuleException(400, "A reservation must start and end on the same local day.");
        var hours = node.Schedule.FirstOrDefault(s => s.DayOfWeek == (int)startLocal.DayOfWeek);
        if (hours is null
            || TimeOnly.FromDateTime(startLocal) < TimeOnly.Parse(hours.OpensAt)
            || TimeOnly.FromDateTime(endLocal) > TimeOnly.Parse(hours.ClosesAt))
            throw new ReservationRuleException(400, "The selected time is outside this node's operating hours.");
    }
    private async Task EnsureProposedCapacityAsync(string nodeId, string slotId, decimal capacity, DateTime start, DateTime end, decimal requestedKwh, string? excludeId, CancellationToken ct)
    {
        // Other open bookings on this physical slot must leave enough stored energy.
        var rows = await _reservations.Find(OverlapFilter(nodeId, slotId, start, end, excludeId)).ToListAsync(ct);
        if (rows.Sum(r => r.RequestedKwh) + requestedKwh > capacity)
            throw new ReservationRuleException(409, "The selected battery slot does not have enough remaining capacity.");
    }
    private async Task EnsureStoredCapacityAsync(string nodeId, string slotId, decimal capacity, DateTime start, DateTime end, CancellationToken ct)
    {
        // Recheck after the write so two simultaneous bookings cannot both take the last capacity.
        var rows = await _reservations.Find(OverlapFilter(nodeId, slotId, start, end, null)).ToListAsync(ct);
        if (rows.Sum(r => r.RequestedKwh) > capacity)
            throw new ReservationRuleException(409, "The selected battery slot does not have enough remaining capacity.");
    }
    private static FilterDefinition<EnergyReservation> OverlapFilter(string nodeId, string slotId, DateTime start, DateTime end, string? excludeId)
    {
        // Overlap is any open booking whose window intersects this one on the same physical slot.
        var filter = Builders<EnergyReservation>.Filter.Eq(r => r.NodeId, nodeId)
            & Builders<EnergyReservation>.Filter.Eq(r => r.SlotId, slotId)
            & Builders<EnergyReservation>.Filter.In(r => r.Status, OpenStatuses)
            & Builders<EnergyReservation>.Filter.Lt(r => r.StartsAtUtc, end)
            & Builders<EnergyReservation>.Filter.Gt(r => r.EndsAtUtc, start);
        if (excludeId is not null)
            filter &= Builders<EnergyReservation>.Filter.Ne(r => r.Id, excludeId);
        return filter;
    }
    private static FilterDefinition<EnergyReservation> ViewFilter(string? view)
    {
        // Current, pending and history are the booking lists the mobile dashboard requests.
        var now = DateTime.UtcNow;
        return view?.Trim().ToLowerInvariant() switch
        {
            null or "" or "all" => Builders<EnergyReservation>.Filter.Empty,
            "current" => Builders<EnergyReservation>.Filter.In(r => r.Status, OpenStatuses)
                & Builders<EnergyReservation>.Filter.Gte(r => r.EndsAtUtc, now),
            "pending" => Builders<EnergyReservation>.Filter.Eq(r => r.Status, ReservationStatus.PENDING),
            "history" => Builders<EnergyReservation>.Filter.In(r => r.Status, [ReservationStatus.COMPLETED, ReservationStatus.CANCELLED, ReservationStatus.REJECTED]),
            _ => throw new ReservationRuleException(400, "View must be current, pending, history or all.")
        };
    }
    private async Task<EnergyReservation> RequireChangeableAsync(string id, string callerNic, bool staff, CancellationToken ct)
    {
        // Finished bookings stay as history and cannot be edited or cancelled.
        var reservation = await RequireAsync(id, ct);
        EnsureCanRead(reservation, callerNic, staff);
        if (reservation.Status is ReservationStatus.COMPLETED or ReservationStatus.CANCELLED or ReservationStatus.REJECTED)
            throw new ReservationRuleException(409, "This reservation can no longer be changed.");
        return reservation;
    }
    private async Task<EnergyReservation> RequireAsync(string id, CancellationToken ct)
    {
        // Missing bookings are a not-found result rather than an empty document.
        return await _reservations.Find(r => r.Id == id).FirstOrDefaultAsync(ct)
            ?? throw new ReservationRuleException(404, "Reservation not found.");
    }
    private static void EnsureCanRead(EnergyReservation reservation, string callerNic, bool staff)
    {
        // Ownership is enforced in the API even when a client guesses another reservation id.
        if (!staff && reservation.ProsumerNic != callerNic)
            throw new ReservationRuleException(403, "You can only access your own reservations.");
    }
    private static void RequireNotice(DateTime startsAtUtc)
    {
        // Exactly 12 hours before the existing start is still enough notice.
        if (startsAtUtc - DateTime.UtcNow < TimeSpan.FromHours(12))
            throw new ReservationRuleException(409, "Updates and cancellations require at least 12 hours' notice.");
    }
    private async Task ReplaceReservationAsync(EnergyReservation reservation, CancellationToken ct)
    {
        // Persist the booking document after the rule checks have passed.
        await _reservations.ReplaceOneAsync(r => r.Id == reservation.Id, reservation, cancellationToken: ct);
    }
    private static EnergyBookingSlot BookingSlotFrom(EnergyReservation reservation)
    {
        // The time-window record points at the same hub and physical slot as the reservation.
        return new EnergyBookingSlot
        {
            Id = reservation.BookingSlotId,
            NodeId = reservation.NodeId,
            SlotId = reservation.SlotId,
            ReservationId = reservation.Id,
            CapacityKwh = reservation.RequestedKwh,
            StartsAtUtc = reservation.StartsAtUtc,
            EndsAtUtc = reservation.EndsAtUtc
        };
    }
    private static EnergyReservation Copy(EnergyReservation reservation)
    {
        // Keep the previous booking so a failed capacity recheck can restore it.
        return new EnergyReservation
        {
            Id = reservation.Id,
            ProsumerNic = reservation.ProsumerNic,
            NodeId = reservation.NodeId,
            SlotId = reservation.SlotId,
            BookingSlotId = reservation.BookingSlotId,
            Status = reservation.Status,
            Direction = reservation.Direction,
            RequestedKwh = reservation.RequestedKwh,
            StartsAtUtc = reservation.StartsAtUtc,
            EndsAtUtc = reservation.EndsAtUtc,
            QrToken = reservation.QrToken,
            CreatedAtUtc = reservation.CreatedAtUtc,
            UpdatedAtUtc = reservation.UpdatedAtUtc
        };
    }
    private static ReservationResponseDTO ToResponse(EnergyReservation reservation, bool includeQr)
    {
        // The QR token is returned only to the owning prosumer after approval.
        return new ReservationResponseDTO
        {
            Id = reservation.Id,
            ProsumerNic = reservation.ProsumerNic,
            NodeId = reservation.NodeId,
            SlotId = reservation.SlotId,
            BookingSlotId = reservation.BookingSlotId,
            Status = reservation.Status.ToString(),
            Direction = reservation.Direction,
            RequestedKwh = reservation.RequestedKwh,
            StartsAtUtc = reservation.StartsAtUtc,
            EndsAtUtc = reservation.EndsAtUtc,
            QrToken = includeQr && reservation.Status == ReservationStatus.APPROVED ? reservation.QrToken : null,
            CreatedAtUtc = reservation.CreatedAtUtc,
            UpdatedAtUtc = reservation.UpdatedAtUtc
        };
    }
    private static DateTime AsUtc(DateTime value)
    {
        // Request fields are UTC instants. A value without a kind is already expressed in UTC.
        return value.Kind switch
        {
            DateTimeKind.Utc => value,
            DateTimeKind.Local => value.ToUniversalTime(),
            _ => DateTime.SpecifyKind(value, DateTimeKind.Utc)
        };
    }
    private static TimeZoneInfo ResolveColombo()
    {
        // Hub schedules use Asia/Colombo. Older Windows zone data exposes the legacy id.
        try { return TimeZoneInfo.FindSystemTimeZoneById("Asia/Colombo"); }
        catch (TimeZoneNotFoundException) { return TimeZoneInfo.FindSystemTimeZoneById("Sri Lanka Standard Time"); }
    }
}
