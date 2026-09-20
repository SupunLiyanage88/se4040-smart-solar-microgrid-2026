// Smart Solar Microgrid Trading System
// API composition, authentication enforcement and account maintenance entry points.
using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Tokens;
using MongoDB.Bson;
using Microsoft.OpenApi;
using MongoDB.Driver;
using SmartSolarMicrogrid.Api.Configuration;
using SmartSolarMicrogrid.Api.DTO.UnauthorizedDTO;
using SmartSolarMicrogrid.Api.Interfaces;
using SmartSolarMicrogrid.Api.Services;

// Load .env (if present) into environment variables before configuration is built.
DotNetEnv.Env.NoClobber().TraversePath().Load();

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.
builder.Services.AddControllers();
// Learn more about configuring OpenAPI at https://aka.ms/aspnet/openapi
builder.Services.AddOpenApi(options =>
{
    // Declare the JWT bearer scheme so Swagger UI shows an Authorize button.
    options.AddDocumentTransformer((document, _, _) =>
    {
        // Describe the bearer scheme for interactive API exploration.
        document.Components ??= new OpenApiComponents();
        document.Components.SecuritySchemes = new Dictionary<string, IOpenApiSecurityScheme>
        {
            ["Bearer"] = new OpenApiSecurityScheme
            {
                Type = SecuritySchemeType.Http,
                Scheme = "bearer",
                BearerFormat = "JWT"
            }
        };
        document.Security = [new OpenApiSecurityRequirement
        {
            [new OpenApiSecuritySchemeReference("Bearer", document)] = []
        }];
        return Task.CompletedTask;
    });
});

builder.Services.AddOptions<MongoDbOptions>()
    .Bind(builder.Configuration.GetSection(MongoDbOptions.SectionName))
    .Validate(options => !string.IsNullOrWhiteSpace(options.ConnectionString),
        "MongoDb:ConnectionString is required.")
    .Validate(options => !string.IsNullOrWhiteSpace(options.DatabaseName),
        "MongoDb:DatabaseName is required.")
    .ValidateOnStart();

builder.Services.AddSingleton<IMongoClient>(serviceProvider =>
{
    // Reuse one MongoClient so the driver can manage connection pooling efficiently.
    var options = serviceProvider.GetRequiredService<IOptions<MongoDbOptions>>().Value;
    return new MongoClient(options.ConnectionString);
});

builder.Services.AddSingleton(serviceProvider =>
{
    // Provide the configured application database to repositories and services.
    var client = serviceProvider.GetRequiredService<IMongoClient>();
    var options = serviceProvider.GetRequiredService<IOptions<MongoDbOptions>>().Value;
    return client.GetDatabase(options.DatabaseName);
});

builder.Services.AddScoped<IUserInterface, UserService>();
builder.Services.AddScoped<IAuthInterface, AuthService>();
builder.Services.AddScoped<IBackOfficeInterface, BackOfficeService>();

builder.Services.AddOptions<JwtOptions>()
    .Bind(builder.Configuration.GetSection(JwtOptions.SectionName))
    .Validate(options => options.Key.Length >= 32, "Jwt:Key must be at least 32 characters.")
    .Validate(options => options.ExpiryMinutes is >= 1 and <= 1440, "Jwt:ExpiryMinutes must be between 1 and 1440.")
    .ValidateOnStart();

