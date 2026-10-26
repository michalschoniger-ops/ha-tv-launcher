FROM node:22.23.2-alpine
WORKDIR /opt/home-tv-launcher
COPY runtime/ ./
COPY startup.mjs ./startup.mjs
# Supervisor připojuje soukromé trvalé /data; přihlašovací údaje dodá při spuštění.
ENV NODE_ENV=production
EXPOSE 4317/tcp
CMD ["node", "/opt/home-tv-launcher/startup.mjs"]
