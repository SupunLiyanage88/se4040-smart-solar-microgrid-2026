// Smart Solar Microgrid Trading System
// Backoffice user directory and activation queues.
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Interfaces;
namespace SmartSolarMicrogrid.Api.Controllers;
[ApiController]
[Route("api/users")]
[Authorize(Roles = "BACKOFFICE")]
public class UserController : ControllerBase
{
    private readonly IUserInterface _users;
    public UserController(IUserInterface users)
    {
        // Resolve the centralized user repository.
        _users = users;
    }
    [HttpGet]
    public async Task<ActionResult<IEnumerable<UserResponseDTO>>> GetAll(CancellationToken ct)
    {
        // Account flags let the web client show pending activation and deactivation queues.
        return Ok((await _users.GetAllAsync(ct)).Select(_users.ToResponse));
    }
    [HttpGet("{id}")]
    public async Task<ActionResult<UserResponseDTO>> GetById(string id, CancellationToken ct)
    {
        // User identifiers are NICs rather than database-generated ObjectIds.
        var user = await _users.GetByIdAsync(id, ct);
        return user is null ? NotFound(new { message = "User not found." }) : Ok(_users.ToResponse(user));
    }
}
