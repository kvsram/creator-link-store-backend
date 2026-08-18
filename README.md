# Creator Link Store API

Spring Boot API for the sectioned creator admin, public storefront, products, customers, orders, analytics, integrations, and automation metadata.

```bash
docker compose up -d db
mvn spring-boot:run
```

It listens on port 8080 and its inferred PostgreSQL schema is initialized from `src/main/resources/schema.sql`. A clean database seeds the deterministic `alex` demo. This schema and its response bodies are original project contracts, not a claim about Stan's private database or responses.

For the complete three-container workflow and API reference, clone the infrastructure repository beside this repository as `infrastructure` and follow `infrastructure/README.md`.

## Regional Kubernetes deployment

`deploy/overlays/region-a`, `region-b`, and `region-c` target three independent Kubernetes clusters. Each deploys one API replica initially, with an HPA that can grow to three replicas in that region. The application remains stateless; PostgreSQL is an external managed service, not a Pod in every cluster.

Create the `creator-store-db` Secret in each cluster using its private managed-PostgreSQL endpoint. For the first low-scale phase, point all regions to one primary database in the closest region. Do not commit a real database password: replace the template with External Secrets or a cloud secret-manager integration.

GitHub Actions builds an immutable GHCR image on every `main` push. To enable manual deploys, create GitHub Environments named `region-a`, `region-b`, and `region-c`, each with a base64-encoded `KUBECONFIG_B64` secret for only its cluster. Trigger **Deploy backend** with the image commit SHA.
