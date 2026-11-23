# Ride Booking App

Uber-style platform: bike, auto, car categories (incl. pooling) and parcel delivery, with scheduled rides, ride history, invoices and live driver tracking.

## Stack
- **Backend:** Java 21, Spring Boot 4.2 (Maven multi-module), Spring Security + OAuth2 (Spring Authorization Server), Spring Cloud Gateway, Spring Data JPA, Flyway, Resilience4j
- **Data:** MySQL 8 (one DB per service), Redis (GEO, locks, sessions, cache), Kafka (KRaft), MinIO/S3
- **Frontend:** React + TypeScript + Vite, Leaflet/OpenStreetMap, STOMP WebSocket
- **DevOps:** Docker, Compose, Kubernetes, GitHub Actions, Terraform, AWS, Prometheus/Grafana/Jaeger/Loki, k6

## Structure
```
.
├── pom.xml                 # parent + aggregator
├── services/
│   ├── common/             # shared events, error model, logging, masking
│   ├── gateway/            # JWT validation, rate limiting
│   ├── auth-service/       # OAuth2 Authorization Server, sessions, Google login
│   ├── user-service/       # rider/driver profiles
│   ├── booking-service/    # bookings, trips, state machine, scheduling, history
│   ├── location-service/   # driver GPS, Redis GEO
│   ├── matching-service/   # driver matching, locks, pooling
│   ├── pricing-service/    # fares, surge
│   ├── payment-service/    # payments, saga, idempotency
│   ├── invoice-service/    # PDF invoices
│   ├── notification-service/
│   └── tracking-service/   # WebSocket live tracking
├── frontend/{rider-app,driver-app}/
├── infra/{docker,k8s,terraform,monitoring}/
├── tools/{driver-simulator,k6}/
└── .github/workflows/
```
Each service uses layers `api → application → domain → infrastructure` (package root `com.ridebooking.<service>`).

## Build
```
mvn clean verify
```
Only `services/common` is registered as a module so far; new services are added to the root `<modules>` as they are built.

## Roadmap
1. Monorepo, Docker Compose (MySQL, Redis, Kafka, MinIO) ← Step 1.1 done
2. auth-service, gateway, user-service
3. booking-service (state machine, service types)
4. location-service + driver simulator
5. matching, Kafka flow, idempotency, DLQ
6. React apps + live map
7. Pricing, scheduled rides, history, pooling
8. Payment saga, outbox, invoices
9. Observability, Kubernetes, CI/CD, Terraform/AWS, load test
