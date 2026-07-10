# Controllable MLLP sink for failover testing (zero-dependency Node).
FROM node:20-alpine
WORKDIR /app
COPY mllp-sink.js .
# Data ports (MLLP) + control port. Override DATA_PORTS/CONTROL_PORT as needed.
EXPOSE 6661 6662 9000
ENTRYPOINT ["node", "mllp-sink.js"]
