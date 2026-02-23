FROM cloudflare/cloudflared:latest

COPY config.yml /etc/cloudflared/config.yml
COPY credentials.json /etc/cloudflared/credentials.json

CMD ["tunnel", "--config", "/etc/cloudflared/config.yml", "run"]
