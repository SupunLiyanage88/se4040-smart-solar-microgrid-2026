using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SmartSolarMicrogrid.Api.DTO.AuthDTO;
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Interfaces;
using SmartSolarMicrogrid.Api.Services;

namespace SmartSolarMicrogrid.Api.Controllers;

[ApiController]
[Route("api")]
public class AuthController : ControllerBase
{
    private readonly IAuthInterface _authService;

    public AuthController(IAuthInterface authService) => _authService = authService;

    [HttpPost("register")]
    public async Task<ActionResult<UserResponseDTO>> Register(
        UserRequestDTO request,
        CancellationToken ct
    )
    {
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
        var userId = User.FindFirstValue(JwtRegisteredClaimNames.Sub);
        if (userId is null)
            return Unauthorized();

        var user = await _authService.GetCurrentUserAsync(userId, ct);
        return user is null ? NotFound() : Ok(user);
    }
}
