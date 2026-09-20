// Smart Solar Microgrid Trading System
// Registration, login and JWT issuing.

using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using SmartSolarMicrogrid.Api.Configuration;
using SmartSolarMicrogrid.Api.DTO.AuthDTO;
using SmartSolarMicrogrid.Api.DTO.UserDTO;
using SmartSolarMicrogrid.Api.Interfaces;
using SmartSolarMicrogrid.Api.Models;

namespace SmartSolarMicrogrid.Api.Services;

public enum LoginStatus
{
    Success,
    InvalidCredentials,
    NotActivated,
}

public record LoginResult(LoginStatus Status, LoginResponseDTO? Response = null);

public class AuthService : IAuthInterface
{
    private readonly IUserInterface _userService;
    private readonly JwtOptions _jwt;

    public AuthService(IUserInterface userService, IOptions<JwtOptions> jwt)
    {
        _userService = userService;
        _jwt = jwt.Value;
    }

    public async Task<UserResponseDTO?> RegisterAsync(
        UserRequestDTO request,
        CancellationToken ct = default
    )
    {
        if (await _userService.ExistsAsync(request.Email, request.NIC, ct))
            return null;

        var user = new User
        {
            UserName = request.UserName,
            Email = request.Email,
            NIC = request.NIC,
            PasswordHash = BCrypt.Net.BCrypt.HashPassword(request.Password),
            Role = UserRole.PROCUMER,
            Activation = false,
        };

        await _userService.CreateAsync(user, ct);
        return _userService.ToResponse(user);
    }

    public async Task<LoginResult> LoginAsync(
        LoginRequestDTO request,
        CancellationToken ct = default
    )
    {
        var user = await _userService.GetByEmailAsync(request.Email, ct);
        if (user is null || !BCrypt.Net.BCrypt.Verify(request.Password, user.PasswordHash))
            return new LoginResult(LoginStatus.InvalidCredentials);

        if (!user.Activation)
            return new LoginResult(LoginStatus.NotActivated);

        var expires = DateTime.UtcNow.AddMinutes(_jwt.ExpiryMinutes);
        var claims = new[]
        {
            new Claim(JwtRegisteredClaimNames.Sub, user.Id!),
            new Claim(JwtRegisteredClaimNames.Email, user.Email),
            new Claim(ClaimTypes.Role, user.Role.ToString()),
        };
        var creds = new SigningCredentials(
            new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_jwt.Key)),
            SecurityAlgorithms.HmacSha256
        );
        var token = new JwtSecurityToken(
            _jwt.Issuer,
            _jwt.Audience,
            claims,
            expires: expires,
            signingCredentials: creds
        );

        return new LoginResult(
            LoginStatus.Success,
            new LoginResponseDTO
            {
                Token = new JwtSecurityTokenHandler().WriteToken(token),
                ExpiresAtUtc = expires,
                User = _userService.ToResponse(user),
            }
        );
    }

    public async Task<UserResponseDTO?> GetCurrentUserAsync(
        string userId,
        CancellationToken ct = default
    )
    {
        var user = await _userService.GetByIdAsync(userId, ct);
        return user is null ? null : _userService.ToResponse(user);
    }
}
