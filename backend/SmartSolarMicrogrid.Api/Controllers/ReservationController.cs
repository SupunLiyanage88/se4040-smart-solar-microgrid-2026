// Smart Solar Microgrid Trading System
// Reservation endpoints. Business rules stay in the reservation service.
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SmartSolarMicrogrid.Api.DTO.ReservationDTO;
using SmartSolarMicrogrid.Api.Services;
namespace SmartSolarMicrogrid.Api.Controllers;
[ApiController]
[Route("api/reservations")]
[Authorize]
public sealed class ReservationController : ControllerBase
{
    private readonly ReservationService _reservations;
    public ReservationController(ReservationService reservations)
    {
        // Delegate booking decisions to the central service.
        _reservations = reservations;
    }
    [HttpPost]
    [Authorize(Roles = "PROSUMER,BACKOFFICE,GRID_OPERATOR")]
    public async Task<ActionResult<ReservationResponseDTO>> Create(CreateReservationRequestDTO request, CancellationToken ct)
    {
        // Prosumers book for themselves. Staff must name the prosumer.
        var created = await _reservations.CreateAsync(request, CallerNic(), Staff(), ct);
        return CreatedAtAction(nameof(Get), new { id = created.Id }, created);
    }
    [HttpGet]
    public Task<List<ReservationResponseDTO>> List([FromQuery] string? view, [FromQuery] string? search, CancellationToken ct)
    {
        // View selects current, pending or history bookings.
        return _reservations.ListAsync(CallerNic(), Staff(), view, search, ct);
    }
    [HttpGet("summary")]
    public Task<ReservationSummaryDTO> Summary(CancellationToken ct)
    {
        // Pending count and approved future count for the dashboard.
        return _reservations.SummaryAsync(CallerNic(), Staff(), ct);
    }
    [HttpGet("{id}")]
    public Task<ReservationResponseDTO> Get(string id, CancellationToken ct)
    {
        // Owners receive an approved QR token. Staff do not.
        return _reservations.GetAsync(id, CallerNic(), Staff(), ct);
    }
    [HttpPut("{id}")]
    [Authorize(Roles = "PROSUMER,BACKOFFICE,GRID_OPERATOR")]
    public Task<ReservationResponseDTO> Update(string id, UpdateReservationRequestDTO request, CancellationToken ct)
    {
        // A successful change returns the booking to pending and clears its QR token.
        return _reservations.UpdateAsync(id, request, CallerNic(), Staff(), ct);
    }
    [HttpPost("{id}/cancel")]
    [Authorize(Roles = "PROSUMER,BACKOFFICE,GRID_OPERATOR")]
    public Task<ReservationResponseDTO> Cancel(string id, CancellationToken ct)
    {
        // Cancellation uses the same 12-hour notice rule as an update.
        return _reservations.CancelAsync(id, CallerNic(), Staff(), ct);
    }
    [HttpPost("{id}/decision")]
    [Authorize(Roles = "BACKOFFICE,GRID_OPERATOR")]
    public Task<ReservationResponseDTO> Decide(string id, ReservationDecisionRequestDTO request, CancellationToken ct)
    {
        // Backoffice and grid operators approve or reject a pending booking.
        return _reservations.DecideAsync(id, request.Decision, ct);
    }
    [HttpPost("complete")]
    [Authorize(Roles = "GRID_OPERATOR")]
    public Task<ReservationResponseDTO> Complete(CompleteReservationRequestDTO request, CancellationToken ct)
    {
        // The operator submits the scanned token. The API accepts it only once.
        return _reservations.CompleteAsync(request.QrToken, ct);
    }
    private string CallerNic()
    {
        // The JWT subject is the account NIC.
        return User.FindFirstValue(JwtRegisteredClaimNames.Sub)
            ?? throw new ReservationRuleException(401, "Sign in before managing reservations.");
    }
    private bool Staff()
    {
        // Backoffice and grid operators share monitoring access. Prosumers do not.
        return User.IsInRole("BACKOFFICE") || User.IsInRole("GRID_OPERATOR");
    }
}
