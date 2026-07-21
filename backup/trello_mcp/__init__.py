"""HabitTracker Trello MCP server, split into cohesive modules.

Import graph (no cycles):
    config  ← formatting
       ↑         ↑
     meta ───────┘        api ──► config, formatting
       ↑                   ↑
     graph ──► config, meta, formatting
    models (pydantic only)
    tools_cards / tools_planning ──► everything above + config.mcp (registers @mcp.tool)

mcp_server.py is the entrypoint: it imports every tool module (which registers the tools
on the shared `mcp`), keeps the cron loop, and calls mcp.run(). See FLOWS_mcp.md.
"""
