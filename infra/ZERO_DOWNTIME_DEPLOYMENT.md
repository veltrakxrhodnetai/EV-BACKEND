# Zero-Downtime Deployment Guide (Blue-Green)

## Overview
- **Blue**: Current live backend
- **Green**: Staging backend (new code)
- **Nginx**: Routes all traffic to active backend
- Deployment: build on inactive → test → switch traffic → keep old for rollback

---

## Initial Setup (First Time Only)

### On VPS, switch to blue-green compose

1. Stop old stack
```bash
cd ~/EV-BACKEND/infra
sudo docker compose down
```

2. Start blue-green stack
```bash
sudo docker compose -f docker-compose-prod.yml up -d
```

3. Verify both backends are healthy
```bash
sudo docker compose -f docker-compose-prod.yml ps
curl http://localhost:8081/actuator/health
curl http://localhost:8082/actuator/health
```

4. Verify Nginx routes to blue (active)
```bash
curl http://localhost/api/admin/dashboard/financial
```

5. Keep old docker-compose.yml as backup
```bash
cp docker-compose.yml docker-compose.backup.yml
```

---

## Every Code Update (Zero-Downtime Procedure)

### Step 1: Code Update (Local)
```bash
git pull origin main
```

### Step 2: Build and Deploy to Green (Inactive Slot)

On VPS:
```bash
cd ~/EV-BACKEND
git pull origin main
cd infra

# Build green image only (blue stays live)
sudo docker compose -f docker-compose-prod.yml build backend-green

# Start green container with new code
sudo docker compose -f docker-compose-prod.yml up -d backend-green
```

### Step 3: Health Check Green

Wait 30 seconds for green to start, then verify:
```bash
curl http://localhost:8082/actuator/health
# Expected: {"status":"UP"}

# If UP, run a quick smoke test
curl http://localhost:8082/api/admin/dashboard/financial
# Expected: 400 (requires auth, but endpoint exists = good)
```

### Step 4: Switch Traffic from Blue to Green

Edit Nginx config to switch:
```bash
sudo sed -i 's/map \$backend_selector/# OLD map/; s/"blue"  "backend_blue";/# OLD/; 1a map $backend_selector {\n    "green"  "backend_green";\n    default  "backend_green";\n}/' /etc/nginx/nginx.conf
sudo docker exec evcsms-nginx nginx -s reload
```

Or manually:
```bash
# Edit /etc/nginx/nginx.conf
# Change: map $backend_selector { ... "blue" "backend_blue"; ... }
# To:     map $backend_selector { ... "green" "backend_green"; ... }

sudo nano /etc/nginx/nginx.conf
# Edit the map block, save, exit

sudo docker exec evcsms-nginx nginx -s reload
```

### Step 5: Verify New Traffic Goes to Green

```bash
curl http://localhost/api/admin/dashboard/financial
curl http://localhost:8082/actuator/health
# Both should work and route to green
```

### Step 6: Monitor for Errors

```bash
# Watch green logs
sudo docker compose -f docker-compose-prod.yml logs -f backend-green

# Check for any errors for 5-10 minutes
# If errors: proceed to ROLLBACK (see below)
# If healthy: keep green live, blue is now standby
```

### Step 7: Prepare Blue for Next Update

Once green is stable (no errors for 5+ min):
```bash
# Stop blue (frees resources, makes it next deployment target)
sudo docker compose -f docker-compose-prod.yml stop backend-blue

# Blue will remain stopped until next deploy
# Next deploy will build and start blue while green is live
```

---

## Rollback (If New Version Has Errors)

### If Green Fails Health Check
```bash
# Stop green
sudo docker compose -f docker-compose-prod.yml stop backend-green

# Nginx stays on blue (old version, still live)
# No downtime occurred for users
```

### If Green is Live But Has Runtime Errors

```bash
# Switch back to blue immediately
# Edit /etc/nginx/nginx.conf

sudo nano /etc/nginx/nginx.conf
# Change: map $backend_selector { ... "green" "backend_green"; ... }
# To:     map $backend_selector { ... "blue" "backend_blue"; ... }
# Save and exit

sudo docker exec evcsms-nginx nginx -s reload

# Start blue again
sudo docker compose -f docker-compose-prod.yml up -d backend-blue

# Verify
curl http://localhost/api/admin/dashboard/financial
# Traffic now on stable blue version
```

---

## Monitoring During Deployment

Always watch logs:
```bash
# Terminal 1: Green logs during deploy
sudo docker compose -f docker-compose-prod.yml logs -f backend-green

# Terminal 2: Nginx logs to see traffic
sudo docker exec evcsms-nginx tail -f /var/log/nginx/access.log

# Terminal 3: User-facing health endpoint
while true; do curl -s http://localhost/api/admin/dashboard/financial && echo "" || echo "FAILED"; sleep 5; done
```

---

## Nginx Config Structure

The map selector in nginx.conf controls routing:
```
map $backend_selector {
    "blue"  "backend_blue";    # Route to blue
    default "backend_blue";
}
```

To switch, change to:
```
map $backend_selector {
    "green" "backend_green";   # Route to green
    default "backend_green";
}
```

Then reload:
```bash
sudo docker exec evcsms-nginx nginx -s reload
```

---

## Summary

**Current Setup:**
- Blue (port 8081): Starting state = ACTIVE
- Green (port 8082): Starting state = INACTIVE
- Nginx (port 80): Routes to ACTIVE

**Deployment Flow:**
1. git pull (local)
2. git pull + docker compose build backend-green (VPS)
3. Health check green
4. Switch nginx to green
5. Monitor green
6. Stop blue (frees resources)
7. Next deploy: build and start blue while green is live

**Rollback (1 minute):**
- Edit nginx.conf map, reload

---

## Important Notes

1. Always health check before switching.
2. Keep old version running for fast rollback.
3. Monitor logs for 5-10 minutes after switch.
4. If error in green, switch back (users never know).
5. Database migrations must be backward-compatible (use Flyway properly).
