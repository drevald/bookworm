# Running the Application Locally

This guide explains how to run the Bookworm server application on your host machine (outside Docker) while using the PostgreSQL database in Docker.

## Prerequisites

- Java 17 or higher
- Gradle
- Docker and Docker Compose

## Setup

### 1. Start the Database

First, start only the PostgreSQL database using Docker Compose:

```powershell
docker compose up -d db
```

This will start PostgreSQL on `localhost:5436`.

### 2. Configure Environment Variables

The application uses environment variables for configuration. These are stored in a `.env` file:

1. Copy the example file:
   ```powershell
   Copy-Item .env.example .env
   ```

2. Edit `.env` to point to your Docker host (e.g., `dobby`):
   ```
   DB_HOST=dobby
   DB_PORT=5437
   DB_NAME=bookworm
   DB_USERNAME=bookworm
   DB_PASSWORD=bookworm
   
   SERVER_PORT=8080
   GRPC_PORT=9090
   ```

### 3. Run the Application

Use the provided batch file to load environment variables and start the app:

```powershell
.\run-local.bat
```

Alternatively, you can manually set environment variables and run Gradle:

```powershell
# Set environment variables
$env:DB_HOST="dobby"
$env:DB_PORT="5437"
$env:DB_NAME="bookworm"
$env:DB_USERNAME="bookworm"
$env:DB_PASSWORD="bookworm"

# Run the application
gradle bootRun
```

## Accessing the Application

Once started, the application will be available at:

- **Web UI**: http://localhost:8080
- **gRPC**: localhost:9090

## Stopping the Application

- Press `Ctrl+C` to stop the Spring Boot application
- Stop the database: `docker compose stop db`
- Stop and remove containers: `docker compose down`

## Running Everything in Docker

If you prefer to run both the app and database in Docker:

```powershell
docker compose up -d
```

This uses the environment variables defined in `docker-compose.yml` instead of `.env`.

## Troubleshooting

### Connection Refused Error

If you see `Connection to localhost:5436 refused`, ensure:
1. The database container is running: `docker compose ps`
2. The port is correct in your `.env` file
3. No other service is using port 5436

### Environment Variables Not Loading

Make sure:
1. The `.env` file exists in the project root
2. You're using the `run-local.ps1` script or manually exporting variables
3. Variable names match exactly (case-sensitive)
