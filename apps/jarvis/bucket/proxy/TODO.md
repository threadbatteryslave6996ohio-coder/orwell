# TODO

- Add infrastructure-specific adapter smoke tests for AWS and Azure upload flows.
- Keep those tests out of the default `mvn test` path; run them from a separate profile, script, or CI job that can supply real credentials and backing infrastructure.
