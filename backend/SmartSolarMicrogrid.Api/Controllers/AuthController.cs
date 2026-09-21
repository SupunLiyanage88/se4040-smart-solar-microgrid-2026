// Smart Solar Microgrid Trading System
// Public authentication and authenticated self-service account operations.
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SmartSolarMicrogrid.Api.DTO.AuthDTO;
using SmartSolarMicrogrid.Api.DTO.UnauthorizedDTO;
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Interfaces;
using SmartSolarMicrogrid.Api.Services;

namespace SmartSolarMicrogrid.Api.Controllers;

[ApiController]
[Route("api")]
public class AuthController : ControllerBase
{
    private readonly IAuthInterface _authService;
    private readonly IUserInterface _users;

    public AuthController(IAuthInterface authService, IUserInterface users)
    {
        // Resolve authentication and account persistence services.
        _authService = authService;
        _users = users;
    }

    [HttpPost("register")]
    public async Task<ActionResult<UserResponseDTO>> Register(
        UserRequestDTO request,
        CancellationToken ct
    )
    {
        // Return a conflict instead of creating a duplicate identity.
        var user = await _authService.RegisterAsync(request, ct);
        if (user is null)
            return Conflict(new { message = "Email or NIC is already registered." });

        return StatusCode(StatusCodes.Status201Created, user);
    }

    [HttpPost("login")]
    public async Task<ActionResult<LoginResponseDTO>> Login(
        LoginRequestDTO request,
        CancellationToken ct
    )
    {
        // Map credential and activation failures to clear client responses.
        var result = await _authService.LoginAsync(request, ct);
        return result.Status switch
        {
            LoginStatus.Success => Ok(result.Response),
            LoginStatus.NotActivated => StatusCode(
                StatusCodes.Status403Forbidden,
                new { message = "Account is not activated yet." }
            ),
            _ => Unauthorized(new { message = "Invalid email or password." }),
        };
    }

    [Authorize]
    [ProducesResponseType(typeof(UnAuthorizedResponseDTO), StatusCodes.Status401Unauthorized)]
    [HttpGet("user")]
    public async Task<ActionResult<UserResponseDTO>> GetCurrentUser(CancellationToken ct)
    {
        // The validated token identifies the current account by NIC.
        var userId = User.FindFirstValue(JwtRegisteredClaimNames.Sub);
        if (userId is null)
            return Unauthorized(new UnAuthorizedResponseDTO());

        var user = await _authService.GetCurrentUserAsync(userId, ct);
        return user is null ? NotFound() : Ok(user);
    }
    [Authorize]
    [HttpPatch("user")]
    public async Task<ActionResult<UserResponseDTO>> UpdateOwnProfile(ProfileRequestDTO request, CancellationToken ct)
    {
        // Use only the authenticated identity, never a client-supplied account identifier.
        var user = await _users.UpdateProfileAsync(User.FindFirstValue(JwtRegisteredClaimNames.Sub)!, request, ct);
        return user is null ? NotFound() : Ok(_users.ToResponse(user));
    }

    [Authorize(Roles = "PROSUMER")]
    [HttpPost("user/deactivation")]
    public async Task<IActionResult> RequestDeactivation(CancellationToken ct)
    {
        // Queue a request for Backoffice; this endpoint cannot reactivate an account.
        return await _users.RequestDeactivationAsync(User.FindFirstValue(JwtRegisteredClaimNames.Sub)!, ct)
            ? Ok(new { message = "Deactivation requested. Backoffice will review your request." })
            : NotFound(new { message = "Active prosumer account not found." });
    }
}
