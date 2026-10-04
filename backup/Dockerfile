# Stage 1: Get MongoDB tools from official image
FROM mongo:7.0-jammy as mongo-tools

# Stage 1b: Node runtime, copied into the final image (avoids a nodesource apt setup)
FROM node:20-bookworm-slim as node-bin

# Stage 2: Build final image
# Pinned to bookworm on purpose: an unpinned python:3.10-slim floated to Debian 13
# (trixie) and silently broke mongodump, which links against libgssapi_krb5.so.2.
FROM python:3.10-slim-bookworm

# mongodump/mongorestore come from an Ubuntu image; libgssapi-krb5-2 supplies the
# shared library they link against, which the slim image does not ship.
COPY --from=mongo-tools /usr/bin/mongodump /usr/bin/mongodump
COPY --from=mongo-tools /usr/bin/mongorestore /usr/bin/mongorestore

RUN apt-get update && apt-get install -y --no-install-recommends zip libgssapi-krb5-2 \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

# Node + Claude Code CLI for the internal /agent/plan endpoint (claude -p).
COPY --from=node-bin /usr/local/bin/node /usr/local/bin/node
COPY --from=node-bin /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
    && npm install -g @anthropic-ai/claude-code \
    && npm cache clean --force

# Unbuffered stdout: without this, backup.py's failure messages sit in the block
# buffer and never reach `docker logs`, so a broken backup looks like a silent one.
ENV PYTHONUNBUFFERED=1

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY backup.py .
COPY mcp_server.py .
COPY internal_api.py .
COPY claude_cli.py .
COPY trello_mcp/ ./trello_mcp/
COPY start.sh .

CMD ["sh", "start.sh"]
