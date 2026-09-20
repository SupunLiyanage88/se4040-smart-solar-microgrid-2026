using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Services;

namespace SmartSolarMicrogrid.Api.Controllers;

// User administration, restricted to back-office staff.
[ApiController]
[Route("api/users")]
[Authorize(Roles = "BACKOFFICE")]
public class UserController : ControllerBase
{
    private readonly IUserService _userService;

    public UserController(IUserService userService) => _userService = userService;

    [HttpGet]
    public async Task<ActionResult<IEnumerable<UserResponseDTO>>> GetAll(CancellationToken ct)
    {
        var users = await _userService.GetAllAsync(ct);
        return Ok(users.Select(_userService.ToResponse));
    }

    [HttpGet("{id}")]
    public async Task<ActionResult<UserResponseDTO>> GetById(string id, CancellationToken ct)
    {
        var user = await _userService.GetByIdAsync(id, ct);
        return user is null ? NotFound() : Ok(_userService.ToResponse(user));
    }

    [HttpPatch("{id}/activation")]
    public async Task<IActionResult> SetActivation(
        string id,
        [FromQuery] bool active,
        CancellationToken ct
    ) => await _userService.SetActivationAsync(id, active, ct) ? NoContent() : NotFound();
}
