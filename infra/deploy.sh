#!/bin/bash

# Zero-Downtime Deployment Script
# Usage: ./deploy.sh [blue|green|rollback|status]

set -e

COMPOSE_FILE="docker-compose-prod.yml"
ACTIVE_BACKEND="${1:-green}"
INACTIVE_BACKEND="blue"

if [ "$ACTIVE_BACKEND" == "blue" ]; then
    INACTIVE_BACKEND="green"
fi

GREEN_PORT=8082
BLUE_PORT=8081
NGINX_CONTAINER="evcsms-nginx"

echo "=== EV Charging System Deployment ==="
echo "Action: $1"
echo "Active: $ACTIVE_BACKEND, Inactive: $INACTIVE_BACKEND"
echo ""

case "$1" in
    "deploy")
        echo "Step 1: Building $INACTIVE_BACKEND..."
        sudo docker compose -f "$COMPOSE_FILE" build backend-$INACTIVE_BACKEND

        echo "Step 2: Starting $INACTIVE_BACKEND..."
        sudo docker compose -f "$COMPOSE_FILE" up -d backend-$INACTIVE_BACKEND

        echo "Step 3: Waiting for $INACTIVE_BACKEND health check..."
        sleep 35

        # Determine inactive backend port
        if [ "$INACTIVE_BACKEND" == "green" ]; then
            TEST_PORT=$GREEN_PORT
        else
            TEST_PORT=$BLUE_PORT
        fi

        echo "Step 4: Testing $INACTIVE_BACKEND health..."
        HEALTH=$(curl -s http://localhost:$TEST_PORT/actuator/health | grep -o '"status":"UP"' || echo "FAILED")
        if [ "$HEALTH" == "" ]; then
            echo "ERROR: $INACTIVE_BACKEND health check failed!"
            exit 1
        fi
        echo "✓ $INACTIVE_BACKEND is healthy"

        echo ""
        echo "Step 5: Switching Nginx to route to $INACTIVE_BACKEND..."
        
        # Update nginx config
        if [ "$INACTIVE_BACKEND" == "green" ]; then
            sed -i 's/map \$backend_selector.*/map \$backend_selector {\n    "green"  "backend_green";\n    default  "backend_green";/' /etc/nginx/nginx.conf
        else
            sed -i 's/map \$backend_selector.*/map \$backend_selector {\n    "blue"  "backend_blue";\n    default  "backend_blue";/' /etc/nginx/nginx.conf
        fi

        sudo docker exec $NGINX_CONTAINER nginx -s reload
        sleep 2

        echo "✓ Traffic switched to $INACTIVE_BACKEND"

        echo "Step 6: Monitoring for errors (30 seconds)..."
        for i in {1..30}; do
            STATUS=$(curl -s http://localhost/api/admin/dashboard/financial -w '%{http_code}' -o /dev/null)
            echo "  Health check $i/30: HTTP $STATUS"
            sleep 1
        done

        echo "Step 7: Stopping $ACTIVE_BACKEND to free resources..."
        sudo docker compose -f "$COMPOSE_FILE" stop backend-$ACTIVE_BACKEND

        echo ""
        echo "✓✓✓ Deployment complete! New version is live."
        echo "Active backend: $INACTIVE_BACKEND"
        echo "Standby backend: $ACTIVE_BACKEND (stopped, ready for next deploy)"
        ;;

    "rollback")
        echo "Rolling back to $ACTIVE_BACKEND..."
        
        # Update nginx config back
        if [ "$ACTIVE_BACKEND" == "blue" ]; then
            sed -i 's/map \$backend_selector.*/map \$backend_selector {\n    "blue"  "backend_blue";\n    default  "backend_blue";/' /etc/nginx/nginx.conf
        else
            sed -i 's/map \$backend_selector.*/map \$backend_selector {\n    "green"  "backend_green";\n    default  "backend_green";/' /etc/nginx/nginx.conf
        fi

        sudo docker exec $NGINX_CONTAINER nginx -s reload
        
        # Start the backend if stopped
        sudo docker compose -f "$COMPOSE_FILE" up -d backend-$ACTIVE_BACKEND

        echo "✓ Rolled back to $ACTIVE_BACKEND"
        ;;

    "status")
        echo "Backend Status:"
        sudo docker compose -f "$COMPOSE_FILE" ps
        echo ""
        echo "Blue health (port 8081):"
        curl -s http://localhost:8081/actuator/health | grep -o '"status":"[^"]*"'
        echo ""
        echo "Green health (port 8082):"
        curl -s http://localhost:8082/actuator/health | grep -o '"status":"[^"]*"'
        echo ""
        echo "Public health (port 80):"
        curl -s http://localhost/api/admin/dashboard/financial -w "HTTP %{http_code}\n" -o /dev/null
        ;;

    "logs")
        BACKEND="${2:-green}"
        echo "Showing logs for backend-$BACKEND..."
        sudo docker compose -f "$COMPOSE_FILE" logs -f backend-$BACKEND
        ;;

    *)
        echo "Usage: ./deploy.sh {deploy|rollback|status|logs [blue|green]}"
        echo ""
        echo "Commands:"
        echo "  deploy          - Deploy new code to inactive backend, switch traffic"
        echo "  rollback        - Switch traffic back to previous version"
        echo "  status          - Show deployment status and health"
        echo "  logs [backend]  - Show logs from blue or green backend"
        exit 1
        ;;
esac
