FROM bash:devel-alpine3.19

RUN apk add --no-cache curl jq

WORKDIR /app
COPY verify-group-chat.sh /app/verify-group-chat.sh
RUN chmod +x /app/verify-group-chat.sh

ENTRYPOINT ["/app/verify-group-chat.sh"]
