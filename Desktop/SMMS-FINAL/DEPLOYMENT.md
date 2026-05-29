# SMMS – Production Deployment Guide

## Prerequisites
- Docker 24+ & Docker Compose v2
- Linux server (Ubuntu 22+ or Rocky 9+)
- 8GB RAM, 100GB SSD
- Domain name with SSL certificate
- Firewall: allow 80, 443 only

---

## 1. Server Setup

```bash
# Update system
sudo apt update && sudo apt upgrade -y

# Install Docker
sudo apt install -y docker.io docker-compose-v2
sudo usermod -aG docker $USER

# Create app directory
sudo mkdir -p /opt/smms
sudo chown $USER:$USER /opt/smms
cd /opt/smms
```

---

## 2. Deploy Code

```bash
# Clone repo
git clone <REPO_URL> .
cd /opt/smms

# Copy environment
cp .env.example .env
nano .env  # Edit with real values

# Generate JWT secret
echo "JWT_SECRET=$(openssl rand -base64 43)" >> .env
```

---

## 3. SSL Certificates

**Production (Let's Encrypt):**
```bash
sudo apt install -y certbot python3-certbot-nginx

sudo certbot certonly --standalone \
  -d smms.yourdomain.com \
  -d api.yourdomain.com

sudo cp /etc/letsencrypt/live/smms.yourdomain.com/fullchain.pem nginx/certs/
sudo cp /etc/letsencrypt/live/smms.yourdomain.com/privkey.pem nginx/certs/
sudo chown $USER:$USER nginx/certs/*
```

**Auto-renewal (cron):**
```bash
# Add to crontab -e:
0 0 * * * certbot renew --quiet && docker compose -f docker-compose.yml -f docker-compose.prod.yml restart frontend
```

---

## 4. Start Services

```bash
# Build images
make build

# Start with production config
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d

# Wait for DB
sleep 10

# Run migrations
docker compose exec postgres psql -U ${DB_USER} -d ${DB_NAME} \
  < scripts/init.sql
docker compose exec postgres psql -U ${DB_USER} -d ${DB_NAME} \
  < scripts/workflow_functions.sql

# Verify
docker compose ps
docker compose logs backend | tail -20
```

---

## 5. Monitoring & Logs

```bash
# Real-time logs
docker compose logs -f

# Backend only
docker compose logs -f backend

# View disk usage
docker system df

# Cleanup old images/volumes
docker system prune -a
```

---

## 6. Backup Strategy

**Daily automated backup:**
```bash
# Create backup script
cat > backup.sh << 'EOF'
#!/bin/bash
BACKUP_DIR=/backups/smms
mkdir -p $BACKUP_DIR

# DB dump
docker compose exec -T postgres pg_dump -U ${DB_USER} ${DB_NAME} \
  | gzip > $BACKUP_DIR/db_$(date +%Y%m%d_%H%M%S).sql.gz

# Keep last 7 days
find $BACKUP_DIR -name "db_*.sql.gz" -mtime +7 -delete
EOF

chmod +x backup.sh

# Schedule daily at 2 AM
# Add to crontab -e:
0 2 * * * /opt/smms/backup.sh
```

---

## 7. Scaling

**Increase resources:**
```yaml
# Edit docker-compose.prod.yml
backend:
  deploy:
    resources:
      limits:
        cpus: "4"
        memory: 2G
```

**Add load balancer (optional):**
- Use HAProxy or Nginx in front
- Point to multiple backend containers

---

## 8. Troubleshooting

**Backend won't start:**
```bash
docker compose logs backend
# Check: JWT_SECRET, DB credentials, ports
```

**DB connection timeout:**
```bash
docker compose exec postgres pg_isready -U ${DB_USER}
# Ensure postgres service is running
```

**High disk usage:**
```bash
docker system df
docker image prune -a
docker container prune
```

**WebSocket connection fails:**
- Ensure Nginx proxy_http_version is 1.1
- Check firewall allows long-lived connections

---

## 9. Security Checklist

- [ ] Change default passwords
- [ ] Use strong JWT secret (64 chars)
- [ ] Enable HTTPS only
- [ ] Set firewall rules (80/443 only)
- [ ] Enable Docker content trust
- [ ] Regular backups tested
- [ ] Monitor logs for errors
- [ ] Update containers monthly

---

## 10. Health Check

```bash
# API health
curl -s https://smms.yourdomain.com/actuator/health | jq

# Frontend
curl -s https://smms.yourdomain.com/health

# DB
docker compose exec postgres pg_isready

# Redis
docker compose exec redis redis-cli ping
```

---

## Rollback

```bash
# View image history
docker image history smms_backend

# Revert to previous version
docker compose down
git revert <commit-hash>
docker compose build
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```
