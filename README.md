# Creator Link Store API

Spring Boot API for the sectioned creator admin, public storefront, products, customers, orders, analytics, integrations, and automation metadata.

The code is a traditional layered Spring Boot modular monolith:

```text
controller -> service -> repository -> PostgreSQL
                     -> provider strategy/client -> Razorpay, Stripe, Instagram
```

`CreatorStoreApplication` is only the entry point. Domain-specific REST controllers contain routing, services contain validation/orchestration, repositories contain parameterized SQL, DTO records define request contracts, and integration adapters isolate provider HTTP calls. The infrastructure repository's `docs/backend-architecture.md` contains the full package/controller map.

```bash
docker compose up -d db
mvn spring-boot:run
```

It listens on port 8080 and its inferred PostgreSQL schema is initialized from `src/main/resources/schema.sql`. A clean database seeds the deterministic `alex` demo. This schema and its response bodies are original project contracts, not a claim about Stan's private database or responses.

India launch defaults are `INR`, Razorpay as the preferred strategy, and both payments and Instagram disabled. `GET /api/v1/payments/config` and `GET /api/v1/integrations/instagram/config` report safe readiness without secrets. Provider modes are `disabled`, `test`, and `live`; use test credentials locally and store deployed secrets in AWS Secrets Manager. Checkout amounts are always loaded server-side and expressed as integer paise in `*_subunits` fields.

For the complete three-container workflow and API reference, start with the [infrastructure repository](https://github.com/kvsram/creator-link-store-infrastructure). Its bootstrap script clones all missing siblings, validates the laptop, starts the three containers, and runs the supported-contract smoke test. Its feature-parity matrix is the source of truth for what is complete versus a route/schema foundation.

## Regional Kubernetes deployment

`deploy/overlays/region-a`, `region-b`, and `region-c` target three independent Kubernetes clusters. Each deploys one API replica initially, with an HPA that can grow to three replicas in that region. The application remains stateless; PostgreSQL is an external managed service, not a Pod in every cluster.

Create the `creator-store-db` Secret in each cluster using its private managed-PostgreSQL endpoint. It is deliberately not generated from the placeholder template by Kustomize. For the first low-scale phase, use one active writer region and keep another region warm; the current API has no read/write datasource split and arbitrary cross-region writes are unsafe. Do not commit a real database password: create the Secret through External Secrets or an equivalent cloud secret-manager integration.

The integration ConfigMap deliberately keeps `PAYMENTS_MODE` and `INSTAGRAM_MODE` disabled. Create `creator-store-runtime-secrets` through External Secrets before switching a stage to `test`. `deploy/base/integration-secret.template.yaml` documents names only and is intentionally excluded from Kustomize.

GitHub Actions builds an immutable GHCR image on every `main` push. Manual promotion uses protected GitHub Environments `dev`, `preprod`, and `prod`, short-lived AWS OIDC credentials, and a self-hosted runner labeled `aws-private` that can reach the private EKS API. Configure the regional variables documented in the infrastructure repository and trigger **Deploy backend** with the exact 40-character image commit SHA plus the exact infrastructure SHA already applied to that stage. The workflow verifies the SSM release contract, copies the image to environment ECR, resolves the RDS managed secret, materializes `creator-store-db`, and then rolls out. No kubeconfig or database value is stored in GitHub.
