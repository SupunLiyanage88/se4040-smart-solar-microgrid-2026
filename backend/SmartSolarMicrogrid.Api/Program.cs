using Microsoft.Extensions.Options;
using MongoDB.Bson;
using MongoDB.Driver;
using SmartSolarMicrogrid.Api.Configuration;

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.
builder.Services.AddControllers();
// Learn more about configuring OpenAPI at https://aka.ms/aspnet/openapi
builder.Services.AddOpenApi();

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

// Configure the HTTP request pipeline.
if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
}

if (!app.Environment.IsDevelopment())
{
    app.UseHttpsRedirection();
}

app.UseCors();

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
