#!/bin/sh
python backup.py &
exec python mcp_server.py
