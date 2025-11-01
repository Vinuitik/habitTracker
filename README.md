# HabitTracker - Comprehensive Project Documentation

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Services](#services)
4. [Setup & Deployment](#setup--deployment)
5. [Database Schema](#database-schema)
6. [API Endpoints](#api-endpoints)
7. [Frontend](#frontend)
8. [Development](#development)
9. [Backup System](#backup-system)
10. [Configuration](#configuration)

---

## Overview

HabitTracker is a comprehensive habit tracking application built with a microservices architecture. It helps users track daily habits, maintain streaks, and monitor progress over time.

### Key Features
- **Daily Habit Tracking**: Track completion of habits on a daily basis
- **Streak Calculation**: Automatic calculation and maintenance of habit streaks
- **Flexible Scheduling**: Support for different habit frequencies (daily, every N days)
- **Web Interface**: Clean, responsive web UI for habit management
- **Automatic Updates**: Background service for maintaining data consistency
- **Automated Backups**: Regular MongoDB backups to Google Drive

### Tech Stack
- **Backend**: Spring Boot (Java 21)
- **Database**: MongoDB
- **Frontend**: Thymeleaf templates with vanilla JavaScript
- **Containerization**: Docker & Docker Compose
- **Backup**: Python with Google Drive API

---

## Architecture

The application consists of three main services:

```
┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
│   HabitTracker  │    │     Updater     │    │     Backup      │
│   (Port 8089)   │    │   (Port 8087)   │    │    Service      │
│                 │    │                 │    │                 │
│ • Web Interface │    │ • Daily Updates │    │ • MongoDB Dump  │
│ • CRUD Ops      │    │ • Streak Calc   │    │ • Google Drive  │
├─────────────────┤    ├─────────────────┤    │ • Scheduled     │
│                 │    │                 │    │                 │
└─────────┬───────┘    └─────────┬───────┘    └─────────────────┘
          │                      │                      
          └──────────────────────┼──────────────────────┘
                                 │
                    ┌─────────────────┐
                    │    MongoDB      │
                    │   (Port 27017)  │
                    │                 │
                    │ • habits        │
                    │ • habitStructure│
                    │ • last_run_date │
                    └─────────────────┘
```

---

## Services

### 1. HabitTracker Service (Main Application)

**Location**: `habitTracker/`  
**Port**: 8089  
**Purpose**: Main web application providing user interface and CRUD operations

#### Key Components:

**Controllers**:
- `HabitController`: Handles web page routing and display
- `HabitWriteController`: Handles data modification operations (POST/PUT/DELETE)

**Services**:
- `HabitService`: Core habit business logic
- `StructureService`: Daily habit completion tracking

**Models**:
- `Habit`: Main habit entity with fields like name, frequency, streak, dates
- `HabitStructure`: Daily habit completion records

#### Key Features:
- Web-based habit management interface
- Real-time habit completion tracking
- Habit creation, editing, and deletion
- Streak display and management
- Responsive design for mobile and desktop

### 2. Updater Service (Refactored)

**Location**: `updater/`  
**Port**: 8087  
**Purpose**: Background service for maintaining data consistency

#### Refactored Architecture:

The updater has been broken down into focused service classes:

**Main Orchestrator**:
- `UpdateScheduler`: Coordinates daily updates (runs at 12:05 AM)

**Core Services**:
- `LastRunDateService`: Manages execution tracking to prevent duplicate runs
- `HabitUpdateService`: Updates habit scheduling and current dates
- `HabitDateCalculator`: Calculates next occurrence dates based on frequency
- `HabitStructureManager`: Creates daily habit completion records
- `StreakCalculationService`: Handles all streak calculations and updates

#### Update Process:
1. **Startup Check**: Runs on application startup to catch up on missed updates
2. **Daily Execution**: Scheduled daily at 12:05 AM
3. **Duplicate Prevention**: Checks if already ran today
4. **Habit Date Updates**: Updates current dates for overdue habits
5. **Structure Creation**: Creates daily completion records for due habits
6. **Streak Calculation**: Updates streaks based on completion history

### 3. Backup Service

**Location**: `backup/`  
**Purpose**: Automated MongoDB backup to Google Drive

#### Features:
- **Scheduled Backups**: Daily backups at configurable times
- **Google Drive Integration**: Secure cloud storage
- **Compression**: Efficient storage using compressed JSON dumps
- **Error Handling**: Robust error handling and logging

---

## Setup & Deployment

### Prerequisites
- Docker Desktop
- PowerShell (Windows)
- Git

### Quick Start

1. **Clone the repository**:
   ```bash
   git clone <repository-url>
   cd HabitTracker
   ```

2. **Run the automated deployment script**:
   ```powershell
   .\docker-compose-runner-v1.ps1
   ```

   This script:
   - Automatically detects your timezone
   - Maps Windows timezone to Linux format
   - Builds all Docker containers
   - Starts the entire application stack

3. **Access the application**:
    - With domain (recommended): https://habitrackerdima.me (auto HTTPS by Caddy)
    - Local/no-domain (dev): http://localhost (Caddy proxies to javaapp)
    - Direct service ports (for debugging):
       - Main App (Java, host-mapped): http://localhost:8079
       - Updater (host-mapped): http://localhost:8087

### TLS with Caddy (Automatic HTTPS)

This stack includes Caddy as an edge proxy that terminates TLS and obtains/renews certificates automatically.

1. Create a `.env` file next to `docker-compose.yml` with your domain and email:
   ```env
   CADDY_DOMAIN=habits.example.com
   CADDY_EMAIL=you@example.com
   # Optional during first tests to avoid LE rate limits:
   # ACME_CA=https://acme-staging-v02.api.letsencrypt.org/directory
   ```

2. Point your DNS A/AAAA records for the domain to this server's public IP.

3. Start the stack (Caddy is the default public entrypoint on 80/443):
   ```powershell
   docker compose up -d --build
   docker compose logs -f caddy
   ```

4. Browse to:
   - https://habits.example.com (automatic redirect from HTTP)

Notes:
- Caddy is now the sole edge proxy and will automatically obtain/renew certificates.
- If DNS isn’t set yet, Caddy still serves HTTP on :80 and proxies to the app.
- X-Forwarded-Proto is preserved end-to-end so Spring Boot generates correct HTTPS URLs behind proxies.

### Production TLS checklist (Namecheap + Oracle Cloud)

1. DNS (Namecheap → Domain List → Manage → Advanced DNS):
   - A @ → <your Oracle VM public IPv4>
   - CNAME www → @
   - TTL: Automatic

2. Oracle Cloud networking:
   - Open inbound TCP ports 80 and 443 in your VCN Security List or NSG.
   - Ensure the instance OS firewall allows 80/443 (ufw/firewalld/Windows Firewall).

3. Watch Caddy issue certs automatically:
   ```powershell
   docker compose logs -f caddy
   ```

4. Optional (initial testing):
   - Use Let's Encrypt staging to avoid rate limits once while validating DNS and reachability.
   - Uncomment the `acme_ca` line in `caddy/Caddyfile`, reload Caddy, then comment it again for production.

### Manual Setup

If you prefer manual setup:

```bash
# Set your timezone environment variable
$env:HOST_TIMEZONE = "America/New_York"  # Replace with your timezone

# Build and start services
docker-compose build
docker-compose up -d
```

### Environment Variables

The application uses the following environment variables:

- `HOST_TIMEZONE`: Your local timezone (auto-detected by script)
- `MONGO_URI`: MongoDB connection string
- `TZ`: Container timezone setting

---

## Database Schema

### Collections

#### 1. `habits`
```json
{
  "_id": "ObjectId",
  "id": "Integer (auto-increment)",
  "name": "String",
  "startDate": "LocalDate",
  "endDate": "LocalDate (optional)",
  "curDate": "LocalDate",
  "frequency": "Integer (days between occurrences)",
  "streak": "Integer",
  "active": "Boolean",
  "_class": "habitTracker.Habit.Habit"
}
```

#### 2. `habitStructure`
```json
{
  "_id": "ObjectId",
  "habitId": "Integer",
  "structureDate": "LocalDate",
  "completed": "Boolean",
  "_class": "habitTracker.Structure.HabitStructure"
}
```

#### 3. `last_run_date`
```json
{
  "_id": "LocalDate",
  "_class": "updater.models.LastRunDate"
}
```

### Database Relationships

- **habits** ↔ **habitStructure**: One-to-many relationship via `habitId`
- **last_run_date**: Singleton collection tracking updater execution

---

## API Endpoints

### HabitTracker Service (Port 8089)

#### Web Pages
- `GET /` - Home page with habit list
- `GET /habit/{id}` - Individual habit details
- `GET /add` - Add new habit form
- `GET /edit/{id}` - Edit habit form

#### API Endpoints
- `POST /add` - Create new habit
- `PUT /edit/{id}` - Update existing habit
- `DELETE /delete/{id}` - Delete habit
- `POST /complete/{habitId}` - Mark habit as completed for today
- `POST /uncomplete/{habitId}` - Mark habit as incomplete for today

#### Data Endpoints
- `GET /habits` - Get all habits (JSON)
- `GET /habit/{id}/data` - Get specific habit data (JSON)

### Updater Service (Port 8087)

The updater service primarily runs scheduled tasks and doesn't expose public endpoints. It operates through:
- `@PostConstruct` method on startup
- `@Scheduled` method daily at 12:05 AM

---

## Frontend

### Technology Stack
- **Template Engine**: Thymeleaf
- **Styling**: CSS3 with responsive design
- **JavaScript**: Vanilla JS for interactivity
- **UI Framework**: Custom responsive grid system

### Key Features

#### Responsive Design
- Mobile-first approach
- Flexible grid layout
- Touch-friendly buttons and controls

#### Interactive Elements
- **Habit Completion**: Click to toggle completion status
- **Real-time Updates**: Immediate visual feedback
- **Dynamic Streaks**: Live streak counter updates
- **Form Validation**: Client-side and server-side validation

#### User Experience
- **Intuitive Interface**: Clean, minimalist design
- **Visual Feedback**: Color-coded completion status
- **Progress Tracking**: Visual representation of streaks
- **Easy Navigation**: Simple, logical page flow

### Page Structure

#### Home Page (`/`)
- Displays all active habits
- Shows current streaks
- Quick completion toggle buttons
- Add new habit button

#### Habit Details (`/habit/{id}`)
- Detailed habit information
- Completion history
- Edit and delete options
- Streak analytics

#### Add/Edit Forms (`/add`, `/edit/{id}`)
- Form validation
- Date pickers
- Frequency selection
- Active/inactive toggle

---

## Development

### Project Structure
```
HabitTracker/
├── habitTracker/           # Main Spring Boot application
│   ├── src/main/java/
│   │   └── habitTracker/
│   │       ├── Habit/      # Habit entity and service
│   │       ├── Structure/  # HabitStructure entity and service
│   │       └── controllers/
│   ├── src/main/resources/
│   │   ├── templates/      # Thymeleaf templates
│   │   └── static/         # CSS, JS, images
│   └── Dockerfile
│
├── updater/               # Background update service
│   ├── src/main/java/updater/
│   │   ├── services/      # Refactored service classes
│   │   └── models/        # Data models
│   └── Dockerfile
│
├── backup/                # Backup service
│   ├── backup_script.py
│   ├── requirements.txt
│   └── Dockerfile
│
├── docker-compose.yml
└── docker-compose-runner-v1.ps1
```

### Development Workflow

1. **Local Development**:
   ```bash
   # Start MongoDB only
   docker-compose up mongodbHabit -d
   
   # Run applications locally for development
   cd habitTracker && mvn spring-boot:run
   cd updater && mvn spring-boot:run
   ```

2. **Testing Changes**:
   ```bash
   # Rebuild specific service
   docker-compose build habittracker
   docker-compose up habittracker -d
   ```

3. **Database Access**:
   ```bash
   # Connect to MongoDB
   docker exec -it mongodbHabit mongosh -u root -p example
   ```

### Key Development Principles

#### Updater Service Refactoring
The updater service has been refactored following these principles:

- **Single Responsibility**: Each class has one clear purpose
- **Dependency Injection**: Clear dependencies between services
- **Testability**: Each service can be unit tested independently
- **Maintainability**: Changes to one aspect don't affect others
- **Error Handling**: Specific error handling per service

#### Service Classes:
1. **UpdateScheduler**: Main coordination and scheduling
2. **LastRunDateService**: Execution tracking
3. **HabitUpdateService**: Habit date management
4. **HabitDateCalculator**: Date calculation logic
5. **HabitStructureManager**: Structure creation
6. **StreakCalculationService**: Streak computation

---

## Backup System

### Overview
Automated backup system that dumps MongoDB data to Google Drive on a scheduled basis.

### Features
- **Scheduled Execution**: Configurable backup frequency
- **Google Drive Integration**: Secure cloud storage
- **Compression**: Efficient data storage
- **Error Handling**: Comprehensive error logging
- **Incremental Backups**: Timestamp-based backup naming

### Configuration
The backup system requires Google Drive API credentials (not included in repository for security).

### Backup Process
1. **MongoDB Dump**: Export collections to JSON
2. **Compression**: Create compressed archive
3. **Upload**: Secure upload to Google Drive
4. **Cleanup**: Remove local temporary files
5. **Logging**: Record backup status and metrics

---

## Configuration

### Docker Compose Configuration

#### Services Configuration
- **MongoDB**: Persistent volume, authentication enabled
- **HabitTracker**: Port 8089, depends on MongoDB
- **Updater**: Port 8087, scheduled execution
- **Backup**: Depends on MongoDB, Google Drive integration

#### Environment Variables
- `MONGO_URI`: Database connection string
- `TZ`: Timezone configuration (auto-detected)
- `JAVA_TOOL_OPTIONS`: JVM timezone settings

### Timezone Handling
The application automatically detects and configures timezone settings:

1. **Detection**: PowerShell script detects Windows timezone
2. **Mapping**: Converts to Linux/IANA timezone format
3. **Configuration**: Sets environment variables for all containers
4. **Consistency**: Ensures all services use the same timezone

### Supported Timezones
The system supports major timezones including:
- **North America**: EST, CST, MST, PST, etc.
- **Europe**: GMT, CET, EET, etc.
- **Asia**: JST, CST, IST, etc.
- **Australia/Pacific**: AEST, NZST, etc.
- **Others**: Various global timezones

---

## Troubleshooting

### Common Issues

#### 1. Port Conflicts
If ports are already in use:
```bash
# Check what's using the ports
netstat -ano | findstr :8089
netstat -ano | findstr :8087
netstat -ano | findstr :27017

# Kill processes or change ports in docker-compose.yml
```

#### 2. Database Connection Issues
```bash
# Check MongoDB logs
docker logs mongodbHabit

# Verify connection
docker exec -it mongodbHabit mongosh -u root -p example
```

#### 3. Timezone Issues
```bash
# Verify timezone setting
docker exec -it updater date
docker exec -it javaapp date

# Check environment variables
docker exec -it updater printenv | grep TZ
```

#### 4. Build Failures
```bash
# Clean rebuild
docker-compose down
docker system prune -f
docker-compose build --no-cache
docker-compose up
```

### Logs and Monitoring
```bash
# View all logs
docker-compose logs -f

# View specific service logs
docker-compose logs -f updater
docker-compose logs -f javaapp
docker-compose logs -f mongodbHabit
```

---

## Contributing

### Development Setup
1. Fork the repository
2. Create a feature branch
3. Make changes following the established patterns
4. Test locally using Docker Compose
5. Submit a pull request

### Code Style
- Follow Spring Boot conventions
- Use meaningful variable and method names
- Include comprehensive comments
- Follow the refactored service pattern for new features

### Testing
- Write unit tests for new services
- Test database operations
- Verify timezone handling
- Test Docker container builds

---

## License

This project is open source. Please ensure you don't include sensitive credentials (like Google API keys) when contributing or sharing.

---

## Security Notes

### Credentials Management
- Google Drive API credentials are not included in the repository
- MongoDB credentials are for development only
- Change default passwords in production environments
- Use environment variables for sensitive configuration

### Best Practices
- Regular security updates for dependencies
- Proper error handling to avoid information disclosure
- Input validation and sanitization
- Secure database connections

---

## Future Enhancements

### Planned Features
- User authentication and multi-user support
- Habit categories and tags
- Advanced analytics and reporting
- Mobile app development
- Social features and habit sharing
- Integration with fitness trackers
- Custom habit frequency patterns
- Habit templates and presets

### Technical Improvements
- Comprehensive test suite
- Performance monitoring
- Enhanced error handling
- API rate limiting
- Caching implementation
- Database optimization
- Security enhancements