var jwt = builder.Configuration.GetSection(JwtOptions.SectionName).Get<JwtOptions>() ?? new JwtOptions();
builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        // Validate token integrity before consulting the account record.
        options.MapInboundClaims = false;
        options.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidateAudience = true,
            ValidateLifetime = true,
            ClockSkew = TimeSpan.Zero,
            ValidateIssuerSigningKey = true,
            ValidIssuer = jwt.Issuer,
            ValidAudience = jwt.Audience,
            IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwt.Key)),
            RoleClaimType = ClaimTypes.Role,
            NameClaimType = JwtRegisteredClaimNames.Sub
        };
        options.Events = new JwtBearerEvents
        {
            OnTokenValidated = async context =>
            {
                // Recheck current state on every request; status changes revoke all old sessions.
                var id = context.Principal?.FindFirstValue(JwtRegisteredClaimNames.Sub);
                var users = context.HttpContext.RequestServices.GetRequiredService<IUserInterface>();
                var user = id is null ? null : await users.GetByIdAsync(id, context.HttpContext.RequestAborted);
                if (user is null || !user.Activation
                    || context.Principal?.FindFirstValue("session_version") != user.SessionVersion
                    || context.Principal?.FindFirstValue(ClaimTypes.Role) != user.Role.ToString())
                    context.Fail("Account is inactive or this session is no longer valid.");
            },
            // Replace the default empty 401 with a JSON body for missing/invalid/expired tokens.
            OnChallenge = async context =>
            {
                // Return a predictable JSON error for missing or expired credentials.
                context.HandleResponse();
                context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                await context.Response.WriteAsJsonAsync(new UnAuthorizedResponseDTO());
            },
            // Distinguish an authenticated role denial from an invalid login session.
            OnForbidden = async context =>
            {
                // Clients must not mistake missing permissions for an expired session.
                context.Response.StatusCode = StatusCodes.Status403Forbidden;
                await context.Response.WriteAsJsonAsync(new { message = "Your role does not permit this operation." });
            }
        };
    });
builder.Services.AddAuthorization();

var allowedOrigins = builder.Configuration.GetSection("Cors:AllowedOrigins").Get<string[]>() ?? [];
builder.Services.AddCors(options =>
{
    // Restrict browser access to the explicitly configured web client origins.
    options.AddDefaultPolicy(policy => policy
        .WithOrigins(allowedOrigins)
        .AllowAnyHeader()
        .AllowAnyMethod());
});

var app = builder.Build();

var database = app.Services.GetRequiredService<IMongoDatabase>();
if (args.Contains("--migrate-user-identities"))
{
    await AccountSetup.MigrateAsync(database);
    return;
}
if (args.Contains("--bootstrap-admin"))
{
    await AccountSetup.BootstrapAsync(database, builder.Configuration);
    return;
}
await AccountSetup.InitializeAsync(database);

app.Use(async (context, next) =>
{
    // Concurrent duplicate registration/profile updates receive a stable conflict response.
    try { await next(context); }
    catch (MongoWriteException ex) when (ex.WriteError.Category == ServerErrorCategory.DuplicateKey)
    {
        context.Response.StatusCode = StatusCodes.Status409Conflict;
        await context.Response.WriteAsJsonAsync(new { message = "Email or NIC is already registered." });
    }
    catch (MongoCommandException ex) when (ex.Code == 11000)
    {
        context.Response.StatusCode = StatusCodes.Status409Conflict;
        await context.Response.WriteAsJsonAsync(new { message = "Email or NIC is already registered." });
    }
    catch (Exception ex) when (ex is MongoException or TimeoutException)
    {
        app.Logger.LogError(ex, "Account database unavailable.");
        context.Response.StatusCode = StatusCodes.Status503ServiceUnavailable;
        await context.Response.WriteAsJsonAsync(new { message = "Account service is temporarily unavailable. Please retry." });
    }
});

// Configure the HTTP request pipeline.
if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
    app.UseSwaggerUI(options =>
    {
        options.SwaggerEndpoint("/openapi/v1.json", "Smart Solar Microgrid API v1");
        options.RoutePrefix = "swagger";
    });
}

if (!app.Environment.IsDevelopment())
{
    app.UseHttpsRedirection();
}

app.UseCors();

app.UseAuthentication();
app.UseAuthorization();

app.MapControllers();

app.MapGet("/api/health", async (IMongoDatabase database, CancellationToken cancellationToken) =>
{
    // Ping MongoDB so this endpoint verifies the real database connection.
    await database.RunCommandAsync<BsonDocument>(
        new BsonDocument("ping", 1),
        cancellationToken: cancellationToken);

    return Results.Ok(new
    {
        status = "Healthy",
        database = database.DatabaseNamespace.DatabaseName
    });
})
.WithName("GetSystemHealth")
.WithTags("System");

app.Run();
