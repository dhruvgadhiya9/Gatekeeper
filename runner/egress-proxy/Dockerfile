# A small forward proxy that gives a sandbox its only route out, limited to an allow-list.
FROM alpine:3.24.3@sha256:be2656061b536c1883c4034790e21d0a09c5aaaf51968b89368a3c0371ea2c9a
RUN apk add --no-cache tinyproxy=1.11.3-r0
COPY entrypoint.sh /entrypoint.sh
RUN chmod 0555 /entrypoint.sh
USER 65534:65534
ENTRYPOINT ["/entrypoint.sh"]
