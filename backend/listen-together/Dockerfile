FROM node:24-alpine

ENV NODE_ENV=production \
    PORT=7860

WORKDIR /app
COPY --chown=node:node package.json ./
RUN npm install --omit=dev --no-audit --no-fund
COPY --chown=node:node server.mjs ./
USER node
EXPOSE 7860
CMD ["node", "server.mjs"]
