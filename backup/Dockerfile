# Stage 1: Get MongoDB tools from official image
FROM mongo:7.0-jammy as mongo-tools

# Stage 2: Build final image
FROM python:3.10-slim

# Copy MongoDB tools from official image
COPY --from=mongo-tools /usr/bin/mongodump /usr/bin/mongodump
COPY --from=mongo-tools /usr/bin/mongorestore /usr/bin/mongorestore

# Install only zip (no GPG key issues)
RUN apt-get update && apt-get install -y zip \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy files
COPY backup.py .
COPY requirements.txt .

# Install Python dependencies
RUN pip install --no-cache-dir -r requirements.txt

CMD ["python", "backup.py"]
