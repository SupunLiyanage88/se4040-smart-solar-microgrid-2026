// Smart Solar Microgrid Trading System
// Role-protected REST endpoints for hub administration and operator availability updates.
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SmartSolarMicrogrid.Api.DTO.NodeDTO;
using SmartSolarMicrogrid.Api.Models;
using SmartSolarMicrogrid.Api.Services;
namespace SmartSolarMicrogrid.Api.Controllers;
[ApiController]
[Route("api/nodes")]
[Authorize]
public sealed class MicrogridNodeController : ControllerBase
{
    private readonly MicrogridNodeService _nodes;
    public MicrogridNodeController(MicrogridNodeService nodes)
    {
        // Delegate business decisions to the central service.
        _nodes = nodes;
    }
    [HttpGet]
    public Task<List<MicrogridNode>> List(CancellationToken ct)
    {
        // Active prosumers may read locations for future mobile map integration.
        return _nodes.ListAsync(User.IsInRole("BACKOFFICE") || User.IsInRole("GRID_OPERATOR"), ct);
    }
    [HttpGet("{id}")]
    public Task<MicrogridNode> Get(string id, CancellationToken ct)
    {
        // Hide inactive nodes from prosumers as well as from their list endpoint.
        return _nodes.GetAsync(id, User.IsInRole("BACKOFFICE") || User.IsInRole("GRID_OPERATOR"), ct);
    }
    [HttpPost]
    [Authorize(Roles = "BACKOFFICE")]
    public async Task<ActionResult<MicrogridNode>> Create(NodeRequestDTO request, CancellationToken ct)
    {
        // Only Backoffice may create hubs and physical slot definitions.
        var node = await _nodes.CreateAsync(request, ct);
        return CreatedAtAction(nameof(Get), new { id = node.Id }, node);
    }
    [HttpPut("{id}")]
    [Authorize(Roles = "BACKOFFICE")]
    public Task<MicrogridNode> Update(string id, UpdateNodeRequestDTO request, CancellationToken ct)
    {
        // Schedules and capacity remain administration operations.
        return _nodes.UpdateAsync(id, request, ct);
    }
    [HttpPatch("{id}/status")]
    [Authorize(Roles = "BACKOFFICE")]
    public Task<MicrogridNode> Status(string id, NodeStatusRequestDTO request, CancellationToken ct)
    {
        // The service rejects deactivation when active reservations exist.
        return _nodes.SetStatusAsync(id, request, ct);
    }
    [HttpPatch("{id}/slots/{slotId}/availability")]
    [Authorize(Roles = "BACKOFFICE,GRID_OPERATOR")]
    public Task<MicrogridNode> Availability(string id, string slotId, SlotAvailabilityRequestDTO request, CancellationToken ct)
    {
        // Operators receive only the narrow ability to change physical-slot availability.
        return _nodes.SetSlotAvailabilityAsync(id, slotId, request, ct);
    }
}
