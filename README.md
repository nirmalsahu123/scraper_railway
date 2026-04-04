# Lugality Trademark Scraper

Spring Boot scraper for IP India Trademark portal.

## Railway Deployment

### Step 1 — Push to GitHub
```bash
git init
git add .
git commit -m "initial commit"
git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git
git push -u origin main
```

### Step 2 — Create Railway Project
1. Go to https://railway.app
2. New Project → Deploy from GitHub repo
3. Select your repo → Railway auto-detects Dockerfile

### Step 3 — Set Environment Variables in Railway
Go to your service → **Variables** tab → Add these:

| Variable | Value |
|---|---|
| `EMAIL_ADDRESS` | it80@ytpr.in |
| `EMAIL_APP_PASSWORD` | SSYYTTure@1212 |
| `EMAIL_ADDRESS_1` | it81@ytpr.in |
| `EMAIL_APP_PASSWORD_1` | SSYYTTure@1212 |
| `EMAIL_ADDRESS_2` | it82@ytpr.in |
| `EMAIL_APP_PASSWORD_2` | SSYYTTure@1212 |
| ... | (up to EMAIL_ADDRESS_10) |
| `IMAP_SERVER` | imap.hostinger.com |
| `NUM_WORKERS` | 4 |
| `HEADLESS` | true |
| `BROWSER_TIMEOUT` | 60000 |
| `APP_TIMEOUT_SECONDS` | 250 |

### Step 4 — Deploy
Railway will build and deploy automatically.
Access the scraper at: `https://your-app.railway.app`

## API Usage

### Start Scraping
```bash
curl -X POST https://your-app.railway.app/api/scrape/batch \
  -H "Content-Type: application/json" \
  -d @applications.json
```

### Check Status
```bash
curl https://your-app.railway.app/actuator/health
```
