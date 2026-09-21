# ─────────────────────────────────────────────────────────────────────────────
# The bridge sidecar from docker-compose.mcp-sidecar.yml.
#
#   docker build -t eddi-mcp-filesystem:2025.8.21 ./mcp-sidecar
#
# WHY THIS FILE EXISTS AT ALL: the upstream bridge image is Python-on-Alpine and
# ships no Node runtime, so the `npx …` command this compose file used to pass it
# could never have started. And it could not have recovered by downloading one
# either — the sidecar sits on an `internal: true` network with no route off the
# host, which is the entire point of that network. So the MCP server has to be IN
# the image, installed at build time, and the container then runs it offline.
#
# Digest-pinned, the same rule EDDI applies to its own base image: a tag is
# mutable, a digest is the artifact you reviewed.
# TODO: re-pin after verifying the digest yourself; this one was current at the
#       time of writing, not blessed.
# ─────────────────────────────────────────────────────────────────────────────
FROM ghcr.io/sparfenyuk/mcp-proxy@sha256:ce037447191eeed517faa80640539dedaffb79a3ec107bfd23625a4438b0d06d

# Pin the SERVER version too. `npx -y pkg` without one resolves *latest* at every
# container start, which is a supply-chain change with no deploy behind it.
ARG MCP_SERVER_PACKAGE="@modelcontextprotocol/server-filesystem@2025.8.21"

USER root
RUN apk add --no-cache nodejs npm \
    && npm install -g "${MCP_SERVER_PACKAGE}" \
    && npm cache clean --force

# Same unprivileged id the compose file runs as, so the image is not usable as
# root even if someone drops the `user:` line.
USER 10001:10001
