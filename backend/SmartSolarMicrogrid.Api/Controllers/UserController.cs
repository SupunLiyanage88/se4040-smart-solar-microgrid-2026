using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SmartSolarMicrogrid.Api.DTO.UnauthorizedDTO;
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Interfaces;
using SmartSolarMicrogrid.Api.Services;

namespace SmartSolarMicrogrid.Api.Controllers;

// User administration, restricted to back-office staff.
[ApiController]
[Route("api/users")]
[Authorize(Roles = "BACKOFFICE")]
[ProducesResponseType(typeof(UnAuthorizedResponseDTO), StatusCodes.Status401Unauthorized)]
public class UserController : ControllerBase
{
    private readonly IUserInterface _userService;

    public UserController(IUserInterface userService) => _userService = userService;

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
}
