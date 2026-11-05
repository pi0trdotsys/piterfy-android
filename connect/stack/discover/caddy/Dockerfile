# Caddy z dodatkiem replace-response — dopisuje do strony Navidrome własny wygląd i przycisk „Mój gust”.
FROM caddy:2.11.7-builder AS builder
RUN xcaddy build --with github.com/caddyserver/replace-response@v0.0.0-20250618171559-80962887e4c6

FROM caddy:2.11.7-alpine
COPY --from=builder /usr/bin/caddy /usr/bin/caddy
