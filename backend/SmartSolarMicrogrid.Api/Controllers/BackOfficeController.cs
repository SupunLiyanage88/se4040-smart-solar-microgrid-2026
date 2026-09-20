using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using SmartSolarMicrogrid.Api.DTO.UnauthorizedDTO;
using SmartSolarMicrogrid.Api.Interfaces;

[ApiController]
[Route("api/back-office")]
[Authorize(Roles = "BACKOFFICE")]
[ProducesResponseType(typeof(UnAuthorizedResponseDTO), StatusCodes.Status401Unauthorized)]
public class BackOfficeController : ControllerBase
{
    private readonly IBackOfficeInterface _backOfficeService;

    public BackOfficeController(IBackOfficeInterface backOfficeService) =>
        _backOfficeService = backOfficeService;

    // PATCH api/back-office/{id}/status?active=true|false — activates or deactivates the user.
    [HttpPatch("{id}/status")]
    public async Task<IActionResult> SetActivation(
        string id,
        [FromQuery] bool active,
        CancellationToken ct
    ) =>
        await _backOfficeService.ActivateDeactivateUserByOfficerAsync(id, active, ct)
            ? NoContent()
            : NotFound();
}

