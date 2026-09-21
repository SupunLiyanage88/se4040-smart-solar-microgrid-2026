// Smart Solar Microgrid Trading System
// Only Backoffice can create accounts, edit other profiles and change activation.
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Interfaces;
namespace SmartSolarMicrogrid.Api.Controllers;
[ApiController]
[Route("api/back-office")]
[Authorize(Roles = "BACKOFFICE")]
public class BackOfficeController : ControllerBase
{
    private readonly IBackOfficeInterface _service;
    public BackOfficeController(IBackOfficeInterface service)
    {
        // Keep account business operations in the central service.
        _service = service;
    }
    [HttpPost("users")]
    public async Task<ActionResult<UserResponseDTO>> Create(StaffUserRequestDTO request, CancellationToken ct)
    {
        // The public registration endpoint cannot choose staff roles.
        var user = await _service.CreateUserByOfficerAsync(request, ct);
        return user is null ? Conflict(new { message = "Email or NIC is already registered." })
            : StatusCode(StatusCodes.Status201Created, user);
    }
    [HttpPatch("{id}")]
    public async Task<ActionResult<UserResponseDTO>> Update(string id, ProfileRequestDTO request, CancellationToken ct)
    {
        // Editable fields deliberately exclude role and account status.
        var user = await _service.UpdateUserByOfficerAsync(id.Trim().ToUpperInvariant(), request, ct);
        return user is null ? NotFound(new { message = "User not found." }) : Ok(user);
    }
    [HttpPatch("{id}/status")]
    public async Task<IActionResult> SetActivation(string id, [FromQuery] bool? active, CancellationToken ct)
    {
        // Require an explicit status and prevent an officer from disabling their own access.
        if (active is null) return BadRequest(new { message = "Specify active=true or active=false." });
        id = id.Trim().ToUpperInvariant();
        if (!active.Value && id == User.FindFirstValue(JwtRegisteredClaimNames.Sub))
            return Conflict(new { message = "You cannot deactivate your own Backoffice account." });
        return await _service.ActivateDeactivateUserByOfficerAsync(id, active.Value, ct)
            ? Ok(new { message = active.Value ? "User activated successfully." : "User deactivated successfully." })
            : NotFound(new { message = "User not found." });
    }
}
