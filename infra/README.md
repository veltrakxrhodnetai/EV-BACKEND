# EV CSMS - Docker Setup

## Prerequisites

- Docker Desktop installed and running
- Docker Compose V2

## Quick Start

### 1. Development Mode (Backend in Terminal - RECOMMENDED)

```bash
# Start only the database
cd infra
docker-compose up -d postgres

# In a new terminal, run the backend (so you can see logs)
cd scripts
run-backend-dev.bat
# OR for PowerShell: .\run-backend-dev.ps1

# In another terminal, run the frontend
cd frontend
npm run dev
```

This lets you see backend logs in real-time for debugging.

Access:
- **PostgreSQL**: localhost:5432
- **Backend API**: http://localhost:8080
- **Frontend**: http://localhost:5173

Database credentials:
- Database: `evcsms`
- Username: `evuser`
- Password: `evpass`

### 2. Production Mode (All in Docker)

First, uncomment the backend service in `docker-compose.yml`, then:

```bash
cd infra
docker-compose up -d
```

Services:
- **PostgreSQL**: http://localhost:5432
- **Backend API**: http://localhost:8080
- **Adminer (DB UI)**: http://localhost:8081 (use `--profile tools`)

### 3. Start with Database Admin UI

```bash
cd infra
docker-compose --profile tools up -d
```

Access Adminer at http://localhost:8081:
- System: PostgreSQL
- Server: postgres
- Username: evuser
- Password: evpass
- Database: evcsms

## Database Migrations

Flyway migrations run automatically when the backend starts. Migration files are in:
```
backend/src/main/resources/db/migration/
```

Current migrations:
- V1: Initial core schema
- V2: Align schema with JPA
- V3: Chargers live table
- V4: Station, Charger, Connector, Tariff tables
- V5: OCPP transaction and billing columns
- V6: Session payment columns
- **V7: Customer authentication table** ✨ NEW

## Development Workflow

### Option A: Docker Database + Local Backend/Frontend

1. Start only PostgreSQL:
   ```bash
   cd infra
   docker-compose up postgres -d
   ```

2. Run backend locally:
   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```

3. Run frontend locally:
   ```bash
   cd frontend
   npm run dev
   ```

### Option B: Full Docker Stack

```bash
cd infra
docker-compose up -d
```

## Useful Commands

### View logs
```bash
docker-compose logs -f backend
docker-compose logs -f postgres
```

### Restart services
```bash
docker-compose restart backend
```

### Stop all services
```bash
docker-compose down
```

### Stop and remove volumes (clean database)
```bash
docker-compose down -v
```

### Rebuild backend after code changes
```bash
docker-compose up -d --build backend
```

## Database Connection Strings

### Local Development
```
jdbc:postgresql://localhost:5432/evcsms
Username: evuser
Password: evpass
```

### From Docker Container
```
jdbc:postgresql://postgres:5432/evcsms
Username: evuser
Password: evpass
```

## Troubleshooting

### Backend won't start
1. Check if PostgreSQL is ready:
   ```bash
   docker-compose logs postgres
   ```

2. Check backend logs:
   ```bash
   docker-compose logs backend
   ```

3. Verify database connection:
   ```bash
   docker exec -it evcsms-postgres psql -U evuser -d evcsms
   ```

### Database connection refused
- Ensure PostgreSQL is running: `docker-compose ps`
- Wait for PostgreSQL health check to pass
- Check port 5432 is not used by another service

### Migrations failed
1. Check migration files are valid SQL
2. Reset database:
   ```bash
   docker-compose down -v
   docker-compose up -d
   ```

## Health Checks

- Backend health: http://localhost:8080/actuator/health
- Database: `docker exec evcsms-postgres pg_isready -U evuser`

## Environment Variables

Backend supports these environment variables:

```yaml
SPRING_DATASOURCE_URL: Database URL
SPRING_DATASOURCE_USERNAME: Database username
SPRING_DATASOURCE_PASSWORD: Database password
SPRING_JPA_SHOW_SQL: Show SQL queries (true/false)
LOG_LEVEL: Application log level (INFO/DEBUG/WARN)
SQL_LOG_LEVEL: SQL log level (DEBUG/WARN)
CORS_ALLOWED_ORIGINS: Comma-separated CORS origins
```

## API Testing

Once running, test the customer auth API:

### Check Phone
```bash
curl -X POST http://localhost:8080/api/customer/auth/check-phone \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber": "9876543210"}'
```

### Send OTP
```bash
curl -X POST http://localhost:8080/api/customer/auth/send-otp \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber": "9876543210", "purpose": "LOGIN"}'
```

## Production Notes

⚠️ Before deploying to production:
1. Change database passwords in docker-compose.yml
2. Update JWT_SECRET in CustomerAuthService.java
3. Use proper certificate for HTTPS
4. Enable proper CORS origins
5. Set up proper logging and monitoring
6. Use external database (not Docker volume)
