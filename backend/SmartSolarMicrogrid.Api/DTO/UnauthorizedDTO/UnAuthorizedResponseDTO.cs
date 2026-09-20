// Smart Solar Microgrid Trading System
// Body returned with a 401 when a request is not authenticated.

namespace SmartSolarMicrogrid.Api.DTO.UnauthorizedDTO;

public class UnAuthorizedResponseDTO
{
    public string Message { get; set; } = "Unauthorized";
}
