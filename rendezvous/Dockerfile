# Build stage
FROM rust:1.95-slim AS build
WORKDIR /app
COPY Cargo.toml Cargo.lock* ./
COPY src ./src
RUN cargo build --release

# Runtime stage
FROM debian:bookworm-slim
RUN useradd --system --no-create-home jukz
COPY --from=build /app/target/release/jukz-rendezvous /usr/local/bin/jukz-rendezvous
USER jukz
ENV PORT=8080
EXPOSE 8080
CMD ["jukz-rendezvous"]
