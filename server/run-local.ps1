# Load environment variables from .env file and run the Spring Boot application
# Usage: .\run-local.ps1

Write-Host "Loading environment variables from .env file..." -ForegroundColor Green

# Check if .env file exists
if (-Not (Test-Path ".env")) {
    Write-Host "Error: .env file not found!" -ForegroundColor Red
    Write-Host "Please copy .env.example to .env and configure it." -ForegroundColor Yellow
    exit 1
}

# Create a hashtable to store environment variables
$envVars = @{}

# Read .env file and collect environment variables
Get-Content .env | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -match '^\s*$') {
        # Skip comments and empty lines
        return
    }
    
    if ($_ -match '^([^=]+)=(.*)$') {
        $name = $matches[1].Trim()
        $value = $matches[2].Trim()
        
        # Remove quotes if present
        $value = $value -replace '^["'']|["'']$', ''
        
        Write-Host "Loading $name = $value" -ForegroundColor Cyan
        $envVars[$name] = $value
    }
}

Write-Host "`nStarting Spring Boot application..." -ForegroundColor Green
Write-Host "Database: $($envVars['DB_HOST']):$($envVars['DB_PORT'])/$($envVars['DB_NAME'])" -ForegroundColor Cyan
Write-Host "Username: $($envVars['DB_USERNAME'])" -ForegroundColor Cyan
Write-Host "`n"

# Build Gradle arguments with environment variables as system properties
$gradleArgs = @(
    "bootRun",
    "--args=--spring.profiles.active=local"
)

# Set environment variables for the current process (Gradle will inherit them)
foreach ($key in $envVars.Keys) {
    [Environment]::SetEnvironmentVariable($key, $envVars[$key], "Process")
}

# Run Gradle bootRun with environment variables
& gradle @gradleArgs
